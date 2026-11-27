package com.tencent.mm.assistant.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.tencent.mm.assistant.R
import com.tencent.mm.assistant.net.Candidate
import com.tencent.mm.assistant.net.CandidateFeedback
import com.tencent.mm.assistant.net.CenterAction
import com.tencent.mm.assistant.net.CenterClient
import com.tencent.mm.assistant.net.CenterResponse
import com.tencent.mm.assistant.net.ChannelOption
import com.tencent.mm.assistant.net.OperatorOption
import com.tencent.mm.assistant.util.LastChoiceMemory
import com.tencent.mm.assistant.util.SafeLogger
import com.tencent.mm.assistant.util.SettingsStore

/**
 * 最近一次资料页上下文（WP-26）：员工点「一键建入岚时」渠道按钮时随 /feedback 回传。
 * 只保存当前已打开资料页上人工可见的信息，不做任何后台遍历。
 */
data class ProfileContext(
    val contactWechatId: String?,
    val profileNickname: String?,
    val linkedTitle: String?,
    val profileTags: List<String>,
    val profileRegion: String?,
)

/**
 * 悬浮窗管理。
 *
 * 布局（按使用反馈调整）：
 *   * **满屏宽**，贴在屏幕**顶部 1/3** 处；
 *   * 内容是**一条条的列表**，每条右侧一个「复制」按钮；
 *   * 列表可上下滚动；底部一条小字说明。
 *
 * 边界（PRD 模块 3）：
 *   * 仅在收到中控响应且 action != none 时显示；
 *   * TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE：绝不抢占微信输入焦点；
 *   * 只占顶部 1/3，不遮挡底部输入框；
 *   * 同一会话同时只存在一个悬浮窗，新响应到来时替换旧窗口，不叠加；
 *   * 复制只写系统剪贴板，不向微信注入任何输入事件，也不触发粘贴。
 */
