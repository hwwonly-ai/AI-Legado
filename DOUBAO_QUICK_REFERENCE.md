# 豆包 TTS V3 快速测试参考

## 🚀 快速开始

### 方式 1：使用测试脚本（推荐）

```bash
cd /Users/huiweiwei/Documents/Coding/legado/legado

# 查看帮助
./test_doubao_tts.sh -h

# 使用参数运行
./test_doubao_tts.sh your_app_id your_access_token

# 使用环境变量运行
export DOUBAO_TTS_APP_ID="your_app_id"
export DOUBAO_TTS_ACCESS_TOKEN="your_access_token"
export DOUBAO_TTS_SPEAKER="zh_female_vv_uranus_bigtts"
./test_doubao_tts.sh
```

### 方式 2：使用 Gradle 命令

#### 仅运行单元测试（无需凭证）
```bash
./gradlew :app:testAppDebugUnitTest
```

#### 运行集成测试（需要凭证）
```bash
export DOUBAO_TTS_APP_ID="your_app_id"
export DOUBAO_TTS_ACCESS_TOKEN="your_access_token"
export DOUBAO_TTS_SPEAKER="zh_female_vv_uranus_bigtts"

./gradlew :app:testAppDebugUnitTest
```

### 方式 3：使用 curl 直接调用 API

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -H "X-Api-App-Key: your_app_id" \
  -H "X-Api-Access-Key: your_access_token" \
  -H "X-Api-Resource-Id: seed-tts-2.0" \
  -H "X-Api-Request-Id: $(uuidgen)" \
  -d '{
    "user": {"uid": "legado_doubao_tts"},
    "req_params": {
      "text": "你好，这是豆包语音合成 V3 测试。",
      "speaker": "zh_female_vv_uranus_bigtts",
      "audio_params": {
        "format": "mp3",
        "sample_rate": 24000,
        "speech_rate": 0,
        "enable_timestamp": false
      },
      "additions": {
        "explicit_language": "zh",
        "disable_markdown_filter": true,
        "disable_emoji_filter": true
      }
    }
  }' \
  https://openspeech.bytedance.com/api/v3/tts/unidirectional
```

## 📋 测试覆盖

### 单元测试（无需凭证）

| 测试 | 说明 | 文件 |
|------|------|------|
| `renderKeepsDoubaoRequestFreeFromAliyunSsml` | 验证无 SSML 标签 | [DoubaoTtsClientTest.kt](app/src/test/java/io/legado/app/model/tts/DoubaoTtsClientTest.kt) |
| `buildPayloadV3AddsContextTextsAndTagParserIntoAdditions` | 验证 additions 字段 | 同上 |
| `buildPayloadV3UsesReqParamsShape` | 验证请求结构 | 同上 |
| `doubaoSettingsDefaultsToV3Speakers` | 验证默认配置 | 同上 |

### 集成测试（需要凭证）

| 测试 | 说明 | 条件 |
|------|------|------|
| `synthesizeV3WithRealCredentialsWhenProvided` | 真实 API 调用 | 需要有效凭证 |

## 🔍 关键配置

```kotlin
// Speaker ID（音色）
zh_female_vv_uranus_bigtts  // 默认女性音色

// Resource ID（资源）
seed-tts-2.0

// Endpoint（API 端点）
https://openspeech.bytedance.com/api/v3/tts/unidirectional

// Audio Format（音频格式）
mp3, ogg_opus, pcm, wav

// Sample Rate（采样率）
24000 Hz（推荐）

// Speech Rate（语速）
范围: -50 ~ 100，0 为正常速度
```

## 🐛 调试技巧

### 1. 查看请求详情

修改 [DoubaoTtsClientV3.kt](app/src/main/java/io/legado/app/model/tts/DoubaoTtsClientV3.kt)：

```kotlin
// 在发送请求前
val payload = buildPayloadV3(request, reqId)
Log.d("DoubaoTTS", "Request: ${GSON.toJson(payload)}")
```

### 2. 查看完整响应

```kotlin
val bodyText = response.body.string()
Log.d("DoubaoTTS", "Response: $bodyText")
```

### 3. 保存音频进行验证

```kotlin
val audio = synthesizeStream(request)
File("/sdcard/test.mp3").writeBytes(audio.bytes)
```

## ❌ 常见错误

| 错误 | 原因 | 解决 |
|------|------|------|
| `resource ID is mismatched with speaker related resource` | Speaker 与 Resource ID 不匹配 | 检查 speaker 是否被授权、additions 格式是否正确 |
| `Invalid Access Token` | Token 过期或错误 | 重新获取 Token |
| `400 Bad Request` | 请求格式错误 | 验证 JSON 格式，检查 additions 是否为对象 |
| `401 Unauthorized` | 认证失败 | 检查 App ID 和 Access Token 是否正确 |

## 📝 请求结构

```
POST /api/v3/tts/unidirectional HTTP/1.1
Host: openspeech.bytedance.com
Content-Type: application/json
X-Api-App-Key: {appId}
X-Api-Access-Key: {accessToken}
X-Api-Resource-Id: seed-tts-2.0
X-Api-Request-Id: {uuid}

{
  "user": {
    "uid": "legado_doubao_tts"
  },
  "req_params": {
    "text": "合成的文本",
    "speaker": "zh_female_vv_uranus_bigtts",
    "audio_params": {
      "format": "mp3",
      "sample_rate": 24000,
      "speech_rate": 0,
      "enable_timestamp": false
    },
    "additions": {
      "explicit_language": "zh",
      "disable_markdown_filter": true,
      "disable_emoji_filter": true,
      "context_texts": ["前一句话"],
      "use_tag_parser": false
    }
  }
}
```

## 📚 相关文件

- 完整指南：[DOUBAO_TTS_V3_TESTING_GUIDE.md](DOUBAO_TTS_V3_TESTING_GUIDE.md)
- 客户端实现：[DoubaoTtsClientV3.kt](app/src/main/java/io/legado/app/model/tts/DoubaoTtsClientV3.kt)
- 单元测试：[DoubaoTtsClientTest.kt](app/src/test/java/io/legado/app/model/tts/DoubaoTtsClientTest.kt)
- API 配置：[DoubaoApiConfig.kt](app/src/main/java/io/legado/app/model/tts/DoubaoApiConfig.kt)
- 测试脚本：[test_doubao_tts.sh](test_doubao_tts.sh)

## ✅ 检查清单

在进行完整测试前，确保：

- [ ] 已获得有效的 App ID 和 Access Token
- [ ] Speaker ID 已在豆包账户中激活
- [ ] 了解 Resource ID 的含义（通常是 `seed-tts-2.0`）
- [ ] 请求体中的 `additions` 是对象，不是 JSON 字符串
- [ ] 已配置正确的环境变量或系统属性
- [ ] Gradle 构建工具已正确安装

## 🔗 相关资源

- [豆包 TTS API 文档](https://www.volcengine.com/docs/82379/1246096)
- [豆包开放平台](https://console.volcengine.com/)
