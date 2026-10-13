package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamProgram

internal data class GuideNowNext(val now: XtreamProgram?, val next: XtreamProgram?)

/** Live labels use timestamps, never the provider's load-time nowPlaying flag. */
internal object GuideNowNextPolicy {
    fun select(programmes: List<XtreamProgram>, nowMs: Long): GuideNowNext {
        val sorted = programmes.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        return GuideNowNext(
            now = sorted.firstOrNull { it.startMs <= nowMs && nowMs < it.endMs },
            next = sorted.firstOrNull { it.startMs > nowMs },
        )
    }
}
