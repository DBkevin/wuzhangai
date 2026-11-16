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
import com.tencent.mm.assistant.util.SafeLogger
import com.tencent.mm.assistant.util.SettingsStore

/**
 * 悬浮窗管理。
 *
 * 边界（PRD 模块 3）：
 *  * 仅在收到中控响应且 action != none 时显示；
 *  * TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE：绝不抢占微信输入焦点；
 *  * 固定宽度（屏宽 40%）、贴屏幕右侧垂直居中，不提供拖动，不会落入底部输入框；
 *  * 同一会话同时只存在一个悬浮窗，新响应到来时先移除旧窗口再展示；
 *  * 点击按钮只把"用户的选择"POST 到中控 /feedback，然后关闭 ——
 *    绝不触碰微信输入框，绝不发送任何微信消息。
 *
 * 交互增强：
 *  * 内容超出最大高度时可**滚动**（ScrollView + 高度上限）；
 *  * 提示文本 / 候选条目**点按即复制**到系统剪贴板（只复制，不注入任何应用）。
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

    /** 中控最近一次响应的上下文，反馈按钮需要用到。 */
    private var lastResponse: CenterResponse? = null
    private var lastTitle: String? = null

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

        // 权限缺失：静默放弃展示（PRD 验收 2：无权限/无网络不得打扰微信）。
        if (!hasOverlayPermission()) {
            logger.event("overlay_no_permission")
            return
        }

        removeInternal()

        val metrics = appContext.resources.displayMetrics
        // 固定宽度：内容再多也不改变窗口宽度，避免窗口"跳动"
        val boxWidth = (metrics.widthPixels * MAX_WIDTH_RATIO).toInt()
        // 内容最大高度：超出后内部滚动，窗口不会顶到屏幕外
        val maxScrollHeight = (metrics.heightPixels * MAX_HEIGHT_RATIO).toInt()

        val view = buildView(response, boxWidth, maxScrollHeight)
            ?: run {
                logger.event("overlay_build_failed")
                return
            }

        val params = WindowManager.LayoutParams(
            boxWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(RIGHT_MARGIN_DP)
            y = 0
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
            // no_match：5 秒后自动消失。
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

    private fun buildView(response: CenterResponse, boxWidth: Int, maxScrollHeight: Int): View? {
        val root = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = cardBackground()
        }
        // 内容区可用宽度（扣除左右内边距），测量换行时必须用这个宽度
        val contentWidth = (boxWidth - dp(14) * 2).coerceAtLeast(dp(60))

        when (response.action) {
            CenterAction.SHOW_HINT -> {
                val hint = response.hint ?: appContext.getString(R.string.overlay_empty_hint)
                root.addView(titleView(appContext.getString(R.string.overlay_title_hint)))

                // 注意：正文**不设 clickable** —— 可点击的 TextView 会吃掉滑动手势，
                // 导致内容无法滚动。复制改用下方明确的「复制」按钮。
                val body = bodyView(hint)
                root.addView(wrapScroll(body, contentWidth, maxScrollHeight))
                root.addView(tapHintView())
                root.addView(
                    buttonRow(
                        button(appContext.getString(R.string.overlay_btn_copy)) { copyToClipboard(hint) },
                        button(appContext.getString(R.string.overlay_btn_got_it)) { dismiss() },
                    ),
                )
            }

            CenterAction.SHOW_CANDIDATES -> {
                root.addView(titleView(appContext.getString(R.string.overlay_title_candidates)))

                val column = LinearLayout(appContext).apply { orientation = LinearLayout.VERTICAL }
                if (response.candidates.isEmpty()) {
                    column.addView(bodyView(appContext.getString(R.string.overlay_empty_candidates)))
                } else {
                    response.candidates.forEachIndexed { index, candidate ->
                        column.addView(candidateCard(candidate, index, response))
                    }
                }
                root.addView(wrapScroll(column, contentWidth, maxScrollHeight))
                root.addView(tapHintView())
                root.addView(button(appContext.getString(R.string.overlay_btn_close)) { dismiss() })
            }

            CenterAction.NO_MATCH -> {
                root.addView(titleView(appContext.getString(R.string.overlay_title_no_match)))
                root.addView(
                    wrapScroll(
                        bodyView(appContext.getString(R.string.overlay_no_match_body)),
                        contentWidth,
                        maxScrollHeight,
                    ),
                )
                root.addView(button(appContext.getString(R.string.overlay_btn_close)) { dismiss() })
            }

            CenterAction.NONE -> return null
        }
        return root
    }

    /**
     * 把内容包进可滚动容器，并限制最大高度。
     *
     * 关键：测量时必须给定**确定的宽度**（contentWidth），否则文本不会按实际宽度换行，
     * 测出的高度会远小于真实高度，导致内容被裁掉且无法滚动。
     *
     * 高度取「内容自然高度」与「上限」的较小值：
     *  * 内容不多 -> 窗口自适应，不出现多余空白
     *  * 内容很多 -> 固定在上限高度，内部可上下滚动
     */
    private fun wrapScroll(child: View, contentWidth: Int, maxHeight: Int): ScrollView {
        val scroll = ScrollView(appContext).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            addView(
                child,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        child.measure(
            View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val natural = child.measuredHeight
        val height = if (natural in 1 until maxHeight) natural else maxHeight
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            height,
        )
        return scroll
    }

    private fun candidateCard(candidate: Candidate, index: Int, response: CenterResponse): View {
        val card = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = candidateBackground()
            // 点按卡片即复制该条候选
            isClickable = true
            setOnClickListener {
                copyToClipboard(
                    buildString {
                        append(candidate.label)
                        append('\n')
                        append(appContext.getString(R.string.overlay_candidate_meta_fmt, (candidate.confidence * 100).toInt(), candidate.evidence))
                    },
                )
            }
        }

        card.addView(
            bodyView(appContext.getString(R.string.overlay_candidate_label_fmt, candidate.label))
                .apply { setTypeface(typeface, Typeface.BOLD) },
        )
        card.addView(
            bodyView(
                appContext.getString(
                    R.string.overlay_candidate_meta_fmt,
                    (candidate.confidence * 100).toInt(),
                    candidate.evidence,
                ),
            ),
        )

        val row = LinearLayout(appContext).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            button(appContext.getString(R.string.overlay_btn_confirm)) {
                sendFeedback(response, index, candidate, accepted = true)
                dismiss()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            button(appContext.getString(R.string.overlay_btn_reject)) {
                sendFeedback(response, index, candidate, accepted = false)
                dismiss()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        card.addView(row)
        return card
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
     * 用户需要自己长按输入框粘贴 —— 这一步永远由人工完成。
     */
    private fun copyToClipboard(text: String) {
        try {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (cm == null) {
                logger.event("clipboard_unavailable")
                return
            }
            cm.setPrimaryClip(ClipData.newPlainText("kf_hint", text))
            Toast.makeText(appContext, R.string.overlay_copied, Toast.LENGTH_SHORT).show()
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
            setPadding(0, dp(2), 0, dp(6))
        }

    private fun bodyView(text: String): TextView =
        TextView(appContext).apply {
            this.text = text
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 13f
            // 注意：不要设 ellipsize —— 否则在滚动容器里仍会被截断成"…"
            setPadding(0, dp(1), 0, dp(4))
        }

    /** 小字提示：告诉用户可以点按复制。 */
    private fun tapHintView(): TextView =
        TextView(appContext).apply {
            text = appContext.getString(R.string.overlay_tap_to_copy)
            setTextColor(0xFF888888.toInt())
            textSize = 10f
            setPadding(0, 0, 0, dp(4))
        }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(appContext).apply {
            this.text = text
            textSize = 13f
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    /** 一行等宽排列两个按钮。 */
    private fun buttonRow(left: View, right: View): LinearLayout =
        LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10f).toFloat()
        setColor(0xE6101010.toInt())
    }

    private fun candidateBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8f).toFloat()
        setColor(0x22FFFFFF)
    }

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Int = (value * appContext.resources.displayMetrics.density).toInt()

    private fun hasOverlayPermission(): Boolean =
        android.provider.Settings.canDrawOverlays(appContext)

    companion object {
        /** 悬浮窗固定宽度：屏宽 40%（PRD 模块 3.3 上限）。 */
        const val MAX_WIDTH_RATIO = 0.40f

        /** 内容区最大高度：屏高 55%，超出后内部滚动。 */
        const val MAX_HEIGHT_RATIO = 0.55f

        /** 距屏幕右缘的边距（dp）。固定右侧居中，不提供拖动，杜绝落入底部输入框区域。 */
        const val RIGHT_MARGIN_DP = 8

        /** no_match 自动关闭延时。 */
        const val AUTO_DISMISS_MS = 5_000L
    }
}
