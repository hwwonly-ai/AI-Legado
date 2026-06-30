# MVP 6A Update：DeepSeek / GLM AI 参数配置执行方案

## 1. 目标

在 MVP6A 的 AI 服务配置基础上，补充 DeepSeek 与智谱 GLM 4.7 系列的 Provider 预设和高级参数配置，使后续 MVP6C 章节解析可以按“速度 / 成本 / 效果”选择合适的 AI 调用模式。

本更新仍属于 MVP6A 范围：只做配置、保存和连接测试，不做 Prompt 管理，不做章节解析。

## 2. 为什么需要更新

仅保存 `endpoint/apiKey/model` 不够，因为 DeepSeek 和 GLM 的思考参数不完全一致：

- DeepSeek 使用 `thinking.type` 控制思考开关，使用 `reasoning_effort` 控制强度。
- GLM 4.7 系列也使用 `thinking.type` 控制思考开关，但 `reasoning_effort` 主要是 GLM-5.2 及以上参数，GLM 4.7 不应强行传。
- 思考模式会影响速度、费用、输出长度和 JSON 稳定性。
- 后续 AI 分角色解析需要在快速解析、复杂章节解析和失败重试之间切换策略。

因此 MVP6A 应把 Provider 特性抽象出来，而不是把所有模型都当成完全相同的 OpenAI-compatible 接口。

## 3. Provider 预设

新增 Provider 选项：

```text
DeepSeek
GLM
Custom
```

### DeepSeek 预设

```text
endpoint: https://api.deepseek.com
defaultModel: deepseek-v4-flash
advancedModel: deepseek-v4-pro
supportsThinking: true
supportsReasoningEffort: true
reasoningEffortValues: high, max
```

兼容说明：

- `deepseek-chat` 与 `deepseek-reasoner` 是兼容旧名，不建议作为默认值。
- 默认使用 `deepseek-v4-flash`。
- 复杂章节或失败重试可切换 `deepseek-v4-pro`。

### GLM 预设

```text
endpoint: https://open.bigmodel.cn/api/paas/v4
defaultModel: glm-4.7-flashx
advancedModel: glm-4.7
supportsThinking: true
supportsReasoningEffort: false
```

兼容说明：

- UI 可显示“GLM-4.7 FlashX（轻量高速）”。
- 高效果模式可选择 `glm-4.7`。
- GLM 4.7 支持 `thinking.type`。
- `reasoning_effort` 不作为 GLM 4.7 的默认参数。

### Custom 预设

```text
endpoint: 用户填写
model: 用户填写
supportsThinking: 用户决定
supportsReasoningEffort: 用户决定
```

Custom 用于兼容代理、第三方聚合 API 或未来模型。

## 4. 配置模型更新

建议将 MVP6A 的配置模型扩展为：

```kotlin
data class AiServiceConfig(
    val enabled: Boolean = false,
    val provider: AiProvider = AiProvider.DeepSeek,
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String = "",
    val mode: AiCallMode = AiCallMode.Fast,
    val thinkingEnabled: Boolean = false,
    val reasoningEffort: String? = null,
    val temperature: Float = 0.2f,
    val topP: Float? = null,
    val maxTokens: Int = 4096,
    val stream: Boolean = false,
    val timeoutSeconds: Int = 60,
    val providerExtraJson: String = ""
)
```

枚举：

```kotlin
enum class AiProvider {
    DeepSeek,
    GLM,
    Custom
}

enum class AiCallMode {
    Fast,
    Balanced,
    Refine
}
```

字段说明：

- `provider`：用于决定默认 endpoint、模型和参数兼容性。
- `mode`：速度/成本/效果策略。
- `thinkingEnabled`：是否开启思考模式。
- `reasoningEffort`：仅在 Provider 支持时传入。
- `providerExtraJson`：保留高级扩展，不在默认 UI 强调。

## 5. 调用模式

### 快速模式 Fast

用途：

- 普通章节。
- 旁白/对白基础识别。
- 成本敏感场景。
- 默认模式。

参数：

