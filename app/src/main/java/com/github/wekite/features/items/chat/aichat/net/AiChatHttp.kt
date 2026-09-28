package com.github.wekite.features.items.chat.aichat.net

import com.github.wekite.features.items.chat.aichat.AiChatConfig
import com.github.wekite.utils.WeLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * JEV（systemone）与 OpenAI 兼容 chat/completions 的统一 HTTP 客户端。
 * 请求可取消（切聊天/关开关时）；错误统一转成带可读信息的 IllegalStateException。
 */
object AiChatHttp {
    private const val TAG = "AiChatHttp"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private const val JSON = "application/json; charset=utf-8"

    /** JEV 结构化请求（systemone 端点）。 */
    suspend fun jevExchange(payload: JSONObject): String {
        val cfg = AiChatConfig
        check(cfg.jevConfigured) { "请先在设置中填写 JEV API Key" }
        return exchange(
            url = cfg.jevEndpoint,
            key = cfg.jevApiKey.filterNot { it.isWhitespace() },
            keyHeader = "Authorization",
            keyPrefix = "Bearer ",
            payload = payload,
        )
    }

    /** OpenAI 兼容 chat/completions。 */
    suspend fun llmExchange(messages: List<Pair<String, String>>, temperature: Double = 0.7): String {
        val cfg = AiChatConfig
        check(cfg.llmConfigured) { "请先在设置中配置 LLM 接口与模型" }
        val cleanKey = cfg.llmApiKey.filterNot { it.isWhitespace() }
        val arr = org.json.JSONArray()
        for ((role, content) in messages) arr.put(JSONObject().put("role", role).put("content", content))
        val payload = JSONObject()
            .put("model", cfg.llmModel)
            .put("messages", arr)
            .put("stream", false)
            .put("temperature", temperature)
        return exchange(
            url = cfg.llmEndpoint,
            key = cleanKey,
            keyHeader = "Authorization",
            keyPrefix = "Bearer ",
            payload = payload,
        )
    }

    private suspend fun exchange(
        url: String, key: String, keyHeader: String, keyPrefix: String, payload: JSONObject,
    ): String = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url(url)
            .header(keyHeader, "$keyPrefix$key")
            .post(payload.toString().toRequestBody(JSON.toMediaType()))
            .build()
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException("连接超时或网络不可用，请稍后重试"))
            }

            override fun onResponse(call: Call, response: Response) {
                if (!cont.isActive) { response.close(); return }
                val bodyStr = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                val outcome = runCatching {
                    check(response.isSuccessful) { humanizeError(response.code, bodyStr) }
                    if (bodyStr.isBlank()) throw IllegalStateException("服务返回空响应")
                    bodyStr
                }
                if (cont.isActive) outcome.fold(cont::resume, cont::resumeWithException)
            }
        })
    }

    /** 把常见 HTTP 码翻成用户能行动的提示（参考 yanwai 的错误分诊，文案自写）。 */
    private fun humanizeError(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty().take(120)
        }.getOrDefault("")
        val reason = when (code) {
            401 -> "API Key 无效，请检查 Key 与渠道是否对应"
            402 -> "账户额度不足，请充值或更换模型"
            403 -> "账户或模型未授权，请到渠道控制台检查"
            404, 422 -> "接口地址或模型不受支持，请检查配置"
            429 -> "请求过于频繁或额度受限，请稍后重试"
            in 500..599 -> "模型服务暂时不可用（$code）"
            else -> "请求失败（HTTP $code）"
        }
        WeLogger.w(TAG, "http $code: $detail")
        return if (detail.isNotBlank()) "$reason：$detail" else reason
    }
}
