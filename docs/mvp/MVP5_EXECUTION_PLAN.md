# MVP 5 当前章节音频预取执行方案

## 1. 目标

优化当前章节朗读时的连续播放体验：播放当前段时，后台只预取当前段之后 3 个音频段，降低段落切换等待，同时避免一次性合成整章造成无效请求、流量和磁盘占用。

跳段、跳章、切换 TTS、调整语速或停止朗读时，旧预取任务必须及时取消，不能继续请求已经失效的音频。

## 2. 当前基础与问题

### 已有能力

- MVP4 已提供整章通用 Segment 缓存。
- 文件播放和流式播放已能得到相同的 `ReadAloudSegment`。
- `md5SpeakFileName()` 已包含 TTS 配置、语速、Segment 元数据、Provider 元数据和文本。
- 文件模式使用 `cacheDir/httpTTS/*.mp3`。
- 流式模式使用 Media3 `SimpleCache`，容量上限 128 MB。
- `onMediaItemTransition()` 可感知当前段切换。
- `playStop()`、`onDestroy()` 和 `downloadTask` 已具备基础取消点。

### 当前问题

- 文件模式会顺序遍历并生成当前朗读窗口的全部音频，不是有限窗口预取。
- 流式模式会一次性加入全部 MediaSource，超大的缓冲时长可能触发过量加载。
- 当前段构建、Provider 渲染、文件名计算、下载和播放队列组装耦合在同一循环中。
- 现有 `preDownloadAudios()` / `preDownloadAudiosStream()` 面向下一章，不是 MVP5 的当前章滑动窗口。
- 跳段或跳章后，旧任务可能继续生成已不再需要的音频。

## 3. 实施范围

### 包含

- 当前章音频任务描述模型。
- 当前段之后固定 3 段的滑动预取窗口。
- 文件模式和流式模式共用预取调度规则。
- 当前段优先，后续段低优先级串行预取。
- 段切换后推进窗口并补充新的最远一段。
- 跳段、跳章、停止、服务销毁、语速变化和 TTS 切换时取消旧任务。
- 命中音频缓存时跳过网络请求。
- 增加可定位窗口、命中、下载、取消和失败原因的日志。

### 不包含

- 不新增下一章预处理策略，留到 MVP5.1。
- 不修改 MVP4 Segment 表和 Room 版本。
- 不新增用户可配置的预取数量，MVP5 固定为 3。
- 不清理或迁移历史音频缓存。
- 不接入 AI，不改变角色识别和 Segment 生成。
- 不修改 HTTP_TTS 登录配置、书籍音色或角色音色。
- 不实现多段并行 TTS 请求，避免接口限流和顺序竞争。

## 4. 核心原则

1. **有界窗口**：只保障当前段和后续 3 段。
2. **当前段最高优先级**：当前播放需要的音频不能排在预取任务之后。
3. **串行预取**：后续段按顺序生成，控制 Provider 请求压力。
4. **缓存优先**：已有音频不重复请求。
5. **代次隔离**：旧章节或旧配置任务即使晚返回，也不能进入新播放队列。
6. **失败不阻断**：某一预取失败只记录日志，真正播放到该段时再前台重试。
7. **Segment 与音频解耦**：MVP4 管 Segment，MVP5 只调度 Provider 渲染后的音频。

## 5. 固定参数

```kotlin
private const val CURRENT_CHAPTER_PREFETCH_COUNT = 3
```

窗口定义：

```text
currentIndex .. currentIndex + CURRENT_CHAPTER_PREFETCH_COUNT
```

其中：

- `currentIndex` 必须可立即播放。
- `currentIndex + 1..3` 是后台预取范围。
- 超出当前章节 Segment 列表的索引直接忽略。

MVP5 不提供设置项。后续如需根据网络、Provider 或设备性能动态调整，再独立设计。

## 6. 音频任务描述模型

新增当前章内部模型，例如：

```kotlin
data class ReadAloudAudioTask(
    val generation: Long,
    val chapterIndex: Int,
    val segmentIndex: Int,
    val speakText: String,
    val originalContent: String,
    val fileName: String,
    val useSSMLRequest: Boolean,
    val segment: ReadAloudSegment?,
    val providerSegment: ReadAloudProviderSegment?
)
```

字段说明：

