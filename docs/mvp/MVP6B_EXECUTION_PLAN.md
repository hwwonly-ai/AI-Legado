# MVP 6B Prompt 管理执行方案

## 1. 目标

完成 AI 章节解析 Prompt 的查看、编辑、保存、恢复默认、导入和导出，为后续 MVP6C AI 单章解析提供稳定 Prompt 配置能力。

MVP6B 只做 Prompt 管理，不调用 AI，不解析章节，不写 AI 解析结果缓存。

## 2. 与前后 MVP 的边界

### 依赖

- MVP6A 已完成 AI 服务配置、Provider 预设、连接测试和高级参数配置。
- MVP6B 使用 MVP6A 的 AI 配置入口区域或相邻入口，但不依赖连接测试成功。

### 不包含

- 不调用 AI 接口。
- 不解析章节正文。
- 不生成 AI Segment。
- 不写 AI 剧本缓存。
- 不接入角色档案。
- 不接入朗读链路。
- 不修改 TTS、音色或预取逻辑。

### 后续使用方

- MVP6C：读取当前 Prompt 发起单章解析。
- MVP6D：使用 Prompt hash 判断 AI 解析缓存是否失效。
- 当前段轻量修正不再纳入 MVP8；如后续恢复，可复用 Prompt 版本和 hash。
- MVP10：调试视图可展示 Prompt hash 和当前 Prompt 摘要。

## 3. 核心功能

1. 首次打开显示内置默认 Prompt。
2. 用户可编辑 Prompt 文本。
3. 用户可保存 Prompt。
4. 用户可恢复默认 Prompt。
5. 用户可导入 Prompt 文本。
6. 用户可导出当前 Prompt。
7. 保存 Prompt 内容 hash。
8. 保存 Prompt 更新时间。
9. 保存 Prompt 版本。
10. UI 显示当前 Prompt 状态。

## 4. 存储策略

MVP6B 优先使用 SharedPreferences 或现有偏好体系，不新增 Room 表。

建议 key：

```kotlin
aiPromptText
aiPromptVersion
aiPromptHash
aiPromptUpdatedAt
```

默认值：

```text
promptText: 内置默认 Prompt
version: 1
contentHash: 默认 Prompt hash
updatedAt: 0 或首次保存时间
```

要求：

- Prompt 可跨重启保留。
- 恢复默认后覆盖用户自定义 Prompt。
- Prompt hash 必须基于规范化文本计算。
- 不把 Prompt 存入数据库，避免 MVP6B 引入迁移。

## 5. Prompt 配置模型

建议新增：

```kotlin
data class AiPromptConfig(
    val promptText: String,
    val version: Int,
    val contentHash: String,
    val updatedAt: Long,
    val isDefault: Boolean
)
```

字段说明：

- `promptText`：当前 Prompt 全文。
- `version`：Prompt 结构版本，用于后续默认 Prompt 升级。
- `contentHash`：Prompt 内容 hash，用于 MVP6D 缓存失效。
- `updatedAt`：用户保存或恢复默认时间。
- `isDefault`：当前 Prompt 是否与内置默认 Prompt 一致。

## 6. 默认 Prompt 设计

MVP6B 内置一个默认 Prompt，要求服务于后续“小说章节 → AI 朗读剧本 / Segment”的任务。

默认 Prompt 应强调：

- 不改写原文。
- 不总结剧情。
- 不补写不存在的台词。
- 按原文顺序输出。
- 区分旁白和对白。
- 识别具体角色。
- 识别情绪和强度。
- 输出严格 JSON。
- 保留可映射到原文的文本。
- 无法判断角色时使用 `unknown` 或旁白兜底。

建议默认 Prompt：

```text
你是一个小说分角色朗读解析器。

任务：
将输入的小说章节文本解析为适合朗读的结构化事件列表。

必须遵守：
1. 不改写原文。
2. 不总结剧情。
3. 不新增原文不存在的台词、角色或事件。
4. 保持原文顺序。
5. 所有输出文本必须能在原文中找到对应内容。
6. 对无法确定说话人的对白，role 使用 "unknown"。
7. 对旁白，role 使用 "narrator"。
8. 情绪不确定时使用 "neutral"。
9. 情绪强度范围为 0.0 到 1.0，默认 1.0。
10. 只输出 JSON，不输出解释说明。

你需要识别：
- narrator：旁白。
- dialog：对白。
- roleName：具体说话人名称。
- emotion：情绪，例如 neutral、happy、sad、angry、fear、surprise。
- emotionIntensity：情绪强度。
- text：原文片段。

输出 JSON 格式：
{
  "segments": [
    {
      "eventType": "text",
      "role": "narrator",
      "roleName": "旁白",
      "emotion": "neutral",
      "emotionIntensity": 1.0,
      "text": "原文片段"
    }
  ]
}
```

说明：

- MVP6B 只保存 Prompt，不验证 AI 输出。
- JSON schema 在 MVP6C 可继续细化。
- 默认 Prompt 后续升级时递增 `version`。

