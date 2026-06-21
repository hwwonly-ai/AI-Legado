# AI 分角色朗读 MVP 执行计划（V1）

本文档定义 MVP 1-10 的**技术实现细节、架构设计、依赖关系和验收标准**。
配套的 PRD（AI_IMMERSIVE_READING_PRD.md）定义**产品功能、用户体验和业务目标**。

---

## 规划调整原则

加入豆包 TTS 后，朗读链路不再只围绕“生成阿里云 SSML”设计，而应拆成两层：

- 通用层：小说文本切段、原文位置映射、eventType/role/emotion/emotionIntensity/context 元数据、缓存 key 和播放进度。
- Provider 层：将通用 segment 解析为每个 TTS 的 Provider segment，再渲染为不同平台的请求。
  - 阿里云：渲染为 `<speak><voice><emotion>...</emotion></voice></speak>`。
  - 豆包：渲染为 `text + providerVoice + emotion + contextTexts + instruction`。

后续 MVP 应避免把“分角色”“情绪识别”“进度映射”“倒回重听”绑定到某一个 TTS 平台。阿里云 SSML 和豆包 TTS 都应消费同一套通用 segment。

引入 Unitale 项目的经验后，后续 AI 能力应再增加一层“AI 朗读剧本”：

- AI 剧本层：将小说片段解析为旁白、具体角色、情绪、强度、停顿、音效、BGM、滤波器和可选图片提示词等结构化事件。
- 朗读段仍是播放链路的最小单位，AI 剧本中的 `dialogue` 事件会转换为eventType-text。
- 音效、BGM、环境音和图片不直接塞进 TTS 文本，应作为独立的声音/视觉事件eventType进入后续编排层。
- AI 输出必须保留原文位置映射，用于阅读进度、选中朗读、倒回重听和章节缓存。
- 用户对角色、音色、别名或声音事件做过一次修正后，后续章节应优先沿用用户修正。

产品模式分三档推进：

- 普通朗读：保持现有系统 TTS/在线 TTS，不进入 AI 剧本链路。
- AI 分角色朗读：识别旁白、具体角色、情绪、停顿和角色音色，这是优先上线目标。
- AI 沉浸朗读：在分角色朗读基础上加入音效、环境音、BGM 和可选图片。

---

## MVP 1-3.2：已完成

**状态：✅ 已完成并验证**

### MVP 1：阿里云 SSML TTS 基线优化

目标：稳定现有阿里云 SSML TTS 朗读能力，作为后续 AI 分角色朗读的基础。

内容：
- 使用阿里云 SSML TTS，不使用 MiniMax。
- 优化现有 `ssmlTagParse`、`readAloudContent`、`HttpReadAloudService` 中的 SSML 处理。
- 确保 `<speak>`、`<voice>`、`<emotion>`、`<break>`、`<prosody>` 等标签能被正确送入 TTS。
- 显示文本仍保持纯文本，不显示 SSML 标签。
- 音频缓存 key 需要包含 SSML 内容、TTS 源、语速等参数。
- SSML 合成失败时回退普通文本朗读或静音占位，不中断服务。

验收：
- 普通章节能正常朗读。
- 带 SSML 标签的章节能正常合成音频。
- 暂停、继续、上一段、下一段正常。
- 已生成音频可缓存复用。
- SSML 标签不会显示在阅读页面。

已完成实现记录：
- 当前分支：`mvp1`。
- 关键提交：
  - `39dfd740c Implement Aliyun SSML TTS MVP1`
  - `cba4a03b2 Unify Aliyun SSML TTS config`
- 已改为自动识别朗读模式：
  - 章节内容含 `<speak>`、`<voice>`、`<emotion>`、`<break>`、`<prosody>`、`<p>`、`<s>`、`<sub>`、`<phoneme>`、`<say-as>` 等标签时，自动作为已有 SSML 处理。
  - 普通文本且当前 HTTP TTS 是阿里云时，自动进入阿里云普通文本预处理模式。
  - 非阿里云 HTTP TTS 保持普通文本朗读，不做 SSML 预处理。
- `ContentProcessor` 当前行为：
  - 自动检测章节中是否存在 SSML 标签。
  - 仅当章节包含 SSML 标签时，将带标签原文保存到 `BookContent.readAloudText` / `TextChapter.readAloudContent`。
  - 阅读页面显示文本会过滤 SSML/HTML 标签，保证标签不显示在阅读页。
  - 普通章节不会因为阿里云能力而改变显示排版。
- `HttpReadAloudService` 当前行为：
  - 非流式播放、流式播放、下一章预下载统一使用同一套识别逻辑。
  - 已有 SSML 内容会经过 `mergeSSMLParagraphs()`、`parseSSMLContent()`、`ensureSpeakTags()` 后送入 TTS。
  - 已增加 `normalizeAliyunSSML()`，用于兼容并规范阿里云音色标签；若输入存在 `<speak voice="xxx">...</speak>`，发送前会统一为 `<speak><voice name="xxx">...</voice></speak>`。
  - 普通文本 + 阿里云 TTS 会经过 `preprocessAliyunPlainText()`，先做 XML 转义，再包装为 `<speak>...</speak>`。
  - SSML 请求失败时会剥离标签回退普通文本朗读。
  - 普通文本回退仍失败时创建静音音频，避免朗读服务中断。
  - 音频缓存文件名已按最终送入 TTS 的文本计算，避免普通文本与 SSML 预处理文本共用错误缓存。
  - 缓存 key 已包含 TTS URL、contentType、responseType、audioPath、登录信息、header、语速、最终朗读内容等关键参数。
  - 已清理并增强 `SSML_TTS` 诊断日志：
    - `modeDecision`：定位章节/当前段是否识别到 SSML，以及是否进入阿里云普通文本预处理。
    - `segment` / `request`：输出 `speakVoice`、`voiceTag`、`emotion`、纯文本长度、hash 和短预览，避免直接泄露完整正文或密钥。
    - `response`：记录阿里云返回的 `Content-Type`、HTTP code 和 message，便于区分 TTS 配置错误与播放队列问题。
- `BaseReadAloudService` 当前行为：
  - 光标进度映射不再读取 `ssmlTagParse`。
  - 是否按页映射由 `TextChapter.readAloudContent` 是否为空自动决定。
  - `stripReadAloudTags()` 继续用于计算纯文本长度和定位。
  - 当朗读入口传入的 `startPos` 已越过当前页可读文本长度时，会回退到当前页页首重试，避免直接得到空内容并销毁朗读服务。
- 默认 `httpTTS.json` 当前保留：
  - `2.阿里云语音`：普通阿里云 TTS，保留作测试备用。
  - `4.阿里云语音(SSML)V2.0`：旧自动判断 SSML 的配置，保留作测试备用。
  - `5.阿里云语音(统一SSML)`：当前推荐统一配置。
- `5.阿里云语音(统一SSML)` 配置约定：
  - 使用用户填写的 `AppKey`、`AccessKeyId`、`AccessKeySecret`。
  - 配置 JS 会记录 `aliyun_ssml_has_tag_voice`、`aliyun_ssml_use_default_voice` 和短预览，便于排查音色未生效问题。
  - 不再在配置 JS 中使用 `hasSSMLTag` 判断。
  - 是否普通文本预处理、是否已有 SSML，由 Kotlin 代码统一决定。
  - 后续 SSML 测试应优先使用 `<speak><voice name="..."><emotion ...>...</emotion></voice></speak>`，不再依赖 `<speak voice="...">` 作为测试输入。

