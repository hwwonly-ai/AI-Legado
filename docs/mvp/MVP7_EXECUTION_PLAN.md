# MVP 7 角色结构升级、UI 重设计与已有角色匹配执行方案

## 1. 目标

升级 MVP3.4 的书籍级角色档案结构，增加角色分类、角色属性和 8 个固定龙套角色；重设计书籍角色管理 UI；并让 AI `roleName` 只匹配已有角色和别名，复用角色音色实现跨章节稳定朗读。

MVP7 不处理未匹配角色写入。AI 不创建角色、不修改角色分类、不修改角色属性、不修改别名、不修改音色。未匹配角色只输出 `roleMatch miss` 日志，留到 MVP7.1 按全书对白次数动态归档。

## 2. 数据模型

在现有 `BookReadAloudRole` 上扩展字段：

```kotlin
var roleCategory: String = RoleCategory.MINOR_SUPPORTING
var roleAttribute: String = RoleAttribute.MIDDLE_MALE
var autoManaged: Boolean = false
var systemRole: Boolean = false
```

书籍级隔离：

- 所有角色数据保存在当前书的 `Book.ReadConfig.readAloudRoles`。
- 不同书籍的角色、别名、分类、属性、锁定状态和角色音色互不共享。
- 8 个固定龙套也是每本书各自初始化一套；修改某本书的龙套别名或音色，不影响其他书。
- 角色匹配只读取当前书的角色档案，不跨书匹配。

### 角色分类

```text
male_lead         男主角，最多 1 人，AI 不可写
female_lead       女主角，最多 1 人，AI 不可写
major_supporting  主要配角，最多 10 人，AI 不可写
minor_supporting  次要配角，最多 10 人，MVP7.1 可动态调整
extra             龙套，固定 8 个系统角色
```

规则：

- 男主角、女主角、主要配角只允许用户手动设置。
- 次要配角可以由用户手动设置；MVP7 只展示和匹配，不自动调整。
- 龙套为系统角色，`systemRole=true`，不可删除，不可改名，不可改分类和属性。

### 角色属性

```text
boy            男童
young_male     少年男性
middle_male    中年男性
elder_male     老年男性
girl           女童
young_female   少年女性
middle_female  中年女性
elder_female   老年女性
```

规则：

- 男主角只能选择男性属性。
- 女主角只能选择女性属性。
- 主要配角、次要配角可选择任一属性。
- 龙套角色属性固定。

### 固定龙套角色

打开角色管理或执行角色匹配前，确保当前书存在 8 个固定龙套：

```text
龙套·男童
龙套·少年男性
龙套·中年男性
龙套·老年男性
龙套·女童
龙套·少年女性
龙套·中年女性
龙套·老年女性
```

龙套字段建议：

- `roleCategory = extra`
- `roleAttribute = 对应 8 种属性`
- `systemRole = true`
- `autoManaged = true`
- `locked = false`
- 默认音色跟随书籍对白音色，用户可单独配置。

## 3. 匹配与音色策略

### 匹配规则

新增轻量 matcher：

```kotlin
object BookReadAloudRoleMatcher {
    fun normalizeRoleKey(name: String): String
    fun findByNameOrAlias(book: Book, roleName: String): BookReadAloudRole?
    fun allRoleKeys(book: Book): Map<String, BookReadAloudRole>
}
```

匹配顺序：

1. 角色主名精确匹配。
2. 角色别名精确匹配。
3. 未命中返回 null。

`normalizeRoleKey`：

- trim。
- 连续空白压缩为单个空格。
- 英文使用 lowercase。
- 中文、日文不做拼音化、不做繁简转换。
- 空字符串不参与匹配。

### 音色解析

命中角色后：

1. 获取当前 TTS 引擎 `ttsEngine`。
2. 调用 `book.getRoleVoice(role.id, ttsEngine)`。
3. 如果返回非空音色，使用该音色。
4. 如果返回空，继续走现有默认对白音色回退。

未匹配角色：

- 不创建角色。
- 不写入别名。
- 不归入龙套。
- 使用默认对白音色。
- 输出 `roleMatch miss`，包含 `roleName` 与测试期对白正文。

## 4. UI 重设计

角色列表从单列表改为分组列表，使用现有 Dialog + RecyclerView 风格，不引入新框架。

```text
┌─────────────────────────────────────┐
│ 书籍角色                         ＋ │
├─────────────────────────────────────┤
│ 全部 32 | 主角 2 | 主要 6 | 次要 10 | 龙套 8 │
├─────────────────────────────────────┤
│ 主角                                │
│ 男主角  张三        中年男性 🔒 voice_a │
│ 女主角  李月        少年女性    默认对白 │
├─────────────────────────────────────┤
│ 主要配角 6 / 10                     │
│ 王五      老年男性    别名 2  voice_b │
├─────────────────────────────────────┤
│ 次要配角 10 / 10                    │
│ 王丽      中年女性    次数 38  跟随龙套 │
│ 黑衣人    中年男性    次数 21  默认对白 │
├─────────────────────────────────────┤
│ 龙套                                │
│ 龙套·男童      别名 4   voice_child_m │
│ 龙套·少年男性  别名 8   voice_young_m │
│ 龙套·中年女性  别名 12  voice_mid_f   │
└─────────────────────────────────────┘
```

