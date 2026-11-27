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
    /**
     * 当前登录工作微信号（可空）。与 service_account_id 同源，
     * 单独下发 `wechat_id` 以兼容 Customer Hub 中控的字段命名（docs/04 §2.3）。
     */
    val wechatId: String? = null,
    /**
     * 本人（员工）微信昵称，仅从右半屏本人气泡头像读取（docs/04 §2.2）。
     * 供中控在双开（主号 + 旧号）场景区分「这台手机现在登的是谁」。
     */
    val selfNickname: String? = null,
    /** 资料页已展示的标签（WP-26，仅 pageType=profile 时可能非空）。 */
    val profileTags: List<String> = emptyList(),
    /** 资料页已展示的地区文本（WP-26）。 */
    val profileRegion: String? = null,
    /**
     * WP-27 请求模式：
     *   * `peek` —— 轻量角标快检，仅更新悬浮球角标；
     *   * `open_profile` / `open_chat` —— 员工主动点击悬浮球展开完整面板。
     * 为空时保持 WP-26 的旧语义（服务端按 page_type 处理）。
     */
    val mode: String? = null,
    /**
     * WP-28：资料页直接读到的 11 位手机号（「电话/手机/描述」栏或备注名）。
     * 仅随本次请求回传，不落任何本地存储。
     */
    val contactPhone: String? = null,
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
        root.put("contact_phone", contactPhone ?: JSONObject.NULL)
        root.put("wechat_id", wechatId ?: serviceAccountId)
        root.put("self_nickname", selfNickname ?: JSONObject.NULL)
        val tagArr = JSONArray()
        profileTags.forEach { tagArr.put(it) }
        root.put("profile_tags", tagArr)
        root.put("profile_region", profileRegion ?: JSONObject.NULL)
        root.put("mode", mode ?: JSONObject.NULL)
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

    /** WP-26：岚时未建档，悬浮窗渲染「一键选渠道建入岚时」面板。 */
    PREPARE_HIS_CREATE("prepare_his_create"),

    /** WP-27：轻量角标快检（不展开面板，仅更新悬浮球角标）。 */
    PEEK("peek"),
    ;

    companion object {
        fun fromWire(value: String?): CenterAction =
            entries.firstOrNull { it.wireValue == value } ?: NONE
    }
}

/** 待建岚时卡片预览（中控已脱敏）。 */
data class HisDraft(
    val customerName: String,
    val contactWechatId: String?,
    val wechatMask: String?,
    val phoneMask: String?,
    val developerName: String?,
    val matchedSourceOne: String?,
    val matchedSourceTwo: String?,
    val matchedTags: List<String>,
    val wxAddedAt: String?,
    /** WP-28：待建草稿携带的手机号（安卓优先用 ProfileContext.phone，缺失时回退此值）。 */
    val phone: String? = null,
)

/** 一键建档渠道按钮。 */
data class ChannelOption(
    val sourceOne: String,
    val sourceTwo: String,
    val label: String,
    val recommended: Boolean,
)

/** 引流运营（岚时所属客服）候选。id 为空表示「无运营（自然/美团）」。 */
data class OperatorOption(
    val id: String,
    val name: String,
)

/** 档案归属席位。 */
data class ArchiveSeat(
    val role: String,
    val name: String,
)