- 未完成/注意：
  - MVP1 只实现阿里云 SSML 基线和自动识别，尚未实现规则分角色、AI 识别、结构化剧本缓存和实时预取队列。

## MVP 2：Segment 与纯文本位置映射基础

目标：建立后续规则分角色、剧本缓存、实时预取、AI 识别和光标同步都共用的最小 segment/mapping 基础。

内容：
- 定义内存态 segment：
  - 原文纯文本 `text`
  - 起始位置 `startPosition`
  - 纯文本长度 `plainLength`
  - 事件类型 `eventType`
  - 朗读身份 `role`
  - 情绪 `emotion`
  - 情绪强度 `emotionIntensity`
- 建立 SSML 到纯文本的映射。
- 保证 `<speak>`、`<voice>`、`<emotion>`、`<break>`、`<prosody>` 等标签不参与阅读页显示和光标长度计算。
- `ReadAloudProgress.chapterPos` 始终使用原文纯文本位置。
- 复用 MVP1 已有的 `stripReadAloudTags()`、`readAloudContent`、`contentStartPositions`、`contentPlainLengths` 作为实现基础。

验收：
- 带 SSML 的测试文本能正确朗读。
- 高亮起点不被 SSML 标签影响。
- 普通文本仍按现有朗读逻辑工作。
- segment 的 `startPosition/plainLength` 能稳定对应原文纯文本位置。

已完成实现记录：
- 当前分支：`mvp2`。
- 关键提交：
  - `0bdd7240b Implement MVP2 read aloud segment mapping`
- 已新增最小内存态 segment：
  - 新增 `ReadAloudSegment`，字段现为 `text`、`startPosition`、`plainLength`、`eventType`、`role`、`roleName`、`emotion`、`emotionIntensity`。
  - 当前 MVP2 只建立内存态基础，不做落库、不做剧本缓存、不接 AI。
- `BaseReadAloudService` 当前行为：
  - 新增 `readAloudSegments`，由 `rebuildContentPositionMap()` 与旧的 `contentStartPositions` / `contentPlainLengths` 同步生成。
  - `contentStartPosition()` / `contentPlainLength()` 优先读取 segment，旧数组仍保留作为兼容输出。
  - segment 的 `text` 使用 `stripReadAloudTags()` 后的纯文本，Provider 输出不保存在通用 segment 中。
  - 当章节存在 `readAloudContent` 且当前朗读列表包含标签时，映射从章节纯文本开头搜索，避免全章 SSML 块被当前页起点截断影响。
  - 已从 `<emotion category="..." intensity="...">` 中提取基础 `emotion` / `emotionIntensity` 元数据。
- `HttpReadAloudService` 当前行为：
  - 文件播放和流式播放在识别到已有 SSML 后，会先将 SSML 内容合并/解析为朗读块，再重建 segment 映射。
  - SSML 起始段选择优先使用 segment 的 `startPosition/plainLength`，并将 `readAloudNumber` 同步到纯文本起点。
  - `ReadAloudProgress.chapterPos` 继续使用章节纯文本位置，不使用 SSML 标签位置。
  - 原有 SSML 合成、普通文本预处理、缓存 key、失败回退和静音占位逻辑保持 MVP1 行为。
- `TTSReadAloudService` 当前行为：
  - 段落推进改为使用 `contentStartPosition()` / `contentPlainLength()`，避免未来遇到标签内容时按标签长度推进。
- 已验证：
  - `git diff --check` 通过。
  - 已确认提交后工作区干净。
- 未完成/注意：
  - 本地 Gradle 编译仍未成功执行：默认 `~/.gradle` wrapper 缓存锁受权限限制；改用工作区 `.gradle` 会触发 Gradle 9.4.1 下载，但当前网络受限且提权审批超时。
  - MVP2 只完成 segment/mapping 基础，尚未实现规则分角色、剧本缓存、实时预取队列或 AI 识别。

## MVP 3：规则旁白/对白分段

目标：不用 AI，先通过规则把普通小说文本切成旁白和对白两类 segment。

范围：
- 识别旁白和引号对白。
- 识别中文引号 `“...”`、`‘...’`、`「...」`、`『...』`，以及英文双引号 `"..."`、全角双引号 `＂...＂`。
- 输出 `ReadAloudSegment`，保留 `text`、`startPosition`、`plainLength`、`eventType`、`role`、`roleName`、`emotion`、`emotionIntensity` 等通用字段。
- 当前只区分 `narrator` 和 `dialog`，不识别具体人物。

当前行为：
- 当前所有可朗读文本段标记为 `eventType=text`；引号内文本标记为 `role=dialog`，引号外文本标记为 `role=narrator`。
- 识别失败、未闭合引号或异常嵌套时回退旁白。
- 默认情绪为 `neutral`，暂不做情绪识别。
- 默认旁白音色为 `zhifeng_emo`，默认对白音色为 `zhixiaoxia`。

验收状态：
- ✅ 普通小说文本可自动生成旁白/对白 segment。
- ✅ 旁白和对白可使用不同音色。
- ✅ 不影响朗读进度、光标定位和章节跳转。
- ✅ 阿里云和豆包真实接口均已验证可按旁白/对白使用不同音色朗读。

注意：
- `zhixiaoxia` 这类普通阿里云音色不能包 `<emotion>`，否则可能触发阿里云 `Engine return error code: 423`。
- MVP3 不包含具体人物识别、剧本缓存、实时预取队列或 AI 识别。

## MVP 3.1：HTTP_TTS 通用分角色播放

目标：让阿里云和豆包共用同一套规则 segment，并通过 HTTP_TTS 配置选择不同 Provider 的请求格式和音色。

当前实现：
- `ReadAloudSegment` 是通用朗读段，阿里云和豆包都消费同一套 `eventType/role/emotion/emotionIntensity` 元数据。
- `ReadAloudProviderSegment` 保存当前 TTS 的 `provider/voice/styleTag/extra` 个性化字段。
- 阿里云使用 `AliyunSsmlRenderer` 将通用 segment + Provider segment 渲染为 `<speak><voice>...</voice></speak>`。
- 豆包不接收 SSML，只接收当前 segment 的纯文本 `speakText` 和 Provider segment 中的 Speaker ID。
- `AnalyzeUrl` 已暴露 `readAloudSegment`、`readAloudProviderSegment`、`segmentEventType`、`segmentRole`、`segmentRoleName`、`segmentEmotion`、`segmentEmotionIntensity`、`providerVoice`、`providerStyleTag`、`providerExtra` 给 HTTP_TTS JavaScript。
- 文件播放、流式播放、下一章预下载使用同一套 segment 元数据和缓存 key。

HTTP_TTS 配置：
- 阿里云 `5.阿里云语音(统一SSML)`：
  - `VoiceName`：普通文本兜底音色。
  - `NarratorVoice`：旁白音色。
  - `DialogVoice`：对白音色。
- 豆包 `3.豆包 TTS(V1.3)`：
  - `ApiKey`：新版豆包鉴权。
  - `Speaker`：默认 Speaker。
  - `NarratorSpeaker`：旁白 Speaker。
  - `DialogSpeaker`：对白 Speaker。
  - `ResourceId`：默认 `seed-tts-2.0`。

日志诊断：
- `segment` / `request` / `ttsConfig` 日志输出 `eventType`、`segmentRole`、`segmentName`、`segmentEmotion`、`segmentIntensity`、`providerVoice`、`providerStyle`。
- 阿里云日志会解析 `<voice name="...">` 和 `<emotion category="...">`。
- 豆包配置日志会输出实际 `speaker`、`eventType`、`role`、`emotion` 和 `intensity`。

