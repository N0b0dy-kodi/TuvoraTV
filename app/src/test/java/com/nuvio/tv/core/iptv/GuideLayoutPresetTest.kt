package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class GuideLayoutPresetTest {
    @Test fun `a viewer who never chose a size gets Compact`() {
        assertEquals(GuideLayoutPreset.COMPACT, GuideLayoutPreset.fromStored(null))
        assertEquals(GuideLayoutPreset.COMPACT, GuideLayoutPreset.DEFAULT)
    }

    @Test fun `an unrecognised stored value falls back to the default`() {
        assertEquals(GuideLayoutPreset.DEFAULT, GuideLayoutPreset.fromStored("HUGE"))
    }

    @Test fun `a size the viewer chose is kept, including Standard`() {
        GuideLayoutPreset.entries.forEach { assertEquals(it, GuideLayoutPreset.fromStored(it.name)) }
    }
}
