package com.nuvio.tv.data.mediaserver

internal class MemoryServerTrustStore : ServerTrustStore {
    private val pins = mutableMapOf<String, String>()
    override fun pinned(authority: String): String? = pins[authority.lowercase()]
    override fun pin(authority: String, fingerprint: String) { pins[authority.lowercase()] = fingerprint }
    override fun unpin(authority: String) { pins.remove(authority.lowercase()) }
    override fun clearAll() = pins.clear()
}
