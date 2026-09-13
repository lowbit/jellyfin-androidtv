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
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.androidx.compose.koinViewModel

/**
 * The sections the server offers, in the order it sends them. A provider bound to exactly one
 * item opens a picker; every other one, including one that takes several items and shows them
 * all until narrowed, is toggled in place so the layout cannot hold it twice.
 */
@Composable
fun SettingsHomeSectionAddScreen() {
	val router = LocalRouter.current
	val viewModel = koinViewModel<SettingsHomeViewModel>()
	val state by viewModel.state.collectAsState()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.home_sections).uppercase()) },
				headingContent = { Text(stringResource(R.string.home_section_add)) },
			)
		}

		if (state.failed) {
			item { ListMessage { Text(stringResource(R.string.home_section_error)) } }
		}

		items(state.providers) { provider ->
			if (provider.itemKind == null || provider.allowsMultipleItems) {
				ListButton(
					headingContent = { Text(provider.name) },
					trailingContent = { Checkbox(checked = state.containsSection(provider.key)) },
					onClick = { viewModel.toggleSection(provider.key) },
					modifier = Modifier.focusKey("home_section_provider_${provider.key}")
				)
			} else {
				ListButton(
					headingContent = { Text(provider.name) },
					onClick = { router.push(Routes.HOME_SECTION_ADD_ITEM, mapOf("key" to provider.key)) },
					modifier = Modifier.focusKey("home_section_provider_${provider.key}")
				)
			}
		}
	}
}
