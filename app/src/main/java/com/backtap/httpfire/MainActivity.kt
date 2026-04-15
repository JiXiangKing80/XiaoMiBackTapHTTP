package com.backtap.httpfire

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 主界面
 * 提供服务状态查看、监听开关、HTTP 请求配置和测试功能
 */
class MainActivity : AppCompatActivity() {

    // ── UI 控件 ──────────────────────────────────────────────
    private lateinit var tvServiceStatus: TextView
    private lateinit var switchMonitor: MaterialSwitch
    private lateinit var layoutStatusCard: View
    private lateinit var viewStatusDot: View
    private lateinit var etUrl: TextInputEditText
    private lateinit var actvMethod: AutoCompleteTextView
    private lateinit var actvBodyType: AutoCompleteTextView
    private lateinit var etHeaders: TextInputEditText
    private lateinit var tilBody: TextInputLayout
    private lateinit var etBody: TextInputEditText
    private lateinit var tvLog: TextView
    private lateinit var tvLogCount: TextView
    private lateinit var btnToggleMute: MaterialButton

    // 记录上次日志条数，避免无变化时重复刷新
    private var lastLogCount = -1

    private val handler = Handler(Looper.getMainLooper())

    // 每 2 秒刷新服务状态、日志和静音按钮
    private val refresher = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshLogDisplay()
            refreshMuteButton()
            handler.postDelayed(this, 2000)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupDropdowns()
        setupListeners()
        loadConfig()
        refreshStatus()
        refreshLogDisplay()
        refreshMuteButton()
    }

    override fun onResume() {
        super.onResume()
        // 强制立即刷新（可能有后台触发的新日志）
        lastLogCount = -1
        handler.post(refresher)
        switchMonitor.isChecked = BackTapAccessibilityService.isMonitoring(this)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    // ═══════════════════════════════════════════════════════════
    // 初始化
    // ═══════════════════════════════════════════════════════════

    private fun initViews() {
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        switchMonitor = findViewById(R.id.switchMonitor)
        layoutStatusCard = findViewById(R.id.layoutStatusCard)
        viewStatusDot = findViewById(R.id.viewStatusDot)
        etUrl = findViewById(R.id.etUrl)
        actvMethod = findViewById(R.id.actvMethod)
        actvBodyType = findViewById(R.id.actvBodyType)
        etHeaders = findViewById(R.id.etHeaders)
        tilBody = findViewById(R.id.tilBody)
        etBody = findViewById(R.id.etBody)
        tvLog = findViewById(R.id.tvLog)
        tvLogCount = findViewById(R.id.tvLogCount)
        btnToggleMute = findViewById(R.id.btnToggleMute)
    }

    private fun setupDropdowns() {
        val methodAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, HttpSender.METHODS)
        actvMethod.setAdapter(methodAdapter)

        val bodyTypeAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, HttpSender.BODY_TYPES)
        actvBodyType.setAdapter(bodyTypeAdapter)

        actvBodyType.setOnItemClickListener { _, _, position, _ ->
            updateBodyHint(HttpSender.BODY_TYPES[position])
        }

        actvMethod.setOnItemClickListener { _, _, position, _ ->
            val method = HttpSender.METHODS[position]
            tilBody.visibility = if (method in listOf("POST", "PUT", "PATCH")) View.VISIBLE else View.GONE
        }
    }

    private fun setupListeners() {
        // 开启无障碍服务
        findViewById<MaterialButton>(R.id.btnEnableService).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "请找到「BackTapHTTP」并开启", Toast.LENGTH_LONG).show()
        }

        // MIUI 保活设置（自启动 + 电池优化）
        findViewById<MaterialButton>(R.id.btnMiuiKeepAlive).setOnClickListener {
            openMiuiKeepAliveSettings()
        }

        // 手动切换静音（不触发 HTTP）
        findViewById<MaterialButton>(R.id.btnToggleMute).setOnClickListener {
            toggleMuteManually()
        }

        // 监听开关
        switchMonitor.setOnCheckedChangeListener { _, isChecked ->
            BackTapAccessibilityService.setMonitoring(this, isChecked)
        }

        // 保存配置
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            saveConfig()
        }

        // 测试发送
        findViewById<MaterialButton>(R.id.btnTest).setOnClickListener {
            testSendRequest()
        }

        // 清除日志
        findViewById<MaterialButton>(R.id.btnClearLog).setOnClickListener {
            clearLog()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 配置读写
    // ═══════════════════════════════════════════════════════════

    private fun loadConfig() {
        etUrl.setText(HttpSender.getUrl(this))
        actvMethod.setText(HttpSender.getMethod(this), false)
        actvBodyType.setText(HttpSender.getBodyType(this), false)
        etHeaders.setText(HttpSender.getHeaders(this))
        etBody.setText(HttpSender.getBody(this))

        val method = HttpSender.getMethod(this)
        tilBody.visibility = if (method in listOf("POST", "PUT", "PATCH")) View.VISIBLE else View.GONE
        updateBodyHint(HttpSender.getBodyType(this))
    }

    private fun saveConfig() {
        HttpSender.saveConfig(
            context = this,
            url = etUrl.text.toString().trim(),
            method = actvMethod.text.toString(),
            bodyType = actvBodyType.text.toString(),
            headers = etHeaders.text.toString().trim(),
            body = etBody.text.toString().trim()
        )
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
    }

    // ═══════════════════════════════════════════════════════════
    // 测试请求
    // ═══════════════════════════════════════════════════════════

    private fun testSendRequest() {
        val url = etUrl.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(this, "请先填写 URL", Toast.LENGTH_SHORT).show()
            return
        }

        saveConfig()

        // HttpSender 内部会自动写入 LogStore，回调中仅刷新显示
        HttpSender.sendRequest(this) { _, _ ->
            handler.post {
                lastLogCount = -1  // 强制刷新
                refreshLogDisplay()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 日志管理（从 LogStore 持久化存储读取）
    // ═══════════════════════════════════════════════════════════

    /** 从 SharedPreferences 读取日志并刷新显示 */
    private fun refreshLogDisplay() {
        val logs = LogStore.getLogLines(this)
        val count = logs.size

        // 无变化时跳过，避免不必要的重绘
        if (count == lastLogCount) return
        lastLogCount = count

        tvLogCount.text = if (count > 0) "${count}条" else ""
        tvLog.text = if (count == 0) "暂无请求记录" else logs.joinToString("\n")
    }

    /** 清空所有日志 */
    private fun clearLog() {
        LogStore.clear(this)
        lastLogCount = -1
        refreshLogDisplay()
    }

    // ═══════════════════════════════════════════════════════════
    // 状态刷新
    // ═══════════════════════════════════════════════════════════

    private fun refreshStatus() {
        val enabled = BackTapAccessibilityService.isServiceEnabled(this)
        val monitoring = BackTapAccessibilityService.isMonitoring(this)

        when {
            !enabled -> {
                tvServiceStatus.text = "无障碍服务未开启"
                viewStatusDot.setBackgroundResource(R.drawable.dot_off)
                layoutStatusCard.setBackgroundResource(R.drawable.bg_status_off)
            }
            monitoring -> {
                tvServiceStatus.text = "正在监听背部轻敲"
                viewStatusDot.setBackgroundResource(R.drawable.dot_active)
                layoutStatusCard.setBackgroundResource(R.drawable.bg_status_active)
            }
            else -> {
                tvServiceStatus.text = "监听已暂停"
                viewStatusDot.setBackgroundResource(R.drawable.dot_paused)
                layoutStatusCard.setBackgroundResource(R.drawable.bg_status_paused)
            }
        }

        if (switchMonitor.isChecked != monitoring) {
            switchMonitor.isChecked = monitoring
        }
    }

    // ═══════════════════════════════════════════════════════════
    // MIUI/HyperOS 保活设置
    // ═══════════════════════════════════════════════════════════

    /**
     * 尝试打开小米自启动管理或电池优化页面
     * MIUI/HyperOS 会在关闭 App 后杀死无障碍服务，
     * 必须开启「自启动」并关闭「电池优化」才能保持服务常驻
     */
    private fun openMiuiKeepAliveSettings() {
        // 先尝试打开 MIUI 自启动管理
        val miuiIntents = listOf(
            // MIUI 自启动管理
            Intent().setComponent(ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )),
            // HyperOS 自启动管理
            Intent().setComponent(ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity"
            )),
            // 通用：App 详情页
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        )

        for (intent in miuiIntents) {
            try {
                startActivity(intent)
                Toast.makeText(this,
                    "请开启「自启动」并将电池策略设为「无限制」",
                    Toast.LENGTH_LONG
                ).show()
                return
            } catch (_: Exception) {
                // 当前 Intent 不可用，尝试下一个
            }
        }

        // 兜底：请求忽略电池优化
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            }
        } catch (_: Exception) {
            Toast.makeText(this, "请手动在设置中开启自启动权限", Toast.LENGTH_LONG).show()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 手动静音切换（不触发 HTTP）
    // ═══════════════════════════════════════════════════════════

    private fun toggleMuteManually() {
        // Android 7+ 需要勿扰权限才能切换静音
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) {
                Toast.makeText(this, "需要「勿扰」权限才能切换静音，请授权", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                return
            }
        }

        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = audio.ringerMode

        // 告诉服务跳过下一次静音变化的拦截
        BackTapAccessibilityService.skipNextRingerChange = true

        try {
            if (current == AudioManager.RINGER_MODE_NORMAL) {
                audio.ringerMode = AudioManager.RINGER_MODE_SILENT
                Toast.makeText(this, "已静音", Toast.LENGTH_SHORT).show()
            } else {
                audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
                Toast.makeText(this, "已取消静音", Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            BackTapAccessibilityService.skipNextRingerChange = false
            Toast.makeText(this, "无权限，请授予勿扰权限", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            } catch (_: Exception) { }
        }

        refreshMuteButton()
    }

    /** 根据当前静音状态更新按钮文字 */
    private fun refreshMuteButton() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val isSilent = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
        btnToggleMute.text = if (isSilent)
            "🔈 当前已静音 — 点击取消"
        else
            "🔇 当前响铃 — 点击静音"
    }

    // ═══════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════

    private fun updateBodyHint(bodyType: String) {
        when (bodyType) {
            HttpSender.BODY_JSON -> {
                tilBody.hint = "JSON 内容"
                etBody.hint = "{\"action\": \"back_tap\", \"time\": 123}"
            }
            HttpSender.BODY_FORM -> {
                tilBody.hint = "表单参数 (每行 key=value)"
                etBody.hint = "action=back_tap\ndevice=xiaomi"
            }
            else -> {
                tilBody.hint = "请求体内容"
                etBody.hint = ""
            }
        }
    }
}
