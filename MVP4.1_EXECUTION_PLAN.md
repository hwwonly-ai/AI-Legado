# MVP 4.1 Segment 缓存失效与手动刷新执行方案

## 1. 目标

在 MVP4 整章 Segment 缓存基础上，提供明确、可控的缓存失效能力：

- 手动刷新当前章 Segment。
- 清理当前书籍全部 Segment 缓存。
- 日志明确区分缓存命中、自动重建和用户手动刷新。

缓存操作不得影响章节正文、音频缓存、HTTP_TTS 配置、书籍音色或角色档案。

## 2. 当前基础

MVP4 已完成：

- `ReadAloudSegmentCache` Room Entity。
- `ReadAloudSegmentCacheDao.get()` / `insert()` / `deleteChapter()` / `deleteBook()`。
- `ReadAloudSegmentCacheRepository.getOrBuildRuleSegments()`。
- 文件播放、流式播放和下一章现有预下载共用整章 Segment 缓存。
- 数据库版本已经由 76 升级到 77。

MVP4.1 只扩展失效、刷新、日志和 UI，不新增表，不升级数据库版本。

## 3. 实施范围

### 包含

- 当前章 Segment 缓存刷新入口。
- 当前书籍全部 Segment 缓存清理入口。
- 删除结果和失败状态反馈。
- 当前正在朗读时，从原朗读位置重启并重建当前章 Segment。
- 日志增加 `manualRefresh` / `manualClearBook` 原因。
- 防止刷新期间重复点击和并发重复操作。

### 不包含

- 不清理章节正文缓存。
- 不清理 TTS 音频缓存。
- 不清理 HTTP_TTS 登录信息或全局音色。
- 不清理书籍旁白/对白音色。
- 不清理书籍角色、别名、锁定状态或角色音色。
- 不增加自动过期时间或定时清理。
- 不增加缓存大小统计页面，留到 MVP7。
- 不新增 AI 缓存管理逻辑。

## 4. 产品交互

在“朗读设置”页面新增 `Segment 缓存` 分组，放在朗读引擎设置之后。

### 4.1 刷新当前章 Segment

标题：`刷新当前章 Segment`

说明：`清除当前章节的朗读分段缓存并重新生成`

行为：

1. 获取当前书籍和当前章节。
2. 弹出确认框，显示当前章节标题。
3. 删除当前章全部 Segment producer 的缓存。
4. 若朗读服务正在运行，保存当前 `ReadAloudProgress`。
5. 从原章节、原页面和原字符位置重新启动朗读。
6. 新服务读取不到缓存后，通过 MVP4 Repository 实时重建并写入。
7. 显示成功或失败提示。

若当前没有可用书籍或章节，入口禁用或提示无法刷新。

### 4.2 清理本书 Segment 缓存

标题：`清理本书 Segment 缓存`

说明：`删除本书所有章节的朗读分段缓存`

行为：

1. 获取当前书籍。
2. 弹出二次确认，明确不会删除正文、角色和音色配置。
3. 删除该书全部 Segment 缓存。
4. 不主动停止或重启当前朗读，当前内存中的 Segment 可继续播放。
5. 后续重新进入章节或重新启动朗读时按需重建。
6. 显示删除条数。

选择不主动重启的原因：清理整本书是管理操作，不应无必要地中断当前播放。

## 5. Preference 配置

在 `pref_config_aloud.xml` 增加独立分类：

```xml
<io.legado.app.lib.prefs.PreferenceCategory
    android:title="@string/read_aloud_segment_cache"
    app:iconSpaceReserved="false">

    <io.legado.app.lib.prefs.Preference
        android:key="refreshCurrentChapterSegmentCache"
        android:title="@string/refresh_current_chapter_segment_cache"
        android:summary="@string/refresh_current_chapter_segment_cache_summary"
        app:iconSpaceReserved="false" />

    <io.legado.app.lib.prefs.Preference
        android:key="clearBookSegmentCache"
        android:title="@string/clear_book_segment_cache"
        android:summary="@string/clear_book_segment_cache_summary"
        app:iconSpaceReserved="false" />

</io.legado.app.lib.prefs.PreferenceCategory>
```