验收状态：
- ✅ 阿里云：旁白/对白使用不同 voice。
- ✅ 豆包：旁白/对白使用不同 Speaker ID。
- ✅ 豆包请求不包含阿里云 SSML 标签。
- ✅ 豆包和阿里云集成测试均已通过。

注意：
- 豆包当前没有显式传情绪标签或上下文数组；语气变化主要来自模型对当前文本语义的自动推断。
- 当前项目规则是不跑 Gradle/编译，验证以静态检查和真实接口测试为准。

## MVP 3.2：通用 Segment 去 Provider 渲染字段

目标：让 `ReadAloudSegment` 只保存通用朗读元数据，不保存阿里云 SSML 这类 Provider 输出。

为什么需要：
- `ssml` 是阿里云渲染结果，不应长期混在通用 segment 中。
- 豆包、阿里云和后续 Provider 应消费同一份通用 segment，再各自渲染请求。
- 后续缓存 segment 时不应缓存某个 Provider 的输出，否则切换 TTS Provider 容易复用错误数据。

范围：
- 从通用 segment 模型中移除 `ssml` 字段。
- 阿里云播放前动态调用 `AliyunSsmlRenderer.render(segment)`。
- 豆包继续使用 `segment.text` 和 `providerVoice`。
- 已有 SSML 章节作为输入内容单独处理，不作为通用 segment 的 Provider 字段保存。

验收：
- `ReadAloudSegment` 不再依赖 `ssml` 字段完成播放。
- 阿里云仍能生成正确 `<speak><voice>...</voice></speak>`。
- 豆包仍只接收纯文本和 Speaker ID。
- 切换阿里云/豆包不会复用错误的 Provider 渲染结果。

## MVP 3.3：书籍级音色管理

状态：✅ 已完成（分支：mvp3.3）

目标：先让每本书能覆盖全局 HTTP_TTS 默认音色。

为什么需要：
- HTTP_TTS 登录 UI 负责 Provider 账号和全局默认音色。
- 不同书籍可能需要不同旁白/对白音色，不应该反复修改全局 TTS 配置。

范围：
- 书籍维度保存旁白音色和默认对白音色。
- 按当前 HTTP_TTS Provider 显示对应字段：
  - 阿里云：NarratorVoice / DialogVoice。
  - 豆包：NarratorSpeaker / DialogSpeaker。
- 不做角色列表、不做角色别名、不做具体人物绑定。

验收：
- 用户可在某本书内修改旁白音色和默认对白音色。
- 同一本书重新朗读时沿用该书音色配置。
- 不影响全局 HTTP_TTS 登录配置。

已完成实现记录：
- `Book.ReadConfig` 新增 `narratorVoice` / `dialogVoice` 字段，并在 `Book` 上提供读写便捷方法；字段随现有 JSON 配置保存，无需新增 Room 迁移。
- `HttpReadAloudService` 的旁白/对白 Provider 音色选择改为优先读取当前书籍配置，未配置时回退 HTTP_TTS `loginInfo`，保持全局默认行为不变。
- `SpeakEngineDialog` 菜单新增「书籍音色」入口，打开 `BookVoiceConfigDialog`。
- `BookVoiceConfigDialog` 支持查看当前 HTTP_TTS Provider、编辑/清空书籍级旁白音色和对白音色；非阿里云/豆包 HTTP_TTS 或系统 TTS 时禁用输入并提示不支持。
- 保存或清空后调用 `book.save()` 和 `ReadAloud.upReadAloudClass()`，下一段朗读开始使用新的书籍级音色。
- 已补默认英文和简体中文字符串资源。
- 按项目规则未跑 Gradle/编译，仅做静态检查；`git diff --check` 已通过。

## MVP 3.4：书籍级角色管理

状态：✅ 已完成

目标：在书籍维度维护角色列表，为后续 AI 角色识别和跨章节一致性打基础。

范围：
- 支持新增、编辑、删除角色。
- 支持角色名、别名、备注、绑定音色、是否锁定。
- 可跟随 MVP3.3 的书籍旁白/对白音色，也可手动输入角色音色 ID。
- 锁定表示角色档案已由用户确认，供后续 AI 识别阶段避免自动修改名称、别名和音色。
- 规则分段阶段仍只自动区分旁白/对白；具体人物匹配可先通过用户手动绑定完成。

验收：
- 用户可新增/编辑角色名和别名。
- 用户可给角色绑定音色并锁定。
- 同一本书重新朗读时角色配置仍保留。

实现：
- 新增 `BookReadAloudRole` 数据类（id/name/aliases/note/voices/locked），Parcelable。
- 在 `Book.ReadConfig` 新增 `readAloudRoles` 字段，通过现有 TypeConverter 持久化，无需 Room migration。
- `Book` 上提供角色 CRUD、音色读写、名称/别名冲突校验方法。
- 新增 `BookRoleListDialog`（角色列表页）和 `BookRoleEditDialog`（角色编辑页）。
- 在 `SpeakEngineDialog` 增加"书籍角色"入口。
- 提取 `HttpTtsExtensions.kt` 共享引擎检测方法，消除重复。
- 角色音色可跟随当前引擎的书籍旁白/对白音色，也可输入自定义 voice / speaker ID；配置按 TTS 引擎 ID 隔离存储，切换引擎互不覆盖。
- MVP3.4 中锁定状态仅持久化和展示，不影响当前朗读，也不限制用户手动编辑或删除；未锁定角色当前同样正常保存和使用。

注意：
- MVP3.4 是角色管理页面，不是豆包账号配置页面。
- 后续 AI 阶段应保留锁定角色的用户配置；未锁定角色允许 AI 补充别名、调整匹配或推荐音色。
- AI 自动识别具体人物仍放在 MVP6B/6C。



## MVP 4：Segment 缓存表

状态：✅ 已完成（分支：mvp4.0）

目标：将规则生成的通用 `ReadAloudSegment` 按书籍、章节和正文内容 hash 持久化到 Room。

范围：
- 新增 Segment 缓存 Entity、DAO 和 Room 数据库版本迁移（76→77）。
- 缓存整章规则 Segment，不缓存当前页或当前播放窗口的局部结果。
- 保存完整通用 Segment JSON。
- 文件播放和流式播放共用同一缓存读取入口。
- 下一章现有预下载流程可读取缓存，但不新增预取策略。
- 缓存异常时自动回退实时规则分段，不中断朗读。

不包含：
- 不新增缓存管理或手动刷新 UI，留到 MVP4.1。
- 不实现当前章后续音频预取，留到 MVP5。
- 不实现下一章预处理调度，留到 MVP5.1。
- 不接入 AI，不缓存 AI 原始响应。
- 不缓存阿里云 SSML、豆包 Speaker、Provider 请求或音频文件。
- 不改变现有角色管理、书籍音色和 HTTP_TTS 全局配置。

