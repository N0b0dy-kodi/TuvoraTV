package com.nuvio.tv.playback.media3

import androidx.media3.common.MimeTypes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareFirstVideoDecodersTest {

    private data class Fake(val name: String, val supported: Boolean)

    private fun pick(mime: String?, vararg decoders: Fake): List<String> =
        hardwareFirstLiveVideoDecoders(
            mimeType = mime,
            decoders = decoders.toList(),
            isSoftware = { hasLiveSoftwareDecoderName(it.name) },
            supportsFormat = { it.supported },
        ).map { it.name }

    @Test
    fun `software decoders are dropped when hardware supports the exact format`() {
        assertEquals(
            listOf("c2.vendor.hevc.decoder"),
            pick(
                MimeTypes.VIDEO_H265,
                Fake("c2.vendor.hevc.decoder", true),
                Fake("c2.android.hevc.decoder", true),
                Fake("OMX.google.h265.decoder", true),
            ),
        )
    }

    @Test
    fun `software is kept when hardware cannot take the format`() {
        assertEquals(
            listOf("c2.vendor.avc.decoder", "c2.android.avc.decoder"),
            pick(
                MimeTypes.VIDEO_H264,
                Fake("c2.vendor.avc.decoder", false),
                Fake("c2.android.avc.decoder", true),
            ),
        )
    }

    @Test
    fun `non gated mime types are untouched`() {
        val all = arrayOf(Fake("c2.vendor.mpeg2.decoder", true), Fake("c2.android.mpeg2.decoder", true))
        assertEquals(all.map { it.name }, pick(MimeTypes.VIDEO_MPEG2, *all))
        assertEquals(all.map { it.name }, pick(null, *all))
    }

    @Test
    fun `software name prefixes are recognised case-insensitively`() {
        listOf("c2.android.avc.decoder", "OMX.google.h264.decoder", "c2.google.av1.decoder", "OMX.ffmpeg.vp9")
            .forEach { assertTrue(it, hasLiveSoftwareDecoderName(it)) }
        assertFalse(hasLiveSoftwareDecoderName("c2.mtk.hevc.decoder"))
    }

    @Test
    fun `live renderers factory builds the hardware-first video renderer`() {
        val source = listOf("app/src/main", "src/main").map { File("$it/java/com/nuvio/tv/playback/media3/AndroidMedia3Backend.kt") }
            .first { it.exists() }.readText()
        assertTrue("ContractRenderersFactory must override buildVideoRenderers", source.contains("override fun buildVideoRenderers"))
        assertTrue("must build HardwareFirstLiveVideoRenderer", source.contains("HardwareFirstLiveVideoRenderer("))
    }
}
