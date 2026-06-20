package io.legado.app.model.ai

import io.legado.app.model.ReadAloudEventType
import io.legado.app.model.ReadAloudRuleSegmenter
import io.legado.app.model.ReadAloudRole
import io.legado.app.model.ReadAloudSegment
import io.legado.app.model.ReadAloudSegmentCacheKey

/**
 * AI 输出 segment 文本到章节原文位置的映射器。
 *
 * 流程：
 * 1. 章节正文规范化换行（与规则分段一致）。
 * 2. 从上一个匹配结束位置开始顺序查找每段 text。
 * 3. 依次尝试：精确查找 → 压缩空白查找 → trim 查找。
 * 4. 单段失败超过阈值时整体回退，避免局部混合造成朗读进度错位。
 *
 * 映射后的 segment startPosition/plainLength 基于规范化后的章节正文，
 * 与 ReadAloudChapterSegmenter / 规则分段保持同一坐标系。
 */
object AiSegmentMapper {

    /** 整体映射成功率低于此值则回退规则分段。 */
    private const val MIN_SUCCESS_RATIO = 0.95

    /** 章节正文压缩空白后的查找索引，避免全角/连续空白差异导致漏匹配。 */
    private data class CompressedIndex(val compressed: String, val offsets: IntArray)

    /**
     * 将 AI segment 映射为带原文位置的 ReadAloudSegment。
     *
     * @return 成功时 [MapResult.Ok]；失败或成功率不足时 [MapResult.Failed]（带已映射数量/总数）。
     */
    fun map(
        aiSegments: List<AiSegmentResponse>,
        chapterContent: String
    ): MapResult {
        val normalized = ReadAloudSegmentCacheKey.normalizeContent(chapterContent)
        if (normalized.isEmpty() || aiSegments.isEmpty()) {
            return MapResult.Failed(mapped = 0, total = aiSegments.size)
        }
        val compressedIndex = buildCompressedIndex(normalized)

        val result = ArrayList<ReadAloudSegment>(aiSegments.size)
        var cursor = 0
        var mapped = 0
        var firstFailure: FailureSnapshot? = null
        for ((index, ai) in aiSegments.withIndex()) {
            val match = findMatch(ai.text, normalized, compressedIndex, cursor)
            if (match != null) {
                result.add(createSegment(normalized, match, ai))
                cursor = match.end
                mapped++
            } else {
                val firstOccurrence = findMatch(ai.text, normalized, compressedIndex, 0)
                val overlapMatch = findContainedOverlapMatch(
                    text = ai.text,
                    normalized = normalized,
                    compressedIndex = compressedIndex,
                    cursor = cursor,
                    result = result
                )
                if (
                    overlapMatch != null &&
                    applyContainedOverlap(result, normalized, overlapMatch, ai)
                ) {
                    mapped++
                    continue
                }
                // 单段无法映射：跳过该段，继续后续段（方案允许跳过）。
                // 记录首个失败段用于诊断；fullText 仅用于临时排查。
                if (firstFailure == null) {
                    firstFailure = FailureSnapshot(
                        index = index,
                        textLen = ai.text.length,
                        head = ai.text.take(30).replace("\n", "\\n"),
                        tail = ai.text.takeLast(30).replace("\n", "\\n"),
                        cursor = cursor,
                        occurrence = when {
                            firstOccurrence == null -> "absent"
                            firstOccurrence.start < cursor -> "beforeCursor"
                            else -> "unknown"
                        },
                        firstOccurrence = firstOccurrence?.start ?: -1,
                        fullText = ai.text
                    )
                }
            }
        }

        val coverage = fillCoverageGaps(normalized, result)
        val refined = splitNarratorDialogs(coverage.segments)
        val ratio = if (aiSegments.isEmpty()) 0.0 else mapped.toDouble() / aiSegments.size
        return if (ratio >= MIN_SUCCESS_RATIO) {
            MapResult.Ok(
                segments = refined.segments,
                mapped = mapped,
                total = aiSegments.size,
                filledGaps = coverage.filledGaps,
                filledGapChars = coverage.filledGapChars,
                narratorDialogSplits = refined.dialogSplits
            )
        } else {
            MapResult.Failed(mapped = mapped, total = aiSegments.size, firstFailure = firstFailure)
        }
    }

    /** 在 normalized 中从 [from] 起顺序查找 [text]，依次精确/压缩空白/trim。 */
    private fun findMatch(
        text: String,
        normalized: String,
        compressedIndex: CompressedIndex,
        from: Int
    ): Match? {
        if (text.isEmpty()) return null
        // 1. 精确查找
        val exact = normalized.indexOf(text, from)
        if (exact >= 0) {
            return Match(exact, exact + text.length)
        }
        // 2. 压缩空白查找
        val compressedMatch = findCompressed(text, compressedIndex, from)
        if (compressedMatch != null) return compressedMatch
        // 3. trim 查找
        val trimmed = text.trim()
        if (trimmed.isNotEmpty() && trimmed != text) {
            val trimIdx = normalized.indexOf(trimmed, from)
            if (trimIdx >= 0) {
                return Match(trimIdx, trimIdx + trimmed.length)
            }
        }
        return null
    }

