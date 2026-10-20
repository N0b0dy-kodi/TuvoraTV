package com.nuvio.tv.core.streams

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceQueryDeadlineTest {
    @Test fun fastResultRemainsUsableWhenAnotherProviderExceedsTheBudget() = runTest {
        val seen = flow { emit("ready"); delay(60_000); emit("too late") }
            .withSourceQueryDeadline(30_000) { "timed out" }.toList()
        assertEquals(listOf("ready", "timed out"), seen)
    }
    @Test fun completedEmptySearchIsNotTimedOut() = runTest {
        assertEquals(emptyList<String>(), flow<String> {}.withSourceQueryDeadline { "timed out" }.toList())
    }
    @Test fun observerCancellationDoesNotEmitATimeout() = runTest {
        val seen = mutableListOf<String>()
        val observer = async { flow<String> { awaitCancellation() }.withSourceQueryDeadline(100) { "timed out" }.toList(seen) }
        runCurrent()
        observer.cancel()
        runCurrent()
        advanceTimeBy(200)
        assertEquals(emptyList<String>(), seen)
    }
    @Test fun normalCompletionCancelsItsWatchdog() = runTest {
        val seen = flow { emit("ready"); delay(10) }.withSourceQueryDeadline(100) { "timed out" }.toList()
        assertEquals(listOf("ready"), seen)
    }
}
