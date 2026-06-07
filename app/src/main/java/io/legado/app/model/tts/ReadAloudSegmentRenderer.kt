package io.legado.app.model.tts

import io.legado.app.model.ReadAloudSegment

interface ReadAloudSegmentRenderer<T> {
    fun render(segment: ReadAloudSegment): T
}
