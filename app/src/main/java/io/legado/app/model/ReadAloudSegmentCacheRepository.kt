package io.legado.app.model

import android.util.Log
import io.legado.app.data.dao.ReadAloudSegmentCacheDao
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReadAloudSegmentCache
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray

data class SegmentCacheResult(
    val segments: List<ReadAloudSegment>,
    val cacheHit: Boolean
)

class ReadAloudSegmentCacheRepository(
    private val dao: ReadAloudSegmentCacheDao
) {
    companion object {
        private const val TAG = "segmentCache"
        private const val AI_CACHE_TAG = "AICache"
    }

    fun getOrBuildRuleSegments(
        book: Book,
        chapter: BookChapter,
        chapterContent: String
    ): SegmentCacheResult {
        val normalized = ReadAloudSegmentCacheKey.normalizeContent(chapterContent)
        val hash = ReadAloudSegmentCacheKey.contentHash(chapterContent)
        val version = ReadAloudSegmentCacheKey.RULE_SEGMENT_VERSION
        val cacheEntry = dao.get(
            bookUrl = book.bookUrl,
            chapterUrl = chapter.url,
            producer = ReadAloudSegmentCacheKey.PRODUCER_RULE
        )
        if (cacheEntry != null) {
            if (cacheEntry.contentHash == hash && cacheEntry.segmentVersion == version) {
                val segments = parseSegmentsJson(cacheEntry.segmentsJson)
                if (segments != null && ReadAloudSegmentCacheValidator.validate(segments, chapterContent)) {
                    logHit(hash, version, segments.size)
                    return SegmentCacheResult(segments, cacheHit = true)
                } else {
                    logInvalid(hash, version, cacheEntry.segmentsJson.length)
                }
            } else {
                logMiss(hash, version, cacheEntry.contentHash, cacheEntry.segmentVersion)
            }
        } else {
            logMiss(hash, version, null, null)
        }
        val segments = ReadAloudChapterSegmenter.buildChapterRuleSegments(chapterContent)
        writeCache(book, chapter, hash, version, segments)
        return SegmentCacheResult(segments, cacheHit = false)
    }

    /**
     * 读取 AI 对白标注缓存。
     *
     * 命中且 validator 通过返回 segments；缓存缺失、contentHash/version 不匹配、
     * JSON 解析失败或校验失败都返回 null（记 cacheMiss，reason 区分原因）。
     * 不在此处触发 AI 请求，调用方负责回退链路。
     */
    fun getAiDialogLabelSegments(
        book: Book,
        chapter: BookChapter,
        chapterContent: String,
        producerKey: String
    ): List<ReadAloudSegment>? {
        val hash = ReadAloudSegmentCacheKey.contentHash(chapterContent)
        val version = ReadAloudSegmentCacheKey.AI_DIALOG_LABEL_VERSION
        val cacheEntry = dao.get(
            bookUrl = book.bookUrl,
            chapterUrl = chapter.url,
            producer = producerKey
        )
        if (cacheEntry != null) {
            if (cacheEntry.contentHash == hash && cacheEntry.segmentVersion == version) {
                val segments = parseSegmentsJson(cacheEntry.segmentsJson)
                if (segments != null && ReadAloudSegmentCacheValidator.validate(segments, chapterContent)) {
                    Log.i(
                        AI_CACHE_TAG,
                        "aiChapterParse cacheHit chapter=${chapter.index} " +
                            "segments=${segments.size} key=${ReadAloudSegmentCacheKey.logKey(producerKey)}"
                    )
                    return segments
                } else {
                    Log.w(
                        AI_CACHE_TAG,
                        "aiChapterParse cacheMiss chapter=${chapter.index} reason=invalid " +
                            "key=${ReadAloudSegmentCacheKey.logKey(producerKey)}"
                    )
                }
            } else {
                val reason = when {
                    cacheEntry.contentHash != hash -> "contentHash"
                    cacheEntry.segmentVersion != version -> "version"
                    else -> "unknown"
                }
                Log.i(
                    AI_CACHE_TAG,
                    "aiChapterParse cacheMiss chapter=${chapter.index} reason=$reason " +
                        "key=${ReadAloudSegmentCacheKey.logKey(producerKey)}"
                )
            }
        } else {
            Log.i(
                AI_CACHE_TAG,
                "aiChapterParse cacheMiss chapter=${chapter.index} reason=missing " +
                    "key=${ReadAloudSegmentCacheKey.logKey(producerKey)}"
            )
        }
        return null
    }

    /**
     * 写入 AI 对白标注缓存。
     *
     * 写入前用 validator 校验位置、长度、文本一致性；校验失败不写缓存。
     * 写入失败不影响当前播放，记日志后由调用方继续使用本次内存结果。
     */
    fun saveAiDialogLabelSegments(
        book: Book,
        chapter: BookChapter,
        chapterContent: String,
        producerKey: String,
        segments: List<ReadAloudSegment>
    ) {
        val hash = ReadAloudSegmentCacheKey.contentHash(chapterContent)
        val version = ReadAloudSegmentCacheKey.AI_DIALOG_LABEL_VERSION
        if (!ReadAloudSegmentCacheValidator.validate(segments, chapterContent)) {
            Log.w(
                AI_CACHE_TAG,
                "aiChapterParse cacheWriteFailed chapter=${chapter.index} reason=invalid " +
                    "key=${ReadAloudSegmentCacheKey.logKey(producerKey)}"
            )
            return
        }
        val startMs = System.currentTimeMillis()
        try {
            val json = GSON.toJson(segments)
            dao.insert(
                ReadAloudSegmentCache(
                    bookUrl = book.bookUrl,
                    chapterUrl = chapter.url,
                    producer = producerKey,
                    chapterIndex = chapter.index,
                    contentHash = hash,
                    segmentVersion = version,
                    segmentsJson = json,
                    updatedAt = System.currentTimeMillis()
                )
            )
            Log.i(
                AI_CACHE_TAG,
                "aiChapterParse cacheWrite chapter=${chapter.index} segments=${segments.size} " +
                    "elapsedMs=${System.currentTimeMillis() - startMs} " +
                    "key=${ReadAloudSegmentCacheKey.logKey(producerKey)}"
            )
        } catch (e: Exception) {
            Log.w(
                AI_CACHE_TAG,
                "aiChapterParse cacheWriteFailed chapter=${chapter.index} reason=exception " +
                    "key=${ReadAloudSegmentCacheKey.logKey(producerKey)}: ${e.message}"
            )
        }
    }

    private fun parseSegmentsJson(json: String): List<ReadAloudSegment>? {
        return try {
            GSON.fromJsonArray<ReadAloudSegment>(json).getOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "parse json failed: ${e.message}")
            null
        }
    }

    private fun writeCache(
        book: Book,
        chapter: BookChapter,
        hash: String,
        version: Int,
        segments: List<ReadAloudSegment>
    ) {
        try {
            val json = GSON.toJson(segments)
            dao.insert(
                ReadAloudSegmentCache(
                    bookUrl = book.bookUrl,
                    chapterUrl = chapter.url,
                    producer = ReadAloudSegmentCacheKey.PRODUCER_RULE,
                    chapterIndex = chapter.index,
                    contentHash = hash,
                    segmentVersion = version,
                    segmentsJson = json,
                    updatedAt = System.currentTimeMillis()
                )
            )
            logRebuild(hash, version, segments.size)
        } catch (e: Exception) {
            Log.w(TAG, "writeError hash=$hash version=$version segments=${segments.size}: ${e.message}")
        }
    }

    private fun logHit(hash: String, version: Int, count: Int) {
        Log.i(TAG, "hit hash=${hash.take(8)} version=$version segments=$count")
    }

    private fun logMiss(hash: String, version: Int, cachedHash: String?, cachedVersion: Int?) {
        val reason = when {
            cachedHash == null -> "noCache"
            cachedHash != hash -> "contentChanged"
            cachedVersion != version -> "versionChanged"
            else -> "unknown"
        }
        Log.i(TAG, "miss reason=$reason hash=${hash.take(8)} version=$version")
    }

    private fun logInvalid(hash: String, version: Int, jsonLength: Int) {
        Log.w(TAG, "invalid hash=${hash.take(8)} version=$version jsonLength=$jsonLength")
    }

    private fun logRebuild(hash: String, version: Int, count: Int) {
        Log.i(TAG, "rebuild hash=${hash.take(8)} version=$version segments=$count")
    }
}

fun selectSegments(
    chapterSegments: List<ReadAloudSegment>,
    windowStart: Int,
    windowEndExclusive: Int
): List<ReadAloudSegment> {
    return chapterSegments.filter { segment ->
        segment.endPosition > windowStart && segment.startPosition < windowEndExclusive
    }
}
