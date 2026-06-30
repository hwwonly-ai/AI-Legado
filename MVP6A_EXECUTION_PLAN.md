# MVP 6A AI 服务配置执行方案

## 1. 目标

完成 OpenAI-compatible AI 服务配置和连接测试，为后续 MVP6B Prompt 管理、MVP6C AI 单章解析提供基础配置能力。

MVP6A 只验证“应用能保存 AI 服务配置，并能用配置发起一次最小连接测试”。不做 Prompt 编辑，不做章节解析，不接入朗读或 Segment 生成链路。

## 2. 范围

### 包含

- AI 服务配置入口。
- 保存 `endpoint`、`apiKey`、`model` 和基础可选参数。
- OpenAI-compatible `/chat/completions` 连接测试。
- 配置重启后保留。
- 缺失字段、网络错误、鉴权错误和响应解析错误提示。
- API Key 脱敏显示和脱敏日志。
- 中英文资源字符串。

### 不包含

- 不做 Prompt 管理，留到 MVP6B。
- 不解析章节，留到 MVP6C。
- 不新增 AI 剧本缓存，留到 MVP6D。
- 不修改 Segment 缓存表。
- 不新增 Room Entity、DAO 或 migration。
- 不接入角色识别、角色档案或音色复用。
- 不自动调用 AI。

## 3. 配置字段

建议新增内存/偏好配置模型：

```kotlin
data class AiServiceConfig(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Float = 0.2f,
    val timeoutSeconds: Int = 60,
    val maxTokens: Int? = null
)
```

字段说明：

- `enabled`：是否启用 AI 功能。
- `endpoint`：OpenAI-compatible base URL，例如 `https://api.openai.com/v1`。
- `apiKey`：密钥，UI 使用密码输入，日志必须脱敏。
- `model`：模型名。
- `temperature`：连接测试默认低随机性。
- `timeoutSeconds`：连接测试超时。
- `maxTokens`：可选，MVP6A 可不暴露或作为高级项。

MVP6A 暂不引入 `extraHeaders`，避免配置和安全边界变复杂；如后续需要兼容特殊代理，再单独加入高级配置。

## 4. 存储策略

优先使用现有偏好存储体系，不新增数据库表。

建议 key：

```kotlin
aiServiceEnabled
aiServiceEndpoint
aiServiceApiKey
aiServiceModel
aiServiceTemperature
aiServiceTimeoutSeconds
aiServiceMaxTokens
```

要求：

- 保存后重启仍可读取。
- API Key 不出现在普通日志、Toast 或错误弹窗中。
- 清空 API Key 后保存应真正清空。
- endpoint 保存前去除首尾空格和末尾 `/`。

## 5. UI 入口

建议新增独立配置弹窗：

- `AiServiceConfigDialog`

入口建议放在朗读配置或 AI 分角色朗读配置区域，名称为：

- “AI 服务配置”

字段：

1. 启用 AI。
2. Endpoint。
3. API Key。
4. Model。
5. Temperature。
6. Timeout。
7. 连接测试按钮。

交互要求：

- 点击连接测试时禁用按钮并显示加载状态。
- 测试成功显示成功摘要。
- 测试失败显示明确原因。
- 未保存的输入也可用于连接测试，测试成功不自动覆盖用户输入，保存仍由用户确认。

## 6. OpenAI-compatible 客户端

新增轻量客户端，例如：

```kotlin
class OpenAiCompatibleClient(
    private val okHttpClient: OkHttpClient
) {
    suspend fun testConnection(config: AiServiceConfig): AiConnectionTestResult
}
```

请求：

```text
POST {endpoint}/chat/completions
Authorization: Bearer {apiKey}
Content-Type: application/json
```

测试 payload：

```json
{
  "model": "配置中的 model",
  "messages": [
    {
      "role": "user",
      "content": "ping"
    }
  ],
  "temperature": 0.2,
  "max_tokens": 16
}
```

成功条件：

- HTTP 2xx。
- JSON 可解析。
- 响应中存在 `choices`，或能解析出兼容文本内容。

失败类型：

- 配置缺失。
- endpoint 非法。
- 请求超时。
- 网络不可用。
- HTTP 401/403 鉴权失败。
- HTTP 404 endpoint 或模型路径错误。
- HTTP 429 限流。
- HTTP 5xx 服务端错误。
- 响应 JSON 结构不兼容。

