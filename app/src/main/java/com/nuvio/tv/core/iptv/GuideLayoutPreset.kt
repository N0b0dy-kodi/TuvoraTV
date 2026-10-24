package com.nuvio.tv.core.iptv

/** Coherent TV layouts; typography stays legible instead of shrinking with row density. */
enum class GuideLayoutPreset(val rowHeightDp: Int, val previewHeightDp: Int, val channelWidthDp: Int) {
    COMPACT(36, 144, 210), STANDARD(44, 180, 230), LARGE(56, 180, 270);

    companion object {
        /** Shows the most channels at once; a viewer's explicit choice (including Standard) always wins. */
        val DEFAULT = COMPACT

        fun fromStored(name: String?): GuideLayoutPreset = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
