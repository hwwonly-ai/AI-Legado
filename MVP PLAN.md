# 多 TTS Provider 分角色朗读 MVP 拆分（阿里云 SSML + 豆包 TTS）

## 规划调整原则

加入豆包 TTS 后，朗读链路不再只围绕“生成阿里云 SSML”设计，而应拆成两层：

- 通用层：小说文本切段、原文位置映射、speaker/voice/emotion/styleTag/context 元数据、缓存 key 和播放进度。
- Provider 层：将通用 segment 渲染为不同 TTS 平台的请求。
  - 阿里云：渲染为 `<speak><voice><emotion>...</emotion></voice></speak>`。
  - 豆包：渲染为 `text + voiceType + emotion + contextTexts + useTagParser + instruction`。

后续 MVP 应避免把“分角色”“情绪识别”“进度映射”“倒回重听”绑定到某一个 TTS 平台。阿里云 SSML 和豆包 TTS 都应消费同一套通用 segment。

引入 Unitale 项目的经验后，后续 AI 能力应再增加一层“AI 朗读剧本”：

- AI 剧本层：将小说片段解析为旁白、具体角色、情绪、强度、停顿、音效、BGM、滤波器和可选图片提示词等结构化事件。
- 朗读段仍是播放链路的最小单位，AI 剧本中的 `dialogue` 事件会转换为通用 `ReadAloudSegment`。
- 音效、BGM、环境音和图片不直接塞进 TTS 文本，应作为独立的声音/视觉事件进入后续编排层。
- AI 输出必须保留原文位置映射，用于阅读进度、选中朗读、倒回重听和章节缓存。
- 用户对角色、音色、别名或声音事件做过一次修正后，后续章节应优先沿用用户修正。

产品模式分三档推进：

- 普通朗读：保持现有系统 TTS/在线 TTS，不进入 AI 剧本链路。
- AI 分角色朗读：识别旁白、具体角色、情绪、停顿和角色音色，这是优先上线目标。
- AI 沉浸朗读：在分角色朗读基础上加入音效、环境音、BGM 和可选图片。

## MVP 1：阿里云 SSML TTS 基线优化

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
- 已移除 MiniMax 默认 HTTP TTS 配置，当前 MVP1 路线只保留阿里云 SSML TTS。
- 已移除“阿里云标签解析”手动开关：
  - 删除设置页 `pref_config_aloud.xml` 中的 `ssmlTagParse` 开关。
  - 删除 `PreferKey.ssmlTagParse`。
  - 删除 `AppConfig.ssmlTagParse`。
  - 后续 MVP 不应再依赖该开关判断 SSML。
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
  - 支持可选 `VoiceName`，未填写时默认 `zhifeng_emo`。
  - 请求固定 `ssml: true`。
  - 若最终朗读文本已经包含 `<voice>` 或 `<speak ... voice=...>`，配置 JS 不再追加顶层默认 `voice_name`，避免覆盖标签内音色。
  - 配置 JS 会记录 `aliyun_ssml_has_tag_voice`、`aliyun_ssml_use_default_voice` 和短预览，便于排查音色未生效问题。
  - 不再在配置 JS 中使用 `hasSSMLTag` 判断。
  - 是否普通文本预处理、是否已有 SSML，由 Kotlin 代码统一决定。
- 测试文本约定：
  - `Test/测试文本.txt` 已改为与 `Test/chapter_0275_ssml.txt` 一致的标准结构：
    - `<speak>`
    - `<voice name="zhifeng_emo">`
    - `<emotion category="neutral" intensity="1.0">文本</emotion>`
    - `</voice>`
    - `</speak>`
  - 后续 SSML 测试应优先使用 `<speak><voice name="..."><emotion ...>...</emotion></voice></speak>`，不再依赖 `<speak voice="...">` 作为测试输入。
  - 如果 APP 中已经导入过旧测试文本，需要重新导入或刷新章节缓存，否则日志可能显示 `chapterHasSSML=false`、`readAloudContent=false`，说明服务拿到的是旧的纯文本缓存。
