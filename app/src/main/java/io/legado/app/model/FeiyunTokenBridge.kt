package io.legado.app.model

import cn.hutool.core.util.HexUtil
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.PreferKey
import io.legado.app.help.CacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.crypto.SymmetricCryptoAndroid
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postForm
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import okhttp3.HttpUrl.Companion.toHttpUrl
import splitties.init.appCtx

object FeiyunTokenBridge {

    private const val DEFAULT_BASE_URL = "http://81.70.179.142"
    private const val DEFAULT_BLY = "89ebaa914cb4ab1f"
    private const val DEFAULT_USER_ID = "hfo8icxuujeo"
    private const val FEIYUN_IV = "8888833388888888"
    private const val FEIYUN_VOSTR_KEY = "beiYong1beiYong1"
    private const val FEIYUN_MD5QWE_KEY = "beiYonasdfghjkl@"
    private const val FEIYUN_LRTS_COOKIE_KEY = "(xiaolanTingShu)"
    private const val FEIYUN_QIANMING = "d045aa52febd81818f6df7ed7ece09dd"
    private const val FEIYUN_SIGN = "21900fe0a2fa5e4027e897dd40660da7"
    private const val FEIYUN_PACKAGE = "cn.longniu.pages"
    private const val DEFAULT_XIAOLAN_BEIYONG5 = "a82f7dc93d30d081ca31e57eb37f99f0"
    private const val DEFAULT_XIAOLAN_BEIYONG3 = "66fc5eace1e4293ecce92d1832dea656"
    private const val CACHE_PREFIX = "feiyun_bridge_"
    private const val LRTS_API = "https://m.lrts.me/ajax"
    private const val LRTS_UA =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/109.0.99.127 Safari/537.36"

    val defaultBaseUrl: String get() = DEFAULT_BASE_URL
    val defaultBly: String get() = DEFAULT_BLY
    val defaultUserId: String get() = DEFAULT_USER_ID

    suspend fun playUrl(json: String): String {
        if (!AppConfig.feiyunBridgeEnabled) {
            error("飞鸟服务器桥接未启用")
        }
        val request = PlayRequest.fromJson(json)
        val zhuUrl = requestFeiyunPlay(request)
        return if (zhuUrl.startsWith("http://", true) || zhuUrl.startsWith("https://", true)) {
            zhuUrl
        } else {
            val cookie = decryptLrtsCookie(zhuUrl)
            CacheManager.put("${CACHE_PREFIX}lrts_cookie", cookie, 12 * 60 * 60)
            requestLrtsPlay(cookie, request)
        }
    }

    suspend fun testConnection(): String {
        val url = playUrl(
            GSON.toJson(
                mapOf(
                    "core" to "xiaoLan",
                    "albumId" to "xiaoLan44636",
                    "trackId" to "4463600000414__414",
                    "entityId" to "44636",
                    "section" to 414,
                    "title" to "秦问|是改变命运还是改变历史？",
                    "albumTitle" to "第414集",
                    "zhuBo" to "匹公子",
                    "tracks" to "710"
                )
            )
        )
        return "连接成功: ${url.take(80)}"
    }

    fun ensureDefaultConfig() {
        if (appCtx.getPrefString(PreferKey.feiyunBridgeBaseUrl).isNullOrBlank()) {
            appCtx.putPrefString(PreferKey.feiyunBridgeBaseUrl, DEFAULT_BASE_URL)
        }
        if (appCtx.getPrefString(PreferKey.feiyunBridgeBly).isNullOrBlank()) {
            appCtx.putPrefString(PreferKey.feiyunBridgeBly, DEFAULT_BLY)
        }
        if (appCtx.getPrefString(PreferKey.feiyunBridgeUserId).isNullOrBlank()) {
            appCtx.putPrefString(PreferKey.feiyunBridgeUserId, DEFAULT_USER_ID)
        }
        if (!appCtx.getPrefBoolean(PreferKey.feiyunBridgeInitialized)) {
            appCtx.putPrefBoolean(PreferKey.feiyunBridgeInitialized, true)
        }
    }

