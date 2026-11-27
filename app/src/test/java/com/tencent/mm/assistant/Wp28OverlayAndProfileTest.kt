package com.tencent.mm.assistant

import com.tencent.mm.assistant.core.ProfilePageReader
import com.tencent.mm.assistant.net.CenterAction
import com.tencent.mm.assistant.net.CenterResponse
import com.tencent.mm.assistant.net.ChannelOption
import com.tencent.mm.assistant.net.OperatorOption
import com.tencent.mm.assistant.net.ReadReport
import com.tencent.mm.assistant.overlay.ArchiveRules
import com.tencent.mm.assistant.overlay.FloatingBallBadge
import com.tencent.mm.assistant.overlay.OperatorChannelRules
import com.tencent.mm.assistant.overlay.PanelLifecycle
import com.tencent.mm.assistant.overlay.SupplementPhoneUi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-28 第 1 层 Android JVM 单测：
 *   * 资料页备注/电话提取（顶部真实备注优先，不再被「昵称: xxx」覆盖）；
 *   * `待` 角标与未建岚时禁显假档案号；
 *   * 页面切换/离开时自动收起的纯判定逻辑；
 *   * 协议透传 `contact_phone`。
 *
 * 全部合成数据，不包含任何真实顾客信息。
 */
class Wp28OverlayAndProfileTest {

    private val profileTexts = listOf(
        "朋友资料",
        "赵黄金微针美团13800001062",
        "昵称: 小赵",
        "微信号: wxid_sim89",
        "电话: 13900002222",
        "描述: 老客，怕疼",
        "标签: 眼整形、双眼皮",
        "地区: 河南 驻马店",
        "发消息",
        "音视频通话",
    )

    @Test
    fun `profile keeps top remark and extracts phones`() {
        val info = ProfilePageReader.parse(profileTexts)
        assertNotNull(info)
        // 顶部长备注必须优先，绝不能被「昵称: 小赵」覆盖。
        assertEquals("赵黄金微针美团13800001062", info!!.nickname)
        assertEquals("小赵", info.rawNicknameLine)
        assertEquals("wxid_sim89", info.wechatId)
        assertEquals(listOf("13800001062", "13900002222"), info.phones)
        assertEquals(listOf("眼整形", "双眼皮"), info.tags)
        assertEquals("河南 驻马店", info.region)
    }

    @Test
    fun `profile labels never become nickname`() {
        val info = ProfilePageReader.parse(
            listOf(
                "朋友资料",
                "更多信息",
                "共同群聊",
                "电话",
                "13800001062",
                "描述",
                "微信号: wxid_sim90",
                "发消息",
            ),
        )
        assertNotNull(info)
        assertNull(info!!.nickname)
        assertEquals(listOf("13800001062"), info.phones)
    }

    @Test
    fun `pending badge maps to chinese character`() {
        assertEquals("待", FloatingBallBadge.textFor("pending"))
        assertEquals(FloatingBallBadge.PENDING, FloatingBallBadge.normalize(" Pending "))
        assertEquals(0xFF546E7A.toInt(), FloatingBallBadge.colorFor("pending"))
        assertTrue(FloatingBallBadge.textFor("pending") != FloatingBallBadge.textFor("archived"))
    }

    @Test
    fun `unbound archive never shows or copies dossier no`() {
        assertFalse(ArchiveRules.canShowDossierNo(hisBound = false))
        assertFalse(ArchiveRules.canCopyDossierNo(hisBound = false))
        assertTrue(ArchiveRules.canShowDossierNo(hisBound = true))
        assertTrue(ArchiveRules.canCopyDossierNo(hisBound = true))
    }

    @Test
    fun `panel collapses on leaving page unless within hold window`() {
        assertTrue(PanelLifecycle.shouldCollapseOnLeave(panelVisible = true, nowMs = 1_000, holdUntilMs = 500))
        assertFalse(PanelLifecycle.shouldCollapseOnLeave(panelVisible = true, nowMs = 400, holdUntilMs = 500))
        assertFalse(PanelLifecycle.shouldCollapseOnLeave(panelVisible = false, nowMs = 1_000, holdUntilMs = 0))
    }

