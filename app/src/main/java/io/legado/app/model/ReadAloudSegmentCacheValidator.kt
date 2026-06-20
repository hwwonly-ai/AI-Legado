package io.legado.app.model

import android.util.Log
import io.legado.app.constant.AppLog

object ReadAloudSegmentCacheValidator {

    private const val TAG = "segmentCache"

    fun validate(
        segments: List<ReadAloudSegment>,
        chapterContent: String
    ): Boolean {
        val normalized = ReadAloudSegmentCacheKey.normalizeContent(chapterContent)
        if (segments.isEmpty() && normalized.isNotBlank()) {
            logInvalid("empty segments for non-blank chapter")
            return false
        }
        for ((index, segment) in segments.withIndex()) {
            if (segment.startPosition < 0) {
                logInvalid("segment[$index] startPosition=${segment.startPosition} < 0")
                return false
            }
            if (segment.plainLength != segment.text.length) {
                logInvalid("segment[$index] plainLength=${segment.plainLength} != text.length=${segment.text.length}")
                return false
            }
            val endPosition = segment.startPosition + segment.plainLength
            if (endPosition > normalized.length) {
                logInvalid("segment[$index] endPosition=$endPosition > contentLength=${normalized.length}")
                return false
            }
            if (segment.eventType.isBlank()) {
                logInvalid("segment[$index] eventType is blank")
                return false
            }
            if (segment.role.isBlank()) {
                logInvalid("segment[$index] role is blank")
                return false
            }
            val expectedText = normalized.substring(segment.startPosition, endPosition)
            if (segment.text != expectedText) {
                logInvalid("segment[$index] text mismatch at startPosition=${segment.startPosition}")
                return false
            }
            if (index > 0) {
                val prev = segments[index - 1]
                if (segment.startPosition < prev.startPosition + prev.plainLength) {
                    logInvalid("segment[$index] overlaps with previous")
                    return false
                }
            }
        }
        for (i in 1 until segments.size) {
            if (segments[i].startPosition < segments[i - 1].startPosition) {
                logInvalid("segments not sorted by startPosition at index=$i")
                return false
            }
        }
        return true
    }

    private fun logInvalid(reason: String) {
        Log.w(TAG, "invalid reason=$reason")
    }
}
