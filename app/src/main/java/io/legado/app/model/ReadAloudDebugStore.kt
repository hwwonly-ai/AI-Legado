package io.legado.app.model

import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReadAloudDebugSnapshot(
    val updatedAt: Long = System.currentTimeMillis(),
    val chapterIndex: Int = -1,
    val segmentIndex: Int = -1,
    val segmentJson: String = "",
    val ttsRequestSummary: String = "",
    val aiSummary: String = "",
    val roleMatchSummary: String = ""
)

object ReadAloudDebugStore {

    private const val MAX_LOGS = 80
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    private val roleLogs = ArrayDeque<String>()
    private val aiLogs = ArrayDeque<String>()

    @Volatile
    private var snapshot = ReadAloudDebugSnapshot()

    @Synchronized
    fun updateSegment(chapterIndex: Int, segmentIndex: Int, segment: ReadAloudSegment?) {
        val json = segment?.let { GSON.toJson(it) }.orEmpty()
        snapshot = snapshot.copy(
            updatedAt = System.currentTimeMillis(),
            chapterIndex = chapterIndex,
            segmentIndex = segmentIndex,
            segmentJson = json.ifBlank { "No current segment." }
        )
    }

    @Synchronized
    fun updateTtsRequest(
        speakText: String,
        segment: ReadAloudSegment?,
        providerSegment: ReadAloudProviderSegment?
    ) {
        val summary = buildString {
            appendLine("provider=${providerSegment?.provider.orEmpty().ifBlank { "-" }}")
            appendLine("voice=${providerSegment?.voice.orEmpty().ifBlank { "-" }}")
            appendLine("style=${providerSegment?.styleTag.orEmpty().ifBlank { "-" }}")
            appendLine("eventType=${segment?.eventType.orEmpty().ifBlank { "-" }}")
            appendLine("role=${segment?.role.orEmpty().ifBlank { "-" }}")
            appendLine("roleName=${segment?.roleName.orEmpty().ifBlank { "-" }}")
            appendLine("emotion=${segment?.emotion.orEmpty().ifBlank { "-" }}")
            appendLine("textLength=${speakText.length}")
            appendLine("textHash=${MD5Utils.md5Encode16(speakText)}")
            append("preview=${speakText.preview()}")
        }
        snapshot = snapshot.copy(
            updatedAt = System.currentTimeMillis(),
            ttsRequestSummary = summary
        )
    }

    @Synchronized
    fun addAiSummary(message: String) {
        aiLogs.addFirst(stamped(message))
        while (aiLogs.size > MAX_LOGS) aiLogs.removeLast()
        snapshot = snapshot.copy(
            updatedAt = System.currentTimeMillis(),
            aiSummary = aiLogs.joinToString("\n")
        )
    }

    @Synchronized
    fun addRoleMatch(message: String) {
        roleLogs.addFirst(stamped(message))
        while (roleLogs.size > MAX_LOGS) roleLogs.removeLast()
        snapshot = snapshot.copy(
            updatedAt = System.currentTimeMillis(),
            roleMatchSummary = roleLogs.joinToString("\n")
        )
    }

    @Synchronized
    fun currentSegmentJson(): String = snapshot.segmentJson.ifBlank { "No current segment." }

    @Synchronized
    fun currentTtsRequestSummary(): String =
        snapshot.ttsRequestSummary.ifBlank { "No TTS request captured yet." }

    @Synchronized
    fun currentAiSummary(): String = snapshot.aiSummary.ifBlank { "No AI summary captured yet." }

    @Synchronized
    fun currentRoleMatchSummary(): String =
        snapshot.roleMatchSummary.ifBlank { "No role match log captured yet." }

    private fun stamped(message: String): String {
        return "${timeFormat.format(Date())} $message"
    }

    private fun String.preview(limit: Int = 80): String {
        val clean = replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= limit) clean else clean.take(limit) + "..."
    }
}
