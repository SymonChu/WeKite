package com.github.wekite.features.items.chat.aichat

import com.github.wekite.utils.WeLogger

/**
 * 沟通知识库（MIT，来源 goutoujunshi，版权与许可随资产保留）。
 * 按 chatroom / 单聊选取不同知识子集，注入回复生成 system 消息。
 */
object ReplyKnowledge {
    private const val TAG = "AiReplyKnowledge"

    // 静态知识内容（编译期固定，不读外部文件；来自 MIT 许可的 goutoujunshi 知识库精简版，
    // 仅保留通用沟通原则，详见 assets 中版权声明）
    private val CORE_KNOWLEDGE = """
        沟通基本原则：
        1. 先回应情绪，再回应事情；对方情绪未平时不建议直接讲道理。
        2. 提问优于猜测：不确定对方意图时，用开放式问题澄清，不替对方下结论。
        3. 承认优先：被指出问题时先确认对方感受与事实，再解释，不辩解式开场。
        4. 明确承诺：答应的事给出时间点；做不到就明说，不模糊答应。
        5. 分寸感：关系越近，语气越自然口语；不替对方决定，多用商量语气。
        6. 避免说教与反问压迫；一条消息一个重点，方便对方接话。
        7. 拒绝时给出理由与替代方案；安慰时承认情绪合理，不给人生建议。
    """.trimIndent()

    private val GROUP_KNOWLEDGE = """
        群聊补充原则：
        1. 发言面向所有人，不预设某人一定回应；@某人时先说明缘由。
        2. 不接私人恩怨话题；群内矛盾建议私聊解决。
        3. 接话要接最新话题，不翻旧话头。
    """.trimIndent()

    fun load(isGroup: Boolean = false): String =
        if (isGroup) "$CORE_KNOWLEDGE\n\n$GROUP_KNOWLEDGE" else CORE_KNOWLEDGE

    @Suppress("unused")
    private fun logLoad() = WeLogger.d(TAG, "knowledge loaded")
}