实现记录：
- `ReadAloudSegmentCache` Entity：主键 `(bookUrl, chapterUrl, producer)`，含 `contentHash`、`segmentVersion`、`segmentsJson`、`updatedAt`；外键 CASCADE 删除。
- `ReadAloudSegmentCacheDao`：`get()`、`insert(REPLACE)`、`deleteChapter()`、`deleteBook()`。
- `AppDatabase` 版本 76→77，注册 Entity/DAO/AutoMigration。
- `ReadAloudSegmentCacheKey`：正文规范化（`\r\n`→`\n`、`\r`→`\n`）、MD5 hash、`RULE_SEGMENT_VERSION = 1`。
- `ReadAloudChapterSegmenter`：整章规则分段，保留空行偏移，`startPosition` 映射到整章正文字符位置。
- `ReadAloudSegmentCacheValidator`：校验 `plainLength`、`endPosition`、`eventType`/`role` 非空、文本一致性、非递减排列、无重叠。
- `ReadAloudSegmentCacheRepository`：get-or-build 流程，缓存命中时返回整章 Segment，未命中或校验失败时实时重建并写入。
- `selectSegments()`：纯函数，按窗口起止位置筛选 Segment，起始位置落在段中间时保留完整段。
- `HttpReadAloudService` 接入：
  - `buildRuleContentList()` 改为从 Repository 获取整章 Segment，再用 `selectSegments()` 截取当前页窗口。
  - `preDownloadAudios()` / `preDownloadAudiosStream()` 改为从 Repository 获取整章 Segment 后 `take(PRE_DOWNLOAD_SEGMENT_LIMIT)`。
  - 修复预下载中 `md5SpeakFileName` 使用错误 TextChapter 引用的问题。
- 日志：`segmentCache hit/miss/invalid/rebuild`，只输出 hash 前 8 位、版本号和段数，不泄露正文与凭据。

验收：
- ✅ 同一章节第二次朗读可读取缓存 segment。
- ✅ 内容 hash 改变时不会误用旧缓存。
- ✅ 文件播放和流式播放共用同一缓存读取入口。
- ✅ 下一章预下载读取缓存前几段。
- ✅ 缓存异常时回退实时规则分段。

## MVP 4.1：缓存失效与手动刷新

状态：✅ 已完成（分支：mvp4.1）

目标：把缓存失效规则和手动刷新独立出来。

范围：
- 支持按当前章清理 segment 缓存。
- 支持按整本书清理 segment 缓存。
- 日志能区分”命中缓存 / 重建缓存 / 手动刷新”。

实现记录：
- `ReadAloudSegmentCacheDao` 的 `deleteChapter()` / `deleteBook()` 返回值改为 `Int`，反馈删除条数。
- 新增 `ReadAloudSegmentCacheManager`：独立管理失效操作，使用 Mutex 串行化同一本书的手动清理，日志输出短 hash 标识。
- `pref_config_aloud.xml` 新增 Segment 缓存分组，含「刷新当前章 Segment」和「清理本书 Segment 缓存」两个动作入口。
- `ReadAloudPreferenceFragment` 绑定两个动作：
  - 刷新当前章：确认框显示章节标题，删除缓存后若朗读服务在运行则保存进度、停止旧服务、按原章节/页面/相对位置重启朗读，保持原播放/暂停状态。
  - 清理本书：二次确认明确不影响正文/角色/音色配置，删除缓存后不停止当前播放。
  - 无当前书籍时禁用入口，操作期间禁用两个 Preference 防止重复点击。
- 日志新增 `manualRefresh chapter` / `manualRefresh restart` / `manualClearBook` / `manualRefresh error` / `manualClearBook error`。
- 补充默认英文和简体中文资源字符串。
- 未修改 `AppDatabase.version`（仍为 77），未新增 Entity/字段/迁移。

验收：
- ✅ 手动刷新当前章后会重新生成 segment。
- ✅ 播放中刷新从接近原位置继续，暂停状态保持。
- ✅ 清理整本书缓存不影响 HTTP_TTS 登录配置和书籍角色配置。
- ✅ 清理本书不停止当前播放。
- ✅ 日志区分自动失效与手动刷新。
- ✅ 快速重复点击不会启动多个清理或重启任务。

## MVP 5：当前章节音频预取

状态：✅ 已完成（分支：mvp5）

目标：只优化当前章节播放中的后续段音频预取。

范围：
- 当前段播放时，后台预取后续 3 段音频。
- 跳章或停止朗读时取消当前预取任务。
- 不做下一章预处理。

实现记录：
- 新增 `ReadAloudAudioTask` 内存态模型，描述每段音频的 generation、chapterIndex、segmentIndex、speakText、fileName、useSSMLRequest、segment 和 providerSegment。
- 新增 `audioGeneration` 代次隔离，跳段、跳章、停止、语速变化、TTS 切换时递增，旧预取任务自动失效。
- 提取 `buildAudioTasks()` 统一任务构建方法，文件模式和流式模式共用同一份 Segment、ProviderSegment、文本和 fileName 逻辑。
- 新增 `cancelAllPrefetchTasks()`、`advancePrefetchWindow()`、`downloadSingleSegmentFile()` 预取调度方法。
- 文件模式重构：当前段前台下载后立即入队，后续 3 段后台串行预取；不再遍历下载整章全部音频。
- 流式模式重构：仅向 ExoPlayer 入队当前段和后续 3 段 MediaSource；不再一次性加入整章 MediaSource。
- `onMediaItemTransition` 改为从 MediaItem tag 读取真实 segmentIndex，推进预取窗口。
- 后台预取失败不创建静音音频文件，避免前台重试时误命中。
- 日志前缀 `audioPrefetch`，输出 generation、segment、distance、mode、cacheHit、prefetchFailed 等事件。
- `playStop()`、`onDestroy()`、`upSpeechRate()` 均调用 `cancelAllPrefetchTasks()`。
- 未修改 `AppDatabase.version`（仍为 77），未新增 Entity/字段/迁移。

验收：
- ✅ 播放第 1 段时能看到第 2-4 段开始预取。
- ✅ 快速跳章后旧预取任务被取消。
- ✅ 文件模式不再遍历下载整章全部音频。
- ✅ 流式模式 ExoPlayer 队列不再包含整章 MediaSource。
- ✅ 后台预取失败不创建静音缓存，真正播放时前台重试。

## MVP 5.1：下一章预处理

状态：✅ 已完成（分支：mvp5.1）

目标：独立实现下一章 segment 和音频预处理。

范围：
- 当前章接近结束时，后台准备下一章前几段。
- 预处理失败不影响当前播放。

验收：
- 当前章结束后，下一章首段可更快播放。
- 断网或失败时能回退普通实时请求。

实现记录：
- 下一章预处理改为当前章进入整章最后 3 个 Segment 时触发，不再在播放初始化后立即执行。
- 按页朗读使用章节绝对位置映射整章 Segment，普通页面结束不会误判为章节结束。
- 文件模式和流式模式共用 `buildNextChapterAudioTasks()`，只构建下一章前 3 段任务。
- 下一章完整规则 Segment 通过 MVP4 Repository 提前生成或命中缓存。
- 文件模式预生成 MP3；流式模式通过 Media3 Downloader 写入 `SimpleCache`。
- 下一章任务使用独立 generation、目标 key、Job 和 Downloader 取消点。
- 当前章滑动窗口推进时可取消下一章任务，当前章任务完成后重新评估触发。
- 停止、跳章、服务销毁、语速或 TTS 变化会通过现有播放重置路径取消旧任务。
- 流式下一章预处理失败时不缓存静音音频，正式播放时继续实时请求。
- 移除原 `preDownloadAudios()` / `preDownloadAudiosStream()` 无条件调用和无界 Downloader Channel。
- 未修改 `AppDatabase.version`，未新增 Entity、字段或 migration。

验收状态：
- ✅ 当前章普通页面末尾不会触发下一章预处理。
- ✅ 整章最后 3 个 Segment 内触发下一章任务。
- ✅ 文件与流式模式只准备下一章前 3 段。
- ✅ 当前章请求优先，旧下一章任务可取消。
- ✅ 失败不影响当前播放，下一章正式播放时可实时回退。

## MVP 6A：AI 服务配置

状态：✅ 已完成（分支：mvp5.1）

