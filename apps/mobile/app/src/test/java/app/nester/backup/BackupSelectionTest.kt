package app.nester.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSelectionTest {

    private fun item(
        id: String,
        name: String = "IMG_1.jpg",
        dir: String = "DCIM/Camera",
        size: Long = 1024,
    ) = MediaItem(
        mediaStoreId = id,
        displayName = name,
        relativeDir = dir,
        size = size,
        dateModifiedS = 1000,
        isVideo = false,
        contentUri = "content://media/external/images/media/$id",
    )

    @Test
    fun selectsOnlyNotYetUploaded() {
        val all = listOf(item("1"), item("2"), item("3"))
        val plan = planBackup(all, uploaded = mapOf("2" to 1000L))
        assertEquals(listOf("1", "3"), plan.map { it.item.mediaStoreId })
    }

    @Test
    fun selectsAllWhenNothingUploaded() {
        val all = listOf(item("1"), item("2"))
        val plan = planBackup(all, uploaded = emptyMap())
        assertEquals(listOf("1", "2"), plan.map { it.item.mediaStoreId })
    }

    @Test
    fun selectsNoneWhenAllUploaded() {
        val all = listOf(item("1"), item("2"))
        val plan = planBackup(all, uploaded = mapOf("1" to 1L, "2" to 2L))
        assertTrue(plan.isEmpty())
    }

    @Test
    fun remotePathJoinsDirAndName() {
        assertEquals("DCIM/Camera/IMG_1.jpg", buildRemotePath("DCIM/Camera/", "IMG_1.jpg"))
        assertEquals("DCIM/Camera/IMG_1.jpg", buildRemotePath("DCIM/Camera", "IMG_1.jpg"))
    }

    @Test
    fun remotePathDropsEmptyAndDotDotSegments() {
        assertEquals("Pictures/IMG_1.jpg", buildRemotePath("Pictures", "IMG_1.jpg"))
        assertEquals("IMG_1.jpg", buildRemotePath("", "IMG_1.jpg"))
        assertEquals("IMG_1.jpg", buildRemotePath("..", "IMG_1.jpg"))
        assertEquals("a/b", buildRemotePath("/a/", "/b"))
    }

    @Test
    fun remotePathSanitizesSeparatorCharsInNames() {
        assertEquals("DCIM/Camera/IMG_1_jpg", buildRemotePath("DCIM/Camera", "IMG_1\\jpg"))
        assertEquals("DCIM/Camera/has_backslash", buildRemotePath("DCIM/Camera", sanitizeName("has\\backslash")))
    }

    @Test
    fun sanitizeReplacesForbiddenChars() {
        assertEquals("a_b", sanitizeName("a/b"))
        assertEquals("a_b", sanitizeName("a\\b"))
        assertEquals("clean.jpg", sanitizeName("clean.jpg"))
        assertEquals("has space.jpg", sanitizeName("has space.jpg"))
    }

    @Test
    fun planKeepsOrder() {
        val all = listOf(item("9"), item("1"), item("5"))
        val plan = planBackup(all, uploaded = emptyMap())
        assertEquals(listOf("9", "1", "5"), plan.map { it.item.mediaStoreId })
        assertEquals("DCIM/Camera/IMG_1.jpg", plan.first().remotePath)
    }
}
