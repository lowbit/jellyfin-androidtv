package org.jellyfin.androidtv.ui.home

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.constant.HomeSectionKey
import org.jellyfin.androidtv.constant.LiveTvOption
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.data.repository.HomeSectionsRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.browsing.CompositeClickedListener
import org.jellyfin.androidtv.ui.browsing.CompositeSelectedListener
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.itemhandling.refreshItem
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.AudioEventListener
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.PositionableListRowPresenter
import org.jellyfin.androidtv.util.KeyProcessor
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.HomeSectionDto
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class HomeRowsFragment : RowsSupportFragment(), AudioEventListener, View.OnKeyListener {
	private val api by inject<ApiClient>()
	private val backgroundService by inject<BackgroundService>()
	private val playbackManager by inject<PlaybackManager>()
	private val mediaManager by inject<MediaManager>()
	private val notificationsRepository by inject<NotificationsRepository>()
	private val userRepository by inject<UserRepository>()
	private val userPreferences by inject<UserPreferences>()
	private val homeSectionsRepository by inject<HomeSectionsRepository>()
	private val dataRefreshService by inject<DataRefreshService>()
	private val customMessageRepository by inject<CustomMessageRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val itemLauncher by inject<ItemLauncher>()
	private val keyProcessor by inject<KeyProcessor>()

	// Data
	private var currentItem: BaseRowItem? = null
	private var currentRow: ListRow? = null
	private var justLoaded = true

	private val sectionRows = HomeSectionRows<Row>()
	private val refreshMutex = Mutex()
	private var lastRefresh: Instant? = null

	// Special rows
	private val notificationsRow by lazy { NotificationsHomeFragmentRow(lifecycleScope, notificationsRepository) }
	private val nowPlaying by lazy { HomeFragmentNowPlayingRow(lifecycleScope, playbackManager, mediaManager) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		adapter = MutableObjectAdapter<Row>(PositionableListRowPresenter())

		lifecycleScope.launch {
			withTimeout(30.seconds) {
				userRepository.currentUser.filterNotNull().first()
			}

			val cardPresenter = CardPresenter()
			notificationsRow.addToRowsAdapter(requireContext(), cardPresenter, rowsAdapter)
			nowPlaying.addToRowsAdapter(requireContext(), cardPresenter, rowsAdapter)

			refreshSections(allStale = true, staleKeys = emptyList())
		}

		onItemViewClickedListener = CompositeClickedListener().apply {
			registerListener(ItemViewClickedListener())
			registerListener(notificationsRow::onItemClicked)
		}

		onItemViewSelectedListener = CompositeSelectedListener().apply {
			registerListener(ItemViewSelectedListener())
		}

		customMessageRepository.message
			.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
			.onEach { message ->
				when (message) {
					CustomMessage.RefreshCurrentItem -> refreshCurrentItem()
					else -> Unit
				}
			}.launchIn(lifecycleScope)

		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				homeSectionsRepository.changes
					.throttleAllStale(ALL_STALE_INTERVAL)
					.onEach { change -> refreshSections(change.allStale, change.staleSectionKeys) }
					.launchIn(this)
			}
		}

		// Subscribe to Audio messages
		mediaManager.addAudioEventListener(this)
	}

	override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
		if (event?.action != KeyEvent.ACTION_UP) return false
		return keyProcessor.handleKey(keyCode, currentItem, activity)
	}

	override fun onResume() {
		super.onResume()

		//React to deletion
		if (currentRow != null && currentItem != null && currentItem?.baseItem != null && currentItem!!.baseItem!!.id == dataRefreshService.lastDeletedItemId) {
			(currentRow!!.adapter as ItemRowAdapter).remove(currentItem)
			currentItem = null
			dataRefreshService.lastDeletedItemId = null
		}

		if (!justLoaded) {
			//Re-retrieve anything that needs it but delay slightly so we don't take away gui landing
			refreshCurrentItem()
			refreshRows()
		} else {
			justLoaded = false
		}

		// Update audio queue
		Timber.i("Updating audio queue in HomeFragment (onResume)")
		nowPlaying.update(requireContext(), rowsAdapter)
	}

	override fun onQueueStatusChanged(hasQueue: Boolean) {
		if (activity == null || requireActivity().isFinishing) return

		Timber.i("Updating audio queue in HomeFragment (onQueueStatusChanged)")
		nowPlaying.update(requireContext(), rowsAdapter)
	}

	@Suppress("UNCHECKED_CAST")
	private val rowsAdapter get() = adapter as MutableObjectAdapter<Row>

	/**
	 * Fetches the layout once and redraws it. Rows whose section id survived are kept, so focus
	 * stays where it was, and are refreshed in place when their key is stale.
	 */
	private suspend fun refreshSections(allStale: Boolean, staleKeys: Collection<String>) = refreshMutex.withLock {
		refreshSectionsLocked(allStale, staleKeys)
	}

	private suspend fun refreshSectionsLocked(allStale: Boolean, staleKeys: Collection<String>) {
		lastRefresh = Instant.now()
		homeSectionsRepository.invalidate()

		val sections = withContext(Dispatchers.IO) {
			runCatching { homeSectionsRepository.getSections() }
				.onFailure { Timber.e(it, "Unable to load home sections") }
				.getOrDefault(emptyList())
		}

		if (context == null) return
		val cardPresenter = CardPresenter()
		val sectionsById = sections.associateBy { it.id }

		// An empty row removes itself, so a row is only kept while it is still on screen
		val kept = sectionRows.update(
			ids = homeSectionRowIds(sections),
			onScreen = { row -> rowsAdapter.indexOf(row) >= 0 },
			create = { id -> sectionsById[id]?.let { createRows(it, cardPresenter) } ?: createLibraryRows() },
		)

		// Rows that manage themselves stay in front
		val leading = rowsAdapter.filter { it === notificationsRow.row || it === nowPlaying.row }
		val target = leading + sectionRows.all
		val current = rowsAdapter.toSet()

		// Rows already on screen are moved or removed in one step; new rows follow one per frame,
		// so the first ones draw while the rest inflate
		rowsAdapter.replaceAll(
			items = target.filter { it in current },
			areItemsTheSame = { old, new -> old === new },
			areContentsTheSame = { old, new -> old === new },
		)
		target.forEachIndexed { index, row ->
			if (row in current) return@forEachIndexed
			val previous = target.getOrNull(index - 1)
			rowsAdapter.add(if (previous == null) 0 else rowsAdapter.indexOf(previous) + 1, row)
			awaitFrame()
		}

		// Rows kept from the previous layout refresh in place
		for (id in kept) {
			for (row in sectionRows[id]) {
				val rowAdapter = (row as? ListRow)?.adapter as? ItemRowAdapter ?: continue
				val key = rowAdapter.homeSection?.key ?: continue
				if (allStale || key in staleKeys) rowAdapter.Retrieve()
			}
		}
	}

	private fun createLibraryRows(): List<Row> = listOf(HomeFragmentViewsRow(small = false).createRow(requireContext(), rowsAdapter))

	private fun createRows(section: HomeSectionDto, cardPresenter: CardPresenter): List<Row> = when (section.key) {
		HomeSectionKey.SMALL_LIBRARY_TILES -> listOf(HomeFragmentViewsRow(small = false).createRow(requireContext(), rowsAdapter))
		HomeSectionKey.LIBRARY_BUTTONS -> listOf(HomeFragmentViewsRow(small = true).createRow(requireContext(), rowsAdapter))
		HomeSectionKey.LIVE_TV -> {
			val buttons = HomeFragmentLiveTVRow(requireActivity(), userRepository).createRow()
			val onNow = HomeFragmentServerSectionRow(section, userPreferences).createRow(requireContext(), cardPresenter, rowsAdapter)
			// The On Now row removes the buttons row with itself when it is empty
			(onNow.adapter as ItemRowAdapter).setSiblingRow(buttons)
			listOf(buttons, onNow)
		}

		else -> listOf(HomeFragmentServerSectionRow(section, userPreferences).createRow(requireContext(), cardPresenter, rowsAdapter))
	}

	/**
	 * Every server row depends on the same request, so when anything changed while the screen
	 * was away the layout is refetched once instead of row by row.
	 */
	private fun refreshRows() {
		lifecycleScope.launch {
			delay(1.5.seconds)

			val since = lastRefresh ?: return@launch
			val changed = listOfNotNull(
				dataRefreshService.lastLibraryChange,
				dataRefreshService.lastPlayback,
				dataRefreshService.lastFavoriteUpdate,
			).any { it.isAfter(since) }

			if (changed) refreshSections(allStale = true, staleKeys = emptyList())
		}
	}

	private fun refreshCurrentItem() {
		val adapter = currentRow?.adapter as? ItemRowAdapter ?: return
		val item = currentItem ?: return

		Timber.i("Refresh item ${item.getFullName(requireContext())}")
		adapter.refreshItem(api, this, item)
	}

	override fun onDestroy() {
		super.onDestroy()

		mediaManager.removeAudioEventListener(this)
	}

	private inner class ItemViewClickedListener : OnItemViewClickedListener {
		override fun onItemClicked(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?,
			row: Row?,
		) {
			if (item is GridButton) {
				when (item.id) {
					LiveTvOption.LIVE_TV_GUIDE_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvGuide)
					LiveTvOption.LIVE_TV_SCHEDULE_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvSchedule)
					LiveTvOption.LIVE_TV_RECORDINGS_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvRecordings)
					LiveTvOption.LIVE_TV_SERIES_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvSeriesRecordings)
				}
			}

			if (item !is BaseRowItem) return
			if (row !is ListRow) return
			@Suppress("UNCHECKED_CAST")
			itemLauncher.launch(item, row.adapter as MutableObjectAdapter<Any>, requireContext())
		}
	}

	private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
		override fun onItemSelected(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?,
			row: Row?,
		) {
			if (item !is BaseRowItem) {
				currentItem = null
				//fill in default background
				backgroundService.clearBackgrounds()
			} else {
				currentItem = item
				currentRow = row as ListRow

				val itemRowAdapter = row.adapter as? ItemRowAdapter
				itemRowAdapter?.loadMoreItemsIfNeeded(itemRowAdapter.indexOf(item))

				backgroundService.setBackground(item.baseItem)
			}
		}
	}

	companion object {
		// The SDK subscribes at a fixed one second interval, web asks the server for five
		private val ALL_STALE_INTERVAL = 5.seconds
	}
}
