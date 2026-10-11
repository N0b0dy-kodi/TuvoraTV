package com.nuvio.tv.playback.mpv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.memory.AndroidPlayerMemoryBudget
import com.nuvio.tv.playback.core.*
import `is`.xyz.mpv.MPV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native option acceptance/readback on the bundled libmpv; no stream or provider is opened. */
@RunWith(AndroidJUnit4::class)
class MpvBufferOptionsDeviceTest {
    @Test
    fun selectedLiveBufferAndDeviceCapsAreAcceptedByBundledMpv() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val budget = AndroidPlayerMemoryBudget(context).demuxerBytes()
        for (seconds in listOf(5, 20, 60)) {
            val graph = PlaybackGraph("mpv-options", EngineType.LIBMPV, GraphOutputProfile.MPV_DIRECT,
                DecoderMode.HARDWARE, AudioMode.DECODE, SurfaceMode.NATIVE_EMBED)
            val requirements = PlaybackRequirements(
                profile = SessionProfile.FULLSCREEN, priority = SessionPriority.QUALITY_AND_STABILITY,
                qualityIntent = VideoQualityIntent.FULL, displayModeSwitchAllowed = false,
                frameRatePreference = FrameRatePreference.OFF, hdrPreference = HdrPreference.AUTO,
                decoderPreference = DecoderPreference.AUTO, softwareDecodeFallbackAllowed = false,
                subtitleFidelity = SubtitleFidelity.COMPATIBLE, subtitlesEnabled = false,
                audioOutput = AudioOutputPreference.PCM, pcmProcessingAllowed = true,
                buffering = BufferingPreference.CUSTOM, customBuffer = LiveBufferPolicy.customBuffer(seconds),
                gpuRenderingAllowed = false, eligibleEngines = setOf(EngineType.LIBMPV),
                secureOutputRequired = false, resourceBudget = ResourceBudget(), liveContent = true,
            )
            val result = MpvAdapterPlanFactory.create(PlaybackEngineStart(1,
                PlaybackRequest("https://example.invalid/not-opened", contentType = ContentType.LIVE),
                StreamEvidence(), graph, requirements, startPaused = true), budget)
            assertTrue(result is PlaybackResult.Success)
            val options = (result as PlaybackResult.Success).value.preInitOptions
            val mpv = MPV()
            mpv.create(context)
            try {
                // No video/audio output or loadfile: only exercise the real native option parser.
                assertTrue(mpv.setOptionString("vo", "null") >= 0)
                assertTrue(mpv.setOptionString("ao", "null") >= 0)
                for (key in listOf("cache", "cache-secs", "demuxer-readahead-secs",
                    "demuxer-max-bytes", "demuxer-max-back-bytes", "cache-pause-wait")) {
                    assertTrue("$key must be accepted", mpv.setOptionString(key, options.getValue(key)) >= 0)
                }
                mpv.init()
                assertEquals(seconds.toDouble(), mpv.getPropertyDouble("options/cache-secs")!!, 0.001)
                assertEquals(seconds.toDouble(), mpv.getPropertyDouble("options/demuxer-readahead-secs")!!, 0.001)
                assertEquals(budget.maxBytes, mpv.getPropertyLong("options/demuxer-max-bytes")!!.toLong())
                assertEquals(budget.maxBackBytes, mpv.getPropertyLong("options/demuxer-max-back-bytes")!!.toLong())
            } finally {
                assertTrue("native core must release", mpv.destroyWithResult())
            }
        }
    }
}