- `generation`：播放任务代次，用于隔离旧预取结果。
- `chapterIndex`：防止跨章节任务混入。
- `segmentIndex`：当前处理后的播放列表索引。
- `speakText`：最终送入 Provider 的文本或 SSML。
- `originalContent`：失败回退时使用的原始段落内容。
- `fileName`：复用现有 `md5SpeakFileName()` 计算结果。
- `useSSMLRequest`：复用现有文件模式失败回退逻辑。
- `segment`：通用 Segment。
- `providerSegment`：当前 TTS 动态生成的 Provider Segment。

该模型只存在于内存，不落库。

## 7. 任务准备阶段

将当前 `downloadAndPlayAudios()` 和 `downloadAndPlayAudiosStream()` 中重复的段落处理抽成统一方法：

```kotlin
fun buildCurrentChapterAudioTasks(
    httpTts: HttpTTS,
    mode: AudioMode
): List<ReadAloudAudioTask>
```

处理步骤：

1. 使用 MVP4 获取或复用当前章节 Segment。
2. 完成已有 SSML、规则 Segment 或普通文本的内容解析。
3. 对每段生成 `finalSpeakText`。
4. 获取 `ReadAloudSegment` 和 `ReadAloudProviderSegment`。
5. 调用 `md5SpeakFileName()` 生成稳定音频 key。
6. 只构造任务描述，不在此阶段下载音频。

文件模式和流式模式必须消费同一份任务列表，避免两条链路出现文本、音色或缓存 key 差异。

## 8. 预取调度器

新增服务内调度器 `CurrentChapterAudioPrefetcher`：

```kotlin
class CurrentChapterAudioPrefetcher(
    private val scope: CoroutineScope,
    private val prefetchCount: Int,
    private val loader: suspend (ReadAloudAudioTask, Boolean) -> Unit
) {
    fun reset(generation: Long, tasks: List<ReadAloudAudioTask>)
    fun updateCurrentIndex(index: Int)
    fun cancel(reason: String)
}
```

`loader(task, foreground)`：

- `foreground=true`：当前段缺失时立即生成，失败按现有播放错误策略处理。
- `foreground=false`：后台预取，失败只记录，不创建永久静音替代真实音频。

调度器职责：

- 保存当前代次、任务列表和当前索引。
- 计算目标窗口。
- 去重同一个 `fileName` 的任务。
- 缓存命中时直接标记完成。
- 当前段任务使用独立高优先级 Job。
- 后续 3 段使用单个串行 Job。
- 窗口推进时取消已离开窗口且尚未开始的任务。
- 代次变化时取消所有旧 Job 和状态。

## 9. 代次隔离

在 `HttpReadAloudService` 增加：

```kotlin
private var audioGeneration = 0L

private fun nextAudioGeneration(): Long {
    audioGeneration += 1
    return audioGeneration
}
```

以下动作必须产生新代次：

- 新章节开始朗读。
- 上一段、下一段跳转。
- 上一章、下一章跳转。
- 停止朗读。
- 更新语速。
- 切换 TTS 引擎或重建朗读服务。
- 文件模式与流式模式切换。
- 服务销毁。

任何下载完成后，在写入播放队列或更新状态前校验：

```kotlin
task.generation == audioGeneration && task.chapterIndex == chapterIndex
```

文件可继续保留为有效缓存，但旧任务不得修改当前 ExoPlayer 队列。

## 10. 文件模式设计

### 10.1 当前问题

文件模式当前会在一个循环中生成所有缺失 MP3，并逐个加入 ExoPlayer。

### 10.2 调整后流程

1. 构建当前章 `ReadAloudAudioTask` 列表。
2. 计算 `startIndex`。
3. 重置预取调度器，当前索引为 `startIndex`。
4. 确保当前段 MP3 存在。
5. 当前段准备完成后立即加入 ExoPlayer 并开始播放。
6. 后台串行生成后续 3 段 MP3。
7. 已生成的后续段按原顺序加入播放队列。
8. `onMediaItemTransition()` 后窗口前移并补充新的一段。

文件模式缓存检查：

```kotlin
hasSpeakFile(task.fileName)
```

文件生成继续复用：

- `createSpeakFileWithFallback()`：当前段前台加载。
- 新增 `prefetchSpeakFile()`：后台加载。