```json
{
  "thinking": { "type": "disabled" },
  "temperature": 0.2,
  "max_tokens": 4096,
  "stream": false
}
```

DeepSeek：

```text
model: deepseek-v4-flash
reasoning_effort: 不传
```

GLM：

```text
model: glm-4.7-flashx
reasoning_effort: 不传
```

### 平衡模式 Balanced

用途：

- 对白较多。
- 需要识别具体说话人。
- 人物关系轻度复杂。
- Fast 结果不稳定时的第一次重试。

参数：

```json
{
  "thinking": { "type": "enabled" },
  "temperature": 0.2,
  "max_tokens": 8192,
  "stream": false
}
```

DeepSeek：

```text
model: deepseek-v4-flash
reasoning_effort: high
```

GLM：

```text
model: glm-4.7-flashx
reasoning_effort: 不传
```

### 精修模式 Refine

用途：

- 角色归属置信度低。
- JSON 解析失败后重试。
- 跨章节角色复用失败。
- 长章节、多人对话、代词密集。

参数：

```json
{
  "thinking": { "type": "enabled" },
  "temperature": 0.1,
  "max_tokens": 12000,
  "stream": false
}
```

DeepSeek：

```text
model: deepseek-v4-pro 或 deepseek-v4-flash
reasoning_effort: max
```

GLM：

```text
model: glm-4.7
reasoning_effort: 不传
```

## 6. 请求参数生成规则

### 通用规则

所有 Provider 都传：

```json
{
  "model": "...",
  "messages": [],
  "stream": false,
  "max_tokens": 4096
}
```

当 `thinkingEnabled = true`：

```json
"thinking": { "type": "enabled" }
```

当 `thinkingEnabled = false`：

```json
"thinking": { "type": "disabled" }
```

当 Provider 支持 `reasoning_effort` 且值不为空：

```json
"reasoning_effort": "high"
```

### DeepSeek 特殊规则

- 思考模式下不要依赖 `temperature/top_p` 控制输出；DeepSeek 文档说明这些参数在思考模式下不生效。
- `reasoning_effort` 只允许 UI 给出 `high/max`。
- `low/medium` 不作为 DeepSeek UI 选项，因为官方会映射到 `high`，容易误导用户。
- `xhigh` 不作为 DeepSeek UI 选项，因为会映射到 `max`。

### GLM 特殊规则

- GLM 4.7 默认支持 Thinking，UI 应明确显示当前是否关闭。
- GLM 4.7 不传 `reasoning_effort`。
- 如果未来用户选择 GLM-5.2 及以上模型，可再允许 `reasoning_effort`。

## 7. UI 更新

AI 服务配置 UI 增加：

1. Provider 下拉：
   - DeepSeek
   - GLM
   - Custom
2. 模式选择：
   - 快速
   - 平衡
   - 精修
3. 思考模式开关。
4. 推理强度：
   - DeepSeek：`high/max`
   - GLM 4.7：隐藏或禁用。
   - Custom：允许手动填写。
5. 模型选择：
   - DeepSeek：`deepseek-v4-flash` / `deepseek-v4-pro`
   - GLM：`glm-4.7-flashx` / `glm-4.7`
   - Custom：文本输入。
6. 高级参数：
   - temperature
   - top_p
   - max_tokens
   - timeout
   - providerExtraJson
7. 恢复推荐参数按钮。

交互规则：

- 切换 Provider 时，若用户未手动改过 endpoint/model，则自动填入预设。
- 切换模式时，自动更新 thinking、reasoningEffort、temperature、maxTokens。
- 用户手动修改高级参数后，不再被模式自动覆盖，除非点击“恢复推荐参数”。
- 连接测试使用当前表单值，不要求先保存。

## 8. 推荐高级参数固化

MVP6A Update 将小说文本处理场景的高级参数固化为推荐参数。用户可以手动修改，但点击“恢复推荐参数”后，按当前 Provider + 模式恢复默认值。

### 通用推荐

```text
temperature: 0.2
topP: null
maxTokens: 8192
timeoutSeconds: 60
providerExtraJson: ""
```

原则：

