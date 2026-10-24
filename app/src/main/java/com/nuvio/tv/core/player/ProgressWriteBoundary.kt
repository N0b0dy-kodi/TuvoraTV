package com.nuvio.tv.core.player

import java.io.IOException

/** Progress persistence is ancillary to playback; cancellation and programming failures still propagate. */
internal suspend fun persistProgressOrContinue(onFailure: (IOException) -> Unit, write: suspend () -> Unit) {
    try {
        write()
    } catch (error: IOException) {
        onFailure(error)
    }
}
