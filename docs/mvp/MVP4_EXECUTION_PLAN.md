# MVP 4 Segment 缓存表执行方案

## 1. 目标

将规则生成的通用 `ReadAloudSegment` 按书籍、章节和正文内容 hash 持久化到 Room。

同一章节再次朗读且正文未变化时直接复用缓存；正文、分段算法或缓存结构变化时自动重建，避免使用过期 Segment。

## 2. 实施边界

### 包含

- 新增 Segment 缓存 Entity、DAO 和 Room 数据库版本迁移。
- 缓存整章规则 Segment，不缓存当前页或当前播放窗口的局部结果。
- 保存完整通用 Segment JSON。
- 文件播放和流式播放共用同一缓存读取入口。
- 下一章现有预下载流程可读取缓存，但 MVP4 不新增预取策略。
- 缓存异常时自动回退实时规则分段，不中断朗读。




### 不包含

- 不新增缓存管理或手动刷新 UI，留到 MVP4.1。
- 不实现当前章后续音频预取，留到 MVP5。
- 不实现下一章预处理调度，留到 MVP5.1。
- 不接入 AI，不缓存 AI 原始响应。
- 不缓存阿里云 SSML、豆包 Speaker、Provider 请求或音频文件。
- 不改变现有角色管理、书籍音色和 HTTP_TTS 全局配置。

## 3. 核心原则

1. **缓存 Provider 无关数据**：只保存 `ReadAloudSegment` 通用字段。
2. **整章生成、局部消费**：缓存整章 Segment，播放时按当前位置截取。
3. **正文决定有效性**：使用参与分段的规范化整章正文计算 hash。
4. **算法版本参与命中**：分段规则调整后无需依赖正文变化即可重建。
5. **缓存失败不影响朗读**：读取、解析或写入失败均回退内存实时生成。

## 4. 数据结构

新增 `ReadAloudSegmentCache`：

```kotlin
@Entity(
    tableName = "readAloudSegmentCaches",
    primaryKeys = ["bookUrl", "chapterUrl", "producer"],
    indices = [
        Index(value = ["bookUrl"]),
        Index(value = ["bookUrl", "chapterIndex"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["bookUrl"],
            childColumns = ["bookUrl"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ReadAloudSegmentCache(
    val bookUrl: String,
    val chapterUrl: String,
    val producer: String,
    val chapterIndex: Int,
    val contentHash: String,
    val segmentVersion: Int,
    val segmentsJson: String,
    val updatedAt: Long
)
```

字段说明：

- `bookUrl`：书籍稳定标识。
- `chapterUrl`：章节稳定标识，来自 `BookChapter.url`。
- `producer`：Segment 生产方式；MVP4 固定为 `rule`，为后续 AI 缓存保留隔离能力。
- `chapterIndex`：用于按书籍章节查询和后续缓存管理，不作为唯一标识。
- `contentHash`：参与分段的完整规范化正文 hash。
- `segmentVersion`：规则分段算法和缓存结构版本。
- `segmentsJson`：完整 `List<ReadAloudSegment>` JSON。
- `updatedAt`：最后更新时间，供诊断和后续清理使用。

每个章节和 `producer` 只保留一条最新缓存。正文或版本变化时使用 `REPLACE` 覆盖旧记录，避免历史 hash 无限累积。

## 5. DAO 设计

新增 `ReadAloudSegmentCacheDao`：

```kotlin
@Dao
interface ReadAloudSegmentCacheDao {

    @Query(
        """select * from readAloudSegmentCaches
        where bookUrl = :bookUrl
        and chapterUrl = :chapterUrl
        and producer = :producer
        limit 1"""
    )
    fun get(
        bookUrl: String,
        chapterUrl: String,
        producer: String
    ): ReadAloudSegmentCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(cache: ReadAloudSegmentCache)

    @Query(
        """delete from readAloudSegmentCaches
        where bookUrl = :bookUrl and chapterUrl = :chapterUrl"""
    )
    fun deleteChapter(bookUrl: String, chapterUrl: String)

    @Query("delete from readAloudSegmentCaches where bookUrl = :bookUrl")
    fun deleteBook(bookUrl: String)
}
```