这两个 Preference 是动作入口，不保存布尔值或字符串配置。

建议将 key 定义为当前 Fragment 私有常量；若后续其他页面复用，再提升到 `PreferKey`。

## 6. DAO 调整

现有删除 SQL 已满足范围，但建议将返回值从 `Unit` 改为 `Int`，用于反馈实际删除条数：

```kotlin
@Query(
    """delete from readAloudSegmentCaches
    where bookUrl = :bookUrl and chapterUrl = :chapterUrl"""
)
fun deleteChapter(bookUrl: String, chapterUrl: String): Int

@Query("delete from readAloudSegmentCaches where bookUrl = :bookUrl")
fun deleteBook(bookUrl: String): Int
```

Room 支持 DELETE Query 返回受影响行数，该调整不改变表结构，无需数据库迁移。

删除当前章时不传 `producer`，一次清理该章节全部 producer，避免未来规则和 AI Segment 并存时只刷新部分结果。

## 7. 缓存管理层

新增 `ReadAloudSegmentCacheManager`，避免 UI 直接拼接 DAO 调用和日志：

```kotlin
class ReadAloudSegmentCacheManager(
    private val dao: ReadAloudSegmentCacheDao
) {
    fun refreshChapter(book: Book, chapter: BookChapter): Int

    fun clearBook(book: Book): Int
}
```

职责：

- 调用 DAO 删除缓存。
- 生成安全日志标识。
- 返回删除条数。
- 捕获数据库异常并向上抛出明确结果。

也可将失效方法加入 `ReadAloudSegmentCacheRepository`，但推荐独立 Manager：Repository 负责 get-or-build，Manager 负责用户主动失效，职责更清楚。

## 8. 当前章定位

当前章刷新使用：

- `ReadBook.book` 获取书籍。
- `ReadBook.durChapterIndex` 获取阅读界面当前章节索引。
- `appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)` 获取稳定的 `chapter.url`。

若朗读进度不在阅读界面当前章，应优先刷新“正在朗读的章节”：

1. `BaseReadAloudService.service?.currentProgress()` 非空时使用其 `chapterIndex`。
2. 否则使用 `ReadBook.durChapterIndex`。

这样“刷新当前章 Segment”与用户当前听到的章节一致，而不是只跟随屏幕显示章节。

确认框中显示最终解析到的章节标题。

## 9. 朗读安全重启

### 9.1 获取原进度

刷新前读取：

```kotlin
val progress = BaseReadAloudService.service?.currentProgress()
```

记录：

- `chapterIndex`
- `pageIndex`
- `chapterPos`
- 刷新前是否正在播放或暂停

### 9.2 计算 startPos

`ReadAloud.play()` 的 `startPos` 是相对当前页面的位置，不是整章绝对位置：

```kotlin
val pageStart = textChapter.getReadLength(progress.pageIndex)
val startPos = (progress.chapterPos - pageStart).coerceAtLeast(0)
```

若无法取得对应 `TextChapter` 或页面信息，回退：

- 当前阅读页可用时调用 `ReadBook.readAloud()`。
- 否则从目标章节页首启动。

### 9.3 重启策略

删除成功后：

1. 调用 `ReadAloud.stop(context)` 停止旧服务。
2. 等待服务停止事件或使用已有服务切换机制，避免两个播放队列并存。
3. 调用 `ReadAloud.play()`，传入保存的章节、页面和相对位置。
4. 保持刷新前播放状态：
   - 原来播放中：自动继续播放。
   - 原来暂停：以 `play=false` 重建队列并保持暂停。

不要调用 `ReadAloud.upReadAloudClass()`，因为刷新 Segment 不涉及 TTS 引擎类型变化。

