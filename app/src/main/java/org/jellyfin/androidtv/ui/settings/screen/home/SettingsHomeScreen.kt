package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
fun SettingsHomeScreen() {
	val router = LocalRouter.current
	val viewModel = koinViewModel<SettingsHomeViewModel>()
	val state by viewModel.state.collectAsState()

	LaunchedEffect(viewModel) { viewModel.load() }

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_customization).uppercase()) },
				headingContent = { Text(stringResource(R.string.home_sections)) },
			)
		}

		if (state.loading) {
			item { ListMessage { Text(stringResource(R.string.loading)) } }
		}

		if (state.failed) {
			item { ListMessage { Text(stringResource(R.string.home_section_error)) } }
		}

		itemsIndexed(state.sections) { index, section ->
			val caption = listOfNotNull(
				state.itemName(section.itemId),
				if (!section.active) stringResource(R.string.home_section_hidden) else null,
			).joinToString(", ")

			ListButton(
				headingContent = { Text(state.providerName(section.key)) },
				captionContent = if (caption.isEmpty()) null else ({ Text(caption) }),
				onClick = { router.push(Routes.HOME_SECTION, mapOf("index" to index.toString())) },
				modifier = Modifier.focusKey("home_section_$index")
			)
		}

		if (!state.loading) {
			item {
				ListButton(
					headingContent = { Text(stringResource(R.string.home_section_add)) },
					onClick = { router.push(Routes.HOME_SECTION_ADD) },
					modifier = Modifier.focusKey(Routes.HOME_SECTION_ADD)
				)
			}

			item {
				ListButton(
					headingContent = { Text(stringResource(R.string.home_section_reset)) },
					onClick = { router.push(Routes.HOME_RESET) },
					modifier = Modifier.focusKey(Routes.HOME_RESET)
				)
			}
		}
	}
}