- 已验证：
  - `httpTTS.json` JSON 格式校验通过。
  - `git diff --check` 通过。
- 未完成/注意：
  - 本地 Gradle 编译未成功执行，原因是当前环境访问 `~/.gradle` wrapper 缓存锁需要提权且审批超时。
  - MVP1 只实现阿里云 SSML 基线和自动识别，尚未实现规则分角色、AI 识别、结构化剧本缓存和实时预取队列。

## MVP 2：SSML Segment 与纯文本位置映射基础

目标：建立后续规则分角色、剧本缓存、实时预取、AI 识别和光标同步都共用的最小 segment/mapping 基础。

内容：
- 定义内存态 segment：
  - 原文纯文本 `text`
  - SSML 内容 `ssml`
  - 起始位置 `startPosition`
  - 纯文本长度 `plainLength`
  - 角色 `speaker`
  - 情绪 `emotion`
  - 音色 `voice`
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
  - 新增 `ReadAloudSegment`，字段为 `text`、`ssml`、`startPosition`、`plainLength`、`speaker`、`emotion`、`voice`。
  - 当前 MVP2 只建立内存态基础，不做落库、不做剧本缓存、不接 AI。
- `BaseReadAloudService` 当前行为：
  - 新增 `readAloudSegments`，由 `rebuildContentPositionMap()` 与旧的 `contentStartPositions` / `contentPlainLengths` 同步生成。
  - `contentStartPosition()` / `contentPlainLength()` 优先读取 segment，旧数组仍保留作为兼容输出。
  - segment 的 `text` 使用 `stripReadAloudTags()` 后的纯文本，`ssml` 保存当前段原始带标签内容。
  - 当章节存在 `readAloudContent` 且当前朗读列表包含标签时，映射从章节纯文本开头搜索，避免全章 SSML 块被当前页起点截断影响。
  - 已从 `<voice name="...">`、`<speak voice="...">`、`<emotion category="...">` 中提取基础 `voice` / `emotion` 元数据。
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

## MVP 3：规则分角色生成 SSML

目标：先不用 AI，通过规则实现基础旁白/对白分角色朗读。

内容：
- 用规则识别：
  - 旁白
  - 引号对白
  - “某某说/问/笑道”等说话提示词
- 输出 MVP2 的 segment 结构。
- 将 segment 转成阿里云兼容 SSML。
- 默认音色：
  - `narratorVoice`
  - `dialogVoice`
- 识别失败时使用旁白音色。
- 不要求准确识别具体人物，只区分旁白和对白。

验收：
- 普通小说文本可以自动生成 SSML。
- 旁白和对白能使用不同音色。
- 对白段能被包裹为对应 SSML。
- 识别失败时回退旁白。
- 不影响光标定位和章节跳转。

已完成实现记录：
- 当前分支：`mvp3`。
- 已新增规则分段器：
  - 新增 `ReadAloudRuleSegmenter`，输入普通纯文本和起始纯文本位置，输出 MVP2 的 `ReadAloudSegment`。
  - `speaker` 固定为 `narrator` 或 `dialog`。
  - 默认旁白音色 `zhifeng_emo`，默认对白音色 `zhixiaoxia`。
  - 默认情绪 `neutral`；仅对 `_emo` 情绪音色输出 `<emotion category="neutral" intensity="1.0">`，普通音色只输出 `<voice>`。
- 当前规则行为：
  - 识别中文引号 `“...”`、`‘...’`、`「...」`、`『...』` 为对白。
  - 引号外文本作为旁白。
  - 遇到未闭合引号或异常嵌套引号时，整段回退为旁白 segment。
  - 不识别具体人物，不维护角色音色表。
