package com.nuvio.tv.ui.screens.stream

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.core.streams.StreamBadgePlacement
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.SourceChipItem
import com.nuvio.tv.ui.components.SourceChipStatus
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real TV Compose presentation, isolated fixtures: no profile, provider or playback-engine writes. */
@RunWith(AndroidJUnit4::class)
class SourceJourneyPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val loading = mutableStateOf(false)
    private val error = mutableStateOf<String?>(null)
    private val candidates = mutableStateOf<List<Stream>>(emptyList())
    private var selected = 0
    private var retried = 0

    private val stream = Stream(
        name = "Playable fixture", title = null, description = "Fixture edition", url = "https://fixture.invalid/video",
        ytId = null, infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
        addonName = "Fast source", addonLogo = null
    )

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            NuvioTheme {
                RightStreamSection(
                    isLoading = loading.value, error = error.value, streams = candidates.value,
                    availableAddons = listOf("Fast source", "Slow source"),
                    sourceChips = listOf(SourceChipItem("Fast source", SourceChipStatus.SUCCESS),
                        SourceChipItem("Slow source", if (loading.value) SourceChipStatus.LOADING else SourceChipStatus.ERROR)),
                    selectedAddonFilter = null, showFileSizeBadges = false, showAddonLogo = false,
                    badgePlacement = StreamBadgePlacement.BOTTOM,
                    onAddonFilterSelected = {}, onRefresh = {}, sharedStream = null, partySource = null,
                    onSharedStreamSelected = {}, onStreamSelected = { selected++ }, focusedStreamIndex = 0,
                    shouldRestoreFocusedStream = false, onRestoreFocusedStreamHandled = {}, onRetry = { retried++ },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun activate(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText(text).assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.mainClock.advanceTimeBy(64)
    }

    @Test fun usableSourceStaysSelectableWhileAnotherProviderIsPending() {
        candidates.value = listOf(stream)
        loading.value = true
        show()
        compose.onNodeWithText("Playable fixture").assertIsDisplayed()
        activate("Playable fixture")
        compose.runOnIdle { assertEquals(1, selected) }
        capture("pending-with-usable-source")
    }

    @Test fun aSlowProviderTimeoutDoesNotReplaceUsableSourcesWithAnErrorScreen() {
        candidates.value = listOf(stream)
        error.value = context.getString(R.string.stream_source_query_timed_out)
        show()
        compose.onNodeWithText("Playable fixture").assertIsDisplayed()
        activate("Playable fixture")
        compose.runOnIdle { assertEquals(1, selected) }
        capture("timeout-with-usable-source")
    }

    @Test fun emptyTimeoutDisplaysTheCauseAndOffersAWorkingRetry() {
        error.value = context.getString(R.string.stream_source_query_timed_out)
        show()
        compose.onNodeWithText(error.value!!).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.stream_retry)).assertIsDisplayed()
        activate(context.getString(R.string.stream_retry))
        compose.runOnIdle { assertEquals(1, retried) }
        capture("empty-timeout-retry")
    }

    @Test fun lateProviderFailureKeepsRemoteFocusAndDoesNotStartPlayback() {
        candidates.value = listOf(stream)
        show()
        compose.onNodeWithText("Playable fixture").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("Playable fixture").assertIsFocused()
        compose.runOnIdle { error.value = context.getString(R.string.stream_source_query_timed_out) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("Playable fixture").assertIsFocused().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, selected) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { assertEquals(1, selected) }
    }

    private fun capture(name: String) {
        val folder = File(context.getExternalFilesDir(null), "source-journey").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
