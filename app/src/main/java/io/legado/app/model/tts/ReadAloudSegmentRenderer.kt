package io.legado.app.model.tts

import io.legado.app.model.ReadAloudSegment
import io.legado.app.model.ReadAloudProviderSegment

interface ReadAloudSegmentRenderer<T> {
    fun render(segment: ReadAloudSegment, providerSegment: ReadAloudProviderSegment): T
}
