package com.nuvio.tv.data.mediaserver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A Quick Connect request: [code] is shown and approved on another signed-in client; [secret] is the poll/exchange handle. */
class ServerQuickConnectRequest(val address: String, val code: String, val secret: String) {
    override fun toString(): String = "ServerQuickConnectRequest(address=$address)"
}

/** The cadence of one Quick Connect sign-in. Pure: the flow below accumulates the waited time, no wall clock. */
object QuickConnectCadence {
    /** Jellyfin expires a request after 10 minutes (QuickConnectManager.Timeout = 10). */
    const val LIFETIME_MS = 10 * 60_000L
    const val FIRST_POLL_MS = 2_000L
    const val STEADY_POLL_MS = 4_000L

    /** Consecutive transport failures tolerated before the sign-in gives up (a flaky LAN is not a failed sign-in). */
    const val MAX_CONSECUTIVE_FAILURES = 5

    fun pollDelayMs(attempt: Int): Long = if (attempt < 3) FIRST_POLL_MS else STEADY_POLL_MS

    fun remainingMs(waitedMs: Long): Long = (LIFETIME_MS - waitedMs).coerceAtLeast(0L)

    /** `m:ss` for the countdown ("9:41"). */
    fun countdownLabel(remainingMs: Long): String {
        val totalSeconds = ((remainingMs + 999) / 1000).coerceAtLeast(0)
        return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
    }

    /** Digits grouped for legibility ("123 456"); the wire form stays ungrouped. */
    fun displayCode(code: String): String {
        val digits = code.filter { !it.isWhitespace() }
        return if (digits.length == 6) "${digits.substring(0, 3)} ${digits.substring(3)}" else digits
    }
}

/** Every Quick Connect sign-in ends in one of the terminal states; Retry is a new flow, never a loop. */
sealed interface QuickConnectState {
    data object Requesting : QuickConnectState
    data class Waiting(val code: String, val remainingMs: Long) : QuickConnectState
    data object Completing : QuickConnectState
    data class Authorised(val signIn: ServerSignIn) : QuickConnectState
    data object Expired : QuickConnectState
    data class Failed(val error: ServerException) : QuickConnectState
}

/**
 * The Quick Connect state machine (lifecycle-bound: it runs only while collected, so closing the sign-in
 * cancels it; bounded: it ends at [QuickConnectCadence.LIFETIME_MS] with [QuickConnectState.Expired], and gives
 * up with [QuickConnectState.Failed] after [QuickConnectCadence.MAX_CONSECUTIVE_FAILURES] transport failures).
 */
fun quickConnectFlow(provider: ServerProvider, address: String): Flow<QuickConnectState> = flow {
    emit(QuickConnectState.Requesting)
    val request = try {
        provider.quickConnectStart(address)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        emit(QuickConnectState.Failed(error.asServerException()))
        return@flow
    }
    emit(QuickConnectState.Waiting(request.code, QuickConnectCadence.remainingMs(0)))

    var waitedMs = 0L
    var attempt = 0
    var failures = 0
    while (waitedMs < QuickConnectCadence.LIFETIME_MS) {
        val wait = QuickConnectCadence.pollDelayMs(attempt++)
        delay(wait)
        waitedMs += wait
        val approved = try {
            provider.quickConnectApproved(request).also { failures = 0 }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ServerException) {
            when (error.failure) {
                // The server forgot the secret: expired or revoked. Terminal.
                ServerFailure.NOT_FOUND -> { emit(QuickConnectState.Expired); return@flow }
                // Quick Connect switched off meanwhile / not allowed: not transient.
                ServerFailure.AUTH_REQUIRED, ServerFailure.FORBIDDEN, ServerFailure.UNSUPPORTED,
                ServerFailure.CERTIFICATE_UNTRUSTED, ServerFailure.CERTIFICATE_CHANGED -> {
                    emit(QuickConnectState.Failed(error)); return@flow
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
        if (approved == null) {
            if (++failures >= QuickConnectCadence.MAX_CONSECUTIVE_FAILURES) {
                emit(QuickConnectState.Failed(ServerException(ServerFailure.UNREACHABLE)))
                return@flow
            }
        } else if (approved) {
            emit(QuickConnectState.Completing)
            try {
                emit(QuickConnectState.Authorised(provider.quickConnectComplete(request)))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                emit(QuickConnectState.Failed(error.asServerException()))
            }
            return@flow
        }
        emit(QuickConnectState.Waiting(request.code, QuickConnectCadence.remainingMs(waitedMs)))
    }
    emit(QuickConnectState.Expired)
}

private fun Throwable.asServerException(): ServerException =
    this as? ServerException ?: ServerException(ServerFailure.FAILED)
