package com.nuvio.tv.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktProgressRefreshPolicyTest {
    @Test fun `unchanged loaded activity fetches no payload`() {
        assertFalse(decide().fetchProgress)
    }
    @Test fun `unknown activity does not redownload an existing snapshot`() {
        assertFalse(decide(activity = null).fetchProgress)
        assertTrue(decide(activity = null, loaded = false).fetchProgress)
    }
    @Test fun `new activity requests a progress fetch`() {
        assertTrue(decide(activity = true).fetchProgress)
    }
    @Test fun `pending changes survive a cancelled refresh`() {
        assertTrue(decide(pending = true).fetchProgress)
    }
    @Test fun `force fetches both watched lists only without activity metadata`() {
        assertFalse(decide(force = true).refetchBothWatchedLists)
        assertTrue(decide(force = true, activity = null).refetchBothWatchedLists)
    }
    @Test fun `the single post force recheck fetches progress only`() {
        val decision = decide(recheck = true)
        assertTrue(decision.fetchProgress)
        assertFalse(decision.refetchBothWatchedLists)
    }
    private fun decide(
        force: Boolean = false, activity: Boolean? = false, pending: Boolean = false,
        loaded: Boolean = true, recheck: Boolean = false,
    ) = TraktProgressRefreshPolicy.decide(force, activity, pending, loaded, recheck)
}
