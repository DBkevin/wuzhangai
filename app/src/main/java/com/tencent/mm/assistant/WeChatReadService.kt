package com.tencent.mm.assistant

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.tencent.mm.assistant.core.AccessibilityNodeTree
import com.tencent.mm.assistant.core.ChatScreenDetector
import com.tencent.mm.assistant.core.ExtractResult
import com.tencent.mm.assistant.core.ReadStatus
import com.tencent.mm.assistant.core.ReportGate
import com.tencent.mm.assistant.net.ReadReport

/**
 * 只读无障碍服务（业务实现）。
 *
 * ============================ 硬性边界声明 ============================
 * 本类及其调用的所有代码：
 *   * 不调用任何写入类无障碍 API：不向节点派发点击/输入/滚动/粘贴等 action，
 *     不做任何手势注入（即使作为"降级方案"也不允许）
 *   * 不注入微信进程、不读微信数据库、不 hook 微信
 *   * 不自动发送任何消息；发送永远由人工在微信内完成
 * 它只做三件事：读当前前台单聊窗口的有限信息 -> 回传中控 -> 交由悬浮窗展示提示。
 * =====================================================================
 *
 * 伪装类名见 com.google.android.accessibility.selecttospeak.SelectToSpeakService。
 */
open class WeChatReadService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gate = ReportGate()

    private var app: AssistantApp? = null

    /** 2 秒去抖：同一会话多次 CONTENT_CHANGED 合并成一次提取。 */
    private var pendingExtract: Runnable? = null

    /** 当前已上报过的会话标题，用于判断"标题是否变化"以突破 5 秒节流。 */
    private var lastReportedTitle: String? = null

    /** 上一次成功读取到的微信号，避免重复扫描「我」页。 */
    private var wechatIdScanDone = false

    /** 诊断：已输出的事件日志条数（限流，避免刷爆 logcat）。 */
    private var eventLogCount = 0

    private val screenRect = Rect()

    override fun onServiceConnected() {
        super.onServiceConnected()
        app = AssistantApp.from(this)
        app?.logger?.event("service_connected")
        // 注意：伪装相关的 serviceInfo 改写放在子类入口类中完成（见 SelectToSpeakService）。
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 无条件原始日志（不受任何开关/判断影响）：用于确认系统是否真的回调本服务。
        android.util.Log.i(
            "KfReadOnly",
            "RAW evt t=" + (event?.eventType ?: -1) + " pkg=" + (event?.packageName ?: "null"),
        )
        if (event == null) return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        // 1) 事件包名是主过滤器（配置里只订阅了微信），非微信直接忽略。
        val eventPackage = event.packageName?.toString()
        if (eventPackage != ChatScreenDetector.WECHAT_PACKAGE) {
            // 离开微信：清空当前会话内存缓存并收起悬浮窗（不采集任何其他应用内容）。
            if (gate.activeSessionKey != null) {
                gate.reset()
                lastReportedTitle = null
                app?.overlay?.dismiss()
            }
            return
        }

        // 2) 取微信自己的窗口根节点。
        //
        // 不用 rootInActiveWindow：MIUI / 部分 ROM 上它会返回常驻悬浮窗、负一屏
        // （com.miui.personalassistant）或输入法的窗口，导致包名不是微信而被误丢弃。
        // 改为遍历 windows，挑 packageName == com.tencent.mm 且 isActive 的窗口。
        val root = wechatRoot()
        if (root == null) {
            if (eventLogCount < MAX_EVENT_LOGS) {
                eventLogCount++
                app?.logger?.event("evt_no_wechat_root t=" + eventType)
            }
            return
        }

        if (eventLogCount < MAX_EVENT_LOGS) {
            eventLogCount++
            app?.logger?.event("evt t=" + eventType + " pkg=" + eventPackage)
        }

        // 3) 顺路尝试补全客服账号标识（微信「我」页的微信号）。
        maybeCaptureWechatId(root)

        // 4) 2 秒去抖：同一会话连续事件只触发一次提取。
        scheduleExtraction()
    }

    override fun onInterrupt() {
        // 无障碍被系统中断：只清理状态，不做任何重试或补偿动作。
        cancelPending()
    }

    override fun onDestroy() {
        cancelPending()
        gate.reset()
        app?.overlay?.dismiss()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 去抖与提取

    private fun scheduleExtraction() {
        pendingExtract?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable { runExtraction() }
        pendingExtract = task
        mainHandler.postDelayed(task, ReportGate.DEBOUNCE_MS)
    }

    private fun cancelPending() {
        pendingExtract?.let { mainHandler.removeCallbacks(it) }
        pendingExtract = null
    }

    private fun runExtraction() {
        pendingExtract = null
        val application = app ?: return
        val root: AccessibilityNodeInfo = wechatRoot() ?: return

        val screen = currentScreenRect()
        val maxMessages = application.settings.maxMessages

        val result = try {
            ChatScreenDetector.detect(AccessibilityNodeTree(root), screen, maxMessages)
        } catch (_: Throwable) {
            // 任何解析异常都不得冒泡（微信版本更新可能改布局）。
            application.logger.skipped("extract_exception")
            return
        }

        // 诊断：只记录结果分类与长度，不记录任何聊天文本。
        application.logger.event(
            "detect " + when (result) {
                is ExtractResult.NotSingleChat -> "skip:" + result.reason
                is ExtractResult.TitleUnreadable -> "title_null msgs=" + result.recentMessages.size
                is ExtractResult.MessagesObfuscated -> "obfuscated title_len=" + result.title.length
                is ExtractResult.Success ->
                    "ok title_len=" + result.title.length + " msgs=" + result.recentMessages.size
            },
        )

        when (result) {
            is ExtractResult.NotSingleChat -> {
                // 群聊 / 公众号 / 服务号 / 朋友圈 / 聊天列表：不上报，并清空该会话缓存。
                application.logger.skipped("not_single_chat:" + result.reason)
                if (gate.activeSessionKey != null) {
                    gate.reset()
                    lastReportedTitle = null
                    application.overlay.dismiss()
                }
            }

            is ExtractResult.TitleUnreadable -> {
                // 标题读不到 -> title=null + read_status=partial（不抛异常、不循环重试）。
                val changed = gate.onSessionChanged(result.sessionKey)
                if (changed) application.overlay.dismiss()
                dispatch(
                    title = null,
                    messages = result.recentMessages,
                    readStatus = ReadStatus.PARTIAL,
                    sessionKey = result.sessionKey,
                )
            }

            is ExtractResult.MessagesObfuscated -> {
                // 消息节点被混淆 -> 只回传标题 + read_status=partial。
                val changed = gate.onSessionChanged(result.sessionKey)
                if (changed) application.overlay.dismiss()
                dispatch(
                    title = result.title,
                    messages = emptyList(),
                    readStatus = ReadStatus.PARTIAL,
                    sessionKey = result.sessionKey,
                )
            }

            is ExtractResult.Success -> {
                val changed = gate.onSessionChanged(result.sessionKey)
                if (changed) application.overlay.dismiss()
                dispatch(
                    title = result.title,
                    messages = result.recentMessages,
                    readStatus = ReadStatus.FULL,
                    sessionKey = result.sessionKey,
                )
            }
        }
    }

    private fun dispatch(
        title: String?,
        messages: List<String>,
        readStatus: ReadStatus,
        sessionKey: String,
    ) {
        val application = app ?: return
        val titleChanged = lastReportedTitle != title || gate.activeSessionKey != sessionKey

        when (gate.shouldSend(sessionKey, title, messages, titleChanged)) {
            is ReportGate.Decision.SkipUnchanged -> {
                application.logger.skipped("unchanged")
                return
            }

            is ReportGate.Decision.SkipThrottled -> {
                application.logger.skipped("throttled")
                return
            }

            is ReportGate.Decision.Send -> Unit
        }

        gate.markSent(sessionKey, title, messages)
        lastReportedTitle = title

        application.logger.readEvent(
            packageName = ChatScreenDetector.WECHAT_PACKAGE,
            readStatus = readStatus.wireValue,
        )

        val report = ReadReport(
            serviceAccountId = application.settings.serviceAccountId,
            deviceId = application.settings.deviceId,
            title = title,
            recentMessages = messages,
            readStatus = readStatus.wireValue,
            ts = System.currentTimeMillis(),
            sessionKey = sessionKey,
        )

        application.centerClient.report(report) { response ->
            // 回调在 IO 线程，切回主线程再展示。
            mainHandler.post {
                if (response == null) return@post
                application.overlay.show(
                    response = response,
                    sessionKey = sessionKey,
                    title = title,
                )
            }
        }
    }

    // ---------------------------------------------------------------- 微信「我」页

    /**
     * 只在「我」页顺路读取微信号并缓存，用于 service_account_id。
     *
     * 这是 PRD 明确允许的唯一"非当前会话"字段采集；不读取通讯录、不遍历好友列表。
     * 一旦缓存到值就停止扫描，避免频繁遍历。
     */
    private fun maybeCaptureWechatId(root: AccessibilityNodeInfo) {
        val application = app ?: return
        if (wechatIdScanDone || application.settings.cachedWechatId.isNotBlank()) {
            wechatIdScanDone = true
            return
        }
        val found = try {
            WechatIdReader.find(root)
        } catch (_: Throwable) {
            null
        }
        if (!found.isNullOrBlank()) {
            application.settings.cachedWechatId = found
            wechatIdScanDone = true
            application.logger.event("wechat_id_cached")
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun safeRoot(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (_: Throwable) {
        null
    }

    /**
     * 取"微信自己的"窗口根节点。
     *
     * 优先遍历 getWindows()：MIUI 等 ROM 上 rootInActiveWindow 常被负一屏、悬浮窗、
     * 输入法等窗口顶掉，包名不是微信。这里按 packageName 精确匹配，
     * 并优先选择 isActive 的窗口（当前真正与用户交互的那个）。
     */
    private fun wechatRoot(): AccessibilityNodeInfo? {
        val candidates = ArrayList<AccessibilityNodeInfo>(4)
        try {
            windows?.forEach { w ->
                val r = try {
                    w.root
                } catch (_: Throwable) {
                    null
                }
                if (r != null && r.packageName?.toString() == ChatScreenDetector.WECHAT_PACKAGE) {
                    candidates.add(r)
                    if (w.isActive) return r
                }
            }
        } catch (_: Throwable) {
            // windows 不可用时回退
        }
        candidates.firstOrNull()?.let { return it }

        // 回退：rootInActiveWindow 若正好是微信，也可用
        val fallback = safeRoot()
        return if (fallback?.packageName?.toString() == ChatScreenDetector.WECHAT_PACKAGE) fallback else null
    }

    private fun currentScreenRect(): Rect {
        return try {
            val wm = getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealMetrics(metrics)
            screenRect.set(0, 0, metrics.widthPixels, metrics.heightPixels)
            screenRect
        } catch (_: Throwable) {
            screenRect.set(0, 0, 1080, 1920)
            screenRect
        }
    }

    companion object {
        /** 诊断日志上限：只记录前若干条事件，避免刷爆 logcat 与环形缓冲。 */
        private const val MAX_EVENT_LOGS = 40
    }
}
