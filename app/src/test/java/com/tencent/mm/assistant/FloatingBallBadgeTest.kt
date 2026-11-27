package com.tencent.mm.assistant

import com.tencent.mm.assistant.net.CandidateFeedback
import com.tencent.mm.assistant.net.CenterResponse
import com.tencent.mm.assistant.overlay.FloatingBallBadge
import com.tencent.mm.assistant.util.LastChoiceMemory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WP-27：苹果式悬浮球角标、上次渠道/运营记忆与二级面板协议的单测。
 *
 * 全部为纯 JVM 逻辑（不实例化 Android View、不触碰 ClipboardManager），
 * 保证「100% 零操控微信」红线不被测试代码破坏。
 */
class FloatingBallBadgeTest {

    // ------------------------------------------------------------ 角标

    @Test
    fun `badge text mapping matches four states`() {
        assertEquals("撞", FloatingBallBadge.textFor("conflict"))
        assertEquals("号", FloatingBallBadge.textFor("phone"))
        assertEquals("档", FloatingBallBadge.textFor("archived"))
        assertEquals("", FloatingBallBadge.textFor(""))
        assertEquals("", FloatingBallBadge.textFor("unknown"))
        assertEquals("", FloatingBallBadge.textFor(null))
    }

    @Test
    fun `badge normalizes case and whitespace`() {
        assertEquals(FloatingBallBadge.CONFLICT, FloatingBallBadge.normalize(" Conflict "))
        assertEquals(FloatingBallBadge.PHONE, FloatingBallBadge.normalize("PHONE"))
        assertEquals(FloatingBallBadge.NONE, FloatingBallBadge.normalize("bogus"))
    }

    @Test
    fun `idle alpha drops only when no badge`() {
        assertEquals(0f, FloatingBallBadge.alphaFor(idle = false, panelOpen = true, hasBadge = true), 0.0001f)
        assertEquals(0.45f, FloatingBallBadge.alphaFor(idle = true, panelOpen = false, hasBadge = false), 0.0001f)
        assertEquals(1f, FloatingBallBadge.alphaFor(idle = true, panelOpen = false, hasBadge = true), 0.0001f)
        assertEquals(1f, FloatingBallBadge.alphaFor(idle = false, panelOpen = false, hasBadge = false), 0.0001f)
    }

    // ------------------------------------------------------------ 上次渠道/运营记忆

    @Test
    fun `channel memory round trip`() {
        val raw = LastChoiceMemory.encodeChannel("吴院IP", "吴院快手")
        val decoded = LastChoiceMemory.decodeChannel(raw)
        assertNotNull(decoded)
        assertEquals("吴院IP", decoded!!.sourceOne)
        assertEquals("吴院快手", decoded.sourceTwo)
        assertNull(LastChoiceMemory.decodeChannel(""))
        assertNull(LastChoiceMemory.decodeChannel("只有一级"))
    }

    @Test
    fun `operator memory allows no-operator but rejects empty name`() {
        val none = LastChoiceMemory.decodeOperator(LastChoiceMemory.encodeOperator("", "无运营（自然/美团）"))
        assertNotNull(none)
        assertEquals("", none!!.id)
        assertEquals("无运营（自然/美团）", none.name)

        val named = LastChoiceMemory.decodeOperator(
            LastChoiceMemory.encodeOperator("038668d1d4d14123bcd5503433c01814", "张育兴"),
        )
        assertEquals("038668d1d4d14123bcd5503433c01814", named!!.id)
        assertEquals("张育兴", named.name)

        assertNull(LastChoiceMemory.decodeOperator(LastChoiceMemory.encodeOperator("abc", "")))
        assertNull(LastChoiceMemory.decodeOperator(null))
    }

    // ------------------------------------------------------------ 协议解析

    @Test
    fun `peek response carries badge without hint`() {
        val response = CenterResponse.parse("""{"action":"peek","badge":"phone","request_id":"req-1"}""")
        assertNotNull(response)
        assertEquals(FloatingBallBadge.PHONE, response!!.badge)
        assertNull(response.hint)
        assertEquals("req-1", response.requestId)
    }

    @Test
    fun `prepare response parses operator options`() {
        val json = """
            {
              "action": "prepare_his_create",
              "request_id": "req-2",
              "operator_options": [
                {"id": "", "name": "无运营（自然/美团）"},
                {"id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "name": "张育兴"}
              ],
              "channel_options": [
                {"source_one": "吴院IP", "source_two": "吴院快手", "label": "吴院IP / 吴院快手", "recommended": true}
              ],
              "his_draft": {"customer_name": "吴快顾客", "matched_tags": ["眼整形"]}
            }
        """.trimIndent()
        val response = CenterResponse.parse(json)
        assertNotNull(response)
        assertEquals(2, response!!.operatorOptions.size)
        assertEquals("", response.operatorOptions[0].id)
        assertEquals("张育兴", response.operatorOptions[1].name)
        assertEquals(1, response.channelOptions.size)
        assertEquals("吴院IP", response.channelOptions[0].sourceOne)
    }

    @Test
    fun `show_hint response parses archive card and pending phone`() {
        val json = """
            {
              "action": "show_hint",
              "request_id": "req-3",
              "hint": "【#261127624598】张* · 沟通中",
              "archive": {
                "customer_no": "261127624598",
                "name": "张*",
                "vip_grade": "金卡",
                "available_points": "300",
                "deposit_balance": "500.5",
                "phone_mask": "138****5678",
                "his_bound": true,
                "seats": [
                  {"role": "develop", "name": "李贝贝"},
                  {"role": "traffic_ops", "name": "张育兴"}
                ]
              },
              "pending_phone": "13900002222",
              "pending_mask": "139****2222",
              "phone_slot": "spare"
            }
        """.trimIndent()
        val response = CenterResponse.parse(json)
        assertNotNull(response)
        val archive = response!!.archive
        assertNotNull(archive)
        assertEquals("261127624598", archive!!.customerNo)
        assertEquals("500.5", archive.depositBalance)
        assertEquals(2, archive.seats.size)
        assertEquals("traffic_ops", archive.seats[1].role)
        assertEquals("13900002222", response.pendingPhone)
        assertEquals("spare", response.phoneSlot)
    }

    @Test
    fun `supplement feedback json carries phone and operator`() {
        val feedback = CandidateFeedback(
            requestId = "req-4",
            serviceAccountId = "wuzhan_kf",
            deviceId = "dev",
            title = "吴快顾客",
            label = "supplement_his_phone",
            candidateIndex = 0,
            accepted = true,
            ts = 1_764_065_400_000L,
            event = "supplement_his_phone",
            phone = "13900002222",
            belongServiceId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            belongServiceName = "张育兴",
        )
        val json = JSONObject(feedback.toJson())
        assertEquals("supplement_his_phone", json.getString("event"))
        assertEquals("13900002222", json.getString("phone"))
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", json.getString("belong_service_id"))
        assertEquals("张育兴", json.getString("belong_service_name"))
    }

    @Test
    fun `peek report serializes mode`() {
        val report = com.tencent.mm.assistant.net.ReadReport(
            serviceAccountId = "wuzhan_kf",
            deviceId = "dev",
            title = "张三",
            recentMessages = emptyList(),
            readStatus = "full",
            ts = 1_764_065_400_000L,
            mode = "peek",
        )
        val json = JSONObject(report.toJson())
        assertEquals("peek", json.getString("mode"))
    }
}
