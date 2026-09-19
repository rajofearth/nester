package app.nester.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun sizeUnits() {
        assertEquals("0 B", formatSize(0))
        assertEquals("512 B", formatSize(512))
        assertEquals("1.0 KB", formatSize(1024))
        assertEquals("1.5 MB", formatSize((1.5 * 1024 * 1024).toLong()))
        assertEquals("1.0 GB", formatSize(1024L * 1024 * 1024))
        assertEquals("6.3 GB", formatSize((6.3 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun countGroups() {
        assertEquals("1,234", formatCount(1234))
        assertEquals("12", formatCount(12))
    }

    @Test
    fun relativeTimeBuckets() {
        val now = 1_000_000_000_000L
        assertEquals("never", formatRelativeTime(0, now))
        assertEquals("never", formatRelativeTime(now / 1000 + 100, now))
        assertEquals("just now", formatRelativeTime(now / 1000 - 30, now))
        assertEquals("1m ago", formatRelativeTime(now / 1000 - 60, now))
        assertEquals("4m ago", formatRelativeTime(now / 1000 - 240, now))
        assertEquals("3h ago", formatRelativeTime(now / 1000 - 3 * 3600, now))
        assertEquals("2d ago", formatRelativeTime(now / 1000 - 2 * 86400, now))
    }

    @Test
    fun speedAndEta() {
        assertEquals("1.2 MB/s", formatSpeed(1_250_000))
        assertEquals("—", formatSpeed(0))
        assertEquals("<1m", formatEta(45))
        assertEquals("5m", formatEta(300))
        assertEquals("12m", formatEta(720))
        assertEquals("1h 30m", formatEta(5400))
        assertEquals("—", formatEta(-5))
    }

    @Test
    fun durationBuckets() {
        assertEquals("45s", formatDuration(45))
        assertEquals("12m", formatDuration(720))
        assertEquals("2h 5m", formatDuration(7500))
    }
}
