package app.nester.gallery

import androidx.paging.PagingSource
import androidx.paging.PagingState

const val GALLERY_PAGE_SIZE = 120

class ListPagingSource<T : Any>(
    private val items: List<T>,
    private val pageSize: Int = GALLERY_PAGE_SIZE,
) : PagingSource<Int, T>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        val page = params.key ?: 0
        val start = page * pageSize
        if (start >= items.size && page > 0) {
            return LoadResult.Page(emptyList(), prevKey = page - 1, nextKey = null)
        }
        val end = minOf(start + pageSize, items.size)
        return LoadResult.Page(
            data = items.subList(start, end),
            prevKey = if (page == 0) null else page - 1,
            nextKey = if (end < items.size) page + 1 else null,
        )
    }

    override fun getRefreshKey(state: PagingState<Int, T>): Int? {
        val last = (items.size - 1).coerceAtLeast(0) / pageSize
        return state.anchorPosition?.let { anchor ->
            (anchor / pageSize).coerceIn(0, last)
        }
    }
}
