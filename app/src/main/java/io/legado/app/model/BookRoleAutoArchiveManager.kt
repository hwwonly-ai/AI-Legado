package io.legado.app.model

import android.util.Log
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookReadAloudRole
import io.legado.app.data.entities.BookRoleMentionStat
import io.legado.app.data.entities.RoleMentionStatWindow
import java.util.UUID

object BookRoleAutoArchiveManager {

    private const val TAG = "roleMatch"
    private const val MINOR_NOTE = "AI 自动创建：统计窗口对白次数 Top10 次要配角"

    private val genericRoleKeys = setOf(
        "对白", "未知", "男人", "女人", "男子", "女子", "老人", "老者", "少年", "少女", "孩子",
        "路人", "众人", "旁人", "仆人", "侍卫", "士兵", "店员", "老板", "司机", "医生", "护士",
        "老妇人", "侍女"
    ).map { BookReadAloudRoleMatcher.normalizeRoleKey(it) }.toSet()

    fun processAiSegments(
        book: Book,
        chapterIndex: Int,
        segments: List<ReadAloudSegment>,
        ttsEngine: String?
    ): Boolean {
        var changed = book.ensureSystemExtraRoles()
        val chapterMentions = linkedMapOf<String, ChapterMention>()

        for (segment in segments) {
            if (segment.role != ReadAloudRole.DIALOG) continue
            val displayName = segment.roleName.trim()
            val roleKey = BookReadAloudRoleMatcher.normalizeRoleKey(displayName)
            if (roleKey.isEmpty()) continue
            val roleAttribute = normalizeRoleAttribute(segment.safeRoleAttribute(), displayName)

            if (roleKey in genericRoleKeys) {
                if (addExtraAlias(book, displayName, roleAttribute)) {
                    changed = true
                    Log.i(
                        TAG,
                        "extraAlias roleName=$displayName attr=$roleAttribute " +
                            "extraRole=${book.findSystemExtraRoleByAttribute(roleAttribute)?.name.orEmpty()} " +
                            "text=${segment.text.logPreview(40)}"
                    )
                }
                continue
            }

            val matched = BookReadAloudRoleMatcher.findByNameOrAlias(book, displayName)
            if (matched != null && shouldSkipAutoArchiveForMatchedRole(matched, roleKey)) {
                continue
            }

            val mention = chapterMentions.getOrPut(roleKey) {
                ChapterMention(roleKey, displayName, roleAttribute, segment.text)
            }
            mention.count += 1
            mention.displayName = displayName
            mention.roleAttribute = roleAttribute
            mention.previewText = segment.text
        }

        if (syncChapterStats(book, chapterIndex, chapterMentions)) {
            changed = true
        }
        if (rebuildDynamicMinorRoles(book, chapterIndex, ttsEngine)) {
            changed = true
        }
        return changed
    }

    private fun shouldSkipAutoArchiveForMatchedRole(
        role: BookReadAloudRole,
        roleKey: String
    ): Boolean {
        if (role.locked || role.roleCategory in BookReadAloudRole.RoleCategory.MANUAL_ONLY) {
            return true
        }
        if (role.roleCategory == BookReadAloudRole.RoleCategory.MINOR_SUPPORTING && role.autoManaged) {
            return false
        }
        if (role.roleCategory == BookReadAloudRole.RoleCategory.EXTRA && role.systemRole) {
            return BookReadAloudRoleMatcher.normalizeRoleKey(role.name) == roleKey
        }
        return true
    }

