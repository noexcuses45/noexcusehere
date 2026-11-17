package com.noexcusehere.mashwave

import com.noexcusehere.mashwave.ui.FloatingWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloatingWindowTest {
    @Test
    fun ordinaryVideosKeepTheirShape() {
        assertEquals(1920 to 1080, FloatingWindow.shape(1920, 1080))
        assertEquals(1080 to 1920, FloatingWindow.shape(1080, 1920))
        assertEquals(640 to 480, FloatingWindow.shape(640, 480))
    }

    @Test
    fun veryWideOrTallVideosArePulledBackToWhatAndroidAllows() {
        assertEquals(239 to 100, FloatingWindow.shape(3840, 1000))
        assertEquals(100 to 239, FloatingWindow.shape(500, 2000))
        // right at the limit is still fine as it is
        assertEquals(239 to 100, FloatingWindow.shape(239, 100))
    }

    @Test
    fun unknownSizeGivesNoShape() {
        assertNull(FloatingWindow.shape(0, 0))
        assertNull(FloatingWindow.shape(1280, 0))
    }
}
