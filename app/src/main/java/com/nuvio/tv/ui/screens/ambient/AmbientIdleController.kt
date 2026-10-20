package com.nuvio.tv.ui.screens.ambient

import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AmbientIdleController(private val nowMs: () -> Long = { SystemClock.uptimeMillis() }) {
    private val _lastInteractionAt = MutableStateFlow(nowMs())
    val lastInteractionAt: StateFlow<Long> = _lastInteractionAt.asStateFlow()

    private val _isShowing = MutableStateFlow(false)
    val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

    private var playbackOwners = 0
    private val _playbackBlocked = MutableStateFlow(false)
    val playbackBlocked: StateFlow<Boolean> = _playbackBlocked.asStateFlow()

    /** Playback ownership is independent of navigation: guide preview and fullscreen share a route.
     * A lease survives buffering and closes with its Compose owner. Overlapping owners cannot
     * release one another; the final release starts a fresh idle interval.
     */
    @Synchronized
    fun blockForPlayback(): AutoCloseable {
        playbackOwners++
        _playbackBlocked.value = true
        dismiss()
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) releasePlayback()
        }
    }

    @Synchronized
    private fun releasePlayback() {
        playbackOwners--
        if (playbackOwners == 0) {
            onInteraction()
            _playbackBlocked.value = false
        }
    }

    private var swallowedKeyCode: Int? = null

    fun onInteraction() {
        _lastInteractionAt.value = nowMs()
    }

    @Synchronized
    fun show() {
        if (!_playbackBlocked.value) _isShowing.value = true
    }

    fun dismiss() {
        _isShowing.value = false
        onInteraction()
    }

    fun consumeKeyEvent(event: KeyEvent): Boolean {
        onInteraction()
        val continuesSwallowedPress = event.keyCode == swallowedKeyCode &&
            (event.action == KeyEvent.ACTION_UP || event.repeatCount > 0)
        if (continuesSwallowedPress) {
            if (event.action == KeyEvent.ACTION_UP) swallowedKeyCode = null
            return true
        }
        swallowedKeyCode = null
        if (_isShowing.value && event.action == KeyEvent.ACTION_DOWN) {
            swallowedKeyCode = event.keyCode
            dismiss()
            return true
        }
        return false
    }
}