- 小说分角色解析追求稳定和一致性，不追求创意发散。
- `temperature` 默认低值。
- `topP` 默认不传，避免和 temperature 叠加影响稳定性。
- `maxTokens` 按模式控制，不无限增大。
- `timeoutSeconds` 按模式控制，避免 UI 长时间无响应。
- `providerExtraJson` 默认留空，仅用于高级调试或特殊 Provider。

### Fast 推荐参数

适用：

- 普通章节。
- 旁白 / 对白基础识别。
- 成本和速度优先。

```text
thinkingEnabled: false
reasoningEffort: null
temperature: 0.2
topP: null
maxTokens: 4096
timeoutSeconds: 45
providerExtraJson: ""
```

### Balanced 推荐参数

适用：

- 默认推荐模式。
- 具体角色识别。
- 对白较多但不极端复杂的章节。

```text
thinkingEnabled: true
reasoningEffort: DeepSeek=high, GLM=null
temperature: 0.2
topP: null
maxTokens: 8192
timeoutSeconds: 60
providerExtraJson: ""
```

### Refine 推荐参数

适用：

- 多人对话。
- JSON 解析失败重试。
- 角色归属置信度低。
- 跨章节角色复用失败。
- 长章节或代词密集文本。

```text
thinkingEnabled: true
reasoningEffort: DeepSeek=max, GLM=null
temperature: 0.1
topP: null
maxTokens: 12000
timeoutSeconds: 90
providerExtraJson: ""
```

### Provider 差异

DeepSeek：

```text
Fast: thinking disabled, reasoningEffort null
Balanced: thinking enabled, reasoningEffort high
Refine: thinking enabled, reasoningEffort max
```

GLM 4.7：

```text
Fast: thinking disabled, reasoningEffort null
Balanced: thinking enabled, reasoningEffort null
Refine: thinking enabled, reasoningEffort null
```

Custom：

```text
Fast: thinking disabled, reasoningEffort null
Balanced: thinking enabled, reasoningEffort high only if user enabled supportsReasoningEffort
Refine: thinking enabled, reasoningEffort max only if user enabled supportsReasoningEffort
```

### 恢复推荐参数

新增按钮：

```text
恢复推荐参数
```

点击后根据当前 `provider + mode` 覆盖以下字段：

```text
thinkingEnabled
reasoningEffort
temperature
topP
maxTokens
timeoutSeconds
providerExtraJson
```

不覆盖：

```text
enabled
provider
endpoint
apiKey
model
stream
```

原因：

- endpoint、apiKey、model 属于用户真实服务配置，不应被高级参数恢复操作误改。
- stream 在 MVP6A 默认不启用，后续是否开放独立控制。
- “恢复推荐参数”只处理效果、速度和稳定性相关参数。

### 手动修改标记

建议为高级参数维护一个内存态标记：

```kotlin
var advancedParamsDirty: Boolean
```

规则：

- 用户手动修改 temperature/topP/maxTokens/timeout/providerExtraJson/thinking/reasoningEffort 后，标记为 dirty。
- 切换模式时，如果 dirty=false，则自动应用该模式推荐参数。
- 切换模式时，如果 dirty=true，则提示“是否应用当前模式推荐参数”。
- 点击“恢复推荐参数”后，应用推荐参数并将 dirty=false。

MVP6A 可先不持久化 dirty 状态，只在当前弹窗生命周期内生效。

## 9. 连接测试更新

连接测试 payload 根据 Provider 生成：

### DeepSeek Fast

```json
{
  "model": "deepseek-v4-flash",
  "messages": [
    { "role": "user", "content": "ping" }
  ],
  "thinking": { "type": "disabled" },
  "max_tokens": 16,
  "stream": false
}
```

### DeepSeek Balanced

```json
{
  "model": "deepseek-v4-flash",
  "messages": [
    { "role": "user", "content": "ping" }
  ],
  "thinking": { "type": "enabled" },
  "reasoning_effort": "high",
  "max_tokens": 16,
  "stream": false
}
```

### GLM Fast

