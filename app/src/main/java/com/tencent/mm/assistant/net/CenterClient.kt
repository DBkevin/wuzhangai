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

    private val httpPeek: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(PEEK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PEEK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(PEEK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(PEEK_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** 主动展开资料页查岚时 / 一键建入岚时 / 补录手机号：4G 下跨云请求需留足 12 秒。 */
    private val httpAction: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(ACTION_TIMEOUT_SECONDS + 3L, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ---------------------------------------------------------------- 上报

    /**
     * 回传一次会话快照。网络异常一律吞掉，不向调用方抛异常，不弹任何提示。
     *
     * @param onResponse 仅在成功拿到可解析响应时回调；失败时为 null。
     */
    fun report(report: ReadReport, onResponse: (CenterResponse?) -> Unit) {
        val url = settings.centerUrl
        if (url.isBlank()) {
            logger.event("center_url_empty")
            onResponse(null)
            return
        }
        val isPeek = report.mode.equals("peek", ignoreCase = true)
        val client = if (isPeek) httpPeek else httpAction
        post(client, url, report.toJson(), maxAttempts = MAX_ATTEMPTS) { body ->
            val parsed = body?.let { CenterResponse.parse(it) }
            if (parsed == null) logger.event("center_bad_response")
            onResponse(parsed)
        }
    }

    // ---------------------------------------------------------------- 反馈

    /** 上报候选反馈（/feedback）。同样静默失败。 */
    fun feedback(feedback: CandidateFeedback, onDone: (Boolean) -> Unit = {}) {
        feedbackWithResponse(feedback) { resp ->
            onDone(resp != null)
        }
    }

    /**
     * 上报反馈并解析返回的 CenterResponse（供一键建入岚时 / 一键补录手机号更新面板与自动复制备注码）。
     * 写操作强制单次请求（maxAttempts = 1），避免 4G 偶发慢响应触发二次重复提交。
     */
    fun feedbackWithResponse(feedback: CandidateFeedback, onResponse: (CenterResponse?) -> Unit) {
        val url = settings.feedbackUrl
        if (url.isBlank()) {
            logger.event("feedback_url_empty")
            onResponse(null)
            return
        }
        val isMutation = feedback.event == "create_his_dossier" || feedback.event == "supplement_his_phone"
        val attempts = if (isMutation) 1 else MAX_ATTEMPTS
        post(httpAction, url, feedback.toJson(), maxAttempts = attempts) { body ->
            val parsed = body?.let { CenterResponse.parse(it) }
            if (parsed == null) logger.event("feedback_bad_response")
            onResponse(parsed)
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * POST + 最多 maxAttempts 次尝试。所有回调都在 IO 线程；调用方负责切主线程。
     */
    private fun post(
        client: OkHttpClient,
        url: String,
        jsonBody: String,
        maxAttempts: Int,
        onResult: (String?) -> Unit,
    ) {
        io.execute {
            var attempt = 0
            var lastCode = 0
            while (attempt < maxAttempts) {
                attempt++
                val result = attemptPost(client, url, jsonBody)
                lastCode = result.code
                if (result.body != null) {
                    logger.http(url = url, code = result.code, attempt = attempt)
                    onResult(result.body)
                    return@execute
                }
                // 失败：仅在还有重试机会时继续
                if (attempt < maxAttempts) {
                    logger.http(url = url, code = result.code, attempt = attempt)
                }
            }
            logger.http(url = url, code = lastCode, attempt = attempt)
            onResult(null)
        }
    }

    private class PostResult(val code: Int, val body: String?)

    private fun attemptPost(client: OkHttpClient, url: String, jsonBody: String): PostResult {
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
            client.newCall(request).execute().use { response ->
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
        const val TIMEOUT_SECONDS = 4L
        const val PEEK_TIMEOUT_SECONDS = 4L
        const val ACTION_TIMEOUT_SECONDS = 12L

        /** 首次 + 重试 1 次 = 2 次尝试。 */
        const val MAX_ATTEMPTS = 2
    }
}
