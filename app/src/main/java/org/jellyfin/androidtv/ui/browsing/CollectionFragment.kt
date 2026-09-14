package org.jellyfin.androidtv.ui.browsing

import android.widget.Toast
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Row
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.HomeSectionsRepository
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.jellyfin.androidtv.ui.presentation.GridButtonPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.time.Instant

class CollectionFragment : EnhancedBrowseFragment() {
	private val homeSectionsRepository by inject<HomeSectionsRepository>()
	private val dataRefreshService by inject<DataRefreshService>()
	private var homePinAdapter: ArrayObjectAdapter? = null

	override fun setupQueries(rowLoader: RowLoader) {
		val movies = GetItemsRequest(
			fields = ItemRepository.itemFields,
			parentId = mFolder.id,
			includeItemTypes = setOf(BaseItemKind.MOVIE),
		)
		mRows.add(BrowseRowDef(getString(R.string.lbl_movies), movies, 100))

		val series = GetItemsRequest(
			fields = ItemRepository.itemFields,
			parentId = mFolder.id,
			includeItemTypes = setOf(BaseItemKind.SERIES),
		)
		mRows.add(BrowseRowDef(getString(R.string.lbl_tv_series), series, 100))

		val others = GetItemsRequest(
			fields = ItemRepository.itemFields,
			parentId = mFolder.id,
			excludeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
		)
		mRows.add(BrowseRowDef(getString(R.string.lbl_other), others, 100))

		rowLoader.loadRows(mRows)
	}

	/**
	 * A "Home sections" row with one button, pin or unpin, added once the server says a home
	 * section takes collections. The collection screen has no button bar, so this is where
	 * the library screens put their views too.
	 */
	override fun addAdditionalRows(rowAdapter: MutableObjectAdapter<Row>) {
		super.addAdditionalRows(rowAdapter)

		lifecycleScope.launch {
			val pinned = runCatching { withContext(Dispatchers.IO) { homeSectionsRepository.isPinnedToHome(mFolder) } }
				.onFailure { Timber.w(it, "Unable to read the home screen layout") }
				.getOrNull() ?: return@launch
			if (!isAdded) return@launch

			val adapter = ArrayObjectAdapter(GridButtonPresenter()).apply { add(homePinButton(pinned)) }
			homePinAdapter = adapter
			rowAdapter.add(ListRow(HeaderItem(rowAdapter.size().toLong(), getString(R.string.home_sections)), adapter))
		}
	}

	override fun setupEventListeners() {
		super.setupEventListeners()

		mClickedListener.registerListener(
			OnItemViewClickedListener { _, item, _, _ ->
				val button = (item as? GridButtonBaseRowItem)?.gridButton ?: item as? GridButton
				if (button?.id == HOME_PIN) toggleHomePin()
			}
		)
	}

	private fun toggleHomePin() {
		lifecycleScope.launch {
			runCatching { withContext(Dispatchers.IO) { homeSectionsRepository.toggleHomePin(mFolder) } }
				.onSuccess { pinned ->
					// The home screen misses the change push while this screen is open, so it
					// checks this when it comes back
					dataRefreshService.lastHomeLayoutChange = Instant.now()
					if (!isAdded) return@onSuccess
					homePinAdapter?.replace(0, homePinButton(pinned))
					val message = if (pinned) R.string.home_section_pinned else R.string.home_section_unpinned
					Toast.makeText(requireContext(), getString(message), Toast.LENGTH_SHORT).show()
				}
				.onFailure {
					Timber.e(it, "Unable to change the home screen layout")
					if (!isAdded) return@onFailure
					Toast.makeText(requireContext(), getString(R.string.home_section_error), Toast.LENGTH_SHORT).show()
				}
		}
	}

	private fun homePinButton(pinned: Boolean) =
		GridButton(HOME_PIN, getString(if (pinned) R.string.home_section_unpin else R.string.home_section_pin))

	private companion object {
		// Past the ids EnhancedBrowseFragment uses for its own view buttons
		const val HOME_PIN = 20
	}
}
