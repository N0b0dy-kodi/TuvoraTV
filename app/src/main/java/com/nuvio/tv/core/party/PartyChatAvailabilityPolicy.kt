package com.nuvio.tv.core.party

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json

/** What one watch-party chat RPC (`watch_party_list_messages`) answered. */
internal sealed interface PartyChatProbe {
    /** The RPC ran and returned a well-formed history: the backend has chat. */
    data object Answered : PartyChatProbe

    /** PostgREST PGRST202 / 42883 / bare 404: the backend has no chat RPCs. */
    data object Missing : PartyChatProbe

    /** Offline, timeout, 5xx, an unreadable body or an error answer: no verdict either way. */
    data object Inconclusive : PartyChatProbe
}

/**
 * Whether TV may point at phone chat. TV has no chat UI, so the "chat on your phone" hint is only
 * honest while the backend has the chat RPCs and the party kill switch is on. Starts hidden; the
 * first answered RPC reveals it, a missing function hides it, a network blip keeps the last answer.
 * Mirrors the shared KMP `PartyChatAvailabilityPolicy`.
 */
internal object PartyChatAvailabilityPolicy {
    fun next(previous: Boolean, probe: PartyChatProbe): Boolean = when (probe) {
        PartyChatProbe.Answered -> true
        PartyChatProbe.Missing -> false
        PartyChatProbe.Inconclusive -> previous
    }

    fun probeOfFailure(postgrestCode: String?, httpStatus: Int?): PartyChatProbe =
        when (PartyAvailabilityPolicy.classifyFailure(postgrestCode, httpStatus)) {
            PartyAvailabilityProbe.Missing -> PartyChatProbe.Missing
            else -> PartyChatProbe.Inconclusive
        }

    /** A history body is an object with a `messages` array; an `error` answer carries no verdict. */
    fun classifyBody(body: String): PartyChatProbe {
        val parsed = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return PartyChatProbe.Inconclusive
        return if (parsed["messages"] != null && parsed["error"] == null) PartyChatProbe.Answered
        else PartyChatProbe.Inconclusive
    }

    fun hintVisible(partyAvailable: Boolean, chatAvailable: Boolean): Boolean = partyAvailable && chatAvailable
}