## 10. 并发控制

手动操作可能与当前播放、流式加载和下一章预下载并发执行。

处理原则：

- UI 操作在 IO 协程中执行数据库删除。
- 每次操作开始后暂时禁用两个缓存 Preference。
- 完成或失败后恢复可点击状态。
- `ReadAloudSegmentCacheManager` 使用 Mutex 串行化同一本书的手动清理。
- 当前章刷新删除后立即重启，新的 get-or-build 负责重建。
- 已经在运行的旧预下载可能重新写入被删除的下一章缓存，这是允许的；当前章刷新必须通过停止旧朗读服务取消当前服务内任务。

不需要为所有自动缓存写入加全局锁，避免阻塞正常朗读。

## 11. 日志规范

现有 MVP4 日志：

- `hit`
- `miss`
- `invalid`
- `rebuild`
- `writeError`

MVP4.1 增加：

```text
manualRefresh chapter book=<shortHash> chapter=<shortHash> deleted=<count>
manualRefresh restart chapterIndex=<index> pageIndex=<index> chapterPos=<pos>
manualClearBook book=<shortHash> deleted=<count>
manualRefresh error type=<errorType>
manualClearBook error type=<errorType>
```

日志要求：

- 使用 `MD5Utils.md5Encode16(...).take(8)` 输出书籍和章节短 hash。
- 不输出完整 `bookUrl`、`chapterUrl` 或正文。
- 不输出 HTTP_TTS 登录信息、API Key 或音色配置。
- 用户手动刷新后的 Repository 日志应自然出现 `miss reason=noCache` 和 `rebuild`。

## 12. 提示与确认文案

需要补充默认英文和简体中文资源：

- Segment 缓存分类标题。
- 刷新当前章标题和说明。
- 清理本书标题和说明。
- 当前章确认文案，包含章节名。
- 本书确认文案，明确角色和音色不会删除。
- 当前章刷新成功/失败。
- 本书清理成功/失败及删除数量。
- 当前无可刷新章节。

示例中文：

```text
刷新当前章 Segment
清除当前章节的朗读分段缓存并重新生成

确认刷新「%1$s」的朗读分段缓存？正在朗读时会从当前位置重新开始。

清理本书 Segment 缓存
删除本书所有章节的朗读分段缓存

仅删除朗读分段缓存，不会删除正文、角色、书籍音色或 HTTP_TTS 配置。
```

## 13. 实施步骤

### 阶段 A：删除结果和管理层

1. 将 DAO 的 `deleteChapter()` / `deleteBook()` 返回值改为 `Int`。
2. 新增 `ReadAloudSegmentCacheManager`。
3. 增加短 hash 日志和错误处理。

完成标准：可独立删除当前章或整本书缓存，并返回准确条数。

### 阶段 B：朗读设置 UI

1. 在 `pref_config_aloud.xml` 新增 Segment 缓存分组。
2. 在 `ReadAloudPreferenceFragment` 绑定两个动作。
3. 增加确认框、禁用状态和操作结果 Toast。
4. 无当前书籍时禁用入口。

完成标准：用户可以从朗读设置完成两种缓存操作，且不会误触正文刷新菜单。

### 阶段 C：当前章即时重建

1. 获取实际朗读章节和 `ReadAloudProgress`。
2. 删除当前章缓存。
3. 正在朗读时停止旧服务任务。
4. 按原进度重新调用 `ReadAloud.play()`。
5. 验证暂停状态和页面位置保留。

完成标准：手动刷新后日志出现 `manualRefresh → miss → rebuild`，播放从原位置继续。

### 阶段 D：文档与静态检查

1. 更新 `MVP PLAN.md` 的 MVP4.1 状态和实现记录。
2. 核对缓存操作未调用 `BookHelp.clearCache()` 或 `BookHelp.delContent()`。
3. 核对未调用音频缓存删除逻辑。
4. 核对未修改书籍角色和音色配置。

