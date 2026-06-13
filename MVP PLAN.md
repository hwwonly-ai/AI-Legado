# AI 分角色朗读 MVP 执行计划（V1）

本文档定义 MVP 1-7 的**技术实现细节、架构设计、依赖关系和验收标准**。
配套的 PRD（AI_IMMERSIVE_READING_PRD.md）定义**产品功能、用户体验和业务目标**。

---

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

---

## MVP 1-3.1：已完成（分支 mvp3.1）

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

## MVP 3.1：通用朗读段与豆包 HTTP_TTS 集成

目标：将 MVP3 中绑定阿里云 SSML 的分段结果抽象成通用朗读段，让阿里云和豆包可以复用同一套分段、角色和原文位置映射。

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
- 豆包 TTS 已完全集成到 HTTP_TTS 框架：
  - 在 `httpTTS.json` 中添加"豆包 TTS(新版)"条目（ID: -33）
  - 支持 API Key 鉴权（新版豆包控制台）
  - 支持自定义 Speaker、Resource ID
  - 用户通过"朗读引擎"列表配置，与其他 TTS（百度、阿里云）保持一致

验收：
- MVP3 原有阿里云规则分角色行为保持兼容。
- 普通文本仍能生成阿里云 `<speak><voice>...</voice></speak>` SSML。
- 豆包 TTS 通过 HTTP_TTS 框架可用（用户在"朗读引擎"列表中配置）。
- 豆包请求中不包含阿里云 `<emotion>` / `<voice>` SSML 标签。
- 豆包请求保留原文起止位置，用于后续朗读进度、倒回和缓存。

已完成实现记录：
- 当前分支：`mvp3.1`。
- 已扩展 `ReadAloudSegment` 与通用渲染：
  - 新增 `speakerName`、`styleTag` 等字段。
  - 已新增 `ReadAloudRole` 统一管理 `narrator/dialog` 常量。
  - 已新增通用渲染接口 `ReadAloudSegmentRenderer<T>`。
  - 已新增 `AliyunSsmlRenderer` 负责输出阿里云兼容 SSML。
- 豆包 TTS 架构改造（2026-06-13）：
  - ✅ 删除独立豆包引擎（DoubaoTtsEngine、DoubaoReadAloudService 等 13 个文件）
  - ✅ 将豆包完全集成到 HTTP_TTS 框架
  - ✅ 在 `httpTTS.json` 中添加"豆包 TTS(新版)"条目，支持 API Key 鉴权
  - ✅ 简化架构，减少代码重复和特殊情况处理
  - ✅ 用户配置方式与其他 TTS 保持一致
- 豆包 API 适配：
  - 新版豆包控制台鉴权：`X-Api-Key`（替代旧的 `X-Api-App-Key` + `X-Api-Access-Key`）
  - 支持流式响应（HTTP Chunked）处理
  - `additions` 字段为 JSON 字符串格式
  - 已验证集成测试通过：使用真实 API Key 成功调用豆包 API
- 已验证：
  - ✅ 编译成功（BUILD SUCCESSFUL）
  - ✅ 所有单元测试通过（0 failures, 0 errors）
  - ✅ APK 构建成功（assembleAppDebug）
- 未完成/注意：
  - 尚未实现具体人物识别；当前仍为旁白/对白双角色。
  - 豆包配置现在由用户在"朗读引擎"列表中进行，无需单独设置界面。

可验证测试案例（MVP 3.1 已删除，豆包完全集成到 HTTP_TTS 框架）：

- 用例 1：通用 Segment - 普通旁白（阿里云兼容）。
  - 输入：`夜色渐深，院子里只剩下风声。`
  - 调用：`ReadAloudRuleSegmenter.segment(text, 0)`。
  - **Segment 结构**（旁白）：
    ```
    speaker: “narrator”
    speakerName: “旁白”
    text: “夜色渐深，院子里只剩下风声。”
    ssml: <speak><voice name=”zhifeng_emo”><emotion category=”neutral” intensity=”1.0”>夜色渐深，院子里只剩下风声。</emotion></voice></speak>
    startPosition: 0
    plainLength: 文本长度
    ```

