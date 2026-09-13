package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.compose.runtime.Composable
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

@Composable
fun SettingsHomeResetScreen() {
	val router = LocalRouter.current
	val viewModel = koinViewModel<SettingsHomeViewModel>()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.home_sections).uppercase()) },
				headingContent = { Text(stringResource(R.string.home_section_reset)) },
			)
		}

		item {
			ListMessage { Text(stringResource(R.string.home_section_reset_confirm)) }
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.home_section_reset)) },
				onClick = {
					viewModel.reset()
					router.back()
				},
				modifier = Modifier.focusKey("home_section_reset_confirm")
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.lbl_cancel)) },
				onClick = { router.back() },
				modifier = Modifier.focusKey("home_section_reset_cancel", initialFocus = true)
			)
		}
	}
}
