package com.tencent.mm.assistant.net

import com.tencent.mm.assistant.util.SafeLogger
import com.tencent.mm.assistant.util.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 与中控通信的 HTTP 客户端。
 *
 * 硬性要求（PRD 模块 2.4）：
 *  * 超时 3 秒；失败重试 1 次（即最多 2 次尝试）
 *  * 仍失败则不展示任何提示，仅静默记录日志
 *  * 绝不阻塞无障碍服务线程 —— 所有网络调用走独立单线程池，
 *    回调统一 post 回主线程，主线程只做 UI 展示。
 */
class CenterClient(
    private val settings: SettingsStore,
    private val logger: SafeLogger,
) {

    private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kf-center-io").apply { isDaemon = true }
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false) // 重试策略由本类显式控制，保持"最多 2 次"语义清晰
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ---------------------------------------------------------------- 上报

    /**
     * 回传一次会话快照。网络异常一律吞掉，不向调用方抛异常，不弹任何提示。
     *
     * @param onResponse 仅在成功拿到可解析响应时回调（主线程）；失败时为 null。
     */
    fun report(report: ReadReport, onResponse: (CenterResponse?) -> Unit) {
        val url = settings.centerUrl
        if (url.isBlank()) {
            logger.event("center_url_empty")
            onResponse(null)
            return
        }
        post(url, report.toJson()) { body ->
            val parsed = body?.let { CenterResponse.parse(it) }
            if (parsed == null) logger.event("center_bad_response")
            onResponse(parsed)
        }
    }

    // ---------------------------------------------------------------- 反馈

    /** 上报候选反馈（/feedback）。同样静默失败。 */
    fun feedback(feedback: CandidateFeedback, onDone: (Boolean) -> Unit = {}) {
        val url = settings.feedbackUrl
        if (url.isBlank()) {
            logger.event("feedback_url_empty")
            onDone(false)
            return
        }
        post(url, feedback.toJson()) { body ->
            onDone(body != null)
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * POST + 最多 2 次尝试。所有回调都在 IO 线程；调用方负责切主线程。
     */
    private fun post(url: String, jsonBody: String, onResult: (String?) -> Unit) {
        io.execute {
            var attempt = 0
            var lastCode = 0
            while (attempt < MAX_ATTEMPTS) {
                attempt++
                val result = attemptPost(url, jsonBody)
                lastCode = result.code
                if (result.body != null) {
                    logger.http(url = url, code = result.code, attempt = attempt)
                    onResult(result.body)
                    return@execute
                }
                // 失败：仅在还有重试机会时继续
                if (attempt < MAX_ATTEMPTS) {
                    logger.http(url = url, code = result.code, attempt = attempt)
                }
            }
            logger.http(url = url, code = lastCode, attempt = attempt)
            onResult(null)
        }
    }

    private class PostResult(val code: Int, val body: String?)

    private fun attemptPost(url: String, jsonBody: String): PostResult {
        val request = try {
            Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody(jsonMedia))
                .header("Content-Type", "application/json; charset=utf-8")
                .build()
        } catch (_: IllegalArgumentException) {
            // URL 非法（用户在设置页填错）——直接放弃，不崩溃
            return PostResult(-1, null)
        }

        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return PostResult(response.code, null)
                }
                val text = response.body?.string()
                if (text.isNullOrBlank()) PostResult(response.code, null)
                else PostResult(response.code, text)
            }
        } catch (_: IOException) {
            PostResult(0, null)
        } catch (_: Exception) {
            // 防御：任何解析/协议异常都不得冒泡到无障碍服务
            PostResult(-1, null)
        }
    }

    fun shutdown() {
        try {
            io.shutdownNow()
        } catch (_: Exception) {
            // ignore
        }
    }

    companion object {
        const val TIMEOUT_SECONDS = 3L

        /** 首次 + 重试 1 次 = 2 次尝试。 */
        const val MAX_ATTEMPTS = 2
    }
}
