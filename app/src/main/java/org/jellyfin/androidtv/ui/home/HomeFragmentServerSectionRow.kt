package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.itemhandling.setHomeSectionItems
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.model.api.HomeSectionDto
import org.jellyfin.sdk.model.api.HomeSectionViewType

/**
 * A row the server built. The heading and items come from the section, the card shape follows
 * [HomeSectionDto.viewType] the way the built-in rows were drawn before.
 */
class HomeFragmentServerSectionRow(
	private val section: HomeSectionDto,
	private val userPreferences: UserPreferences,
) {
	fun createRow(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>): ListRow {
		// Landscape rows (resume, next up) show the series thumb for episodes and keep one height
		val landscape = section.viewType == HomeSectionViewType.LANDSCAPE
		val preferParentThumb = landscape && userPreferences[UserPreferences.seriesThumbnailsEnabled]

		val rowAdapter = ItemRowAdapter(context, section, preferParentThumb, landscape, cardPresenter, rowsAdapter)
		val row = ListRow(HeaderItem(section.displayText), rowAdapter)
		rowAdapter.setRow(row)
		// The items came with the section, so the row draws without another request
		rowAdapter.setHomeSectionItems(section.items)
		return row
	}
}