后台预取失败时不要直接创建静音文件，否则真正播放时会错误命中静音缓存，失去重试机会。

## 11. 流式模式设计

### 11.1 当前问题

流式模式一次性加入全部 MediaSource，且 LoadControl 缓冲上限极大，可能让 ExoPlayer 拉取过多后续音频。

### 11.2 调整后流程

1. 构建与文件模式相同的任务列表。
2. 只向 ExoPlayer 队列加入当前段和后续 3 段 MediaSource。
3. 当前段由正常播放加载。
4. 后续段使用 `Downloader` 写入 Media3 `SimpleCache`。
5. `onMediaItemTransition()` 后移除已播放的旧队列项，并追加新的最远一段。
6. 队列始终控制在约 4 个 MediaItem 内。

流式缓存命中判断不能依赖文件模式的 `hasSpeakFile()`。建议通过统一 cache key 与 `SimpleCache` 的 cached bytes/content length 判断，或让 Downloader 幂等执行并记录完成状态。

不要再使用一个无界 `Channel<Downloader>` 排队整章任务。调度器只提交当前窗口内任务。

## 12. 播放队列索引映射

限制 ExoPlayer 队列长度后，`exoPlayer.currentMediaItemIndex` 不再等于章节 `segmentIndex`。

每个 MediaItem 必须携带章节段索引：

```kotlin
MediaItem.Builder()
    .setUri(uri)
    .setMediaId(task.fileName)
    .setTag(task.segmentIndex)
    .build()
```

`onMediaItemTransition()` 从 `localConfiguration.tag` 或维护的映射表取得真实 `segmentIndex`，再同步：

- `nowSpeak`
- `readAloudNumber`
- `pageIndex`
- 预取窗口

不能继续依赖“每切一次媒体就简单 `nowSpeak++`”，否则跳段、重试或队列裁剪后会错位。

若当前 Media3 版本的 tag 读取接口不方便，可维护：

```kotlin
MutableMap<String, Int> // mediaId -> segmentIndex
```

## 13. 窗口推进

播放第 `N` 段时目标窗口：

```text
N, N+1, N+2, N+3
```

自动切换到 `N+1` 后：

```text
N+1, N+2, N+3, N+4
```

调度器应：

1. 保留已完成的 `N+2`、`N+3`。
2. 不重新请求相同 `fileName`。
3. 新增 `N+4` 预取。
4. 清理 `N` 的内存任务状态，但不立即删除音频缓存。

当剩余段数少于 3 时只处理实际存在的段。

## 14. 跳转和取消

### 14.1 下一段 / 上一段

现有 `nextP()` / `prevP()` 会调用 `playStop()` 后重新 `play()`。

调整：

- `playStop()` 必须调用 `currentChapterPrefetcher.cancel("playStop")`。
- 新 `play()` 产生新 generation 并重建目标窗口。
- 已完成的音频继续通过缓存命中复用。

### 14.2 跳章

`newReadAloud()` 开始前旧服务调用 `playStop()`，取消当前章预取。

新章加载完成后生成新的任务列表和 generation。

### 14.3 停止与销毁

- `stopSelf()` / `playStop()`：取消当前预取 Job。
- `onDestroy()`：关闭调度器、取消 Downloader、清理内存映射。
- 不删除已经成功写入的音频缓存。

### 14.4 语速和 TTS 变化

语速、TTS、登录信息、书籍音色或角色音色变化后，`fileName` 会变化。

必须先取消旧 generation，再按新配置构建任务；不能让旧配置音频进入新队列。

## 15. 与下一章预下载的边界

现有代码已经存在：

- `preDownloadAudios()`
- `preDownloadAudiosStream()`

它们处理下一章前几段，属于 MVP5.1 的能力。

MVP5 实施要求：

- 不扩大下一章预下载数量。
- 不修改下一章触发时机。
- 当前章调度器不接收 `ReadBook.nextTextChapter`。
- 当前章窗口任务优先级高于现有下一章预下载。
- 若当前章缺少音频，应暂停或取消下一章 Downloader，避免抢占 Provider 请求。

推荐在 MVP5 中先将现有下一章预下载调用保留但降为最低优先级；MVP5.1 再统一重构其调度策略。

## 16. 请求优先级

优先级从高到低：

