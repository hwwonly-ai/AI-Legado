package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookReadAloudRole

/**
 * MVP7 轻量角色匹配器。
 *
 * AI 标注出的 roleName 在播放音色解析时通过本对象匹配已有角色和别名，
 * 命中后复用角色音色实现跨章节稳定朗读。未匹配不创建角色、不写别名，
 * 只由调用方输出 roleMatch miss 日志，留到 MVP7.1 动态归档。
 */
object BookReadAloudRoleMatcher {

    /**
     * 归一化角色键：trim → 连续空白压缩为单个空格 → 仅 ASCII A-Z 转 lowercase。
     * 中文、日文不做拼音化、不做繁简转换。空字符串不参与匹配。
     */
    fun normalizeRoleKey(name: String): String {
        val collapsed = name.trim().replace(Regex("\\s+"), " ")
        return buildString {
            for (c in collapsed) {
                if (c in 'A'..'Z') append(c.lowercaseChar()) else append(c)
            }
        }
    }

    /**
     * 匹配顺序：角色主名精确匹配 → 角色别名精确匹配（均归一化后比较）。
     * 未命中返回 null，调用方不可据此创建角色或写别名。
     */
    fun findByNameOrAlias(book: Book, roleName: String): BookReadAloudRole? {
        val key = normalizeRoleKey(roleName)
        if (key.isEmpty()) return null
        val roles = book.getReadAloudRoles()
        for (role in roles) {
            if (normalizeRoleKey(role.name) == key) return role
        }
        for (role in roles) {
            if (role.aliases.any { normalizeRoleKey(it) == key }) return role
        }
        return null
    }

    /** 所有归一化键 → 角色，用于诊断/批量场景。主名优先于别名。 */
    fun allRoleKeys(book: Book): Map<String, BookReadAloudRole> {
        val map = HashMap<String, BookReadAloudRole>()
        for (role in book.getReadAloudRoles()) {
            val nameKey = normalizeRoleKey(role.name)
            if (nameKey.isNotEmpty()) map[nameKey] = role
            for (alias in role.aliases) {
                val aliasKey = normalizeRoleKey(alias)
                if (aliasKey.isNotEmpty() && !map.containsKey(aliasKey)) {
                    map[aliasKey] = role
                }
            }
        }
        return map
    }
}
