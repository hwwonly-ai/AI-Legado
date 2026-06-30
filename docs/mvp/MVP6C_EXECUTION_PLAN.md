# MVP 6C AI 单章解析最小闭环执行方案

## 1. 目标

完成 AI 对当前章节的最小解析闭环：读取当前章节文本，使用 MVP6A 的 AI 服务配置和 MVP6B 的 Prompt，调用 AI 返回旁白 / 对白 / 具体角色 Segment，并将结果映射回原文位置。

MVP6C 的核心目标是证明 AI 解析链路可用，但不做缓存持久化。AI 解析失败、JSON 不合法或映射失败时，必须回退 MVP3 规则分段，保证朗读链路可继续。

## 2. 范围

### 包含

- 从当前章节正文构造 AI 解析请求。
- 读取 MVP6A 当前 AI 服务配置。
- 读取 MVP6B 当前 Prompt。
- 调用 OpenAI-compatible Chat Completions。
- 解析 AI 返回 JSON。
- 转换为 `ReadAloudSegment` 列表。
- 将 AI 输出文本映射回原文 `startPosition/plainLength`。
- 只处理旁白和对白文本事件。
- 识别具体角色名。
- 情绪和强度按现有 Segment 字段落入。
- 解析失败时回退规则分段。
- 增加 AI 解析日志与错误原因。
- 更新 `MVP PLAN.md` 的 MVP6C 实现记录和状态。

### 不包含

- 不写入 Segment 缓存，留到 MVP6D。
- 不新增 Room 表。
- 不修改 `AppDatabase.version`。
- 不做角色匹配和音色复用，留到 MVP7。
- 不做用户当前段修正；该能力不纳入当前 V1 MVP 路线。
- 不导入导出剧本，留到 MVP9。
- 不处理 BGM、音效、图片、环境音。
- 不修改 HTTP_TTS Provider 渲染逻辑。

## 3. 输入与输出

### 输入

- 当前书籍。
- 当前章节 `TextChapter`。
- 章节纯文本内容。
- AI 服务配置 `AiServiceConfig`。
- Prompt 配置 `AiPromptConfig`。

### 输出

```kotlin
data class AiChapterParseResult(
    val segments: List<ReadAloudSegment>,
    val provider: String,
    val model: String,
    val promptHash: String,
    val promptVersion: Int,
    val elapsedMs: Long,
    val fallbackUsed: Boolean,
    val errorMessage: String? = null
)
```

MVP6C 不落库，该结果只用于当前播放流程。

## 4. AI 输出 JSON 结构

AI 应返回：

```json
{
  "segments": [
    {
      "eventType": "text",
      "role": "narrator",
      "roleName": "旁白",
      "emotion": "neutral",
      "emotionIntensity": 1.0,
      "text": "原文片段"
    },
    {
      "eventType": "text",
      "role": "dialog",
      "roleName": "黑夫",
      "emotion": "happy",
      "emotionIntensity": 0.8,
      "text": "“原文对白”"
    }
  ]
}
```

字段规则：

- `eventType`：MVP6C 固定为 `text`。
- `role`：`narrator`、`dialog` 或 `unknown`。
- `roleName`：旁白使用 `旁白`，未知角色使用 `unknown`。
- `emotion`：缺失时回退 `neutral`。
- `emotionIntensity`：缺失时回退 `1.0`，限制在 `0.0..1.0`。
- `text`：必须来自原文，不允许改写。

## 5. 映射规则

AI 返回的 `text` 必须映射回章节原文。

映射流程：

1. 章节正文规范化换行。
2. 从上一个匹配结束位置开始顺序查找。
3. 精确查找 `text`。
4. 精确失败时尝试压缩空白查找。
5. 仍失败时尝试去除首尾空白查找。
6. 单段失败比例超过阈值则整体回退规则分段。

阈值建议：

```text
单段无法映射：允许跳过或回退，MVP6C 建议整体回退
整体映射成功率：>= 95%
```

为什么 MVP6C 建议整体回退：

- MVP6C 是最小闭环，稳定优先。
- 局部混合 AI + 规则 Segment 容易造成朗读进度错位。
- 局部容错可留到 MVP6D 或后续修正能力。

## 6. 调用策略

MVP6C 使用 MVP6A 的当前模式参数：

- Fast：普通章节默认。
- Balanced：用户配置为平衡时使用。
- Refine：用户配置为精修时使用。

MVP6C 不自动升级模式，避免范围膨胀。

后续 MVP 可扩展：