1. 当前播放段缺失后的前台加载。
2. 当前章下一段 `N+1`。
3. 当前章 `N+2`。
4. 当前章 `N+3`。
5. 现有下一章预下载。

同一 HTTP_TTS Provider 默认只允许一个主动合成请求，避免：

- Provider 限流。
- 账号并发限制。
- 音频返回顺序竞争。
- 网络和 CPU 峰值。

可使用服务级 `Mutex` 或单消费者 Channel，但 Channel 内只允许当前有效 generation 的任务。

## 17. 缓存一致性

音频 key 必须继续使用现有 `md5SpeakFileName()`，不得新建弱化版 key。

缓存必须区分：

- TTS URL。
- contentType / responseType / audioPath。
- 登录信息和 header。
- 语速。
- Segment eventType / role / roleName / emotion / intensity。
- Provider voice / style / extra。
- 最终文本或 SSML。

因此：

- 切换书籍音色或角色音色会生成新音频 key。
- 切换阿里云/豆包不会误用对方缓存。
- Segment 缓存命中不代表音频缓存一定命中。

## 18. 错误处理

### 当前段失败

保持现有行为：

- 正常 Provider 请求。
- SSML 请求失败时按已有逻辑回退普通文本。
- 最终失败时使用静音占位，避免播放服务中断。

### 后台预取失败

- 记录失败，不弹 Toast。
- 不写静音文件作为永久缓存。
- 从调度器完成集合中移除。
- 真正播放到该段时作为当前段重新请求。
- 连续失败不影响其他后续段，但请求仍保持串行。

### 取消

`CancellationException` 只记录 debug 级取消原因，不作为错误上报。

## 19. 日志规范

新增统一前缀 `audioPrefetch`：

```text
audioPrefetch reset generation=12 chapter=8 tasks=42 start=5 window=5..8 mode=file
audioPrefetch hit generation=12 segment=6 key=abcd1234 mode=file
audioPrefetch start generation=12 segment=7 distance=2 mode=stream
audioPrefetch success generation=12 segment=7 elapsedMs=430 mode=stream
audioPrefetch failed generation=12 segment=8 error=HttpError mode=file
audioPrefetch cancel generation=12 reason=nextChapter pending=2
audioPrefetch stale generation=11 current=12 segment=9
```

日志要求：

- 音频 key 只输出前 8 位。
- 不输出正文、SSML、完整 URL、API Key 或登录 header。
- 输出 chapterIndex、segmentIndex、distance、mode、generation 和耗时。
- 缓存命中与网络请求必须可区分。

## 20. 实施步骤

### 阶段 A：任务模型与公共构建

1. 新增 `ReadAloudAudioTask` 和 `AudioMode`。
2. 从文件/流式循环中提取统一任务构建方法。
3. 保证两种模式的 Segment、ProviderSegment、文本和 fileName 一致。

完成标准：任务构建不执行网络请求，输入相同则输出稳定。

### 阶段 B：滑动窗口调度器

1. 新增 `CurrentChapterAudioPrefetcher`。
2. 实现 generation、窗口计算、去重、串行预取和取消。
3. 增加当前段高优先级加载。
4. 增加统一日志。

完成标准：任意时刻最多维护当前段之后 3 个预取目标。

### 阶段 C：文件模式接入

1. 移除当前章整列表顺序下载。
2. 当前段准备后立即播放。
3. 后续 MP3 由调度器生成。
4. 段切换时推进窗口。

完成标准：播放第 1 段时只生成第 1-4 段，不生成整章。

### 阶段 D：流式模式接入

1. 移除当前章全部 MediaSource 一次性入队。
2. 队列限制为当前段和后续 3 段。
3. 后续段使用 Downloader 写入 `SimpleCache`。
4. MediaItem 携带真实 Segment 索引。
5. 段切换后裁剪旧项并追加新项。

完成标准：ExoPlayer 队列有界，当前段切换后持续补充最远一段。

### 阶段 E：取消与边界收尾

1. 接入 `playStop()`、`onDestroy()`、跳段、跳章和语速变化。
2. 检查切换 TTS/音色后的 generation 隔离。
3. 当前章任务优先于下一章现有预下载。
4. 更新 `MVP PLAN.md` 实现记录。

## 21. 验收用例