    fun clearCache() {
        CacheManager.delete("${CACHE_PREFIX}lrts_cookie")
    }

    private suspend fun requestFeiyunPlay(request: PlayRequest): String {
        val body = okHttpClient.newCallStrResponse {
            url("${AppConfig.feiyunBridgeBaseUrl.trimEnd('/')}/TsApi/neiRong")
            addHeaders(buildHeaders(request))
            postForm(buildForm(request))
        }.body
        val root = JsonParser.parseString(body).asJsonObject
        val status = root.get("status")?.asInt
        val message = root.get("message")?.asString ?: root.get("msg")?.asString
        if (status != null && status != 200) {
            error(message ?: "飞鸟服务器返回异常: $status")
        }
        val zhuUrl = root.objectOrNull("data")?.string("zhuUrl")
        if (zhuUrl.isNullOrBlank()) {
            error(message ?: "飞鸟服务器未返回播放地址: ${body?.take(200)}")
        }
        return zhuUrl
    }

    private fun buildHeaders(request: PlayRequest): Map<String, String> {
        val now = System.currentTimeMillis()
        val voPayload = linkedMapOf(
            "jarVersion" to request.voJarVersion,
            "time" to (now + 14).toString(),
            "sheng" to request.sheng
        )
        return linkedMapOf(
            "leiXing" to "app",
            "biaoShi" to "feiyun",
            "jarVersion" to "32.60",
            "version" to "2.2606",
            "voStr" to encryptHex(GSON.toJson(voPayload), FEIYUN_VOSTR_KEY),
            "bly" to AppConfig.feiyunBridgeBly,
            "ud" to AppConfig.feiyunBridgeUserId,
            "User-Agent" to request.userAgent
        )
    }

    private fun buildForm(request: PlayRequest): Map<String, String> {
        val signPayload = linkedMapOf(
            "qianMing" to FEIYUN_QIANMING,
            "sss" to 0,
            "isSdkReady" to true,
            "trackId" to request.trackId,
            "sign" to FEIYUN_SIGN,
            "time" to System.currentTimeMillis(),
            "packageName" to FEIYUN_PACKAGE
        )
        return linkedMapOf<String, String>().apply {
            put("stscTimeZong", request.stscTimeZong)
            put("trackId", request.trackId)
            put("anNiu", request.anNiu)
            request.yinzhiNo?.let { put("yinzhiNo", it) }
            put("albumId", request.albumId)
            put("infoVo", request.infoVo)
            request.isXin?.let { put("isXin", it) }
            request.beiYong5?.let { put("beiYong5", it) }
            put("core", request.core)
            request.beiYong3?.let { put("beiYong3", it) }
            request.isFree?.let { put("isFree", it) }
            request.fromUrl?.let { put("fromUrl", it) }
            request.asdxin?.let { put("asdxin", it) }
            putAll(request.extraForm)
            put("isX5", request.isX5)
            put("md5qwe", encryptHex(GSON.toJson(signPayload), FEIYUN_MD5QWE_KEY))
            put("anNiuBiaoZhi", request.anNiuBiaoZhi)
        }
    }

    private fun encryptHex(text: String, key: String): String {
        val crypto = SymmetricCryptoAndroid(
            "AES/CBC/PKCS5Padding",
            key.encodeToByteArray()
        )
        crypto.setIv(FEIYUN_IV.encodeToByteArray())
        return HexUtil.encodeHexStr(crypto.encrypt(text))
    }

    fun decryptLrtsCookie(encryptedHex: String): String {
        val crypto = SymmetricCryptoAndroid(
            "AES/CBC/PKCS5Padding",
            FEIYUN_LRTS_COOKIE_KEY.encodeToByteArray()
        )
        crypto.setIv(FEIYUN_IV.encodeToByteArray())
        return String(crypto.decrypt(encryptedHex))
    }

