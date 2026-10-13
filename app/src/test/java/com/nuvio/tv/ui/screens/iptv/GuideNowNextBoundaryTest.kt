package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GuideNowNextBoundaryTest {
    private fun programme(title: String, start: Long, end: Long, markedNow: Boolean = false) =
        XtreamProgram(title, "", start, end, markedNow)
    private val first = programme("First", 0, 100, markedNow = true)
    private val second = programme("Second", 100, 200)
    private val third = programme("Third", 200, 300)
    private val loaded = GuideEpg(first, second, listOf(first, second, third))

    @Test fun boundaryPromotesNextDespiteStaleNowFlag() {
        val result = GuideWindowRows.atTime(loaded, 100)
        assertEquals(second, result.now)
        assertEquals(third, result.next)
    }
    @Test fun beforeBoundaryKeepsCurrent() {
        val result = GuideWindowRows.atTime(loaded, 99)
        assertEquals(first, result.now)
        assertEquals(second, result.next)
    }
    @Test fun scheduleGapDoesNotPresentEndedProgrammeAsCurrent() {
        val later = programme("Later", 150, 200)
        val result = GuideWindowRows.atTime(GuideEpg(first, later, listOf(first, later)), 125)
        assertNull(result.now)
        assertEquals(later, result.next)
    }
    @Test fun beforeFirstProgrammeShowsUpcomingOnly() {
        val result = GuideWindowRows.atTime(GuideEpg(second, third, listOf(second, third)), 50)
        assertNull(result.now)
        assertEquals(second, result.next)
    }
    @Test fun afterLastProgrammeDoesNotResurrectFirst() {
        val result = GuideWindowRows.atTime(loaded, 300)
        assertNull(result.now)
        assertNull(result.next)
    }
    @Test fun cachedPairSurvivesTravelledWindowButIsRecomputed() {
        val history = listOf(programme("Past", -100, 0))
        val result = GuideWindowRows.atTime(GuideEpg(first, second, history, storedWindow = true), 100)
        assertEquals(second, result.now)
        assertNull(result.next)
        assertSame(history, result.programmes)
        assertEquals(true, result.storedWindow)
    }
    @Test fun emptyScheduleIsEmpty() {
        val result = GuideWindowRows.atTime(GuideEpg(null, null), 100)
        assertNull(result.now)
        assertNull(result.next)
    }
}
