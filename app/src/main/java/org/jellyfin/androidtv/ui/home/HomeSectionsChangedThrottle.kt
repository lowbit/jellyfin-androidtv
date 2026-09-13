package org.jellyfin.androidtv.ui.home

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.HomeSectionsChangedInfo
import kotlin.time.Duration

/**
 * Passes targeted changes through at once and limits "all stale" changes to one per [window]:
 * the first goes out immediately, later ones within the window collapse into a single trailing
 * emission. A library scan marks everything stale once a second for as long as it runs.
 */
fun Flow<HomeSectionsChangedInfo>.throttleAllStale(window: Duration): Flow<HomeSectionsChangedInfo> = channelFlow {
	val allStale = Channel<HomeSectionsChangedInfo>(Channel.CONFLATED)

	launch {
		for (change in allStale) {
			send(change)
			delay(window)
		}
	}

	collect { change ->
		if (change.allStale) allStale.send(change)
		else send(change)
	}

	allStale.close()
}