    fun rebuildDynamicMinorRoles(
        book: Book,
        currentChapter: Int,
        ttsEngine: String?
    ): Boolean {
        val stats = book.getRoleMentionStats()
        val recentChapterWindow = stats
            .flatMap { it.chapterDialogCounts.keys }
            .filter { it <= currentChapter }
            .distinct()
            .sortedDescending()
            .take(20)
            .toSet()
        val ranked = stats.asSequence()
            .mapNotNull { stat ->
                val count = dialogCount(stat, book.getRoleMentionStatWindow(), recentChapterWindow)
                if (count <= 0) return@mapNotNull null
                val role = stat.roleId?.let { book.getReadAloudRoleById(it) }
                if (role != null && (role.locked || role.roleCategory in BookReadAloudRole.RoleCategory.MANUAL_ONLY)) {
                    return@mapNotNull null
                }
                RankedStat(stat, count)
            }
            .sortedWith(
                compareByDescending<RankedStat> { it.dialogCount }
                    .thenByDescending { it.stat.lastSeenChapter }
                    .thenBy { it.stat.displayName }
            )
            .take(10)
            .toList()
        val topKeys = ranked.map { it.stat.roleKey }.toSet()
        val topRoleIds = ranked.mapNotNull { it.stat.roleId }.toSet()
        var changed = false

        for (rank in ranked) {
            if (promoteMinorRole(book, rank.stat, rank.dialogCount, ttsEngine)) {
                changed = true
            }
        }

        val rolesToDemote = book.getReadAloudRoles().filter { role ->
            role.autoManaged &&
                !role.locked &&
                role.roleCategory == BookReadAloudRole.RoleCategory.MINOR_SUPPORTING &&
                shouldDemoteAutoMinor(role, stats, topKeys, topRoleIds)
        }
        for (role in rolesToDemote) {
            if (demoteMinorRole(book, role, stats)) {
                changed = true
            }
        }
        return changed
    }

    private fun shouldDemoteAutoMinor(
        role: BookReadAloudRole,
        stats: List<BookRoleMentionStat>,
        topKeys: Set<String>,
        topRoleIds: Set<String>
    ): Boolean {
        if (role.id in topRoleIds) return false
        val roleKey = BookReadAloudRoleMatcher.normalizeRoleKey(role.name)
        if (roleKey in topKeys) return false
        val stat = stats.find { it.roleId == role.id || it.roleKey == roleKey }
        return stat == null || stat.roleKey !in topKeys
    }

    fun resetAutoArchive(book: Book): ResetResult {
        val statsCount = book.getRoleMentionStats().size
        book.getRoleMentionStats().clear()
        val roles = book.getReadAloudRoles()
        val autoMinorIds = roles
            .filter {
                it.autoManaged &&
                    !it.locked &&
                    it.roleCategory == BookReadAloudRole.RoleCategory.MINOR_SUPPORTING
            }
            .map { it.id }
        autoMinorIds.forEach(book::deleteReadAloudRole)
        Log.i(
            TAG,
            "autoArchiveReset stats=$statsCount autoMinor=${autoMinorIds.size}"
        )
        return ResetResult(
            statCount = statsCount,
            autoMinorCount = autoMinorIds.size
        )
    }

    private fun syncChapterStats(
        book: Book,
        chapterIndex: Int,
        chapterMentions: Map<String, ChapterMention>
    ): Boolean {
        val stats = book.getRoleMentionStats()
        var changed = false
        for (stat in stats) {
            if (stat.chapterDialogCounts.containsKey(chapterIndex) && !chapterMentions.containsKey(stat.roleKey)) {
                val oldCount = stat.chapterDialogCounts.remove(chapterIndex) ?: 0
                stat.totalDialogCount = (stat.totalDialogCount - oldCount).coerceAtLeast(0)
                changed = true
            }
        }
        for (mention in chapterMentions.values) {
            val stat = stats.find { it.roleKey == mention.roleKey }
                ?: BookRoleMentionStat(
                    roleKey = mention.roleKey,
                    displayName = mention.displayName,
                    roleAttribute = mention.roleAttribute,
                    totalDialogCount = 0,
                    chapterDialogCounts = mutableMapOf(),
                    lastSeenChapter = chapterIndex
                ).also {
                    stats.add(it)
                    changed = true
                }
            val oldCount = stat.chapterDialogCounts[chapterIndex] ?: 0
            if (oldCount != mention.count) {
                stat.chapterDialogCounts[chapterIndex] = mention.count
                stat.totalDialogCount = (stat.totalDialogCount - oldCount + mention.count).coerceAtLeast(0)
                changed = true
            }
            if (stat.displayName != mention.displayName) {
                stat.displayName = mention.displayName
                changed = true
            }
            if (stat.roleAttribute != mention.roleAttribute) {
                stat.roleAttribute = mention.roleAttribute
                changed = true
            }
            if (stat.lastSeenChapter != chapterIndex) {
                stat.lastSeenChapter = chapterIndex
                changed = true
            }
            Log.i(
                TAG,
                "stat chapter=$chapterIndex roleName=${mention.displayName} attr=${mention.roleAttribute} " +
                    "count=${stat.totalDialogCount} text=${mention.previewText.logPreview(40)}"
            )
        }
        return changed
    }

