package io.legado.app.model.tts

import io.legado.app.model.ReadAloudRole
import io.legado.app.model.ReadAloudSegment
import io.legado.app.utils.MD5Utils

data class DoubaoVoiceConfig(
    val narratorVoiceType: String = "BV700_V2_streaming",
    val dialogVoiceType: String = "BV001_V2_streaming",
    val enableVoiceTags: Boolean = false,
    val instruction: String = "",
    val contextWindowSize: Int = 2
)

class DoubaoTtsRenderer(
    private val config: DoubaoVoiceConfig = DoubaoVoiceConfig()
) : ReadAloudSegmentRenderer<DoubaoTtsRequest> {

    override fun render(segment: ReadAloudSegment): DoubaoTtsRequest {
        return render(segment, emptyList())
    }

    fun render(
        segment: ReadAloudSegment,
        previousSegments: List<ReadAloudSegment>
    ): DoubaoTtsRequest {
        val voiceType = when (segment.speaker) {
            ReadAloudRole.DIALOG -> config.dialogVoiceType
            else -> config.narratorVoiceType
        }
        val text = buildText(segment)
        val contextTexts = previousSegments
            .takeLast(config.contextWindowSize)
            .map { previous ->
                val name = previous.speakerName.ifBlank {
                    if (previous.speaker == ReadAloudRole.NARRATOR) "旁白" else "角色"
                }
                "$name：${previous.text}"
            }
        return DoubaoTtsRequest(
            text = text,
            voiceType = voiceType,
            emotion = segment.emotion.takeIf { it.isNotBlank() },
            contextTexts = contextTexts,
            useTagParser = config.enableVoiceTags,
            rawStartPosition = segment.startPosition,
            rawEndPosition = segment.endPosition,
            speaker = segment.speaker,
            speakerName = segment.speakerName,
            cacheKeySeed = buildCacheKeySeed(segment, text, voiceType)
        )
    }

    private fun buildText(segment: ReadAloudSegment): String {
        val tag = segment.styleTag.takeIf {
            config.enableVoiceTags && it.isNotBlank()
        }?.let { "[$it]" }.orEmpty()
        val instruction = config.instruction.takeIf { it.isNotBlank() }
            ?.let { "[#$it]" }
            .orEmpty()
        return instruction + tag + segment.text
    }

    private fun buildCacheKeySeed(
        segment: ReadAloudSegment,
        text: String,
        voiceType: String
    ): String {
        return listOf(
            segment.startPosition,
            segment.endPosition,
            segment.speaker,
            voiceType,
            segment.emotion,
            MD5Utils.md5Encode16(text)
        ).joinToString("-|-")
    }
}
