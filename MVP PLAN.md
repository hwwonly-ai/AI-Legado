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