MVP4 主流程只调用 `get()` 和 `insert()`。删除接口提前放在 DAO 中，实际 UI 和手动刷新由 MVP4.1 接入。

## 6. Room 迁移

当前数据库版本为 76，MVP4 调整为 77：

1. 将 Entity 加入 `AppDatabase.entities`。
2. 新增 `readAloudSegmentCacheDao`。
3. 增加 `AutoMigration(from = 76, to = 77)`。
4. 保持 `exportSchema = true`。
5. 最终构建环境需要生成并提交 `app/schemas/io.legado.app.data.AppDatabase/77.json`。

项目规则禁止运行 Gradle，因此本阶段实现时只做源码和 schema 预期结构的静态核对，不在 Codex 流程中执行 schema 导出。

## 7. 正文规范化与 Hash

新增统一方法，例如 `ReadAloudSegmentCacheKey`：

```kotlin
object ReadAloudSegmentCacheKey {
    const val PRODUCER_RULE = "rule"
    const val RULE_SEGMENT_VERSION = 1

    fun normalizeContent(content: String): String {
        return content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    fun contentHash(content: String): String {
        return MD5Utils.md5Encode(normalizeContent(content))
    }
}
```

约束：

- 分段输入和 hash 输入必须使用同一份规范化文本。
- 不执行 `trim()`、连续空白合并或标点替换，避免破坏 `startPosition` 映射。
- 使用完整 MD5，不使用 16 位短 hash 作为数据库有效性判断。
- 章节标题、TTS 引擎、音色和语速不参与 Segment hash。
- 替换规则生效后正文发生变化时，处理后的正文自然产生新 hash。

## 8. Repository 设计

新增 `ReadAloudSegmentCacheRepository`，统一隔离 Room、JSON 和实时生成逻辑。

建议接口：

```kotlin
data class SegmentCacheResult(
    val segments: List<ReadAloudSegment>,
    val cacheHit: Boolean
)

class ReadAloudSegmentCacheRepository(
    private val dao: ReadAloudSegmentCacheDao
) {
    fun getOrBuildRuleSegments(
        book: Book,
        chapter: BookChapter,
        chapterContent: String
    ): SegmentCacheResult
}
```

处理流程：

1. 规范化整章正文并计算 `contentHash`。
2. 查询 `bookUrl + chapterUrl + producer=rule`。
3. 同时满足 hash 和 `segmentVersion` 时解析 JSON。
4. 校验 Segment 内容和位置映射。
5. 校验通过返回缓存命中。
6. 未命中或解析失败时实时生成整章 Segment。
7. 生成结果通过 DAO 覆盖写入。
8. 写入失败仍返回内存结果。

Repository 不渲染 Provider 内容，也不依赖当前 TTS 引擎。

## 9. 整章 Segment 生成

不能直接缓存当前 `contentList`，因为它可能只包含当前页或预下载的前几段。

新增整章生成入口：

```kotlin
fun buildChapterRuleSegments(chapterContent: String): List<ReadAloudSegment>
```

建议步骤：

1. 使用规范化后的完整 `TextChapter.getContent()`。
2. 按换行保留段落在整章中的真实偏移。
3. 每个非空段落调用 `ReadAloudRuleSegmenter.segment()`。
4. `startPosition` 使用该段落在整章正文中的字符位置。
5. 空行仍计入位置偏移，但不生成可朗读 Segment。
6. 输出按 `startPosition` 排序的整章 Segment。

不能使用 `filter { it.isNotEmpty() }` 后再累计长度来计算整章位置，否则连续空行和不同换行格式会造成偏移错误。

## 10. 缓存结果校验

缓存 JSON 解析成功后仍需执行轻量校验：