- 用例 2：通用 Segment - 旁白 + 对白（阿里云兼容）。
  - 输入：`她停下脚步，说：”你终于来了。”风从门缝里吹进来。`
  - 调用：`ReadAloudRuleSegmenter.segment(text, 0)`。
  - **Segment 结构**（3 个 segment）：
    1. **旁白段**：
       ```
       speaker: “narrator”
       speakerName: “旁白”
       text: “她停下脚步，说：”
       ssml: <speak><voice name=”zhifeng_emo”><emotion category=”neutral” intensity=”1.0”>她停下脚步，说：”</emotion></voice></speak>
       startPosition: 0
       plainLength: 对应长度
       ```
    2. **对白段**：
       ```
       speaker: “dialog”
       speakerName: “对白”
       text: “你终于来了。”
       ssml: <speak><voice name=”zhixiaoxia”>你终于来了。</voice></speak>  // 普通音色不包 <emotion>
       startPosition: (对应位置)
       plainLength: (对应长度)
       ```
    3. **旁白段**：
       ```
       speaker: “narrator”
       speakerName: “旁白”
       text: “风从门缝里吹进来。”
       ssml: <speak><voice name=”zhifeng_emo”><emotion category=”neutral” intensity=”1.0”>风从门缝里吹进来。</emotion></voice></speak>
       startPosition: (对应位置)
       plainLength: (对应长度)
       ```

- 用例 3：通用 Segment - 纯 segment（不生成阿里云 SSML，用于豆包/其他 TTS）。
  - 输入：`他说：”别怕。”她点了点头。`
  - 调用：`ReadAloudRuleSegmenter.segment(text, 0, renderAliyunSsml = false)`。
  - **Segment 结构**（3 个 segment，无 SSML）：
    ```
    旁白段: speaker=”narrator”, speakerName=”旁白”, ssml=””
    对白段: speaker=”dialog”, speakerName=”对白”, ssml=””
    旁白段: speaker=”narrator”, speakerName=”旁白”, ssml=””
    ```

- 用例 4：**豆包通过 HTTP_TTS 框架**（新版架构）。
  - 输入：`雨停了。”我们走吧。”他低声说。`
  - 配置位置：`httpTTS.json` 中的”豆包 TTS(新版)”条目
  - 配置 JavaScript 会：
    1. 从 `speaker` 字段判断是旁白还是对白
    2. 映射到不同的 voiceType：
       - 旁白 (`speaker == “narrator”`) → `zh_female_vv_uranus_bigtts`
       - 对白 (`speaker == “dialog”`) → `zh_female_vv_uranus_bigtts`（可配置不同）
    3. 构建豆包 API 请求：
       ```json
       {
         “user”: {“uid”: “legado_doubao_tts”},
         “req_params”: {
           “text”: “雨停了。”,
           “speaker”: “zh_female_vv_uranus_bigtts”,  // 根据 speaker 字段判断
           “audio_params”: {
             “format”: “mp3”,
             “sample_rate”: 24000,
             “speech_rate”: 0,
             “enable_timestamp”: false
           },
           “additions”: “{\”explicit_language\”: \”zh\”, \”disable_markdown_filter\”: true, \”disable_emoji_filter\”: true}”
         }
       }
       ```

- 用例 5：**Segment 旁白/对白区别**（关键）。
  - 同一章节的三个段落：
    ```
    Segment 1 (旁白): speaker=”narrator”, text=”雨停了。”
    Segment 2 (对白): speaker=”dialog”, text=”我们走吧。”
    Segment 3 (旁白): speaker=”narrator”, text=”他低声说。”
    ```
  - **重要**：Segment 本身无区别，区别在 `speaker` 字段
  - 当使用豆包时，HTTP_TTS 的 JavaScript 配置会根据 `speaker` 字段选择不同的 voiceType
  - 当使用阿里云时，`ssml` 字段已包含正确的 `<voice>` 标签

