package io.legado.app.model.tts

import io.legado.app.model.ReadAloudRuleSegmenter

class DoubaoNovelTtsPlanner(
    private val renderer: DoubaoTtsRenderer = DoubaoTtsRenderer()
) {

    fun buildRequests(
        chapterText: String,
        chapterStartPosition: Int = 0
    ): List<DoubaoTtsRequest> {
        val segments = ReadAloudRuleSegmenter.segment(
            text = chapterText,
            startPosition = chapterStartPosition,
            renderAliyunSsml = false
        )
        return segments.mapIndexed { index, segment ->
            renderer.render(segment, segments.take(index))
        }
    }
}
