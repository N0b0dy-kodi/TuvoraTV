package com.nuvio.tv.data.mediaserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistRewriterTest {
    private val authority = "nas.local:8920"
    private val base = "http://127.0.0.1:5000/tok"

    @Test
    fun `references to the pinned server go back through the relay`() {
        val playlist = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://nas.local:8920/keys/1\"\n#EXTINF:6,\nhttps://nas.local:8920/hls/0.ts?x=1\n#EXTINF:6,\n/hls/1.ts\nrel/2.ts\nhttps://cdn.example.com/3.ts\n#EXT-X-ENDLIST\n"
        val out = HlsPlaylistRewriter.rewrite(playlist, authority, base)
        assertEquals("pinned absolute and root-relative rewritten, the rest untouched", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"$base/keys/1\"\n#EXTINF:6,\n$base/hls/0.ts?x=1\n#EXTINF:6,\n$base/hls/1.ts\nrel/2.ts\nhttps://cdn.example.com/3.ts\n#EXT-X-ENDLIST\n", out)
    }

    @Test
    fun `crlf playlists keep their line endings`() {
        assertEquals("crlf", "#EXTM3U\r\n$base/a.ts\r\n", HlsPlaylistRewriter.rewrite("#EXTM3U\r\n/a.ts\r\n", authority, base))
    }

    @Test
    fun `other hosts and protocol relative urls are not touched`() {
        val text = "#EXTM3U\n//evil.example/x.ts\nhttps://nas.local:9999/other-port.ts\n"
        assertEquals("unchanged", text, HlsPlaylistRewriter.rewrite(text, authority, base))
    }

    @Test
    fun `playlists are recognised by type or extension`() {
        assertTrue("apple type", HlsPlaylistRewriter.isPlaylist("application/vnd.apple.mpegurl; charset=utf-8", "/x"))
        assertTrue("x-mpegurl", HlsPlaylistRewriter.isPlaylist("application/x-mpegURL", "/x"))
        assertTrue("extension", HlsPlaylistRewriter.isPlaylist(null, "/hls/main.m3u8?token=1"))
        assertFalse("a video", HlsPlaylistRewriter.isPlaylist("video/mp4", "/Videos/1/stream"))
    }
}
