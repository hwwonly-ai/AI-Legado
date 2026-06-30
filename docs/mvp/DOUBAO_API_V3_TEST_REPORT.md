# 豆包 TTS V3 接口测试报告

## 实现概览

### DoubaoTtsClientV3 实现 ✅
- **文件**: `app/src/main/java/io/legado/app/model/tts/DoubaoTtsClientV3.kt`
- **接口**: `https://openspeech.bytedance.com/api/v3/tts/unidirectional`
- **Resource ID**: `seed-tts-2.0`
- **特性**:
  - V3 HTTP 单向合成接口支持
  - `req_params` 请求结构支持
  - `X-Api-App-Key` / `X-Api-Access-Key` / `X-Api-Resource-Id` 鉴权头支持
  - 响应 `data` base64 音频解码

### API 配置 ✅
- **文件**: `DoubaoApiConfig.kt`
- **字段**:
  - `endpointV3`
  - `resourceIdV3`
  - `encoding`
  - `rate`
  - `speedRatio`
  - `volumeRatio`
  - `pitchRatio`

## API 测试结果

### 测试环境
```
Endpoint: https://openspeech.bytedance.com/api/v3/tts/unidirectional
App ID: your_app_id
Access Token: your_access_token
Resource ID: seed-tts-2.0
```

### 测试结果
```
HTTP Status: 200 OK
Content-Type: text/plain; charset=utf-8
Response Code: 0
Audio: MP3, 24 kHz, mono, 64 kbps
```

## 请求结构

```kotlin
mapOf(
    "user" to mapOf("uid" to config.uid),
    "req_params" to mapOf(
        "text" to request.text,
        "speaker" to request.voiceType,
        "audio_params" to mapOf(
            "format" to config.formatV3(),
            "sample_rate" to config.rate,
            "speech_rate" to ...,
            "enable_timestamp" to false
        )
    )
)
```

## 当前结论

豆包 TTS 当前只保留 V3 官方接口接入。真实接口已使用 `TTS config.md` 中的凭证调通，并成功生成 MP3 音频。
