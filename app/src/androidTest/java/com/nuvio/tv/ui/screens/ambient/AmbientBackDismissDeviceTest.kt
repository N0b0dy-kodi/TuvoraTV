package com.nuvio.tv.ui.screens.ambient

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real system Back must dismiss the app's poster layer without navigating its underlying page. */
@RunWith(AndroidJUnit4::class)
class AmbientBackDismissDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun firstSystemBackDismissesSlidesAndSecondReachesUnderlyingPage() {
        val controller = AmbientIdleController()
        var pageBacks = 0
        compose.setContent {
            val showing by controller.isShowing.collectAsState()
            BackHandler { pageBacks++ }
            AmbientDismissBackHandler(controller, showing)
        }
        compose.runOnIdle { controller.show() }
        compose.waitForIdle()
        fun pressSystemBack() {
            ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4")
            ).use { it.readBytes() }
        }
        pressSystemBack()
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(controller.isShowing.value)
            assertEquals(0, pageBacks)
        }
        pressSystemBack()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, pageBacks) }
    }
}