    private fun promoteMinorRole(
        book: Book,
        stat: BookRoleMentionStat,
        dialogCount: Int,
        ttsEngine: String?
    ): Boolean {
        val existing = stat.roleId?.let { book.getReadAloudRoleById(it) }
        if (existing != null) {
            if (existing.locked || existing.roleCategory in BookReadAloudRole.RoleCategory.MANUAL_ONLY) return false
            if (existing.roleCategory == BookReadAloudRole.RoleCategory.MINOR_SUPPORTING) {
                var changed = false
                if (existing.autoManaged && existing.roleAttribute != stat.roleAttribute) {
                    existing.roleAttribute = stat.roleAttribute
                    ttsEngine?.takeIf { it.isNotBlank() }?.let { engine ->
                        val voice = existing.voices[engine]
                        if (voice?.startsWith(BookReadAloudRole.VOICE_EXTRA_PREFIX) == true) {
                            existing.voices[engine] = BookReadAloudRole.VOICE_EXTRA_PREFIX + stat.roleAttribute
                        }
                    }
                    changed = true
                }
                return changed
            }
        }

        removeExtraAlias(book, stat.displayName)
        if (hasRoleKeyConflict(book, stat.displayName, emptySet())) {
            Log.i(TAG, "autoSkip roleName=${stat.displayName} reason=conflictWithExistingRole")
            return false
        }

        val roleId = UUID.randomUUID().toString()
        val voices = hashMapOf<String, String>()
        ttsEngine?.takeIf { it.isNotBlank() }?.let {
            voices[it] = BookReadAloudRole.VOICE_EXTRA_PREFIX + stat.roleAttribute
        }
        book.addOrUpdateReadAloudRole(
            BookReadAloudRole(
                id = roleId,
                name = stat.displayName,
                aliases = mutableListOf(),
                note = MINOR_NOTE,
                voices = voices,
                locked = false,
                roleCategory = BookReadAloudRole.RoleCategory.MINOR_SUPPORTING,
                roleAttribute = stat.roleAttribute,
                autoManaged = true,
                systemRole = false
            )
        )
        stat.roleId = roleId
        Log.i(
            TAG,
            "minorPromote roleName=${stat.displayName} attr=${stat.roleAttribute} " +
                "count=$dialogCount roleId=$roleId"
        )
        return true
    }

    private fun demoteMinorRole(
        book: Book,
        role: BookReadAloudRole,
        stats: List<BookRoleMentionStat>
    ): Boolean {
        if (!addExtraAlias(book, role.name, role.roleAttribute, setOf(role.id))) {
            Log.i(TAG, "autoSkip roleName=${role.name} reason=demoteAliasConflict")
            return false
        }
        book.deleteReadAloudRole(role.id)
        stats.filter { it.roleId == role.id }.forEach { it.roleId = null }
        Log.i(
            TAG,
            "minorDemote roleName=${role.name} attr=${role.roleAttribute} " +
                "extraRole=${book.findSystemExtraRoleByAttribute(role.roleAttribute)?.name.orEmpty()}"
        )
        return true
    }

