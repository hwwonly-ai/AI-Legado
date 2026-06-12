package io.legado.app.model.tts

import io.legado.app.model.ReadAloudRuleSegmenter
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DoubaoApiIntegrationTest {

    @Test
    fun testDoubaoApiIntegration() {
        // 豆包配置
        val appId = "3375438334"
        val accessToken = "PFx-n_ffssXh0bE-WM7XwIuNgEdpy0XR"
        val endpoint = "https://wa8zhbufmo-8300.cnb.run/"

        // 创建配置
        val config = DoubaoApiConfig(
            appId = appId,
            accessToken = accessToken,
            endpoint = endpoint
        )

        // 测试文本
        val testText = "你好，这是豆包语音合成测试。"

        // 生成分段
        val segments = ReadAloudRuleSegmenter.segment(
            text = testText,
            startPosition = 0,
            renderAliyunSsml = false
        )

        println("=== 生成的分段 ===")
        segments.forEachIndexed { i, seg ->
            println("段 $i: speaker=${seg.speaker}, text='${seg.text}'")
        }

        // 创建渲染器并生成豆包请求
        val renderer = DoubaoTtsRenderer(
            DoubaoVoiceConfig(
                narratorVoiceType = "BV700_V2_streaming",
                dialogVoiceType = "BV001_V2_streaming"
            )
        )

        println("\n=== 豆包 TTS 请求 ===")
        segments.forEach { segment ->
            val request = renderer.render(segment)
            println("请求: voiceType='${request.voiceType}', text='${request.text}'")

            // 生成 payload
            val payload = DoubaoTtsClient.buildPayloadForConfig(config, request)
            println("Payload 生成成功，包含: ${payload.keys}")
        }

        // 尝试真实调用
        println("\n=== 尝试真实 API 调用 ===")
        runBlocking {
            try {
                val client = DoubaoTtsClient(config)
                val request = segments.first().let { renderer.render(it) }
                println("发送请求: text='${request.text}'")

                val audio = client.synthesize(request)
                println("✅ API 调用成功！")
                println("音频大小: ${audio.bytes.size} bytes")
                println("音频格式: ${audio.contentType}")
            } catch (e: Exception) {
                println("❌ API 调用失败: ${e.message}")
                e.printStackTrace()
                throw e
            }
        }
    }
}