    private fun findCompressed(
        text: String,
        compressedIndex: CompressedIndex,
        from: Int
    ): Match? {
        val target = compressWhitespace(text)
        if (target.isEmpty()) return null
        val compressed = compressedIndex.compressed
        val offsets = compressedIndex.offsets
        // 在压缩串中查找，但要保证匹配起点对应原文位置 >= from
        var searchFrom = compressedLowerBound(offsets, from)
        if (searchFrom < 0) searchFrom = 0
        val idx = compressed.indexOf(target, searchFrom)
        if (idx < 0) return null
        val startCompressed = idx
        val endCompressed = idx + target.length
        if (endCompressed > offsets.size) return null
        val originalStart = offsets[startCompressed]
        val originalEnd = if (endCompressed - 1 < offsets.size) {
            offsets[endCompressed - 1] + 1
        } else {
            offsets.last() + 1
        }
        if (originalStart < from) return null
        return Match(originalStart, originalEnd)
    }

    /** 二分查找 offsets 中第一个 >= from 的索引。 */
    private fun compressedLowerBound(offsets: IntArray, from: Int): Int {
        var lo = 0
        var hi = offsets.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (offsets[mid] >= from) {
                ans = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return ans
    }

    private fun compressWhitespace(text: String): String {
        val sb = StringBuilder(text.length)
        var prevSpace = false
        for (c in text) {
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '　') {
                if (!prevSpace) {
                    sb.append(' ')
                    prevSpace = true
                }
            } else {
                sb.append(c)
                prevSpace = false
            }
        }
        return sb.toString().trim()
    }

    private fun buildCompressedIndex(normalized: String): CompressedIndex {
        val sb = StringBuilder(normalized.length)
        val offsets = IntArray(normalized.length)
        var count = 0
        var prevSpace = false
        for (i in normalized.indices) {
            val c = normalized[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '　') {
                if (!prevSpace) {
                    sb.append(' ')
                    offsets[count++] = i
                    prevSpace = true
                }
            } else {
                sb.append(c)
                offsets[count++] = i
                prevSpace = false
            }
        }
        return CompressedIndex(sb.toString(), offsets.copyOf(count))
    }

    private fun findContainedOverlapMatch(
        text: String,
        normalized: String,
        compressedIndex: CompressedIndex,
        cursor: Int,
        result: List<ReadAloudSegment>
    ): Match? {
        var searchFrom = 0
        var best: Match? = null
        while (searchFrom < cursor) {
            val match = findMatch(text, normalized, compressedIndex, searchFrom) ?: break
            if (match.start >= cursor) break
            if (
                match.end <= cursor &&
                result.any { it.startPosition <= match.start && it.endPosition >= match.end }
            ) {
                best = match
            }
            searchFrom = match.start + 1
        }
        return best
    }

    /**
     * 处理 AI 常见重叠输出：上一段已包含对白，下一段又单独输出该对白。
     * 命中时拆分已有段，避免重复朗读，同时保留当前段的角色与情绪。
     */
    private fun applyContainedOverlap(
        result: ArrayList<ReadAloudSegment>,
        normalized: String,
        match: Match,
        ai: AiSegmentResponse
    ): Boolean {
        val targetIndex = result.indexOfLast {
            it.startPosition <= match.start && it.endPosition >= match.end
        }
        if (targetIndex < 0) return false

        val source = result[targetIndex]
        val replacement = ArrayList<ReadAloudSegment>(3)
        if (source.startPosition < match.start) {
            replacement.add(source.copyRange(normalized, source.startPosition, match.start))
        }
        replacement.add(createSegment(normalized, match, ai))
        if (match.end < source.endPosition) {
            replacement.add(source.copyRange(normalized, match.end, source.endPosition))
        }
        result.removeAt(targetIndex)
        result.addAll(targetIndex, replacement.filter { it.plainLength > 0 })
        return true
    }

    private fun ReadAloudSegment.copyRange(
        normalized: String,
        start: Int,
        end: Int
    ): ReadAloudSegment {
        val text = normalized.substring(start, end)
        return copy(
            text = text,
            startPosition = start,
            plainLength = text.length
        )
    }

    private fun createSegment(
        normalized: String,
        match: Match,
        ai: AiSegmentResponse
    ): ReadAloudSegment {
        return ReadAloudSegment(
            text = normalized.substring(match.start, match.end),
            startPosition = match.start,
            plainLength = match.end - match.start,
            eventType = ReadAloudEventType.TEXT,
            role = ai.role,
            roleName = ai.roleName,
            emotion = ai.emotion,
            emotionIntensity = ai.emotionIntensity
        )
    }

