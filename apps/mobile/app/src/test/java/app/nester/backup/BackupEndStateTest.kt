package app.nester.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupEndStateTest {

    @Test
    fun processed_counts_all_outcomes() {
        assertEquals(7, processedCount(done = 4, skipped = 2, failed = 1))
        assertEquals(0, processedCount(done = 0, skipped = 0, failed = 0))
    }

    @Test
    fun all_failed_run_reports_zero_backed_up() {
        assertEquals(
            "Finished - 0 of 1976 backed up · 1976 failed",
            backupEndStateHeadline(done = 0, planned = 1976, skipped = 0, failed = 1976),
        )
    }

    @Test
    fun mixed_run_mentions_every_bucket() {
        assertEquals(
            "Finished - 1970 of 1976 backed up · 4 skipped · 2 failed",
            backupEndStateHeadline(done = 1970, planned = 1976, skipped = 4, failed = 2),
        )
    }
}
