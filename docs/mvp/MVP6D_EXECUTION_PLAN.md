# MVP 6D AI 结果缓存与下一章预热执行方案

## 1. 目标

把 MVP6C 的 AI 对白标注结果接入缓存，减少重复 AI 请求，并降低章节切换时等待 AI 解析的停顿。

MVP6D 分两层：

- 持久化缓存：AI 标注成功后写入 Segment 缓存，同章再次朗读优先复用。
- 运行时预热：当前章接近结束时提前标注下一章，结果先放服务内存，切章时直接复用。

核心目标是减少等待，但不改变朗读稳定性：AI 缓存或预热失败时，必须继续走现有 AI 实时解析或规则回退链路。

## 2. 范围

### 包含

- 设计 AI 标注结果的缓存 key。
- AI 成功标注后写入持久化缓存。
- 当前章读取时优先命中 AI 缓存。
- Prompt 版本、Prompt hash、AI 配置、章节内容变化时缓存失效。
- 当前章最后几个 Segment 内触发下一章 AI 预热。
- 下一章 AI 预热结果写入服务内存缓存。
- 切到下一章时，若内存预热 key 匹配，直接复用预热结果。
- 下一章 AI 预热成功后，可同步写入持久化缓存。
- 增加缓存命中、缓存失效、预热触发、预热命中和预热失败日志。

### 不包含

- 不做角色档案匹配和音色复用，留到 MVP7。
- 不做用户手动修正当前段；该能力不纳入当前 V1 MVP 路线。
- 不做 AI 原始返回查看 UI，留到 MVP10。
- 不缓存完整 Prompt、正文、API Key 或 AI 原始响应。
- 不改变 TTS Provider 渲染接口。

## 3. 当前基础

MVP6C 当前实际链路：

- 规则先切出 narrator/dialog Segment。
- AI 只标注对白的 `roleName/emotion/emotionIntensity`。
- `text/startPosition/plainLength` 由规则保证，AI 不允许改正文。
- AI 失败时回退规则 Segment。
- `aiDialogLabel` tag 可单独查看 AI 标注结果。
- `audioTaskBatchSummary` 可验证 TTS 任务完整构建。

MVP6D 不再回到“AI 切整章文本”的旧方案，继续沿用“规则切分 + AI 标注对白”的模型。

## 4. 缓存模型

### Producer

建议在现有 Segment 缓存 producer 上新增 AI producer：

```kotlin
const val PRODUCER_AI_DIALOG_LABEL = "ai_dialog_label"
const val AI_DIALOG_LABEL_VERSION = 1
```

规则缓存与 AI 缓存分开：

- `rule`：只保存规则 Segment。
- `ai_dialog_label`：保存规则边界 + AI 标注后的 Segment。

这样规则缓存仍可独立复用，AI 缓存失效不会破坏规则 fallback。

### 缓存内容

缓存 `List<ReadAloudSegment>` JSON，字段至少包含：

- `eventType`
- `role`
- `roleName`
- `emotion`
- `emotionIntensity`
- `text`
- `startPosition`
- `plainLength`

不缓存：

- AI 原始响应
- Prompt 文本
- API Key
- TTS provider 渲染后的 SSML
- 音频文件路径

## 5. 缓存 Key

AI 缓存必须至少绑定：

- `bookUrl`
- `chapterUrl`
- `chapter.index`
- `contentHash`
- `producer = ai_dialog_label`
- `AI_DIALOG_LABEL_VERSION`
- `promptVersion`
- `promptHash`
- `aiProvider`
- `selectedInstanceId`
- `endpointHash`
- `model`
- `mode`

建议不要把 API Key 本身放入 key；如果同一 endpoint/model 更换 API Key，一般不需要缓存失效。

`thinkingEnabled/reasoningEffort` 对当前 label 请求固定为 `false/null`，可以不单独入 key；如果后续允许 label 请求使用思考模式，再加入 key。

## 6. 读取策略

当前章构建 Segment 时：

1. 获取规则 Segment 缓存或实时规则生成。
2. 构造 AI 缓存 key。
3. 优先读取 `ai_dialog_label` 缓存。
4. 缓存命中并校验通过：使用 AI Segment。
5. 缓存缺失或失效：调用 `AiDialogLabeler.labelRuleSegments()`。
6. AI 成功：写入 AI 缓存并使用结果。
7. AI 失败：使用规则 Segment，不写 AI 缓存。

缓存命中时不再发起 AI 请求。

## 7. 写入策略

AI 标注成功后写入缓存：

- 写入前复用现有 Segment validator，至少校验位置、长度、文本一致性。
- 如果校验失败，不写缓存，当前播放回退规则 Segment。
- 写入失败不影响当前播放，记录日志后继续使用本次内存结果。

写入建议发生在 AI 标注成功后、TTS 拆长段前。

## 8. 下一章 AI 预热

### 触发时机

复用 MVP5.1 的下一章预处理触发条件：

- 当前章进入整章最后 `NEXT_CHAPTER_PREPARE_REMAINING_COUNT` 个 Segment。
- 当前页朗读模式下，必须处于当前章最后一页。
- 已有下一章预热任务运行时不重复触发。
- 跳章、停止朗读、切换 TTS/AI 配置时取消旧任务。

### 预热流程

