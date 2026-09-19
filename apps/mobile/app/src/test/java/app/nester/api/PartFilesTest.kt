package app.nester.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PartFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val hour: Long = 60 * 60 * 1000

    @Test
    fun deletesOnlyOldPartFiles() {
        val now = System.currentTimeMillis()
        val oldPart = tmp.newFile("movie.part").apply { setLastModified(now - 25 * hour) }
        val freshPart = tmp.newFile("doc.part")
        val oldRegular = tmp.newFile("notes.txt").apply { setLastModified(now - 48 * hour) }
        val nestedOldPart = File(tmp.newFolder("sub"), "old.part").apply {
            writeText("x")
            setLastModified(now - 30 * hour)
        }

        val deleted = sweepStalePartFiles(tmp.root, now)

        assertEquals(2, deleted)
        assertFalse(oldPart.exists())
        assertFalse(nestedOldPart.exists())
        assertTrue(freshPart.exists())
        assertTrue(oldRegular.exists())
    }

    @Test
    fun boundaryWithinMaxAgeIsKept() {
        val now = System.currentTimeMillis()
        val kept = tmp.newFile("edge.part").apply { setLastModified(now - 24 * hour + 1000) }
        assertEquals(0, sweepStalePartFiles(tmp.root, now))
        assertTrue(kept.exists())
    }

    @Test
    fun missingRootIsNoop() {
        assertEquals(0, sweepStalePartFiles(File(tmp.root, "nope"), System.currentTimeMillis()))
    }
}
