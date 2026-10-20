package com.nuvio.tv.playback.mpv

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.playback.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Real libmpv, surface and session recovery against a finite synthetic live fixture.
 * An EOF is intentional here: it must either recover with new frames or report a failure.
 * Requires the local release-gate fixture on host port 9030. Never touches profile data.
 */
@RunWith(AndroidJUnit4::class)
class MpvLiveReconnectDeviceTest {
    @Test fun repeatedFiniteLiveEofRecoversWithFrames() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val emulator = android.os.Build.MODEL.contains("sdk")
        val hardwareGate = arguments.getString("hardwareReleaseGate") == "true"
        org.junit.Assume.assumeTrue("Requires explicit isolated emulator or muted hardware gate",
            (emulator && arguments.getString("isolatedReleaseGate") == "true") || hardwareGate)
        val fixtureUrl = if (hardwareGate) {
            // Hardware uses an ADB-reversed local fixture; never open a real provider in this test.
            require(arguments.getString("fixtureBaseUrl") == "http://127.0.0.1:19022")
            "http://127.0.0.1:19022/finite.ts"
        } else "http://10.0.2.2:9030/media/portal.ts"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val reader = ImageReader.newInstance(320, 180, ImageFormat.PRIVATE, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
        val engine = MpvEngine(scope, ViewMpvSurfaceHost({ reader.surface }, { reader.surface }),
            AndroidMpvBackendFactory(context), com.nuvio.tv.core.memory.AndroidPlayerMemoryBudget(context).demuxerBytes())
        val graph = PlaybackGraph("mpv-reconnect-device", EngineType.LIBMPV, GraphOutputProfile.MPV_RENDER,
            DecoderMode.HARDWARE, AudioMode.DECODE, SurfaceMode.GPU_RENDER)
        val evidence = StreamEvidence(
            delivery = EvidenceFact(DeliveryType.PROGRESSIVE, EvidenceProvenance.PROVIDER_DECLARED),
            container = EvidenceFact(ContainerType.MPEG_TS, EvidenceProvenance.PROVIDER_DECLARED))
        val requirements = PlaybackRequirements(
            profile = SessionProfile.FULLSCREEN, priority = SessionPriority.QUALITY_AND_STABILITY,
            qualityIntent = VideoQualityIntent.FULL, displayModeSwitchAllowed = false,
            frameRatePreference = FrameRatePreference.OFF, hdrPreference = HdrPreference.AUTO,
            decoderPreference = DecoderPreference.AUTO, softwareDecodeFallbackAllowed = true,
            subtitleFidelity = SubtitleFidelity.COMPATIBLE, subtitlesEnabled = false,
            audioOutput = AudioOutputPreference.PCM, pcmProcessingAllowed = true,
            buffering = BufferingPreference.RECOMMENDED, gpuRenderingAllowed = true,
            eligibleEngines = setOf(EngineType.LIBMPV), secureOutputRequired = false,
            resourceBudget = ResourceBudget(), liveContent = true)
        val entries = CopyOnWriteArrayList<String>()
        fun record(s: String) { entries += "${System.currentTimeMillis()} $s"; Log.i("ReconnectDeviceTest", s) }
        val session = PlaybackSession(scope,
            PlaybackRequestResolver { request -> PlaybackResult.Success(ResolvedPlaybackRequest(request, request.summary(), evidence)) },
            PlaybackEnvironmentProvider { input -> PlaybackResult.Success(PlaybackEnvironmentResolution(
                PlaybackEnvironmentSnapshot(RuntimeCapabilities(snapshotVersion = 1, capturedAtEpochMs = System.currentTimeMillis(), apiLevel = android.os.Build.VERSION.SDK_INT,
                    display = DisplayCapabilities(VideoDimensions(1920, 1080)), audioRoute = AudioRouteCapabilities(AudioRoute.TV_SPEAKERS),
                    resources = ResourceCapabilities(1_000_000_000, false), surfaces = SurfaceCapabilities()), secureOutputRequired = false), input.effectivePreferences)) },
            PlaybackRequirementsResolver { PlaybackResult.Success(requirements) },
            PlaybackGraphProvider { PlaybackResult.Success(listOf(graph)) },
            PlaybackEngineRegistry { if (it == EngineType.LIBMPV) engine else null },
            object : PlaybackOutputController {
                override suspend fun apply(request: PlaybackOutputRequest) = PlaybackResult.Success(PlaybackOutputApplication(PlaybackOutputStatus.DISABLED))
                override suspend fun reset(releasedGeneration: Long?, reason: ActiveWorkReleaseReason) = PlaybackResult.Success(Unit)
            },
            object : PlaybackClock {
                override fun nowEpochMs() = System.currentTimeMillis()
                override suspend fun delayMs(durationMs: Long) = delay(durationMs)
            }, PlaybackDiagnostics { record("diagnostic=$it") })
        val events = CopyOnWriteArrayList<PlaybackEvent>()
        val collector = scope.launch {
            session.events.collect { event ->
                if (event is PlaybackEvent.FirstVideoFrame || event is PlaybackEvent.PlaybackEnded || event is PlaybackEvent.Failed) {
                    events += event; record("event=$event")
                }
            }
        }
        val snapshots = scope.launch {
            var previous = ""
            session.snapshot.collect {
                val concise = "state=${it.state} playing=${it.isPlaying} reconnecting=${it.isReconnecting} " +
                    "positionSec=${it.positionMs / 1000} tracks=${it.tracks} progress=${it.progress} failure=${it.failure}"
                if (concise != previous) { record("snapshot=$concise"); previous = concise }
            }
        }
        try {
            session.dispatch(PlaybackCommand.SurfaceAvailable)
            session.dispatch(PlaybackCommand.Tune(PlaybackRequest(fixtureUrl, contentType = ContentType.LIVE), SessionProfile.FULLSCREEN))
            withTimeout(185_000) {
                while (events.filterIsInstance<PlaybackEvent.FirstVideoFrame>().size < 6) {
                    if (session.snapshot.value.state == PlaybackState.FAILED) error("Terminal playback failure: ${session.snapshot.value}")
                    delay(250)
                }
            }
            assertTrue("must exercise at least five real EOFs", events.filterIsInstance<PlaybackEvent.PlaybackEnded>().count { it.reason == PlaybackEndReason.EOF } >= 5)
        } finally {
            record("final=${session.snapshot.value}")
            withContext(NonCancellable) { withTimeoutOrNull(20_000) { session.release() } }
            collector.cancel(); snapshots.cancel(); scope.cancel(); reader.close()
            File(context.getExternalFilesDir(null), "mpv-live-reconnect-device.txt").writeText(entries.joinToString("\n"))
        }
    }
}