```json
{
  "model": "glm-4.7-flashx",
  "messages": [
    { "role": "user", "content": "ping" }
  ],
  "thinking": { "type": "disabled" },
  "max_tokens": 16,
  "stream": false
}
```

### GLM Balanced

```json
{
  "model": "glm-4.7-flashx",
  "messages": [
    { "role": "user", "content": "ping" }
  ],
  "thinking": { "type": "enabled" },
  "max_tokens": 16,
  "stream": false
}
```

连接测试只验证鉴权、endpoint、model 和基础参数兼容性，不评估章节解析质量。

## 10. 推荐默认值

首次打开 AI 配置：

```text
provider: DeepSeek
endpoint: https://api.deepseek.com
model: deepseek-v4-flash
mode: Fast
thinkingEnabled: false
reasoningEffort: null
temperature: 0.2
maxTokens: 4096
stream: false
timeoutSeconds: 60
```

如果用户选择 GLM：

```text
endpoint: https://open.bigmodel.cn/api/paas/v4
model: glm-4.7-flashx
mode: Fast
thinkingEnabled: false
reasoningEffort: null
temperature: 0.2
maxTokens: 4096
stream: false
timeoutSeconds: 60
```

## 11. 后续 MVP6C 使用策略

MVP6A 只保存配置，但应为 MVP6C 预留策略：

```text
普通章节：Fast
对白密集：Balanced
JSON 失败重试：Refine
角色归属低置信度：Refine
跨章节角色复用失败：Refine
```

MVP6C 可按解析结果自动升级模式，但不应在 MVP6A 中实现自动解析。

## 12. 验收用例

1. 选择 DeepSeek 自动填入 `https://api.deepseek.com`。
2. DeepSeek Fast 默认关闭 thinking。
3. DeepSeek Balanced 开启 thinking，并传 `reasoning_effort=high`。
4. DeepSeek Refine 可使用 `reasoning_effort=max`。
5. 选择 GLM 自动填入 `https://open.bigmodel.cn/api/paas/v4`。
6. GLM Fast 默认关闭 thinking。
7. GLM Balanced 开启 thinking，但不传 `reasoning_effort`。
8. 切换模式会更新推荐参数。
9. 手动改过参数后不会被静默覆盖。
10. 连接测试能按 Provider 生成正确请求体。
11. API Key 不进入日志。
12. 不新增 Prompt 管理。
13. 不触发章节解析。
14. 不修改数据库版本。
15. 点击“恢复推荐参数”只恢复高级参数，不覆盖 endpoint、apiKey、model。
16. DeepSeek Balanced 恢复为 `reasoning_effort=high`。
17. DeepSeek Refine 恢复为 `reasoning_effort=max`。
18. GLM 4.7 恢复推荐参数时不写入 `reasoning_effort`。
19. Fast 恢复后关闭 thinking。
20. Refine 恢复后 temperature 为 `0.1`，timeout 为 `90`。

## 13. 静态验证清单

按项目规则不运行编译或 Gradle：

- `git diff --check` 通过。
- 不修改 `AppDatabase.version`。
- 不新增 Room Entity、DAO 或 migration。
- Provider 预设覆盖 DeepSeek、GLM、Custom。
- DeepSeek 支持 `thinking.type` 和 `reasoning_effort`。
- GLM 4.7 支持 `thinking.type`，默认不传 `reasoning_effort`。
- Fast/Balanced/Refine 三档参数清晰。
- API Key 全链路脱敏。
- 连接测试不依赖 Prompt 管理。
- 不接入章节解析。
- 恢复推荐参数不覆盖 endpoint、apiKey、model。
- Fast/Balanced/Refine 的高级参数推荐值固定。

## 14. 完成定义

- MVP6A 配置可选择 DeepSeek / GLM / Custom。
- 不同 Provider 有合理默认 endpoint、model 和参数。
- 用户可配置思考模式和推理强度。
- DeepSeek 和 GLM 的参数差异被正确处理。
- 连接测试使用当前 Provider 参数。
- 后续 MVP6C 可直接复用 Fast/Balanced/Refine 策略。
- 点击“恢复推荐参数”可按当前 Provider + 模式恢复高级参数默认值。
