# MVP 5.1 下一章预处理执行方案

## 1. 目标

在当前章节接近结束时，后台准备下一章的 Segment 和前 3 段音频，使自动进入下一章时能够更快开始播放。

下一章预处理属于低优先级优化：不能阻塞当前章播放，失败、断网或下一章尚未加载时必须静默降级为现有实时生成流程。

## 2. 当前基础

- MVP4 已提供按书籍、章节和内容指纹缓存的通用 Segment。
- MVP4.1 已提供自动失效、手动刷新和整书缓存清理。
- MVP5 已完成当前章音频任务构建、后续 3 段滑动预取、缓存复用和 generation 隔离。
- `ReadBook.nextTextChapter` 可提供已经加载的下一章正文。
- 当前代码已有 `preDownloadAudios()` 和 `preDownloadAudiosStream()`，但会在当前章播放刚开始时直接运行，缺少统一触发、优先级和生命周期管理。
- 文件模式使用 `cacheDir/httpTTS/*.mp3`，流式模式使用 Media3 `SimpleCache`。

## 3. 当前问题

1. 下一章预下载在当前章播放初始化后立即触发，不符合“接近章末再准备”的范围。
2. 文件模式和流式模式各自实现下一章内容解析，存在重复逻辑。
3. 下一章任务没有独立 generation，跳章、跳段、切换 TTS 后可能继续执行旧任务。
4. 下一章任务可能与当前章前台加载或滑动预取竞争同一个 Provider。
5. 流式模式临时创建无界 `Channel<Downloader>`，缺少明确关闭和取消边界。
6. 预处理结果没有统一状态，无法区分未开始、Segment 已完成、音频部分完成和失败。
7. 下一章正文尚未加载、目录变化或章节失效时，缺少可诊断的跳过原因。

## 4. 实施范围

### 包含

- 当前章接近结束时触发下一章预处理。
- 获取或生成下一章完整通用 Segment 缓存。
- 基于下一章 Segment 构建前 3 段音频任务。
- 文件模式预生成前 3 段 MP3。
- 流式模式预下载前 3 段到 Media3 `SimpleCache`。
- 复用 MVP5 的文本处理、ProviderSegment 和音频 key 规则。
- 当前章任务优先于下一章任务。
- 跳章、停止、服务销毁、语速或 TTS 变化时取消旧任务。
- 下一章成为当前章后直接复用 Segment 和音频缓存。
- 增加触发、缓存命中、取消、失败和降级日志。

### 不包含

- 不预处理下一章全部音频。
- 不连续预处理下下章。
- 不新增用户配置项，预处理数量固定为 3。
- 不修改书籍角色、书籍音色或 HTTP_TTS 配置结构。
- 不新增数据库表、字段或 Room migration。
- 不修改章节正文加载和目录刷新策略。
- 不引入并行 TTS 合成。
- 不接入 AI 章节解析，继续使用当前规则 Segment。

## 5. 固定参数

```kotlin
private const val NEXT_CHAPTER_PREPARE_SEGMENT_COUNT = 3
private const val NEXT_CHAPTER_PREPARE_REMAINING_COUNT = 3
```

- `NEXT_CHAPTER_PREPARE_SEGMENT_COUNT`：下一章最多准备的音频段数。
- `NEXT_CHAPTER_PREPARE_REMAINING_COUNT`：按整章 Segment 计算，当前章剩余 3 段时允许启动下一章预处理。

MVP5.1 不增加设置页面。后续若要按网络类型或 Provider 动态调整，应单独设计。

## 6. 触发条件

下一章预处理只在以下条件全部满足时启动：

1. 当前正在使用 HTTP_TTS 朗读。
2. 当前章任务列表非空。
3. 当前播放位置按整章 Segment 计算，距离章末不超过 3 段。
4. `ReadBook.nextTextChapter` 已存在且内容加载完成。
5. 下一章不是当前章，且不是当前 generation 已完成或正在执行的目标。
6. 当前段音频已可播放。
7. 当前章滑动窗口没有前台缺失任务。
8. 服务未停止、未销毁，也未处于章节切换过程。

触发检查放在 MVP5 的 `onMediaItemTransition()` / `advancePrefetchWindow()` 之后：

