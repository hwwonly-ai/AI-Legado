# MVP 7.1 未匹配角色自动归档与动态次要配角执行方案

## 1. 目标

在 MVP7 已完成角色结构升级、8 个固定龙套和已有角色匹配的基础上，处理 AI Segment 中未匹配到现有角色的 `roleName`。

MVP7.1 使用可配置统计窗口维护动态次要配角 Top10：用户可选择“全书累计统计”或“最近 20 章统计”。统计窗口内出现次数最多的 10 个未锁定候选成为次要配角；随着章节推进，如果其他角色对白次数超过现有次要配角，则将次数较少且未锁定的次要配角降入对应属性龙套别名。

主角和主要配角只允许用户手动管理，AI 不能创建或修改。

## 2. AI 返回结构

AI 对白标注返回新增 `roleAttribute`：

```json
{
  "id": 12,
  "role": "dialog",
  "roleName": "王丽",
  "roleAttribute": "middle_female",
  "emotion": "neutral",
  "emotionIntensity": 1.0
}
```

`roleAttribute` 必须是 8 种角色属性之一：

```text
boy, young_male, middle_male, elder_male,
girl, young_female, middle_female, elder_female
```

解析策略：

- 缺失或非法时默认 `middle_male`，并记录日志。
- `roleAttribute` 只用于自动创建次要配角和归入龙套。
- AI 不返回、也不决定 `roleCategory`。

## 3. 统计窗口与对白次数

在 `Book.ReadConfig` 中保存统计模式和角色对白统计：

```kotlin
enum class RoleMentionStatWindow {
    ALL_BOOK,
    RECENT_20_CHAPTERS
}

data class BookRoleMentionStat(
    val roleKey: String,
    var displayName: String,
    var roleAttribute: String,
    var totalDialogCount: Int,
    var chapterDialogCounts: MutableMap<Int, Int>,
    var lastSeenChapter: Int,
    var roleId: String? = null
)
```

书籍级隔离：

- `roleMentionStatWindow` 和 `BookRoleMentionStat` 都保存在当前书的 `Book.ReadConfig`。
- 不同书籍的未匹配角色统计、Top10 次要配角、龙套 aliases 互不共享。
- 最近 20 章统计只在当前书内计算，不跨书统计章节。

配置项：

- `roleMentionStatWindow = ALL_BOOK`：全书累计统计，默认值。
- `roleMentionStatWindow = RECENT_20_CHAPTERS`：只按最近 20 章统计。

UI 入口：

- 在书籍角色管理的自动归档设置中增加“次要配角统计范围”。
- 选项：`全书累计` / `最近 20 章`。
- 切换后提示会重新计算未锁定次要配角；锁定次要配角不受影响。

统计规则：

- 每次 AI 标注成功后，按规范化 `roleName` 累计 `totalDialogCount` 和当前章节的 `chapterDialogCounts[chapterIndex]`。
- Top10 排序使用当前配置对应的统计窗口。
- 全书累计模式：使用 `totalDialogCount`。
- 最近 20 章模式：汇总当前章节及之前最近 20 个已解析章节的 `chapterDialogCounts`；不足 20 章时按已有章节统计。
- 同一段对白只累计一次，避免缓存命中或重复播放导致计数膨胀。
- `roleAttribute` 使用最近一次合法 AI 返回值更新。
- `lastSeenChapter` 使用最近出现章节更新。
- 切换统计窗口后，重新计算动态次要配角 Top10，不清除原始 mention 记录。

冲突排除：

- 如果 `roleName` 或规范化 key 命中男主、女主、主要配角的名称或别名，不进入自动候选。
- 如果 `roleName` 命中已锁定角色，不进入动态调整。
- 如果与现有角色名或别名冲突但不能稳定解析，跳过写入并记录日志。

## 4. 动态次要配角 Top10

候选范围：

- 未命中主角和主要配角。
- 未锁定。
- 非固定龙套。
- 有合法 `roleName` 和 `roleAttribute`。
- 不属于泛称 / 群体 / 低价值称谓。

排序规则：

```text
dialogCount desc
lastSeenChapter desc
displayName asc
```

其中 `dialogCount` 是根据当前统计窗口计算出的临时值，不是单独持久化字段。

动态规则：