- JSON 失败自动 Refine 重试。
- 映射失败自动降低 chunk 大小。
- 角色置信度低自动重试。

## 7. 章节长度处理

MVP6C 最小闭环优先支持单章一次请求。

为避免超长章节导致请求失败，增加硬限制：

```text
AI_PARSE_MAX_CHARS = 12000
```

策略：

- 当前章节长度 <= 12000：调用 AI。
- 当前章节长度 > 12000：MVP6C 回退规则分段，并记录 `reason=chapterTooLong`。

不在 MVP6C 做章节切片，因为切片合并、位置映射和角色连续性会显著增加复杂度，留到后续优化。

## 8. 回退策略

以下情况必须回退 MVP3 规则分段：

- AI 服务未启用。
- endpoint/apiKey/model 缺失。
- Prompt 为空。
- 网络失败。
- HTTP 鉴权失败。
- HTTP 限流或服务端错误。
- 响应不是合法 JSON。
- JSON 中没有 `segments`。
- Segment 列表为空。
- AI 返回文本无法映射。
- 章节超过 MVP6C 最大长度。

回退要求：

- 不中断朗读。
- 不弹出频繁错误 Toast。
- 写入可诊断日志。
- 返回规则分段结果。

## 9. 代码结构建议

新增：

```text
app/src/main/java/io/legado/app/model/ai/AiChapterParser.kt
app/src/main/java/io/legado/app/model/ai/AiChapterParseResult.kt
app/src/main/java/io/legado/app/model/ai/AiSegmentResponse.kt
app/src/main/java/io/legado/app/model/ai/AiSegmentMapper.kt
```

可能复用：

```text
AiServiceConfig
AiPromptStore
OpenAiCompatibleClient
ReadAloudChapterSegmenter
ReadAloudRuleSegmenter
ReadAloudSegment
```

主要接入位置：

```text
ReadAloudSegmentCacheRepository 或 ReadAloudChapterSegmenter 上层调用处
HttpReadAloudService 的 buildRuleContentList 前置解析路径
```

推荐接入方式：

- 新增 `AiChapterParser` 只负责“AI 解析 + 映射 + 回退结果”。
- 不让 `HttpReadAloudService` 直接拼 AI 请求。
- `HttpReadAloudService` 只消费 `List<ReadAloudSegment>`。

## 10. 日志规范

统一前缀：

```text
aiChapterParse
```

日志示例：

```text
aiChapterParse start chapter=12 chars=4230 provider=DeepSeek model=deepseek-v4-flash promptHash=abcd1234 mode=Fast
aiChapterParse response chapter=12 elapsedMs=3200 rawLength=8420
aiChapterParse mapped chapter=12 aiSegments=86 mapped=86 elapsedMs=3400
aiChapterParse fallback chapter=12 reason=jsonInvalid
aiChapterParse fallback chapter=12 reason=mappingFailed mapped=70 total=86
```

禁止输出：

- API Key。
- Authorization Header。
- 完整章节正文。
- 完整 Prompt。
- 完整 AI 原始返回。

允许输出：

- chapterIndex。
- content hash。
- prompt hash 前 8 位。
- provider。
- model。
- segment 数量。
- elapsedMs。
- fallback reason。

完整 AI 原始返回留到 MVP10 调试视图，不在普通日志输出。

## 11. UI 范围

MVP6C 不新增复杂 UI。

可选最小开关：

- “启用 AI 分角色解析”

如果 MVP6A 已有 `enabled`，MVP6C 直接使用该开关，不新增入口。

MVP6C 不做：

- 解析按钮。
- 解析结果编辑器。
- AI 原始返回查看。
- 当前段修正（不纳入当前 V1 MVP 路线）。

这些留到后续修正能力 / MVP10。

## 12. 实施步骤

### 阶段 A：AI 解析模型

1. 新增 `AiSegmentResponse`。
2. 新增 `AiChapterParseResult`。
3. 定义 AI JSON 解析结构。
4. 增加字段默认值和合法化逻辑。

完成标准：

- 能把合法 AI JSON 转换为中间结构。
- 缺失可选字段能回退默认值。

### 阶段 B：Prompt + 请求组装

1. 读取 `AiPromptStore` 当前 Prompt。
2. 读取 `AiServiceConfig` 当前配置。
3. 构造 messages：
   - system：Prompt。
   - user：章节正文。
4. 复用 MVP6A Provider 参数生成规则。
5. 调用 OpenAI-compatible 客户端。

完成标准：