```kotlin
private fun maybePrepareNextChapter(currentSegmentIndex: Int)
```

不再从 `downloadAndPlayAudios()` 或 `downloadAndPlayAudiosStream()` 初始化末尾无条件调用下一章预下载。

### 按页朗读兼容

开启 `readAloudByPage` 后，`currentChapterTasks` 只包含当前页任务。当前页剩余 3 段不等于当前章剩余 3 段，因此不能使用 `currentChapterTasks.lastIndex`、当前页 `nowSpeak`、ExoPlayer 队列长度或当前页剩余媒体项数量判断章末。

触发判断必须使用 MVP4 缓存中的整章 Segment 列表，并将当前阅读位置映射为整章绝对 Segment 索引：

```kotlin
private fun isNearChapterEnd(
    chapterSegments: List<ReadAloudSegment>,
    chapterPosition: Int
): Boolean {
    val currentIndex = chapterSegments.indexOfLast {
        it.startPosition <= chapterPosition
    }.coerceAtLeast(0)
    return chapterSegments.lastIndex - currentIndex <=
            NEXT_CHAPTER_PREPARE_REMAINING_COUNT
}
```

若整章 Segment 暂时不可用，可以使用章节绝对文本位置确认是否已经进入最后一页，但不能把普通页面末尾视为章节末尾。优先使用整章 Segment 索引，确保按页和非按页模式的触发时机一致。

## 7. 下一章身份

不能只用章节标题判断目标。定义稳定的内存态身份：

```kotlin
data class NextChapterPrepareKey(
    val bookUrl: String,
    val chapterIndex: Int,
    val chapterUrl: String,
    val contentHash: String,
    val ttsKey: String,
    val speechRate: Int,
    val mode: AudioMode
)
```

- `bookUrl + chapterIndex + chapterUrl` 确定章节。
- `contentHash` 防止正文更新后复用旧任务状态。
- `ttsKey + speechRate` 防止切换 Provider、登录信息、音色或语速后错误复用。
- `mode` 区分文件缓存和 Media3 流式缓存状态。

音频文件名仍必须由现有 `md5SpeakFileName()` 生成，不由该 key 替代。

## 8. 状态模型

新增内存态状态：

```kotlin
sealed interface NextChapterPrepareState {
    data object Idle : NextChapterPrepareState
    data class Running(
        val generation: Long,
        val key: NextChapterPrepareKey,
        val completedAudioCount: Int
    ) : NextChapterPrepareState
    data class Completed(
        val key: NextChapterPrepareKey,
        val audioCount: Int
    ) : NextChapterPrepareState
    data class Failed(
        val key: NextChapterPrepareKey,
        val reason: String
    ) : NextChapterPrepareState
}
```

该状态只用于调度和日志，不落库。真正可复用的数据仍是 MVP4 Segment 缓存和现有音频缓存。

## 9. 独立代次与取消

下一章任务使用独立代次，避免与 MVP5 当前章 `audioGeneration` 混用：

```kotlin
private var nextChapterPrepareGeneration = 0L
private var nextChapterPrepareJob: Job? = null
```

以下情况必须取消并递增代次：

- 当前章发生变化。
- 手动上一章或下一章。
- 停止朗读。
- 服务销毁。
- 调整语速。
- 切换 HTTP_TTS、Provider 或播放模式。
- 下一章对象、URL、索引或正文指纹变化。
- 当前章出现前台音频缺失，需要抢占 Provider。

取消只停止任务，不删除已成功生成的 Segment 或音频缓存。

## 10. 统一任务构建

将现有下一章两套解析逻辑收敛为公共方法：

```kotlin
private suspend fun buildNextChapterAudioTasks(
    httpTts: HttpTTS,
    nextChapter: TextChapter,
    generation: Long,
    mode: AudioMode
): List<ReadAloudAudioTask>
```

处理流程：

1. 读取下一章正文及 `readAloudContent`。
2. 使用 `ReadAloudSegmentCacheRepository` 获取或生成完整章节 Segment。
3. 若章节是已有 SSML，继续使用现有 SSML 解析流程。
4. 若使用规则分段，从完整 Segment 中取前 3 段。
5. 为每段生成 ProviderSegment。
6. 按 Provider 生成最终纯文本或 SSML。
7. 调用 `md5SpeakFileName()` 生成音频 key。
8. 返回最多 3 个 `ReadAloudAudioTask`，不在构建阶段请求音频。

