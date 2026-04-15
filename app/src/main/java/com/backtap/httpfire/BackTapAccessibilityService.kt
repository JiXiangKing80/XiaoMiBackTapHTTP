package com.backtap.httpfire

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import android.content.pm.ServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 核心无障碍服务
 *
 * 工作原理：
 * 1. 背部轻敲 → MIUI 系统切换静音模式 → 触发 RINGER_MODE_CHANGED 广播
 * 2. 本服务收到广播后：
 *    a) 立即反转静音状态（用户无感知）
 *    b) 发送用户配置的 HTTP 请求
 * 3. 通过 SharedPreferences 的 "monitoring" 开关控制是否拦截
 *    - App 内的 Switch 和通知栏按钮都可控制
 *    - 关闭后静音操作恢复正常，不会触发 HTTP
 */
class BackTapAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BackTapService"

        // 冷却时间（毫秒），防止短时间内重复触发
        private const val COOLDOWN_MS = 3000L

        // 通知相关常量
        private const val CHANNEL_ID = "backtap_status"
        private const val NOTIFICATION_ID = 1

        // 通知栏切换按钮的广播 Action
        const val ACTION_TOGGLE = "com.backtap.httpfire.ACTION_TOGGLE"

        // SharedPreferences 中存储监听开关的 Key
        private const val PREFS_NAME = "backtap_config"
        private const val KEY_MONITORING = "monitoring"

        /** 服务实例是否存活（内存中） */
        var isRunning = false
            private set

        /** App 内手动切换静音时置 true，服务会跳过一次拦截 */
        @Volatile
        var skipNextRingerChange = false

        /**
         * 检查无障碍服务是否已启用
         * 优先使用 AccessibilityManager API（兼容 MIUI/HyperOS），
         * 再用 isRunning 内存标记作为兜底
         */
        fun isServiceEnabled(context: Context): Boolean {
            // 方式1：内存标记（同进程内最准确）
            if (isRunning) return true

            // 方式2：通过 AccessibilityManager 查询已启用的服务列表
            try {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                        as android.view.accessibility.AccessibilityManager
                val enabledServices = am.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                )
                val found = enabledServices.any {
                    it.resolveInfo.serviceInfo.packageName == context.packageName &&
                    it.resolveInfo.serviceInfo.name == BackTapAccessibilityService::class.java.name
                }
                if (found) return true
            } catch (_: Exception) { }

            // 方式3：兜底读 Settings.Secure（部分系统 API 可能受限）
            try {
                val raw = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val myComponent = "${context.packageName}/${BackTapAccessibilityService::class.java.name}"
                return raw.contains(myComponent)
            } catch (_: Exception) { }

            return false
        }

        /** 从 SharedPreferences 读取监听开关状态 */
        fun isMonitoring(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_MONITORING, true)
        }

        /** 写入监听开关状态到 SharedPreferences */
        fun setMonitoring(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_MONITORING, enabled).apply()
        }
    }

    // 上次触发时间戳，用于冷却判断
    private var lastTriggerTime = 0L

    // 标记：当前的静音变化是否由本服务反转操作引起（避免死循环）
    private var isSelfReverting = false

    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager

    // 动态注册的广播接收器
    private var ringerReceiver: BroadcastReceiver? = null
    private var toggleReceiver: BroadcastReceiver? = null

    // 监听 SharedPreferences 变化，及时更新通知
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // ═══════════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════════

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        registerRingerModeReceiver()   // 监听静音变化
        registerToggleReceiver()       // 监听通知栏切换按钮
        registerPrefsListener()        // 监听 SharedPreferences 变化

        // ★ 关键：以前台服务模式运行，防止 MIUI/HyperOS 杀后台
        goForeground()

        Log.i(TAG, "服务已启动（前台模式），开始监听静音模式变化")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 本服务仅使用静音模式广播作为触发机制，不处理无障碍事件
    }

    override fun onInterrupt() {
        Log.w(TAG, "服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        unregisterRingerReceiver()
        unregisterToggleReceiver()
        unregisterPrefsListener()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "服务已销毁")
    }

    // ═══════════════════════════════════════════════════════════
    // 通知栏（低优先级，不会打扰用户）
    // ═══════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "BackTap 状态", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示监听状态和快捷开关"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /** 构建状态通知 */
    private fun buildNotification(): android.app.Notification {
        val monitoring = isMonitoring(this)

        val toggleIntent = Intent(ACTION_TOGGLE).setPackage(packageName)
        val togglePending = PendingIntent.getBroadcast(
            this, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (monitoring) "监听中 — 轻敲将发送请求" else "已暂停 — 静音不被拦截"
        val toggleText = if (monitoring) "暂停" else "恢复"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("BackTapHTTP")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, toggleText, togglePending)
            .build()
    }

    /** 以前台服务模式启动，防止被系统杀掉 */
    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ 需要指定 foregroundServiceType
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** 更新通知内容（开关状态变化时调用） */
    fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    // ═══════════════════════════════════════════════════════════
    // 通知栏按钮点击接收器
    // ═══════════════════════════════════════════════════════════

    private fun registerToggleReceiver() {
        toggleReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_TOGGLE) return
                // 取反当前状态并写入 SharedPreferences
                val current = isMonitoring(context)
                setMonitoring(context, !current)
                Log.i(TAG, "通知栏切换监听: ${!current}")
                // 通知会由 prefsListener 自动更新
            }
        }
        val filter = IntentFilter(ACTION_TOGGLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(toggleReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(toggleReceiver, filter)
        }
    }

    private fun unregisterToggleReceiver() {
        toggleReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        toggleReceiver = null
    }

    // ═══════════════════════════════════════════════════════════
    // SharedPreferences 变化监听（App 内切换开关时同步通知栏）
    // ═══════════════════════════════════════════════════════════

    private fun registerPrefsListener() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_MONITORING) {
                updateNotification()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun unregisterPrefsListener() {
        prefsListener?.let {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(it)
        }
        prefsListener = null
    }

    // ═══════════════════════════════════════════════════════════
    // 核心：静音模式变化监听
    // ═══════════════════════════════════════════════════════════

    private fun registerRingerModeReceiver() {
        ringerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != AudioManager.RINGER_MODE_CHANGED_ACTION) return

                // ① 监听已暂停 → 不拦截，让静音正常生效
                if (!isMonitoring(context)) {
                    Log.d(TAG, "监听已暂停，忽略静音变化")
                    return
                }

                // ② 是本服务自己反转引起的 → 忽略，避免死循环
                if (isSelfReverting) {
                    isSelfReverting = false
                    Log.d(TAG, "忽略自身反转触发的静音变化")
                    return
                }

                // ②b App 内手动切换静音 → 跳过，不触发 HTTP
                if (skipNextRingerChange) {
                    skipNextRingerChange = false
                    Log.d(TAG, "App 内手动切换静音，跳过拦截")
                    return
                }

                // ③ 冷却期内 → 忽略，防止短时间重复触发
                val now = System.currentTimeMillis()
                if (now - lastTriggerTime < COOLDOWN_MS) {
                    Log.d(TAG, "冷却期内，忽略")
                    return
                }

                // ④ 确认是背部轻敲触发 → 反转静音 + 发送 HTTP
                lastTriggerTime = now
                Log.i(TAG, "检测到静音切换（背部轻敲），反转并发送 HTTP 请求")

                reverseRingerMode()
                HttpSender.sendRequest(context)
            }
        }

        val filter = IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 必须用 RECEIVER_EXPORTED 才能接收系统广播（MIUI/HyperOS 兼容）
            registerReceiver(ringerReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(ringerReceiver, filter)
        }
    }

    /**
     * 反转静音模式
     * 系统刚切成静音 → 我们切回正常；系统刚切回正常 → 我们切回静音
     * 这样用户感知不到任何音量变化
     */
    private fun reverseRingerMode() {
        isSelfReverting = true  // 标记下一次变化是自己触发的
        val currentMode = audioManager.ringerMode
        val targetMode = when (currentMode) {
            AudioManager.RINGER_MODE_SILENT -> AudioManager.RINGER_MODE_NORMAL
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_NORMAL
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        try {
            audioManager.ringerMode = targetMode
            Log.i(TAG, "静音模式已反转: $currentMode → $targetMode")
        } catch (e: Exception) {
            Log.e(TAG, "反转静音失败: ${e.message}")
            isSelfReverting = false
        }
    }

    private fun unregisterRingerReceiver() {
        ringerReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        ringerReceiver = null
    }
}
