package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upstream MpvPlaybackErrorPolicyTest's watchdog and permanent-status cases (3448dd7a5, 830e8e83c).
 * The rest of that suite tests upstream's libmpv event relay and error-text builder, which the fork's
 * shadow-based mpv layer does not use.
 */
class MpvStartupWatchdogPolicyTest {

    @Test
    fun watchdog_surfaceWaitDoesNotCountAsIdle() {
        val before = advance(MpvStartupWatchdogPolicy.surfaceWaitTicks - 1, waitingForSurface = true)
        assertEquals(MpvStartupWatchdogPolicy.Action.Continue, before.action)

        val fired = advance(MpvStartupWatchdogPolicy.surfaceWaitTicks, waitingForSurface = true)
        assertEquals(MpvStartupWatchdogPolicy.Action.SurfaceTimeout, fired.action)
    }

    @Test
    fun watchdog_idleAfterLoadFailsFast() {
        val before = advance(MpvStartupWatchdogPolicy.idleTicksLimit - 1, idleActive = true)
        assertEquals(MpvStartupWatchdogPolicy.Action.Continue, before.action)

        val fired = advance(MpvStartupWatchdogPolicy.idleTicksLimit, idleActive = true)
        assertEquals(MpvStartupWatchdogPolicy.Action.IdleError, fired.action)
    }

    @Test
    fun watchdog_noCacheGrowthFailsAtStallTimeout() {
        val before = advance(MpvStartupWatchdogPolicy.stallTicksLimit - 1)
        assertEquals(MpvStartupWatchdogPolicy.Action.Continue, before.action)

        val fired = advance(MpvStartupWatchdogPolicy.stallTicksLimit)
        assertEquals(MpvStartupWatchdogPolicy.Action.StallTimeout, fired.action)
    }

    @Test
    fun watchdog_cacheGrowthExtendsWaitUntilAbsoluteTimeout() {
        val stillWaiting = advance(
            ticks = MpvStartupWatchdogPolicy.stallTicksLimit,
            cacheProgressing = true
        )
        assertEquals(MpvStartupWatchdogPolicy.Action.Continue, stillWaiting.action)
        assertEquals(0, stillWaiting.counters.stallTicks)

        val fired = advance(
            ticks = MpvStartupWatchdogPolicy.absoluteTicksLimit,
            cacheProgressing = true
        )
        assertEquals(MpvStartupWatchdogPolicy.Action.AbsoluteTimeout, fired.action)
    }

    @Test
    fun watchdog_idleBlipsDoNotResetTheAbsoluteClock() {
        var counters = MpvStartupWatchdogPolicy.Counters()
        var action = MpvStartupWatchdogPolicy.Action.Continue
        for (index in 0 until MpvStartupWatchdogPolicy.absoluteTicksLimit + 8) {
            val step = MpvStartupWatchdogPolicy.step(
                MpvStartupWatchdogPolicy.Input(
                    enabled = true,
                    waitingForSurface = false,
                    idleActive = index % 8 != 7,
                    cacheProgressing = false,
                    counters = counters
                )
            )
            counters = step.counters
            action = step.action
            if (action != MpvStartupWatchdogPolicy.Action.Continue) break
        }

        assertEquals(MpvStartupWatchdogPolicy.Action.AbsoluteTimeout, action)
    }

    @Test
    fun watchdog_pauseClearsAccumulatedTicks() {
        val partial = advance(10)
        val paused = MpvStartupWatchdogPolicy.step(
            MpvStartupWatchdogPolicy.Input(
                enabled = false,
                waitingForSurface = false,
                idleActive = true,
                cacheProgressing = false,
                counters = partial.counters
            )
        )

        assertEquals(MpvStartupWatchdogPolicy.Action.Continue, paused.action)
        assertEquals(MpvStartupWatchdogPolicy.Counters(), paused.counters)
    }

    @Test
    fun permanentHttpStatus_skipsRetryOnlyForDeterministicClientErrors() {
        listOf(400, 401, 403, 404, 410).forEach { assertTrue("$it", isPermanentHttpStatus(it)) }
        listOf(408, 425, 429, 500, 502, 503, 504).forEach { assertFalse("$it", isPermanentHttpStatus(it)) }
    }

    @Test
    fun cacheGrowth_ignoresNoiseBelowAQuarterSecond() {
        assertFalse(MpvStartupWatchdogPolicy.cacheIsProgressing(previousSec = 1.0, currentSec = 1.2))
        assertTrue(MpvStartupWatchdogPolicy.cacheIsProgressing(previousSec = 1.0, currentSec = 1.3))
    }

    private fun advance(
        ticks: Int,
        waitingForSurface: Boolean = false,
        idleActive: Boolean = false,
        cacheProgressing: Boolean = false,
    ): MpvStartupWatchdogPolicy.Step {
        var counters = MpvStartupWatchdogPolicy.Counters()
        var step = MpvStartupWatchdogPolicy.Step(MpvStartupWatchdogPolicy.Action.Continue, counters)
        repeat(ticks) {
            step = MpvStartupWatchdogPolicy.step(
                MpvStartupWatchdogPolicy.Input(
                    enabled = true,
                    waitingForSurface = waitingForSurface,
                    idleActive = idleActive,
                    cacheProgressing = cacheProgressing,
                    counters = counters
                )
            )
            counters = step.counters
            if (step.action != MpvStartupWatchdogPolicy.Action.Continue) return step
        }
        return step
    }
}
