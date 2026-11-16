package com.tencent.mm.assistant.net

import org.json.JSONArray
import org.json.JSONObject

/** 回传中控的请求体，字段与 PRD 模块 2 完全一致。 */
data class ReadReport(
    val serviceAccountId: String,
    val deviceId: String,
    val title: String?,
    val recentMessages: List<String>,
    val readStatus: String,
    val ts: Long,
    /** 会话去重键，仅用于本地状态机，不参与序列化。 */
    val sessionKey: String = "",
    /**
     * 页面类型：chat = 单聊页；profile = 个人资料页。
     * 资料页仅在**人工**打开时读取，用于识别对方微信号。
     */
    val pageType: String = PAGE_CHAT,
    /** 资料页上展示的微信号（仅 pageType=profile 时非空）。 */
    val contactWechatId: String? = null,
    /**
     * 资料页的"来源会话标题"。
     *
     * 关键字段：资料页是从某个单聊点进去的，因此该资料页属于那个会话对象。
     * 有了它，中控就能把「会话标题 → 微信号」绑定起来，
     * 后续同一会话的上报即可用微信号精确取任务，不再依赖昵称字符串匹配。
     */
    val linkedTitle: String? = null,
    /** 资料页顶部昵称（可能与会话标题不同：标题常是备注名）。 */
    val profileNickname: String? = null,
    /**
     * 统一身份键：有微信号时用微信号，否则退回会话标题。
     * 中控可用它作为"这个人"的唯一键，跨客服、跨会话匹配。
     */
    val identityKey: String? = null,
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("service_account_id", serviceAccountId)
        root.put("device_id", deviceId)
        root.put("title", title ?: JSONObject.NULL)
        val arr = JSONArray()
        recentMessages.forEach { arr.put(it) }
        root.put("recent_messages", arr)
        root.put("read_status", readStatus)
        root.put("ts", ts)
        root.put("page_type", pageType)
        root.put("contact_wechat_id", contactWechatId ?: JSONObject.NULL)
        root.put("linked_title", linkedTitle ?: JSONObject.NULL)
        root.put("profile_nickname", profileNickname ?: JSONObject.NULL)
        root.put("identity_key", identityKey ?: JSONObject.NULL)
        return root.toString()
    }

    companion object {
        const val PAGE_CHAT = "chat"
        const val PAGE_PROFILE = "profile"
    }
}

/** 中控响应动作。 */
enum class CenterAction(val wireValue: String) {
    SHOW_HINT("show_hint"),
    SHOW_CANDIDATES("show_candidates"),
    NO_MATCH("no_match"),
    NONE("none"),
    ;

    companion object {
        fun fromWire(value: String?): CenterAction =
            entries.firstOrNull { it.wireValue == value } ?: NONE
    }
}

data class Candidate(
    val label: String,
    val confidence: Double,
    val evidence: String,
)

data class CenterResponse(
    val action: CenterAction,
    val hint: String?,
    val candidates: List<Candidate>,
    val requestId: String?,
) {
    companion object {
        /** 越界防御：候选最多 3 条，避免悬浮窗被超长内容撑爆。 */
        const val MAX_CANDIDATES = 3

        /** 候选 label/evidence 的显示上限（短文本，够用即可）。 */
        private const val MAX_TEXT = 160

        /**
         * 提示正文上限。
         *
         * 不能沿用 160：中控可能返回多行说明（历史订单、注意事项等），
         * 截断到 160 会让用户看不到关键信息。这里放宽到 2000，
         * 超出部分由悬浮窗内部滚动承载。
         */
        private const val MAX_HINT = 2000

        fun parse(json: String): CenterResponse? = try {
            val root = JSONObject(json)
            val action = CenterAction.fromWire(root.optString("action", null))
            val hint = root.optStringOrNull("hint")?.take(MAX_HINT)
            val candidates = ArrayList<Candidate>(MAX_CANDIDATES)
            val arr = root.optJSONArray("candidates")
            if (arr != null) {
                var i = 0
                while (i < arr.length() && candidates.size < MAX_CANDIDATES) {
                    val item = arr.optJSONObject(i)
                    if (item != null) {
                        candidates.add(
                            Candidate(
                                label = item.optStringOrNull("label")?.take(MAX_TEXT).orEmpty(),
                                confidence = item.optDouble("confidence", 0.0),
                                evidence = item.optStringOrNull("evidence")?.take(MAX_TEXT).orEmpty(),
                            )
                        )
                    }
                    i++
                }
            }
            CenterResponse(
                action = action,
                hint = hint,
                candidates = candidates,
                requestId = root.optStringOrNull("request_id"),
            )
        } catch (_: Exception) {
            null
        }

        private fun JSONObject.optStringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            val v = optString(key, "")
            return v.ifBlank { null }
        }
    }
}

/** 候选反馈请求体（用户点「确认关联」/「不是同一人」）。 */
data class CandidateFeedback(
    val requestId: String?,
    val serviceAccountId: String,
    val deviceId: String,
    val title: String?,
    val label: String,
    val candidateIndex: Int,
    /** true = 确认关联；false = 不是同一人。 */
    val accepted: Boolean,
    val ts: Long,
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("request_id", requestId ?: JSONObject.NULL)
        root.put("service_account_id", serviceAccountId)
        root.put("device_id", deviceId)
        root.put("title", title ?: JSONObject.NULL)
        root.put("label", label)
        root.put("candidate_index", candidateIndex)
        root.put("decision", if (accepted) "confirm" else "reject")
        root.put("accepted", accepted)
        root.put("ts", ts)
        return root.toString()
    }
}
