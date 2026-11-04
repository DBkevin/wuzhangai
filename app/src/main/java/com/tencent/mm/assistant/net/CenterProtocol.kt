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
        return root.toString()
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
        /** 越界防御：候选最多 3 条，文本截断，避免悬浮窗被超长内容撑爆。 */
        const val MAX_CANDIDATES = 3
        private const val MAX_TEXT = 160

        fun parse(json: String): CenterResponse? = try {
            val root = JSONObject(json)
            val action = CenterAction.fromWire(root.optString("action", null))
            val hint = root.optStringOrNull("hint")?.take(MAX_TEXT)
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