/** 悬浮球面板「档案与资产」结构化数据（中控已脱敏）。 */
data class ArchiveCard(
    val customerNo: String,
    val name: String,
    val vipGrade: String?,
    val stage: String?,
    val protectStatus: String?,
    val availablePoints: String,
    val frozenPoints: String?,
    val depositBalance: String?,
    val phoneMask: String?,
    val seats: List<ArchiveSeat>,
    val lastVisitAt: String?,
    val hisBound: Boolean,
)

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
    /** WP-26：待建岚时卡片预览（action=prepare_his_create 时非空）。 */
    val hisDraft: HisDraft? = null,
    /** WP-26：一键建档渠道按钮（推荐项排第一）。 */
    val channelOptions: List<ChannelOption> = emptyList(),
    /** WP-26：建档成功后中控下发 ` #<档案号>`，安卓自动写入系统剪贴板。 */
    val autoCopyText: String? = null,
    /** WP-27：轻量 peek 角标（conflict / phone / archived / 空）。 */
    val badge: String? = null,
    /** WP-27：建档面板的引流运营候选（首项 id 为空 = 无运营）。 */
    val operatorOptions: List<OperatorOption> = emptyList(),
    /** WP-27：单聊/资料页面板的结构化档案资产卡。 */
    val archive: ArchiveCard? = null,
    /** WP-27：可一键补录的手机号（脱敏）与槽位（main / spare）。 */
    val pendingPhone: String? = null,
    val pendingMask: String? = null,
    val phoneSlot: String? = null,
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

        /** 渠道按钮上限（中控已截断到 24，这里再兜一层）。 */
        private const val MAX_CHANNELS = 24

        /** 识别到的项目标签上限。 */
        private const val MAX_TAGS = 8

        /** auto_copy_text 上限（` #` + 13 位档案号，留足余量）。 */
        private const val MAX_COPY_TEXT = 32

        /** 引流运营候选上限（中控已截断到 30，这里再兜一层）。 */
        private const val MAX_OPERATORS = 30

        fun parse(json: String): CenterResponse? = try {
            val root = JSONObject(json)
            val action = CenterAction.fromWire(root.optString("action", ""))
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
                hisDraft = parseHisDraft(root.optJSONObject("his_draft")),
                channelOptions = parseChannelOptions(root.optJSONArray("channel_options")),
                autoCopyText = root.optStringOrNull("auto_copy_text")?.take(MAX_COPY_TEXT),
                badge = root.optStringOrNull("badge")?.take(MAX_TEXT),
                operatorOptions = parseOperatorOptions(root.optJSONArray("operator_options")),
                archive = parseArchive(root.optJSONObject("archive")),
                pendingPhone = root.optStringOrNull("pending_phone")?.take(MAX_TEXT),
                pendingMask = root.optStringOrNull("pending_mask")?.take(MAX_TEXT),
                phoneSlot = root.optStringOrNull("phone_slot")?.take(MAX_TEXT),
            )
        } catch (_: Exception) {
            null
        }

        private fun parseHisDraft(obj: JSONObject?): HisDraft? {
            if (obj == null) return null
            val tags = ArrayList<String>()
            obj.optJSONArray("matched_tags")?.let { arr ->
                var i = 0
                while (i < arr.length() && tags.size < MAX_TAGS) {
                    val tag = arr.optString(i, "").trim()
                    if (tag.isNotEmpty()) tags.add(tag.take(MAX_TEXT))
                    i++
                }
            }
            return HisDraft(
                customerName = obj.optStringOrNull("customer_name")?.take(MAX_TEXT).orEmpty(),
                contactWechatId = obj.optStringOrNull("contact_wechat_id")?.take(MAX_TEXT),
                wechatMask = obj.optStringOrNull("wechat_mask")?.take(MAX_TEXT),
                phoneMask = obj.optStringOrNull("phone_mask")?.take(MAX_TEXT),
                developerName = obj.optStringOrNull("developer_name")?.take(MAX_TEXT),
                matchedSourceOne = obj.optStringOrNull("matched_source_one")?.take(MAX_TEXT),
                matchedSourceTwo = obj.optStringOrNull("matched_source_two")?.take(MAX_TEXT),
                matchedTags = tags,
                wxAddedAt = obj.optStringOrNull("wx_added_at")?.take(MAX_TEXT),
                phone = obj.optStringOrNull("phone")?.take(MAX_TEXT),
            )
        }

        private fun parseChannelOptions(arr: JSONArray?): List<ChannelOption> {
            if (arr == null) return emptyList()
            val out = ArrayList<ChannelOption>(MAX_CHANNELS)
            var i = 0
            while (i < arr.length() && out.size < MAX_CHANNELS) {
                val item = arr.optJSONObject(i)
                if (item != null) {
                    val one = item.optStringOrNull("source_one")?.take(MAX_TEXT).orEmpty()
                    val two = item.optStringOrNull("source_two")?.take(MAX_TEXT).orEmpty()
                    if (one.isNotEmpty()) {
                        val label = item.optStringOrNull("label")?.take(MAX_TEXT)
                            ?: if (two.isEmpty()) one else "$one / $two"
                        out.add(
                            ChannelOption(
                                sourceOne = one,
                                sourceTwo = two,
                                label = label,
                                recommended = item.optBoolean("recommended", false),
                            )
                        )
                    }
                }
                i++
            }
            return out
        }

        private fun parseOperatorOptions(arr: JSONArray?): List<OperatorOption> {
            if (arr == null) return emptyList()
            val out = ArrayList<OperatorOption>(MAX_OPERATORS)
            var i = 0
            while (i < arr.length() && out.size < MAX_OPERATORS) {
                val item = arr.optJSONObject(i)
                i++
                if (item == null) continue
                val name = item.optStringOrNull("name")?.take(MAX_TEXT) ?: continue
                out.add(
                    OperatorOption(
                        id = item.optStringOrNull("id")?.take(MAX_TEXT).orEmpty(),
                        name = name,
                    )
                )
            }
            return out
        }

        private fun parseArchive(obj: JSONObject?): ArchiveCard? {
            if (obj == null) return null
            val seats = ArrayList<ArchiveSeat>(6)
            obj.optJSONArray("seats")?.let { arr ->
                var i = 0
                while (i < arr.length() && seats.size < 6) {
                    val item = arr.optJSONObject(i)
                    i++
                    if (item == null) continue
                    val name = item.optStringOrNull("name")?.take(MAX_TEXT) ?: continue
                    seats.add(
                        ArchiveSeat(
                            role = item.optStringOrNull("role")?.take(MAX_TEXT).orEmpty(),
                            name = name,
                        )
                    )
                }
            }
            return ArchiveCard(
                customerNo = obj.optStringOrNull("customer_no")?.take(MAX_TEXT).orEmpty(),
                name = obj.optStringOrNull("name")?.take(MAX_TEXT).orEmpty(),
                vipGrade = obj.optStringOrNull("vip_grade")?.take(MAX_TEXT),
                stage = obj.optStringOrNull("stage")?.take(MAX_TEXT),
                protectStatus = obj.optStringOrNull("protect_status")?.take(MAX_TEXT),
                availablePoints = obj.optStringOrNull("available_points")?.take(MAX_TEXT) ?: "0",
                frozenPoints = obj.optStringOrNull("frozen_points")?.take(MAX_TEXT),
                depositBalance = obj.optStringOrNull("deposit_balance")?.take(MAX_TEXT),
                phoneMask = obj.optStringOrNull("phone_mask")?.take(MAX_TEXT),
                seats = seats,
                lastVisitAt = obj.optStringOrNull("last_visit_at")?.take(MAX_TEXT),
                hisBound = obj.optBoolean("his_bound", false),
            )
        }

        private fun JSONObject.optStringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            val v = optString(key, "")
            return v.ifBlank { null }
        }
    }
}

