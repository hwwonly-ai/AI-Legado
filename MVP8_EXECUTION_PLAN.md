# MVP 8 音色管理执行方案

## 1. 目标

把 MVP8 从“当前段轻量修正”调整为“音色管理”。基于现有书籍音色配置能力，提供一个类似 MultiTTS 的音色库界面：顶部选择 TTS Provider，下面展示当前 Provider 的官方音色列表，每个音色以卡片呈现名称、voiceId、属性和试听按钮，支持编辑试听文本，并可把音色分配给书籍默认旁白/对白、角色或固定龙套。

MVP8 不做当前段修正，不改 AI Segment，不改剧本导入导出。

## 2. 当前基础

已有能力：

- `BookVoiceConfigDialog`：当前书籍旁白/对白音色配置。
- `Book.setTtsVoices(ttsEngine, narratorVoice, dialogVoice)`：按 TTS 引擎保存书籍默认音色。
- `BookReadAloudRole.voices[engineKey]`：按 TTS 引擎保存角色音色。
- `BookRoleEditDialog`：角色编辑页已有跟随旁白、跟随对白、自定义音色选择。
- `HttpTTS`：HTTP_TTS Provider 配置来源。
- `HttpTTS.isSupportedRoleVoice()` / `voiceHint()`：判断是否支持分角色音色和显示 voice/speaker 提示。

## 3. 范围

### 包含

- 新增或扩展音色管理 Dialog。
- 顶部提供 TTS Provider 选择栏，默认选当前朗读 TTS Provider。
- 复用现有书籍音色表单的保存能力，但主界面改为“音色库列表”。
- 展示当前 Provider 的预置音色列表，优先使用官方音色数据。
- 每个音色卡片支持点击试听。
- 支持编辑试听文本。
- 支持将音色分配给一个或多个角色。
- 支持把音色设为书籍默认旁白 / 默认对白。
- 支持清空某个角色音色，使其回退到默认规则。

### 不包含

- 不做当前段角色/音色/情绪修正。
- 不修改 AI 角色识别结果。
- 不新增 TTS Provider。
- 不导入导出音色库。
- 不做云端音色搜索。
- 不自动推荐音色。

## 4. 音色数据源

MVP8 采用内置预置音色列表，不在运行时抓取网页。

### 阿里云

数据来源：阿里云智能语音交互官方文档“音色列表”。

字段建议：

- `provider`: `aliyun`
- `displayName`: 音色中文名，例如 `知小白`
- `voiceId`: voice 参数值，例如 `zhixiaobai`
- `gender`: 男 / 女 / 未知
- `ageGroup`: 儿童 / 少年 / 青年 / 中年 / 老年 / 未知
- `roleAttribute`: 对应 MVP7 的 8 种角色属性，可人工标注
- `type`: 文档中的类型，例如普通话女声、广东普通话
- `scenario`: 适用场景
- `sampleRates`: 支持采样率
- `quality`: 声音品质

### 豆包

数据来源：火山引擎官方文档中“豆包语音合成模型2.0 音色列表”。

只收录该分组下的音色，不混入旧模型、实时语音、复刻音色或其他模型音色。

字段建议：

- `provider`: `doubao`
- `model`: `doubao-tts-2.0`
- `displayName`
- `voiceId`
- `gender`
- `ageGroup`
- `roleAttribute`
- `style` / `scene`

### 维护方式

- 第一版可以手工整理为 Kotlin 常量或 JSON asset。
- 每个 Provider 单独一个列表，便于后续增量维护。
- `roleAttribute` 不依赖 AI 自动判断，音色列表导入时人工标注。
- 如果用户提供自定义音色，作为“自定义”分组追加在官方列表之后。

## 5. 数据策略

MVP8 不新增 Room 表。

音色仍写入现有位置：

- 书籍默认旁白 / 对白：`Book.setTtsVoices(ttsEngine, narratorVoice, dialogVoice)`。
- 角色音色：`BookReadAloudRole.voices[ttsEngine] = voiceId`。
- 跟随同属性龙套：沿用 MVP7 约定的内部标记，例如 `@extra:middle_female`。
- 跟随书籍旁白 / 对白：`BookReadAloudRole.VOICE_BOOK_NARRATOR` / `BookReadAloudRole.VOICE_BOOK_DIALOG`。

官方音色条目是 UI 数据源，不是用户配置实体。只有当用户把某个 `voiceId` 分配给书籍或角色时，才写入现有配置。

## 6. UI 设计

```text
┌────────────────────────────────────────────┐
│ 音色管理                                × │
├────────────────────────────────────────────┤
│ ☰  音色管理        [豆包2.0 ▼]      🔍 ⋮  │
├────────────────────────────────────────────┤
│ 语速 ━━━━━━━━━●━━━━━━  65                 │
│ 音量 ━━━━━━━●━━━━━━━━  50                 │
│ 语调 ━━━●━━━━━━━━━━━━  25                 │
├────────────────────────────────────────────┤
│ 试听文本  [ 你好，我是这个角色的试听文本。]│
├────────────────────────────────────────────┤
│ [头像] 灿灿        青年女性   BV700_V2  🎧 │
│        豆包2.0 / 通用场景        分配  ⋮   │
├────────────────────────────────────────────┤
│ [头像] 成熟大叔    中年男性   BV003     🎧 │
│        豆包2.0 / 旁白场景        分配  ⋮   │
├────────────────────────────────────────────┤
│ [头像] 温柔淑女    青年女性   BV104     🎧 │
│        豆包2.0 / 对话场景        分配  ⋮   │
└────────────────────────────────────────────┘
```

