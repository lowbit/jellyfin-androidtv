package org.jellyfin.androidtv.constant

/**
 * The built-in section keys the app draws differently from a plain row of cards. The full list of
 * sections comes from the server, so nothing else is known here.
 */
object HomeSectionKey {
	const val SMALL_LIBRARY_TILES = "smalllibrarytiles"
	const val LIBRARY_BUTTONS = "librarybuttons"
	const val LIVE_TV = "livetv"

	val libraryKeys = setOf(SMALL_LIBRARY_TILES, LIBRARY_BUTTONS)
}