- 排名前 10 的候选自动创建或保持为 `minor_supporting`。
- Top10 之外的未锁定次要配角降入同属性龙套。
- 锁定的次要配角不参与升降级。
- 用户手动设置为男主、女主、主要配角后，AI 不再调整该角色。

自动创建次要配角字段：

```kotlin
BookReadAloudRole(
    id = UUID.randomUUID().toString(),
    name = displayName,
    aliases = mutableListOf(),
    note = "AI 自动创建：统计窗口对白次数 Top10 次要配角",
    voices = hashMapOf(currentTtsEngine to "@extra:${roleAttribute}"),
    locked = false,
    roleCategory = RoleCategory.MINOR_SUPPORTING,
    roleAttribute = roleAttribute,
    autoManaged = true,
    systemRole = false
)
```

音色规则：

- 次要配角默认跟随同属性龙套音色。
- 例：`王丽 + middle_female` 默认使用“龙套·中年女性”的音色。
- 如果次要配角设置了自定义音色，则优先使用自定义音色。
- 如果同属性龙套音色变化，未自定义音色的次要配角跟随变化。

## 5. 降级到龙套

当未锁定次要配角退出 Top10：

1. 找到该角色 `roleAttribute` 对应的固定龙套。
2. 将角色名加入该龙套 aliases。
3. 将该次要配角从独立角色列表移除或标记为非活跃；实现时优先选择“移除独立角色”，避免 UI 中重复出现。
4. 保留统计记录，后续如果对白次数再次进入 Top10，可重新创建或恢复为次要配角。

降级约束：

- 锁定的次要配角不降级。
- 男主、女主、主要配角不降级。
- 龙套别名写入前必须检查与所有角色名 / 别名无冲突。

## 6. 泛称归入龙套

泛称、群体或低价值称谓不进入次要配角候选，直接归入对应属性龙套别名。

示例泛称词表：

```text
对白、未知、男人、女人、男子、女子、老人、老者、少年、少女、孩子、
路人、众人、旁人、仆人、侍卫、士兵、店员、老板、司机、医生、护士
```

示例：

- `侍卫 + young_male` → “龙套·少年男性” aliases。
- `老妇人 + elder_female` → “龙套·老年女性” aliases。
- `侍女 + young_female` → “龙套·少年女性” aliases。

泛称写入规则：

- 不创建独立角色。
- 不跨属性写入。
- 如果别名已存在，不重复写。
- 如果别名与主角或主要配角冲突，不写入。

## 7. 接入位置

建议在 AI 标注结果进入当前章节 Segment 后、TTS 渲染前处理：

1. 先用 MVP7 matcher 匹配已有角色。
2. 命中则使用已有角色音色，不调整分类。
3. 未命中则记录 mention stat。
4. 根据当前统计窗口 Top10 重算动态次要配角。
5. 对泛称角色写入对应龙套 aliases。
6. 更新 matcher 内存索引。
7. 再解析最终角色音色。

不改变 AI Segment 缓存 key。

## 8. 日志规范

沿用 tag：`roleMatch`。

示例：

```text
roleMatch stat chapter=12 roleName=王丽 attr=middle_female count=38 text=王丽轻声道：“我知道。”
roleMatch minorPromote roleName=王丽 attr=middle_female count=38 roleId=...
roleMatch minorDemote roleName=黑衣人 attr=middle_male count=12 extraRole=龙套·中年男性
roleMatch extraAlias roleName=侍卫 attr=young_male extraRole=龙套·少年男性 text=侍卫齐声应是。
roleMatch autoSkip roleName=王丽 reason=conflictWithLead
```

测试期可输出对白正文；仍不输出 Prompt、API Key、完整 AI 原始响应。

## 9. 验证计划

静态检查：

- `rg "roleAttribute" app/src/main/java/io/legado/app/model/ai app/src/main/java/io/legado/app/model`
- `rg "BookRoleMentionStat|totalDialogCount|chapterDialogCounts|lastSeenChapter" app/src/main/java/io/legado/app`
- `rg "RoleMentionStatWindow|RECENT_20_CHAPTERS|ALL_BOOK" app/src/main/java/io/legado/app`
- `rg "minorPromote|minorDemote|extraAlias|autoSkip" app/src/main/java/io/legado/app`
- `rg "@extra:|龙套·" app/src/main/java/io/legado/app app/src/main/res`
- 确认 AI 不写 `male_lead` / `female_lead` / `major_supporting`。
- 确认锁定次要配角不参与动态调整。
- 确认未新增 Room Entity、DAO、migration。

