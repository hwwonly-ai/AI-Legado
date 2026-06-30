package io.legado.app.model

import android.util.Log
import splitties.init.appCtx

object ReadAloudCacheInvalidator {

    private const val TAG = "audioCache"

    fun clearCurrentChapterAudio(reason: String): ReadAloudAudioCache.ClearResult {
        val title = ReadBook.curTextChapter?.title
        val result = ReadAloudAudioCache.clearChapterAudioCache(appCtx.cacheDir, title)
        Log.i(
            TAG,
            "clearCurrentChapter reason=$reason title=${title.orEmpty()} " +
                "files=${result.fileCount} bytes=${result.totalBytes}"
        )
        return result
    }
}
