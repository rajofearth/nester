package app.nester.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupProgressTest {

    @Test
    fun trackerIsZeroWithFewSamples() {
        val tracker = TransferRateTracker()
        tracker.record(0, 0)
        assertEquals(0L, tracker.bytesPerSecond())
    }

    @Test
    fun trackerComputesRollingSpeed() {
        val tracker = TransferRateTracker()
        tracker.record(0, 0)
        tracker.record(1000, 1000)
        assertEquals(1000L, tracker.bytesPerSecond())
        tracker.record(2000, 3000)
        assertEquals(1500L, tracker.bytesPerSecond())
    }

    @Test
    fun trackerCapsWindowAtMaxSamples() {
        val tracker = TransferRateTracker(maxSamples = 3)
        tracker.record(0, 0)
        tracker.record(1000, 1000)
        tracker.record(2000, 2000)
        tracker.record(3000, 9000)
        assertEquals(4000L, tracker.bytesPerSecond())
    }

    @Test
    fun trackerReset() {
        val tracker = TransferRateTracker()
        tracker.record(0, 0)
        tracker.record(1000, 1000)
        tracker.reset()
        assertEquals(0L, tracker.bytesPerSecond())
    }

    @Test
    fun etaHandlesNoSpeed() {
        assertNull(etaSeconds(1000, 0))
        assertNull(etaSeconds(0, 1000))
        assertEquals(10L, etaSeconds(1000, 100))
    }

    @Test
    fun failurePrefixStripsDetails() {
        assertEquals("connection refused", failurePrefix("connection refused: 192.168.1.10"))
        assertEquals("timeout", failurePrefix("timeout"))
        assertEquals("x", failurePrefix("x: y: z"))
    }

    @Test
    fun groupFailuresCountsAndSorts() {
        val groups = groupFailures(
            listOf(
                "upload failed: 500",
                "connection refused: host",
                "upload failed: 502",
                "upload failed: 500",
            ),
        )
        assertEquals(listOf(FailureGroup("upload failed", 3), FailureGroup("connection refused", 1)), groups)
    }

    @Test
    fun notBackedUpText() {
        assertEquals("Not backed up: 12 items (1.5 MB)", notBackedUpSummaryText(12, (1.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun runPhaseDefaultsSane() {
        val run = BackupRun(
            phase = BackupPhase.RUNNING,
            planIds = listOf("1", "2"),
            sentBytes = 10,
            totalBytes = 20,
            bytesPerSecond = 5,
            startedMs = 0,
        )
        assertTrue(run.endedMs == null)
        assertEquals(BackupPhase.RUNNING, run.phase)
    }
}
