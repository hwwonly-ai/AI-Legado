package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "readAloudSegmentCaches",
    primaryKeys = ["bookUrl", "chapterUrl", "producer"],
    indices = [
        Index(value = ["bookUrl"]),
        Index(value = ["bookUrl", "chapterIndex"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["bookUrl"],
            childColumns = ["bookUrl"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ReadAloudSegmentCache(
    val bookUrl: String,
    val chapterUrl: String,
    val producer: String,
    val chapterIndex: Int,
    val contentHash: String,
    val segmentVersion: Int,
    val segmentsJson: String,
    val updatedAt: Long
)