- `HttpReadAloudService` 当前行为：
  - 阿里云 HTTP TTS + 普通纯文本 + 无已有 SSML 时，自动生成规则 SSML。
  - 文件播放和流式播放都会将规则 segments 的 `ssml` 作为播放列表，并复用 `startPosition/plainLength` 做进度映射。
  - 下一章预下载也使用同一规则生成 SSML，保证音频缓存 key 与正式播放的最终 TTS 内容一致。
  - 已有 SSML 章节继续走 MVP1/MVP2 的已有 SSML 解析和映射链路，不做二次规则分段。
  - 非阿里云 HTTP TTS 和系统 TTS 不启用规则 SSML。
- 已验证：
  - `git diff --check` 通过。
  - `kotlinc ReadAloudSegment.kt ReadAloudRuleSegmenter.kt` 独立语法编译通过。
- 未完成/注意：
  - 实测 `zhixiaoxia` 搭配 `<emotion>` 会触发阿里云 `Engine return error code: 423`，已改为普通音色不包 emotion。
  - 本地 Gradle 编译仍未成功执行：默认 `~/.gradle` wrapper 缓存锁受权限限制且提权审批超时；改用工作区 `.gradle` 会触发 Gradle 9.4.1 下载，但当前环境无法解析 `services.gradle.org`。
  - MVP3 只完成规则旁白/对白 SSML 生成，不包含剧本缓存、实时预取队列、AI 识别或用户可配置音色。

## MVP 3.1：通用朗读段与豆包渲染基础

目标：将 MVP3 中绑定阿里云 SSML 的分段结果抽象成通用朗读段，让阿里云和豆包语音合成模型 2.0 可以复用同一套分段、角色和原文位置映射。

内容：
- 保留 MVP3 的旁白/对白规则分段能力。
- 将 `ReadAloudSegment` 扩展为平台无关的朗读段：
  - 原文文本 `text`
  - 阿里云兼容 SSML `ssml`
  - 原文起始位置 `startPosition`
  - 原文结束位置 `endPosition`
  - 纯文本长度 `plainLength`
  - 角色标识 `speaker`
  - 角色名称 `speakerName`
  - 情绪 `emotion`
  - 平台音色 `voice`
  - 语音标签 `styleTag`
- 新增统一角色常量：
  - `narrator`
  - `dialog`
- 将阿里云 SSML 输出从 `ReadAloudRuleSegmenter` 中拆出，改由 `AliyunSsmlRenderer` 负责。
- 新增豆包渲染基础：
  - `DoubaoTtsRequest`
  - `DoubaoVoiceConfig`
  - `DoubaoTtsRenderer`
  - `DoubaoNovelTtsPlanner`
- 豆包请求规划支持：
  - 旁白/对白映射不同 `voiceType`
  - 情绪字段 `emotion`
  - 可选语音指令 `instruction`
  - 可选语音标签 `styleTag`
  - 上下文窗口 `contextTexts`
  - 原文位置 `rawStartPosition/rawEndPosition`
  - 缓存 key seed
- 当前阶段只做模型层和渲染层，不直接接入豆包网络请求和播放链路。

验收：
- MVP3 原有阿里云规则分角色行为保持兼容。
- 普通文本仍能生成阿里云 `<speak><voice>...</voice></speak>` SSML。
- 豆包规划器能基于同一段落生成 `DoubaoTtsRequest`。
- 豆包请求中不包含阿里云 `<emotion>` / `<voice>` SSML 标签。
- 豆包请求保留原文起止位置，用于后续朗读进度、倒回和缓存。
- 上下文承接只进入 `contextTexts`，不会被拼进当前朗读文本。

已完成实现记录：
- 当前分支：`mvp3.1`。
- 已创建并切换到分支 `mvp3.1`。
- 已扩展 `ReadAloudSegment`：
  - 新增 `speakerName`。
  - 新增 `styleTag`。
  - 新增计算属性 `endPosition`。