## 7. Prompt Hash 规则

Prompt hash 用于后续 AI 缓存失效。

规范化规则：

```kotlin
fun normalizePrompt(prompt: String): String {
    return prompt
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
}
```

hash：

```kotlin
MD5Utils.md5Encode(normalizePrompt(prompt))
```

要求：

- 内容变化 hash 必须变化。
- 行尾差异不应造成无意义变化。
- 首尾空白不参与 hash。
- Prompt hash 不包含 AI Provider、model 或章节文本。

后续 MVP6D 的 AI 解析缓存 key 应至少包含：

```text
bookUrl
chapterUrl
chapterContentHash
promptHash
promptVersion
aiProvider
aiModel
parseSchemaVersion
```

MVP6B 只提供 `promptHash` 和 `promptVersion`，不创建 AI 缓存表。

## 8. UI 入口

建议新增入口：

```text
AI Prompt 管理
```

入口位置：

- AI 服务配置弹窗中增加“Prompt 管理”按钮。
- 或朗读配置中与“AI 服务配置”同级。

推荐：

- MVP6B 先从 AI 服务配置弹窗进入，避免主菜单入口过多。
- 后续 AI 功能稳定后，再考虑独立入口。

## 9. UI 布局

新增 Dialog：

```text
AiPromptConfigDialog
```

布局内容：

1. 标题：AI Prompt 管理。
2. 状态摘要：
   - 当前版本。
   - 当前 hash 前 8 位。
   - 是否默认 Prompt。
   - 更新时间。
3. Prompt 编辑框：
   - 多行。
   - 支持滚动。
   - 等宽字体可选。
4. 操作按钮：
   - 保存。
   - 恢复默认。
   - 导入。
   - 导出。
   - 复制。
   - 取消。

编辑框要求：

- 支持长文本。
- 最小高度足够编辑 Prompt。
- 空文本保存前提示。
- 文本未保存时关闭弹窗需确认。

## 10. 保存逻辑

保存时：

1. trim 首尾空白。
2. 校验非空。
3. 计算 hash。
4. 判断是否默认 Prompt。
5. 保存 promptText。
6. 保存 promptVersion。
7. 保存 promptHash。
8. 保存 updatedAt。
9. Toast 或 Snackbar 提示保存成功。

保存不做：

- 不调用 AI。
- 不验证 JSON schema。
- 不解析章节。
- 不清理任何缓存。

缓存清理留到 MVP6D 结合 AI 解析缓存实现。

## 11. 恢复默认

点击“恢复默认”：

1. 弹出确认。
2. 用户确认后，编辑框替换为内置默认 Prompt。
3. 保存默认 Prompt。
4. 更新 hash、version、updatedAt。
5. 标记 `isDefault=true`。

注意：

- 恢复默认会覆盖用户自定义 Prompt。
- 不影响 AI 服务配置。
- 不影响 API Key、Provider、模型和高级参数。

## 12. 导入 Prompt

导入方式建议：

- 从剪贴板导入。
- 从本地 `.txt` 或 `.md` 文件导入。

MVP6B 可先实现剪贴板导入；文件导入如果项目已有通用文件选择器，可顺手接入，否则放后续。

导入逻辑：

1. 读取文本。
2. 校验非空。
3. 填入编辑框。
4. 标记未保存。
5. 用户点击保存后才持久化。

导入不应直接覆盖已保存 Prompt，避免误操作。

## 13. 导出 Prompt

导出方式建议：

- 复制到剪贴板。
- 分享为文本。
- 保存为 `.txt` 文件。

MVP6B 最小实现：

- “复制”按钮：复制当前编辑框内容到剪贴板。
- “导出”按钮：调用系统分享文本。

导出内容：

```text
当前编辑框中的 Prompt
```

不要强行添加元数据，避免用户再次导入时污染 Prompt。

如需要元数据，可后续新增“导出带元数据 JSON”。

## 14. 版本策略

内置默认 Prompt 有结构版本：

```kotlin
const val DEFAULT_AI_PROMPT_VERSION = 1
```

规则：

- 修改默认 Prompt 结构时版本递增。
- 用户自定义 Prompt 仍记录当前默认版本，用于提示“默认 Prompt 有新版本”。
- MVP6B 不做复杂升级合并，只提供恢复默认。

后续可扩展：

- 如果内置默认版本大于用户保存版本，UI 提示“默认 Prompt 已更新，可恢复默认查看新版”。

## 15. 字符串资源

需要新增中英文字符串：

```text
ai_prompt_config
ai_prompt_save
ai_prompt_restore_default
ai_prompt_import
ai_prompt_export
ai_prompt_copy
ai_prompt_hash
ai_prompt_version
ai_prompt_updated_at
ai_prompt_is_default
ai_prompt_unsaved_confirm
ai_prompt_empty_error
ai_prompt_saved
ai_prompt_restored
ai_prompt_copied
```

简体中文示例：