    private suspend fun requestLrtsPlay(cookie: String, request: PlayRequest): String {
        val entityId = request.entityId ?: inferEntityId(request.albumId)
            ?: error("懒人播放需要 entityId，或 albumId 末尾包含数字")
        val section = request.section ?: inferSection(request.trackId)
            ?: error("懒人播放需要 section，或 trackId 包含 __章节号")
        val mediaId = request.lrtsId ?: "$entityId${section.toString().padStart(8, '0')}"
        val entityType = if (request.core.equals("xiaoLan", ignoreCase = true)) 3 else request.entityType
        val url = "$LRTS_API/getPlayPath".toHttpUrl().newBuilder()
            .addQueryParameter("entityId", entityId)
            .addQueryParameter("entityType", entityType.toString())
            .addQueryParameter("opType", "1")
            .addQueryParameter("sections", "[$section]")
            .addQueryParameter("type", "0")
            .addQueryParameter("section", section.toString())
            .addQueryParameter("id", mediaId)
            .build()
        val body = okHttpClient.newCallStrResponse {
            url(url)
            addHeader("Cookie", cookie)
            addHeader("User-Agent", LRTS_UA)
            addHeader("Accept", "application/json,text/plain,*/*")
            addHeader("Referer", "https://m.lrts.me/")
        }.body
        val responseBody = body ?: error("懒人接口返回为空")
        return parseLrtsPlayUrl(responseBody)
            ?: error("懒人接口未返回播放地址: ${responseBody.take(200)}")
    }

    private fun parseLrtsPlayUrl(body: String): String? {
        val root = JsonParser.parseString(body).asJsonObject
        root.string("path")?.takeIf { it.isNotBlank() }?.let { return it }
        root.string("url")?.takeIf { it.isNotBlank() }?.let { return it }
        val data = root.objectOrNull("data")
        data?.string("path")?.takeIf { it.isNotBlank() }?.let { return it }
        data?.string("url")?.takeIf { it.isNotBlank() }?.let { return it }
        data?.arrayOrNull("list")?.firstOrNull()?.asObjectOrNull()?.let {
            it.string("path")?.takeIf { path -> path.isNotBlank() }?.let { path -> return path }
            it.string("url")?.takeIf { path -> path.isNotBlank() }?.let { path -> return path }
        }
        root.arrayOrNull("list")?.firstOrNull()?.asObjectOrNull()?.let {
            it.string("path")?.takeIf { path -> path.isNotBlank() }?.let { path -> return path }
            it.string("url")?.takeIf { path -> path.isNotBlank() }?.let { path -> return path }
        }
        return null
    }

    private fun inferEntityId(albumId: String): String? {
        return Regex("(\\d+)$").find(albumId)?.groupValues?.get(1)
    }

    private fun inferSection(trackId: String): Int? {
        return Regex("__(\\d+)$").find(trackId)?.groupValues?.get(1)?.toIntOrNull()
    }

