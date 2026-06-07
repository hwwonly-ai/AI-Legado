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
  - 普通文本 + 阿里云 TTS 会经过 `preprocessAliyunPlainText()`，先做 XML 转义，再包装为 `<speak>...</speak>`。
  - SSML 请求失败时会剥离标签回退普通文本朗读。
  - 普通文本回退仍失败时创建静音音频，避免朗读服务中断。
  - 音频缓存文件名已按最终送入 TTS 的文本计算，避免普通文本与 SSML 预处理文本共用错误缓存。
  - 缓存 key 已包含 TTS URL、contentType、responseType、audioPath、登录信息、header、语速、最终朗读内容等关键参数。
- `BaseReadAloudService` 当前行为：
  - 光标进度映射不再读取 `ssmlTagParse`。
  - 是否按页映射由 `TextChapter.readAloudContent` 是否为空自动决定。
  - `stripReadAloudTags()` 继续用于计算纯文本长度和定位。
- 默认 `httpTTS.json` 当前保留：
  - `2.阿里云语音`：普通阿里云 TTS，保留作测试备用。
  - `4.阿里云语音(SSML)V2.0`：旧自动判断 SSML 的配置，保留作测试备用。
  - `5.阿里云语音(统一SSML)`：当前推荐统一配置。
- `5.阿里云语音(统一SSML)` 配置约定：
  - 使用用户填写的 `AppKey`、`AccessKeyId`、`AccessKeySecret`。
  - 支持可选 `VoiceName`，未填写时默认 `zhifeng_emo`。
  - 请求固定 `ssml: true`。
  - 不再在配置 JS 中使用 `hasSSMLTag` 判断。
  - 是否普通文本预处理、是否已有 SSML，由 Kotlin 代码统一决定。
- 已验证：
  - `httpTTS.json` JSON 格式校验通过。
  - `git diff --check` 通过。
- 未完成/注意：
  - 本地 Gradle 编译未成功执行，原因是当前环境访问 `~/.gradle` wrapper 缓存锁需要提权且审批超时。
  - MVP1 只实现阿里云 SSML 基线和自动识别，尚未实现规则分角色、AI 识别、结构化剧本缓存和实时预取队列。

## MVP 2：SSML 朗读剧本缓存

目标：将章节预处理结果保存为可复用的 SSML 朗读剧本。

内容：
- 新增朗读剧本结构：
  - `chapterIndex`
  - `contentHash`
  - `promptVersion`
  - `segments`
- 每个 segment 保存：
  - 原文纯文本 `text`
  - SSML 内容 `ssml`
  - 起始位置 `startPosition`
  - 纯文本长度 `plainLength`
  - 角色 `speaker`
  - 情绪 `emotion`
  - 音色 `voice`
- 缓存 key：
  - `bookId + chapterIndex + contentHash + promptVersion + voiceProfileVersion`
- 返回已处理章节时直接读取缓存，不重复预处理。
- 原文、提示词、音色配置变化时重新生成。

验收：
- 第一次读章节时生成 SSML 剧本。
- 返回已处理章节时不重新预处理。
- 原文变化后能重新生成。
- 剧本缓存不会影响原阅读排版。

## MVP 3：规则分角色生成 SSML

目标：先不用 AI，通过规则实现基础旁白/对白分角色朗读。

内容：
- 用规则识别：
  - 旁白
  - 引号对白
  - “某某说/问/笑道”等说话提示词
- 默认音色：
  - `narratorVoice`
  - `dialogVoice`
- 根据规则生成阿里云兼容 SSML。
- 识别失败时使用旁白音色。
- 不要求准确识别具体人物，只区分旁白和对白。

验收：
- 旁白和对白能使用不同音色。
- 对白段能被包裹为对应 SSML。
- 识别失败时回退旁白。
- 不影响光标定位和章节跳转。

## MVP 4：光标同步与 SSML 映射

目标：朗读时文本高亮和翻页能跟随 SSML 音频播放。

内容：
- 建立 SSML 到纯文本的映射。
- `ReadAloudProgress.chapterPos` 始终使用原文纯文本位置。
- SSML 标签不参与 `plainLength`。
- 片段播放开始时上报 `segment.startPosition`。
- 音频播放中使用时长比例估算当前字位置。
- 继续复用：
  - `ReadAloudProgress`
  - `EventBus.TTS_PROGRESS`
  - `ReadBookActivity.showReadAloudProgress()`
  - `TextPage.upPageAloudSpan()`

验收：
- 当前朗读段落能高亮。
- 跨页时能自动翻页。
- 返回已缓存章节时仍能同步。
- SSML 标签不会导致高亮偏移明显错误。

## MVP 5：AI 角色与情绪识别生成 SSML

目标：用 AI 判断具体角色、情绪和朗读风格，再生成 SSML。

内容：
- 接入 AI API 或后端代理。
- 输入当前章节窗口文本。
- AI 输出结构化 JSON：
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

## MVP 6：实时预处理与预取队列

目标：从“离线预处理整本小说”改为“边读边处理 + 后台预取”。

内容：
- 启动朗读时：
  - 当前 1-3 个片段优先处理。
  - 未处理完成时可先用规则结果播放。
- 后台任务：
  - 预处理当前章节剩余片段。
  - 预处理下一章。
  - 预生成 TTS 音频。
- 支持：
  - 跳章取消无用任务。
  - 网络失败重试。
  - 缓存命中跳过 AI 和 TTS。
  - App 退出后保留已完成缓存。
- 不再要求用户先整本预处理再导入。

验收：
- 点朗读后不长时间等待。
- 当前段能尽快开始播放。
- 后续片段能提前准备。
- 跳到其他章节后旧任务能停止。
- 返回已处理章节时直接用缓存。

## MVP 7：用户配置与管理

目标：让阿里云 SSML AI 朗读能力可长期使用和调试。

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
2. MVP 2：建立 SSML 剧本缓存。
3. MVP 3：实现规则分角色。
4. MVP 4：增强光标同步。
5. MVP 6：实现实时预处理和预取队列。
6. MVP 5：接入 AI 角色/情绪识别。
7. MVP 7：完善配置和管理。

## 最小可上线版本

建议做到：

- MVP 1
- MVP 2
- MVP 3
- MVP 4
- MVP 6

也就是：

- 阿里云 SSML 能稳定朗读。
- 章节剧本能缓存。
- 旁白/对白能用不同音色。
- 光标能跟随。
- 不需要整本离线预处理，可以边读边预处理。
