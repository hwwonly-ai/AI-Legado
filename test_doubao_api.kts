#!/usr/bin/env kotlin

import io.legado.app.model.ReadAloudRuleSegmenter
import io.legado.app.model.tts.*
import java.io.File

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
val testText = "雨停了。\"我们走吧。\"他低声说。"

// 生成分段
val segments = ReadAloudRuleSegmenter.segment(
    text = testText,
    startPosition = 0,
    renderAliyunSsml = false
)

println("=== 生成的分段 ===")
segments.forEachIndexed { i, seg ->
    println("段 $i: speaker=${seg.speaker}, text=${seg.text}")
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
    println("请求: voiceType=${request.voiceType}, text=${request.text}")

    // 生成 payload
    val payload = DoubaoTtsClient.buildPayloadForConfig(config, request)
    println("Payload 结构: ${payload.keys}")
}

println("\n✅ API 集成测试完成")
