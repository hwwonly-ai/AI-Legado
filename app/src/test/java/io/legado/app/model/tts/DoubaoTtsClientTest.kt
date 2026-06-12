package io.legado.app.model.tts

import io.legado.app.model.ReadAloudRuleSegmenter
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DoubaoTtsClientTest {

    @Test
    fun buildPayloadKeepsDoubaoRequestFreeFromAliyunSsml() {
        val text = "雨停了。“我们走吧。”他低声说。"
        val requests = DoubaoNovelTtsPlanner(
            DoubaoTtsRenderer(
                DoubaoVoiceConfig(
                    narratorVoiceType = "BV700_V2_streaming",
                    dialogVoiceType = "BV001_V2_streaming"
                )
            )
        ).buildRequests(text, 100)
        val dialogRequest = requests.first { it.speaker == "dialog" }
        val payload = testClient().buildPayload(dialogRequest, "fixed-req-id")

        val audio = payload.mapValue<Map<String, Any>>("audio")
        val request = payload.mapValue<Map<String, Any>>("request")

        assertEquals("BV001_V2_streaming", audio["voice_type"])
        assertEquals("fixed-req-id", request["reqid"])
        assertEquals("query", request["operation"])
        assertFalse(dialogRequest.text.contains("<speak>"))
        assertFalse(dialogRequest.text.contains("<voice"))
        assertFalse(dialogRequest.text.contains("<emotion"))
        assertEquals(100 + text.indexOf('“'), dialogRequest.rawStartPosition)
    }

    @Test
    fun buildPayloadAddsContextTextsAndTagParserIntoExtraParam() {
        val segment = ReadAloudRuleSegmenter.segment(
            text = "他低下头。“对不起。”她没有回答。",
            startPosition = 0,
            renderAliyunSsml = false
        )
        val renderer = DoubaoTtsRenderer(
            DoubaoVoiceConfig(
                enableVoiceTags = true,
                instruction = "以自然有声书风格朗读",
                contextWindowSize = 2
            )
        )
        val request = renderer.render(
            segment = segment[1].copy(styleTag = "哽咽地"),
            previousSegments = listOf(segment[0])
        )
        val payload = testClient().buildPayload(request, "fixed-req-id")
        val requestBody = payload.mapValue<Map<String, Any>>("request")
        val extraParam = requestBody["extra_param"].toString()

        assertTrue(request.text.startsWith("[#以自然有声书风格朗读][哽咽地]"))
        assertTrue(extraParam.contains("context_texts"))
        assertTrue(extraParam.contains("旁白：他低下头。"))
        assertTrue(extraParam.contains("use_tag_parser"))
    }

    @Test
    fun authorizationHeaderAddsBearerPrefixOnce() {
        assertEquals(
            "Bearer; token",
            DoubaoApiConfig(appId = "app", accessToken = "token").authorizationHeader()
        )
        assertEquals(
            "Bearer; token",
            DoubaoApiConfig(appId = "app", accessToken = "Bearer; token").authorizationHeader()
        )
    }

    @Test
    fun buildPayloadV3UsesReqParamsShape() {
        val request = DoubaoTtsRequest(
            text = "你好，这是 V3 测试。",
            voiceType = "zh_female_vv_uranus_bigtts",
            emotion = null,
            contextTexts = emptyList(),
            useTagParser = false,
            rawStartPosition = 0,
            rawEndPosition = 10,
            speaker = "narrator",
            speakerName = "旁白",
            cacheKeySeed = "seed"
        )
        val client = DoubaoTtsClientV3(
            DoubaoApiConfig(appId = "app", accessToken = "token"),
            client = OkHttpClient()
        )
        val payload = client.buildPayloadV3(request, "fixed-req-id")
        val reqParams = payload.mapValue<Map<String, Any>>("req_params")
        val audioParams = reqParams.mapValue<Map<String, Any>>("audio_params")

        assertFalse(payload.containsKey("app"))
        assertFalse(payload.containsKey("audio"))
        assertFalse(payload.containsKey("request"))
        assertEquals("你好，这是 V3 测试。", reqParams["text"])
        assertEquals("zh_female_vv_uranus_bigtts", reqParams["speaker"])
        assertEquals("mp3", audioParams["format"])
        assertEquals(24000, audioParams["sample_rate"])
        assertEquals("https://openspeech.bytedance.com/api/v3/tts/unidirectional", clientConfig().endpointV3)
        assertEquals("seed-tts-2.0", clientConfig().resourceIdV3)
    }

    @Test
    fun synthesizeWithRealCredentialsWhenProvided() {
        val appId = secret("DOUBAO_TTS_APP_ID", "doubao.tts.appId")
        val accessToken = secret("DOUBAO_TTS_ACCESS_TOKEN", "doubao.tts.accessToken")
        val voiceType = secret("DOUBAO_TTS_VOICE_TYPE", "doubao.tts.voiceType")
            .ifBlank { "BV700_V2_streaming" }
        assumeTrue(
            "Set DOUBAO_TTS_APP_ID and DOUBAO_TTS_ACCESS_TOKEN to run real Doubao TTS smoke test.",
            appId.isNotBlank() && accessToken.isNotBlank()
        )

        val request = DoubaoTtsRenderer(
            DoubaoVoiceConfig(narratorVoiceType = voiceType)
        ).render(
            ReadAloudRuleSegmenter.segment(
                text = "你好，这是豆包语音合成测试。",
                startPosition = 0,
                renderAliyunSsml = false
            ).first()
        )
        val audio = kotlinx.coroutines.runBlocking {
            DoubaoTtsClient(
                DoubaoApiConfig(
                    appId = appId,
                    accessToken = accessToken
                ),
                client = OkHttpClient()
            ).synthesize(request)
        }

        assertTrue(audio.bytes.isNotEmpty())
        assertEquals("audio/mpeg", audio.contentType)
    }

    private fun testClient(): DoubaoTtsClient {
        return DoubaoTtsClient(
            clientConfig(),
            client = OkHttpClient()
        )
    }

    private fun clientConfig(): DoubaoApiConfig {
        return DoubaoApiConfig(
            appId = "app",
            accessToken = "token"
        )
    }

    private fun secret(envName: String, propertyName: String): String {
        return System.getenv(envName)
            ?: System.getProperty(propertyName)
            ?: ""
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> Map<String, Any>.mapValue(key: String): T {
        return this[key] as T
    }
}