class HintOverlayManager(
    private val appContext: Context,
    private val centerClient: CenterClient,
    private val settings: SettingsStore,
    private val logger: SafeLogger,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var currentView: View? = null
    private var currentSessionKey: String? = null
    private var autoDismissRunnable: Runnable? = null

    private var lastResponse: CenterResponse? = null
    private var lastTitle: String? = null

    /** 最近一次资料页上下文（微信号/昵称/标签/地区），供一键建档/补录手机号回传。 */
    private var profileContext: ProfileContext? = null

    // ---------------------------------------------------------------- 常驻悬浮球（WP-27）

    /** 员工点击悬浮球：由读屏服务根据当前页发起 open 请求。 */
    private var onBallClick: (() -> Unit)? = null

    private var ballView: FloatingBallView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelVisible = false
    private var lastBadge: String = FloatingBallBadge.NONE
    private var lastHealthy = true
    private var idleRunnable: Runnable? = null

    fun setOnBallClick(action: () -> Unit) {
        onBallClick = action
    }

    /** 记录最近一次资料页上下文（由 WeChatReadService 在人工打开资料页时调用）。 */
    fun rememberProfileContext(context: ProfileContext) {
        mainHandler.post { profileContext = context }
    }

    /** 展示/刷新常驻悬浮球（仅在微信前台调用）。 */
    fun showBall(healthy: Boolean) {
        mainHandler.post {
            lastHealthy = healthy
            if (panelVisible) return@post
            if (!ensureBall()) return@post
            ballView?.setHealthy(healthy)
            ballView?.setBadge(lastBadge)
            scheduleIdle()
        }
    }

    /** 更新角标（peek 响应）并保持健康。 */
    fun updateBadge(badge: String?, healthy: Boolean = true) {
        mainHandler.post {
            lastBadge = FloatingBallBadge.normalize(badge)
            lastHealthy = healthy
            if (panelVisible) return@post
            if (!ensureBall()) return@post
            ballView?.setHealthy(healthy)
            ballView?.setBadge(lastBadge)
            scheduleIdle()
        }
    }

    /** 离开微信前台：立即移除悬浮球与面板（零隐私残留）。 */
    fun hideAll() {
        mainHandler.post {
            removePanelInternal()
            removeBallInternal()
            panelVisible = false
        }
    }

    fun isPanelVisible(): Boolean = panelVisible

    // ---------------------------------------------------------------- 展示

    /** 兼容旧调用：peek 只更新角标；其余动作展开面板。 */
    fun show(response: CenterResponse, sessionKey: String, title: String?, pageType: String = "chat") {
        if (response.action == CenterAction.NONE) return
        if (response.action == CenterAction.PEEK) {
            updateBadge(response.badge)
            return
        }
        expandPanel(response, sessionKey, title, pageType)
    }

    /** 员工点击悬浮球后展开场景化二级面板。 */
    fun expandPanel(response: CenterResponse, sessionKey: String, title: String?, pageType: String = "chat") {
        mainHandler.post { expandInternal(response, sessionKey, title, pageType) }
    }

    /** 收起面板，回到小悬浮球。 */
    fun collapsePanel() {
        mainHandler.post { collapseInternal() }
    }

    private fun expandInternal(response: CenterResponse, sessionKey: String, title: String?, pageType: String) {
        val wm = windowManager ?: appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            logger.event("overlay_no_wm")
            return
        }
        windowManager = wm

        if (!hasOverlayPermission()) {
            logger.event("overlay_no_permission")
            return
        }

        removePanelInternal()
        removeBallInternal()

        // 岚时建档成功后中控下发 ` #<档案号>`：自动写入系统剪贴板（粘贴仍由人工完成）。
        response.autoCopyText?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            copyToClipboard(
                text,
                appContext.getString(R.string.overlay_copied_remark_code_fmt, text.trim()),
            )
        }

        val metrics = appContext.resources.displayMetrics
        val heightRatio = if (response.action == CenterAction.PREPARE_HIS_CREATE) PREPARE_HEIGHT_RATIO else HEIGHT_RATIO
        val panelHeight = (metrics.heightPixels * heightRatio).toInt()

        val view = buildView(response, panelHeight, pageType)
            ?: run {
                logger.event("overlay_build_failed")
                return
            }

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            panelHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = statusBarHeight()
        }

        try {
            wm.addView(view, params)
        } catch (_: Throwable) {
            logger.event("overlay_add_failed")
            return
        }

        currentView = view
        currentSessionKey = sessionKey
        lastResponse = response
        lastTitle = title
        panelVisible = true
        logger.event("overlay_shown")

        // 建档成功自动收起面板回到小悬浮球（角标变「档」）。
        if (!response.autoCopyText.isNullOrBlank()) {
            lastBadge = FloatingBallBadge.ARCHIVED
            mainHandler.postDelayed({ collapseInternal() }, AUTO_COLLAPSE_MS)
            return
        }

        if (response.action == CenterAction.NO_MATCH) {
            val r = Runnable { collapseInternal() }
            autoDismissRunnable = r
            mainHandler.postDelayed(r, AUTO_DISMISS_MS)
        }
    }

    private fun collapseInternal() {
        removePanelInternal()
        panelVisible = false
        if (!ensureBall()) return
        ballView?.setHealthy(lastHealthy)
        ballView?.setBadge(lastBadge)
        scheduleIdle()
    }

    // ---------------------------------------------------------------- 悬浮球生命周期

    private fun ensureBall(): Boolean {
        if (ballView != null) return true
        val wm = windowManager ?: appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        if (!hasOverlayPermission()) {
            logger.event("overlay_no_permission")
            return false
        }
        windowManager = wm
        val ball = FloatingBallView(appContext)
        ball.setBadge(lastBadge)
        ball.setHealthy(lastHealthy)
        ball.onTap = {
            resetIdle()
            onBallClick?.invoke()
        }
        ball.onDrag = { dx, dy, end -> moveBall(dx, dy, end) }

        val metrics = appContext.resources.displayMetrics
        val size = dp(BALL_SIZE_DP)
        val defaultY = (metrics.heightPixels * DEFAULT_BALL_Y_RATIO).toInt()
        val y = (if (settings.floatingBallY >= 0) settings.floatingBallY else defaultY)
            .coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - size - dp(4)).coerceAtLeast(0)
            this.y = y
        }
        return try {
            wm.addView(ball, params)
            ballView = ball
            ballParams = params
            scheduleIdle()
            true
        } catch (_: Throwable) {
            logger.event("ball_add_failed")
            false
        }
    }

    private fun moveBall(dx: Float, dy: Float, end: Boolean) {
        val wm = windowManager ?: return
        val ball = ballView ?: return
        val params = ballParams ?: return
        val metrics = appContext.resources.displayMetrics
        val size = dp(BALL_SIZE_DP)
        if (!end) {
            params.x = (params.x + dx.toInt()).coerceIn(0, (metrics.widthPixels - size).coerceAtLeast(0))
            params.y = (params.y + dy.toInt()).coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))
            try {
                wm.updateViewLayout(ball, params)
            } catch (_: Throwable) {
                // 窗口已失效：忽略
            }
            resetIdle()
            return
        }
        // 松手磁吸到较近的一侧，并记住 Y 坐标。
        val margin = dp(4)
        params.x = if (params.x + size / 2 < metrics.widthPixels / 2) margin else (metrics.widthPixels - size - margin)
        params.y = params.y.coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))
        settings.floatingBallY = params.y
        try {
            wm.updateViewLayout(ball, params)
        } catch (_: Throwable) {
            // 窗口已失效：忽略
        }
        scheduleIdle()
    }

    private fun removeBallInternal() {
        idleRunnable?.let { mainHandler.removeCallbacks(it) }
        idleRunnable = null
        val ball = ballView ?: return
        ballView = null
        ballParams = null
        try {
            windowManager?.removeView(ball)
        } catch (_: Throwable) {
            // 视图已被移除：忽略
        }
    }

    private fun scheduleIdle() {
        idleRunnable?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable { ballView?.applyAlpha(idle = true, panelOpen = panelVisible) }
        idleRunnable = task
        mainHandler.postDelayed(task, IDLE_ALPHA_MS)
    }

    private fun resetIdle() {
        idleRunnable?.let { mainHandler.removeCallbacks(it) }
        ballView?.applyAlpha(idle = false, panelOpen = panelVisible)
        scheduleIdle()
    }

    // ---------------------------------------------------------------- 关闭

    fun dismiss() {
        mainHandler.post { collapseInternal() }
    }

    private fun removePanelInternal() {
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = null
        val view = currentView ?: return
        currentView = null
        currentSessionKey = null
        lastResponse = null
        lastTitle = null
        panelVisible = false
        try {
            windowManager?.removeView(view)
        } catch (_: Throwable) {
            // 视图已被移除或窗口失效：忽略
        }
        logger.event("overlay_dismissed")
    }

    // ---------------------------------------------------------------- 视图构建

    private fun buildView(response: CenterResponse, panelHeight: Int, pageType: String): View? {
        val root = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = cardBackground()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                panelHeight,
            )
        }

        // 顶部标题栏：标题 + 关闭
        val header = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            titleView(titleFor(response)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        // WP-26/27：顶栏固定显示两个独立复制按钮（hint 或结构化 archive 里含档案号时）。
        val headerDossierNo = HintCopyHelper.extractDossierNo(response.hint)
            ?: response.archive?.customerNo?.takeIf { it.isNotBlank() }
        headerDossierNo?.let { dossierNo ->
            header.addView(
                smallButton(appContext.getString(R.string.overlay_btn_copy_dossier_no)) {
                    copyToClipboard(
                        dossierNo,
                        appContext.getString(R.string.overlay_copied_dossier_no_fmt, dossierNo),
                    )
                },
            )
            header.addView(
                smallButton(appContext.getString(R.string.overlay_btn_copy_remark_code)) {
                    copyToClipboard(
                        " #$dossierNo",
                        appContext.getString(R.string.overlay_copied_remark_code_fmt, " #$dossierNo"),
                    )
                },
            )
        }
        header.addView(smallButton(appContext.getString(R.string.overlay_btn_collapse)) { collapsePanel() })
        header.addView(smallButton(appContext.getString(R.string.overlay_btn_close)) { collapsePanel() })
        root.addView(header)

        // 中间：可滚动的条目列表
        val list = LinearLayout(appContext).apply { orientation = LinearLayout.VERTICAL }
        when (response.action) {
            CenterAction.SHOW_HINT -> {
                when {
                    pageType == "chat" -> list.addView(chatTabs(response))
                    response.archive != null -> {
                        list.addView(assetView(response))
                        appendHintRows(list, response)
                    }
                    else -> appendHintRows(list, response)
                }
            }

            CenterAction.PREPARE_HIS_CREATE -> {
                list.addView(prepareHisCreatePanel(response))
            }

            CenterAction.SHOW_CANDIDATES -> {
                if (response.candidates.isEmpty()) {
                    list.addView(rowView(appContext.getString(R.string.overlay_empty_candidates)))
                } else {
                    response.candidates.take(CenterResponse.MAX_CANDIDATES).forEachIndexed { index, candidate ->
                        list.addView(candidateRow(candidate, index, response))
                    }
                }
            }

            CenterAction.NO_MATCH -> {
                list.addView(rowView(appContext.getString(R.string.overlay_no_match_body)))
            }

            // peek 只更新角标，永不构建面板。
            CenterAction.PEEK -> return null

            CenterAction.NONE -> return null
        }

        // 条目多时可上下滚动。实测真实手指点击在 ScrollView 内正常
        // （之前"点不动"是 ADB 注入的触摸被 MIUI 安全策略拦截，与 ScrollView 无关）。
        val scroll = ScrollView(appContext).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            addView(
                list,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        // 底部说明
        root.addView(footerView())
        return root
    }

    // ---------------------------------------------------------------- 二级面板内容（WP-27）

    /** 把 hint 按行拆成可复制条目（话术行只复制正文，状态行无复制按钮）。 */
    private fun appendHintRows(list: LinearLayout, response: CenterResponse) {
        val hint = response.hint ?: appContext.getString(R.string.overlay_empty_hint)
        val items = hint.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) {
            list.addView(rowView(hint, HintCopyHelper.copyableRowText(hint)))
            return
        }
        items.forEach { line ->
            list.addView(rowView(line, HintCopyHelper.copyableRowText(line)))
        }
    }

    /** 单聊页二级 Tab：档案与资产 / 补录手机号 / 跟进话术。 */
    private fun chatTabs(response: CenterResponse): View {
        val wrap = LinearLayout(appContext).apply { orientation = LinearLayout.VERTICAL }
        val tabRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(4))
        }
        val content = LinearLayout(appContext).apply { orientation = LinearLayout.VERTICAL }
        fun select(index: Int) {
            content.removeAllViews()
            when (index) {
                0 -> content.addView(assetView(response))
                1 -> content.addView(phonePanel(response))
                else -> content.addView(scriptPanel(response))
            }
        }
        listOf(R.string.overlay_tab_asset, R.string.overlay_tab_phone, R.string.overlay_tab_script)
            .forEachIndexed { index, resId ->
                tabRow.addView(
                    smallButton(appContext.getString(resId)) { select(index) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
        wrap.addView(tabRow)
        wrap.addView(content)
        select(0)
        return wrap
    }

    /** 档案与资产卡（结构化 archive 优先，缺失时退回 hint 文本）。 */
    private fun assetView(response: CenterResponse): View {
        val wrap = LinearLayout(appContext).apply { orientation = LinearLayout.VERTICAL }
        val archive = response.archive
        if (archive == null) {
            wrap.addView(rowView(appContext.getString(R.string.overlay_asset_no_archive), null))
            appendHintRows(wrap, response)
            return wrap
        }
        wrap.addView(rowView(appContext.getString(R.string.overlay_asset_no_fmt, archive.customerNo), archive.customerNo))
        wrap.addView(rowView(appContext.getString(R.string.overlay_asset_name_fmt, archive.name), null))
        archive.vipGrade?.takeIf { it.isNotBlank() }?.let {
            wrap.addView(rowView(appContext.getString(R.string.overlay_asset_vip_fmt, it), null))
        }
        if (archive.seats.isNotEmpty()) {
            val seats = archive.seats.joinToString(" · ") { seat ->
                appContext.getString(R.string.overlay_seat_fmt, roleLabel(seat.role), seat.name)
            }
            wrap.addView(rowView(appContext.getString(R.string.overlay_asset_seats_fmt, seats), null))
        }
        val deposit = archive.depositBalance?.takeIf { it.isNotBlank() } ?: "0"
        wrap.addView(rowView(appContext.getString(R.string.overlay_asset_points_fmt, archive.availablePoints, deposit), null))
        archive.phoneMask?.takeIf { it.isNotBlank() }?.let {
            wrap.addView(rowView(appContext.getString(R.string.overlay_asset_phone_fmt, it), null))
        }
        archive.lastVisitAt?.takeIf { it.isNotBlank() }?.let {
            wrap.addView(rowView(appContext.getString(R.string.overlay_asset_visit_fmt, it), null))
        }
        val actions = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        actions.addView(
            smallButton(appContext.getString(R.string.overlay_btn_copy_dossier_no)) {
                copyToClipboard(
                    archive.customerNo,
                    appContext.getString(R.string.overlay_copied_dossier_no_fmt, archive.customerNo),
                )
            },
        )
        actions.addView(
            smallButton(appContext.getString(R.string.overlay_btn_copy_remark_code)) {
                copyToClipboard(
                    " #${archive.customerNo}",
                    appContext.getString(R.string.overlay_copied_remark_code_fmt, " #${archive.customerNo}"),
                )
            },
        )
        wrap.addView(actions)
        return wrap
    }

    /** 一键补录岚时主/备手机号。 */
    private fun phonePanel(response: CenterResponse): View {
        val wrap = LinearLayout(appContext).apply { orientation = LinearLayout.VERTICAL }
        val phone = response.pendingPhone
        if (phone.isNullOrBlank()) {
            wrap.addView(rowView(appContext.getString(R.string.overlay_phone_none), null))
            return wrap
        }
        val mainSlot = response.phoneSlot != "spare"
        val mask = response.pendingMask?.takeIf { it.isNotBlank() } ?: phone
        val label = appContext.getString(
            if (mainSlot) R.string.overlay_btn_supplement_main_fmt else R.string.overlay_btn_supplement_spare_fmt,
            mask,
        )
        val button = Button(appContext).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setTextColor(Color.WHITE)
            setBackgroundColor(if (mainSlot) 0xFF1565C0.toInt() else 0xFF6A1B9A.toInt())
            setOnClickListener {
                isEnabled = false
                text = appContext.getString(R.string.overlay_btn_submitting)
                sendSupplementFeedback(response, phone)
            }
        }
        wrap.addView(
            button,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        wrap.addView(rowView(appContext.getString(R.string.overlay_phone_conflict_tip), null))
        return wrap
    }

    /** 跟进话术（只读，纯话术正文复制）。 */
    private fun scriptPanel(response: CenterResponse): View {
        val wrap = LinearLayout(appContext).apply { orientation = LinearLayout.VERTICAL }
        appendHintRows(wrap, response)
        return wrap
    }

    private fun sendSupplementFeedback(response: CenterResponse, phone: String) {
        val feedback = CandidateFeedback(
            requestId = response.requestId,
            serviceAccountId = settings.serviceAccountId,
            deviceId = settings.deviceId,
            title = lastTitle,
            label = "supplement_his_phone",
            candidateIndex = 0,
            accepted = true,
            ts = System.currentTimeMillis(),
            event = "supplement_his_phone",
            contactWechatId = profileContext?.contactWechatId,
            profileNickname = profileContext?.profileNickname,
            profileTags = profileContext?.profileTags ?: emptyList(),
            profileRegion = profileContext?.profileRegion,
            phone = phone,
        )
        centerClient.feedback(feedback)
    }

    private fun roleLabel(role: String): String = when (role) {
        "develop" -> "开发"
        "traffic_ops" -> "运营"
        "counselor" -> "顾问"
        "doctor" -> "医生"
        "butler" -> "管家"
        "nurse" -> "护士"
        else -> role
    }


    private fun titleFor(response: CenterResponse): String = when (response.action) {
        CenterAction.SHOW_HINT -> appContext.getString(R.string.overlay_title_hint)
        CenterAction.SHOW_CANDIDATES -> appContext.getString(R.string.overlay_title_candidates)
        CenterAction.NO_MATCH -> appContext.getString(R.string.overlay_title_no_match)
        CenterAction.PREPARE_HIS_CREATE -> appContext.getString(R.string.overlay_title_prepare_his)
        CenterAction.PEEK -> ""
        CenterAction.NONE -> ""
    }

    /**
     * 一条文本 + 右侧「复制」按钮。
     * [copyText] 为 null 时表示状态行（归属/资产/来源等），不渲染复制按钮、整行不可点。
     */
    private fun rowView(text: String, copyText: String? = text): View {
        val row = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        if (copyText != null) {
            row.isClickable = true
            row.setOnClickListener { copyToClipboard(copyText) }
        }
        val content = TextView(appContext).apply {
            this.text = text
            setTextColor(0xFFE8E8E8.toInt())
            textSize = 13f
            setPadding(0, 0, dp(8), 0)
        }
        row.addView(
            content,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (copyText != null) {
            row.addView(copyButton(copyText))
        }
        return row
    }

    /** 候选：文本（label + 置信度 + evidence）+ 右侧复制 + 下方两个决策按钮。 */
    private fun candidateRow(candidate: Candidate, index: Int, response: CenterResponse): View {
        val wrap = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(6))
        }

        val meta = appContext.getString(
            R.string.overlay_candidate_meta_fmt,
            (candidate.confidence * 100).toInt(),
            candidate.evidence,
        )
        val fullText = candidate.label + "\n" + meta

        val top = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val content = TextView(appContext).apply {
            text = fullText
            setTextColor(0xFFE8E8E8.toInt())
            textSize = 13f
            setPadding(0, 0, dp(8), 0)
        }
        top.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(copyButton(fullText))
        wrap.addView(top)

        val actions = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, 0)
        }
        actions.addView(
            smallButton(appContext.getString(R.string.overlay_btn_confirm)) {
                sendFeedback(response, index, candidate, accepted = true)
                dismiss()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        actions.addView(
            smallButton(appContext.getString(R.string.overlay_btn_reject)) {
                sendFeedback(response, index, candidate, accepted = false)
                dismiss()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        wrap.addView(actions)
        return wrap
    }

    /**
     * 「待建岚时」面板（WP-26）：
     *   * 顶部紧凑预览（姓名/备注、脱敏微信号、开发人、识别到的项目标签）；
     *   * 预匹配渠道渲染为醒目绿色主按钮「✅ 立即建入岚时【医美APP / 美团】」；
     *   * 其余常用渠道每行 2 个紧凑胶囊按钮，点任一渠道即按该渠道建档。
     * 点击后按钮立即变为「建档中...」并禁用，防止连点重复建档。
     */
    private fun prepareHisCreatePanel(response: CenterResponse): View {
        val wrap = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(6))
        }
        val draft = response.hisDraft
        val preview = StringBuilder()
        preview.append("姓名/备注：").append(draft?.customerName?.takeIf { it.isNotBlank() } ?: "-")
        draft?.wechatMask?.takeIf { it.isNotBlank() }?.let { preview.append("\n微信号：").append(it) }
        draft?.phoneMask?.takeIf { it.isNotBlank() }?.let { preview.append("\n手机号：").append(it) }
        draft?.developerName?.takeIf { it.isNotBlank() }?.let { preview.append("\n开发人：").append(it) }
        draft?.wxAddedAt?.takeIf { it.isNotBlank() }?.let { preview.append("\n微信首加：").append(it) }
        if (!draft?.matchedTags.isNullOrEmpty()) {
            preview.append("\n识别项目：").append(draft!!.matchedTags.joinToString("、"))
        }
        if (!draft?.matchedSourceOne.isNullOrBlank()) {
            preview.append("\n预匹配渠道：").append(draft!!.matchedSourceOne)
            draft.matchedSourceTwo?.takeIf { it.isNotBlank() }?.let { preview.append(" / ").append(it) }
        }
        wrap.addView(
            TextView(appContext).apply {
                text = preview.toString()
                setTextColor(0xFFE8E8E8.toInt())
                textSize = 13f
                setPadding(0, 0, 0, dp(6))
            },
        )

        val options = response.channelOptions
        if (options.isEmpty()) {
            wrap.addView(
                TextView(appContext).apply {
                    text = appContext.getString(R.string.overlay_prepare_empty)
                    setTextColor(0xFFFFB74D.toInt())
                    textSize = 12f
                },
            )
            return wrap
        }

        val primary = options.firstOrNull { it.recommended } ?: options.first()
        // WP-27：引流运营选择（默认「无运营」，优先恢复本机上次选择）。
        val operators = response.operatorOptions
        var selectedOperator: OperatorOption? = operators.firstOrNull()
        LastChoiceMemory.decodeOperator(settings.lastOperatorRaw)?.let { last ->
            operators.firstOrNull { it.id == last.id && it.name == last.name }?.let { selectedOperator = it }
        }
        val primaryButton = Button(appContext).apply {
            text = appContext.getString(R.string.overlay_btn_create_his_fmt, primary.label)
            textSize = 13f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF2E7D32.toInt())
            setOnClickListener {
                isEnabled = false
                text = appContext.getString(R.string.overlay_btn_creating_his)
                sendCreateFeedback(response, primary, options.indexOf(primary), selectedOperator)
            }
        }
        wrap.addView(
            primaryButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        // 其余渠道：每行 2 个紧凑按钮。
        val rest = options.filter { it != primary }
        var index = 0
        while (index < rest.size) {
            val row = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, 0)
            }
            for (slot in 0..1) {
                val position = index + slot
                if (position >= rest.size) {
                    row.addView(View(appContext), LinearLayout.LayoutParams(0, 1, 1f))
                    continue
                }
                val channel = rest[position]
                val button = Button(appContext).apply {
                    text = channel.label
                    textSize = 11f
                    isAllCaps = false
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(4), dp(2), dp(4), dp(2))
                    setOnClickListener {
                        isEnabled = false
                        text = appContext.getString(R.string.overlay_btn_creating_his)
                        sendCreateFeedback(response, channel, options.indexOf(channel), selectedOperator)
                    }
                }
                row.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            wrap.addView(row)
            index += 2
        }

        // 引流运营（写入岚时 belongServiceId + 中枢 traffic_ops 席位）。
        wrap.addView(
            TextView(appContext).apply {
                text = appContext.getString(R.string.overlay_operator_title)
                setTextColor(0xFFE8E8E8.toInt())
                textSize = 12f
                setPadding(0, dp(6), 0, dp(2))
            },
        )
        val operatorStatus = TextView(appContext).apply {
            text = appContext.getString(
                R.string.overlay_operator_selected_fmt,
                selectedOperator?.name ?: appContext.getString(R.string.overlay_operator_none),
            )
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 11f
        }
        wrap.addView(operatorStatus)
        val chips = operators.take(9)
        var chipIndex = 0
        while (chipIndex < chips.size) {
            val row = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(2), 0, 0)
            }
            for (slot in 0..2) {
                val position = chipIndex + slot
                if (position >= chips.size) {
                    row.addView(View(appContext), LinearLayout.LayoutParams(0, 1, 1f))
                    continue
                }
                val operator = chips[position]
                val label = operator.name.ifBlank { appContext.getString(R.string.overlay_operator_none) }
                val button = Button(appContext).apply {
                    text = label
                    textSize = 10f
                    isAllCaps = false
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    setOnClickListener {
                        selectedOperator = operator
                        operatorStatus.text = appContext.getString(R.string.overlay_operator_selected_fmt, label)
                    }
                }
                row.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            wrap.addView(row)
            chipIndex += 3
        }
        wrap.addView(
            TextView(appContext).apply {
                text = appContext.getString(R.string.overlay_operator_meituan_locked)
                setTextColor(0xFFFFB74D.toInt())
                textSize = 10f
                setPadding(0, dp(2), 0, 0)
            },
        )
        return wrap
    }

    /** 员工点选渠道后发送建档反馈（唯一会触发岚时建档的入口）。 */
    private fun sendCreateFeedback(
        response: CenterResponse,
        channel: ChannelOption,
        index: Int,
        operator: OperatorOption?,
    ) {
        val context = profileContext
        val draft = response.hisDraft
        // 记忆上次所选渠道/运营（只存名称，不存任何聊天内容）。
        settings.lastChannelRaw = LastChoiceMemory.encodeChannel(channel.sourceOne, channel.sourceTwo)
        val effectiveOperator = if (isMeituanChannel(channel)) null else operator
        if (effectiveOperator != null) {
            settings.lastOperatorRaw = LastChoiceMemory.encodeOperator(effectiveOperator.id, effectiveOperator.name)
        }
        val feedback = CandidateFeedback(
            requestId = response.requestId,
            serviceAccountId = settings.serviceAccountId,
            deviceId = settings.deviceId,
            title = lastTitle,
            label = channel.label,
            candidateIndex = index,
            accepted = true,
            ts = System.currentTimeMillis(),
            event = "create_his_dossier",
            sourceOne = channel.sourceOne,
            sourceTwo = channel.sourceTwo,
            contactWechatId = context?.contactWechatId ?: draft?.contactWechatId,
            profileNickname = context?.profileNickname ?: draft?.customerName,
            profileTags = context?.profileTags ?: draft?.matchedTags ?: emptyList(),
            profileRegion = context?.profileRegion,
            belongServiceId = effectiveOperator?.id?.takeIf { it.isNotBlank() },
            belongServiceName = effectiveOperator?.name?.takeIf { it.isNotBlank() },
        )
        centerClient.feedback(feedback)
    }

    /** 美团/大众点评渠道不挂运营（与后端硬锁一致，避免渠道业绩串档）。 */
    private fun isMeituanChannel(channel: ChannelOption): Boolean =
        channel.sourceTwo.contains("美团") ||
            channel.sourceTwo.contains("大众点评") ||
            channel.sourceOne.contains("美团")


    private fun copyButton(text: String): Button =
        Button(appContext).apply {
            this.text = appContext.getString(R.string.overlay_btn_copy)
            textSize = 12f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener {
                copyToClipboard(text)
            }
        }

    private fun sendFeedback(
        response: CenterResponse,
        index: Int,
        candidate: Candidate,
        accepted: Boolean,
    ) {
        // 只回传用户的选择与候选标识；绝不包含微信输入框内容，也不触发任何自动发送。
        val feedback = CandidateFeedback(
            requestId = response.requestId,
            serviceAccountId = settings.serviceAccountId,
            deviceId = settings.deviceId,
            title = lastTitle,
            label = candidate.label,
            candidateIndex = index,
            accepted = accepted,
            ts = System.currentTimeMillis(),
        )
        centerClient.feedback(feedback)
    }

    // ---------------------------------------------------------------- 复制

    /**
     * 复制到系统剪贴板。
     *
     * 只写剪贴板，不向任何应用注入输入事件，也不触发粘贴。
     * 粘贴永远由人工完成。
     */
    private fun copyToClipboard(text: String, toastMessage: String? = null) {
        try {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (cm == null) {
                logger.event("clipboard_unavailable")
                return
            }
            cm.setPrimaryClip(ClipData.newPlainText("kf_hint", text))
            Toast.makeText(
                appContext,
                toastMessage ?: appContext.getString(R.string.overlay_copied),
                Toast.LENGTH_SHORT,
            ).show()
            logger.event("overlay_copied")
        } catch (_: Throwable) {
            logger.event("clipboard_failed")
        }
    }

    // ---------------------------------------------------------------- 视图工具

    private fun titleView(text: String): TextView =
        TextView(appContext).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(4))
        }

    private fun footerView(): TextView =
        TextView(appContext).apply {
            text = appContext.getString(R.string.overlay_list_hint)
            setTextColor(0xFF888888.toInt())
            textSize = 10f
            setPadding(0, dp(2), 0, 0)
        }

    private fun smallButton(text: String, onClick: () -> Unit): Button =
        Button(appContext).apply {
            this.text = text
            textSize = 12f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { onClick() }
        }

    /**
     * 面板背景：半透明深色，不透明度由设置页控制（默认 78%）。
     *
     * 100% = 完全不透明；数值越小越透，越能看清下面的微信界面。
     */
    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8f).toFloat()
        val percent = settings.overlayOpacityPercent
        val alpha = (percent * 255 / 100).coerceIn(0, 255)
        setColor((alpha shl 24) or 0x101010)
        setStroke(dp(1), 0x40FFFFFF)
    }

    private fun statusBarHeight(): Int {
        val resId = appContext.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) appContext.resources.getDimensionPixelSize(resId) else dp(24)
    }

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Int = (value * appContext.resources.displayMetrics.density).toInt()

    private fun hasOverlayPermission(): Boolean =
        android.provider.Settings.canDrawOverlays(appContext)

    companion object {
        /** 面板高度：屏高 1/3（贴顶部）。 */
        const val HEIGHT_RATIO = 1f / 3f

        /** 待建岚时面板需要展示渠道 + 运营，给到 52% 屏高。 */
        const val PREPARE_HEIGHT_RATIO = 0.52f

        /** 悬浮球窗口尺寸（内部圆球 44dp，外层留角标空间）。 */
        const val BALL_SIZE_DP = 52

        /** 悬浮球默认停在屏幕右侧 35% 高度，避开输入法与发送键。 */
        const val DEFAULT_BALL_Y_RATIO = 0.35f

        /** 无角标闲置 4 秒后降为 45% 不透明度。 */
        const val IDLE_ALPHA_MS = 4_000L

        /** 建档成功后自动收起面板回到小悬浮球。 */
        const val AUTO_COLLAPSE_MS = 900L

        /** no_match 自动关闭延时。 */
        const val AUTO_DISMISS_MS = 5_000L
    }
}