- 用例 6：豆包通过 HTTP_TTS 配置流程（最终用户体验）。
  - 1. 用户在”朗读引擎”列表中选择”豆包 TTS(新版)”
  - 2. 输入 API Key
  - 3. （可选）修改旁白/对白的 Speaker ID
  - 4. 朗读时，自动识别 segment 的 `speaker` 字段
  - 5. 调用豆包 API 发送请求（不同的 speaker 发送不同的 voiceType）

**注意**：
- Segment 结构在 MVP 3.1 中设计，现已删除独立的 DoubaoTtsRenderer/DoubaoTtsClientV3 等类
- 豆包现通过 HTTP_TTS 框架的 JavaScript 配置来实现
- 旁白和对白的区别通过 `speaker` 字段体现，而不是独立的音色对象
- 整个架构与阿里云、百度等 TTS 统一



## MVP 4：Segment 剧本持久化缓存

**目标：** 将内存态 segment 持久化到数据库，返回已处理章节时直接读取，不重复处理。

**具体实现：**
- 使用 Room 新增 `ReadAloudScriptEntity` 表（复用现有 AppDatabase）
  - 字段：`bookUrl`, `chapterIndex`, `contentHash`, `ruleVersion`, `promtVersion`, `segments`（JSON）, `createdAt`
- 缓存 key = `${bookUrl}#${chapterIndex}#${contentHash}`
- 内容变化（contentHash 改变）或规则版本变化时自动失效
- 序列化：segments list → JSON string（Gson）
- 音频缓存继续用现有机制（MD5 of TTS params + content）

**技术架构：**
```
ReadAloudSegment[] → Gson.toJson → DB.insert
↓（返回章节时）
DB.query by contentHash → Gson.fromJson → ReadAloudSegment[]
```

**单元测试：**
1. 相同输入两次调用 segmenter，第二次从 DB 读取缓存，不重新构建
2. contentHash 改变后缓存失效，触发重新生成
3. 返回已处理章节时 logcat 无 "segment rebuild" 日志

**集成测试：**
- 朗读一章节，关闭 App，重新打开该章节，直接从缓存读取

---

## MVP 4.1：豆包配置 UI 与连接测试

**目标：** 让用户能配置豆包账号并验证连接，使 MVP 3.1 的豆包能力对用户可用。

**具体实现：**
- 新增设置页面（复用现有 PreferenceFragment 模式）
  - 输入项：AppID、AccessToken、Resource ID、旁白/对白 voiceType
  - [测试连接] 按钮 → 调用 `DoubaoTtsClientV3.synthesizeStream()` 合成 1 句示例文本（如"你好，测试"）
  - 显示结果：成功/失败 + 音频时长 + 错误码（若失败）
- 配置存储：复用现有 `DoubaoTtsEngine.getSettings()` / `saveSettings()`（SharedPreferences + GSON）
- 测试失败时给出具体错误提示（HTTP status、服务端 code 等）

**验收标准：**
1. 用户填入真实豆包账号后点"测试连接"，能播放合成音频
2. 填入错误 AppID 时，界面显示"401 Unauthorized"或对应错误码
3. 配置保存后重启 App 设置仍保留
4. 空白状态 App 不要求用户填豆包配置（可选）

---

## MVP 5：预取队列与章节背景处理

**目标：** 朗读启动后能快速播放第一段，同时后台异步预处理后续内容。

**具体实现（基于现有 speakJob/downloadTask）：**
- 新增 `SegmentPrefetchQueue`（Kotlin Channel，容量 = 5 段）
  - 当播放当前段时，异步预取接下来 3 段的 segment 和音频
  - 跳章时 cancel 当前 channel，新建队列
- 当前 segment 未就绪时，立即回退 MVP3 规则分段播放（不阻塞）
- 下一章预处理：当前章播到 70% 时，后台触发下一章的 segment 生成 + DB 写入（MVP 4 缓存）
- 预生成 TTS：segment 就绪后自动调用 TTS，音频写入文件缓存