1. 获取下一章 `TextChapter` 和正文。
2. 获取或生成下一章规则 Segment。
3. 构造下一章 AI 缓存 key。
4. 先查持久化 AI 缓存。
5. 持久化命中：写入服务内存预热结果，并继续现有下一章前 3 段音频预处理。
6. 持久化未命中：后台调用 AI 标注下一章。
7. AI 成功：写入持久化 AI 缓存，同时写入服务内存预热结果。
8. AI 失败：不影响当前播放；下一章正式进入时继续正常解析/回退。

### 内存缓存

服务内存缓存只保存一章：

```kotlin
private var nextChapterPreparedAiKey: String? = null
private var nextChapterPreparedAiSegments: List<ReadAloudSegment>? = null
```

正式进入下一章时：

- 如果 key 匹配，直接使用 `nextChapterPreparedAiSegments`。
- 命中后可以保留到本章构建完成，随后清理。
- key 不匹配则丢弃，走正常持久化读取/AI 请求/规则回退链路。

### 清理条件

以下情况清理内存预热结果：

- 停止朗读。
- 跳章。
- 切换书籍。
- TTS 配置变化。
- AI 服务配置变化。
- Prompt 变化。
- 下一章正文 hash 变化。
- 新一轮下一章预热开始。

## 9. 日志规范

继续避免输出正文、Prompt、API Key 和完整 AI 响应。

建议日志：

```text
aiChapterParse cacheCheck chapter=... producer=ai_dialog_label key=... hit=true
aiChapterParse cacheHit chapter=... segments=... promptHash=...
aiChapterParse cacheMiss chapter=... reason=missing|contentHash|promptHash|version
aiChapterParse cacheWrite chapter=... segments=... elapsedMs=...
aiChapterParse cacheWriteFailed chapter=... reason=...
nextChapterPrepare aiCheck targetChapter=... cacheHit=...
nextChapterPrepare aiStart targetChapter=... dialogs=...
nextChapterPrepare aiPrepared targetChapter=... segments=... elapsedMs=...
nextChapterPrepare aiFailed targetChapter=... reason=...
nextChapterPrepare aiMemoryHit targetChapter=... segments=...
```

现有调试日志继续保留：

- `aiDialogLabel`：查看对白 name/emotion/intensity。
- `aiChapterParse labelResult`：查看 AI 标注总数和 fallback。
- `aiChapterParse audioTaskBatchSummary`：查看 TTS 任务是否完整构建。

## 10. 代码改动建议

### Model / Repository

建议新增或扩展：

```text
ReadAloudSegmentCacheKey
ReadAloudSegmentCacheRepository
ReadAloudSegmentCacheValidator
```

新增能力：

- AI producer 常量。
- AI cache key 构造函数。
- `getAiDialogLabelSegments(...)`
- `saveAiDialogLabelSegments(...)`

### AI Labeler

`AiDialogLabeler` 保持纯标注职责，不直接写缓存。

缓存写入由上层 orchestration 控制，避免 AI 调用层耦合 Room。

### HttpReadAloudService

改动点：

- `buildRuleContentList()`：加入 AI 缓存读取和写入。
- `maybePrepareNextChapter()`：下一章接近结尾时触发 AI 预热。
- `buildNextChapterAudioTasks()`：使用预热后的前 3 段 Segment 生成音频任务。
- `cancelAllPrefetchTasks()` / 跳章路径：清理下一章 AI 内存缓存。

## 11. 验收计划

### AI 持久化缓存

- 首次朗读章节：能看到 AI 请求和 `cacheWrite`。
- 同章第二次朗读：不出现 AI 请求，出现 `cacheHit`。
- 修改 Prompt：缓存失效，重新请求 AI。
- 修改章节正文：缓存失效，重新请求 AI。
- AI 请求失败：不写缓存，使用规则 Segment。

### 下一章 AI 预热

- 当前章未接近结尾：不触发下一章 AI 预热。
- 当前章最后几个 Segment：触发 `nextChapterPrepare aiStart`。
- 下一章预热成功：出现 `aiPrepared`，切章时出现 `aiMemoryHit`。
- 下一章预热命中后：切章时不再等待 AI 请求完成。
- 下一章预热失败：当前章播放不受影响；下一章仍可正常实时 AI 或规则回退。

### 日志与性能

- 不再触发 `LOG_FLOWCTRL DROPPED`。
- `aiDialogLabel` 能查看缓存命中后的角色和情绪。
- `audioTaskBatchSummary` 仍显示完整 TTS 任务数量。

## 12. 风险与处理

- 缓存 key 过窄导致误用旧 AI 结果：key 必须包含正文 hash、Prompt hash/version、AI provider/model。
- 缓存 key 过宽导致命中率低：API Key 不进入 key；label 请求固定 thinking 参数不进入 key。
- 下一章预热抢占当前章 TTS 下载：继续复用 `prefetchRequestLock` 和 generation 取消点，当前章优先。
- 预热结果与正式切章内容不一致：切章时重新校验 key，不一致直接丢弃。
- 日志过多触发限流：AI 缓存日志只打摘要；逐段角色调试放 `aiDialogLabel`，必要时再开关化。

## 13. 不跑编译说明

按项目规则，本阶段实施和验证只做静态检查，不跑 Gradle/编译。
