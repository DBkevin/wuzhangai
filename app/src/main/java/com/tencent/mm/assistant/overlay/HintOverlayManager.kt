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

    /** 最近一次资料页上下文（微信号/昵称/标签/地区），供一键建档回传。 */
    private var profileContext: ProfileContext? = null

    /** 记录最近一次资料页上下文（由 WeChatReadService 在人工打开资料页时调用）。 */
    fun rememberProfileContext(context: ProfileContext) {
        mainHandler.post { profileContext = context }
    }

    // ---------------------------------------------------------------- 展示

    fun show(response: CenterResponse, sessionKey: String, title: String?) {
        if (response.action == CenterAction.NONE) return
        mainHandler.post { showInternal(response, sessionKey, title) }
    }

    private fun showInternal(response: CenterResponse, sessionKey: String, title: String?) {
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

        removeInternal()

        // WP-26：在岚时建档成功后，中控下发 ` #<档案号>`，这里自动写入系统剪贴板，
        // 员工退回微信点备注即可直接粘贴（粘贴仍由人工完成，App 绝不触发）。
        response.autoCopyText?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            copyToClipboard(
                text,
                appContext.getString(R.string.overlay_copied_remark_code_fmt, text.trim()),
            )
        }

        val metrics = appContext.resources.displayMetrics
        val panelHeight = (metrics.heightPixels * HEIGHT_RATIO).toInt()

        val view = buildView(response, panelHeight)
            ?: run {
                logger.event("overlay_build_failed")
                return
            }

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,   // 满屏宽
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
        logger.event("overlay_shown")

        if (response.action == CenterAction.NO_MATCH) {
            val r = Runnable { dismiss() }
            autoDismissRunnable = r
            mainHandler.postDelayed(r, AUTO_DISMISS_MS)
        }
    }

    // ---------------------------------------------------------------- 关闭

    fun dismiss() {
        mainHandler.post { removeInternal() }
    }

    private fun removeInternal() {
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = null
        val view = currentView ?: return
        currentView = null
        currentSessionKey = null
        lastResponse = null
        lastTitle = null
        try {
            windowManager?.removeView(view)
        } catch (_: Throwable) {
            // 视图已被移除或窗口失效：忽略
        }
        logger.event("overlay_dismissed")
    }

    // ---------------------------------------------------------------- 视图构建

    private fun buildView(response: CenterResponse, panelHeight: Int): View? {
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
        // WP-26：顶栏固定显示两个独立复制按钮（仅当提示里含 `#档案号`）。
        HintCopyHelper.extractDossierNo(response.hint)?.let { dossierNo ->
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
        header.addView(smallButton(appContext.getString(R.string.overlay_btn_close)) { dismiss() })
        root.addView(header)

        // 中间：可滚动的条目列表
        val list = LinearLayout(appContext).apply { orientation = LinearLayout.VERTICAL }
        when (response.action) {
            CenterAction.SHOW_HINT -> {
                // 提示按行拆成"一条条"：话术行只复制剥离前缀后的正文，状态行不渲染复制按钮。
                val hint = response.hint ?: appContext.getString(R.string.overlay_empty_hint)
                val items = hint.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (items.isEmpty()) {
                    list.addView(rowView(hint, HintCopyHelper.copyableRowText(hint)))
                } else {
                    items.forEach { line ->
                        list.addView(rowView(line, HintCopyHelper.copyableRowText(line)))
                    }
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

    private fun titleFor(response: CenterResponse): String = when (response.action) {
        CenterAction.SHOW_HINT -> appContext.getString(R.string.overlay_title_hint)
        CenterAction.SHOW_CANDIDATES -> appContext.getString(R.string.overlay_title_candidates)
        CenterAction.NO_MATCH -> appContext.getString(R.string.overlay_title_no_match)
        CenterAction.PREPARE_HIS_CREATE -> appContext.getString(R.string.overlay_title_prepare_his)
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
                sendCreateFeedback(response, primary, options.indexOf(primary))
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
                        sendCreateFeedback(response, channel, options.indexOf(channel))
                    }
                }
                row.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            wrap.addView(row)
            index += 2
        }
        return wrap
    }

    /** 员工点选渠道后发送建档反馈（唯一会触发岚时建档的入口）。 */
    private fun sendCreateFeedback(response: CenterResponse, channel: ChannelOption, index: Int) {
        val context = profileContext
        val draft = response.hisDraft
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
        )
        centerClient.feedback(feedback)
    }


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

        /** no_match 自动关闭延时。 */
        const val AUTO_DISMISS_MS = 5_000L

    }
}
