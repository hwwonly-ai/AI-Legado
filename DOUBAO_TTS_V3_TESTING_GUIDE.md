# 豆包 TTS V3 接口测试指南

## 概述

豆包 TTS V3 接口的测试分为两种：

1. **单元测试**：验证请求结构、payload 构建等逻辑（不需要真实凭证）
2. **集成测试**：使用真实凭证调用豆包 API（需要有效的 App ID 和 Access Token）

## 单元测试（无需凭证）

### 运行所有单元测试

```bash
./gradlew :app:testAppDebugUnitTest
```

### 单元测试覆盖范围

测试文件：[DoubaoTtsClientTest.kt](app/src/test/java/io/legado/app/model/tts/DoubaoTtsClientTest.kt)

#### 1. **renderKeepsDoubaoRequestFreeFromAliyunSsml**
- 验证渲染过程不包含阿里云 SSML 标签
- 确保对话和旁白使用正确的 speaker

```kotlin
@Test
fun renderKeepsDoubaoRequestFreeFromAliyunSsml() {
    val text = "雨停了。"我们走吧。"他低声说。"
    val requests = DoubaoNovelTtsPlanner(
        DoubaoTtsRenderer(...)
    ).buildRequests(text, 100)
    
    // 验证请求中没有 SSML 标签
    assertFalse(dialogRequest.text.contains("<speak>"))
}
```

#### 2. **buildPayloadV3AddsContextTextsAndTagParserIntoAdditions**
- 验证 `additions` 字段正确包含上下文和标签解析器配置
- 测试带有情绪标签和指令的请求

```kotlin
@Test
fun buildPayloadV3AddsContextTextsAndTagParserIntoAdditions() {
    val payload = testClientV3().buildPayloadV3(request, "fixed-req-id")
    val additions = reqParams.mapValue<Map<String, Any>>("additions")
    
    // 验证 additions 包含正确的字段
    assertTrue(additions["context_texts"].toString().contains("旁白："))
    assertEquals(true, additions["use_tag_parser"])
}
```

#### 3. **buildPayloadV3UsesReqParamsShape**
- 验证请求体结构符合豆包 API V3 规范
- 检查必需字段和默认值

```kotlin
@Test
fun buildPayloadV3UsesReqParamsShape() {
    val payload = testClientV3().buildPayloadV3(request, "fixed-req-id")
    
    // 验证请求结构
    assertEquals("你好，这是 V3 测试。", reqParams["text"])
    assertEquals(DOUBAO_DEFAULT_NARRATOR_SPEAKER, reqParams["speaker"])
    assertEquals("mp3", audioParams["format"])
}
```

#### 4. **doubaoSettingsDefaultsToV3Speakers**
- 验证默认 speaker 配置正确

#### 5. **synthesizeV3WithRealCredentialsWhenProvided**
- 集成测试：使用真实凭证调用豆包 API
- 仅在提供凭证时运行（使用 `assumeTrue`）

## 集成测试（需要真实凭证）

### 前置条件

你需要有：
1. 豆包账户（字节跳动）
2. App ID
3. Access Token
4. 已授权的 Speaker ID（如 `zh_female_vv_uranus_bigtts`）

### 方法 1：使用环境变量

```bash
export DOUBAO_TTS_APP_ID="your_app_id"
export DOUBAO_TTS_ACCESS_TOKEN="your_access_token"
export DOUBAO_TTS_SPEAKER="zh_female_vv_uranus_bigtts"  # 可选，不设置则使用默认值

./gradlew :app:testAppDebugUnitTest
```

### 方法 2：使用系统属性

```bash
./gradlew :app:testAppDebugUnitTest \
  -Ddoubao.tts.appId="your_app_id" \
  -Ddoubao.tts.accessToken="your_access_token" \
  -Ddoubao.tts.speaker="zh_female_vv_uranus_bigtts"
```

### 方法 3：在 gradle.properties 中配置

编辑 `gradle.properties`：

```properties
doubao.tts.appId=your_app_id
doubao.tts.accessToken=your_access_token
doubao.tts.speaker=zh_female_vv_uranus_bigtts
```

然后运行：

```bash
./gradlew :app:testAppDebugUnitTest
```

## 测试代码示例

### 如何读取凭证

