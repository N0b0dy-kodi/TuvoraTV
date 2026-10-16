package com.nuvio.tv.core.party

import kotlin.math.abs

/**
 * What the player sees on one sync tick. Times are plain milliseconds: [monoMs] is a monotonic clock
 * (for the policy's own debounces), [partyNowMs] is the party's server clock (for the shared timeline).
 */
internal data class PartyTickInput(
    val monoMs: Long,
    val partyNowMs: Long,
    val party: ActiveParty,
    /** The media key of what this player is playing right now. */
    val playerKey: String,
    val playback: PartyPlayback?,
    val positionMs: Long?,
    val durationMs: Long,
    /** Play intent: true while the user wants playback running, even if buffering. */
    val playing: Boolean,
    /** The speed the user picked (not a temporary nudge). */
    val rate: Float,
    val loading: Boolean,
    val hasError: Boolean,
    val inBackground: Boolean,
    val rendered: Boolean,
    val playbackEnded: Boolean,
    val previewSeekPending: Boolean,
    val behind: Boolean,
    val behindShownMs: Long?,
    /** The temporary drift-correction speed, or null when none is applied. */
    val nudgeRate: Float?,
)

internal data class PartyStatusReport(
    val status: PartyMemberStatus,
    val mediaKey: String,
    val behindMs: Long?,
)

/** The loop's memory between ticks. Starts empty per party session. */
internal data class PartyTickState(
    val previousKey: String? = null,
    val bufferingSinceMs: Long? = null,
    val mismatchSinceMs: Long? = null,
    val lastSeekMs: Long? = null,
    val lastCorrectionMs: Long? = null,
    val lastSeq: Pair<Long, String?>? = null,
    val lastStatus: PartyStatusReport? = null,
    val wasInBackground: Boolean = false,
    val wasRendered: Boolean = false,
    val publishedInitial: Boolean = false,
    val seekLeadMs: Long = 0L,
    val measureSeekLead: Boolean = false,
    val wasBehind: Boolean = false,
)

/** One instruction for the player. The controller applies them in order and decides nothing itself. */
internal sealed interface PartyTickAction {
    /** The host moved to other content in-player (next episode): tell the party. */
    data object PublishMediaSwitch : PartyTickAction
    data class ReportStatus(val report: PartyStatusReport) : PartyTickAction
    data class Publish(val paused: Boolean, val positionMs: Long, val rate: Float) : PartyTickAction
    data class SetPaused(val paused: Boolean) : PartyTickAction
    data class Seek(val positionMs: Long) : PartyTickAction
    /** Adopt the party's chosen speed (persistent, shown in the UI). */
    data class SetRate(val rate: Float) : PartyTickAction
    /** Temporarily play slightly faster or slower to close a small drift. */
    data class Nudge(val rate: Float) : PartyTickAction
    /** Drop the temporary nudge and return to [rate]. */
    data class ClearNudge(val rate: Float) : PartyTickAction
    data class SetBehind(val behind: Boolean) : PartyTickAction
    data class ShowBehind(val gapMs: Long) : PartyTickAction
    data object CaughtUp : PartyTickAction
}

internal data class PartyTickResult(val state: PartyTickState, val actions: List<PartyTickAction>)

/**
 * The decisions of upstream's TV player party loop (`PlayerRuntimeControllerParty.runPartySync`,
 * NuvioMedia/NuvioTV #4028), lifted out of the player so they are testable without ExoPlayer, mpv or
 * the network. Per tick it decides: which presence to report, whether the host seeds the first
 * playback, whether a new remote state must be applied (pause/play, seek, rate), how a member who
 * fell behind on purpose is tracked, what to do when the local player disagrees with the party
 * (members who may control publish, others are pulled back or marked behind — the host-only rule),
 * and how drift is corrected (seek past [PartySync.SEEK_THRESHOLD_MS], else a small speed nudge).
 */