目标：完成 OpenAI-compatible AI 服务配置和连接测试，区分 DeepSeek / GLM / 自定义 Provider，支持多实例和 API Key 加密存储。

范围：
- 配置 Provider（DeepSeek / GLM / 自定义实例）、endpoint、apiKey、model 和高级参数。
- 保存配置并提供连接测试。
- API Key 按 Provider/实例独立存储并加密。
- 自定义 Provider 可新增多个实例，主弹窗内直接管理（新增/删除）。
- 不做 Prompt 编辑、不做章节解析。

验收：
- 配置保存后重启仍保留。
- 切换 Provider/实例自动带出对应 apiKey/endpoint/model。
- 连接测试能返回成功或明确错误。
- API Key 明文不落磁盘、不进日志。

实现记录：

**配置模型与存储**
- `AiServiceConfig`（data class）：enabled、provider、selectedInstanceId、endpoint、apiKey（运行时解密值）、model、mode、thinkingEnabled、reasoningEffort、temperature、stream、providerExtraJson。maxTokens 由 labeler 动态计算作请求参数（不入 config），topP/timeoutSeconds 已移除（timeout 固定 600s）。
- `AiServiceConfigStore` + `AiServiceConfig.load()`：读写 SharedPreferences，apiKey 不再落偏好（仅 selectedInstanceId 落偏好）。
- 偏好 key 新增于 `PreferKey`：`aiServiceEnabled/Endpoint/Model/Temperature/Provider/Mode/ThinkingEnabled/ReasoningEffort/Stream/ProviderExtraJson/SelectedInstance`。旧 `aiServiceMaxTokens/TopP/TimeoutSeconds` key 已在 `load()` 中清理删除。
- 首次升级检测旧 `aiServiceApiKey` 偏好 → 加密迁移为实例 → 删除旧 key。

**Provider 预设与调用模式**
- `ProviderPresets.forProvider(AiProvider)` / `forProviderName(String)`：DeepSeek（`https://api.deepseek.com`、deepseek-v4-flash/pro、支持 thinking + reasoning_effort=high/max）、GLM（`https://open.bigmodel.cn/api/paas/v4`、glm-4.7-flashx/glm-4.7、支持 thinking、不传 reasoning_effort）、Custom（空预设）。
- `AiCallMode` 枚举（Fast/Balanced/Refine）+ `defaultParamsForMode(mode, provider)`：按 Provider 返回 thinking/reasoningEffort/temperature 推荐值。
- 切换模式自动填充推荐参数；手动改过的高级参数不被覆盖；「恢复推荐参数」按钮按当前 Provider+模式重置高级参数（不覆盖 endpoint/apiKey/model）。

**多实例与加密存储**
- `AiServiceInstance` Room Entity（表 `aiServiceInstances`）：id、name、provider（自定义实例存自身 name）、endpoint、apiKeyEncrypted（AES 加密 Base64）、model、isDefault、sortOrder。
- `AiServiceInstanceDao`：getByProvider/getDefault/get/insert/update/delete。
- `AppDatabase` version 77→78，注册 entity + `AutoMigration(77→78)`。
- `ApiKeyCrypto`：AES（androidId 派生 key）加解密，复用项目 `SymmetricCryptoAndroid`/`AES` 模式。
- `AiServiceInstanceRepo`：CRUD + 加解密；`apiKey()` 解密返回明文（仅运行时）；日志只输出 `hasKey` 布尔值。

**Provider 下拉（动态实例列表）**
- Provider 下拉 = 内置 DeepSeek + GLM + 所有自定义实例 + 「＋ 新增自定义」特殊项。
- 选「新增自定义」→ 显示实例名输入框 → 填配置保存后新实例名出现在下拉。
- DeepSeek/GLM/自定义实例的 model/endpoint/key 都可改并保存。
- 主弹窗底部「删除」按钮可删当前选中项（含内置项，删内置项后下次重建默认）。
- 切换下拉项 apiKey/endpoint/model 跟随。

**OpenAI 兼容客户端**
- `OpenAiCompatibleClient.buildPayload`（ping）：model、messages（ping）、thinking.type、reasoning_effort（仅 Provider 支持且非空时）、temperature、max_tokens（固定 16）、stream。`buildChatPayload(config, messages, maxTokens)`：max_tokens/max_completion_tokens 由参数传入。
- temperature 经 `formatTemperature` 限制为最多 2 位小数，避免 float 精度尾巴（`0.2f.toDouble()` = 0.20000000298023224）导致服务端校验失败。请求超时固定 `REQUEST_TIMEOUT_SECONDS=600`。
- 失败分类：`MissingField/InvalidEndpoint/Unauthorized(401/403)/NotFound(404)/RateLimited(429)/ServerError(5xx)/Timeout/NetworkError/BadResponse`。
- 日志仅输出 endpointHash、model、code、type、elapsedMs、thinking/reasoningEffort/stream，不输出 Key 或 Header。

**UI 与入口**
- `AiServiceConfigDialog`（布局 `dialog_ai_service_config.xml`）：启用开关、Provider 下拉、Mode 下拉、endpoint、API Key（密码态）、model（可编辑）、思考模式开关、推理强度下拉、temperature、stream 开关、扩展 JSON、连接测试、恢复推荐参数、删除、保存/取消。maxTokens/topP/timeout 控件已移除。
- 入口：朗读配置弹窗「AI 服务」preference（key `aiServiceConfig`）。
- 全链路日志（前缀 `aiConfig`）：initData/load/save/providerOptionChanged/modeChanged/restoreDefaults/buildPayload/testStart/testSuccess/testFailed。

**已知限制**
- stream 开关当前只传字段，`testConnection` 响应解析只处理非流式（`choices[0].message.content`）；开 stream 后连接测试会因拿不到 message 而归类 BadResponse。MVP6A 连接测试应保持 stream 关闭。
- 不接入 Segment 缓存或朗读链路。

## MVP 6B：Prompt 管理

目标：独立完成 AI 解析 Prompt 的查看、编辑和恢复默认。
状态：✅ 已完成（分支：mvp6b）

范围：
- 内置默认 Prompt。
- 支持用户编辑和恢复默认。
- 支持复制与剪贴板粘贴 Prompt 文本。

验收：
- 修改 Prompt 后可保存。
- 复制后再粘贴内容一致。

实现状态（2026-06-19，分支 mvp6b）：
- 新增 `DefaultAiPrompts`（默认 Prompt 常量、`DEFAULT_AI_PROMPT_VERSION=1`、`normalizePrompt`、`promptHash` 基于 MD5）。
- 新增 `AiPromptConfig` 数据类（promptText/version/contentHash/updatedAt/isDefault）。
- 新增 `AiPromptStore`，基于 SharedPreferences 持久化（key：`aiPromptText/aiPromptVersion/aiPromptHash/aiPromptUpdatedAt`），未保存时返回默认 Prompt，不新增 Room 表。
- 新增 `AiPromptConfigDialog` + `dialog_ai_prompt_config.xml`：显示版本/hash/更新时间/是否默认/未保存状态；按钮栏含恢复默认、复制、粘贴、取消、保存；编辑框区域用 NestedScrollView 限制高度，按钮栏固定底部始终可见；保存做空校验，恢复默认前确认，粘贴只填编辑框不自动保存，有未保存修改时关闭需确认。
- 入口：AI 服务配置弹窗按钮栏新增“Prompt 管理”按钮，打开 `AiPromptConfigDialog`。
- 不调用 AI、不解析章节、不写/清 segment 缓存、不修改数据库版本。
- 后续 MVP6C 读取当前 Prompt 发起解析；MVP6D 使用 `promptHash`/`promptVersion` 判断缓存失效。