## 14. 验收用例

1. 当前章已有缓存时刷新，删除条数大于 0，随后重新生成。
2. 当前章无缓存时刷新，删除条数为 0，仍能重新生成并正常朗读。
3. 播放中刷新，从接近原字符位置继续，不跳回章节开头。
4. 暂停中刷新，重建后保持暂停。
5. 阅读界面章节与朗读章节不一致时，刷新实际正在朗读的章节。
6. 未启动朗读时刷新屏幕当前章，下一次朗读命中重新生成的新缓存。
7. 清理本书缓存后，该书所有章节缓存被删除。
8. 清理本书缓存不停止当前播放。
9. 清理本书缓存不影响章节正文和离线缓存。
10. 清理本书缓存不影响 TTS 音频缓存。
11. 清理本书缓存不影响 HTTP_TTS 登录配置。
12. 清理本书缓存不影响书籍旁白/对白音色。
13. 清理本书缓存不影响角色、别名、锁定状态和角色音色。
14. 数据库异常时显示失败提示，应用不崩溃。
15. 快速重复点击不会启动多个清理或重启任务。
16. 文件播放和流式播放刷新后都能重建。
17. 日志能区分自动失效与手动刷新。

## 15. 静态验证清单

按项目规则不运行编译或 Gradle：

- `git diff --check` 通过。
- `pref_config_aloud.xml` XML 校验通过。
- 新增字符串在默认英文和简体中文资源中同时存在。
- Preference key 与 Kotlin 分支完全一致。
- DAO DELETE 返回类型为 `Int`，SQL 不变。
- MVP4.1 未修改 `AppDatabase.version`，仍为 77。
- 未新增 Entity、字段或数据库迁移。
- 当前章刷新获取的是实际朗读章节优先。
- `chapterPos` 正确转换为相对页面的 `startPos`。
- 当前章刷新不会调用正文缓存删除方法。
- 本书清理不会调用 `ReadAloud.stop()`。
- 所有数据库操作在 IO 线程执行。
- 失败路径恢复 Preference 可点击状态。
- 日志不包含完整 URL、正文或凭据。

## 16. 预计改动位置

- `app/src/main/java/io/legado/app/data/dao/ReadAloudSegmentCacheDao.kt`
- `app/src/main/java/io/legado/app/model/ReadAloudSegmentCacheManager.kt`
- `app/src/main/java/io/legado/app/ui/book/read/config/ReadAloudConfigDialog.kt`
- `app/src/main/res/xml/pref_config_aloud.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-zh/strings.xml`
- `MVP PLAN.md`

不修改：

- `AppDatabase.version`
- `ReadAloudSegmentCache` 表结构
- `httpTTS.json`
- 书籍角色和书籍音色模型

## 17. 风险控制

- **误删正文缓存**：缓存 Manager 只能依赖 `ReadAloudSegmentCacheDao`。
- **刷新后跳章或跳页**：重启前保存 `ReadAloudProgress`，正确换算相对页面位置。
- **暂停状态丢失**：保存刷新前播放状态，使用 `play` 参数恢复。
- **重复播放队列**：刷新当前章前停止旧服务并等待停止状态。
- **并发重复操作**：UI 禁用 + Manager Mutex。
- **未来 producer 遗留**：当前章和本书删除均不限定 producer。
- **配置被误清**：删除范围只允许 `readAloudSegmentCaches` 表。
- **用户误解正文刷新**：入口和确认文案明确“只清朗读分段缓存”。

## 18. 完成定义

- 用户可在朗读设置中刷新当前章 Segment。
- 用户可清理当前书籍全部 Segment 缓存。
- 当前章刷新后能从原朗读位置立即重建并继续。
- 清理本书不打断当前播放。
- 日志明确记录手动刷新和清理原因。
- 所有操作不影响正文、音频、HTTP_TTS、书籍音色和角色配置。
- 不新增数据库迁移，静态检查通过。