    data class PlayRequest(
        val core: String,
        val albumId: String,
        val trackId: String,
        val entityId: String?,
        val entityType: Int,
        val section: Int?,
        val lrtsId: String?,
        val infoVo: String,
        val stscTimeZong: String,
        val anNiu: String,
        val yinzhiNo: String?,
        val isXin: String?,
        val beiYong5: String?,
        val beiYong3: String?,
        val isFree: String?,
        val fromUrl: String?,
        val asdxin: String?,
        val extraForm: Map<String, String>,
        val isX5: String,
        val anNiuBiaoZhi: String,
        val voJarVersion: String,
        val sheng: String,
        val userAgent: String
    ) {
        companion object {
            fun fromJson(json: String): PlayRequest {
                val root = JsonParser.parseString(json).asJsonObject
                val core = root.requiredString("core")
                val albumId = root.requiredString("albumId")
                val trackId = root.requiredString("trackId")
                return PlayRequest(
                    core = core,
                    albumId = albumId,
                    trackId = trackId,
                    entityId = root.string("entityId"),
                    entityType = root.int("entityType") ?: 3,
                    section = root.int("section"),
                    lrtsId = root.string("lrtsId") ?: root.string("id"),
                    infoVo = root.string("infoVo") ?: buildInfoVo(root),
                    stscTimeZong = root.string("stscTimeZong") ?: "60000",
                    anNiu = root.string("anNiu") ?: "tingShu",
                    yinzhiNo = root.string("yinzhiNo"),
                    isXin = root.string("isXin") ?: if (core == "xiaoLan") "1" else null,
                    beiYong5 = root.string("beiYong5") ?: if (core == "xiaoLan") {
                        DEFAULT_XIAOLAN_BEIYONG5
                    } else {
                        null
                    },
                    beiYong3 = root.string("beiYong3") ?: if (core == "xiaoLan") {
                        DEFAULT_XIAOLAN_BEIYONG3
                    } else {
                        null
                    },
                    isFree = root.string("isFree"),
                    fromUrl = root.string("fromUrl"),
                    asdxin = root.string("asdxin"),
                    extraForm = root.objectMap("extraForm"),
                    isX5 = root.string("isX5") ?: "false",
                    anNiuBiaoZhi = root.string("anNiuBiaoZhi") ?: "tingShu",
                    voJarVersion = root.string("voJarVersion") ?: "31.9",
                    sheng = root.string("sheng") ?: "中国-陕西-西安-移动",
                    userAgent = root.string("userAgent")
                        ?: "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/80.0.3987.87 Safari/537.36 SE 2.X MetaSr 1.0"
                )
            }

            private fun buildInfoVo(root: JsonObject): String {
                val section = root.int("section")?.toString()
                val infoVo = linkedMapOf(
                    "isSerial" to (root.string("isSerial") ?: "1"),
                    "orderNo" to (root.string("orderNo") ?: section.orEmpty()),
                    "cover_path" to (root.string("cover_path") ?: root.string("coverPath").orEmpty()),
                    "albumTitle" to (root.string("albumTitle") ?: "第${section ?: 1}集"),
                    "title" to (root.string("title") ?: ""),
                    "zhuBo" to (root.string("zhuBo") ?: root.string("zhubo") ?: ""),
                    "tracks" to (root.string("tracks") ?: "")
                )
                return GSON.toJson(infoVo)
            }
        }
    }

    private fun JsonObject.requiredString(key: String): String {
        return string(key)?.takeIf { it.isNotBlank() } ?: error("飞鸟参数缺少 $key")
    }

    private fun JsonObject.string(key: String): String? {
        val item = get(key) ?: return null
        if (item.isJsonNull) return null
        return item.asString
    }

    private fun JsonObject.int(key: String): Int? {
        val value = string(key) ?: return null
        return value.toIntOrNull()
    }

    private fun JsonObject.objectMap(key: String): Map<String, String> {
        val item = get(key) ?: return emptyMap()
        if (item.isJsonNull || !item.isJsonObject) return emptyMap()
        return item.asJsonObject.entrySet().associate { it.key to it.value.asString }
    }

    private fun JsonObject.objectOrNull(key: String): JsonObject? {
        val item = get(key) ?: return null
        if (item.isJsonNull || !item.isJsonObject) return null
        return item.asJsonObject
    }

    private fun JsonObject.arrayOrNull(key: String) = get(key)?.takeIf {
        !it.isJsonNull && it.isJsonArray
    }?.asJsonArray

    private fun com.google.gson.JsonElement.asObjectOrNull(): JsonObject? {
        if (isJsonNull || !isJsonObject) return null
        return asJsonObject
    }
}
