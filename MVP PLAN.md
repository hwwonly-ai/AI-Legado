# AI 分角色朗读 MVP 执行计划（V1）

本文档定义 MVP 1-7 的**技术实现细节、架构设计、依赖关系和验收标准**。
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

## MVP 3.4：书籍级角色管理

目标：在书籍维度维护角色列表，为后续 AI 角色识别和跨章节一致性打基础。

范围：
- 支持新增、编辑、删除角色。
- 支持角色名、别名、备注、绑定音色、是否锁定。
- 可从 MVP3.3 的书籍级音色中选择音色作为角色音色。
- 规则分段阶段仍只自动区分旁白/对白；具体人物匹配可先通过用户手动绑定完成。

验收：
- 用户可新增/编辑角色名和别名。
- 用户可给角色绑定音色并锁定。
- 同一本书重新朗读时角色配置仍保留。

注意：
- MVP3.4 是角色管理页面，不是豆包账号配置页面。
- AI 自动识别具体人物仍放在 MVP6B/6C。



## MVP 4：Segment 缓存表

目标：只完成 segment 剧本的 Room 持久化读写。

范围：
- 新增 segment 缓存表。
- 支持按书籍、章节、内容 hash 读写 `ReadAloudSegment` JSON。
- 不做预取、不做刷新 UI、不接 AI。

验收：
- 同一章节第二次朗读可读取缓存 segment。
- 内容 hash 改变时不会误用旧缓存。

## MVP 4.1：缓存失效与手动刷新

目标：把缓存失效规则和手动刷新独立出来。

范围：
- 支持按当前章清理 segment 缓存。
- 支持按整本书清理 segment 缓存。
- 日志能区分“命中缓存 / 重建缓存 / 手动刷新”。

验收：
- 手动刷新当前章后会重新生成 segment。
- 清理整本书缓存不影响 HTTP_TTS 登录配置和书籍角色配置。

## MVP 5：当前章节音频预取

目标：只优化当前章节播放中的后续段音频预取。

范围：
- 当前段播放时，后台预取后续 2-3 段音频。
- 跳章或停止朗读时取消当前预取任务。
- 不做下一章预处理。

验收：
- 播放第 1 段时能看到第 2-3 段开始预取。
- 快速跳章后旧预取任务被取消。

## MVP 5.1：下一章预处理

目标：独立实现下一章 segment 和音频预处理。

范围：
- 当前章接近结束时，后台准备下一章前几段。
- 预处理失败不影响当前播放。

验收：
- 当前章结束后，下一章首段可更快播放。
- 断网或失败时能回退普通实时请求。

## MVP 6A：AI 服务配置

目标：只完成 OpenAI-compatible AI 服务配置和连接测试。

范围：
- 配置 endpoint、apiKey、model、可选参数。
- 保存配置并提供连接测试。
- 不做 Prompt 编辑、不做章节解析。

验收：
- 配置保存后重启仍保留。
- 连接测试能返回成功或明确错误。

## MVP 6B：Prompt 管理

目标：独立完成 AI 解析 Prompt 的查看、编辑和恢复默认。

范围：
- 内置默认 Prompt。
- 支持用户编辑和恢复默认。
- 支持导入/导出 Prompt 文本。

验收：
- 修改 Prompt 后可保存。
- 导出后再导入内容一致。

## MVP 6C：AI 单章解析最小闭环

目标：AI 只解析当前章节为旁白/对白/具体角色 segment。

范围：
- 输入当前章节文本。
- 输出可映射回原文的 segment 列表。
- 只处理 dialogue/narrator，不处理 BGM、音效、图片。

验收：
- 能识别旁白和至少一个具体角色名。
- AI 输出映射失败时回退 MVP3 规则分段。

## MVP 6D：AI 结果缓存接入

目标：把 AI 解析结果接入 MVP4 缓存。

范围：
- AI 成功后写入 segment 缓存。
- 再次朗读同章优先读取缓存。
- Prompt 版本或内容 hash 变化时缓存失效。

验收：
- 同章第二次朗读不重复调用 AI。
- 修改 Prompt 后会重新解析。

## MVP 6E：角色档案持久化

目标：只建立每本书的角色档案表。

范围：
- 保存角色名、别名、音色 ID、锁定状态、描述。
- 不做自动匹配、不做导入导出。

验收：
- 用户新增角色后，重启仍保留。
- 锁定状态可保存。

## MVP 6F：角色匹配与跨章节音色复用

目标：让 AI 识别出的角色优先匹配书籍角色档案。

范围：
- 按角色名和别名匹配角色档案。
- 匹配成功时使用已绑定音色。
- 锁定角色不被 AI 覆盖。

验收：
- 连续两章同一角色使用同一音色。
- 手动锁定音色后，下一章不被覆盖。

## MVP 6G：当前段轻量修正

目标：只做当前朗读段的角色/音色情绪修正。

范围：
- 当前段可修改角色、音色、情绪。
- 支持“仅本段 / 本章 / 全书记住”三种范围。

验收：
- 修改当前段后立即影响重新合成。
- 选择“全书记住”会写入角色档案。

## MVP 6H：剧本与角色导入导出

目标：把导入导出独立成最后一步。

范围：
- 导出当前章剧本 JSON。
- 导出/导入书籍角色档案 JSON。
- 不导出 API Key 和小说正文。

验收：
- 清空缓存后导入剧本可直接朗读。
- 导入角色档案后角色和音色恢复。

## MVP 7：缓存管理

目标：只做缓存清理和空间管理。

范围：
- 清理音频缓存。
- 清理 segment/AI 剧本缓存。
- 保留角色配置重新生成缓存。

验收：
- 清理音频缓存不影响角色档案。
- 清理剧本缓存不影响 HTTP_TTS 登录配置。

