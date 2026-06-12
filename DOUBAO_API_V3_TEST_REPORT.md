# MVP3.1 豆包 TTS V3 流式接口实现与测试

## 实现概览

### DoubaoTtsClientV3 实现 ✅
- **文件**: `app/src/main/java/io/legado/app/model/tts/DoubaoTtsClientV3.kt`
- **编译状态**: BUILD SUCCESSFUL
- **特性**:
  - V3 HTTP 单向合成接口支持
  - `req_params` 请求结构支持
  - `X-Api-App-Key` / `X-Api-Access-Key` / `X-Api-Resource-Id` 鉴权头支持
  - 响应 `data` base64 音频解码
  - 优雅的错误处理

### API 配置更新 ✅
- **文件**: `DoubaoApiConfig.kt`
- **新增字段**: `endpointV3` (默认: `https://openspeech.bytedance.com/api/v3/tts/unidirectional`)
- **新增字段**: `resourceIdV3` (默认: `seed-tts-2.0`)
- **向后兼容**: V1 endpoint 保留

## V3 与 V1 对比

| 特性 | V1 | V3 |
|------|----|----|
| 接口类型 | 同步 | V3 单向合成 |
| 响应方式 | 完整返回 | 完整返回 base64 音频 |
| 延迟 | 较高 | 低 |
| 编码 | MP3 base64 | MP3 base64 |
| 请求结构 | `app/audio/request` | `user/req_params` |
| 实现状态 | ✅ 完成 | ✅ 完成 |

## V3 API 测试结果

### 测试环境
```
Endpoint: https://openspeech.bytedance.com/api/v3/tts/unidirectional
App ID: 3375438334
Access Token: PFx-n_ffssXh0bE-...
Resource ID: seed-tts-2.0
```

### 测试结果
```
HTTP Status: 200 OK
Content-Type: text/plain; charset=utf-8
Response Code: 0
Audio: MP3, 24 kHz, mono, 64 kbps
```

### 修复记录
1. ✅ 修正 V3 endpoint：`/api/v3/tts` -> `/api/v3/tts/unidirectional`
2. ✅ 修正 V3 鉴权：`Authorization: Bearer; ...` -> `X-Api-App-Key` / `X-Api-Access-Key`
3. ✅ 增加 `X-Api-Resource-Id: seed-tts-2.0`
4. ✅ 修正 V3 payload：V1 的 `app/audio/request` -> V3 的 `user/req_params`

## V3 核心实现代码

```kotlin
// V3 响应处理
suspend fun synthesizeStream(request: DoubaoTtsRequest): DoubaoTtsAudio {
    // ... 发送请求 ...
    val result = GSON.fromJson(bodyText, DoubaoV3Response::class.java)
    return DoubaoTtsAudio(
        bytes = Base64.getDecoder().decode(result.data),
        ...
    )
}

// V3 特定配置
fun buildPayloadV3(...): Map<String, Any> {
    return mapOf(
        "user" to mapOf("uid" to config.uid),
        "req_params" to mapOf(
            "text" to request.text,
            "speaker" to request.voiceType,
            "audio_params" to mapOf(...)
        )
    )
}
```

## 技术亮点

1. **V3 官方协议适配**
   - 使用 `/api/v3/tts/unidirectional`
   - 使用 V3 专用 headers 鉴权
   - 使用 `req_params` 请求结构

2. **错误恢复**
   - 忽略解析失败的行
   - 检查空音频响应
   - 清晰的错误消息

3. **向后兼容**
   - V1 和 V3 共存
   - 配置完全独立
   - 可灵活切换

## 建议

### 后续优化
1. 将 V3 客户端接入主朗读播放链路。
2. 为 V3 单独配置默认 speaker，避免继续使用 V1 的 `BV*_V2_streaming` 音色。
3. 如需真正边下边播，可进一步接入 V3 SSE 或 WebSocket 形态。

## 总结

| 阶段 | V1 | V3 |
|------|----|----|
| 代码实现 | ✅ 完成 | ✅ 完成 |
| 编译验证 | ✅ 成功 | ✅ 成功 |
| Payload 生成 | ✅ 正常 | ✅ 正常 |
| API 调用 | ⚠️ 权限问题 | ✅ 成功 |

**MVP3.1 现已修复豆包 TTS V3 官方接口接入问题。V3 官方 endpoint 已使用 `TTS config.md` 中的凭证真实调通，并成功生成 MP3 音频。**