    /**
     * AI 可能漏掉一段原文但其余 segment 都映射成功。这里补齐非空 gap 为旁白，
     * 避免 mapped=100% 时仍静默漏读中间内容。
     */
    private fun fillCoverageGaps(
        normalized: String,
        segments: List<ReadAloudSegment>
    ): CoverageResult {
        if (segments.isEmpty()) {
            return CoverageResult(
                segments = listOf(createNarratorGapSegment(normalized, 0, normalized.length)),
                filledGaps = 1,
                filledGapChars = normalized.length
            )
        }
        val sorted = segments.sortedWith(compareBy<ReadAloudSegment> { it.startPosition }.thenBy { it.endPosition })
        val result = ArrayList<ReadAloudSegment>(sorted.size)
        var cursor = 0
        var filledGaps = 0
        var filledGapChars = 0

        for (segment in sorted) {
            if (segment.endPosition <= cursor) {
                continue
            }
            if (segment.startPosition > cursor) {
                val gap = createNarratorGapSegment(normalized, cursor, segment.startPosition)
                if (gap.plainLength > 0) {
                    result.add(gap)
                    filledGaps++
                    filledGapChars += gap.plainLength
                }
            }
            val effectiveSegment = if (segment.startPosition < cursor) {
                segment.copyRange(normalized, cursor, segment.endPosition)
            } else {
                segment
            }
            result.add(effectiveSegment)
            cursor = effectiveSegment.endPosition
        }

        if (cursor < normalized.length) {
            val gap = createNarratorGapSegment(normalized, cursor, normalized.length)
            if (gap.plainLength > 0) {
                result.add(gap)
                filledGaps++
                filledGapChars += gap.plainLength
            }
        }

        return CoverageResult(
            segments = result,
            filledGaps = filledGaps,
            filledGapChars = filledGapChars
        )
    }

    private fun createNarratorGapSegment(
        normalized: String,
        start: Int,
        end: Int
    ): ReadAloudSegment {
        val text = normalized.substring(start, end)
        if (text.isBlank()) {
            return ReadAloudSegment(
                text = "",
                startPosition = start,
                plainLength = 0
            )
        }
        return ReadAloudSegment(
            text = text,
            startPosition = start,
            plainLength = text.length,
            eventType = ReadAloudEventType.TEXT,
            role = ReadAloudRole.NARRATOR,
            roleName = "旁白",
            emotion = "neutral",
            emotionIntensity = 1.0f
        )
    }

    private data class CoverageResult(
        val segments: List<ReadAloudSegment>,
        val filledGaps: Int,
        val filledGapChars: Int
    )

    /**
     * AI 偶尔会把含引号的整段识别成旁白。这里用规则分段器二次拆出对白，
     * 避免明显对白继续使用旁白音色。
     */
    private fun splitNarratorDialogs(segments: List<ReadAloudSegment>): RefineResult {
        val result = ArrayList<ReadAloudSegment>(segments.size)
        var dialogSplits = 0
        for (segment in segments) {
            if (segment.role != ReadAloudRole.NARRATOR || !segment.text.hasQuote()) {
                result.add(segment)
                continue
            }
            val split = ReadAloudRuleSegmenter.segment(segment.text, segment.startPosition)
            if (split.size <= 1 || split.none { it.role == ReadAloudRole.DIALOG }) {
                result.add(segment)
                continue
            }
            split.forEach { child ->
                if (child.role == ReadAloudRole.NARRATOR) {
                    result.add(
                        segment.copy(
                            text = child.text,
                            startPosition = child.startPosition,
                            plainLength = child.plainLength
                        )
                    )
                } else {
                    result.add(child)
                    dialogSplits++
                }
            }
        }
        return RefineResult(result, dialogSplits)
    }

    private fun String.hasQuote(): Boolean {
        return any { it == '“' || it == '‘' || it == '「' || it == '『' || it == '"' || it == '＂' }
    }

    private data class RefineResult(
        val segments: List<ReadAloudSegment>,
        val dialogSplits: Int
    )

    private data class Match(val start: Int, val end: Int)

    /** 首个映射失败段的诊断快照。fullText 仅用于临时排查，定位完成后移除。 */
    data class FailureSnapshot(
        val index: Int,
        val textLen: Int,
        val head: String,
        val tail: String,
        val cursor: Int,
        val occurrence: String,
        val firstOccurrence: Int,
        val fullText: String
    )

    sealed class MapResult {
        data class Ok(
            val segments: List<ReadAloudSegment>,
            val mapped: Int,
            val total: Int,
            val filledGaps: Int = 0,
            val filledGapChars: Int = 0,
            val narratorDialogSplits: Int = 0
        ) : MapResult()

        data class Failed(
            val mapped: Int,
            val total: Int,
            val firstFailure: FailureSnapshot? = null
        ) : MapResult()
    }
}