Gradle 编译验证：

- `./gradlew :app:assembleDebug`

## 10. 验收用例

- 全书累计模式下，`王丽 + middle_female` 全书对白次数进入 Top10，自动成为次要配角。
- 最近 20 章模式下，只按最近 20 章对白次数计算 Top10。
- 切换全书累计 / 最近 20 章统计后，动态次要配角重新计算。
- `王丽` 默认使用“龙套·中年女性”的音色。
- 后续其他角色次数超过王丽，且王丽未锁定，王丽降入“龙套·中年女性”别名。
- 锁定的次要配角不会被降级。
- `王丽` 不能与男主、女主、主要配角的名称或别名冲突。
- `侍卫 + young_male` 不进入次要配角 Top10 时，加入“龙套·少年男性”别名。
- AI 不能创建或修改男主角、女主角、主要配角。
- 达到 Top10 后，第 11 名未锁定候选不创建独立次要配角。

## 11. 风险与处理

- **动态调整抖动**：全书累计模式更稳定；最近 20 章模式更贴合当前剧情但可能更频繁变化。锁定角色不参与调整。
- **角色档案污染**：主角和主要配角冲突时跳过；泛称只进龙套别名。
- **音色不一致**：次要配角默认跟随同属性龙套，自定义音色优先。
- **统计膨胀**：同一段对白只累计一次，避免重复播放增加次数。
- **范围膨胀**：不做模糊匹配、角色合并、当前段修正或导入导出；当前段修正不纳入当前 V1 MVP 路线。

## 12. 完成定义

- AI 返回支持 `roleAttribute`。
- 可配置全书累计统计或最近 20 章统计。
- 未锁定次要配角按 Top10 动态调整。
- Top10 外未锁定次要配角可降入同属性龙套。
- 次要配角默认跟随同属性龙套音色。
- 主角和主要配角不被 AI 创建或修改。
- 不新增数据库表、不升级数据库版本。
- 静态检查通过，`./gradlew :app:assembleDebug` 通过。

## 13. 完成记录

状态：已完成，已验证。

完成内容：

- AI 标注解析支持 `roleAttribute`，非法或缺失时回退到 `middle_male` 并记录日志。
- 默认 AI Prompt 已要求返回合法 `roleAttribute`，Prompt 版本已递增。
- `Book.ReadConfig` 已保存统计窗口和角色 mention 统计。
- 已新增自动归档协调器：
  - 统计 AI 标注成功后的未匹配对白角色。
  - 按 `全书累计` / `最近 20 章` 计算动态 Top10 次要配角。
  - 未锁定 Top10 外自动次要配角下放到同属性 `龙套·...` 别名。
  - 泛称角色直接归入同属性龙套。
- 角色音色支持 `@extra:{roleAttribute}`，自动次要配角默认跟随同属性龙套音色。
- 角色管理 UI 已增加统计范围切换。
- 已增加 AI 标注缓存清理、当前书缓存清理和自动归档重置入口。
- 已修复 OnePlus 锁屏时 AI 标注请求容易被中断的问题：
  - 朗读前台服务声明 `mediaPlayback|dataSync`。
  - AI 请求日志增加电池优化状态。
  - AI HTTP 请求改为可取消异步调用。
  - AI 标注阶段单独持有 wake lock / wifi lock，并用引用计数避免嵌套流程提前释放。

验证记录：

- 静态检查已覆盖 `roleAttribute`、统计窗口、mention 统计、自动升降级日志、`@extra:` 和 `龙套·` 相关实现。
- Gradle 编译已通过：`./gradlew :app:assembleDebug`。
- 真机验证已覆盖：
  - AI 标注成功后的 `stat` / `minorPromote` / `minorDemote` 日志。
  - 龙套默认音色和 `@extra:` 跟随逻辑。
  - 次要配角超过 10 个后的动态下放。
  - OnePlus 无线 ADB 锁屏场景日志观察。
