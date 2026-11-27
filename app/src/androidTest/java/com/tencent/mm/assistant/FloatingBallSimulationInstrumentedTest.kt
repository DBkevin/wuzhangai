package com.tencent.mm.assistant

import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.mm.assistant.net.CenterAction
import com.tencent.mm.assistant.net.CenterClient
import com.tencent.mm.assistant.net.CenterResponse
import com.tencent.mm.assistant.net.ChannelOption
import com.tencent.mm.assistant.net.OperatorOption
import com.tencent.mm.assistant.net.HisDraft
import com.tencent.mm.assistant.net.ArchiveCard
import com.tencent.mm.assistant.overlay.FloatingBallView
import com.tencent.mm.assistant.overlay.HintOverlayManager
import com.tencent.mm.assistant.overlay.ProfileContext
import com.tencent.mm.assistant.util.SafeLogger
import com.tencent.mm.assistant.util.SettingsStore
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * WP-28 第 2 层：荣耀 100 真机 Android Instrumentation 模拟点击。
 *
 * 铁律：全程只在 `wuzhanai` 自有进程内驱动真实 `FloatingBallView` /
 * `HintOverlayManager` 视图树，**绝不向微信进程注入任何触摸或输入事件**。
 */
@RunWith(AndroidJUnit4::class)
class FloatingBallSimulationInstrumentedTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsStore
    private lateinit var logger: SafeLogger
    private lateinit var manager: HintOverlayManager

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        settings = SettingsStore(context)
        logger = SafeLogger(settings)
        manager = HintOverlayManager(context, CenterClient(settings, logger), settings, logger)
    }

    @Test
    fun badgeFlowPendingConflictPhoneArchived() {
        onMain {
            val ball = FloatingBallView(context)
            val cases = listOf(
                "pending" to "待",
                "conflict" to "撞",
                "phone" to "号",
                "archived" to "档",
            )
            cases.forEach { (value, expected) ->
                ball.setBadge(value)
                assertEquals(expected, ball.badgeText())
                assertEquals(value, ball.badgeValue())
            }
            ball.setBadge("")
            assertEquals("", ball.badgeText())
        }
    }

    @Test
    fun unboundChatPanelHidesFakeDossierNo() {
        val response = chatResponse(hisBound = false)
        val view = onMain { manager.buildViewForTest(response, "chat") }
        assertNotNull(view)
        val tab = onMain { findButton(view!!, "📋 档案与资产") }
        assertNotNull("未找到「档案与资产」Tab", tab)
        onMain { tab!!.performClick() }

        val texts = onMain { textsOf(view!!) }
        assertTrue("面板未显示「岚时未建档」：$texts", texts.any { it.contains("岚时未建档") })
        assertFalse("面板泄漏本地假档案号：$texts", texts.any { it.contains("261127624598") })
        assertNull("未建岚时不得出现「复制档案号」按钮", onMain { findButton(view!!, "复制档案号") })
        assertNull("未建岚时不得出现「复制 #备注码」按钮", onMain { findButton(view!!, "复制#备注码") })
    }

    @Test
    fun meituanChannelClearsOperatorAndCarriesPhone() {
        val captured = AtomicReference<com.tencent.mm.assistant.net.CandidateFeedback?>()
        manager.feedbackInterceptor = { captured.set(it) }
        manager.rememberProfileContext(
            ProfileContext(
                contactWechatId = "wxid_sim_wp28",
                profileNickname = "吴快双眼皮13900002222",
                linkedTitle = "吴快",
                profileTags = listOf("眼整形"),
                profileRegion = null,
                phone = null,
            ),
        )
        onMain { }
        Thread.sleep(100)

        val view = onMain { manager.buildViewForTest(prepareResponse(), "profile") }
        assertNotNull(view)
        val operatorButton = onMain { findButton(view!!, "张育兴") }
        assertNotNull("未找到运营候选人按钮", operatorButton)
        onMain { operatorButton!!.performClick() }

        val channelButton = onMain { findButtonContaining(view!!, "美团") }
        assertNotNull("未找到美团渠道按钮", channelButton)
        onMain { channelButton!!.performClick() }

        val feedback = captured.get()
        assertNotNull("点击渠道后未触发 feedback", feedback)
        assertNull("美团渠道必须置空 belongServiceId", feedback!!.belongServiceId)
        assertEquals("13900002222", feedback.phone)
    }

    @Test
    fun createSuccessCopiesRemarkCodeAndAutoCollapses() {
        assumeTrue("真机未授予悬浮窗权限，跳过真实窗口用例", ensureOverlayPermission())
        // Android 10+ 仅允许前台聚焦应用读取剪贴板：先启动自有 MainActivity（绝不启动微信）。
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(400)
            val copied = AtomicReference<String?>()
            manager.clipboardObserverForTest = { copied.set(it) }
            val response = successResponse()
            onMain { manager.expandPanel(response, "chat:s1", "吴快双眼皮", "profile") }
            Thread.sleep(600)
            assertTrue("建档成功面板未展开", onMain { manager.isPanelVisible() })
            // MagicOS/Android 16 对测试进程同时隐藏剪贴板内容与 hasPrimaryClip：
            // 观察点位于 setPrimaryClip 成功返回之后，回调收到文本即证明真实写入了系统剪贴板。
            assertEquals(" #261127999001", copied.get())

            Thread.sleep(1_600)
            assertFalse("1.5s 后应自动收起", onMain { manager.isPanelVisible() })
            assertEquals("archived", onMain { manager.currentBadgeForTest() })
        }
    }

    @Test
    fun leavingChatPageCollapsesPanelAndClearsBadge() {
        assumeTrue("真机未授予悬浮窗权限，跳过真实窗口用例", ensureOverlayPermission())
        onMain { manager.expandPanel(chatResponse(hisBound = true), "chat:s2", "张敏", "chat") }
        Thread.sleep(400)
        assertTrue("单聊面板未展开", onMain { manager.isPanelVisible() })

        onMain { manager.onLeaveSupportedPage(true) }
        Thread.sleep(300)
        assertFalse("退回微信列表页后 300ms 内必须收起", onMain { manager.isPanelVisible() })
        assertEquals("", onMain { manager.currentBadgeForTest() })
    }

    // ------------------------------------------------------------ helpers

    private fun chatResponse(hisBound: Boolean): CenterResponse = CenterResponse(
        action = CenterAction.SHOW_HINT,
        hint = if (hisBound) {
            "【#25100188888】张敏(已实名) · 沟通中\n资产：可用积分 0"
        } else {
            "⚠️【岚时未建档 · 仅微信预占】赵六(待实名) · 沟通中\n👉 请点开顾客微信头像进入「详细资料页」，再点悬浮球一键建入岚时"
        },
        candidates = emptyList(),
        requestId = "req-wp28-unbound",
        archive = ArchiveCard(
            customerNo = if (hisBound) "25100188888" else "261127624598",
            name = if (hisBound) "张敏" else "赵六",
            vipGrade = null,
            stage = "communicating",
            protectStatus = "protected",
            availablePoints = "0",
            frozenPoints = "0",
            depositBalance = "0",
            phoneMask = null,
            seats = emptyList(),
            lastVisitAt = null,
            hisBound = hisBound,
        ),
    )

    private fun prepareResponse(): CenterResponse = CenterResponse(
        action = CenterAction.PREPARE_HIS_CREATE,
        hint = "🆕 岚时未建档，请核对下方信息并点选渠道一键建入岚时",
        candidates = emptyList(),
        requestId = "req-wp28-prepare",
        hisDraft = HisDraft(
            customerName = "吴快双眼皮",
            contactWechatId = "wxid_sim_wp28",
            wechatMask = "wxid****p28",
            phoneMask = "139****2222",
            developerName = "李贝贝",
            matchedSourceOne = "吴院IP",
            matchedSourceTwo = "吴院快手",
            matchedTags = listOf("眼整形"),
            wxAddedAt = null,
        ),
        channelOptions = listOf(
            ChannelOption("医美APP", "美团", "医美APP / 美团", true),
            ChannelOption("吴院IP", "吴院快手", "吴院IP / 吴院快手", false),
        ),
        operatorOptions = listOf(
            OperatorOption("", "无运营（自然/美团）"),
            OperatorOption("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "张育兴"),
        ),
    )

    private fun successResponse(): CenterResponse = CenterResponse(
        action = CenterAction.SHOW_HINT,
        hint = "✅【岚时建档成功 #261127999001】来源：吴院IP/吴院快手 · 开发：李贝贝 · 运营：张育兴",
        candidates = emptyList(),
        requestId = "req-wp28-created",
        autoCopyText = " #261127999001",
        archive = ArchiveCard(
            customerNo = "261127999001",
            name = "吴快双眼皮",
            vipGrade = null,
            stage = "communicating",
            protectStatus = "protected",
            availablePoints = "0",
            frozenPoints = "0",
            depositBalance = "0",
            phoneMask = null,
            seats = emptyList(),
            lastVisitAt = null,
            hisBound = true,
        ),
    )

    private fun ensureOverlayPermission(): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // 测试进程读取剪贴板需要 READ_CLIPBOARD appop（Android 10+ 默认仅前台可读）。
        try {
            instrumentation.uiAutomation
                .executeShellCommand("appops set ${context.packageName} READ_CLIPBOARD allow")
                .close()
        } catch (_: Throwable) {
            // 旧系统无此 appop：忽略
        }
        try {
            instrumentation.uiAutomation
                .executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
                .close()
        } catch (_: Throwable) {
            // Android 12 及以下无此运行时权限：忽略
        }
        if (Settings.canDrawOverlays(context)) return true
        try {
            instrumentation.uiAutomation
                .executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
                .close()
        } catch (_: Throwable) {
            return false
        }
        Thread.sleep(300)
        return Settings.canDrawOverlays(context)
    }

    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>(64)
        fun walk(view: View) {
            out.add(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(root)
        return out
    }

    private fun textsOf(root: View): List<String> = allViews(root)
        .mapNotNull { (it as? TextView)?.text?.toString() }
        .filter { it.isNotEmpty() }

    private fun findButton(root: View, label: String): Button? =
        allViews(root).filterIsInstance<Button>().firstOrNull { it.text.toString() == label }

    private fun findButtonContaining(root: View, part: String): Button? =
        allViews(root).filterIsInstance<Button>().firstOrNull { it.text.toString().contains(part) }
}
