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
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingsHomeSectionScreen(index: Int) {
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
				overlineContent = { Text(stringResource(R.string.home_sections).uppercase()) },
				headingContent = { Text(state.providerName(section.key)) },
				captionContent = state.itemNames(section)
					?.takeUnless { state.takesSeveralItems(section.key) }
					?.let { name -> ({ Text(name) }) },
			)
		}

		if (state.takesSeveralItems(section.key)) {
			item {
				ListButton(
					headingContent = { Text(stringResource(R.string.home_section_choose_items)) },
					captionContent = {
						Text(state.itemNames(section) ?: stringResource(R.string.home_section_all_items))
					},
					onClick = { router.push(Routes.HOME_SECTION_ADD_ITEM, mapOf("key" to section.key)) },
					modifier = Modifier.focusKey("home_section_items")
				)
			}

			item {
				ListButton(
					headingContent = { Text(stringResource(R.string.home_section_order_items)) },
					enabled = section.itemIds.size > 1,
					onClick = { router.push(Routes.HOME_SECTION_ORDER, mapOf("index" to index.toString())) },
					modifier = Modifier.focusKey("home_section_order")
				)
			}
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.home_section_move_up)) },
				enabled = index > 0,
				onClick = {
					viewModel.move(index, -1)
					router.back()
				},
				modifier = Modifier.focusKey("home_section_move_up")
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.home_section_move_down)) },
				enabled = index < state.sections.lastIndex,
				onClick = {
					viewModel.move(index, 1)
					router.back()
				},
				modifier = Modifier.focusKey("home_section_move_down")
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(if (section.active) R.string.home_section_hide else R.string.home_section_show)) },
				onClick = {
					viewModel.setActive(index, !section.active)
					router.back()
				},
				modifier = Modifier.focusKey("home_section_active")
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.home_section_max_items)) },
				captionContent = { Text(section.maxItems?.toString() ?: stringResource(R.string.home_section_max_items_default)) },
				onClick = { router.push(Routes.HOME_SECTION_MAX_ITEMS, mapOf("index" to index.toString())) },
				modifier = Modifier.focusKey("home_section_max_items")
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.home_section_remove)) },
				onClick = {
					viewModel.remove(index)
					router.back()
				},
				modifier = Modifier.focusKey("home_section_remove")
			)
		}
	}
}