```kotlin
private fun secret(envName: String, propertyName: String): String {
    return System.getenv(envName)
        ?: System.getProperty(propertyName)
        ?: ""
}

// 使用方式
val appId = secret("DOUBAO_TTS_APP_ID", "doubao.tts.appId")
val accessToken = secret("DOUBAO_TTS_ACCESS_TOKEN", "doubao.tts.accessToken")
```

### 测试流程

```kotlin
@Test
fun synthesizeV3WithRealCredentialsWhenProvided() {
    // 1. 读取凭证
    val appId = secret("DOUBAO_TTS_APP_ID", "doubao.tts.appId")
    val accessToken = secret("DOUBAO_TTS_ACCESS_TOKEN", "doubao.tts.accessToken")
    
    // 2. 条件跳过：如果没有凭证，则跳过此测试
    assumeTrue(
        "Set DOUBAO_TTS_APP_ID and DOUBAO_TTS_ACCESS_TOKEN to run real Doubao TTS V3 smoke test.",
        appId.isNotBlank() && accessToken.isNotBlank()
    )
    
    // 3. 构建请求
    val request = DoubaoTtsRenderer(
        DoubaoVoiceConfig(narratorVoiceType = speaker)
    ).render(
        ReadAloudRuleSegmenter.segment(
            text = "你好，这是豆包语音合成 V3 测试。",
            startPosition = 0,
            renderAliyunSsml = false
        ).first()
    )
    
    // 4. 调用 API
    val audio = kotlinx.coroutines.runBlocking {
        DoubaoTtsClientV3(
            DoubaoApiConfig(
                appId = appId,
                accessToken = accessToken
            ),
            client = OkHttpClient()
        ).synthesizeStream(request)
    }
    
    // 5. 验证结果
    assertTrue(audio.bytes.isNotEmpty())
    assertEquals("audio/mpeg", audio.contentType)
}
```

## 请求结构

### 完整的请求体格式

```json
{
    "user": {
        "uid": "legado_doubao_tts"
    },
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
            "disable_emoji_filter": true,
            "context_texts": ["旁白：前一句话"],
            "use_tag_parser": false
        }
    }
}
```

### 请求头

```
Content-Type: application/json
X-Api-App-Key: {appId}
X-Api-Access-Key: {accessToken}
X-Api-Resource-Id: seed-tts-2.0
X-Api-Request-Id: {uuid}
```

## API 端点

- **URL**: `https://openspeech.bytedance.com/api/v3/tts/unidirectional`
- **方法**: POST
- **响应格式**: 
  ```json
  {
      "code": 0,
      "message": "success",
      "data": "base64_encoded_audio"
  }
  ```

## 常见错误处理

### 错误：resource ID is mismatched with speaker related resource

**原因**：
- Speaker 与 Resource ID 不匹配
- Speaker 在账户中未被授权
- `additions` 字段格式错误

**解决**：
- 确认账户已激活该 speaker
- 检查 Resource ID 配置
- 验证 `additions` 是否为对象格式（不是 JSON 字符串）

### 错误：Invalid Access Token

**原因**：
- Access Token 已过期
- Access Token 格式错误
- Access Token 没有被正确传递

**解决**：
- 从豆包控制台重新获取 Access Token
- 检查环境变量或系统属性是否正确设置

## 调试技巧

### 查看请求日志

修改 `DoubaoTtsClientV3.kt`，在发送请求前打印 payload：

```kotlin
val payload = buildPayloadV3(request, reqId)
val body = GSON.toJson(payload).toRequestBody(JSON_MEDIA_TYPE)
Log.d("DoubaoTTS", "Request payload: ${GSON.toJson(payload)}")
```

### 查看响应日志

```kotlin
val bodyText = response.body.string()
Log.d("DoubaoTTS", "Response: $bodyText")
```

### 保存音频文件用于验证

```kotlin
val audio = synthesizeStream(request)
val file = File("/sdcard/test_audio.mp3")
file.writeBytes(audio.bytes)
```

## 最佳实践

1. **分离测试环境**：
   - 本地开发：使用 mock 测试
   - CI/CD：使用真实凭证进行集成测试
   - 生产：不要在代码中硬编码凭证

2. **错误处理**：
   - 实现重试逻辑
   - 记录详细的错误信息
   - 提供用户友好的错误提示

3. **性能优化**：
   - 缓存合成结果
   - 并发限制
   - 超时设置

4. **安全**：
   - 不要在日志中输出凭证
   - 使用环境变量管理凭证
   - 定期更新 Access Token