**时间指标：**
- 首段可播放等待时间 ≤ 1 秒（豆包网络 + 合成延迟）
- 跳章后旧 prefetch job 在 100ms 内 cancel

**验收标准：**
1. 打开新章节，首段 TTS 返回后立即开始播放（无额外延迟）
2. logcat 显示 prefetch 在播放第 1 段时已启动第 2-3 段请求
3. 快速跳章后 logcat 显示旧 job 已 cancelled
4. 断网时 prefetch 失败，播放继续使用缓存或规则降级

---

## MVP 6A：AI 服务配置与 Prompt 管理

**目标：** 让用户配置 AI 模型服务（OpenAI-compatible），并能编辑/导出/导入小说解析 Prompt。
**对应 PRD 验收：** 第 13、14 条

**具体实现：**
- **AI 配置数据结构** `AiServiceConfig`
  ```
  {
    id: String,
    name: String,           // "我的 Claude"
    endpoint: String,       // "https://api.openai.com/v1"
    apiKey: String,         // 用户提供
    model: String,          // "gpt-4" / "claude-opus" 等
    params: String?         // 可选额外 JSON 参数
  }
  ```
- **Prompt 两级管理**（参考 Unitale）
  - 内置默认 Prompt（代码资源文件，版本追踪）
  - 用户自定义 Prompt → SharedPreferences
  - 启用开关：关闭时使用内置，启用时使用用户版本
  - 自定义启用时，系统自动叠加「原文完整性」「结构化输出」最低约束，用户无法绕过
  - Prompt 模板用 `${emotionList}`、`${rawScript}` 等占位符，运行时替换
- **导出/导入**
  - 导出：Prompt 保存为 `.txt` 文件
  - 导入：从用户选择的 `.txt` 文件读取
  - 恢复默认：覆盖用户版本为内置版本
- **连接测试**
  - 发送 1 段示例文本（如"你好")，验证模型返回 JSON 格式正确
  - 显示成功/失败 + token 用量（若返回）

**验收标准：**
1. 填入 OpenAI-compatible endpoint + API key，点"测试"，返回示例 JSON
2. 修改 Prompt 后导出为文件，清空后重新导入，内容一致
3. 恢复默认后 Prompt 内容变回内置版本
4. 配置保存后重启 App 仍保留

---

## MVP 6B：AI 单章剧本解析与基础角色识别

**目标：** 接入 AI 将当前章节解析为结构化 dialogue 事件，替换 MVP3 的规则分段。
**对应 PRD 验收：** 第 2、3、7 条

**具体实现：**
- **新增 `AiScriptParser`**
  - 输入：章节文本 + 已知角色摘要 + Prompt（来自 MVP 6A）
  - 输出：JSON → `List<ScriptEvent>`
  - 调用 AI 服务（OpenAI-compatible）分析
  - 返回 JSON schema：
    ```json
    [
      {"type": "bgm", "action": "play", "name": "BGM名"},
      {"type": "bgImage", "image_prompt": "中文场景描述"},
      {
        "type": "dialogue",
        "role_name": "角色名",
        "text_content": "台词内容",
        "emotion": "害怕",
        "intensity": "强烈",
        "break_duration": 0.5,
        "filter": "电话",
        "sfx": [{"name": "音效", "position": 0.5}]
      }
    ]
    ```
- **JSON 校验**
  - 覆盖全部原文，无内容缺失/重复/乱序
  - dialogue 的 `text_content` 按 `startPosition/endPosition` 或通过字符匹配重构原文，确保能完整覆盖输入
- **失败降级**
  - AI 超时/格式错误 → 自动回退 MVP3 规则分段
  - 网络错误 → 使用缓存或规则分段
  - 日志记录失败原因，便于调试
- **AI 结果写入 MVP 4 的 DB 缓存**