```text
AI Prompt 管理
保存
恢复默认
导入
导出
复制
Prompt 不能为空
Prompt 已保存
已恢复默认 Prompt
已复制 Prompt
```

## 16. 文件建议

新增：

```text
app/src/main/java/io/legado/app/model/ai/AiPromptConfig.kt
app/src/main/java/io/legado/app/model/ai/AiPromptStore.kt
app/src/main/java/io/legado/app/model/ai/DefaultAiPrompts.kt
app/src/main/java/io/legado/app/ui/book/read/config/AiPromptConfigDialog.kt
app/src/main/res/layout/dialog_ai_prompt_config.xml
```

修改：

```text
app/src/main/java/io/legado/app/constant/PreferKey.kt
app/src/main/java/io/legado/app/ui/book/read/config/AiServiceConfigDialog.kt
app/src/main/res/values/strings.xml
app/src/main/res/values-zh/strings.xml
MVP PLAN.md
```

不修改：

```text
AppDatabase.kt
ReadAloudSegmentCache
HttpReadAloudService
TTS Provider 渲染逻辑
```

除非实现中发现 AI 服务配置入口需要增加按钮，否则不碰朗读播放链路。

## 17. 实施步骤

### 阶段 A：Prompt 常量与存储

1. 新增默认 Prompt 常量。
2. 新增 `AiPromptConfig`。
3. 新增 `AiPromptStore`。
4. 实现 Prompt normalize。
5. 实现 Prompt hash。
6. 接入 PreferKey。

完成标准：

- 首次读取返回默认 Prompt。
- 保存自定义 Prompt 后可再次读取。
- hash 随内容变化。

### 阶段 B：Prompt 管理 UI

1. 新增 `AiPromptConfigDialog`。
2. 新增布局。
3. 显示 Prompt 文本。
4. 显示 version/hash/updatedAt/isDefault。
5. 实现保存。
6. 实现未保存关闭确认。

完成标准：

- 用户可编辑并保存 Prompt。
- 关闭重开仍显示保存内容。

### 阶段 C：恢复默认与导入导出

1. 实现恢复默认确认弹窗。
2. 实现复制到剪贴板。
3. 实现从剪贴板导入。
4. 实现系统分享导出。
5. 补充 Toast/Snackbar。

完成标准：

- 恢复默认可覆盖当前 Prompt。
- 导入不会自动保存。
- 导出内容与编辑框一致。

### 阶段 D：入口与计划更新

1. 在 AI 服务配置弹窗加入 Prompt 管理入口。
2. 更新 `MVP PLAN.md`。
3. 静态检查。

## 18. 验收用例

1. 首次打开 Prompt 管理显示默认 Prompt。
2. 默认 Prompt hash 正确显示。
3. 修改 Prompt 后保存成功。
4. 关闭后重新打开仍显示修改后的 Prompt。
5. 修改 Prompt 后 hash 变化。
6. 恢复默认后文本回到内置默认 Prompt。
7. 恢复默认后 `isDefault=true`。
8. 空 Prompt 不能保存。
9. 有未保存修改时关闭弹窗会提示确认。
10. 从剪贴板导入后编辑框更新，但未自动保存。
11. 导入空文本时提示失败。
12. 复制 Prompt 后剪贴板内容正确。
13. 导出 Prompt 内容与编辑框一致。
14. Prompt 管理不发起 AI 请求。
15. Prompt 管理不解析章节。
16. Prompt 管理不修改 Segment 缓存。
17. Prompt 管理不影响 AI 服务配置。
18. 不修改数据库版本。

## 19. 静态验证清单

按项目规则不运行编译或 Gradle：

- `git diff --check` 通过。
- 不修改 `AppDatabase.version`。
- 不新增 Room Entity、DAO 或 migration。
- 不调用 `OpenAiCompatibleClient`。
- 不新增章节解析入口。
- 不修改 `HttpReadAloudService`。
- Prompt hash 使用规范化文本。
- 默认 Prompt 常量集中管理。
- strings 中英文齐全。
- 导入导出不泄露 API Key。

## 20. 风险控制

- **用户误改 Prompt**：提供恢复默认。
- **Prompt 太长不好编辑**：编辑框支持滚动和多行。
- **默认 Prompt 后续升级**：使用 version 记录。
- **缓存失效无法判断**：保存 promptHash。
- **范围膨胀**：MVP6B 不调用 AI，不解析章节。
- **误清缓存**：MVP6B 不清理缓存，MVP6D 再处理缓存失效。
- **导入污染 Prompt**：导入只填编辑框，不自动保存。

## 21. 完成定义

- 用户可以查看当前 AI Prompt。
- 用户可以编辑并保存 Prompt。
- 用户可以恢复默认 Prompt。
- 用户可以导入和导出 Prompt。
- Prompt hash、version、updatedAt 可读取。
- 后续 MVP6C 可直接读取 Prompt。
- 后续 MVP6D 可使用 promptHash 判断缓存失效。
- 不触发 AI 请求，不解析章节，不修改数据库。