- 已新增 `ReadAloudRole`，统一管理 `narrator/dialog` 常量。
- 已新增通用渲染接口 `ReadAloudSegmentRenderer<T>`。
- 已新增 `AliyunSsmlRenderer`：
  - 继续输出阿里云兼容 `<speak><voice name="...">...</voice></speak>`。
  - `_emo` 音色继续包裹 `<emotion category="...">`。
  - 普通音色不包 `<emotion>`，保持 MVP3 对 `zhixiaoxia` 的兼容修复。
- 已改造 `ReadAloudRuleSegmenter`：
  - 默认仍生成阿里云 SSML，保证现有 `HttpReadAloudService` 行为不变。
  - 新增 `renderAliyunSsml` 参数；传 `false` 时只生成通用 segment，不生成阿里云 SSML。
  - `speakerName` 当前规则值为 `旁白` 或 `对白`。
- 已新增豆包模型层：
  - `DoubaoTtsRequest` 保存豆包合成所需的文本、音色、情绪、上下文、标签开关、原文位置和缓存 seed。
  - `DoubaoVoiceConfig` 保存旁白音色、对白音色、语音标签开关、语音指令和上下文窗口大小。
  - `DoubaoTtsRenderer` 将通用 segment 渲染成豆包请求。
  - `DoubaoNovelTtsPlanner` 将章节文本切成通用 segment，再批量生成豆包请求。
- 已新增豆包 API 测试客户端：
  - `DoubaoApiConfig` 保存 `AppID`、`AccessToken`、`cluster`、音频格式、采样率、语速、音量、音调和 additions 默认配置。
  - `DoubaoTtsClient` 接入火山 `https://openspeech.bytedance.com/api/v1/tts` HTTP 非流式接口，用于后续真实账号测试。
  - `DoubaoTtsClient.synthesize()` 输入 `DoubaoTtsRequest`，输出 `DoubaoTtsAudio`。
  - `DoubaoTtsClient.synthesizeStream()` 可直接返回音频 `InputStream`，便于后续接入朗读服务。
  - 请求体按 V1 非流式接口生成 `app/user/audio/request` 四段结构，`operation` 固定为 `query`。
  - 鉴权 Header 使用 `Authorization: Bearer; <AccessToken>`。
  - 响应 `data` 字段按 base64 解码为音频 bytes。
  - `contextTexts`、`useTagParser`、`explicitLanguage`、Markdown/emoji 过滤配置会写入 `request.extra_param`。
- 已验证：
  - `./gradlew :app:compileAppDebugKotlin` 编译通过。
  - 编译仅出现既有 `HttpReadAloudService.kt` 的非空断言 warning，与 MVP3.1 改动无关。
- 已新增可自动化验证：
  - `DoubaoTtsClientTest.buildPayloadKeepsDoubaoRequestFreeFromAliyunSsml`
  - `DoubaoTtsClientTest.buildPayloadAddsContextTextsAndTagParserIntoExtraParam`
  - `DoubaoTtsClientTest.authorizationHeaderAddsBearerPrefixOnce`
- 未完成/注意：
  - 豆包 V1 HTTP 非流式客户端已完成，但尚未接入 UI 和主朗读播放链路。
  - V1 HTTP 非流式接口用于 MVP3.1 测试；正式低延迟朗读仍建议后续接 V3 HTTP Chunked / WebSocket。
  - 尚未把豆包请求接入 `HttpReadAloudService` 或新的专用朗读服务。
  - 尚未实现具体人物识别；当前仍为旁白/对白双角色。
  - 尚未实现用户 UI 配置豆包 `AppID`、`AccessToken`、音色、情绪和语音标签开关。

