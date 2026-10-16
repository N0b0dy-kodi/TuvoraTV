package com.nuvio.tv.ui.screens.party

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.party.PartyMedia
import com.nuvio.tv.core.party.PartyProfile
import com.nuvio.tv.core.party.PartyUiState
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The player's party panel sits over the video with the player controls dimmed behind it. D-pad
 * focus must stay inside the panel while it is open (Back closes it); it must never slip onto the
 * dimmed controls, where presses would act on a player the user cannot see.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PartyPanelFocusTest {
    @get:Rule val rule = createComposeRule()

    private fun showPanel() {
        rule.setContent {
            NuvioTheme {
                Row {
                    // Stands in for the player controls behind the scrim.
                    Box(Modifier.size(80.dp).testTag("behind").focusable())
                    Box(Modifier.width(520.dp).fillMaxHeight()) {
                        PartyPanel(
                            state = PartyUiState(),
                            profile = PartyProfile(name = "Me", imageUrl = null, color = null),
                            playerMedia = PartyMedia(type = "movie", metaId = "tt1", metaType = "movie", title = "Film"),
                            isSupported = false,
                            canStart = false,
                            canShareStream = false,
                            onStart = {},
                            onSwitchParty = {},
                            onWatchWithParty = {},
                            onLeave = {},
                            onEnd = {},
                            onRemove = {},
                            onHostOnlyChange = {},
                            onSharingChange = {},
                            behindMs = null,
                            onCatchUp = {},
                            onClose = {},
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun nothingToStartFocusesClose() {
        showPanel()
        rule.onNodeWithText("Close").assertIsFocused()
    }

    @Test
    fun dpadCannotLeaveThePanelForTheDimmedControls() {
        showPanel()
        rule.onNodeWithText("Close").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        rule.onNodeWithTag("behind").assertIsNotFocused()
        rule.onNodeWithText("Close").assertIsFocused()
    }
}