**单元测试：**
1. Mock AI 返回值，校验 segment 映射不缺原文位置
2. AI 返回格式错误时，自动降级为规则分段
3. JSON 校验测试：缺少字段、类型错误、无法映射原文时识别

**E2E 测试：**
1. 使用真实 AI API，验证识别出旁白 + 至少 2 个具体角色名
2. 验证 text_content 与原文能对应
3. AI 失败时朗读不中断（自动降级）

---

## MVP 6C：角色档案与跨章节一致性

**目标：** 建立每本书的角色档案，同一角色跨章节复用同一音色。
**对应 PRD 验收：** 第 4、5、8、15 条

**具体实现：**
- **Room 新增 `CharacterProfileEntity` 表**
  ```
  {
    id: Long (primary key),
    bookUrl: String,
    characterId: String,    // 唯一标识符
    name: String,           // 显示名
    aliases: String,        // JSON 数组
    level: String,          // "protagonist" / "major" / "minor" / "extra" / "unknown"
    voiceType: String,      // TTS 音色名（如 "zhifeng_emo"）
    locked: Boolean,        // 是否锁定（不被 AI 覆盖）
    description: String,    // 角色描述（用于 AI 生成参考音色）
    firstAppearChapter: Int,
    lastAppearChapter: Int,
    createdAt: Long
  }
  ```
- **角色识别流程**
  - 首次识别新角色时，创建 CharacterProfile 记录
  - 后续章节遇到同名角色，优先读档案匹配（获取已有音色）
  - 用户修正角色 → 写锁定状态 → 后续 AI 不覆盖
- **导入导出**
  - 导出：JSON 文件（含角色名/别名/音色绑定/锁定状态），不含 API Key
  - 导入：读 JSON，处理冲突（新增/覆盖/跳过）

**验收标准：**
1. 连续朗读 2 章，同一角色（如"主角"）使用同一音色
2. 手动修改某角色音色并锁定 → 进入下一章 → 音色未被 AI 更改
3. 导出角色档案 JSON → 清空角色 → 重新导入 → 角色恢复
4. 导入时发生冲突 → 界面显示冲突列表，用户可选覆盖或跳过

---

## MVP 6D：轻量剧本修正与导入导出

**目标：** 用户可修正当前段角色/情绪，并选择范围；剧本支持导入导出。
**对应 PRD 验收：** 第 4、16 条

**具体实现：**
- **轻量修正入口**
  - 朗读控制面板：长按当前段 → 修正菜单（角色/情绪/表演方式）
  - 修正范围选择：仅本段 / 本章 / 全书记住
  - 修正写入角色档案（若选"全书记住"）
- **AI 剧本导入导出**
  - 导出：按章节导出 JSON（含版本、原文校验、角色引用，不含 API Key 和小说正文）
  - 导入：读 JSON，校验书籍和章节版本，显示覆盖范围
- **修正后同步**
  - 用户修改当前段角色 + 范围"本章应用" → 刷新当前章节所有同角色段落
  - 用户修改 + 范围"全书记住" → 更新角色档案的音色绑定

**验收标准：**
1. 修正当前段角色 → 选"全书记住" → 当前章同类段落更新
2. 导出当前章剧本 → 文件包含正确 JSON 结构，不含 API Key
3. 清空缓存后导入剧本 → 该章节无需重新 AI 解析

---

## MVP 7：缓存管理与调试视图

**目标：** 用户可管理缓存并查看调试信息排查问题。
**配置 UI 已随各功能 MVP 交付（MVP 4.1、6A 等）**

**具体实现（精简）：**
- **缓存管理页面**
  - 查看当前书籍占用空间（音频 + 剧本缓存）
  - 清理语音缓存（保留剧本）
  - 清理剧本缓存（保留角色档案）
  - 保留角色配置重新生成
  - 仅 Wi-Fi 预生成开关
- **调试视图（高级设置）**
  - 当前段 segment 的 JSON 展示
  - 当前段 AI JSON 展示（若有）
  - 角色档案匹配结果
  - TTS 错误日志

