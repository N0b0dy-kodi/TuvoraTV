package com.nuvio.tv.data.repository

/** Decide whether the small activity response warrants downloading progress or watched lists. */
internal object TraktProgressRefreshPolicy {
    data class Decision(val fetchProgress: Boolean, val refetchBothWatchedLists: Boolean)

    fun decide(
        force: Boolean,
        activityChanged: Boolean?,
        activityRefreshPending: Boolean,
        hasLoadedProgress: Boolean,
        progressRecheckDue: Boolean,
    ): Decision = Decision(
        fetchProgress = force || activityChanged == true || activityRefreshPending || progressRecheckDue ||
            (activityChanged == null && !hasLoadedProgress),
        refetchBothWatchedLists = force && activityChanged == null,
    )
}
