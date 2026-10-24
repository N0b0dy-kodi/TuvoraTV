package com.nuvio.tv.ui.screens.iptv

/**
 * Where the guide puts its highlight when fullscreen closes. Identities, never an old row number or a
 * channel removed by a catalog update. The channel being WATCHED wins: zapping in fullscreen changes
 * it, and a highlight left on the row you entered from would name one channel while another plays.
 * Then the row you entered from, then the first row.
 */
internal object GuideFocusRestorePolicy {
    fun target(playing: String?, origin: String?, visibleIds: List<String>): String? =
        playing?.takeIf { it in visibleIds } ?: origin?.takeIf { it in visibleIds } ?: visibleIds.firstOrNull()
}