**验收标准：**
1. 清理语音缓存后，角色档案不受影响
2. 开启"仅 Wi-Fi"后，移动网络下不触发预生成
3. 调试视图能显示当前段的 AI 解析结果

---

## V1 最小可上线 MVP 依赖关系

```
MVP 1-3.1 (已完成)
    ↓
MVP 4 (Segment 缓存)
    ↓
MVP 4.1 (豆包配置 UI)
    ↓
MVP 5 (预取队列)
    ↓
MVP 6A (AI 配置 + Prompt)
    ↓
MVP 6B (AI 解析)
    ↓
MVP 6C (角色档案)
    ↓
MVP 6D (轻量修正)
    ↓
MVP 7 (缓存管理)
    ↓
可上线 V1
```

---
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

---

## V1 MVP 执行计划拆分（基于审查方案）

### 完成状态

| MVP | 功能 | 状态 | 分支 |
|---|---|---|---|
| 1 | 阿里云 SSML TTS 基线 | ✅ 完成 | mvp1 |
| 2 | Segment 与位置映射 | ✅ 完成 | mvp2 |
| 3 | 规则分角色 SSML | ✅ 完成 | mvp3 |
| 3.1 | 豆包渲染 + 播放服务 | ✅ 完成 | mvp3.1 |
| **4** | Segment 剧本 Room 缓存 | ⏳ 计划中 | - |
| **4.1** | 豆包配置 UI + 连接测试 | ⏳ 计划中 | - |
| **5** | 预取队列与背景处理 | ⏳ 计划中 | - |
| **6A** | AI 配置 + Prompt 管理 | ⏳ 计划中 | - |
| **6B** | AI 剧本解析（dialogue/bgm/bgImage） | ⏳ 计划中 | - |
| **6C** | 角色档案与跨章节一致性 | ⏳ 计划中 | - |
| **6D** | 轻量剧本修正 + 导入导出 | ⏳ 计划中 | - |
| **7** | 缓存管理 + 调试视图 | ⏳ 计划中 | - |

### V1 最小可上线条件

执行顺序：MVP 1-3.1（已完成）→ MVP 4 → MVP 4.1 → MVP 5 → MVP 6A → MVP 6B → MVP 6C → MVP 6D → MVP 7

**关键路径：**
1. MVP 4 提供 segment 剧本持久化缓存（Room）
2. MVP 4.1 提供豆包配置 UI，让用户能启用豆包朗读
3. MVP 5 提供快速首段播放和后台预处理
4. MVP 6A 让用户能配置 AI 服务和 Prompt
5. MVP 6B 接入 AI 解析，替换规则分段
6. MVP 6C 提供角色档案和跨章节一致性
7. MVP 6D 提供轻量修正和导入导出
8. MVP 7 提供缓存管理和调试

---

## MVP 4 及之后的详细设计

（详见下文各 MVP 章节）

## 推荐开发顺序

1. ✅ MVP 1：阿里云 SSML TTS 基线（已完成）
2. ✅ MVP 2：SSML Segment 与纯文本位置映射基础（已完成）
3. ✅ MVP 3：规则分角色 SSML 生成（已完成）
4. ✅ MVP 3.1：豆包渲染 + 完整播放链路（已完成）
5. ⏳ MVP 4：Segment 剧本 Room 缓存
6. ⏳ MVP 4.1：豆包配置 UI 与连接测试
7. ⏳ MVP 5：预取队列与章节背景处理
8. ⏳ MVP 6A：AI 服务配置与 Prompt 管理
9. ⏳ MVP 6B：AI 单章剧本解析与基础角色识别
10. ⏳ MVP 6C：角色档案与跨章节一致性
11. ⏳ MVP 6D：轻量剧本修正与导入导出
12. ⏳ MVP 7：缓存管理与调试视图

---

## V1 最小可上线版本

完成上述 12 个 MVP 可以上线 V1 版本：

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
