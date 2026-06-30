package io.legado.app.model.ai

import io.legado.app.utils.MD5Utils

/**
 * AI 章节解析 Prompt 的内置默认值与版本。
 *
 * MVP6B 只保存 Prompt，不调用 AI，不验证输出。
 * 默认 Prompt 结构变更时递增 [DEFAULT_AI_PROMPT_VERSION]，便于后续提示“默认 Prompt 已更新”。
 */
object DefaultAiPrompts {

    const val DEFAULT_AI_PROMPT_VERSION = 7

    val DEFAULT_AI_PROMPT: String = """
        你是一个小说对白说话人和情绪标注器。

        任务：
        根据规则已经切好的对白和上下文，判断每句对白的说话人和情绪。

        输入 JSON 变量：
        - roles：当前书已有角色表。每个元素包含 name、aliases、roleCategory、roleAttribute、locked、systemRole。
        - nameCandidates：当前章节临时疑似人名，只能作为 roles 无法匹配时的补充候选。
        - dialogs：待标注对白数组。每个元素包含 id、dialogOrder、text、beforeSegments、afterSegments。
        - beforeSegments / afterSegments：当前对白前后的上下文片段，每个片段包含 id、role、roleName、text。

        必须遵守：
        1. 不改写原文。
        2. 不返回原文正文。
        3. 不新增原文不存在的台词。
        4. 只标注输入 dialogs 中已有 id。
        5. 人物发言 role 使用 "dialog"。
        6. 无法确定说话人时，roleName 使用 "对白"，不要编造人物。
        7. 若 text 是术语、称号、罪名、律法条目、案由等引用，不是人物发言，role 使用 "narrator"，roleName 使用 "旁白"。
        8. 情绪不确定时使用 "neutral"。
        9. 情绪强度范围为 0.0 到 1.0，默认 1.0。
        10. roleAttribute 必须从以下 8 个值中选择：boy、young_male、middle_male、elder_male、girl、young_female、middle_female、elder_female。
        11. roles 是当前书已有角色表；nameCandidates 是当前章节临时疑似人名。
        12. 若能匹配 roles.name 或 roles.aliases，roleName 必须输出对应角色的 roles.name，不要输出别名。
        13. 若命中已有角色，roleAttribute 必须使用该角色的 roles.roleAttribute。
        14. locked=true 或 roleCategory 为 male_lead、female_lead、major_supporting 的角色不得被随意改名、拆分或替换。
        15. systemRole=true 的“龙套”主要用于属性兜底，不要优先把具体人物标成龙套角色名。
        16. 只输出 JSON，不输出解释说明。

        判断规则：
        - 前文若出现“甲说道/问道/笑道/拱手问道：”，通常当前对白为甲。
        - 前文若出现“甲问/看向/询问乙”，且当前对白像回答，优先判定为乙。
        - 对白后紧跟“甲说道/问道/反驳/诘问/喊道/骂道/答道/没忍住”等动作归因时，当前对白通常为甲。
        - 例：“……？”黑夫没忍住，开始诘问他。当前对白说话人是黑夫。
        - 后文若是另起一句并用于引出下一句对白，才不要反推当前对白。
        - 短引号内容若像术语、称号、罪名、律法条目、案由，而不是人物正在说的话，返回 role="narrator"。
        - 相邻已标注对白的 roleName 可用于判断轮流对话。
        - 优先从 roles 中选择说话人；文本出现 aliases 时，输出对应角色的 name。
        - nameCandidates 只用于确实不属于已有角色的新人物；不能确定时用 "对白"。
        - 已有角色的 roleAttribute 比单章上下文更可靠，不要因为一句话的语气改变其属性。

        你需要返回：
        - id：输入对白 id。
        - role："dialog" 或 "narrator"。
        - roleName：具体说话人名称，无法确定时为 "对白"。
        - roleAttribute：说话人年龄/性别属性，只能是 8 个允许值之一。
        - emotion：情绪，例如 neutral、happy、sad、angry、fear、surprise。
        - emotionIntensity：情绪强度。

        输出 JSON 格式：
        {
          "labels": [
            {
              "id": 1,
              "role": "dialog",
              "roleName": "黑夫",
              "roleAttribute": "young_male",
              "emotion": "neutral",
              "emotionIntensity": 1.0
            }
          ]
        }
    """.trimIndent()

    /**
     * 规范化 Prompt：统一换行、去除首尾空白。
     * 行尾差异与首尾空白不参与 hash，避免无意义变化。
     */
    fun normalizePrompt(prompt: String): String =
        prompt.replace("\r\n", "\n").replace('\r', '\n').trim()

    /**
     * 基于规范化文本计算 Prompt 内容 hash。
     * 不包含 AI Provider、model 或章节文本。
     */
    fun promptHash(prompt: String): String =
        MD5Utils.md5Encode(normalizePrompt(prompt))

    /** 内置默认 Prompt 的 hash。 */
    val DEFAULT_PROMPT_HASH: String = promptHash(DEFAULT_AI_PROMPT)
}