可验证测试案例：
- 用例 1：阿里云兼容回归，普通旁白。
  - 输入：`夜色渐深，院子里只剩下风声。`
  - 调用：`ReadAloudRuleSegmenter.segment(text, 0)`。
  - 期望：
    - 返回 1 个 segment。
    - `speaker == "narrator"`。
    - `speakerName == "旁白"`。
    - `startPosition == 0`。
    - `plainLength == text.length`。
    - `ssml` 包含 `<speak><voice name="zhifeng_emo">`。
    - `ssml` 包含 `<emotion category="neutral" intensity="1.0">`。
- 用例 2：阿里云兼容回归，旁白 + 对白。
  - 输入：`她停下脚步，说：“你终于来了。”风从门缝里吹进来。`
  - 调用：`ReadAloudRuleSegmenter.segment(text, 10)`。
  - 期望：
    - 返回 3 个 segment：旁白、对白、旁白。
    - 第一段 `speaker == "narrator"`，`startPosition == 10`。
    - 第二段 `speaker == "dialog"`，`voice == "zhixiaoxia"`。
    - 第二段 `ssml` 包含 `<voice name="zhixiaoxia">`。
    - 第二段 `ssml` 不包含 `<emotion`，避免普通音色触发阿里云 423。
    - 三段 `startPosition/plainLength` 能覆盖各自原文片段。
- 用例 3：豆包通用 segment，不生成阿里云 SSML。
  - 输入：`他说：“别怕。”她点了点头。`
  - 调用：`ReadAloudRuleSegmenter.segment(text, 0, renderAliyunSsml = false)`。
  - 期望：
    - 返回旁白、对白、旁白 segment。
    - 所有 segment 的 `ssml` 都为空。
    - `text/startPosition/plainLength/speaker/speakerName` 正常。
- 用例 4：豆包请求规划，旁白/对白音色不同。
  - 输入：`雨停了。“我们走吧。”他低声说。`
  - 配置：`DoubaoVoiceConfig(narratorVoiceType = "BV700_V2_streaming", dialogVoiceType = "BV001_V2_streaming")`。
  - 调用：`DoubaoNovelTtsPlanner(DoubaoTtsRenderer(config)).buildRequests(text, 100)`。
  - 期望：
    - 旁白请求 `voiceType == "BV700_V2_streaming"`。
    - 对白请求 `voiceType == "BV001_V2_streaming"`。
    - 请求 `text` 不包含 `<speak>`、`<voice>`、`<emotion>`。
    - 请求 `rawStartPosition/rawEndPosition` 对应原文位置。
- 用例 5：豆包上下文承接。
  - 输入：`他低下头。“对不起。”她没有回答。`
  - 配置：`contextWindowSize = 2`。
  - 调用：同用例 4。
  - 期望：
    - 第 1 个请求 `contextTexts` 为空。
    - 第 2 个请求 `contextTexts` 包含前一个旁白片段，格式类似 `旁白：他低下头。`。
    - 第 3 个请求 `contextTexts` 至多包含前 2 个片段。
    - 上下文文本不会拼入当前请求的 `text`。
- 用例 6：豆包语音指令和语音标签开关。
  - 构造 segment：`text = "你骗我！"`，`speaker = "dialog"`，`styleTag = "生气地"`。
  - 配置 A：`enableVoiceTags = false`，`instruction = "以自然有声书风格朗读"`。
  - 期望 A：请求 `text` 以 `[#以自然有声书风格朗读]` 开头，但不包含 `[生气地]`。
  - 配置 B：`enableVoiceTags = true`。
  - 期望 B：请求 `text` 包含 `[生气地]你骗我！`。
- 用例 7：豆包 V1 HTTP payload 结构。
  - 输入：用 `DoubaoNovelTtsPlanner` 生成一段对白请求。
  - 调用：`DoubaoTtsClient.buildPayload(request, "fixed-req-id")`。
  - 期望：
    - 顶层包含 `app/user/audio/request`。
    - `app.appid` 等于配置的 `AppID`。
    - `audio.voice_type` 等于当前 segment 的 `voiceType`。
    - `request.reqid == "fixed-req-id"`。
    - `request.operation == "query"`。
    - `request.text` 不包含阿里云 `<speak>`、`<voice>`、`<emotion>`。