- 请求不泄露 API Key。
- Prompt hash 参与日志。
- 不在日志打印正文和 Prompt 全文。

### 阶段 C：原文映射

1. 新增 `AiSegmentMapper`。
2. 实现顺序精确匹配。
3. 实现空白规范化匹配。
4. 生成 `ReadAloudSegment`。
5. 统计映射成功率。
6. 失败时返回回退原因。

完成标准：

- AI 输出文本能映射到 `startPosition/plainLength`。
- 映射失败可诊断。

### 阶段 D：回退规则分段

1. 封装统一 fallback。
2. AI 未启用时直接规则分段。
3. AI 异常时规则分段。
4. 映射失败时规则分段。

完成标准：

- 任何 AI 失败都不影响朗读。
- fallback 日志明确 reason。

### 阶段 E：播放链路接入

1. 在当前章 Segment 构建路径中优先尝试 AI 解析。
2. 成功时使用 AI Segment。
3. 失败时使用 MVP3 规则 Segment。
4. ProviderSegment 渲染仍走现有逻辑。
5. 不写 Segment 缓存。

完成标准：

- 当前章节可使用 AI 具体角色 Segment。
- 不改变 TTS Provider 渲染接口。

### 阶段 F：更新 MVP PLAN

1. 在 `MVP PLAN.md` 的 MVP6C 小节补充实现记录。
2. 将完成状态表中 MVP6C 改为已完成。
3. 将推荐开发顺序中 MVP6C 改为已完成。
4. 记录分支名 `mvp6c`。
5. 明确说明 MVP6C 不写缓存，MVP6D 再接入缓存。

完成标准：

- `MVP PLAN.md` 与实际实现一致。
- 后续 MVP6D 范围清晰。

### 阶段 G：静态检查

按项目规则不运行编译或 Gradle：

1. 执行 `git diff --check`。
2. 静态检查是否修改 `AppDatabase.version`。
3. 静态检查是否新增 Room Entity / DAO / migration。
4. 静态检查是否有 API Key 明文日志。
5. 静态检查 AI 失败路径是否 fallback。

## 13. 验收用例

1. AI 未启用时，仍使用 MVP3 规则分段。
2. AI 配置缺失时，回退规则分段。
3. Prompt 为空时，回退规则分段。
4. 普通章节 AI 返回合法 JSON 时，生成 AI Segment。
5. AI Segment 至少包含 narrator 和一个具体角色。
6. AI Segment 的 `startPosition/plainLength` 能映射到原文。
7. AI 返回非法 JSON 时，回退规则分段。
8. AI 返回文本改写导致无法映射时，回退规则分段。
9. AI 返回空 segments 时，回退规则分段。
10. 超长章节超过 12000 字时，回退规则分段。
11. 网络失败时，不中断朗读。
12. 鉴权失败时，不泄露 API Key。
13. 日志包含 fallback reason。
14. 不写入 Segment 缓存。
15. 不修改数据库版本。
16. 修改 Prompt 后，MVP6C 请求读取新 Prompt。
17. Provider 切换后，MVP6C 使用当前 Provider 配置。
18. TTS 渲染仍能使用 AI Segment 的角色信息。

## 14. 静态验证清单

- `git diff --check` 通过。
- 不修改 `AppDatabase.version`。
- 不新增 Room Entity、DAO 或 migration。
- 不写 AI Segment 缓存。
- AI 请求不输出 API Key。
- 日志不输出完整正文、Prompt 和 AI 原始返回。
- AI 失败路径都有 fallback。
- `ReadAloudSegment.startPosition/plainLength` 有效。
- MVP6C 文档和 `MVP PLAN.md` 已更新。

## 15. 风险控制

- **AI 改写原文导致进度错位**：严格原文映射，失败整体回退。
- **JSON 不稳定**：默认 Prompt 强制只输出 JSON，失败回退。
- **超长章节超 token**：MVP6C 超长直接回退，后续再做切片。
- **AI 失败影响朗读**：所有异常吞掉并 fallback。
- **范围膨胀**：不写缓存、不做角色档案、不做修正 UI。
- **日志泄露隐私**：不打印正文、Prompt、原始返回和 API Key。

## 16. 完成定义

- 当前章节可通过 AI 解析为旁白 / 对白 / 具体角色 Segment。
- AI Segment 能映射回原文位置。
- 失败时稳定回退 MVP3 规则分段。
- 不写缓存，不改数据库。
- `MVP PLAN.md` 已更新 MVP6C 状态和实现记录。
- 静态检查通过。