下一章成为当前章后，MVP5 的 `buildAudioTasks()` 必须得到相同的 Segment、ProviderSegment、最终文本和文件名。

## 11. Segment 预处理

下一章 Segment 预处理使用 MVP4 Repository：

```kotlin
segmentCacheRepository.getOrBuildRuleSegments(
    book = book,
    chapter = nextChapter.chapter,
    chapterContent = chapterContent
)
```

要求：

- 缓存命中时不重新分段。
- 缓存失效时按 MVP4.1 规则自动重建。
- 即使音频预下载失败，Segment 缓存仍可保留。
- 用户手动刷新或清理缓存后，旧预处理状态不得阻止重新生成。
- 已有 SSML 输入继续按现有路径处理，不强行写入规则 Segment 缓存。

## 12. 文件模式

文件模式按顺序处理前 3 个任务：

1. 调用 `hasSpeakFile(fileName)` 检查缓存。
2. 命中时记录完成，不发送请求。
3. 未命中时使用后台预取方法生成 MP3。
4. 每完成一段后重新检查 generation 和当前章身份。
5. 失败只记录，不创建永久静音文件。
6. 某段失败后继续尝试后续段，避免首段异常阻断全部预处理。

下一章开始播放时：

- 已成功生成的 MP3 直接命中。
- 未生成或失败的段由 MVP5 当前段前台逻辑实时请求。
- 前台最终失败时仍保留现有静音占位策略。

## 13. 流式模式

流式模式使用 Media3 Downloader 将前 3 段写入 `SimpleCache`：

1. 为任务创建与正式播放一致的 DataSource 和 cache key。
2. 逐段串行执行 Downloader。
3. 不创建长期存活的无界 Channel。
4. Job 取消时取消当前 Downloader，并停止后续任务。
5. Downloader 完成后校验 generation，再更新状态。
6. 下一章正式播放时仍由 ExoPlayer 正常创建 MediaSource，并直接读取缓存。

建议将 Downloader 生命周期封装在下一章调度器中，而不是由调用方传入 Channel。

## 14. 请求优先级

统一优先级：

1. 当前播放段前台加载。
2. 当前章下一段预取。
3. 当前章其余滑动窗口预取。
4. 下一章 Segment 生成。
5. 下一章前 3 段音频预处理。

同一个 HTTP_TTS Provider 默认只允许一个主动合成请求。

当当前章出现缺失任务时：

- 取消或暂停正在进行的下一章音频任务。
- 当前章任务完成后，由 `maybePrepareNextChapter()` 根据条件重新启动。
- Segment 读取或本地规则分段不占用 Provider，可继续完成，但不能阻塞播放线程。

## 15. 下一章切换后的复用

当预处理目标成为当前章时：

1. 取消该目标的下一章调度 Job。
2. 清空内存态 `NextChapterPrepareState`。
3. MVP5 正常构建当前章完整任务列表。
4. Segment Repository 命中已生成的章节缓存。
5. 前 3 段通过文件缓存或 `SimpleCache` 命中。
6. MVP5 从当前播放位置继续维护后续 3 段滑动窗口。

不直接把下一章预处理任务列表搬进当前章播放队列，避免章节加载、起始位置或配置变化导致索引不一致。

## 16. 无下一章与加载未完成

以下情况直接跳过，不视为错误：

- 当前章是书籍最后一章。
- `ReadBook.nextTextChapter == null`。
- 下一章内容尚未加载完成。
- 下一章正文为空。
- 下一章索引与预期不一致。

仅记录 debug/info 日志。后续媒体段切换时可再次检查，不主动触发章节网络加载，以免改变现有阅读加载策略。

## 17. 错误与降级

### Segment 失败

- 记录失败原因。
- 不影响当前播放。
- 下一章开始后按现有实时路径重新解析。

### 音频失败

- 不弹 Toast。
- 不调用当前章的致命错误处理。
- 不创建静音文件。
- 保留已成功完成的其他段。
- 下一章真正播放时实时重试。

