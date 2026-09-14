package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.compose.foundation.lazy.itemsIndexed
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
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.androidx.compose.koinViewModel

/**
 * The rows of a section that takes several items, in the order they are drawn. Picking one
 * opens where to move it.
 */
@Composable
fun SettingsHomeSectionOrderScreen(index: Int) {
	val router = LocalRouter.current
	val viewModel = koinViewModel<SettingsHomeViewModel>()
	val state by viewModel.state.collectAsState()
	val section = state.sections.getOrNull(index)

	if (section == null) {
		ListMessage {
			Text("Unknown section $index")
		}

		return
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(state.providerName(section.key).uppercase()) },
				headingContent = { Text(stringResource(R.string.home_section_order_items)) },
			)
		}

		itemsIndexed(section.itemIds) { position, itemId ->
			ListButton(
				headingContent = { Text(state.itemName(itemId) ?: itemId.toString()) },
				captionContent = { Text((position + 1).toString()) },
				onClick = {
					router.push(Routes.HOME_SECTION_ORDER_ITEM, mapOf("index" to index.toString(), "itemId" to itemId.toString()))
				},
				modifier = Modifier.focusKey("home_section_order_$itemId")
			)
		}
	}
}
