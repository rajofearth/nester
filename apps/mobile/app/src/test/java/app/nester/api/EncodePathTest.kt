package app.nester.api

import org.junit.Assert.assertEquals
import org.junit.Test

class EncodePathTest {
    private val encode: (String) -> String = { it.replace(" ", "%20").replace("#", "%23") }

    @Test
    fun keepsLeadingSlash() {
        assertEquals("/api/folders/x/files/y", encodePathSegments("/api/folders/x/files/y", encode))
    }

    @Test
    fun encodesPerSegmentOnly() {
        assertEquals("/api/folders/x/files/my%20photos/my%20pic.jpg", encodePathSegments("/api/folders/x/files/my photos/my pic.jpg", encode))
    }

    @Test
    fun keepsSeparatorsBetweenEncodedSegments() {
        assertEquals("/a/b%20c/d", encodePathSegments("/a/b c/d", encode))
    }
}
