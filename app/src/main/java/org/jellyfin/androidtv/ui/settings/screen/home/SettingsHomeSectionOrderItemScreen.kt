package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListMessage
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.androidx.compose.koinViewModel
import java.util.UUID

/** Where one row of a section that takes several items goes: top, one up, one down, bottom. */
@Composable
fun SettingsHomeSectionOrderItemScreen(index: Int, itemId: UUID) {
	val router = LocalRouter.current
	val viewModel = koinViewModel<SettingsHomeViewModel>()
	val state by viewModel.state.collectAsState()
	val section = state.sections.getOrNull(index)
	val position = section?.itemIds?.indexOf(itemId) ?: -1

	if (section == null || position < 0) {
		ListMessage {
			Text("Unknown item $itemId")
		}

		return
	}

	val moves = listOf(
		R.string.home_section_move_top to -section.itemIds.size,
		R.string.home_section_move_up to -1,
		R.string.home_section_move_down to 1,
		R.string.home_section_move_bottom to section.itemIds.size,
	)

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(state.providerName(section.key).uppercase()) },
				headingContent = { Text(state.itemName(itemId) ?: itemId.toString()) },
				captionContent = { Text("${position + 1} / ${section.itemIds.size}") },
			)
		}

		items(moves.size) { i ->
			val (label, offset) = moves[i]
			val enabled = if (offset < 0) position > 0 else position < section.itemIds.lastIndex

			ListButton(
				headingContent = { Text(stringResource(label)) },
				enabled = enabled,
				onClick = {
					viewModel.moveItem(index, itemId, offset)
					router.back()
				},
				modifier = Modifier.focusKey("home_section_order_move_$offset")
			)
		}
	}
}