    @Test
    fun `panel collapses when page type or session changes`() {
        // 同页同会话：不收起。
        assertFalse(
            PanelLifecycle.shouldCollapseOnContextChange(
                panelVisible = true, nowMs = 1_000, holdUntilMs = 0,
                expandedPageType = "chat", currentSessionKey = "s1",
                newPageType = "chat", newSessionKey = "s1",
            ),
        )
        // 切到资料页：收起。
        assertTrue(
            PanelLifecycle.shouldCollapseOnContextChange(
                panelVisible = true, nowMs = 1_000, holdUntilMs = 0,
                expandedPageType = "chat", currentSessionKey = "s1",
                newPageType = "profile", newSessionKey = "p1",
            ),
        )
        // 同页换会话：收起。
        assertTrue(
            PanelLifecycle.shouldCollapseOnContextChange(
                panelVisible = true, nowMs = 1_000, holdUntilMs = 0,
                expandedPageType = "chat", currentSessionKey = "s1",
                newPageType = "chat", newSessionKey = "s2",
            ),
        )
        // 建档成功展示期内：不打断。
        assertFalse(
            PanelLifecycle.shouldCollapseOnContextChange(
                panelVisible = true, nowMs = 1_000, holdUntilMs = 2_000,
                expandedPageType = "profile", currentSessionKey = "p1",
                newPageType = "chat", newSessionKey = "s1",
            ),
        )
    }

    @Test
    fun `unbound archive parses his_bound false`() {
        val json = """
            {
              "action": "show_hint",
              "hint": "⚠️【岚时未建档 · 仅微信预占】赵*(待实名) · 沟通中",
              "archive": {
                "customer_no": "261127624598",
                "name": "赵*",
                "available_points": "0",
                "his_bound": false
              },
              "request_id": "req-wp28"
            }
        """.trimIndent()
        val response = CenterResponse.parse(json)
        assertNotNull(response)
        assertNotNull(response!!.archive)
        assertFalse(response.archive!!.hisBound)
        assertEquals("261127624598", response.archive!!.customerNo)
        assertFalse(ArchiveRules.canShowDossierNo(response.archive!!.hisBound))
    }

    @Test
    fun `read report serializes contact phone`() {
        val report = ReadReport(
            serviceAccountId = "wuzhan_kf",
            deviceId = "dev",
            title = "赵*",
            recentMessages = emptyList(),
            readStatus = "full",
            ts = 1_764_065_400_000L,
            pageType = ReadReport.PAGE_PROFILE,
            mode = "peek",
            contactPhone = "13900002222",
        )
        val json = JSONObject(report.toJson())
        assertEquals("13900002222", json.getString("contact_phone"))
        assertEquals("peek", json.getString("mode"))
    }

    @Test
    fun `operator forbidden channels clear belong service id`() {
        val operator = OperatorOption(id = "9001", name = "张育兴")
        val forbidden = listOf(
            ChannelOption("医美APP", "新氧", "医美APP / 新氧", false),
            ChannelOption("医美APP", "抖音团购医美", "医美APP / 抖音团购医美", false),
            ChannelOption("老带新/新带新", "", "老带新/新带新", false),
            ChannelOption("医美APP", "美团", "医美APP / 美团", false),
        )
        for (channel in forbidden) {
            assertTrue("${channel.sourceOne}/${channel.sourceTwo} 应禁挂运营", OperatorChannelRules.isOperatorForbidden(channel))
            assertNull(OperatorChannelRules.effectiveOperator(channel, operator))
        }
        val allowed = ChannelOption("吴院IP", "吴院快手", "吴院IP / 吴院快手", false)
        assertFalse(OperatorChannelRules.isOperatorForbidden(allowed))
        assertEquals(operator, OperatorChannelRules.effectiveOperator(allowed, operator))
    }

    @Test
    fun `supplement button shows mask and posts session sentinel`() {
        val response = CenterResponse(
            action = CenterAction.SHOW_HINT,
            hint = null,
            candidates = emptyList(),
            requestId = "req-wp29",
            pendingPhone = "session_cached",
            pendingMask = "138****7766",
            phoneSlot = "spare",
        )
        assertEquals("138****7766", SupplementPhoneUi.displayMask(response))
        assertFalse(SupplementPhoneUi.displayMask(response)!!.contains("session_cached"))
        assertEquals("session_cached", SupplementPhoneUi.feedbackPhone(response))
    }

    @Test
    fun `leaving wechat clears last self nickname`() {
        assertNull(WechatLifecycleRules.selfNicknameAfterLeavingWechat("主号本人昵称"))
        assertNull(WechatLifecycleRules.selfNicknameAfterLeavingWechat(null))
    }
}
