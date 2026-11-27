package com.tencent.mm.assistant

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.tencent.mm.assistant.R
import com.tencent.mm.assistant.core.AccessibilityNodeTree
import com.tencent.mm.assistant.core.ChatScreenDetector
import com.tencent.mm.assistant.core.ExtractResult
import com.tencent.mm.assistant.core.ProfileInfo
import com.tencent.mm.assistant.core.ProfilePageReader
import com.tencent.mm.assistant.core.ReadStatus
import com.tencent.mm.assistant.core.ReportGate
import com.tencent.mm.assistant.core.forEachNode
import com.tencent.mm.assistant.net.ReadReport
import com.tencent.mm.assistant.overlay.FloatingBallBadge
import com.tencent.mm.assistant.overlay.ProfileContext

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

    // ---- WP-27：轻量 peek / 主动展开面板的本地上下文（全部只在内存，离开微信即清空） ----
    /** peek 去重签名：页型|身份|识别到的手机号。 */
    private var lastPeekSignature: String? = null
    private var lastNetworkOk = true
    private var lastProfileInfo: ProfileInfo? = null
    private var lastProfileLinkedTitle: String? = null
    private var lastChatTitle: String? = null
    private var lastChatMessages: List<String> = emptyList()
    private var lastChatReadStatus: ReadStatus = ReadStatus.PARTIAL
    private var lastChatSessionKey: String = ""
    private var lastChatWechatId: String? = null
    private val phoneRegex = Regex("""(?<!\d)(1[3-9]\d{9})(?!\d)""")

    /** 上一次成功读取到的微信号，避免重复扫描「我」页。 */
    private var wechatIdScanDone = false
    /** 最近一次读到的本人（员工）昵称，供上报携带 self_nickname（docs/04 §2.2）。 */
    private var lastSelfNickname: String? = null
    /** 微信号重扫节流：避免每次轮询都遍历节点树。 */
    private var lastWechatIdScanAt = 0L

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
            android.util.Log.i("KfReadOnly", "POLL tick")
            try {
                pollOnce()
            } catch (t: Throwable) {
                android.util.Log.i("KfReadOnly", "POLL err " + t.javaClass.simpleName)
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onServiceConnected() {
        super.onServiceConnected()
        android.util.Log.i("KfReadOnly", "RAW onServiceConnected")
        app = AssistantApp.from(this)
        android.util.Log.i("KfReadOnly", "RAW app=" + (app != null))
        app?.logger?.event("service_connected")
        // WP-27：员工点击悬浮球时，按当前页（资料页/单聊）发起一次 open 请求并展开面板。
        app?.overlay?.setOnBallClick { openPanel() }
        // 前台服务保活：避免 MIUI/HyperOS 冻结本进程导致轮询停止
        KeepAliveService.start(this)
        startPolling()
        android.util.Log.i("KfReadOnly", "RAW polling started")
        // 伪装相关的 serviceInfo 改写放在子类入口类中完成（见 SelectToSpeakService）。
    }

    override fun onInterrupt() {
        cancelPending()
    }

    override fun onDestroy() {
        polling = false
        mainHandler.removeCallbacks(pollTask)
        cancelPending()
        app?.overlay?.hideAll()
        gate.reset()
        app?.overlay?.dismiss()
        KeepAliveService.stop(this)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 轮询主通道

    private fun startPolling() {
        if (polling) return
        polling = true
        mainHandler.postDelayed(pollTask, POLL_INTERVAL_MS)
    }

    private fun pollOnce() {
        val application = app ?: run {
            android.util.Log.i("KfReadOnly", "POLL app_null")
            return
        }
        if (!isScreenInteractive()) {
            android.util.Log.i("KfReadOnly", "POLL screen_off")
            return
        }

        val root = safeRoot()
        if (root == null) {
            android.util.Log.i("KfReadOnly", "POLL root_null")
            return
        }
        val pkg = root.packageName?.toString()
        android.util.Log.i("KfReadOnly", "POLL root pkg=" + (pkg ?: "NULL"))

        if (!isWechatRoot(root, pkg)) {
            // 非微信前台：立即清空内存快照、收起面板与悬浮球（本地零隐私残留）。
            gate.reset()
            lastReportedTitle = null
            lastPeekSignature = null
            lastProfileInfo = null
            lastProfileLinkedTitle = null
            lastChatTitle = null
            lastChatMessages = emptyList()
            lastChatWechatId = null
            application.overlay.hideAll()
            return
        }

        lastRoot = root
        // 微信前台常驻悬浮球（含健康指示灯）。
        application.overlay.showBall(lastNetworkOk)

        // 双开防串号（docs/04 §2.2）：每轮轮询都允许重新读取工作号与本人昵称，
        // 不再「扫到一次就永久锁死」，切换账号后能及时刷新。
        maybeCaptureWechatId(root)
        captureSelfNickname(root)

        // 资料页优先：人工点头像进入资料页时，读微信号并上报（不点击、不跳转）。
        if (application.settings.profileReadEnabled) {
            val profile = try {
                ProfilePageReader.find(AccessibilityNodeTree(root))
            } catch (_: Throwable) {
                null
            }
            if (profile != null) {
                handleProfilePage(profile)
                return
            }
        }

        extractAndDispatch()
    }

    /**
     * 处理个人资料页：只读昵称与微信号，回传中控，由中控决定展示什么提示。
     *
     * 触发方式：**人工**在微信里点头像进入资料页。本服务不点击、不跳转、不返回。
     *
     * 身份关联（关键）：
     *   资料页是从某个单聊点进去的，因此它属于那个会话对象。
     *   用「最近一次单聊标题 + 时间邻接（ProfileLinker.DEFAULT_WINDOW_MS 内）」把资料页
     *   绑定到来源会话，回传 linked_title，中控即可建立「会话标题 → 微信号」映射。
     *   这样不依赖昵称字符串匹配（备注名与昵称常常不同），也不会张冠李戴。
     */
    private fun handleProfilePage(profile: ProfileInfo) {
        val application = app ?: return
        val wechatId = profile.wechatId
        val nickname = profile.nickname

        // 时间邻接：只有刚从单聊进入资料页时才做绑定，避免隔了很久误绑。
        val linkedTitle = application.profileLinker.sourceTitleFor(System.currentTimeMillis())
        if (linkedTitle != null && !wechatId.isNullOrBlank()) {
            application.wechatIdByTitle[linkedTitle] = wechatId
        }

        lastProfileInfo = profile
        lastProfileLinkedTitle = linkedTitle
        lastChatWechatId = wechatId
        val sessionKey = "profile:" + (wechatId ?: nickname ?: "unknown")
        application.overlay.collapseIfContextChanged("profile", sessionKey)

        application.logger.event(
            "profile id_len=" + (wechatId?.length ?: -1) +
                " nick_len=" + (nickname?.length ?: -1) +
                " linked=" + (linkedTitle != null),
        )

        // WP-26/27：把资料页上下文交给悬浮窗，员工点「一键建入岚时」时随 feedback 回传。
        application.overlay.rememberProfileContext(
            ProfileContext(
                contactWechatId = wechatId,
                profileNickname = nickname,
                linkedTitle = linkedTitle,
                profileTags = profile.tags,
                profileRegion = profile.region,
                phone = profile.phones.firstOrNull(),
            ),
        )

        // WP-27：资料页只发轻量 peek（不自动展开大面板）。
        peekIfNeeded(
            pageType = ReadReport.PAGE_PROFILE,
            title = linkedTitle ?: nickname,
            wechatId = wechatId,
            messages = emptyList(),
            readStatus = if (wechatId != null) ReadStatus.FULL else ReadStatus.PARTIAL,
            sessionKey = "profile:" + (wechatId ?: nickname ?: "unknown"),
            profile = profile,
            linkedTitle = linkedTitle,
        )    }

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
                // 群聊 / 公众号 / 服务号 / 朋友圈 / 聊天列表：不采集，清空会话上下文（悬浮球保留）。
                lastProfileInfo = null
                lastChatTitle = null
                lastChatMessages = emptyList()
                lastChatWechatId = null
                lastPeekSignature = null
                // WP-28：退回微信列表/通讯录/发现/我等页面，立即收起残留面板与角标。
                application.overlay.onLeaveSupportedPage(lastNetworkOk)
            }

            is ExtractResult.TitleUnreadable ->
                applyChat(null, result.recentMessages, ReadStatus.PARTIAL, result.sessionKey)

            is ExtractResult.MessagesObfuscated ->
                applyChat(result.title, emptyList(), ReadStatus.PARTIAL, result.sessionKey)

            is ExtractResult.Success ->
                applyChat(result.title, result.recentMessages, ReadStatus.FULL, result.sessionKey)
        }
    }

    private fun applyChat(
        title: String?,
        messages: List<String>,
        readStatus: ReadStatus,
        sessionKey: String,
    ) {
        val application = app ?: return
        application.overlay.collapseIfContextChanged("chat", sessionKey)
        lastProfileInfo = null
        lastChatTitle = title
        lastChatMessages = messages
        lastChatReadStatus = readStatus
        lastChatSessionKey = sessionKey
        val knownWechatId = title?.let { application.wechatIdByTitle[it] }
        lastChatWechatId = knownWechatId
        if (title != null) {
            // 记录"最近一次单聊"，供资料页做来源绑定。
            application.profileLinker.onChatSeen(title, System.currentTimeMillis())
        }
        lastReportedTitle = title
        peekIfNeeded(ReadReport.PAGE_CHAT, title, knownWechatId, messages, readStatus, sessionKey, null, null)
    }

    /**
     * WP-27 轻量 peek：仅在「会话/资料页切换」或「首次识别出 11 位手机号」时各发 1 次，
     * 只更新悬浮球角标，绝不自动展开面板遮挡聊天界面。
     */
    private fun peekIfNeeded(
        pageType: String,
        title: String?,
        wechatId: String?,
        messages: List<String>,
        readStatus: ReadStatus,
        sessionKey: String,
        profile: ProfileInfo?,
        linkedTitle: String?,
    ) {
        val application = app ?: return
        val phone = profile?.phones?.firstOrNull() ?: extractPhone(listOfNotNull(title, linkedTitle, profile?.nickname) + messages)
        val signature = pageType + "|" + (wechatId ?: title ?: "") + "|" + phone
        if (signature == lastPeekSignature) return
        lastPeekSignature = signature

        val report = ReadReport(
            serviceAccountId = application.settings.serviceAccountId,
            deviceId = application.settings.deviceId,
            title = title,
            // 轻量：只为角标判定携带识别到的手机号，不回传聊天原文。
            recentMessages = if (phone.isNotEmpty()) listOf("手机号 $phone") else emptyList(),
            readStatus = readStatus.wireValue,
            ts = System.currentTimeMillis(),
            sessionKey = sessionKey,
            pageType = pageType,
            contactWechatId = wechatId,
            linkedTitle = linkedTitle,
            profileNickname = profile?.nickname,
            identityKey = wechatId ?: title ?: profile?.nickname,
            wechatId = application.settings.cachedWechatId.ifBlank { null },
            selfNickname = lastSelfNickname,
            profileTags = profile?.tags ?: emptyList(),
            profileRegion = profile?.region,
            // WP-28：把资料页电话/描述栏读到的手机号一并回传，供中控实时查重。
            contactPhone = profile?.phones?.firstOrNull(),
            mode = "peek",
        )
        application.centerClient.report(report) { response ->
            mainHandler.post {
                lastNetworkOk = response != null
                application.overlay.showBall(lastNetworkOk)
                application.overlay.updateBadge(response?.badge, lastNetworkOk)
            }
        }
    }

    /** 员工主动点击悬浮球：按当前页发一次完整 open 请求并展开场景化二级面板。 */
    private fun openPanel() {
        val application = app ?: return
        // WP-28：再次点球 = 收起当前面板（开闭切换）。
        if (application.overlay.isPanelVisible()) {
            application.overlay.collapsePanel()
            return
        }
        val root = safeRoot() ?: return
        val pkg = root.packageName?.toString()
        if (!isWechatRoot(root, pkg)) return
        lastRoot = root

        val profile = if (application.settings.profileReadEnabled) {
            try {
                ProfilePageReader.find(AccessibilityNodeTree(root))
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

        if (profile != null) {
            val linkedTitle = application.profileLinker.sourceTitleFor(System.currentTimeMillis())
            if (linkedTitle != null && !profile.wechatId.isNullOrBlank()) {
                application.wechatIdByTitle[linkedTitle] = profile.wechatId
            }
            lastProfileInfo = profile
            lastProfileLinkedTitle = linkedTitle
            application.overlay.rememberProfileContext(
                ProfileContext(
                    contactWechatId = profile.wechatId,
                    profileNickname = profile.nickname,
                    linkedTitle = linkedTitle,
                    profileTags = profile.tags,
                    profileRegion = profile.region,
                    phone = profile.phones.firstOrNull(),
                ),
            )
            val title = linkedTitle ?: profile.nickname
            val report = ReadReport(
                serviceAccountId = application.settings.serviceAccountId,
                deviceId = application.settings.deviceId,
                title = title,
                recentMessages = emptyList(),
                readStatus = if (profile.wechatId != null) ReadStatus.FULL.wireValue else ReadStatus.PARTIAL.wireValue,
                ts = System.currentTimeMillis(),
                sessionKey = "profile:" + (profile.wechatId ?: profile.nickname ?: "unknown"),
                pageType = ReadReport.PAGE_PROFILE,
                contactWechatId = profile.wechatId,
                linkedTitle = linkedTitle,
                profileNickname = profile.nickname,
                identityKey = profile.wechatId ?: linkedTitle ?: profile.nickname,
                wechatId = application.settings.cachedWechatId.ifBlank { null },
                selfNickname = lastSelfNickname,
                profileTags = profile.tags,
                profileRegion = profile.region,
                contactPhone = profile.phones.firstOrNull(),
                mode = "open_profile",
            )
            sendOpenReport(report, "profile:" + (profile.wechatId ?: profile.nickname ?: "unknown"), title, "profile")
            return
        }

        val result = try {
            ChatScreenDetector.detect(
                AccessibilityNodeTree(root),
                currentScreenRect(),
                application.settings.maxMessages,
            )
        } catch (_: Throwable) {
            null
        }
        // WP-28：停在微信列表/通讯录等非会话页时，绝不拿残留标题发空请求。
        if (result == null || result is ExtractResult.NotSingleChat) {
            Toast.makeText(application, R.string.overlay_need_chat_or_profile, Toast.LENGTH_SHORT).show()
            application.overlay.updateBadge(FloatingBallBadge.NONE, lastNetworkOk)
            return
        }
        val title = when (result) {
            is ExtractResult.Success -> result.title
            is ExtractResult.MessagesObfuscated -> result.title
            else -> lastChatTitle
        }
        val messages = when (result) {
            is ExtractResult.Success -> result.recentMessages
            is ExtractResult.TitleUnreadable -> result.recentMessages
            else -> lastChatMessages
        }
        val readStatus = if (result is ExtractResult.Success) ReadStatus.FULL else lastChatReadStatus
        val sessionKey = when (result) {
            is ExtractResult.Success -> result.sessionKey
            is ExtractResult.TitleUnreadable -> result.sessionKey
            is ExtractResult.MessagesObfuscated -> result.sessionKey
            else -> lastChatSessionKey
        }
        val knownWechatId = title?.let { application.wechatIdByTitle[it] } ?: lastChatWechatId
        val report = ReadReport(
            serviceAccountId = application.settings.serviceAccountId,
            deviceId = application.settings.deviceId,
            title = title,
            recentMessages = messages,
            readStatus = readStatus.wireValue,
            ts = System.currentTimeMillis(),
            sessionKey = sessionKey,
            pageType = ReadReport.PAGE_CHAT,
            contactWechatId = knownWechatId,
            identityKey = knownWechatId ?: title,
            wechatId = application.settings.cachedWechatId.ifBlank { null },
            selfNickname = lastSelfNickname,
            mode = "open_chat",
        )
        sendOpenReport(report, sessionKey, title, "chat")
    }

    private fun sendOpenReport(report: ReadReport, sessionKey: String, title: String?, pageType: String) {
        val application = app ?: return
        application.centerClient.report(report) { response ->
            mainHandler.post {
                lastNetworkOk = response != null
                if (response == null) {
                    application.overlay.showBall(false)
                    return@post
                }
                application.overlay.expandPanel(response, sessionKey, title, pageType)
                application.overlay.updateBadge(response.badge, true)
            }
        }
    }

    private fun extractPhone(texts: Iterable<String>): String {
        for (text in texts) {
            phoneRegex.find(text)?.let { return it.groupValues[1] }
        }
        return ""
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
        val now = System.currentTimeMillis()
        if (now - lastWechatIdScanAt < WECHAT_ID_RESCAN_MS) return
        lastWechatIdScanAt = now
        val found = try {
            WechatIdReader.find(root)
        } catch (_: Throwable) {
            null
        }
        if (found.isNullOrBlank()) return
        if (found != application.settings.cachedWechatId) {
            application.settings.cachedWechatId = found
            application.logger.event("wechat_id_refreshed")
        }
        wechatIdScanDone = true
    }

    /**
     * 读取本人（员工）昵称：仅取右半屏本人气泡头像的 contentDescription（`"<昵称>头像"`）。
     * 读不到就保留上一次的值，绝不清空、绝不点击。
     */
    private fun captureSelfNickname(root: AccessibilityNodeInfo) {
        val width = try {
            currentScreenRect().width()
        } catch (_: Throwable) {
            0
        }
        if (width <= 0) return
        val found = try {
            WechatIdReader.findSelfNickname(root, width)
        } catch (_: Throwable) {
            null
        }
        if (!found.isNullOrBlank() && found != lastSelfNickname) {
            lastSelfNickname = found
            app?.logger?.event("self_nickname_refreshed")
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
        /** 微信号重扫节流（毫秒）：双开切换账号后最多 10 秒内刷新。 */
        private const val WECHAT_ID_RESCAN_MS = 10_000L

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
