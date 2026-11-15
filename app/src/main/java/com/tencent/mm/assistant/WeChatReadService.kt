package com.tencent.mm.assistant

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
 *   * 不向任何节点派发 action（点击/输入/滚动/粘贴一律不调用）
 *   * 不做任何手势注入
 *   * 不注入微信进程、不读微信数据库、不 hook 微信
 *   * 不自动发送任何消息；发送永远由人工在微信内完成
 * 它只做三件事：读当前前台单聊窗口的有限信息 -> 回传中控 -> 交由悬浮窗展示提示。
 * =====================================================================
 *
 * 为什么用"轮询 + 事件"双通道：
 *   在 MIUI/HyperOS 上实测，系统对第三方无障碍服务的 TYPE_WINDOW_* 事件投递不可靠
 *   （服务已绑定、无崩溃、但回调长期不触发）。而节点树本身可读（uiautomator 可导出
 *   完整文本），因此以 2 秒定时轮询为主通道，事件到达时作为加速通道。
 *   两条通道都走同一个 ReportGate，不会重复回传。
 */
open class WeChatReadService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gate = ReportGate()

    private var app: AssistantApp? = null

    /** 事件加速通道的 2 秒去抖任务。 */
    private var pendingExtract: Runnable? = null

    /** 当前已上报过的会话标题，用于判断"标题是否变化"以突破 5 秒节流。 */
    private var lastReportedTitle: String? = null

    /** 上一次成功读取到的微信号，避免重复扫描「我」页。 */
    private var wechatIdScanDone = false

    /** 诊断：已输出的事件日志条数（限流，避免刷爆 logcat）。 */
    private var eventLogCount = 0

    /** 诊断：已输出的轮询日志条数（限流）。 */
    private var pollLogCount = 0

    /** 轮询是否已启动。 */
    private var polling = false

    /** 最近一次取到的微信根节点，供去抖后的提取复用。 */
    private var lastRoot: AccessibilityNodeInfo? = null

    private val screenRect = Rect()

    private val pollTask = object : Runnable {
        override fun run() {
            if (!polling) return
            try {
                pollOnce()
            } catch (_: Throwable) {
                // 轮询任何异常都不得终止循环，也不得影响微信
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onServiceConnected() {
        super.onServiceConnected()
        app = AssistantApp.from(this)
        app?.logger?.event("service_connected")
        startPolling()
        // 伪装相关的 serviceInfo 改写放在子类入口类中完成（见 SelectToSpeakService）。
    }

    override fun onInterrupt() {
        cancelPending()
    }

    override fun onDestroy() {
        polling = false
        mainHandler.removeCallbacks(pollTask)
        cancelPending()
        gate.reset()
        app?.overlay?.dismiss()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 轮询主通道

    private fun startPolling() {
        if (polling) return
        polling = true
        mainHandler.postDelayed(pollTask, POLL_INTERVAL_MS)
    }

    private fun pollOnce() {
        val application = app ?: return
        if (!isScreenInteractive()) return

        val root = safeRoot() ?: return
        val pkg = root.packageName?.toString()

        if (pollLogCount < MAX_POLL_LOGS) {
            pollLogCount++
            application.logger.event("poll pkg=" + (pkg ?: "NULL"))
        }

        if (!isWechatRoot(root, pkg)) {
            // 非微信前台：清空当前会话内存缓存并收起悬浮窗，绝不采集其他应用内容。
            if (gate.activeSessionKey != null) {
                gate.reset()
                lastReportedTitle = null
                application.overlay.dismiss()
            }
            return
        }

        lastRoot = root
        extractAndDispatch()
    }

    /**
     * 判断当前根节点是否属于微信。
     *
     * 为什么不能只看 packageName：
     *   微信对第三方无障碍服务混淆节点树，rootInActiveWindow.packageName 实测会返回 null，
     *   直接比较包名会导致永远判定"不是微信"。
     * 兜底判据：viewIdResourceName 的包前缀（需 FLAG_REPORT_VIEW_IDS，已启用）。
     * 该字段形如 com.tencent.mm:id/xxxx，混淆节点树时通常仍保留。
     */
    private fun isWechatRoot(root: AccessibilityNodeInfo, pkg: String?): Boolean {
        if (pkg == ChatScreenDetector.WECHAT_PACKAGE) return true
        if (pkg != null && pkg.isNotEmpty()) {
            // 明确是别的应用，直接否定，避免误读其他 App 内容。
            return false
        }
        // packageName 为 null（被混淆）：用节点 ID 前缀佐证。
        var hit = false
        try {
            AccessibilityNodeTree(root).forEachNode(maxDepth = VIEW_ID_PROBE_DEPTH, maxNodes = VIEW_ID_PROBE_NODES) { node, _ ->
                val id = node.viewIdResourceName
                if (!hit && id != null && id.startsWith(ChatScreenDetector.WECHAT_PACKAGE + ":")) {
                    hit = true
                }
            }
        } catch (_: Throwable) {
            return false
        }
        return hit
    }

    private fun isScreenInteractive(): Boolean = try {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        pm?.isInteractive ?: true
    } catch (_: Throwable) {
        true
    }

    // ---------------------------------------------------------------- 事件加速通道

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        // 事件包名是可靠的（微信混淆的是节点树，不是事件包名）。
        val eventPackage = event.packageName?.toString()
        if (eventPackage != ChatScreenDetector.WECHAT_PACKAGE) return

        if (eventLogCount < MAX_EVENT_LOGS) {
            eventLogCount++
            app?.logger?.event("evt t=" + eventType + " pkg=" + eventPackage)
        }

        // 事件只作为"加速器"：缩短一次提取的等待，真正的判定仍由轮询统一完成。
        scheduleExtraction()
    }

    private fun scheduleExtraction() {
        pendingExtract?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            pendingExtract = null
            try {
                pollOnce()
            } catch (_: Throwable) {
                // ignore
            }
        }
        pendingExtract = task
        mainHandler.postDelayed(task, EVENT_DEBOUNCE_MS)
    }

    private fun cancelPending() {
        pendingExtract?.let { mainHandler.removeCallbacks(it) }
        pendingExtract = null
    }

    // ---------------------------------------------------------------- 提取与回传

    private fun extractAndDispatch() {
        val application = app ?: return
        val root = lastRoot ?: return

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
                if (gate.activeSessionKey != null) {
                    gate.reset()
                    lastReportedTitle = null
                    application.overlay.dismiss()
                }
            }

            is ExtractResult.TitleUnreadable -> {
                // 标题读不到 -> title=null + read_status=partial（不抛异常、不循环重试）。
                if (gate.onSessionChanged(result.sessionKey)) application.overlay.dismiss()
                dispatch(
                    title = null,
                    messages = result.recentMessages,
                    readStatus = ReadStatus.PARTIAL,
                    sessionKey = result.sessionKey,
                )
            }

            is ExtractResult.MessagesObfuscated -> {
                // 消息节点被混淆 -> 只回传标题 + read_status=partial。
                if (gate.onSessionChanged(result.sessionKey)) application.overlay.dismiss()
                dispatch(
                    title = result.title,
                    messages = emptyList(),
                    readStatus = ReadStatus.PARTIAL,
                    sessionKey = result.sessionKey,
                )
            }

            is ExtractResult.Success -> {
                if (gate.onSessionChanged(result.sessionKey)) application.overlay.dismiss()
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
        /** 轮询周期：2 秒，与 PRD 的去抖窗口一致。 */
        private const val POLL_INTERVAL_MS = 2_000L

        /** 事件加速通道的去抖延时。 */
        private const val EVENT_DEBOUNCE_MS = 600L

        /** 诊断日志上限：只记录前若干条事件，避免刷爆 logcat 与环形缓冲。 */
        private const val MAX_EVENT_LOGS = 40

        /** 轮询诊断日志上限。 */
        private const val MAX_POLL_LOGS = 30

        /** 用节点 ID 前缀判断归属时的探测范围（浅层即可命中）。 */
        private const val VIEW_ID_PROBE_DEPTH = 8
        private const val VIEW_ID_PROBE_NODES = 120
    }
}
