package io.legado.app.model

import android.util.Log
import io.legado.app.data.dao.ReadAloudSegmentCacheDao
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ReadAloudSegmentCacheManager(
    private val dao: ReadAloudSegmentCacheDao
) {
    companion object {
        private const val TAG = "segmentCache"
        private val mutex = Mutex()
    }

    suspend fun refreshChapter(book: Book, chapter: BookChapter): Int {
        return mutex.withLock {
            try {
                val deleted = dao.deleteChapter(book.bookUrl, chapter.url)
                val bookHash = MD5Utils.md5Encode16(book.bookUrl).take(8)
                val chapterHash = MD5Utils.md5Encode16(chapter.url).take(8)
                Log.i(TAG, "manualRefresh chapter book=$bookHash chapter=$chapterHash deleted=$deleted")
                deleted
            } catch (e: Exception) {
                Log.e(TAG, "manualRefresh error type=${e.javaClass.simpleName}", e)
                throw e
            }
        }
    }

    suspend fun clearBook(book: Book): Int {
        return mutex.withLock {
            try {
                val deleted = dao.deleteBook(book.bookUrl)
                val bookHash = MD5Utils.md5Encode16(book.bookUrl).take(8)
                Log.i(TAG, "manualClearBook book=$bookHash deleted=$deleted")
                deleted
            } catch (e: Exception) {
                Log.e(TAG, "manualClearBook error type=${e.javaClass.simpleName}", e)
                throw e
            }
        }
    }
}