- 用例 8：豆包 V1 鉴权头。
  - 输入：`DoubaoApiConfig(appId = "app", accessToken = "token")`。
  - 期望：`authorizationHeader() == "Bearer; token"`。
  - 输入：`accessToken = "Bearer; token"`。
  - 期望：不会重复追加 `Bearer;`。
- 用例 9：豆包真实账号冒烟测试。
  - 前提：提供真实 `AppID`、`AccessToken`、开通可用的 `voiceType`。
  - 调用：`DoubaoTtsClient.synthesize(request)`。
  - 期望：
    - 返回 `DoubaoTtsAudio.bytes` 非空。
    - `contentType == "audio/mpeg"`。
    - 将 bytes 写为 `.mp3` 后可播放。
  - 注意：该用例需要真实火山账号和网络，不应放入默认 CI。

## MVP 4：通用剧本缓存与音频缓存复用

目标：让已生成的通用 segment 剧本、阿里云 SSML 和豆包请求计划可复用，返回章节不重复处理。

内容：
- 缓存结构化通用 segment 剧本。
- 缓存 provider 渲染结果：
  - 阿里云 SSML
  - 豆包请求计划
- 缓存 key：
  - `bookId`
  - `chapterIndex`
  - `contentHash`
  - `ruleVersion/promptVersion`
  - `voiceProfileVersion`
- Provider 渲染缓存 key 还需要包含：
  - `provider`
  - `rendererVersion`
  - `voiceName/voiceType`
  - `emotion`
  - `instruction`
  - `useTagParser`
- 音频缓存继续按最终送入 TTS 的内容和 TTS 参数计算。
- 返回已处理章节时直接读取缓存，不重复预处理。
- 原文、规则版本、提示词版本、音色配置变化时重新生成。
- 剧本缓存不改变原阅读排版，只服务朗读链路。

验收：
- 第一次读章节时生成通用 segment 剧本。
- 阿里云路径能从通用剧本渲染 SSML。
- 豆包路径能从通用剧本渲染请求计划。
- 返回已处理章节时不重新预处理。
- 原文变化后能重新生成。
- 剧本缓存不会影响原阅读排版。
- 已生成音频可按最终 TTS 内容和参数复用。

## MVP 5：实时预处理与预取队列

目标：从“离线预处理整本小说”改为“边读边处理 + 后台预取”。

内容：
- 启动朗读时：
  - 当前 1-3 个 segment 优先处理。
  - 未处理完成时可先用 MVP3 规则结果播放。
- 后台任务：
  - 预处理当前章节剩余 segment。
  - 预处理下一章。
  - 可预生成 TTS 音频。
- 支持：
  - 跳章取消无用任务。
  - 网络失败重试。
  - 缓存命中跳过规则/AI 和 TTS。
  - App 退出后保留已完成缓存。
- 不再要求用户先整本预处理再导入。

验收：
- 点朗读后不长时间等待。
- 当前段能尽快开始播放。
- 后续片段能提前准备。
- 跳到其他章节后旧任务能停止。
- 返回已处理章节时直接用缓存。

## MVP 6：Unitale 式 AI 剧本分析与角色档案

目标：参考 Unitale 的 Prompt 设计，用 AI 将当前章节窗口解析为可缓存、可修正、可回放的朗读剧本，再转换成通用 segment。

内容：
- 接入 AI API 或后端代理。
- 输入内容包含：
  - 当前章节窗口文本。
  - 前文短摘要。
  - 本书已知角色档案。
  - 已有角色别名。
  - 当前可用情绪列表。
  - 当前可用音色列表。
- AI 输出结构化 JSON，不直接输出 SSML 或具体 TTS 请求。
- `dialogue` 事件字段：
  - `text`
  - `startPosition`
  - `endPosition`
  - `speaker`
  - `speakerName`
  - `speakerAlias`
  - `emotion`
  - `intensity`
  - `styleTag`
  - `breakDuration`
  - `confidence`
