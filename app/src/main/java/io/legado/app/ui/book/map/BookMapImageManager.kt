package io.legado.app.ui.book.map

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import io.legado.app.data.entities.Book
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest

data class MapImageCandidate(
    val title: String,
    val pageUrl: String,
    val imageUrl: String,
    val thumbnailUrl: String = imageUrl
)

data class MapPeriod(
    val title: String,
    val pageUrl: String
)

object BookMapImageManager {
    private const val SITE = "http://www.laozhaopian5.com/ditu/"
    private const val MAP_DIR = "book-map-images"
    private const val CACHE_DIR = "cache"
    private const val MIRROR_SITE = "http://www.txlzp.com/ditu/"
    private val dynastyPages = linkedMapOf(
        "秦" to "qin", "西汉" to "xihan", "东汉" to "donghan", "汉" to "donghan",
        "三国" to "sanguo", "两晋" to "jin", "晋" to "jin", "南北朝" to "nanbei",
        "隋" to "sui", "唐" to "tang", "五代" to "wudai", "宋" to "song",
        "元" to "yuan", "明" to "ming", "清" to "qing", "民国" to "minguo"
    )
    private val dynastyAliases = mapOf("汉" to "东汉", "晋" to "两晋")
    private val periodFallbackUrls = mapOf(
        "各省省会变迁" to SITE + "diming/"
    )

    fun currentFile(book: Book): File? = book.readConfig?.mapImagePath
        ?.let(::File)
        ?.takeIf { it.isFile }

    fun currentPath(book: Book): String? = currentFile(book)?.absolutePath

    fun inferDynasty(book: Book): String? {
        val text = listOf(book.name, book.intro, book.kind, book.customTag).joinToString(" ")
        return dynastyPages.keys.sortedByDescending { it.length }
            .firstOrNull { text.contains(it) }
            ?.let { dynastyAliases[it] ?: it }
    }

    fun dynastyNames(): List<String> = dynastyPages.keys.filterNot { it in dynastyAliases }

    suspend fun fetchPeriods(): List<MapPeriod> = withContext(Dispatchers.IO) {
        val homeUrl = SITE
        val doc = Jsoup.parse(fetchHtml(homeUrl), homeUrl)
        doc.select(".flinklist2 a[href]").mapNotNull { link ->
            val title = link.text().trim()
            val url = link.absUrl("href").takeIf { it.isNotBlank() }
            if (title.isBlank() || url == null) null
            else MapPeriod(title, periodFallbackUrls[title] ?: url)
        }.distinctBy { "${it.title}|${it.pageUrl}" }
            .ifEmpty {
                dynastyPages.map { (title, path) -> MapPeriod("${title}历史地图", SITE + path + ".html") }
            }
    }

    suspend fun fetchCandidates(period: MapPeriod): List<MapImageCandidate> = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(fetchHtml(period.pageUrl), period.pageUrl)
        val body = doc.selectFirst(".mapbody") ?: doc
        val links = body.select("a[href]").mapNotNull { link ->
            val url = normalizeSiteUrl(link.absUrl("href")).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val image = link.selectFirst("img")
            val title = link.attr("title").ifBlank { link.text() }.ifBlank {
                image?.attr("alt").orEmpty()
            }.trim()
            if (title.isBlank()) null else Triple(url, title, image)
        }.distinctBy { it.first }

        val directCandidates = links.mapNotNull { (url, title, image) ->
            if (!isLikelyImage(url)) return@mapNotNull null
            MapImageCandidate(
                title = title,
                pageUrl = period.pageUrl,
                imageUrl = url,
                thumbnailUrl = normalizeSiteUrl(image?.absUrl("src").orEmpty()).ifBlank { url }
            )
        }.distinctBy { it.imageUrl }

        val articleLinks = links.mapNotNull { (url, title, _) ->
            if (isLikelyImage(url) || url == normalizeSiteUrl(period.pageUrl)) return@mapNotNull null
            if (url.endsWith("1215.html") || url.endsWith("ditu.jpg")) return@mapNotNull null
            url to title
        }.distinctBy { it.first }