1. 播放第 1 段时，当前段立即准备，并开始预取第 2-4 段。
2. 切换到第 2 段时，不重复请求第 3-4 段，只新增第 5 段。
3. 章节只剩 2 段时，不产生越界任务。
4. 已有文件音频缓存时直接命中，不发网络请求。
5. 流式缓存命中时不重复下载。
6. 文件模式不会一次性生成整章音频。
7. 流式模式 ExoPlayer 队列不会包含整章 MediaSource。
8. 快速点击下一段后，旧窗口任务被取消并重建新窗口。
9. 快速跳章后，旧章预取停止，新章任务不被旧结果污染。
10. 停止朗读后不再出现新的当前章 TTS 请求。
11. 服务销毁后 Downloader 和协程全部取消。
12. 调整语速后旧语速任务取消，新 key 重新预取。
13. 切换 TTS 引擎后不复用旧 Provider 音频。
14. 修改书籍或角色音色后使用新 Provider voice key。
15. 后台预取失败时当前播放不受影响。
16. 预取失败段真正播放时会前台重试。
17. 当前段最终失败仍按现有逻辑使用静音占位。
18. 暂停朗读时不取消已开始的当前窗口预取；停止时必须取消。
19. 恢复播放时已完成的预取可直接使用。
20. 文件播放和流式播放都保持正确的高亮和章节位置。
21. 现有下一章预下载不会抢占当前章缺失音频请求。

## 22. 静态验证清单

按项目规则不运行编译或 Gradle：

- `git diff --check` 通过。
- 不修改 `AppDatabase.version`，仍为 77。
- 不新增 Entity、DAO 或 Room migration。
- 文件模式不再遍历下载当前章全部缺失音频。
- 流式模式不再一次性加入当前章全部 MediaSource。
- 固定预取数量为 3。
- 任务构建与下载执行分离。
- 文件和流式模式共用任务构建逻辑。
- MediaItem 可映射回真实 Segment 索引。
- `playStop()` 和 `onDestroy()` 都取消预取任务。
- 跳段、跳章、语速变化产生新 generation。
- 下载完成后校验 generation 和 chapterIndex。
- 后台失败不写永久静音缓存。
- `md5SpeakFileName()` 继续作为唯一音频 key 来源。
- 日志不输出正文、SSML、完整 URL 或凭据。
- 不扩大下一章预下载范围。

## 23. 预计改动位置

- `app/src/main/java/io/legado/app/model/ReadAloudAudioTask.kt`
- `app/src/main/java/io/legado/app/model/CurrentChapterAudioPrefetcher.kt`
- `app/src/main/java/io/legado/app/service/HttpReadAloudService.kt`
- `MVP PLAN.md`

可能仅在需要共享常量时修改：

- `app/src/main/java/io/legado/app/constant/` 或 `HttpReadAloudService` 文件级常量。

不修改：

- `AppDatabase.kt`
- `ReadAloudSegmentCache` 表和 DAO
- `httpTTS.json`
- 书籍音色与角色模型

## 24. 风险控制

- **整章请求仍然发生**：文件模式下载循环和流式全量入队必须同时移除。
- **队列索引错位**：MediaItem 显式携带 `segmentIndex`，不依赖局部队列下标。
- **旧任务污染新章节**：generation + chapterIndex 双重校验。
- **Provider 限流**：后续预取默认串行。
- **当前段被预取阻塞**：当前段使用高优先级加载，并可取消后台请求。
- **静音缓存误命中**：后台预取失败不创建静音文件。
- **快速跳转重复请求**：按 fileName 去重，缓存命中直接返回。
- **内存增长**：只保存当前章节轻量任务和最多 4 个活动窗口状态。
- **下一章任务抢占**：当前章窗口优先级高于现有下一章预下载。
- **进度映射回归**：播放状态以任务 `segmentIndex` 同步 `nowSpeak`。

## 25. 完成定义

- 当前段播放时只预取后续 3 段。
- 段切换后窗口平滑推进且不重复请求。
- 文件和流式模式都使用有界队列。
- 跳段、跳章、停止和配置变化能取消旧预取。
- 缓存命中时不重复请求。
- 后台失败不影响当前播放，并允许前台重试。
- 当前章预取优先于现有下一章预下载。
- 不新增数据库迁移，静态检查通过。
