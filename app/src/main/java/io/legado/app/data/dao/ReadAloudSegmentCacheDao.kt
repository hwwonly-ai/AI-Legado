package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.ReadAloudSegmentCache

@Dao
interface ReadAloudSegmentCacheDao {

    @Query(
        """select * from readAloudSegmentCaches
        where bookUrl = :bookUrl
        and chapterUrl = :chapterUrl
        and producer = :producer
        limit 1"""
    )
    fun get(
        bookUrl: String,
        chapterUrl: String,
        producer: String
    ): ReadAloudSegmentCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(cache: ReadAloudSegmentCache)

    @Query(
        """delete from readAloudSegmentCaches
        where bookUrl = :bookUrl and chapterUrl = :chapterUrl"""
    )
    fun deleteChapter(bookUrl: String, chapterUrl: String): Int

    @Query("delete from readAloudSegmentCaches where bookUrl = :bookUrl")
    fun deleteBook(bookUrl: String): Int
}
