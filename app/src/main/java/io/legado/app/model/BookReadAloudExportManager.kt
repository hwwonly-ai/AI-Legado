package io.legado.app.model

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookReadAloudRole
import io.legado.app.data.entities.BookRoleMentionStat
import io.legado.app.data.entities.ReadAloudSegmentCache
import io.legado.app.data.entities.RoleMentionStatWindow
import io.legado.app.help.book.BookHelp
import io.legado.app.model.ai.AiServiceConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import java.util.UUID

data class BookScriptExport(
    val format: String = "legado-book-script",
    val version: Int = 1,
    val bookUrl: String,
    val bookName: String,
    val bookAuthor: String,
    val chapterUrl: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val contentHash: String,
    val segmentVersion: Int,
    val producer: String,
    val segmentsJson: String,
    val exportedAt: Long = System.currentTimeMillis()
)

data class BookRoleProfileExport(
    val format: String = "legado-book-role-profile",
    val version: Int = 1,
    val bookUrl: String,
    val bookName: String,
    val bookAuthor: String,
    val narratorVoices: HashMap<String, String> = hashMapOf(),
    val dialogVoices: HashMap<String, String> = hashMapOf(),
    val roles: MutableList<BookReadAloudRole> = mutableListOf(),
    val roleMentionStatWindow: RoleMentionStatWindow = RoleMentionStatWindow.ALL_BOOK,
    val roleMentionStats: MutableList<BookRoleMentionStat> = mutableListOf(),
    val exportedAt: Long = System.currentTimeMillis()
)

class BookReadAloudExportManager {

    fun exportCurrentChapterScript(book: Book, chapter: BookChapter): BookScriptExport {
        val cache = appDb.readAloudSegmentCacheDao.getLatestByProducerPattern(
            bookUrl = book.bookUrl,
            chapterUrl = chapter.url,
            producerPattern = ReadAloudSegmentCacheKey.PRODUCER_AI_DIALOG_LABEL + "%"
        ) ?: throw IllegalStateException("No AI script cache")
        return BookScriptExport(
            bookUrl = book.bookUrl,
            bookName = book.name,
            bookAuthor = book.author,
            chapterUrl = chapter.url,
            chapterIndex = chapter.index,
            chapterTitle = chapter.title,
            contentHash = cache.contentHash,
            segmentVersion = cache.segmentVersion,
            producer = cache.producer,
            segmentsJson = cache.segmentsJson
        )
    }

    fun importCurrentChapterScript(
        book: Book,
        chapter: BookChapter,
        script: BookScriptExport,
        mode: String
    ) {
        require(script.format == "legado-book-script") { "Invalid script file" }
        require(script.bookUrl == book.bookUrl) { "Book mismatch" }
        require(script.chapterUrl == chapter.url && script.chapterIndex == chapter.index) {
            "Chapter mismatch"
        }
        val chapterContent = currentChapterContent(book, chapter)
        val contentHash = ReadAloudSegmentCacheKey.contentHash(chapterContent)
        require(script.contentHash == contentHash) { "Chapter content changed" }
        val segments = GSON.fromJsonArray<ReadAloudSegment>(script.segmentsJson).getOrNull()
            ?: throw IllegalArgumentException("Invalid script segments")
        require(ReadAloudSegmentCacheValidator.validate(segments, chapterContent)) {
            "Invalid script segments"
        }
        val producer = AiDialogLabelProducerKey.current(AiServiceConfig.load(), mode)
        appDb.readAloudSegmentCacheDao.insert(
            ReadAloudSegmentCache(
                bookUrl = book.bookUrl,
                chapterUrl = chapter.url,
                producer = producer,
                chapterIndex = chapter.index,
                contentHash = contentHash,
                segmentVersion = ReadAloudSegmentCacheKey.AI_DIALOG_LABEL_VERSION,
                segmentsJson = script.segmentsJson,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun exportRoleProfile(book: Book): BookRoleProfileExport {
        return BookRoleProfileExport(
            bookUrl = book.bookUrl,
            bookName = book.name,
            bookAuthor = book.author,
            narratorVoices = HashMap(book.config.narratorVoices ?: hashMapOf()),
            dialogVoices = HashMap(book.config.dialogVoices ?: hashMapOf()),
            roles = book.getReadAloudRoles().map { it.normalizedForExport() }.toMutableList(),
            roleMentionStatWindow = book.getRoleMentionStatWindow(),
            roleMentionStats = book.getRoleMentionStats().map {
                it.normalizedForExport()
            }.toMutableList()
        )
    }

    fun importRoleProfile(book: Book, profile: BookRoleProfileExport) {
        require(profile.format == "legado-book-role-profile") { "Invalid role profile file" }
        book.config.narratorVoices = HashMap(profile.narratorVoices)
        book.config.dialogVoices = HashMap(profile.dialogVoices)
        book.config.readAloudRoles = profile.roles.map { it.normalizedForExport() }.toMutableList()
        book.config.roleMentionStatWindow = profile.roleMentionStatWindow
        book.config.roleMentionStats = profile.roleMentionStats
            .map { it.normalizedForExport() }
            .toMutableList()
        book.ensureSystemExtraRoles()
        book.save()
    }

    fun parseScript(json: String): BookScriptExport {
        return GSON.fromJsonObject<BookScriptExport>(json).getOrNull()
            ?: throw IllegalArgumentException("Invalid script file")
    }

    fun parseRoleProfile(json: String): BookRoleProfileExport {
        return GSON.fromJsonObject<BookRoleProfileExport>(json).getOrNull()
            ?: throw IllegalArgumentException("Invalid role profile file")
    }

    fun toJson(value: Any): String {
        return GSON.toJson(value)
    }

    fun currentChapterContent(book: Book, chapter: BookChapter): String {
        ReadBook.curTextChapter?.takeIf {
            it.chapter.index == chapter.index && it.chapter.url == chapter.url
        }?.let {
            return it.readAloudContent.ifBlank { it.getContent() }
        }
        return BookHelp.getContent(book, chapter)
            ?: throw IllegalStateException("No chapter content")
    }

    private fun BookReadAloudRole.normalizedForExport(): BookReadAloudRole {
        return BookReadAloudRole(
            id = id ?: UUID.randomUUID().toString(),
            name = name?.takeIf { it.isNotBlank() } ?: "未命名角色",
            aliases = aliases?.filter { it.isNotBlank() }?.distinct()?.toMutableList()
                ?: mutableListOf(),
            note = note ?: "",
            voices = HashMap(voices ?: hashMapOf()),
            locked = locked,
            roleCategory = roleCategory?.takeIf { it in BookReadAloudRole.RoleCategory.ORDER }
                ?: BookReadAloudRole.RoleCategory.MINOR_SUPPORTING,
            roleAttribute = roleAttribute?.takeIf { it in BookReadAloudRole.RoleAttribute.ALL }
                ?: BookReadAloudRole.RoleAttribute.MIDDLE_MALE,
            autoManaged = autoManaged,
            systemRole = systemRole
        )
    }

    private fun BookRoleMentionStat.normalizedForExport(): BookRoleMentionStat {
        return BookRoleMentionStat(
            roleKey = roleKey ?: "",
            displayName = displayName ?: "",
            roleAttribute = roleAttribute?.takeIf { it in BookReadAloudRole.RoleAttribute.ALL }
                ?: BookReadAloudRole.RoleAttribute.MIDDLE_MALE,
            totalDialogCount = totalDialogCount,
            chapterDialogCounts = HashMap(chapterDialogCounts ?: mutableMapOf()),
            lastSeenChapter = lastSeenChapter,
            roleId = roleId
        )
    }
}