## MVP 7.1：调试视图

目标：独立提供排错信息展示。

范围：
- 查看当前段 segment JSON。
- 查看当前段 TTS 请求摘要。
- 查看 AI 原始返回和映射结果。
- 查看角色匹配结果和错误日志。

验收：
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
MVP 6E / 6F (角色档案与匹配)
    ↓
MVP 6G / 6H (修正与导入导出)
    ↓
MVP 7 / 7.1 (缓存管理与调试)
    ↓
可上线 V1
```

---

## V1 MVP 执行计划拆分（基于审查方案）

### 完成状态

| MVP | 功能 | 状态 | 分支 |
|---|---|---|---|
| 1 | 阿里云 SSML TTS 基线 | ✅ 完成 | mvp1 |
| 2 | Segment 与位置映射 | ✅ 完成 | mvp2 |
| 3 | 规则分角色通用 Segment / 阿里云 SSML | ✅ 完成 | mvp3 |
| 3.1 | 豆包 HTTP_TTS 集成 + 分角色音色 | ✅ 完成 | mvp3.1 |
| **3.2** | 通用 Segment 去 Provider 渲染字段 | ✅ 完成 | mvp3.2 |
| **3.3** | 书籍级音色管理 | ⏳ 计划中 | - |
| **3.4** | 书籍级角色管理 | ⏳ 计划中 | - |
| **4** | Segment 缓存表 | ⏳ 计划中 | - |
| **4.1** | 缓存失效与手动刷新 | ⏳ 计划中 | - |
| **5** | 当前章节音频预取 | ⏳ 计划中 | - |
| **5.1** | 下一章预处理 | ⏳ 计划中 | - |
| **6A** | AI 服务配置 | ⏳ 计划中 | - |
| **6B** | Prompt 管理 | ⏳ 计划中 | - |
| **6C** | AI 单章解析最小闭环 | ⏳ 计划中 | - |
| **6D** | AI 结果缓存接入 | ⏳ 计划中 | - |
| **6E** | 角色档案持久化 | ⏳ 计划中 | - |
| **6F** | 角色匹配与跨章节音色复用 | ⏳ 计划中 | - |
| **6G** | 当前段轻量修正 | ⏳ 计划中 | - |
| **6H** | 剧本与角色导入导出 | ⏳ 计划中 | - |
| **7** | 缓存管理 | ⏳ 计划中 | - |
| **7.1** | 调试视图 | ⏳ 计划中 | - |

### V1 最小可上线条件

执行顺序：MVP 1-3.2（已完成）→ MVP 3.3 → MVP 3.4 → MVP 4 → MVP 4.1 → MVP 5 → MVP 5.1 → MVP 6A → MVP 6B → MVP 6C → MVP 6D → MVP 6E → MVP 6F → MVP 6G → MVP 6H → MVP 7 → MVP 7.1

**关键路径：**
1. MVP 3.3 提供书籍级默认音色管理。
2. MVP 3.4 提供书籍级角色管理。
3. MVP 4/4.1 提供 segment 缓存和刷新能力。
4. MVP 5/5.1 提供当前章和下一章预取。
5. MVP 6A/6B 提供 AI 服务配置和 Prompt 管理。
6. MVP 6C/6D 接入 AI 解析并缓存结果。
7. MVP 6E/6F 提供角色档案和跨章节音色复用。
8. MVP 6G/6H 提供修正与导入导出。
9. MVP 7/7.1 提供缓存管理和调试。

---

## MVP 4 及之后的详细设计

（详见下文各 MVP 章节）

## 推荐开发顺序

1. ✅ MVP 1：阿里云 SSML TTS 基线（已完成）
2. ✅ MVP 2：Segment 与纯文本位置映射基础（已完成）
3. ✅ MVP 3：规则分角色通用 Segment / 阿里云 SSML（已完成）
4. ✅ MVP 3.1：豆包 HTTP_TTS 集成 + 分角色音色（已完成）
5. ✅ MVP 3.2：通用 Segment 去 Provider 渲染字段（已完成）
6. ⏳ MVP 3.3：书籍级音色管理
7. ⏳ MVP 3.4：书籍级角色管理
8. ⏳ MVP 4：Segment 缓存表
9. ⏳ MVP 4.1：缓存失效与手动刷新
10. ⏳ MVP 5：当前章节音频预取
11. ⏳ MVP 5.1：下一章预处理
12. ⏳ MVP 6A：AI 服务配置
13. ⏳ MVP 6B：Prompt 管理
14. ⏳ MVP 6C：AI 单章解析最小闭环
15. ⏳ MVP 6D：AI 结果缓存接入
16. ⏳ MVP 6E：角色档案持久化
17. ⏳ MVP 6F：角色匹配与跨章节音色复用
18. ⏳ MVP 6G：当前段轻量修正
19. ⏳ MVP 6H：剧本与角色导入导出
20. ⏳ MVP 7：缓存管理
21. ⏳ MVP 7.1：调试视图

---

## V1 最小可上线版本

完成上述 MVP 后可以上线 V1 版本：

**核心能力：**
- 阿里云 SSML 能稳定朗读
- Segment 与纯文本位置映射稳定
- 豆包 TTS 可用（用户自配）
- AI 能识别旁白和多个角色
- 用户可修正和锁定角色
- 角色档案可导出导入
- AI 剧本和音频可缓存复用
- 边读边处理，无需离线预处理
- AI 失败自动降级到规则朗读

**不包含（推迟到 V1.1/V2）：**
- ❌ 音效、环境声、背景音乐编排
- ❌ 背景图片生成和视频导出
- ❌ IndexTTS 2 集成
- ❌ AI 生成参考音色
