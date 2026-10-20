package com.nuvio.tv.core.streams

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** Bounds one observer's interactive wait; cancellation leaves other shared-search observers alone. */
internal fun <T> Flow<T>.withSourceQueryDeadline(
    budgetMs: Long = 30_000L,
    timedOut: () -> T,
): Flow<T> = channelFlow {
    val collector = launch { collect { send(it) } }
    val watchdog = launch {
        delay(budgetMs)
        send(timedOut())
        collector.cancel()
    }
    collector.invokeOnCompletion { watchdog.cancel() }
    collector.join()
}