## 7. 安全与日志

API Key 脱敏规则：

```text
sk-abc...xyz
```

日志只允许输出：

- endpoint host 或 endpoint hash。
- model。
- HTTP code。
- error type。
- elapsedMs。

禁止输出：

- 完整 API Key。
- Authorization Header。
- 完整请求体中的密钥。
- 后续章节正文或 Prompt。

建议日志前缀：

```text
aiConfig testStart endpointHash=xxxx model=gpt-4.1
aiConfig testSuccess model=gpt-4.1 elapsedMs=520
aiConfig testFailed type=Unauthorized code=401 elapsedMs=420
```

## 8. 校验规则

连接测试前校验：

1. endpoint 非空。
2. endpoint 以 `http://` 或 `https://` 开头。
3. apiKey 非空。
4. model 非空。
5. temperature 在 `0.0..2.0`。
6. timeoutSeconds 在 `5..180`。
7. maxTokens 为空或大于 0。

endpoint 规范化：

- trim。
- 去掉末尾 `/`。
- 如果用户输入完整 `/chat/completions`，保存时可提示应填写 base URL，或自动去除该后缀。

## 9. 实施步骤

### 阶段 A：配置模型与存储

1. 新增 `AiServiceConfig`。
2. 新增配置读写 helper。
3. 新增偏好 key 常量。
4. 实现 endpoint 规范化和 API Key 脱敏工具。

完成标准：配置可保存、读取、清空，重启后仍存在。

### 阶段 B：连接测试客户端

1. 新增 `OpenAiCompatibleClient`。
2. 构建 `/chat/completions` 请求。
3. 解析成功响应。
4. 归类常见失败。
5. 日志脱敏。

完成标准：正确配置返回成功，错误配置返回明确失败原因。

### 阶段 C：配置 UI

1. 新增 AI 服务配置弹窗。
2. 接入字段编辑、保存和取消。
3. 接入连接测试按钮。
4. 增加 loading 状态和错误展示。
5. 补充中英文字符串。

完成标准：用户可完成配置和连接测试，不需要进入章节解析流程。

### 阶段 D：计划记录与静态检查

1. 更新 `MVP PLAN.md` 中 MVP6A 状态。
2. 记录实现位置和验收状态。
3. 执行静态检查。

## 10. 验收用例

1. endpoint、apiKey、model 为空时不能发起测试，并给出字段提示。
2. endpoint 不合法时提示 URL 错误。
3. 正确配置时连接测试成功。
4. API Key 错误时提示鉴权失败，不泄露 Key。
5. model 错误时提示模型或请求错误。
6. 网络不可用时提示网络失败。
7. 服务限流时提示 429。
8. 保存配置后退出重进仍可看到配置。
9. 清空 API Key 保存后不会继续使用旧 Key。
10. 测试按钮 loading 期间不可重复点击。
11. 日志不包含完整 API Key。
12. 不触发章节解析、Segment 更新或朗读重启。
13. 不修改数据库版本。

## 11. 静态验证清单

按项目规则不运行编译或 Gradle：

- `git diff --check` 通过。
- 不修改 `AppDatabase.version`。
- 不新增 Room Entity、DAO 或 migration。
- 不新增 Prompt 管理 UI。
- 不新增章节 AI 解析入口。
- API Key 日志脱敏。
- 连接测试错误可诊断。
- 中英文字符串齐全。

## 12. 风险控制

- **泄露 API Key**：统一脱敏工具，禁止直接打印配置对象。
- **范围膨胀**：MVP6A 不做 Prompt 和章节解析。
- **代理兼容不足**：先支持标准 OpenAI-compatible，特殊 header 后续独立扩展。
- **UI 入口混乱**：配置入口只负责 AI 服务连接，不承担解析任务。
- **错误不可诊断**：错误类型至少区分字段缺失、鉴权、限流、超时、服务端错误和响应格式错误。

## 13. 完成定义

- 用户可配置 OpenAI-compatible endpoint、apiKey 和 model。
- 配置可保存并持久化。
- 可发起连接测试。
- 成功和失败都有明确反馈。
- API Key 不泄露。
- 不影响现有朗读、Segment 缓存和预取逻辑。
- 不新增数据库迁移，静态检查通过。
