package io.legado.app.help.book

import io.legado.app.data.entities.ReplaceRule

data class BookContent(
    val sameTitleRemoved: Boolean,
    val textList: List<String>,
    //起效的替换规则
    val effectiveReplaceRules: List<ReplaceRule>?,
    // 用于朗读的原始内容（保留SSML标签）
    val readAloudText: String = ""
) {

    override fun toString(): String {
        return textList.joinToString("\n")
    }

}