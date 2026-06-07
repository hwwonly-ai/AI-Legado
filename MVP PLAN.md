# 阿里云 SSML AI 分角色朗读 MVP 拆分

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
  - 默认情绪 `neutral`，输出 `<emotion category="neutral" intensity="1.0">`。
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
  - 本地 Gradle 编译仍未成功执行：默认 `~/.gradle` wrapper 缓存锁受权限限制且提权审批超时；改用工作区 `.gradle` 会触发 Gradle 9.4.1 下载，但当前环境无法解析 `services.gradle.org`。
  - MVP3 只完成规则旁白/对白 SSML 生成，不包含剧本缓存、实时预取队列、AI 识别或用户可配置音色。

## MVP 4：SSML 剧本缓存与音频缓存复用

目标：让已生成的 segment/SSML 可复用，返回章节不重复处理。

内容：
- 缓存结构化 segment 剧本。
- 缓存 key：
  - `bookId`
  - `chapterIndex`
  - `contentHash`
  - `ruleVersion/promptVersion`
  - `voiceProfileVersion`
- 音频缓存继续按最终送入 TTS 的内容和 TTS 参数计算。
- 返回已处理章节时直接读取缓存，不重复预处理。
- 原文、规则版本、提示词版本、音色配置变化时重新生成。
- 剧本缓存不改变原阅读排版，只服务朗读链路。

验收：
- 第一次读章节时生成 SSML 剧本。
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

## MVP 6：AI 角色与情绪识别生成 SSML

目标：在规则链路、剧本缓存和预取队列稳定后，用 AI 判断具体角色、情绪和朗读风格。

内容：
- 接入 AI API 或后端代理。
- 输入当前章节窗口文本。
- AI 输出结构化 JSON，再转换成 MVP2 segment：
  - `text`
  - `speaker`
  - `emotion`
  - `voice`
  - `startPosition`
- 将 AI 结果转换为阿里云 SSML。
- 维护每本书的角色音色表。
- 同一角色跨章节复用同一音色。
- AI 失败时回退 MVP3 规则结果。

验收：
- 能识别主要角色。
- 同一角色使用一致音色。
- 情绪能映射到 SSML 标签或音色参数。
- AI 失败不影响基础朗读。
- AI 结果能进入 MVP4 剧本缓存和 MVP5 预取队列。

## MVP 7：用户配置、管理与调试

目标：让阿里云 SSML AI 朗读能力可长期使用、管理和排错。

内容：
- 阿里云 TTS 配置：
  - AppKey
  - AccessKeyId
  - AccessKeySecret
  - 默认 voice
- AI 配置：
  - API Key
  - 模型
  - promptVersion
- 角色音色表：
  - 旁白
  - 男主
  - 女主
  - 老人
  - 儿童
  - 自定义角色
- 缓存管理：
  - 清理 SSML 剧本缓存
  - 清理 TTS 音频缓存
  - 手动刷新当前章节
  - 手动刷新整本
- 调试能力：
  - 查看当前片段 SSML
  - 查看 AI JSON
  - 查看 TTS 错误日志

验收：
- 用户能修改默认音色。
- 用户能刷新某章预处理结果。
- 用户能清理缓存。
- 出错时能看到可定位的日志。

## 推荐开发顺序

1. MVP 1：先稳定阿里云 SSML TTS。
2. MVP 2：建立 SSML Segment 与纯文本位置映射基础。
3. MVP 3：实现规则分角色。
4. MVP 4：建立 SSML 剧本缓存与音频缓存复用。
5. MVP 5：实现实时预处理和预取队列。
6. MVP 6：接入 AI 角色/情绪识别。
7. MVP 7：完善配置、管理和调试。

## 最小可上线版本

建议做到：

- MVP 1
- MVP 2
- MVP 3
- MVP 4
- MVP 5

也就是：

- 阿里云 SSML 能稳定朗读。
- Segment 与纯文本位置映射稳定。
- 旁白/对白能用不同音色。
- 章节剧本和音频能缓存复用。
- 不需要整本离线预处理，可以边读边预处理。
