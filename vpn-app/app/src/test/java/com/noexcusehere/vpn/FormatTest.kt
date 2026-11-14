package com.noexcusehere.vpn

import com.noexcusehere.vpn.ui.formatBytes
import com.noexcusehere.vpn.ui.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun bytes() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.00 KB", formatBytes(1024))
        assertEquals("1.50 KB", formatBytes(1536))
        assertEquals("12.3 MB", formatBytes((12.3 * 1024 * 1024).toLong()))
        assertEquals("512 MB", formatBytes(512L * 1024 * 1024))
        assertEquals("2.00 GB", formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun durations() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("02:05", formatDuration(125))
        assertEquals("1:02:05", formatDuration(3725))
        assertEquals("00:00", formatDuration(-3))
    }
}
