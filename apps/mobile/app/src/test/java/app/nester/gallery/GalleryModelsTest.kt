package app.nester.gallery

import app.nester.store.EntryRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryModelsTest {

    private fun entry(path: String, kind: String = "file", mtimeS: Long = 100, size: Long = 10) = EntryRecord(
        path = path,
        kind = kind,
        size = size,
        mtimeS = mtimeS,
        deleted = false,
        hash = null,
        sequence = 0,
    )

    @Test
    fun classify_splits_media_from_files() {
        assertEquals(MediaKind.PHOTO, classify("a/IMG_0001.JPG"))
        assertEquals(MediaKind.PHOTO, classify("x.webp"))
        assertEquals(MediaKind.VIDEO, classify("a/b/vid.MP4"))
        assertEquals(MediaKind.VIDEO, classify("clip.mov"))
        assertEquals(MediaKind.FILE, classify("doc.pdf"))
        assertEquals(MediaKind.FILE, classify("noext"))
        assertEquals(MediaKind.FILE, classify("a.b.c/notes.txt"))
    }

    @Test
    fun galleryViews_orders_media_newest_first_and_files_by_path() {
        val views = galleryViews(
            listOf(
                entry("c.txt", mtimeS = 5),
                entry("p1.jpg", mtimeS = 1),
                entry("p2.jpg", mtimeS = 9),
                entry("v1.mp4", mtimeS = 2),
                entry("a.txt", mtimeS = 5),
                entry("sub", kind = "dir"),
            ),
        )
        assertEquals(listOf("p2.jpg", "p1.jpg"), views.photos.map { it.path })
        assertEquals(listOf("v1.mp4"), views.videos.map { it.path })
        assertEquals(listOf("a.txt", "c.txt"), views.files.map { it.path })
    }

    @Test
    fun filter_by_dir_and_dir_listing() {
        val entries = listOf(
            entry("2023/a.jpg"),
            entry("2023/sub/b.jpg"),
            entry("top.jpg"),
            entry("2023", kind = "dir"),
            entry("2023/sub", kind = "dir"),
        )
        assertEquals(entries, filterByDir(entries, null))
        assertEquals(listOf("2023", "2023/sub"), dirPaths(entries))
        val filtered = filterByDir(entries, "2023")
        assertEquals(setOf("2023/a.jpg", "2023/sub/b.jpg", "2023/sub"), filtered.map { it.path }.toSet())
        assertEquals(emptyList<EntryRecord>(), filterByDir(entries, "missing"))
    }

    @Test
    fun mime_types_map_by_extension() {
        assertEquals("image/jpeg", mimeFor("a/pic.JPG"))
        assertEquals("video/mp4", mimeFor("a/b/c.MP4"))
        assertEquals("application/pdf", mimeFor("doc.pdf"))
        assertEquals("*/*", mimeFor("thing.bin"))
    }
}
