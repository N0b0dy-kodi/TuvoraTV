package com.nuvio.tv.ui.screens.player.clean.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Zapping changes the aimed channel on every press. The overlay must swap the text that changed and
 * leave everything else where it was — a name that jumps up and down between presses can't be read.
 */
@RunWith(AndroidJUnit4::class)
class LiveControlsOverlayZapDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var info by mutableStateOf(RICH)

    private fun noop() = Unit
    private val actions = LiveOverlayActions(
        onPlayPause = ::noop, onZapPrevious = ::noop, onZapNext = ::noop, onRetry = ::noop,
        onSubtitles = ::noop, onAudio = ::noop, onAspect = null, onChannelList = null,
        onToggleFavourite = null, onStreamInfo = null, onToggleMore = ::noop, onKeepAlive = ::noop, onHide = ::noop,
    )

    @Test fun nameStaysPutWhenTheNextChannelHasLessToShow() {
        compose.setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize()) {
                    LiveControlsOverlay(
                        info = info, nowMs = 0, paused = false, favourite = false, moreOpen = false,
                        buttons = LiveControlsPolicy.buttons(false, 0, 0, hasLineup = false),
                        primaryFocus = FocusRequester(), actions = actions,
                    )
                }
            }
        }
        compose.onNodeWithText("Rich Channel").assertIsDisplayed()
        val rich = compose.onNodeWithText("Rich Channel").getUnclippedBoundsInRoot()
        save("zap-overlay-with-programme")

        info = BARE
        compose.waitForIdle()
        compose.onNodeWithText("Bare Channel").assertIsDisplayed()
        val bare = compose.onNodeWithText("Bare Channel").getUnclippedBoundsInRoot()
        save("zap-overlay-without-programme")

        assertEquals("name top", rich.top.value, bare.top.value, 0.5f)
        assertEquals("name left", rich.left.value, bare.left.value, 0.5f)
    }

    /** Through the shell, into a folder that outlives the test app's uninstall. */
    private fun save(label: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val out = automation.executeShellCommand("screencap -p /sdcard/Download/$label.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
    }

    private companion object {
        val RICH = LiveOverlayInfo(
            channelName = "Rich Channel", logoUrl = null, reserveLogoSlot = true,
            nowTitle = "17:00–18:00  The News", nextLine = "Next: 18:00 Weather",
        )
        val BARE = LiveOverlayInfo(channelName = "Bare Channel", reserveLogoSlot = true)
    }
}
