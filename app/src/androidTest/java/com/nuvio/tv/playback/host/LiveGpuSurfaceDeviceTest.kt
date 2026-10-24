package com.nuvio.tv.playback.host

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.memory.AndroidPlayerMemoryBudget
import com.nuvio.tv.playback.core.*
import com.nuvio.tv.playback.mpv.AndroidMpvBackendFactory
import com.nuvio.tv.playback.mpv.MpvEngine
import com.nuvio.tv.playback.ui.PlaybackSessionController
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real bundled libmpv and Android TextureView; opt-in disposable, muted emulator only. */
@RunWith(AndroidJUnit4::class)
class LiveGpuSurfaceDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun textureViewCanDisplayKnownPixelsInThisEmulator(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolatedReleaseGate") == "true")
        assumeTrue(Build.MODEL.contains("sdk"))
        val ready = CompletableDeferred<TextureView>()
        compose.setContent {
            AndroidView(factory = { TextureView(it).also { view -> ready.complete(view) } }, modifier = Modifier.fillMaxSize())
        }
        val view = ready.await()
        withTimeout(5_000) {
            while (!withContext(Dispatchers.Main.immediate) { view.isAvailable }) delay(25)
        }
        withContext(Dispatchers.Main.immediate) {
            val surface = android.view.Surface(view.surfaceTexture)
            try {
                val canvas = surface.lockCanvas(null)
                canvas.drawColor(android.graphics.Color.RED)
                surface.unlockCanvasAndPost(canvas)
            } finally { surface.release() }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "live-surface-device").apply { mkdirs() }
        assertVisiblePattern(view, output, "canvas-control", requireMotion = false)
    }

    @Test
    fun previewResizeAndUnexpectedLossReleaseOnlyAfterNativeAcknowledgement(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolatedReleaseGate") == "true")
        assumeTrue(Build.MODEL.contains("sdk") && Build.VERSION.SDK_INT >= 26)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val rootReady = CompletableDeferred<FrameLayout>()
        compose.setContent {
            AndroidView(factory = { FrameLayout(it).also(rootReady::complete) }, modifier = Modifier.fillMaxSize())
        }
        val root = rootReady.await()
        val clip = File(context.cacheDir, "live-surface-fixture.ts")
        instrumentation.context.assets.open("live-surface.ts").use { input ->
            clip.outputStream().use { input.copyTo(it) }
        }
        val facts = mutableListOf<String>()
        val output = File(context.getExternalFilesDir(null), "live-surface-device").apply { mkdirs() }
        try {
            repeat(3) { cycle ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val commands = CopyOnWriteArrayList<PlaybackCommand>()
                val owner = withContext(Dispatchers.Main.immediate) {
                    FrameLayout(context).also { root.addView(it, FrameLayout.LayoutParams(320, 180)) }
                }
                val coordinator = withContext(Dispatchers.Main.immediate) {
                    CleanLiveSurfaceCoordinator(owner, scope, setOf(SurfaceMode.GPU_RENDER), false).also {
                        it.bindController(PlaybackSessionController(MutableStateFlow(PlaybackSnapshot()), commands::add, {}))
                    }
                }
                val engine = MpvEngine(scope, coordinator.mpvSurfaceHost, AndroidMpvBackendFactory(context),
                    AndroidPlayerMemoryBudget(context).demuxerBytes())
                val generation = cycle.toLong() + 1
                val graph = PlaybackGraph("live-gpu-surface", EngineType.LIBMPV, GraphOutputProfile.MPV_RENDER,
                    DecoderMode.SOFTWARE, AudioMode.DECODE, SurfaceMode.GPU_RENDER)
                val firstFrame = scope.async(start = CoroutineStart.UNDISPATCHED) {
                    engine.events.first {
                        it is PlaybackEvent.FirstVideoFrame || it is PlaybackEvent.Failed || it is PlaybackEvent.PlaybackEnded
                    }
                }
                var released = false
                try {
                    assertTrue(coordinator.startHosting())
                    assertTrue(engine.attachSurface(generation, graph) is PlaybackResult.Success)
                    assertTrue(engine.start(PlaybackEngineStart(generation,
                        PlaybackRequest(clip.absolutePath, contentType = ContentType.LIVE), StreamEvidence(),
                        graph, requirements(), startPaused = false)) is PlaybackResult.Success)
                    val first = withTimeout(20_000) { firstFrame.await() }
                    assertTrue("Expected native video before loss, received $first", first is PlaybackEvent.FirstVideoFrame)
                    val view = withContext(Dispatchers.Main.immediate) { owner.getChildAt(0) as TextureView }
                    val texture = withContext(Dispatchers.Main.immediate) { requireNotNull(view.surfaceTexture) }
                    assertVisiblePattern(view, output, "preview-$cycle")
                    val priorFrames = frames(engine, generation)
                    withContext(Dispatchers.Main.immediate) {
                        owner.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                    withTimeout(10_000) {
                        while (!withContext(Dispatchers.Main.immediate) { owner.width == root.width && owner.width > 320 }) delay(25)
                        while (frames(engine, generation) <= priorFrames) delay(25)
                    }
                    withContext(Dispatchers.Main.immediate) {
                        assertSame("Fullscreen resize must preserve the texture", texture, view.surfaceTexture)
                    }
                    assertVisiblePattern(view, output, "fullscreen-$cycle")
                    if (cycle == 0) {
                        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                            File(output, "fullscreen-before-loss.png").outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            bitmap.recycle()
                        }
                    }
                    val fullscreenFrames = frames(engine, generation)
                    withContext(Dispatchers.Main.immediate) { owner.layoutParams = FrameLayout.LayoutParams(320, 180) }
                    withTimeout(10_000) {
                        while (!withContext(Dispatchers.Main.immediate) { owner.width == 320 }) delay(25)
                        while (frames(engine, generation) <= fullscreenFrames) delay(25)
                    }
                    // Fault injection: defer renderer teardown after the real framework callback.
                    withContext(Dispatchers.Main.immediate) { owner.removeView(view) }
                    withTimeout(5_000) {
                        while (PlaybackCommand.SurfaceUnavailable !in commands) delay(25)
                    }
                    assertFalse("Android must not destroy libmpv's active buffer queue", texture.isReleased)
                    assertTrue(withTimeout(20_000) { engine.release(generation) } is PlaybackResult.Success)
                    released = true
                    withTimeout(5_000) { while (!texture.isReleased) delay(25) }
                    assertTrue(coordinator.disposeAfterSessionRelease())
                    val fact = "cycle=$cycle first_frame=true fullscreen_frames_advance=true preview_frames_advance=true retained_until_ack=true released_after_ack=true"
                    facts += fact
                    Log.i("LiveGpuSurfaceDeviceTest", fact)
                } finally {
                    firstFrame.cancel()
                    if (!released) withContext(NonCancellable) {
                        withTimeoutOrNull(20_000) { engine.hardAbort(generation) }
                    }
                    scope.cancel()
                    withContext(Dispatchers.Main.immediate) { root.removeView(owner) }
                }
            }
        } finally {
            File(output, "results.txt").writeText(facts.joinToString("\n"))
            clip.delete()
        }
    }

    private suspend fun frames(engine: MpvEngine, generation: Long): Long {
        val result = engine.snapshotMetrics(generation)
        check(result is PlaybackResult.Success) { "Frame metrics unavailable: $result" }
        return requireNotNull(result.value.videoFramesRendered)
    }

    private suspend fun assertVisiblePattern(view: TextureView, output: File, name: String, requireMotion: Boolean = true) {
        var firstVisibleHash: Int? = null
        withTimeout(5_000) {
            while (true) {
                val visible = withContext(Dispatchers.Main.immediate) {
                    assertTrue("Native video view must be shown", view.isShown)
                    assertTrue("TextureView requires hardware acceleration", view.isHardwareAccelerated)
                    val bitmap = view.getBitmap(160, 90) ?: return@withContext false
                    try {
                        val pixels = IntArray(160 * 90)
                        bitmap.getPixels(pixels, 0, 160, 0, 0, 160, 90)
                        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        val hasPattern = pixels.count { (it and 0x00ffffff) > 0x00202020 } > 100
                        val hash = pixels.contentHashCode()
                        val changed = firstVisibleHash != null && firstVisibleHash != hash
                        if (hasPattern && firstVisibleHash == null) firstVisibleHash = hash
                        hasPattern && (!requireMotion || changed)
                    } finally { bitmap.recycle() }
                }
                if (visible) break
                delay(100)
            }
        }
    }

    private fun requirements() = PlaybackRequirements(
        profile = SessionProfile.FULLSCREEN, priority = SessionPriority.QUALITY_AND_STABILITY,
        qualityIntent = VideoQualityIntent.FULL, displayModeSwitchAllowed = false,
        frameRatePreference = FrameRatePreference.OFF, hdrPreference = HdrPreference.AUTO,
        decoderPreference = DecoderPreference.SOFTWARE_ONLY, softwareDecodeFallbackAllowed = true,
        subtitleFidelity = SubtitleFidelity.COMPATIBLE, subtitlesEnabled = false,
        audioOutput = AudioOutputPreference.PCM, pcmProcessingAllowed = true,
        buffering = BufferingPreference.RECOMMENDED, gpuRenderingAllowed = true,
        eligibleEngines = setOf(EngineType.LIBMPV), secureOutputRequired = false,
        resourceBudget = ResourceBudget(), liveContent = true,
    )
}
