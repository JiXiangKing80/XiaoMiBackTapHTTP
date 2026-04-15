package com.backtap.httpfire

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * HTTP 请求发送器
 * 负责配置的持久化存储和实际的网络请求发送
 */
object HttpSender {

    private const val TAG = "HttpSender"
    private const val PREFS_NAME = "backtap_config"

    // SharedPreferences 存储的 Key
    private const val KEY_URL = "url"
    private const val KEY_METHOD = "method"
    private const val KEY_BODY_TYPE = "body_type"
    private const val KEY_HEADERS = "headers"
    private const val KEY_BODY = "body"

    /** 请求体类型枚举 */
    const val BODY_NONE = "无"
    const val BODY_JSON = "JSON"
    const val BODY_FORM = "表单 (Form)"
    val BODY_TYPES = arrayOf(BODY_NONE, BODY_JSON, BODY_FORM)

    /** HTTP 方法列表 */
    val METHODS = arrayOf("GET", "POST", "PUT", "DELETE", "PATCH")

    // 复用同一个 OkHttpClient 实例，节省资源
    private val client = OkHttpClient()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── 配置读写 ─────────────────────────────────────────────

    /** 保存所有请求配置到 SharedPreferences */
    fun saveConfig(
        context: Context,
        url: String,
        method: String,
        bodyType: String,
        headers: String,
        body: String
    ) {
        getPrefs(context).edit()
            .putString(KEY_URL, url)
            .putString(KEY_METHOD, method)
            .putString(KEY_BODY_TYPE, bodyType)
            .putString(KEY_HEADERS, headers)
            .putString(KEY_BODY, body)
            .apply()
    }

    fun getUrl(context: Context): String = getPrefs(context).getString(KEY_URL, "") ?: ""
    fun getMethod(context: Context): String = getPrefs(context).getString(KEY_METHOD, "GET") ?: "GET"
    fun getBodyType(context: Context): String = getPrefs(context).getString(KEY_BODY_TYPE, BODY_NONE) ?: BODY_NONE
    fun getHeaders(context: Context): String = getPrefs(context).getString(KEY_HEADERS, "") ?: ""
    fun getBody(context: Context): String = getPrefs(context).getString(KEY_BODY, "") ?: ""

    // ── 发送请求 ─────────────────────────────────────────────

    /**
     * 根据已保存的配置发送 HTTP 请求
     * @param callback 可选的回调，返回 (是否成功, 描述信息)
     */
    fun sendRequest(context: Context, callback: ((Boolean, String) -> Unit)? = null) {
        val url = getUrl(context)
        if (url.isBlank()) {
            Log.w(TAG, "URL 为空，跳过请求")
            callback?.invoke(false, "URL 为空")
            return
        }

        val method = getMethod(context).uppercase()
        val bodyType = getBodyType(context)
        val headersStr = getHeaders(context)
        val bodyStr = getBody(context)

        try {
            val builder = Request.Builder().url(url)

            // 解析自定义请求头（每行格式：Key: Value）
            parseHeaders(headersStr, builder)

            // 根据请求方法和请求体类型构建 RequestBody
            val requestBody = buildRequestBody(method, bodyType, bodyStr, headersStr)
            builder.method(method, requestBody)

            val request = builder.build()
            Log.i(TAG, "发送 $method 请求到 $url (类型=$bodyType)")

            // 记录请求开始时间，用于计算耗时
            val startTime = System.currentTimeMillis()
            // 使用 applicationContext 防止 Context 泄漏
            val appContext = context.applicationContext

            // 异步发送，不阻塞调用线程
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.e(TAG, "请求失败: ${e.message}")
                    val detail = "$method $url\n耗时 ${elapsed}ms\n错误: ${e.message}"
                    // 持久化日志（服务和界面都可读）
                    LogStore.addLog(appContext, false, detail)
                    callback?.invoke(false, detail)
                }

                override fun onResponse(call: Call, response: Response) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val code = response.code
                    val respBody = response.body?.string()?.take(500) ?: ""
                    Log.i(TAG, "响应: $code $respBody")
                    response.close()
                    val success = code in 200..399
                    val detail = "$method $url\nHTTP $code · ${elapsed}ms"
                    // 持久化日志（服务和界面都可读）
                    LogStore.addLog(appContext, success, detail)
                    callback?.invoke(success, detail)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "构建请求出错: ${e.message}")
            callback?.invoke(false, "出错: ${e.message}")
        }
    }

    // ── 内部工具方法 ──────────────────────────────────────────

    /**
     * 解析请求头字符串，每行格式为 "Key: Value"
     * 自动跳过格式不正确的行
     */
    private fun parseHeaders(headersStr: String, builder: Request.Builder) {
        if (headersStr.isBlank()) return
        headersStr.lines().forEach { line ->
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                if (key.isNotEmpty()) builder.addHeader(key, value)
            }
        }
    }

    /**
     * 根据方法和类型构建请求体
     * - GET/DELETE: 无请求体
     * - JSON: Content-Type = application/json
     * - 表单: Content-Type = application/x-www-form-urlencoded，
     *         内容每行 "key=value" 或 "key: value"
     * - 无: 发送空体（某些 POST 接口只需要 URL 和 Header）
     */
    private fun buildRequestBody(
        method: String,
        bodyType: String,
        bodyStr: String,
        headersStr: String
    ): RequestBody? {
        // GET 和 DELETE 通常不带请求体
        if (method in listOf("GET", "DELETE")) return null

        return when (bodyType) {
            BODY_JSON -> {
                // JSON 格式：直接将内容作为 JSON 发送
                bodyStr.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            }
            BODY_FORM -> {
                // 表单格式：解析每行为 key=value 键值对
                val formBuilder = FormBody.Builder()
                bodyStr.lines().forEach { line ->
                    // 支持 "key=value" 和 "key: value" 两种写法
                    val sepIndex = line.indexOf('=').takeIf { it > 0 }
                        ?: line.indexOf(':').takeIf { it > 0 }
                    if (sepIndex != null && sepIndex > 0) {
                        val key = line.substring(0, sepIndex).trim()
                        val value = line.substring(sepIndex + 1).trim()
                        if (key.isNotEmpty()) formBuilder.add(key, value)
                    }
                }
                formBuilder.build()
            }
            BODY_NONE -> {
                // 空体：某些 POST 接口只需 URL+Header 即可触发
                "".toRequestBody(null)
            }
            else -> {
                // 兜底：按自定义 Content-Type 发送原始内容
                val contentType = headersStr.lines()
                    .firstOrNull { it.trim().startsWith("Content-Type", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()
                    ?: "text/plain"
                bodyStr.toRequestBody(contentType.toMediaTypeOrNull())
            }
        }
    }
}