## MVP 6C：AI 单章解析最小闭环

目标：AI 只解析当前章节为旁白/对白/具体角色 segment。
状态：✅ 已完成（分支：mvp6c）

范围：
- 输入当前章节文本。
- 输出可映射回原文的 segment 列表。
- 只处理 dialogue/narrator，不处理 BGM、音效、图片。

验收：
- 能识别旁白和至少一个具体角色名。
- AI 输出映射失败时回退 MVP3 规则分段。

实现状态（2026-06-19，分支 mvp6c；2026-06-20 同步修正描述）：
- 方案演进：早期实现过“AI 切整章文本”模型（`AiChapterParser`/`AiSegmentMapper`/`AiSegmentResponse`），后改为“规则切分 + AI 标注对白”模型。旧方案类已于 mvp6d 清理时删除（彼此互引用、无外部调用方），最终链路只用 `AiDialogLabeler`。
- 新增 `AiDialogLabel`（`AiDialogLabelResponse.kt`）：解析 AI 返回 JSON（兼容 ```json 代码块/外层说明），输出 `labels` 列表（id/role/roleName/emotion/emotionIntensity），缺失字段回退默认值（roleName 默认“对白”，emotion 默认 neutral，emotionIntensity 限制 0..1 默认 1.0）。
- `AiChapterParseResult` + `AiParseFallbackReason`：解析结果（segments/provider/model/promptHash/promptVersion/elapsedMs/fallbackUsed/errorMessage）与回退原因枚举。`AiChapterParseResult` 被 `AiDialogLabeler` 复用作为标注结果载体。
- `OpenAiCompatibleClient` 提供 `chatCompletion(config, messages, maxTokens)` + `AiChatResult`/`AiChatMessage`：复用 MVP6A 参数生成（thinking/reasoning_effort/temperature/max_tokens/stream），messages 为 system(Prompt)+user(对白上下文 payload)；maxTokens 由 labeler 按对白数动态算后传入，请求超时固定 600s；日志只打 endpointHash/model/messages 数量/elapsedMs，不输出 payload、正文、Prompt、API Key。
- 新增 `AiDialogLabeler.labelRuleSegments(...)`：规则先切出 narrator/dialog segment，AI 只标注对白的 roleName/emotion/emotionIntensity；`text/startPosition/plainLength` 由规则保证，AI 不改正文。系统 Prompt 限定只输出 JSON、禁改写正文、emotion 限定枚举、不确定 roleName 返回“对白”。每条对白带 before/after 上下文段（前 3 段 500 字 / 后 2 段 300 字），单次最多 80 条对白；label 请求固定 thinking=false/reasoningEffort=null，maxTokens 按 `4096 + 对白数×96` 上限 16000 动态计算并作为 `chatCompletion` 参数传入。
- 接入 `HttpReadAloudService.buildRuleContentList`（改为 suspend）：规则 segment 由 `segmentCacheRepository.getOrBuildRuleSegments` 提供，经 `tryAiLabelRuleSegments` 调 `AiDialogLabeler` 标注；AI 成功用标注后 segment 走原有 windowing + `renderRuleContents` 渲染（坐标与规则分段一致），失败/未启用返回 null 回退规则 segment。`aiDialogLabel` tag 可逐段查看对白 name/emotion/intensity，`audioTaskBatchSummary` 验证 TTS 任务完整构建。
- 回退原因：aiDisabled/missingConfig/emptyPrompt/requestFailed/labelParseFailed（对应 `AiDialogLabeler.fallback` 的 reason）。
- MVP6C 不写缓存（留 MVP6D）、不新增 Room 表、不改 `AppDatabase.version`、不改 TTS Provider 渲染接口。

## MVP 6D：AI 结果缓存接入

目标：把 AI 解析结果接入缓存，并减少跨章等待 AI 解析的停顿。
状态：✅ 已完成（分支：mvp6d）

范围：
- AI 成功后写入 segment 缓存。
- 再次朗读同章优先读取缓存。
- Prompt 版本或内容 hash 变化时缓存失效。
- 当前章接近结束时，可提前对下一章发起 AI 标注。
- 下一章 AI 预热结果先放服务内存缓存，不落库；切到下一章时若章节内容、Prompt、AI 配置一致，则直接复用，跳过等待。
- 下一章 AI 预热失败不影响当前播放；正式进入下一章时继续按正常 AI/规则回退链路执行。
- 停止朗读、跳章、TTS/AI 配置或 Prompt 变化时，清理下一章内存预热结果。

验收：
- 同章第二次朗读不重复调用 AI。
- 修改 Prompt 后会重新解析。
- 当前章最后几个 Segment 内触发下一章 AI 预热日志。
- 下一章预热命中时，章节开始不再等待 AI 请求完成。
- 下一章预热失败时，当前章播放不受影响，下一章仍可正常回退解析。

实现状态（2026-06-20，分支 mvp6d）：
- 复用 MVP4 的 `ReadAloudSegmentCache` 表，不新增 Room 迁移、不改 `AppDatabase.version`（仍 78）。AI 缓存与规则缓存通过 producer 区分：规则 `rule`，AI `ai_dialog_label`。
- `ReadAloudSegmentCacheKey` 新增 `PRODUCER_AI_DIALOG_LABEL` / `AI_DIALOG_LABEL_VERSION=1` 与 `aiDialogLabelProducer(...)`：把 promptVersion/promptHash/provider/selectedInstanceId/endpointHash/model/mode 全部失效维度编码进一个不透明 producer 字符串，复用现有 (bookUrl, chapterUrl, producer) 主键。API Key、thinking/reasoningEffort 不入 key。外加 `logKey(...)` 截断展示。
- `ReadAloudSegmentCacheRepository` 新增 `getAiDialogLabelSegments(...)` / `saveAiDialogLabelSegments(...)`：复用 `parseSegmentsJson` 与 `ReadAloudSegmentCacheValidator`，写入前校验位置/长度/文本一致性；命中缺失/失效/校验失败都返回 null 由调用方回退。
- `HttpReadAloudService.tryAiLabelRuleSegments` 改为三级读取：内存预热 → 持久化 AI 缓存 → 实时 AI 标注。内存命中即消费并清理（`aiMemoryHit`）；持久化命中直接用（`cacheHit`）；未命中调 `AiDialogLabeler.labelRuleSegments()`，成功非 fallback 写持久化缓存（`cacheWrite`），fallback 返回 null 走规则回退。`AiDialogLabeler` 保持纯标注，不写缓存。
- 下一章 AI 预热 `prepareNextChapterAiLabels(...)`：插入 `maybePrepareNextChapter` 音频下载前，复用既有触发条件/`prefetchRequestLock`/generation 取消点。先查持久化缓存（命中写内存，`aiCheck cacheHit`），未命中后台标注下一章（`aiStart`→`aiPrepared`，失败 `aiFailed`），成功同步落库 + 写内存。内存只保存一章（`nextChapterPreparedAiKey/Segments`）。
- 切章 key 比对：`buildAiDialogLabelProducerKey(...)` 当前章与下一章预热共用同一构造，切章时重算比对一致才复用，不一致丢弃走正常链路。清理点挂 `cancelNextChapterPrepare` → 覆盖停读/跳章/换速/播放重置。
- 日志：判断缓存是否生效的关键组合统一输出到 tag `AICache`（`cacheHit`/`cacheMiss`/`cacheWrite`/`cacheWriteFailed`/`aiCheck`/`aiStart`/`aiPrepared`/`aiFailed`/`aiSkip`/`aiMemoryHit`/`aiLabel`），一条 `adb logcat -s AICache` 可看完整链路；逐段角色仍走 `aiDialogLabel`，规则缓存走 `segmentCache`，下一章音频预热走 `nextChapterPrepare`。不输出正文/Prompt/API Key/完整响应。
- 不缓存 AI 原始响应、Prompt 文本、API Key、SSML、音频文件路径；不改 TTS Provider 渲染接口。

## MVP 7：角色匹配与跨章节音色复用

目标：升级书籍级角色结构，增加角色分类、8 种角色属性和 8 个固定龙套；重设计角色管理 UI；让 AI 识别出的角色只匹配已有角色档案，并复用角色已绑定音色。

范围：
- 不新增角色档案表，不新增 Room migration。
- 复用 `BookReadAloudRole`、`Book.ReadConfig.readAloudRoles` 和现有角色管理 UI。
- 角色、龙套、别名、锁定状态和角色音色均按书籍隔离，不同书籍互不共享。
- 增加 `roleCategory/roleAttribute/autoManaged/systemRole`。
- 定义男主角、女主角、主要配角、次要配角、龙套分类。
- 定义 8 种角色属性和 8 个固定龙套角色。
- 重设计角色管理 UI，按主角、主要配角、次要配角、龙套分组展示。
- 梳理角色名 / 别名规范化规则，新增轻量 matcher 工具。
- 按角色名和别名匹配角色档案。
- 匹配成功时使用已绑定音色。
- AI 不创建角色、不改分类、不改属性、不改别名、不改音色。
- 未匹配角色不写入角色档案，只使用默认对白音色并记录日志。
- 测试期 `roleMatch` 日志允许输出对白正文，便于验证匹配准确性。
- 测试期角色音色变更后清理当前章节音频缓存；正式缓存失效策略留到 MVP10。
- 不做导入导出。

验收：
- 8 个固定龙套角色自动存在且不可删除。
- 男主角、女主角、主要配角、次要配角数量限制可校验。
- 连续两章同一角色使用同一音色。
- AI 命中已有角色时只读取档案，不修改档案。
- 未匹配角色不污染角色档案，并可通过 `roleMatch miss` 日志定位。
- 改角色音色后，当前章节可重新合成新音色音频用于测试验证。

## MVP 7.1：未匹配角色自动归档与动态次要配角

目标：在 MVP7 已能匹配已有角色的基础上，处理 AI Segment 中未匹配到现有角色的 `roleName`，并用可配置统计窗口维护动态次要配角 Top10。

范围：
- AI 返回新增 `roleAttribute`。
- 维护 `roleName` 对白次数统计。
- 统计、动态次要配角和自动写入龙套 aliases 均按书籍隔离。
- 增加统计窗口开关：全书累计统计 / 最近 20 章统计。
- 开关入口放在书籍角色管理的自动归档设置中。
- 非主角、非主要配角、未锁定候选按对白次数 Top10 成为次要配角。
- Top10 之外的未锁定次要配角降入对应属性龙套 aliases。
- 次要配角默认跟随同属性龙套音色。
- 锁定次要配角不参与动态调整。
- 主角和主要配角只允许用户手动管理，AI 不创建、不修改。
- 不做模糊匹配、角色合并或拆分。

验收：
- 全书累计模式下，`王丽 + middle_female` 全书对白次数进入 Top10 后自动成为次要配角。
- 最近 20 章模式下，动态次要配角 Top10 只按最近 20 章对白次数计算。
- 次要配角默认使用同属性龙套音色。
- Top10 外未锁定次要配角可降入对应龙套别名。
- 锁定次要配角不会被降级。
- `侍卫/路人/众人` 等泛称归入对应属性龙套，不创建独立角色。
- AI 不能创建或修改男主角、女主角、主要配角。

## MVP 8：音色管理

目标：提供类似 MultiTTS 的中文音色库管理入口，仅面向阿里云和豆包，按 TTS Provider 展示官方中文音色列表，支持搜索、试听、右上角三点菜单编辑试听文本，并可将音色分配给书籍默认音色、龙套或不同角色。

范围：
- 顶部选择 TTS Provider，只显示阿里云和豆包。
- 阿里云只展示官方中文音色列表。
- 豆包只展示“豆包语音合成模型2.0”中文音色列表。
- 实施计划中内置阿里云和豆包中文音色清单；阿里云请求传 `voice`，豆包请求传 `voice_type`。
- 音色库为内置静态数据，音色分配结果按书籍隔离。
- 展示 displayName、已分配 roleName、声音属性，不显示请求参数值。
- 阿里云 displayName 显示官方名称，声音属性显示官方类型。
- 豆包 displayName 显示官方音色名称，声音属性显示官方场景。
- 每个音色以卡片呈现，并提供明确的试听按钮和分配按钮。
- 支持按名称、已分配角色名、声音属性搜索或筛选。
- 试听文本不直接展示，通过右上角三点菜单打开弹窗编辑。
- 不做试听语速、音量、语调调节。
- 已分配音色显示在列表上方，并按旁白、默认对白、主角、龙套、主要配角、次要配角排序。
- 未分配音色显示在已分配音色之后。
- 同一个音色可分配给多个角色。
- 每个角色在同一 Provider 下只能有一个音色，后一次分配会替换之前的分配。
- 支持将音色设为书籍默认旁白/对白，或分配给 8 个固定龙套。
- 支持清空角色音色，回退默认规则。
- 复用现有书籍音色与角色音色存储，不新增表。

验收：
- 切换 Provider 后，音色库按 Provider 隔离展示。
- Provider 选择只包含阿里云和豆包。
- 点击任意音色卡片的试听按钮可用试听文本试听。
- 搜索名称、已分配角色名或声音属性可以过滤列表。
- 主列表不展示试听文本正文。
- 主列表不展示请求参数值。
- 已分配音色显示在未分配音色上方。
- 已分配音色按旁白、默认对白、主角、龙套、主要配角、次要配角顺序显示。
- 分配音色给角色后，角色朗读使用该音色。
- 同一个音色分配给多个角色后，这些角色都使用该音色。
- 同一个角色再次分配其他音色后，新音色替换旧音色。
- 分配音色给书籍默认旁白/对白后，默认朗读使用该音色。
- 修改龙套音色后，跟随该龙套的次要配角使用新音色。

## MVP 8.1：角色侧音色选择

目标：从书籍角色列表 / 角色编辑页为当前角色选择音色，替代手动输入自定义音色 ID 的主要操作路径。

范围：
- 依赖 MVP8 的阿里云 / 豆包中文音色库。
- 在角色列表或角色编辑页增加“选择音色”入口。
- 打开轻量音色选择器，只给当前角色选择音色。
- UI 显示 displayName、已分配 roleName、声音属性，不显示请求参数值。
- 支持搜索、试听、选择和清空自定义音色。
- 选择后写入当前书当前角色：`BookReadAloudRole.voices[ttsEngine] = requestValue`。
- 阿里云请求传 `voice = requestValue`，豆包请求传 `voice_type = requestValue`。
- 不做批量分配，不设置书籍旁白 / 默认对白，不管理其他 TTS。
- 音色选择结果按书籍隔离。

验收：
- 从角色编辑页点击“选择音色”可以打开轻量音色选择器。
- 选择音色后，当前角色朗读使用该音色。
- 同一个音色可以被多个角色选择。
- 同一角色再次选择其他音色后，新音色覆盖旧音色。
- 清空角色自定义音色后，角色回退默认音色规则。
- 不同书籍同名角色的音色互不影响。

## MVP 9：剧本与角色导入导出

目标：把导入导出独立成最后一步。

范围：
- 导出当前章剧本 JSON。
- 导出/导入书籍角色档案 JSON。
- 不导出 API Key 和小说正文。

验收：
- 清空缓存后导入剧本可直接朗读。
- 导入角色档案后角色和音色恢复。

## MVP 10：缓存与调试管理

目标：提供缓存清理、空间管理和排错信息查看能力，作为 V1 上线前的维护工具集合。

范围：
- 清理音频缓存。
- 清理 segment/AI 剧本缓存。
- 保留角色配置重新生成缓存。
- 查看当前段 segment JSON。
- 查看当前段 TTS 请求摘要。
- 查看 AI 原始返回和映射结果。
- 查看角色匹配结果和错误日志。

验收：
- 清理音频缓存不影响角色档案。
- 清理剧本缓存不影响 HTTP_TTS 登录配置。
- 出错时能定位是规则分段、AI 解析、角色匹配还是 TTS 请求问题。

## V1 最小可上线 MVP 依赖关系

```
MVP 1-3.2 (已完成)
    ↓
