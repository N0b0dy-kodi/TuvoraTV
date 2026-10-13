package com.nuvio.tv.ui.screens.iptv

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.iptv.XtreamProgram
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The actual guide info pane must follow time without a new EPG response. */
@RunWith(AndroidJUnit4::class)
class GuideInfoBoundaryTest {
    @get:Rule val compose = createComposeRule()
    private val minute = 60_000L
    private fun programme(title: String, start: Long, end: Long, markedNow: Boolean = false) =
        XtreamProgram(title, "", start * minute, end * minute, markedNow)

    @Test fun clockTickPromotesNextWithoutReloadingGuideRows() {
        val first = programme("Morning News", 60, 120, true)
        val second = programme("Nature Hour", 120, 180)
        val third = programme("Cooking Live", 180, 240)
        val epg = GuideEpg(first, second, listOf(first, second, third))
        val clock = mutableLongStateOf(119 * minute)
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(600.dp, 210.dp).background(Color.Black)) {
                    PreviewInfoPane("Fixture TV", epg, clock.longValue, null)
                }
            }
        }
        compose.onNodeWithText("Morning News").assertIsDisplayed()
        save("before-boundary")
        compose.runOnIdle { clock.longValue = 120 * minute }
        compose.waitForIdle()
        save("after-boundary")
        compose.onNodeWithText("Nature Hour").assertIsDisplayed()
        compose.onNodeWithText("Morning News").assertDoesNotExist()
        compose.onNodeWithText("Cooking Live", substring = true).assertIsDisplayed()
    }

    private fun save(stage: String) {
        val phase = InstrumentationRegistry.getArguments().getString("evidencePhase") ?: "green"
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(directory, "guide-$phase-$stage.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
