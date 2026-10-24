package com.nuvio.tv.core.player

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProgressWriteBoundaryTest {
    @Test fun failedDiskWriteDoesNotStopThePlaybackCaller() = runTest {
        var recorded: IOException? = null
        val failure = IOException("synthetic disk failure")
        persistProgressOrContinue({ recorded = it }) { throw failure }
        assertSame(failure, recorded)
    }
    @Test fun successfulWriteDoesNotReportFailure() = runTest {
        var writes = 0
        persistProgressOrContinue({ fail("Unexpected failure") }) { writes++ }
        assertEquals(1, writes)
    }
    @Test fun cancellationStillPropagates() = runTest {
        val cancellation = CancellationException("scope ended")
        try {
            persistProgressOrContinue({ fail("Cancellation is not a disk failure") }) { throw cancellation }
            fail("Cancellation was swallowed")
        } catch (caught: CancellationException) { assertSame(cancellation, caught) }
    }
}
