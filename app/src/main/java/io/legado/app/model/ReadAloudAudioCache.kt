package io.legado.app.model

import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import java.io.File

/**
 * MVP7 测试期 TTS 音频缓存清理。
 *
 * 用户保存角色音色后，清理当前章节的音频缓存（MP3），让后续播放按新音色重新合成。
 * 只清 ttsFolderPath 下、文件名以该章节 title 的 md5 前缀开头的文件，
 * 与 HttpReadAloudService.removeCacheFile 同样的命名约定（md5SpeakFileName = titleMd5 + "_" + ...）。
 * 不触碰 AI/规则 Segment 缓存（readAloudSegmentCacheDao），正式按角色/音色/Provider 维度精确失效留到 MVP10。
 */
object ReadAloudAudioCache {

    /** 音频缓存目录名，与 HttpReadAloudService.ttsFolderPath 保持一致。 */
    private const val AUDIO_DIR_NAME = "httpTTS"

    data class Stats(
        val fileCount: Int,
        val totalBytes: Long
    )

    data class ClearResult(
        val fileCount: Int,
        val totalBytes: Long
    )

    /**
     * 清理指定章节的 TTS 音频缓存。
     *
     * @param cacheDir 应用缓存目录（context.cacheDir）
     * @param chapterTitle 当前章节标题，与 md5SpeakFileName 哈希的 title 一致
     */
    fun clearChapterAudioCache(cacheDir: File, chapterTitle: String?): ClearResult {
        if (chapterTitle.isNullOrBlank()) return ClearResult(0, 0)
        val folder = File(cacheDir, AUDIO_DIR_NAME)
        if (!folder.exists()) return ClearResult(0, 0)
        val titleMd5 = MD5Utils.md5Encode16(chapterTitle)
        return clearFilesMatching(cacheDir) {
            it.name.startsWith(titleMd5)
        }
    }

    fun clearBookAudioCache(cacheDir: File, chapterTitles: Collection<String>): ClearResult {
        val titlePrefixes = chapterTitles
            .asSequence()
            .filter { it.isNotBlank() }
            .map { MD5Utils.md5Encode16(it) }
            .toHashSet()
        if (titlePrefixes.isEmpty()) return ClearResult(0, 0)
        return clearFilesMatching(cacheDir) { file ->
            titlePrefixes.any { file.name.startsWith(it) }
        }
    }

    fun clearAllAudioCache(cacheDir: File): ClearResult {
        return clearFilesMatching(cacheDir) { true }
    }

    fun stats(cacheDir: File): Stats {
        val folder = File(cacheDir, AUDIO_DIR_NAME)
        val files = FileUtils.listDirsAndFiles(folder.absolutePath)
            ?.filter { it.isFile }
            ?: return Stats(0, 0)
        return Stats(files.size, files.sumOf { it.length() })
    }

    private fun clearFilesMatching(cacheDir: File, predicate: (File) -> Boolean): ClearResult {
        val folder = File(cacheDir, AUDIO_DIR_NAME)
        if (!folder.exists()) return ClearResult(0, 0)
        val files = FileUtils.listDirsAndFiles(folder.absolutePath) ?: return ClearResult(0, 0)
        var count = 0
        var bytes = 0L
        for (file in files) {
            if (!file.isFile || !predicate(file)) continue
            val length = file.length()
            if (FileUtils.delete(file.absolutePath)) {
                count += 1
                bytes += length
            }
        }
        return ClearResult(count, bytes)
    }
}
