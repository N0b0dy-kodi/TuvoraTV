package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamCatchUp.ProgrammeAction

/**
 * What OK does on a programme cell.
 *
 * The locked decision, in one table: a finished replayable programme plays INSTANTLY (TiViMate's
 * behaviour, and what switchers expect), the airing programme on an archive channel opens the
 * two-button sheet — the only state with two reasonable destinations and no obvious default — and
 * an airing programme with no archive just plays live. Unplayable programmes can still take focus for inspection; OK does not promise playback.
 */
internal object GuideCellIntent {

    /** What pressing OK on the cell should do. */
    enum class Intent {
        /** Nothing playable here — focus may still reveal details. */
        NONE,

        /** Tune the channel live, no sheet. */
        PLAY_LIVE,

        /** Two destinations: the sheet asks "Watch live" or "Start over". */
        OPEN_SHEET,

        /** Play the recording immediately. */
        REPLAY,
    }

    fun forAction(action: ProgrammeAction): Intent = when (action) {
        ProgrammeAction.NONE -> Intent.NONE
        ProgrammeAction.PLAY_LIVE -> Intent.PLAY_LIVE
        ProgrammeAction.START_OVER -> Intent.OPEN_SHEET
        ProgrammeAction.REPLAY -> Intent.REPLAY
    }

    /** Whether OK can activate playback; independent of inspection focus. */
    fun canActivate(action: ProgrammeAction): Boolean = forAction(action) != Intent.NONE

    /**
     * The ⟲ badge means "the panel kept this", not "this cell is selectable" — an airing programme
     * on a channel with no archive is selectable and carries no badge, which is exactly the
     * distinction broadcasters draw between Start Over and plain live.
     */
    fun showsReplayBadge(action: ProgrammeAction): Boolean =
        action == ProgrammeAction.REPLAY || action == ProgrammeAction.START_OVER
}