交互：

- 顶部 Provider 下拉只显示支持分角色音色的 HTTP_TTS。
- Provider 切换后，列表刷新为该 Provider 的官方音色。
- 顶部搜索可按名称、voiceId、性别、年龄、场景过滤。
- 语速、音量、语调滑杆只影响试听，不直接改书籍配置。
- 点击卡片右侧耳机按钮，使用试听文本和该音色试听。
- 点击“分配”打开角色选择器，可选择书籍旁白、书籍对白、8 个龙套或一个/多个角色。
- 卡片更多菜单提供“设为默认旁白”“设为默认对白”“复制 voiceId”。
- 已被当前书籍或角色使用的音色，在卡片上显示“已用于：旁白 / 张三 / 龙套·中年女性”。
- 角色条目可在角色管理页清空音色，清空后按角色规则回退。

视觉规则：

- 采用纵向卡片列表，布局参考 MultiTTS：左侧头像，中间音色名称和属性，右侧 voiceId 和试听按钮。
- 男声/女声用不同头像或色彩标识，儿童/少年/中年/老年用小标签标识。
- 卡片高度固定，长名称单行省略，voiceId 不换行。
- 试听按钮使用耳机图标；分配使用文本按钮或更多菜单，避免主列表拥挤。
- 页面第一屏优先展示音色库，不把角色列表作为主内容。

## 7. 试听策略

试听文本：

- 默认：`你好，我是这个角色的试听文本。`
- 用户可编辑，保存在本地偏好或当前 Dialog 状态。
- 试听文本不进入角色档案、不进入 AI 缓存。

试听调用：

- 使用当前 Provider 的 HTTP_TTS 配置。
- 使用选中条目的 voiceId。
- 使用短文本生成临时音频并播放。
- 试听音频放临时缓存，可复用普通音频播放能力，但不写章节音频缓存。

失败处理：

- Provider 不支持分角色音色：禁用列表编辑和试听，显示提示。
- voiceId 为空：使用该条目的回退音色试听；仍为空则禁用试听。
- 试听失败：显示错误，不影响保存。

## 8. 与角色系统的关系

- MVP7 的 8 个固定龙套可在 MVP8 里集中配置音色。
- MVP7.1 自动创建的次要配角默认跟随同属性龙套音色。
- 用户可在 MVP8 中给某个次要配角单独分配音色。
- 用户可批量把同一音色分配给多个角色。
- 修改龙套音色后，跟随该龙套的次要配角自动使用新音色。

## 9. 缓存关系

MVP8 沿用 MVP7 测试期策略：

- 保存书籍默认音色、龙套音色或角色音色后，清理当前书当前章节音频缓存。
- 不清 AI Segment 缓存。
- 不清规则 Segment 缓存。
- 正式精确缓存失效留到 MVP10。

## 10. 静态验证计划

按项目规则不跑编译 / Gradle，只做静态检查：

- `rg "BookVoiceConfigDialog|dialog_book_voice_config" app/src/main/java/io/legado/app app/src/main/res/layout`
- `rg "setTtsVoices|getNarratorVoice|getDialogVoice|getRoleVoice|setRoleVoice" app/src/main/java/io/legado/app`
- `rg "VOICE_BOOK_NARRATOR|VOICE_BOOK_DIALOG|@extra:" app/src/main/java/io/legado/app`
- `rg "voiceHint|isSupportedRoleVoice|providerName" app/src/main/java/io/legado/app/ui/book/read/config`
- 确认 MVP8 不修改 AI 标注和 Segment 缓存 key。

## 11. 验收用例

- 打开音色管理，默认选中当前 TTS Provider。
- 切换 Provider 后，音色库按 Provider 隔离展示。
- 阿里云 Provider 展示阿里云官方音色列表。
- 豆包 Provider 只展示“豆包语音合成模型2.0”音色列表。
- 搜索 voiceId 或音色名可以过滤列表。
- 编辑试听文本后，点击音色条目可用新文本试听。
- 调整语速、音量、语调后试听使用新的临时参数。
- 点击任意音色卡片耳机按钮，使用该 voiceId 试听。
- 将某个 voice 设为书籍旁白后，旁白朗读使用该 voice。
- 将某个 voice 分配给角色“张三”，保存后张三朗读使用该 voice。
- 清空角色音色后，角色回退到默认规则。
- 修改龙套音色后，跟随该龙套的次要配角使用新音色。
- 保存音色后，当前章节音频缓存被清理用于验证。

## 12. 完成定义

- MVP8 替代旧“当前段轻量修正”。
- 可按 TTS Provider 查看官方音色库。
- 阿里云和豆包2.0 音色列表可静态维护。
- 每个音色卡片可点击试听。
- 试听文本可编辑。
- 试听参数可调整。
- 可把音色分配给不同角色。
- 复用现有书籍音色和角色音色存储。
- 不新增数据库表、不升级数据库版本。
- 未执行编译 / Gradle，仅完成静态检查。
