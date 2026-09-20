package app.nester.gallery

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListPagingSourceTest {

    @Test
    fun pages_slice_the_list_in_order() = runBlocking {
        val source = ListPagingSource((0 until 250).map { "item$it" }, pageSize = 120)
        val first = source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 120, placeholdersEnabled = false))
        first as PagingSource.LoadResult.Page
        assertEquals((0 until 120).map { "item$it" }, first.data)
        assertNull(first.prevKey)
        assertEquals(1, first.nextKey)

        val second = source.load(PagingSource.LoadParams.Append(key = 1, loadSize = 120, placeholdersEnabled = false))
        second as PagingSource.LoadResult.Page
        assertEquals((120 until 240).map { "item$it" }, second.data)

        val third = source.load(PagingSource.LoadParams.Append(key = 2, loadSize = 120, placeholdersEnabled = false))
        third as PagingSource.LoadResult.Page
        assertEquals(listOf("item240", "item241", "item242", "item243", "item244", "item245", "item246", "item247", "item248", "item249"), third.data)
        assertNull(third.nextKey)
    }

    @Test
    fun empty_list_yields_one_empty_page() = runBlocking {
        val source = ListPagingSource(emptyList<String>(), pageSize = 120)
        val page = source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 120, placeholdersEnabled = false))
        page as PagingSource.LoadResult.Page
        assertEquals(0, page.data.size)
        assertNull(page.nextKey)
    }

    @Test
    fun refresh_key_anchors_to_the_page_around_the_position() {
        val source = ListPagingSource((0 until 250).map { "item$it" }, pageSize = 120)
        val state = PagingState<Int, String>(
            pages = emptyList(),
            anchorPosition = 130,
            config = PagingConfig(pageSize = 120),
            leadingPlaceholderCount = 0,
        )
        assertEquals(1, source.getRefreshKey(state))
        val stateTop = PagingState<Int, String>(
            pages = emptyList(),
            anchorPosition = 10,
            config = PagingConfig(pageSize = 120),
            leadingPlaceholderCount = 0,
        )
        assertEquals(0, source.getRefreshKey(stateTop))
    }
}