### 断网

- 预处理失败后回到 `Failed` 或 `Idle`。
- 当前章继续播放已有窗口音频。
- 下一章开始时由正常 HTTP_TTS 请求处理网络错误。

### 取消

- `CancellationException` 只记取消原因，不作为错误上报。
- 已写完整的缓存保留，未完成的临时下载由现有缓存实现清理。

## 18. 日志规范

统一前缀 `nextChapterPrepare`：

```text
nextChapterPrepare trigger generation=8 currentChapter=12 targetChapter=13 remaining=3 mode=file
nextChapterPrepare segment generation=8 targetChapter=13 cacheHit=true count=48
nextChapterPrepare audioHit generation=8 targetChapter=13 segment=0 key=abcd1234 mode=file
nextChapterPrepare audioStart generation=8 targetChapter=13 segment=1 mode=stream
nextChapterPrepare audioSuccess generation=8 targetChapter=13 segment=1 elapsedMs=420
nextChapterPrepare cancel generation=8 reason=currentAudioRequired completed=1
nextChapterPrepare skip reason=nextChapterNotReady currentChapter=12
nextChapterPrepare failed generation=8 stage=audio segment=2 error=HttpError
```

日志不得输出正文、完整 SSML、完整 URL、API Key、Cookie 或登录 header。音频 key 只输出前 8 位。

## 19. 代码结构建议

新增：

- `NextChapterPrepareKey`
- `NextChapterPrepareState`
- `NextChapterAudioPreparer`

建议文件：

- `app/src/main/java/io/legado/app/model/NextChapterAudioPreparer.kt`

主要修改：

- `app/src/main/java/io/legado/app/service/HttpReadAloudService.kt`
- `MVP PLAN.md`

复用：

- `ReadAloudAudioTask`
- `ReadAloudSegmentCacheRepository`
- `ReadAloudSegmentCacheKey`
- `md5SpeakFileName()`
- MVP5 当前章音频请求互斥和取消逻辑

不修改：

- `AppDatabase.kt`
- Room Entity / DAO / migration
- `httpTTS.json`
- 书籍音色和书籍角色数据模型

## 20. 实施步骤

### 阶段 A：统一下一章任务构建

1. 提取下一章 Segment 和音频任务构建方法。
2. 文件与流式模式共用同一份任务列表。
3. 确保下一章任务与其成为当前章后的音频 key 一致。

完成标准：相同章节和配置在两种阶段生成相同的前 3 个 key。

### 阶段 B：调度器与状态

1. 新增 key、state、generation 和 Job。
2. 实现 `maybePrepareNextChapter()`。
3. 实现章末剩余 3 段触发和重复任务去重。
4. 接入停止、销毁、跳章、语速和 TTS 变化取消。
5. 使用整章绝对 Segment 索引判断章末，兼容按页朗读。

完成标准：同一目标不会重复启动，过期任务不能更新当前状态。

### 阶段 C：文件模式接入

1. 删除文件播放初始化末尾的无条件 `preDownloadAudios()` 调用。
2. 将下一章文件下载迁入统一调度器。
3. 按顺序准备前 3 段，缓存命中时跳过。
4. 后台失败不创建静音文件。

完成标准：只有接近章末时才出现下一章文件请求。

### 阶段 D：流式模式接入

1. 删除临时无界 Downloader Channel。
2. 将 Downloader 生命周期交给统一调度器。
3. 串行写入前 3 段到 `SimpleCache`。
4. 取消任务时同步停止 Downloader。

完成标准：下一章播放时前 3 段能够命中相同 cache key。

### 阶段 E：优先级与收尾

1. 当前章前台加载可抢占下一章任务。
2. 当前章窗口恢复稳定后允许重新触发。
3. 增加统一日志和失败降级。
4. 更新 `MVP PLAN.md` 实现记录与验收状态。

## 21. 验收用例

