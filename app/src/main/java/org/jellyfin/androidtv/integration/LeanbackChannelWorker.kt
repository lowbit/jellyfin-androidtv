package org.jellyfin.androidtv.integration

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms
import androidx.tvprovider.media.tv.WatchNextProgram
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.HomeSectionKey
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.integration.provider.ImageProvider
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.startup.StartupActivity
import org.jellyfin.androidtv.util.AndroidVersion
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.androidtv.util.dp
import org.jellyfin.androidtv.util.sdk.isUsable
import org.jellyfin.androidtv.util.stripHtml
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.api.client.extensions.homeSectionsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.HomeSectionDto
import org.jellyfin.sdk.model.api.HomeSectionViewType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.extensions.ticks
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Manages channels on the android tv home screen. Each row of the app's home screen gets a channel,
 * in the same order, so a row added on the server or by a plugin shows up there too.
 *
 * More info: https://developer.android.com/training/tv/discovery/recommendations-channel.
 */
class LeanbackChannelWorker(
	private val context: Context,
	workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams), KoinComponent {
	companion object {
		private const val PERIODIC_UPDATE_REQUEST_NAME = "LeanbackChannelPeriodicUpdateRequest"
		private const val CHANNEL_STORE = "leanback_channels"
		private const val SECTION_CHANNEL_PREFIX = "section:"

		// A launcher row is browsed a few cards deep, the whole row is one press away in the app
		private const val CHANNEL_ITEM_LIMIT = 20

		suspend fun enqueue(workManager: WorkManager) {
			workManager.enqueueUniquePeriodicWork(
				PERIODIC_UPDATE_REQUEST_NAME,
				ExistingPeriodicWorkPolicy.UPDATE,
				PeriodicWorkRequestBuilder<LeanbackChannelWorker>(1, TimeUnit.HOURS)
					.setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
					.build()
			).await()
		}
	}

	private val api by inject<ApiClient>()
	private val userPreferences by inject<UserPreferences>()
	private val userViewsRepository by inject<UserViewsRepository>()
	private val imageHelper by inject<ImageHelper>()

	/**
	 * Check if the app can use Leanback features and is API level 26 or higher.
	 */
	private val isSupported = AndroidVersion.isAtLeastO &&
		// Check for leanback support
		context.packageManager.hasSystemFeature("android.software.leanback")
		// Check for "android.media.tv" provider to workaround a false-positive in the previous check
		&& context.packageManager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null

	/**
	 * Update all channels for the currently authenticated user.
	 */
	override suspend fun doWork(): Result = when {
		// Fail when not supported
		!isSupported -> Result.failure()
		// Retry later if no authenticated user is found
		!api.isUsable -> Result.retry()
		else -> try {
			// Get next up episodes
			val (resumeItems, nextUpItems) = getNextUpItems()
			// Get the rows of the home screen
			val sections = getSections()
			// Delete current items from the channels
			context.contentResolver.delete(TvContractCompat.PreviewPrograms.CONTENT_URI, null, null)

			val preferParentThumb = userPreferences[UserPreferences.seriesThumbnailsEnabled]
			val channelNames = mutableSetOf<String>()

			sections.forEachIndexed { index, section ->
				val name = SECTION_CHANNEL_PREFIX + section.id
				channelNames += name

				val channel = getChannelUri(
					name, Channel.Builder()
						.setType(TvContractCompat.Channels.TYPE_PREVIEW)
						.setDisplayName(section.displayText)
						.setInternalProviderId(section.id)
						.setAppLinkIntent(Intent(context, StartupActivity::class.java))
						.build(),
					default = index == 0
				)

				if (channel == null) {
					Timber.e("Skipping channel because it was not available")
				} else {
					section.items.map { item ->
						createPreviewProgram(
							channel,
							item,
							preferParentThumb,
							section.viewType
						)
					}.let {
						context.contentResolver.bulkInsert(
							TvContractCompat.PreviewPrograms.CONTENT_URI,
							it.toTypedArray()
						)
					}
				}
			}
			removeChannels(keep = channelNames)
			updateWatchNext(resumeItems + nextUpItems)

			// Success!
			Result.success()
		} catch (err: TimeoutException) {
			Timber.w(err, "Server unreachable, trying again later")

			Result.retry()
		} catch (err: ApiClientException) {
			Timber.e(err, "SDK error, trying again later")

			Result.retry()
		}
	}

	/**
	 * Get the uri for a channel or create it if it doesn't exist. Uses the [settings] parameter to
	 * update or create the channel. The [name] parameter is used to store the id and should be
	 * unique.
	 */
	private fun getChannelUri(name: String, settings: Channel, default: Boolean = false): Uri? {
		val store = context.getSharedPreferences(CHANNEL_STORE, Context.MODE_PRIVATE)
		var uri: Uri? = null

		// Try and re-use our existing channel definition
		if (store.contains(name)) {
			uri = store.getString(name, null)?.toUri()

			if (uri != null) {
				val result = context.contentResolver.update(uri, settings.toContentValues(), null, null)
				// If we did not affect exactly 1 row there might be something wrong, so recreate it
				if (result != 1) uri = null
			}
		}

		if (uri == null) {
			// Create new channel
			uri = context.contentResolver.insert(
				TvContractCompat.Channels.CONTENT_URI,
				settings.toContentValues()
			)

			// Set as default row to display (we can request one row to automatically be added to the home screen)
			if (uri != null && default) {
				TvContractCompat.requestChannelBrowsable(context, ContentUris.parseId(uri))
			}

			// Save uri to shared preferences
			store.edit { putString(name, uri?.toString()) }
		}

		// Update logo
		if (uri != null) {
			ResourcesCompat.getDrawable(context.resources, R.mipmap.app_icon, context.theme)?.let {
				ChannelLogoUtils.storeChannelLogo(
					context,
					ContentUris.parseId(uri),
					it.toBitmap(80.dp(context), 80.dp(context))
				)
			}
		}

		return uri
	}

	/**
	 * Deletes the channels of rows that are no longer on the home screen, including the fixed
	 * channels this worker created before its rows came from the server.
	 */
	private fun removeChannels(keep: Set<String>) {
		val store = context.getSharedPreferences(CHANNEL_STORE, Context.MODE_PRIVATE)
		val stale = store.all.keys - keep

		stale.forEach { name ->
			store.getString(name, null)?.toUri()?.let { context.contentResolver.delete(it, null, null) }
		}
		store.edit { stale.forEach { remove(it) } }
	}

	/**
	 * Gets the rows of the user's home screen that have something to show. The live TV row is left
	 * out, as it always was here, and the library row keeps the libraries the app can browse.
	 */
	private suspend fun getSections(): List<HomeSectionDto> =
		// The launcher shows the description, the home screen does not, so only this asks for it
		api.homeSectionsApi.getHomeSections(itemLimit = CHANNEL_ITEM_LIMIT, fields = listOf(ItemFields.OVERVIEW)).content
			.filter { section -> section.key != HomeSectionKey.LIVE_TV }
			.map { section ->
				if (section.key !in HomeSectionKey.libraryKeys) section
				else section.copy(items = section.items.filter { userViewsRepository.isSupported(it.collectionType) })
			}
			.filter { section -> section.items.isNotEmpty() }

	/**
	 * Gets the poster art for an item. Uses the [preferParentThumb] parameter to fetch the series
	 * image when preferred, and a wide image for a film or series on a [landscape] card.
	 */
	private fun BaseItemDto.getPosterArtImageUrl(
		preferParentThumb: Boolean,
		landscape: Boolean = false,
	): Uri = when {
		landscape && (type == BaseItemKind.MOVIE || type == BaseItemKind.SERIES) ->
			itemImages[ImageType.THUMB] ?: itemBackdropImages.firstOrNull() ?: itemImages[ImageType.PRIMARY]
		type == BaseItemKind.MOVIE || type == BaseItemKind.SERIES -> itemImages[ImageType.PRIMARY]
		(preferParentThumb || !itemImages.contains(ImageType.PRIMARY)) && parentImages.contains(ImageType.THUMB) -> parentImages[ImageType.THUMB]
		else -> itemImages[ImageType.PRIMARY]
	}.let { image ->
		ImageProvider.getImageUri(image?.getUrl(api) ?: imageHelper.getResourceUrl(context, R.drawable.tile_land_tv))
	}

	/**
	 * Gets the resume and next up episodes. The returned pair contains two lists:
	 * 1. resume items
	 * 2. next up items
	 */
	private suspend fun getNextUpItems(): Pair<List<BaseItemDto>, List<BaseItemDto>> =
		withContext(Dispatchers.IO) {
			val resume = async {
				api.libraryApi.getResumeItems(
					fields = ItemRepository.itemFields,
					imageTypeLimit = 1,
					limit = 10,
					mediaTypes = listOf(MediaType.VIDEO),
					includeItemTypes = listOf(BaseItemKind.EPISODE, BaseItemKind.MOVIE),
					excludeActiveSessions = true,
				).content.items
			}

			val nextUp = async {
				api.showApi.getNextUp(
					imageTypeLimit = 1,
					limit = 10,
					enableResumable = false,
					fields = ItemRepository.itemFields,
				).content.items
			}

			// Concat
			Pair(resume.await(), nextUp.await())
		}

	@SuppressLint("RestrictedApi")
	private fun createPreviewProgram(
		channelUri: Uri,
		item: BaseItemDto,
		preferParentThumb: Boolean,
		viewType: HomeSectionViewType,
	): ContentValues {
		val imageUri = item.getPosterArtImageUrl(preferParentThumb, viewType == HomeSectionViewType.LANDSCAPE)
		val seasonString = item.parentIndexNumber?.toString().orEmpty()

		val episodeString = when {
			item.indexNumberEnd != null && item.indexNumber != null ->
				"${item.indexNumber}-${item.indexNumberEnd}"

			else -> item.indexNumber?.toString().orEmpty()
		}

		return PreviewProgram.Builder()
			.setChannelId(ContentUris.parseId(channelUri))
			.setType(
				when (item.type) {
					BaseItemKind.SERIES -> WatchNextPrograms.TYPE_TV_SERIES
					BaseItemKind.MOVIE -> WatchNextPrograms.TYPE_MOVIE
					BaseItemKind.EPISODE -> WatchNextPrograms.TYPE_TV_EPISODE
					BaseItemKind.AUDIO -> WatchNextPrograms.TYPE_TRACK
					BaseItemKind.PLAYLIST -> WatchNextPrograms.TYPE_PLAYLIST
					else -> WatchNextPrograms.TYPE_CHANNEL
				}
			)
			.setTitle(item.seriesName ?: item.name)
			.setEpisodeTitle(if (item.type == BaseItemKind.EPISODE) item.name else null)
			.setDescription(item.overview?.stripHtml())
			.setReleaseDate(
				if (item.premiereDate != null) DateTimeFormatter.ISO_DATE.format(item.premiereDate)
				else null
			)
			.setPosterArtUri(imageUri)
			.setPosterArtAspectRatio(
				when (viewType) {
					HomeSectionViewType.LANDSCAPE -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
					HomeSectionViewType.SQUARE -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1
					HomeSectionViewType.PORTRAIT -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_MOVIE_POSTER
				}
			)
			.setIntent(Intent(context, StartupActivity::class.java).apply {
				putExtra(StartupActivity.EXTRA_ITEM_ID, item.id.toString())
				putExtra(StartupActivity.EXTRA_ITEM_IS_USER_VIEW, item.type == BaseItemKind.COLLECTION_FOLDER)
			})
			.setDurationMillis(
				if (item.runTimeTicks?.ticks != null) {
					// If we are resuming, we need to show remaining time, cause GoogleTV
					// ignores setLastPlaybackPositionMillis
					val duration = item.runTimeTicks?.ticks ?: Duration.ZERO
					val playbackPosition = item.userData?.playbackPositionTicks?.ticks
						?: Duration.ZERO
					(duration - playbackPosition).inWholeMilliseconds.toInt()
				} else 0
			)
			.apply {
				if ((item.parentIndexNumber ?: 0) > 0)
					setSeasonNumber(seasonString, item.parentIndexNumber!!)
				if ((item.indexNumber ?: 0) > 0)
					setEpisodeNumber(episodeString, item.indexNumber!!)
			}.build().toContentValues()
	}

	/**
	 * Updates the "watch next" row with new and unfinished episodes. Does not include movies, music
	 * or other types of media. Uses the [nextUpItems] parameter to store items returned by a
	 * NextUpQuery().
	 */
	@SuppressLint("RestrictedApi")
	private fun updateWatchNext(nextUpItems: List<BaseItemDto>) {
		deletePrograms(nextUpItems)

		// Get current watch next state
		val currentWatchNextPrograms = getCurrentWatchNext()

		// Create all programs in nextUpItems but not in watch next
		val programsToAdd = nextUpItems
			.filter { next -> currentWatchNextPrograms.none { it.internalProviderId == next.id.toString() } }
		context.contentResolver.bulkInsert(
			WatchNextPrograms.CONTENT_URI,
			programsToAdd.map { item -> getBaseItemAsWatchNextProgram(item).toContentValues() }
				.toTypedArray())
	}

	/**
	 * Delete stale programs from the watch next row. Items that don't need to be touched are
	 * kept as is, so they keep their ordering in the watch next row.
	 */
	@SuppressLint("RestrictedApi")
	private fun deletePrograms(nextUpItems: List<BaseItemDto>) {
		// Retrieve current watch next row
		val currentWatchNextPrograms = getCurrentWatchNext()

		// Find all stale programs to delete
		val deletedByUser = currentWatchNextPrograms.filter { !it.isBrowsable }
		val noLongerInWatchNext =
			currentWatchNextPrograms.filter { (nextUpItems).none { next -> it.internalProviderId == next.id.toString() } }
		val continueWatching = currentWatchNextPrograms.filter { it.watchNextType == WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE }

		// Delete the programs
		(deletedByUser + noLongerInWatchNext + continueWatching)
			.forEach { context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(it.id), null, null) }
	}

	/**
	 * Retrieves the current watch next row state.
	 */
	@SuppressLint("RestrictedApi")
	private fun getCurrentWatchNext(): MutableList<WatchNextProgram> {
		val currentWatchNextPrograms: MutableList<WatchNextProgram> = mutableListOf()
		context.contentResolver.query(WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)
			.use { cursor ->
				if (cursor != null && cursor.moveToFirst()) {
					do {
						currentWatchNextPrograms.add(WatchNextProgram.fromCursor(cursor))
					} while (cursor.moveToNext())
				}
			}
		return currentWatchNextPrograms
	}

	/**
	 * Convert [BaseItemDto] to [WatchNextProgram]. Assumes the item type is "episode".
	 */
	@Suppress("RestrictedApi")
	private fun getBaseItemAsWatchNextProgram(item: BaseItemDto) =
		WatchNextProgram.Builder().apply {
			val preferParentThumb = userPreferences[UserPreferences.seriesThumbnailsEnabled]

			setInternalProviderId(item.id.toString())

			// Poster size & type
			if (item.type == BaseItemKind.EPISODE) {
				setType(WatchNextPrograms.TYPE_TV_EPISODE)
				setPosterArtAspectRatio(WatchNextPrograms.ASPECT_RATIO_16_9)
			} else if (item.type == BaseItemKind.MOVIE) {
				setType(WatchNextPrograms.TYPE_MOVIE)
				setPosterArtAspectRatio(WatchNextPrograms.ASPECT_RATIO_MOVIE_POSTER)
			}

			// Name and episode details
			if (item.seriesName != null) {
				setTitle(item.seriesName)
				setEpisodeTitle(item.name)

				item.indexNumber?.takeIf { it > 0 }?.let { setEpisodeNumber(it) }
				item.parentIndexNumber?.takeIf { it > 0 }?.let { setSeasonNumber(it) }
			} else {
				setTitle(item.name)
			}

			setDescription(item.overview?.stripHtml())

			// Poster
			setPosterArtUri(item.getPosterArtImageUrl(preferParentThumb))

			when {
				// User has started playing the episode
				(item.userData?.playbackPositionTicks ?: 0) > 0 -> {
					setWatchNextType(WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
					setLastPlaybackPositionMillis(item.userData!!.playbackPositionTicks.ticks.inWholeMilliseconds.toInt())
					// Use last played date to prioritize

					setLastEngagementTimeUtcMillis(
						item.userData?.lastPlayedDate?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
							?: Instant.now().toEpochMilli()
					)
				}
				// First episode of the season
				item.indexNumber == 1 -> {
					setWatchNextType(WatchNextPrograms.WATCH_NEXT_TYPE_NEW)
					setLastEngagementTimeUtcMillis(
						item.dateCreated?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
							?: Instant.now().toEpochMilli()
					)
				}
				// Default
				else -> {
					setWatchNextType(WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)
					setLastEngagementTimeUtcMillis(Instant.now().toEpochMilli())
				}
			}

			// Runtime has been determined
			item.runTimeTicks?.ticks?.let { setDurationMillis(it.inWholeMilliseconds.toInt()) }

			// Set intent to open the episode
			setIntent(Intent(context, StartupActivity::class.java).apply {
				putExtra(StartupActivity.EXTRA_ITEM_ID, item.id.toString())
			})
		}.build()
}
