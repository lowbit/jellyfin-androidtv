package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListMessage
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.androidx.compose.koinViewModel

// A remote has no number entry, so the cap is picked from a list
private val maxItemsOptions = listOf(null, 8, 12, 16, 24, 50)

@Composable
fun SettingsHomeSectionMaxItemsScreen(index: Int) {
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
				headingContent = { Text(stringResource(R.string.home_section_max_items)) },
			)
		}

		items(maxItemsOptions) { option ->
			ListButton(
				headingContent = { Text(option?.toString() ?: stringResource(R.string.home_section_max_items_default)) },
				trailingContent = { RadioButton(checked = section.maxItems == option) },
				onClick = {
					viewModel.setMaxItems(index, option)
					router.back()
				},
				modifier = Modifier
					.focusKey("home_section_max_items_$option", initialFocus = section.maxItems == option)
			)
		}
	}
}
