package com.nuvio.tv.playback.media3

import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.SurfaceMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlayerViewMedia3SurfaceHostTest {
    private fun player() = mockk<ExoPlayer>(relaxed = true) {
        every { applicationLooper } returns Looper.getMainLooper()
        every { isCommandAvailable(any()) } returns false
    }

    @Test fun `uncertain renderer detach keeps the view bound and prevents disposal`() = runTest {
        val view = PlayerView(RuntimeEnvironment.getApplication())
        var releases = 0
        val host = PlayerViewMedia3SurfaceHost(view, Dispatchers.Unconfined) { releases++ }
        val lease = (host.acquire(SurfaceMode.SURFACE_VIEW, false) as PlaybackResult.Success).value
        val player = player()
        every { player.clearVideoSurfaceWithResult() } returnsMany listOf(false, true)
        lease.attach(player)
        assertSame(player, view.player)
        assertFalse(lease.detach(player))
        assertSame(player, view.player)
        assertFalse(lease.release())
        assertEquals(0, releases)
        assertTrue(lease.detach(player))
        assertNull(view.player)
        assertTrue(lease.release())
        assertTrue(lease.release())
        assertEquals(1, releases)
    }

    @Test fun `proof of player death permits reuse of the same presentation view`() = runTest {
        val view = PlayerView(RuntimeEnvironment.getApplication())
        val host = PlayerViewMedia3SurfaceHost(view, Dispatchers.Unconfined)
        val lease = (host.acquire(SurfaceMode.SURFACE_VIEW, true) as PlaybackResult.Success).value
        val first = player()
        lease.attach(first)
        lease.confirmPlayerReleased()
        val replacement = player()
        lease.attach(replacement)
        assertSame(replacement, view.player)
        assertFalse(lease.release())
        lease.confirmPlayerReleased()
        assertTrue(lease.release())
        assertNull(view.player)
    }

    @Test fun `a different player cannot detach the current binding`() = runTest {
        val view = PlayerView(RuntimeEnvironment.getApplication())
        val host = PlayerViewMedia3SurfaceHost(view, Dispatchers.Unconfined)
        val lease = (host.acquire(SurfaceMode.SURFACE_VIEW, false) as PlaybackResult.Success).value
        val owner = player()
        lease.attach(owner)
        assertFalse(lease.detach(player()))
        assertSame(owner, view.player)
        assertFalse(lease.release())
        lease.confirmPlayerReleased()
        assertTrue(lease.release())
    }

    @Test fun `unsupported modes and already bound views fail closed`() = runTest {
        val view = PlayerView(RuntimeEnvironment.getApplication())
        val host = PlayerViewMedia3SurfaceHost(view, Dispatchers.Unconfined)
        assertTrue(host.acquire(SurfaceMode.TEXTURE_VIEW, false) is PlaybackResult.Failure)
        view.player = player()
        assertTrue(host.acquire(SurfaceMode.SURFACE_VIEW, false) is PlaybackResult.Failure)
        view.player = null
    }
}
