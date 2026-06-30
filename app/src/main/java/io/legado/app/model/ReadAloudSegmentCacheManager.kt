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
        private const val AI_DIALOG_PRODUCER_PATTERN =
            ReadAloudSegmentCacheKey.PRODUCER_AI_DIALOG_LABEL + "%"
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

    suspend fun refreshChapterAiLabels(book: Book, chapter: BookChapter): Int {
        return mutex.withLock {
            try {
                val deleted = dao.deleteChapterByProducerPattern(
                    bookUrl = book.bookUrl,
                    chapterUrl = chapter.url,
                    producerPattern = AI_DIALOG_PRODUCER_PATTERN
                )
                val bookHash = MD5Utils.md5Encode16(book.bookUrl).take(8)
                val chapterHash = MD5Utils.md5Encode16(chapter.url).take(8)
                Log.i(TAG, "manualRefreshAi chapter book=$bookHash chapter=$chapterHash deleted=$deleted")
                deleted
            } catch (e: Exception) {
                Log.e(TAG, "manualRefreshAi error type=${e.javaClass.simpleName}", e)
                throw e
            }
        }
    }

    suspend fun clearBookAiLabels(book: Book): Int {
        return mutex.withLock {
            try {
                val deleted = dao.deleteBookByProducerPattern(
                    bookUrl = book.bookUrl,
                    producerPattern = AI_DIALOG_PRODUCER_PATTERN
                )
                val bookHash = MD5Utils.md5Encode16(book.bookUrl).take(8)
                Log.i(TAG, "manualClearBookAi book=$bookHash deleted=$deleted")
                deleted
            } catch (e: Exception) {
                Log.e(TAG, "manualClearBookAi error type=${e.javaClass.simpleName}", e)
                throw e
            }
        }
    }
}
