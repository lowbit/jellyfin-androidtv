package org.jellyfin.androidtv.ui.home

import org.jellyfin.androidtv.constant.HomeSectionKey
import org.jellyfin.sdk.model.api.HomeSectionDto

// Id for the library row added when the layout has none; no section id has a space
const val LIBRARY_FALLBACK_ROW_ID = "library fallback"

/**
 * The row ids to draw for a layout, in order. Libraries are only reachable from the home screen,
 * so a layout without a library row gets one first.
 */
fun homeSectionRowIds(sections: List<HomeSectionDto>): List<String> = buildList {
	if (sections.none { it.key in HomeSectionKey.libraryKeys }) add(LIBRARY_FALLBACK_ROW_ID)
	sections.mapTo(this) { it.id }
}

/**
 * The rows drawn for each section id, in layout order. On [update] the rows of an id that is
 * still in the layout and still on screen are kept, so a layout change only touches the rows
 * that changed and focus stays where it was. Rows that removed themselves are created again.
 */
class HomeSectionRows<R : Any> {
	private var rows = linkedMapOf<String, List<R>>()

	val all: List<R> get() = rows.values.flatten()

	operator fun get(id: String): List<R> = rows[id].orEmpty()

	/**
	 * Redraws for [ids] and returns the ids whose rows were kept.
	 */
	fun update(
		ids: List<String>,
		onScreen: (row: R) -> Boolean,
		create: (id: String) -> List<R>,
	): Set<String> {
		val previous = rows
		val next = linkedMapOf<String, List<R>>()
		val kept = mutableSetOf<String>()

		for (id in ids) {
			val existing = previous[id]?.takeIf { rows -> rows.all(onScreen) }
			if (existing != null) kept += id
			next[id] = existing ?: create(id)
		}

		rows = next
		return kept
	}
}
