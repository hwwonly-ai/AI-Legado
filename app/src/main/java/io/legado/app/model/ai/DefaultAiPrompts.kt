package io.legado.app.model.ai

import io.legado.app.utils.MD5Utils

/**
 * AI 章节解析 Prompt 的内置默认值与版本。
 *
 * MVP6B 只保存 Prompt，不调用 AI，不验证输出。
 * 默认 Prompt 结构变更时递增 [DEFAULT_AI_PROMPT_VERSION]，便于后续提示“默认 Prompt 已更新”。
 */
object DefaultAiPrompts {

    const val DEFAULT_AI_PROMPT_VERSION = 4

    val DEFAULT_AI_PROMPT: String = """
        你是一个小说对白说话人和情绪标注器。

        任务：
        根据规则已经切好的对白和上下文，判断每句对白的说话人和情绪。

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
        10. 只输出 JSON，不输出解释说明。

        判断规则：
        - 前文若出现“甲说道/问道/笑道/拱手问道：”，通常当前对白为甲。
        - 前文若出现“甲问/看向/询问乙”，且当前对白像回答，优先判定为乙。
        - 对白后紧跟“甲说道/问道/反驳/诘问/喊道/骂道/答道/没忍住”等动作归因时，当前对白通常为甲。
        - 例：“……？”黑夫没忍住，开始诘问他。当前对白说话人是黑夫。
        - 后文若是另起一句并用于引出下一句对白，才不要反推当前对白。
        - 短引号内容若像术语、称号、罪名、律法条目、案由，而不是人物正在说的话，返回 role="narrator"。
        - 相邻已标注对白的 roleName 可用于判断轮流对话。
        - candidates 是候选角色名，优先从中选择；无法确定时用 "对白"。

        你需要返回：
        - id：输入对白 id。
        - role："dialog" 或 "narrator"。
        - roleName：具体说话人名称，无法确定时为 "对白"。
        - emotion：情绪，例如 neutral、happy、sad、angry、fear、surprise。
        - emotionIntensity：情绪强度。

        输出 JSON 格式：
        {
          "labels": [
            {
              "id": 1,
              "role": "dialog",
              "roleName": "黑夫",
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