- Segment 列表非空；空章节除外。
- `startPosition >= 0`。
- `plainLength == text.length`。
- `endPosition <= chapterContent.length`。
- Segment 按 `startPosition` 非递减排列。
- 相邻 Segment 不重叠。
- `eventType`、`role` 为空时视为无效缓存。
- Segment 对应正文片段与 `text` 不一致时视为无效缓存。

任何校验失败均记录日志并实时重建，不让错误位置映射进入播放链路。

## 11. 播放链路接入

### 11.1 当前章节文件播放

在确认使用规则 Segment 后：

1. 从 Repository 获取整章 Segment。
2. 根据当前朗读窗口的起止位置筛选 Segment。
3. 将筛选结果写入 `readAloudSegments`。
4. 使用现有 `renderRuleContents(httpTts)` 动态渲染。
5. 使用现有 `findSegmentStartIndex()` 定位当前播放起点。

### 11.2 当前章节流式播放

复用与文件播放完全相同的 Repository 和窗口筛选方法，只保留后续 MediaSource 构建差异。

### 11.3 下一章节现有预下载

现有预下载流程可读取下一章整章缓存，并从 `startPosition=0` 取前 `PRE_DOWNLOAD_SEGMENT_LIMIT` 个 Segment。

这只是复用缓存，不增加新的预下载时机、数量或调度策略。

### 11.4 已有 SSML 章节

MVP4 不改变已有 SSML 章节处理方式：

- 已有 SSML 继续走 `parseSSMLContent()` 和 `rebuildContentPositionMap()`。
- 不把 SSML 或 Provider 渲染结果写入规则 Segment 缓存。
- 后续若需要缓存已有 SSML 的通用解析结果，应使用独立 `producer` 和版本。

## 12. 朗读窗口截取

新增纯函数，例如：

```kotlin
fun selectSegments(
    chapterSegments: List<ReadAloudSegment>,
    windowStart: Int,
    windowEndExclusive: Int
): List<ReadAloudSegment>
```

选择条件：

```text
segment.endPosition > windowStart
and segment.startPosition < windowEndExclusive
```

若起始位置落在某个 Segment 中间，保留完整 Segment，并继续使用现有 `readAloudNumber` 和光标映射定位，避免截断对白或改变缓存内容。

文件播放、流式播放和预下载必须共用该方法，不能分别实现位置判断。

## 13. 日志

MVP4 增加最小诊断日志：

- `segmentCache hit`：命中缓存。
- `segmentCache miss`：无缓存、hash 变化或版本变化。
- `segmentCache invalid`：JSON 或位置校验失败。
- `segmentCache rebuild`：实时重建成功。
- `segmentCache writeError`：写入失败但继续朗读。

日志只输出：

- mode
- book/chapter URL 短 hash
- contentHash
- segmentVersion
- segment 数量
- miss/invalid 原因

不输出完整正文、完整 URL、账号或密钥。

## 14. 实施步骤

### 阶段 A：数据库

1. 新增 `ReadAloudSegmentCache` Entity。
2. 新增 `ReadAloudSegmentCacheDao`。
3. 更新 `AppDatabase` 为版本 77。
4. 注册 Entity、DAO 和 76→77 AutoMigration。

完成标准：Entity 字段、主键、索引和迁移预期与 Room schema 一致。

### 阶段 B：缓存基础层

1. 新增正文规范化、hash 和版本常量。
2. 新增整章规则 Segment 生成器。
3. 新增 JSON 序列化和缓存校验。
4. 新增 Repository 的 get-or-build 流程。

完成标准：相同正文重复调用返回相同 Segment；正文或版本变化时重建。

### 阶段 C：播放接入

1. 文件播放接入整章缓存。
2. 流式播放复用同一入口。
3. 下一章现有预下载读取缓存并截取前几段。
4. 保持已有 SSML 和普通非分角色 TTS 路径不变。