/** 候选反馈请求体（用户点「确认关联」/「不是同一人」/「一键建入岚时」）。 */
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
    /** WP-26：`create_his_dossier` 时表示员工点选了渠道并明确要求建入岚时。 */
    val event: String? = null,
    val sourceOne: String? = null,
    val sourceTwo: String? = null,
    val contactWechatId: String? = null,
    val profileNickname: String? = null,
    val profileTags: List<String> = emptyList(),
    val profileRegion: String? = null,
    /** WP-27：一键补录手机号的明文（仅本次回传，App 不留存）。 */
    val phone: String? = null,
    /** WP-27：引流运营（岚时 serviceList UUID）与姓名。 */
    val belongServiceId: String? = null,
    val belongServiceName: String? = null,
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
        root.put("event", event ?: JSONObject.NULL)
        root.put("source_one", sourceOne ?: JSONObject.NULL)
        root.put("source_two", sourceTwo ?: JSONObject.NULL)
        root.put("contact_wechat_id", contactWechatId ?: JSONObject.NULL)
        root.put("profile_nickname", profileNickname ?: JSONObject.NULL)
        root.put("profile_region", profileRegion ?: JSONObject.NULL)
        val tagArr = JSONArray()
        profileTags.forEach { tagArr.put(it) }
        root.put("profile_tags", tagArr)
        root.put("phone", phone ?: JSONObject.NULL)
        root.put("belong_service_id", belongServiceId ?: JSONObject.NULL)
        root.put("belong_service_name", belongServiceName ?: JSONObject.NULL)
        return root.toString()
    }
}
