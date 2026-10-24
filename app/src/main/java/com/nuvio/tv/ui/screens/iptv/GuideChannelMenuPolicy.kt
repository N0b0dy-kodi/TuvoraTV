package com.nuvio.tv.ui.screens.iptv

/** Channel actions are always explicit; opening a menu never changes the lineup. */
object GuideChannelMenuPolicy {

    enum class Action { MOVE_UP, MOVE_DOWN, REMOVE_FAVORITE, HIDE }

    fun menu(
        special: GuideSpecial?,
        isPinned: Boolean,
        indexInList: Int,
        listSize: Int,
    ): List<Action> {
        val favouritesView = special == GuideSpecial.FAVORITES || special == GuideSpecial.ALL_FAVORITES
        val pinnedInProviderView = isPinned && (special == null || special == GuideSpecial.ALL)
        if (!favouritesView && !pinnedInProviderView) return listOf(Action.HIDE)
        return buildList {
            if (indexInList > 0) add(Action.MOVE_UP)
            if (indexInList in 0 until listSize - 1) add(Action.MOVE_DOWN)
            add(if (favouritesView) Action.REMOVE_FAVORITE else Action.HIDE)
        }
    }
}