完成标准：三条路径使用相同 Segment 数据，Provider 渲染仍在播放前动态执行。

### 阶段 D：文档与静态验证

1. 更新 `MVP PLAN.md` 的 MVP4 实现记录。
2. 核对数据库版本、Entity、DAO 和迁移注册。
3. 核对所有 JSON 类型和空数据兼容。
4. 核对日志不泄露正文与凭据。

## 15. 验收用例

1. 首次朗读普通章节时生成规则 Segment 并写入缓存。
2. 第二次朗读同一章节时命中缓存，Segment 数量和位置一致。
3. 从章节中间开始朗读时正确选择对应 Segment，不从章节首段播放。
4. 按页朗读切换页面时复用同一整章缓存，不覆盖为局部缓存。
5. 文件播放和流式播放读取相同缓存结果。
6. 下一章现有预下载只消费缓存前几段，不写入局部缓存。
7. 正文增加、删除或替换字符后 hash 改变并重建。
8. 提升 `RULE_SEGMENT_VERSION` 后旧缓存不再命中。
9. 缓存 JSON 损坏时实时重建，朗读不中断。
10. 数据库写入失败时使用内存 Segment 继续朗读。
11. 切换阿里云与豆包不会重建通用 Segment 缓存。
12. 修改书籍音色、角色音色或语速不会使 Segment 缓存失效。
13. 已有 SSML 章节保持原处理行为。
14. 删除书籍后关联 Segment 缓存通过外键级联删除。

## 16. 静态验证清单

按项目规则不运行编译或 Gradle：

- `git diff --check` 通过。
- XML/JSON 文件格式检查通过。
- `AppDatabase` 版本只增加一次：76 → 77。
- Entity 已加入 `entities`，DAO 已加入抽象属性。
- AutoMigration 76→77 已注册。
- 主键、索引、DAO SQL 字段名与 Entity 完全一致。
- `segmentsJson` 只序列化通用 `ReadAloudSegment`。
- hash 输入与分段输入使用同一规范化正文。
- 文件、流式和预下载路径共用 Repository。
- 局部页面或预下载列表不会覆盖整章缓存。
- 缓存异常存在实时生成回退。
- Provider voice、SSML、登录信息和音频不进入 Segment 缓存。

## 17. 预计改动位置

- `app/src/main/java/io/legado/app/data/entities/ReadAloudSegmentCache.kt`
- `app/src/main/java/io/legado/app/data/dao/ReadAloudSegmentCacheDao.kt`
- `app/src/main/java/io/legado/app/data/AppDatabase.kt`
- `app/src/main/java/io/legado/app/model/`：整章生成、缓存 key 和校验。
- `app/src/main/java/io/legado/app/service/HttpReadAloudService.kt`
- `app/schemas/io.legado.app.data.AppDatabase/77.json`（最终构建环境生成）
- `MVP PLAN.md`

## 18. 风险控制

- **局部缓存污染**：只允许整章生成器写缓存。
- **位置映射错误**：hash、分段和校验使用同一规范化正文，不移除空行。
- **Provider 污染**：缓存模型禁止加入 voice、speaker、SSML 或请求字段。
- **旧算法误命中**：所有读取必须同时校验 `segmentVersion`。
- **损坏缓存阻断朗读**：解析和校验异常必须回退实时生成。
- **数据库膨胀**：每章每种 producer 只保留一条最新记录。
- **并发重复构建**：同一章节可使用按 cache key 的 Mutex 合并并发 get-or-build。

## 19. 完成定义

- Segment 缓存表、DAO 和数据库迁移完成。
- 规则 Segment 按整章持久化并可正确命中。
- 内容 hash 或算法版本变化时自动重建。
- 文件播放、流式播放和现有下一章预下载共用缓存基础层。
- 缓存不包含任何 Provider 渲染字段。
- 缓存异常不影响朗读。
- 静态检查通过，`MVP PLAN.md` 记录实际实现。