internal object PartyPlayerSyncPolicy {
    const val TICK_MS = 250L
    const val BUFFERING_REPORT_DELAY_MS = 1_000L
    const val OUTSIDE_TOGGLE_DELAY_MS = 1_200L
    const val SEEK_SETTLE_MS = 2_500L
    const val CORRECTION_INTERVAL_MS = 500L
    const val MAX_SEEK_LEAD_MS = 3_000L
    const val BEHIND_DISPLAY_STEP_MS = 1_000L

    fun tick(previous: PartyTickState, input: PartyTickInput): PartyTickResult {
        val actions = mutableListOf<PartyTickAction>()
        var state = previous
        var nudge = input.nudgeRate
        var behind = input.behind
        val party = input.party
        val key = input.playerKey
        val now = input.monoMs

        fun clearNudge(rate: Float) {
            if (nudge == null) return
            nudge = null
            actions += PartyTickAction.ClearNudge(rate)
        }

        fun setBehind(value: Boolean) {
            if (behind == value) return
            behind = value
            actions += PartyTickAction.SetBehind(value)
        }

        fun done() = PartyTickResult(state, actions)

        // The player moved on (next episode) while it was showing the party's media.
        if (state.previousKey != key) {
            val previousKey = state.previousKey
            state = state.copy(previousKey = key)
            setBehind(false)
            if (previousKey != null && party.isHost && party.media.key == previousKey) {
                actions += PartyTickAction.PublishMediaSwitch
            }
        }

        // Coming back to the foreground or to a first frame: re-apply the party state from scratch.
        if (state.wasInBackground && !input.inBackground) state = state.copy(lastSeq = null)
        state = state.copy(wasInBackground = input.inBackground)
        if (input.rendered && !state.wasRendered) state = state.copy(lastSeq = null)
        state = state.copy(wasRendered = input.rendered)

        val playback = input.playback
        val behindGap = playback
            ?.takeIf { behind && party.media.key == key }
            ?.let { current ->
                input.positionMs?.let { local ->
                    PartySync.expectedPositionMs(current, input.partyNowMs, input.durationMs) - local
                }
            }
        val status = when {
            input.hasError || input.inBackground -> PartyMemberStatus.Browsing
            behindGap != null -> PartyMemberStatus.Behind
            !input.rendered -> PartyMemberStatus.Loading
            input.loading && !input.playbackEnded -> {
                val since = state.bufferingSinceMs ?: now.also { state = state.copy(bufferingSinceMs = it) }
                if (now - since > BUFFERING_REPORT_DELAY_MS) PartyMemberStatus.Buffering else PartyMemberStatus.Ready
            }
            else -> {
                state = state.copy(bufferingSinceMs = null)
                PartyMemberStatus.Ready
            }
        }
        val report = PartyStatusReport(
            status = status,
            mediaKey = key,
            behindMs = behindGap?.takeIf { status == PartyMemberStatus.Behind }?.let(PartySync::reportedGapMs),
        )
        if (report != state.lastStatus) {
            state = state.copy(lastStatus = report)
            actions += PartyTickAction.ReportStatus(report)
        }

        val positionMs = input.positionMs
        if (party.media.key != key || positionMs == null || input.hasError || input.inBackground) {
            clearNudge(playback?.rate ?: input.rate)
            return done()
        }

        if (playback == null) {
            // Nobody has published yet: the host seeds the room with where it is.
            if (input.rendered && !state.publishedInitial && party.isHost) {
                state = state.copy(publishedInitial = true)
                actions += PartyTickAction.Publish(paused = !input.playing, positionMs = positionMs, rate = input.rate)
            }
            return done()
        }
        state = state.copy(publishedInitial = true)
        if (!input.rendered) return done()

        // Behind on purpose is only for members who cannot control the room.
        if (behind && party.canControl) setBehind(false)
        if (behind) {
            clearNudge(playback.rate)
            state = state.copy(mismatchSinceMs = null)
            val gap = PartySync.expectedPositionMs(playback, input.partyNowMs, input.durationMs) - positionMs
            if (PartySync.shouldRejoin(gap, playing = input.playing && !input.loading)) {
                setBehind(false)
            } else {
                val shown = input.behindShownMs
                if (shown == null || abs(gap - shown) >= BEHIND_DISPLAY_STEP_MS) {
                    actions += PartyTickAction.ShowBehind(gap.coerceAtLeast(0L))
                }
                state = state.copy(wasBehind = true)
                return done()
            }
        }
        if (state.wasBehind) {
            state = state.copy(wasBehind = false, lastSeq = null)
            if (!party.canControl) actions += PartyTickAction.CaughtUp
        }

        val expected = PartySync.expectedPositionMs(playback, input.partyNowMs, input.durationMs)
        val identity = playback.seq to playback.memberId
        if (identity != state.lastSeq) {
            // A new party state (or a fresh start): adopt it.
            val forced = state.lastSeq == null
            state = state.copy(lastSeq = identity)
            if (forced || input.playing == playback.paused) {
                actions += PartyTickAction.SetPaused(playback.paused)
            }
            val own = playback.memberId == party.memberId && !forced
            var measure = state.measureSeekLead
            if ((!own || playback.paused) && abs(positionMs - expected) > PartySync.PAUSED_TOLERANCE_MS) {
                actions += PartyTickAction.Seek(if (playback.paused) expected else expected + state.seekLeadMs)
                measure = !playback.paused
            }
            state = state.copy(lastSeekMs = now, measureSeekLead = measure, mismatchSinceMs = null)
            if (nudge == null && abs(input.rate - playback.rate) > RATE_EPSILON) {
                actions += PartyTickAction.SetRate(playback.rate)
            }
            return done()
        }

        if (input.loading || input.previewSeekPending || input.playbackEnded) {
            state = state.copy(mismatchSinceMs = null)
            return done()
        }

        // The local player disagrees with the room (remote control, headset button, lifecycle).
        if (input.playing == playback.paused) {
            val since = state.mismatchSinceMs ?: now.also { state = state.copy(mismatchSinceMs = it) }
            if (now - since > OUTSIDE_TOGGLE_DELAY_MS) {
                state = state.copy(mismatchSinceMs = null)
                when {
                    party.canControl -> actions += PartyTickAction.Publish(
                        paused = !input.playing,
                        positionMs = positionMs,
                        rate = playback.rate,
                    )
                    input.playing -> actions += PartyTickAction.SetPaused(playback.paused)
                    else -> setBehind(true)
                }
            }
            return done()
        }
        state = state.copy(mismatchSinceMs = null)

        val settled = state.lastSeekMs?.let { now - it > SEEK_SETTLE_MS } ?: true
        val due = state.lastCorrectionMs?.let { now - it >= CORRECTION_INTERVAL_MS } ?: true
        if (!settled || !due) return done()
        state = state.copy(lastCorrectionMs = now)

        // Learn how far a seek lands behind its target while playing, so later seeks aim ahead.
        val drift = positionMs - expected
        if (state.measureSeekLead && !playback.paused) {
            state = state.copy(measureSeekLead = false)
            if (abs(drift) < MAX_SEEK_LEAD_MS) {
                state = state.copy(seekLeadMs = (state.seekLeadMs - drift).coerceIn(0L, MAX_SEEK_LEAD_MS))
            }
        }
        when (val correction = PartySync.correction(positionMs, expected, playback, nudge != null)) {
            is PartyCorrection.Seek -> {
                clearNudge(playback.rate)
                val target = if (playback.paused) correction.positionMs else correction.positionMs + state.seekLeadMs
                actions += PartyTickAction.Seek(if (input.durationMs > 0L) target.coerceAtMost(input.durationMs) else target)
                state = state.copy(lastSeekMs = now, measureSeekLead = !playback.paused)
            }
            is PartyCorrection.Nudge -> if (nudge != correction.rate) {
                nudge = correction.rate
                actions += PartyTickAction.Nudge(correction.rate)
            }
            PartyCorrection.InSync -> clearNudge(playback.rate)
        }
        return done()
    }

    private const val RATE_EPSILON = 0.01f
}