        coroutineScope {
            val direct = directCandidates.map { candidate ->
                async { candidate.takeIf { isReachableImage(it.imageUrl) } }
            }
            val articles = articleLinks.map { (url, title) ->
                async { runCatching { resolvePageImage(url, title) }.getOrNull() }
            }
            (direct + articles).awaitAll().filterNotNull().distinctBy { it.imageUrl }
        }
    }

    suspend fun fetchCandidates(dynasty: String): List<MapImageCandidate> {
        val period = fetchPeriods().firstOrNull { it.title.contains(dynasty) }
            ?: MapPeriod("${dynasty}历史地图", SITE + (dynastyPages[dynasty] ?: return emptyList()) + ".html")
        return fetchCandidates(period)
    }

    suspend fun saveLocal(context: Context, book: Book, uri: Uri): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "$MAP_DIR/current").apply { mkdirs() }
        val target = File(dir, "${safeName(book.bookUrl)}.img")
        val temp = File(dir, target.name + ".tmp")
        context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use(input::copyTo) }
            ?: error("无法读取图片")
        validateImage(temp)
        replaceFile(temp, target)
        updateBook(book, target.absolutePath, uri.toString(), "本地图片")
        target.absolutePath
    }

    fun cachedFile(context: Context, candidate: MapImageCandidate): File? {
        return candidateCacheFile(context, candidate).takeIf { it.isFile }
    }

    suspend fun download(context: Context, book: Book, candidate: MapImageCandidate): String = withContext(Dispatchers.IO) {
        val target = candidateCacheFile(context, candidate)
        if (target.isFile) {
            runCatching { validateImage(target) }
                .onSuccess {
                    updateBook(book, target.absolutePath, candidate.imageUrl, candidate.title)
                    return@withContext target.absolutePath
                }
                .onFailure { target.delete() }
        }

        val temp = File(target.parentFile, target.name + ".tmp").apply { delete() }
        val response = okHttpClient.newCall(Request.Builder().url(candidate.imageUrl).build()).execute()
        response.use {
            if (!it.isSuccessful) error("HTTP ${it.code}")
            val body = it.body
            if (body.contentType()?.type != "image") error("服务器未返回图片")
            body.byteStream().use { input -> temp.outputStream().use(input::copyTo) }
        }
        validateImage(temp)
        replaceFile(temp, target)
        updateBook(book, target.absolutePath, candidate.imageUrl, candidate.title)
        target.absolutePath
    }

    fun clear(book: Book) {
        book.readConfig?.mapImagePath?.let { path ->
            val file = File(path)
            // 远程地图缓存允许其他书籍复用，只解除当前书籍的引用。
            if (file.parentFile?.name != CACHE_DIR) file.delete()
        }
        book.config.mapImagePath = null
        book.config.mapImageSource = null
        book.config.mapImageTitle = null
        book.save()
    }

    private fun updateBook(book: Book, path: String, source: String, title: String) {
        val previous = book.readConfig?.mapImagePath
        book.config.mapImagePath = path
        book.config.mapImageSource = source
        book.config.mapImageTitle = title
        book.save()
        if (previous != null && previous != path) {
            val previousFile = File(previous)
            if (previousFile.parentFile?.name != CACHE_DIR) previousFile.delete()
        }
    }

    private fun candidateCacheFile(context: Context, candidate: MapImageCandidate): File {
        val dir = File(context.filesDir, "$MAP_DIR/$CACHE_DIR").apply { mkdirs() }
        return File(dir, sha256(candidate.imageUrl) + ".img")
    }

    private fun replaceFile(temp: File, target: File) {
        if (temp.renameTo(target)) return
        target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun validateImage(file: File) {
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            error("文件不是有效图片")
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            file.delete()
            error("文件不是有效图片")
        }
    }

    private fun safeName(value: String): String = value.toByteArray().joinToString("") { "%02x".format(it) }.take(120)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun decodeHtml(bytes: ByteArray): String {
        val head = bytes.copyOfRange(0, minOf(bytes.size, 2048)).toString(Charsets.ISO_8859_1)
        val charset = Regex("charset\\s*=\\s*['\\\"]?([\\w-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
        return runCatching { Charset.forName(charset ?: "GB2312").decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { bytes.toString(Charsets.UTF_8) }
    }

    private fun fetchHtml(url: String): String {
        val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.isSuccessful) error("HTTP ${it.code}")
            return decodeHtml(it.body.bytes())
        }
    }

    private fun resolvePageImage(pageUrl: String, title: String): MapImageCandidate? {
        val normalizedPageUrl = normalizeSiteUrl(pageUrl)
        val doc = Jsoup.parse(fetchHtml(normalizedPageUrl), normalizedPageUrl)
        val image = doc.select(".mapbody img, img").firstOrNull { image ->
            val src = normalizeSiteUrl(image.absUrl("src"))
            src.isNotBlank() && !isSitePlaceholder(src) && !src.contains("/css/") &&
                isReachableImage(src)
        } ?: return null
        val thumbnail = normalizeSiteUrl(image.absUrl("src"))
        val original = normalizeSiteUrl(
            image.parent()?.absUrl("href").takeIf { !it.isNullOrBlank() } ?: thumbnail
        )
        if (!isReachableImage(original)) return null
        return MapImageCandidate(title, normalizedPageUrl, original, thumbnail)
    }

    private fun isReachableImage(url: String): Boolean = runCatching {
        val request = Request.Builder().url(url).head().build()
        okHttpClient.newCall(request).execute().use { response ->
            response.isSuccessful && response.header("Content-Type")?.startsWith("image/") == true
        }
    }.getOrDefault(false)

    private fun normalizeSiteUrl(url: String): String {
        return if (url.startsWith(MIRROR_SITE)) SITE + url.removePrefix(MIRROR_SITE) else url
    }

    private fun isLikelyImage(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
            path.endsWith(".webp")
    }

    private fun isSitePlaceholder(url: String): Boolean {
        return url.endsWith("/ditu.jpg") || url.endsWith("ditu.jpg") ||
            url.contains("/css/") || url.endsWith(".gif")
    }
}
