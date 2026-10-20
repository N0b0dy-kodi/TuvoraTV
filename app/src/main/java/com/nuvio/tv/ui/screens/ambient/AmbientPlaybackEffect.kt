package com.nuvio.tv.ui.screens.ambient

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/** Keep app-owned poster slides behind playback, including preview and transient buffering. */
@Composable
fun AmbientPlaybackEffect(active: Boolean) {
    val controller = LocalAmbientIdleController.current
    DisposableEffect(controller, active) {
        val lease = if (active) controller?.blockForPlayback() else null
        onDispose { lease?.close() }
    }
}
