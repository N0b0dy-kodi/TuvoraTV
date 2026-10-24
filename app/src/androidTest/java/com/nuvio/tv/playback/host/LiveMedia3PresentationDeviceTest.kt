package com.nuvio.tv.playback.host

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.memory.AndroidPlayerMemoryBudget
import com.nuvio.tv.playback.core.*
import com.nuvio.tv.playback.media3.AndroidMedia3BackendFactory
import com.nuvio.tv.playback.media3.Media3Engine
import com.nuvio.tv.playback.ui.PlaybackSessionController
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Same real session, decoder and fixtures; only the presentation adapter changes. */
@RunWith(AndroidJUnit4::class)
class LiveMedia3PresentationDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var backAction by mutableStateOf<(() -> Unit)?>(null)

    @Test fun rawSurfaceBaselineTransitions(): Unit = runBlocking { compare(playerView = false) }
    @Test fun playerViewTransitions(): Unit = runBlocking { compare(playerView = true) }

    private suspend fun compare(playerView: Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolatedReleaseGate") == "true")
        assumeTrue(Build.MODEL.contains("sdk") ||
            InstrumentationRegistry.getArguments().getString("hardwarePlaybackGate") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ready = CompletableDeferred<FrameLayout>()
        compose.setContent {
            BackHandler(backAction != null) { backAction?.invoke() }
            AndroidView(factory = { FrameLayout(it).also { root -> ready.complete(root) } }, modifier = Modifier.fillMaxSize())
        }
        val root = ready.await()
        val owner = withContext(Dispatchers.Main.immediate) {
            FrameLayout(context).also { root.addView(it, FrameLayout.LayoutParams(320, 180)) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val surfaces = withContext(Dispatchers.Main.immediate) {
            CleanLiveSurfaceCoordinator(owner, scope, setOf(SurfaceMode.SURFACE_VIEW), true,
                media3PlayerViewEnabled = playerView)
        }
        val engine = Media3Engine(scope, surfaces.media3SurfaceHost,
            AndroidMedia3BackendFactory(context, OkHttpClient(), playerMemoryBudget = AndroidPlayerMemoryBudget(context)))
        val graph = PlaybackGraph("media3-presentation", EngineType.MEDIA3, GraphOutputProfile.MEDIA3_STANDARD,
            DecoderMode.HARDWARE, AudioMode.DECODE, SurfaceMode.SURFACE_VIEW)
        val evidence = StreamEvidence(
            delivery = EvidenceFact(DeliveryType.PROGRESSIVE, EvidenceProvenance.PROVIDER_DECLARED),
            container = EvidenceFact(ContainerType.MPEG_TS, EvidenceProvenance.PROVIDER_DECLARED))
        val session = PlaybackSession(scope,
            PlaybackRequestResolver { request -> PlaybackResult.Success(ResolvedPlaybackRequest(request, request.summary(), evidence)) },
            PlaybackEnvironmentProvider { input -> PlaybackResult.Success(PlaybackEnvironmentResolution(
                PlaybackEnvironmentSnapshot(RuntimeCapabilities(1, System.currentTimeMillis(), Build.VERSION.SDK_INT,
                    display = DisplayCapabilities(VideoDimensions(1920, 1080)), audioRoute = AudioRouteCapabilities(AudioRoute.TV_SPEAKERS),
                    resources = ResourceCapabilities(1_000_000_000, false), surfaces = surfaces.capabilities), secureOutputRequired = false), input.effectivePreferences)) },
            PlaybackRequirementsResolver { PlaybackResult.Success(requirements()) },
            PlaybackGraphProvider { PlaybackResult.Success(listOf(graph)) },
            PlaybackEngineRegistry { if (it == EngineType.MEDIA3) engine else null },
            object : PlaybackOutputController {
                override suspend fun apply(request: PlaybackOutputRequest) = PlaybackResult.Success(PlaybackOutputApplication(PlaybackOutputStatus.DISABLED))
                override suspend fun reset(releasedGeneration: Long?, reason: ActiveWorkReleaseReason) = PlaybackResult.Success(Unit)
            },
            object : PlaybackClock {
                override fun nowEpochMs() = System.currentTimeMillis()
                override suspend fun delayMs(durationMs: Long) = delay(durationMs)
            }, PlaybackDiagnostics {})
        val controller = PlaybackSessionController(session)
        val events = CopyOnWriteArrayList<PlaybackEvent>()
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) { session.events.collect { events += it } }
        val exit = CompletableDeferred<Unit>()
        val clips = listOf("A", "B").map { channel ->
            File(context.cacheDir, "presentation-$channel.ts").also { file ->
                instrumentation.context.assets.open("live-surface.ts").use { input -> file.outputStream().use { input.copyTo(it) } }
            }
        }
        val output = File(context.getExternalFilesDir(null), "media3-presentation/${if (playerView) "player-view" else "raw"}").apply { mkdirs() }
        val facts = mutableListOf("model=${Build.MODEL}", "api=${Build.VERSION.SDK_INT}", "player_view=$playerView")
        suspend fun waitFor(label: String, predicate: () -> Boolean) {
            withTimeout(35_000) {
                while (!predicate()) {
                    check(session.snapshot.value.state != PlaybackState.FAILED) { "$label: ${session.snapshot.value.failure}" }
                    delay(25)
                }
            }
            facts += "$label=true"
        }
        try {
            assertTrue(surfaces.bindController(controller))
            assertTrue(surfaces.startHosting())
            withContext(Dispatchers.Main.immediate) {
                backAction = {
                    scope.launch {
                        if (session.snapshot.value.profile == SessionProfile.FULLSCREEN) {
                            controller.changeProfile(SessionProfile.GUIDE)
                            owner.layoutParams = FrameLayout.LayoutParams(320, 180)
                        } else {
                            controller.release()
                            check(surfaces.disposeAfterSessionRelease())
                            root.removeView(owner)
                            exit.complete(Unit)
                        }
                    }
                }
            }
            // Advance the Compose test clock so BackHandler observes the installed callback.
            compose.waitForIdle()
            controller.tune(PlaybackRequest(android.net.Uri.fromFile(clips[0]).toString(), contentType = ContentType.LIVE), SessionProfile.GUIDE)
            waitFor("initial_video") { events.any { it is PlaybackEvent.FirstVideoFrame } }
            val presentation = withContext(Dispatchers.Main.immediate) { owner.getChildAt(0) }
            assertEquals(playerView, presentation is PlayerView)
            val surface = withContext(Dispatchers.Main.immediate) { findSurface(presentation)!! }
            assertMovingPicture(surface, File(output, "preview.png"))
            controller.changeProfile(SessionProfile.FULLSCREEN)
            withContext(Dispatchers.Main.immediate) {
                owner.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            waitFor("fullscreen_profile") { session.snapshot.value.profile == SessionProfile.FULLSCREEN }
            assertMovingPicture(surface, File(output, "fullscreen.png"))
            compose.waitForIdle()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("back_to_guide") { session.snapshot.value.profile == SessionProfile.GUIDE }
            assertMovingPicture(surface, File(output, "back-to-guide.png"))
            val firstFrames = events.count { it is PlaybackEvent.FirstVideoFrame }
            controller.zap(PlaybackRequest(android.net.Uri.fromFile(clips[1]).toString(), contentType = ContentType.LIVE), SessionProfile.GUIDE)
            waitFor("channel_switch_video") { events.count { it is PlaybackEvent.FirstVideoFrame } > firstFrames }
            assertMovingPicture(surface, File(output, "channel-B.png"))
            withContext(Dispatchers.Main.immediate) {
                assertSame("Profile changes and zapping retain the presentation", presentation, owner.getChildAt(0))
                assertSame(surface, findSurface(presentation))
            }
            facts += "same_view_and_surface_through_zap=true"
            val beforeEof = events.count { it is PlaybackEvent.FirstVideoFrame }
            waitFor("live_eof_observed") { events.any { it is PlaybackEvent.PlaybackEnded && it.reason == PlaybackEndReason.EOF } }
            waitFor("live_reconnect_video") { events.count { it is PlaybackEvent.FirstVideoFrame } > beforeEof }
            // Existing reconnect policy affirmatively releases the engine and reacquires output.
            // Sampling the old SurfaceView here would test a deliberately released surface.
            val reconnectedSurface = withContext(Dispatchers.Main.immediate) {
                val currentPresentation = checkNotNull(owner.getChildAt(0))
                assertEquals(playerView, currentPresentation is PlayerView)
                checkNotNull(findSurface(currentPresentation))
            }
            assertMovingPicture(reconnectedSurface, File(output, "reconnected.png"))
            facts += "moving_video_after_reacquisition=true"
            compose.waitForIdle()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            withTimeout(25_000) { exit.await() }
            facts += "back_release_before_removal=true"
            assertEquals(0, withContext(Dispatchers.Main.immediate) { root.childCount })
        } finally {
            withContext(NonCancellable) { withTimeoutOrNull(25_000) { session.release() } }
            collector.cancel()
            withContext(Dispatchers.Main.immediate) { backAction = null }
            scope.cancel()
            clips.forEach(File::delete)
            facts += "decoder_events=${events.filterIsInstance<PlaybackEvent.VideoDecoderInitialized>()}"
            File(output, "results.txt").writeText(facts.joinToString("\n"))
        }
    }

    private fun findSurface(view: View): SurfaceView? = when (view) {
        is SurfaceView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findSurface(view.getChildAt(it)) }
        else -> null
    }

    private suspend fun assertMovingPicture(surface: SurfaceView, file: File) {
        var initial: Int? = null
        withTimeout(8_000) {
            while (true) {
                val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
                val copied = withContext(Dispatchers.Main.immediate) {
                    suspendCancellableCoroutine { continuation ->
                        PixelCopy.request(surface, bitmap, { result ->
                            if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
                        }, Handler(Looper.getMainLooper()))
                    }
                }
                val pixels = IntArray(160 * 90)
                if (copied) bitmap.getPixels(pixels, 0, 160, 0, 0, 160, 90)
                val visible = copied && pixels.count { (it and 0x00ffffff) > 0x00202020 } > 100
                val hash = pixels.contentHashCode()
                val moving = visible && initial != null && hash != initial
                if (visible && initial == null) initial = hash
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                if (moving) break
                delay(100)
            }
        }
    }

    private fun requirements() = PlaybackRequirements(
        profile = SessionProfile.FULLSCREEN, priority = SessionPriority.QUALITY_AND_STABILITY,
        qualityIntent = VideoQualityIntent.FULL, displayModeSwitchAllowed = false,
        frameRatePreference = FrameRatePreference.OFF, hdrPreference = HdrPreference.AUTO,
        decoderPreference = DecoderPreference.AUTO, softwareDecodeFallbackAllowed = true,
        subtitleFidelity = SubtitleFidelity.COMPATIBLE, subtitlesEnabled = false,
        audioOutput = AudioOutputPreference.PCM, pcmProcessingAllowed = true,
        buffering = BufferingPreference.RECOMMENDED, gpuRenderingAllowed = false,
        eligibleEngines = setOf(EngineType.MEDIA3), allowedSurfaceModes = setOf(SurfaceMode.SURFACE_VIEW),
        secureOutputRequired = false, resourceBudget = ResourceBudget(), liveContent = true,
    )
}
