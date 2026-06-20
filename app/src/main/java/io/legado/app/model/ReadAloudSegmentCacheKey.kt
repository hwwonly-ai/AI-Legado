package io.legado.app.model

import io.legado.app.utils.MD5Utils

object ReadAloudSegmentCacheKey {
    const val PRODUCER_RULE = "rule"
    const val RULE_SEGMENT_VERSION = 1

    fun normalizeContent(content: String): String {
        return content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    fun contentHash(content: String): String {
        return MD5Utils.md5Encode(normalizeContent(content))
    }
}