- 角色识别规则参考 Unitale：
  - 完整保留原文，不删改小说内容。
  - 引号内对白尝试识别具体说话人。
  - “他说”“她低声问”等说话提示语必须归入旁白。
  - 旁白统一标记为 `旁白`。
  - 连续旁白优先合并，过长旁白按句号拆分。
  - 不确定说话人时允许使用 `未知角色`，避免强行误判。
- 建立每本书的角色档案：
  - 角色 ID。
  - 显示名。
  - 别名列表。
  - 角色等级：旁白、主角、主要配角、次要配角、龙套、未知角色。
  - 性别/年龄段/声音描述。
  - 固定音色或自动音色策略。
  - 锁定状态。
  - 首次出现章节和最近出现章节。
- 音色策略：
  - 旁白可单独选择音色。
  - 主角、主要配角、次要配角可固定分配已有音色。
  - 龙套和未分配角色可从临时演员音色池选择，或由后续 IndexTTS 2 自动生成参考音色。
  - 用户锁定的角色音色不能被 AI 自动覆盖。
- AI 结果不绑定具体 provider。
- 通过 provider renderer 将 `dialogue` 事件转换为：
  - 阿里云 SSML。
  - 豆包 TTS 请求。
  - 后续 IndexTTS 2 请求。
- AI 失败时回退 MVP3 规则旁白/对白分段。

验收：
- 能识别旁白和主要角色。
- 同一角色跨章节复用同一个角色档案。
- 用户修正角色名、别名或音色后，后续章节优先沿用。
- 情绪和强度能映射到阿里云、豆包或 IndexTTS 2 的 provider 能力。
- AI 结果能进入 MVP4 剧本缓存和 MVP5 预取队列。
- AI 失败不影响基础朗读。

## MVP 6.5：声音事件、音效与 BGM 编排

目标：在 AI 分角色朗读稳定后，参考 Unitale 的声音事件模型，加入音效、环境音和背景音乐，但默认保持克制。

内容：
- AI 剧本新增非台词事件：
  - `sfx`：瞬时音效。
  - `ambient`：环境音。
  - `bgm`：背景音乐播放/停止/切换。
  - `pause`：独立停顿。
- `sfx` 可挂在 `dialogue` 事件上：
  - `name`
  - `position`
  - `volume`
  - `confidence`
- `position` 参考 Unitale，表示台词音频内的相对位置：
  - `0.0`：台词开始。
  - `0.5`：台词中段。
  - `1.0`：台词结束。
- `bgm` 和 `ambient` 作为独立事件，按场景持续播放。
- 音效与 BGM 必须来自用户已安装或启用的素材库，AI 不得编造素材名称。
- 用户可选择声音强度：
  - 关闭。
  - 克制。
  - 标准。
  - 电影感。
- 默认策略：
  - 一个场景最多一个主要环境音。
  - BGM 按场景切换，不按每句台词频繁切换。
  - 说话时自动降低 BGM 音量。
  - 普通动作不强制插入音效。
  - 用户可禁用某个音效或某类声音。
- 失败或资源缺失时跳过声音事件，不影响人声朗读。

验收：
- 人声朗读不受音效/BGM 失败影响。
- 当前段可播放台词内音效。
- BGM 能按剧本播放、停止和切换。
- 用户能全局关闭音效或 BGM。
- 导航、跳段、跳章时旧声音事件能停止。

## MVP 6.6：可选背景图片与有声视频方向

目标：为后续沉浸朗读和有声视频导出预留视觉事件，但不作为首批 AI 分角色朗读的上线条件。

内容：
- AI 剧本可选输出 `bgImage` 事件：
  - `imagePrompt`
  - `sceneId`
  - `relatedCharacters`
  - `position`
