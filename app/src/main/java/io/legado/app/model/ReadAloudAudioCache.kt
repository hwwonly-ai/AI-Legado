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

    /**
     * 清理指定章节的 TTS 音频缓存。
     *
     * @param cacheDir 应用缓存目录（context.cacheDir）
     * @param chapterTitle 当前章节标题，与 md5SpeakFileName 哈希的 title 一致
     */
    fun clearChapterAudioCache(cacheDir: File, chapterTitle: String?) {
        if (chapterTitle.isNullOrBlank()) return
        val folder = File(cacheDir, AUDIO_DIR_NAME)
        if (!folder.exists()) return
        val titleMd5 = MD5Utils.md5Encode16(chapterTitle)
        val files = FileUtils.listDirsAndFiles(folder.absolutePath) ?: return
        for (file in files) {
            if (!file.isFile) continue
            if (file.name.startsWith(titleMd5)) {
                FileUtils.delete(file.absolutePath)
            }
        }
    }
}