    private fun addExtraAlias(
        book: Book,
        roleName: String,
        roleAttribute: String,
        allowedConflictRoleIds: Set<String> = emptySet()
    ): Boolean {
        val extra = book.findSystemExtraRoleByAttribute(roleAttribute) ?: return false
        val key = BookReadAloudRoleMatcher.normalizeRoleKey(roleName)
        if (key.isEmpty()) return false
        if (BookReadAloudRoleMatcher.normalizeRoleKey(extra.name) == key) return false
        if (extra.aliases.any { BookReadAloudRoleMatcher.normalizeRoleKey(it) == key }) return false
        if (hasRoleKeyConflict(book, roleName, allowedConflictRoleIds + extra.id)) {
            Log.i(TAG, "autoSkip roleName=$roleName reason=conflictWithLead")
            return false
        }
        extra.aliases.add(roleName.trim())
        return true
    }

    private fun removeExtraAlias(book: Book, roleName: String) {
        val key = BookReadAloudRoleMatcher.normalizeRoleKey(roleName)
        if (key.isEmpty()) return
        book.getReadAloudRoles()
            .filter { it.roleCategory == BookReadAloudRole.RoleCategory.EXTRA && it.systemRole }
            .forEach { role ->
                role.aliases.removeAll { BookReadAloudRoleMatcher.normalizeRoleKey(it) == key }
            }
    }

    private fun hasRoleKeyConflict(
        book: Book,
        roleName: String,
        allowedRoleIds: Set<String>
    ): Boolean {
        val key = BookReadAloudRoleMatcher.normalizeRoleKey(roleName)
        if (key.isEmpty()) return true
        return book.getReadAloudRoles().any { role ->
            role.id !in allowedRoleIds &&
                (BookReadAloudRoleMatcher.normalizeRoleKey(role.name) == key ||
                    role.aliases.any { BookReadAloudRoleMatcher.normalizeRoleKey(it) == key })
        }
    }

    private fun dialogCount(
        stat: BookRoleMentionStat,
        window: RoleMentionStatWindow,
        recentChapterWindow: Set<Int>
    ): Int {
        return when (window) {
            RoleMentionStatWindow.ALL_BOOK -> stat.totalDialogCount
            RoleMentionStatWindow.RECENT_20_CHAPTERS -> recentChapterWindow
                .sumOf { stat.chapterDialogCounts[it] ?: 0 }
        }
    }

    private fun normalizeRoleAttribute(raw: String, roleName: String): String {
        if (raw in BookReadAloudRole.RoleAttribute.ALL) return raw
        Log.i(
            TAG,
            "autoSkip roleName=${roleName.ifBlank { "对白" }} reason=invalidRoleAttribute attr=${raw.ifBlank { "-" }}"
        )
        return BookReadAloudRole.RoleAttribute.MIDDLE_MALE
    }

    private data class ChapterMention(
        val roleKey: String,
        var displayName: String,
        var roleAttribute: String,
        var previewText: String,
        var count: Int = 0
    )

    private data class RankedStat(
        val stat: BookRoleMentionStat,
        val dialogCount: Int
    )

    data class ResetResult(
        val statCount: Int,
        val autoMinorCount: Int
    )

    private fun String.logPreview(limit: Int): String {
        val oneLine = replace(Regex("\\s+"), " ").trim()
        return oneLine.take(limit) + if (oneLine.length > limit) "..." else ""
    }

    private fun ReadAloudSegment.safeRoleAttribute(): String {
        return (roleAttribute as String?)
            ?.takeIf { it.isNotBlank() }
            ?: BookReadAloudRole.RoleAttribute.MIDDLE_MALE
    }
}
