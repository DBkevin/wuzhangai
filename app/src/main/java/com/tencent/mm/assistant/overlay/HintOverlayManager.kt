package com.tencent.mm.assistant.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.mm.assistant.R
import com.tencent.mm.assistant.net.CenterAction
import com.tencent.mm.assistant.net.CenterClient
import com.tencent.mm.assistant.net.CenterResponse
import com.tencent.mm.assistant.net.CandidateFeedback
import com.tencent.mm.assistant.util.SafeLogger
import com.tencent.mm.assistant.util.SettingsStore

/**
 * 悬浮窗管理。
 *
 * 边界（PRD 模块 3）：
 *  * 仅在收到中控响应且 action != none 时显示；
 *  * TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE：绝不抢占微信输入焦点；
 *  * 窗口尺寸为包裹内容、宽度上限 40% 屏宽，贴屏幕右侧垂直居中，
 *    不会落入底部输入框区域（也不提供拖动，杜绝被拖进输入区）；
 *  * 同一会话同时只存在一个悬浮窗，新响应到来时先移除旧窗口再展示；
 *  * 点击按钮只会把"用户的选择"POST 到中控 /feedback，然后关闭悬浮窗 ——
 *    绝不触碰微信输入框，绝不发送任何微信消息。
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

        val view = buildView(response)
            ?: run {
                logger.event("overlay_build_failed")
                return
            }

        val metrics = appContext.resources.displayMetrics
        val maxWidth = (metrics.widthPixels * MAX_WIDTH_RATIO).toInt()
        val params = WindowManager.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(RIGHT_MARGIN_DP)
            y = 0
        }
        // 包裹内容 + 最大宽度限制：用 post 再量一次，超出则压回。
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        if (view.measuredWidth > maxWidth) {
            params.width = maxWidth
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

    private fun buildView(response: CenterResponse): View? {
        val context = appContext
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = cardBackground()
        }

        when (response.action) {
            CenterAction.SHOW_HINT -> {
                root.addView(titleView(context, context.getString(R.string.overlay_title_hint)))
                root.addView(bodyView(context, response.hint ?: context.getString(R.string.overlay_empty_hint)))
                root.addView(button(context, context.getString(R.string.overlay_btn_got_it)) {
                    dismiss()
                })
            }

            CenterAction.SHOW_CANDIDATES -> {
                root.addView(titleView(context, context.getString(R.string.overlay_title_candidates)))
                if (response.candidates.isEmpty()) {
                    root.addView(bodyView(context, context.getString(R.string.overlay_empty_candidates)))
                } else {
                    response.candidates.forEachIndexed { index, candidate ->
                        root.addView(candidateCard(context, candidate, index, response))
                    }
                }
                root.addView(button(context, context.getString(R.string.overlay_btn_close)) {
                    dismiss()
                })
            }

            CenterAction.NO_MATCH -> {
                root.addView(titleView(context, context.getString(R.string.overlay_title_no_match)))
                root.addView(bodyView(context, context.getString(R.string.overlay_no_match_body)))
                root.addView(button(context, context.getString(R.string.overlay_btn_close)) {
                    dismiss()
                })
            }

            CenterAction.NONE -> return null
        }
        return root
    }

    private fun candidateCard(
        context: Context,
        candidate: com.tencent.mm.assistant.net.Candidate,
        index: Int,
        response: CenterResponse,
    ): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = candidateBackground()
        }

        card.addView(
            bodyView(
                context,
                context.getString(R.string.overlay_candidate_label_fmt, candidate.label),
            ).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) },
        )
        card.addView(
            bodyView(
                context,
                context.getString(
                    R.string.overlay_candidate_meta_fmt,
                    (candidate.confidence * 100).toInt(),
                    candidate.evidence,
                ),
            ),
        )

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        row.addView(
            button(context, context.getString(R.string.overlay_btn_confirm)) {
                sendFeedback(response, index, candidate, accepted = true)
                dismiss()
            },
            LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            button(context, context.getString(R.string.overlay_btn_reject)) {
                sendFeedback(response, index, candidate, accepted = false)
                dismiss()
            },
            LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        card.addView(row)
        return card
    }

    private fun sendFeedback(
        response: CenterResponse,
        index: Int,
        candidate: com.tencent.mm.assistant.net.Candidate,
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

    // ---------------------------------------------------------------- 视图工具

    private fun titleView(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(6))
        }

    private fun bodyView(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 13f
            maxLines = 6
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(1), 0, dp(4))
        }

    private fun button(context: Context, text: String, onClick: () -> Unit): Button =
        Button(context).apply {
            this.text = text
            textSize = 13f
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10f)
        setColor(0xE6101010.toInt())
    }

    private fun candidateBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8f)
        setColor(0x22FFFFFF)
    }

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Int = (value * appContext.resources.displayMetrics.density).toInt()

    private fun hasOverlayPermission(): Boolean =
        android.provider.Settings.canDrawOverlays(appContext)

    companion object {
        /** 悬浮窗最大宽度：不超过屏宽 40%（PRD 模块 3.3）。 */
        const val MAX_WIDTH_RATIO = 0.40f

        /** 距屏幕右缘的边距（dp）。固定右侧居中，不提供拖动，杜绝落入底部输入框区域。 */
        const val RIGHT_MARGIN_DP = 8

        /** no_match 自动关闭延时。 */
        const val AUTO_DISMISS_MS = 5_000L
    }
}
