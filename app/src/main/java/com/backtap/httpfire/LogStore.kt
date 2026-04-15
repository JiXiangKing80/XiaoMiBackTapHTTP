package com.backtap.httpfire

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 请求日志的持久化存储
 * 使用 SharedPreferences 保存，App 重启后日志不丢失
 * 服务端（BackTapAccessibilityService）和界面端（MainActivity）共用
 */
object LogStore {

    private const val PREFS_NAME = "backtap_logs"
    private const val KEY_LOGS = "log_entries"
    private const val MAX_LOGS = 30
    // 使用不会出现在日志正文中的分隔符
    private const val SEPARATOR = "║"

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * 记录一次请求结果（线程安全）
     * @param context 任意上下文（Service 或 Activity 均可）
     * @param success 请求是否成功
     * @param rawDetail HttpSender 返回的原始详情文本
     */
    @Synchronized
    fun addLog(context: Context, success: Boolean, rawDetail: String) {
        val time = timeFmt.format(Date())
        val icon = if (success) "✓" else "✗"

        // 从 rawDetail 提取一行摘要
        // rawDetail 格式为：第一行 "METHOD URL"，第二行 "HTTP xxx · xxxms" 或 "耗时 xxxms"
        val lines = rawDetail.lines()
        val summary = when {
            lines.size >= 2 -> "${lines[0]} → ${lines[1]}"
            else -> rawDetail.replace("\n", " ").take(80)
        }

        val logLine = "[$time] $icon $summary"

        val all = getLogLines(context).toMutableList()
        all.add(0, logLine)
        // 超过上限自动清理最旧条目
        while (all.size > MAX_LOGS) {
            all.removeAt(all.size - 1)
        }

        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOGS, all.joinToString(SEPARATOR))
            .apply()
    }

    /** 获取所有日志行（最新在前） */
    fun getLogLines(context: Context): List<String> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LOGS, "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split(SEPARATOR)
    }

    /** 清空所有日志 */
    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LOGS)
            .apply()
    }
}
