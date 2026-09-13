package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListMessage
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.androidx.compose.koinViewModel

/**
 * The items a provider can be bound to. Each one is toggled in place: a tick means the layout
 * has a row for it.
 */
@Composable
fun SettingsHomeSectionItemScreen(key: String) {
	val viewModel = koinViewModel<SettingsHomeViewModel>()
	val state by viewModel.state.collectAsState()
	val provider = state.provider(key)
	val kind = provider?.itemKind

	if (provider == null || kind == null) {
		ListMessage {
			Text("Unknown section $key")
		}

		return
	}

	val items = state.items[kind].orEmpty()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.home_section_add).uppercase()) },
				headingContent = { Text(provider.name) },
			)
		}

		if (state.failed) {
			item { ListMessage { Text(stringResource(R.string.home_section_error)) } }
		} else if (items.isEmpty()) {
			item { ListMessage { Text(stringResource(R.string.lbl_no_items)) } }
		}

		items(items) { item ->
			val added = state.contains(key, item.id)

			ListButton(
				headingContent = { Text(item.name.orEmpty()) },
				trailingContent = { Checkbox(checked = added) },
				onClick = {
					if (added) viewModel.remove(key, item.id)
					else viewModel.add(key, item.id)
				},
				modifier = Modifier.focusKey("home_section_item_${item.id}")
			)
		}
	}
}
