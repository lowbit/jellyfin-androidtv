package org.jellyfin.androidtv.data.repository

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.HomeSectionConfigDto
import org.jellyfin.sdk.model.api.HomeSectionProviderDto
import java.util.UUID

// Pinning an item to the home screen: it becomes a row of the list section that takes its kind

/** The list section an item of this kind is pinned to, if the server offers one. */
fun List<HomeSectionProviderDto>.pinProviderFor(item: BaseItemDto): HomeSectionProviderDto? =
	firstOrNull { it.itemKind != null && it.allowsMultipleItems && it.itemKind == item.type }

/** Whether a layout shows the item as a row: in an active list section of its kind. */
fun List<HomeSectionConfigDto>.isPinned(key: String, itemId: UUID): Boolean =
	any { it.key == key && it.active && itemId in it.itemIds }

/**
 * Adds an item as the last row of its list section, unhiding the section, or adds that section
 * at the end of the layout when there is none.
 */
fun List<HomeSectionConfigDto>.withItemPinned(key: String, itemId: UUID): List<HomeSectionConfigDto> {
	if (none { it.key == key }) return this + HomeSectionConfigDto(key = key, itemIds = listOf(itemId), maxItems = null, active = true)

	return map { section ->
		if (section.key != key) section
		else section.copy(itemIds = section.itemIds - itemId + itemId, active = true)
	}
}

/** Takes an item's row away, and the section with it once nothing is left in it. */
fun List<HomeSectionConfigDto>.withItemUnpinned(key: String, itemId: UUID): List<HomeSectionConfigDto> =
	map { section -> if (section.key == key) section.copy(itemIds = section.itemIds - itemId) else section }
		.filterNot { it.key == key && it.itemIds.isEmpty() }