角色编辑页：

```text
┌─────────────────────────────────────┐
│ 编辑角色                            │
├─────────────────────────────────────┤
│ 角色名 [ 王丽 ]                     │
│                                     │
│ 角色分类                            │
│ [男主] [女主] [主要配角] [次要配角] │
│                                     │
│ 角色属性                            │
│ [男童] [少年男] [中年男] [老年男]   │
│ [女童] [少年女] [中年女] [老年女]   │
│                                     │
│ 别名 [ 王姑娘, 王姐 ]               │
│                                     │
│ 音色                                │
│ ( ) 跟随同属性龙套                  │
│ ( ) 跟随书籍对白                    │
│ ( ) 自定义 [ voice_xxx ]            │
│                                     │
│ [ ] 锁定，不参与 AI 动态调整         │
│ 备注 [ ... ]                        │
├─────────────────────────────────────┤
│ 取消                         保存   │
└─────────────────────────────────────┘
```

说明：

- MVP7 阶段仍保留手动输入自定义音色 ID。
- MVP8.1 会将“自定义 [ voice_xxx ]”替换为“选择音色”入口，从角色侧打开轻量音色选择器。

龙套编辑页差异：

```text
角色名：龙套·中年女性       不可修改
分类：龙套                  不可修改
属性：中年女性              不可修改
别名：王妈、老板娘、妇人     可编辑
音色：可编辑
删除：隐藏
锁定：隐藏
```

## 5. 校验规则

- 男主角最多 1 人。
- 女主角最多 1 人。
- 主要配角最多 10 人。
- 次要配角最多 10 人。
- 主角和主要配角分类只允许用户手动设置。
- 角色名不能与任何角色名或别名冲突。
- 别名不能与任何角色名或别名冲突。
- 次要配角不能与男主、女主、主要配角的名称或别名冲突。
- 龙套角色不可删除，不能修改 `roleCategory` / `roleAttribute` / `systemRole`。

## 6. 缓存与日志

MVP7 不改变 AI Segment 缓存 key。

测试期临时策略：

- 用户保存角色音色后，清理当前书当前章节音频缓存。
- 只清音频缓存，不清 AI Segment 缓存和规则 Segment 缓存。
- 正式按角色 / 音色 / Provider 维度精确失效留到 MVP10。

日志 tag：`roleMatch`。

测试期允许输出对白正文，方便验证匹配：

```text
roleMatch hit chapter=12 segment=34 roleName=张三 roleId=... category=male_lead attr=middle_male hasVoice=true text=张三冷笑道：“你终于来了。”
roleMatch miss chapter=12 segment=36 roleName=黑衣人 text=黑衣人冷冷地说：“跟我走。”
roleMatch fallbackVoice chapter=12 segment=37 roleId=... reason=noRoleVoice text=张三叹了口气。
```

日志仍不输出 Prompt、API Key、完整 AI 原始响应。

## 7. 验证计划

静态检查：

- `rg "roleCategory|roleAttribute|autoManaged|systemRole" app/src/main/java/io/legado/app`
- `rg "BookReadAloudRoleMatcher|normalizeRoleKey|findByNameOrAlias" app/src/main/java/io/legado/app`
- `rg "龙套·|male_lead|female_lead|major_supporting|minor_supporting|extra" app/src/main/java/io/legado/app app/src/main/res`
- `rg "roleMatch" app/src/main/java/io/legado/app`
- 确认 MVP7 没有自动创建未匹配角色的逻辑。
- 确认未新增 Room Entity、DAO、migration。

Gradle 编译验证：

- `./gradlew :app:assembleDebug`

## 8. 验收用例

- 已有角色 `张三`，AI 返回 `roleName=张三`，命中并使用其音色。
- 已有角色别名 `三哥`，AI 返回 `roleName=三哥`，命中张三。
- AI 返回 `王丽`，角色档案不存在，MVP7 不创建角色，只输出 miss。
- 8 个龙套固定存在，不可删除。
- 男主、女主、主要配角数量超限时保存失败。
- 龙套角色可编辑别名和音色，但不可改名、删除、改分类、改属性。
- 角色音色变更后，当前章节音频缓存被清理用于测试验证。

## 9. 完成定义

- 角色档案支持分类、属性、自动管理标记和系统角色标记。
- 8 个固定龙套可自动补齐。
- 角色 UI 按主角、主要配角、次要配角、龙套分组展示。
- AI 只匹配已有角色，不写未匹配角色。
- 匹配成功时按当前 TTS 引擎使用角色音色。
- 不新增数据库表、不升级数据库版本。
- 静态检查通过，`./gradlew :app:assembleDebug` 通过。
