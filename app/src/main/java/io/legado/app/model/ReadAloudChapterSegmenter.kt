package io.legado.app.model

object ReadAloudChapterSegmenter {

    fun buildChapterRuleSegments(chapterContent: String): List<ReadAloudSegment> {
        val normalized = ReadAloudSegmentCacheKey.normalizeContent(chapterContent)
        if (normalized.isBlank()) {
            return emptyList()
        }
        val segments = mutableListOf<ReadAloudSegment>()
        var cursor = 0
        for (line in normalized.splitToSequence("\n")) {
            val lineLength = line.length
            if (line.isNotBlank()) {
                segments.addAll(
                    ReadAloudRuleSegmenter.segment(
                        text = line,
                        startPosition = cursor
                    )
                )
            }
            cursor += lineLength + 1
        }
        return segments.sortedBy { it.startPosition }
    }
}