MVP 3.3 (书籍级音色管理)
    ↓
MVP 3.4 (书籍级角色管理)
    ↓
MVP 4 / 4.1 (Segment 缓存与刷新)
    ↓
MVP 5 / 5.1 (音频预取)
    ↓
MVP 6A / 6B (AI 配置与 Prompt)
    ↓
MVP 6C / 6D (AI 解析与缓存)
    ↓
MVP 7 (角色匹配与跨章节音色复用)
    ↓
MVP 7.1 (未匹配角色自动归档)
    ↓
MVP 8 (音色管理)
    ↓
MVP 8.1 (角色侧音色选择)
    ↓
MVP 9 (剧本与角色导入导出)
    ↓
MVP 10 (缓存与调试管理)
    ↓
可上线 V1
```

---

## V1 MVP 执行计划拆分（基于审查方案）

验证原则：从 MVP7 开始，实施完成后除静态检查外，还需要执行 `./gradlew :app:assembleDebug` 做 Gradle 编译验证。

### 完成状态

| MVP | 功能 | 状态 | 分支 |
|---|---|---|---|
| 1 | 阿里云 SSML TTS 基线 | ✅ 完成 | mvp1 |
| 2 | Segment 与位置映射 | ✅ 完成 | mvp2 |
| 3 | 规则分角色通用 Segment / 阿里云 SSML | ✅ 完成 | mvp3 |
| 3.1 | 豆包 HTTP_TTS 集成 + 分角色音色 | ✅ 完成 | mvp3.1 |
| **3.2** | 通用 Segment 去 Provider 渲染字段 | ✅ 完成 | mvp3.2 |
| **3.3** | 书籍级音色管理 | ✅ 完成 | mvp3.3 |
| **3.4** | 书籍级角色管理 | ✅ 完成 | mvp3.4 |
| **4** | Segment 缓存表 | ✅ 完成 | mvp4.0 |
| **4.1** | 缓存失效与手动刷新 | ✅ 完成 | mvp4.1 |
| **5** | 当前章节音频预取 | ✅ 完成 | mvp5 |
| **5.1** | 下一章预处理 | ✅ 完成 | mvp5.1 |
| **6A** | AI 服务配置 | ✅ 完成 | mvp5.1 |
| **6B** | Prompt 管理 | ✅ 已完成 | - |
| **6C** | AI 单章解析最小闭环 | ✅ 已完成 | - |
| **6D** | AI 结果缓存接入 | ✅ 已完成 | mvp6d |
| **7** | 角色匹配与跨章节音色复用 | ⏳ 计划中 | - |
| **7.1** | 未匹配角色自动归档 | ⏳ 计划中 | - |
| **8** | 音色管理 | ⏳ 计划中 | - |
| **8.1** | 角色侧音色选择 | ⏳ 计划中 | - |
| **9** | 剧本与角色导入导出 | ⏳ 计划中 | - |
| **10** | 缓存与调试管理 | ⏳ 计划中 | - |

### V1 最小可上线条件

执行顺序：MVP 1-3.3（已完成）→ MVP 3.4 → MVP 4 → MVP 4.1 → MVP 5 → MVP 5.1 → MVP 6A → MVP 6B → MVP 6C → MVP 6D → MVP 7 → MVP 7.1 → MVP 8 → MVP 8.1 → MVP 9 → MVP 10

**关键路径：**
1. MVP 3.3 提供书籍级默认音色管理。
2. MVP 3.4 提供书籍级角色管理。
3. MVP 4/4.1 提供 segment 缓存和刷新能力。
4. MVP 5/5.1 提供当前章和下一章预取。
5. MVP 6A/6B 提供 AI 服务配置和 Prompt 管理。
6. MVP 6C/6D 接入 AI 解析并缓存结果。
7. MVP 7 复用 MVP3.4 角色档案，提供已有角色匹配与跨章节音色复用。
8. MVP 7.1 处理未匹配角色自动归档。
9. MVP 8 提供音色管理。
10. MVP 8.1 提供角色侧音色选择。
11. MVP 9 提供剧本与角色导入导出。
12. MVP 10 提供缓存管理和调试。

---

## MVP 4 及之后的详细设计

（详见下文各 MVP 章节）

## 推荐开发顺序

1. ✅ MVP 1：阿里云 SSML TTS 基线（已完成）
2. ✅ MVP 2：Segment 与纯文本位置映射基础（已完成）
3. ✅ MVP 3：规则分角色通用 Segment / 阿里云 SSML（已完成）
4. ✅ MVP 3.1：豆包 HTTP_TTS 集成 + 分角色音色（已完成）
5. ✅ MVP 3.2：通用 Segment 去 Provider 渲染字段（已完成）
6. ✅ MVP 3.3：书籍级音色管理（已完成）
7. ✅ MVP 3.4：书籍级角色管理（已完成）
8. ✅ MVP 4：Segment 缓存表（已完成）
9. ✅ MVP 4.1：缓存失效与手动刷新（已完成）
10. ✅ MVP 5：当前章节音频预取（已完成）
11. ✅ MVP 5.1：下一章预处理（已完成）
12. ✅ MVP 6A：AI 服务配置（已完成）
13. ✅ MVP 6B：Prompt 管理
14. ✅ MVP 6C：AI 单章解析最小闭环
15. ✅ MVP 6D：AI 结果缓存接入（已完成）
16. ⏳ MVP 7：角色匹配与跨章节音色复用
17. ⏳ MVP 7.1：未匹配角色自动归档
18. ⏳ MVP 8：音色管理
19. ⏳ MVP 8.1：角色侧音色选择
20. ⏳ MVP 9：剧本与角色导入导出
21. ⏳ MVP 10：缓存与调试管理

---

## V1 最小可上线版本

完成上述 MVP 后可以上线 V1 版本：

**核心能力：**
- 阿里云 SSML 能稳定朗读
- Segment 与纯文本位置映射稳定
- 豆包 TTS 可用（用户自配）
- AI 能识别旁白和多个角色
- 用户可维护、锁定角色，并管理角色音色
- 角色档案可导出导入
- AI 剧本和音频可缓存复用
- 边读边处理，无需离线预处理
- AI 失败自动降级到规则朗读

**不包含（推迟到 V1.1/V2）：**
- ❌ 音效、环境声、背景音乐编排
- ❌ 背景图片生成和视频导出
- ❌ IndexTTS 2 集成
- ❌ AI 生成参考音色
