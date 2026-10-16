package com.nuvio.tv.core.party

/** What asking the backend's `watch_party_enabled` kill switch produced. */
internal sealed interface PartyAvailabilityProbe {
    /** The switch answered. */
    data class Answered(val enabled: Boolean) : PartyAvailabilityProbe

    /**
     * The backend has no watch-party RPCs: PostgREST PGRST202 (function not in the schema cache,
     * HTTP 404), Postgres 42883 (undefined function), a bare 404, or a body that is not a boolean.
     * Retrying cannot fix this, so the feature is off rather than a dead screen.
     */
    data object Missing : PartyAvailabilityProbe

    /** Network or server trouble that may pass: keep what we knew. */
    data object Unreachable : PartyAvailabilityProbe
}

internal object PartyAvailabilityPolicy {
    private val MISSING_FUNCTION_CODES = setOf("PGRST202", "42883")
    private const val HTTP_NOT_FOUND = 404

    /** [postgrestCode] / [httpStatus] come from a PostgREST error; null when the failure was not one. */
    fun classifyFailure(postgrestCode: String?, httpStatus: Int?): PartyAvailabilityProbe = when {
        postgrestCode?.trim()?.uppercase() in MISSING_FUNCTION_CODES -> PartyAvailabilityProbe.Missing
        httpStatus == HTTP_NOT_FOUND -> PartyAvailabilityProbe.Missing
        else -> PartyAvailabilityProbe.Unreachable
    }

    /** Parses the RPC body, which must be exactly `true` or `false`. */
    fun classifyBody(body: String): PartyAvailabilityProbe = when (body.trim()) {
        "true" -> PartyAvailabilityProbe.Answered(true)
        "false" -> PartyAvailabilityProbe.Answered(false)
        else -> PartyAvailabilityProbe.Missing
    }

    /** Whether watch parties are available after [probe], given the server's advertised [capability]. */
    fun next(capability: Boolean, current: Boolean, probe: PartyAvailabilityProbe): Boolean {
        if (!capability) return false
        return when (probe) {
            is PartyAvailabilityProbe.Answered -> probe.enabled
            PartyAvailabilityProbe.Missing -> false
            PartyAvailabilityProbe.Unreachable -> current
        }
    }
}