1. 当前章开始播放时不立即预处理下一章。
2. 当前章剩余超过 3 段时不触发下一章任务。
3. 当前章进入最后 3 段后生成或命中下一章 Segment 缓存。
4. 开启按页朗读时，普通页面结束不会误触发下一章预处理。
5. 开启按页朗读时，仅整章最后一页的最后 3 个 Segment 可以触发。
6. 文件模式只准备下一章前 3 段 MP3。
7. 流式模式只下载下一章前 3 段到 `SimpleCache`。
8. 下一章已有 Segment 缓存时不重复分段。
9. 下一章已有音频缓存时不重复请求。
10. 下一章开始播放时首段直接命中缓存。
11. 下一章第 2、3 段也能被 MVP5 当前章窗口复用。
12. 下一章第 4 段及以后继续由 MVP5 滑动窗口处理。
13. 预处理失败不暂停或停止当前章。
14. 断网时不弹出下一章预处理专用错误提示。
15. 下一章播放时未命中的音频能够实时请求。
16. 快速跳到其他章节后旧下一章任务被取消。
17. 手动回到上一章后旧目标不会污染新的下一章状态。
18. 停止朗读后不再发起下一章请求。
19. 服务销毁后 Job 和 Downloader 均被取消。
20. 调整语速后旧语速任务失效，新任务使用新 key。
21. 切换 HTTP_TTS 或音色后不复用错误音频。
22. 当前章音频缺失时能够抢占下一章任务。
23. 当前章恢复后下一章任务可重新触发且不重复已完成缓存。
24. 最后一章不会尝试预处理不存在的章节。
25. 下一章尚未加载时只跳过，后续进入触发区可再次检查。
26. 手动刷新下一章 Segment 后旧预处理状态不会阻止重建。
27. 文件模式与流式模式均保持章节进度和高亮正确。

## 22. 静态验证清单

按项目规则不运行编译或 Gradle：

- `git diff --check` 通过。
- 不修改 `AppDatabase.version`，仍为 77。
- 不新增 Entity、DAO 或 migration。
- 删除播放初始化阶段无条件调用下一章预下载的逻辑。
- 下一章触发条件为整章绝对 Segment 剩余不超过 3 段。
- 按页朗读不使用当前页任务数量判断章末。
- 下一章音频数量固定为 3。
- 文件与流式模式共用下一章任务构建逻辑。
- 下一章任务复用 MVP5 的音频 key 规则。
- Segment 通过 MVP4 Repository 获取或生成。
- 当前章任务优先级高于下一章任务。
- 下一章 Job 具备独立 generation。
- `playStop()`、`onDestroy()`、跳章和配置变化均取消下一章任务。
- 后台失败不创建静音文件。
- 流式模式不再创建无界 Downloader Channel。
- 下一章成为当前章后只通过缓存复用，不直接搬移旧播放队列。
- 日志不输出正文、SSML、完整 URL 或凭据。

## 23. 风险控制

- **按页模式触发过早**：使用整章绝对 Segment 索引，不使用当前页任务列表判断章末。
- **普通模式触发过早**：仅在整章剩余 3 段时启动。
- **影响当前播放**：当前段和当前章窗口始终拥有更高优先级。
- **重复请求**：按目标 key、音频 fileName 和缓存状态三层去重。
- **旧任务污染**：独立 generation 加目标章节身份校验。
- **章节正文更新**：目标 key 包含正文指纹，Segment Repository 负责失效。
- **音色或语速错用**：目标 key 与音频 key 都包含相关 TTS 配置。
- **流式任务泄漏**：Downloader 绑定到可取消 Job，不使用无界 Channel。
- **失败阻断切章**：预处理错误不进入当前播放的致命错误路径。
- **预处理范围膨胀**：只处理下一章前 3 段，不递归处理下下章。
- **索引错位**：下一章正式播放时重新构建当前章任务列表。

## 24. 完成定义

- 下一章预处理只在整章接近结束时启动，按页朗读不会在普通页面末尾误触发。
- 下一章完整 Segment 可提前生成或命中缓存。
- 文件和流式模式只准备下一章前 3 段音频。
- 下一章成为当前章后可直接复用已有 Segment 和音频缓存。
- 当前章请求能够抢占下一章任务。
- 跳章、停止、销毁和配置变化会取消旧任务。
- 失败或断网不影响当前播放，并能在下一章实时回退。
- 不新增数据库迁移，静态检查通过。