- 图片生成不在第一阶段内置，可先支持用户手动添加图片。
- 朗读播放时，图片仅作为场景背景或章节插图。
- 后续可扩展为：
  - 字幕。
  - 背景图切换。
  - 有声视频导出。

验收：
- 未启用图片时不影响朗读。
- 启用后能在剧本中保存图片提示词或本地图片引用。
- 图片事件能与章节缓存一起保存和刷新。

## MVP 7：用户配置、管理与调试

目标：让多 provider AI 分角色朗读能力可长期使用、管理和排错。

内容：
- 阿里云 TTS 配置：
  - AppKey
  - AccessKeyId
  - AccessKeySecret
  - 默认 voice
- 豆包 TTS 配置：
  - AppID
  - AccessToken
  - 默认旁白 voiceType
  - 默认对白 voiceType
  - 默认 emotion
  - 语音指令 instruction
  - 是否启用语音标签 useTagParser
  - 上下文窗口大小 contextWindowSize
- IndexTTS 2 配置：
  - Endpoint
  - Token
  - 默认旁白音色
  - 默认对白音色
  - 参考音频管理
  - 情绪向量策略
- AI 配置：
  - API Key
  - 模型
  - promptVersion
  - 角色识别 Prompt
  - 声音事件 Prompt
- 角色音色表：
  - 旁白
  - 男主
  - 女主
  - 老人
  - 儿童
  - 自定义角色
  - 龙套音色池
  - 未知角色默认音色
  - 角色别名
  - 角色锁定状态
- 声音素材库：
  - 瞬时音效
  - 环境音
  - BGM
  - 是否启用
  - 默认音量
  - 裁剪起止位置
- 图片/视觉事件：
  - 是否启用背景图片
  - 每章图片数量策略
  - 图片提示词缓存
  - 本地图片引用
- 缓存管理：
  - 清理通用剧本缓存
  - 清理 provider 渲染缓存
  - 清理 TTS 音频缓存
  - 清理声音事件缓存
  - 清理图片事件缓存
  - 手动刷新当前章节
  - 手动刷新整本
- 调试能力：
  - 查看当前片段 SSML
  - 查看当前片段豆包请求
  - 查看当前片段 IndexTTS 2 请求
  - 查看当前片段原文位置映射
  - 查看 AI JSON
  - 查看角色档案匹配结果
  - 查看声音事件时间线
  - 查看 TTS 错误日志

验收：
- 用户能修改默认音色。
- 用户能锁定角色音色和别名。
- 用户能关闭音效、BGM 或图片。
- 用户能刷新某章预处理结果。
- 用户能清理缓存。
- 出错时能看到可定位的日志。

## 推荐开发顺序

1. MVP 1：先稳定阿里云 SSML TTS。
2. MVP 2：建立 SSML Segment 与纯文本位置映射基础。
3. MVP 3：实现规则分角色。
4. MVP 3.1：抽象通用朗读段，并建立豆包渲染基础。
5. MVP 4：建立 SSML/豆包请求剧本缓存与音频缓存复用。
6. MVP 5：实现实时预处理和预取队列。
7. MVP 6：接入 Unitale 式 AI 剧本分析、角色档案和情绪识别。
8. MVP 6.5：加入声音事件、音效、环境音和 BGM 编排。
9. MVP 6.6：预留可选背景图片和有声视频方向。
10. MVP 7：完善配置、管理和调试。

## 最小可上线版本

建议做到：

- MVP 1
- MVP 2
- MVP 3
- MVP 3.1
- MVP 4
- MVP 5

也就是：

- 阿里云 SSML 能稳定朗读。
- Segment 与纯文本位置映射稳定。
- 旁白/对白能用不同音色。
- 同一套分段结果能渲染为阿里云 SSML 或豆包请求。
- 章节剧本和音频能缓存复用。
- 不需要整本离线预处理，可以边读边预处理。
