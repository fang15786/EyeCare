package com.eyecare.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * EyeCare 20-20-20 主活动入口
 * 提供大字号使用时间倒计时展示、悬浮窗权限申请、服务启动/停止控制、运行周期时间设置以及音视频暂停联动配置
 */
class MainActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_OVERLAY_PERMISSION = 1001
        private const val PREFS_NAME = EyeCareService.PREFS_NAME
        private const val KEY_WORK_CYCLE_SECONDS = EyeCareService.KEY_WORK_CYCLE_SECONDS
        private const val KEY_PAUSE_MEDIA = EyeCareService.KEY_PAUSE_MEDIA
    }

    // 倒计时核心看板控件
    private lateinit var tvUsageCountdown: TextView
    private lateinit var tvCountdownTip: TextView

    private lateinit var tvStatus: TextView
    private lateinit var btnToggleService: Button
    private lateinit var btnTriggerTest: Button

    // 运行时间预设按钮
    private lateinit var btnCycle10s: Button
    private lateinit var btnCycle10m: Button
    private lateinit var btnCycle15m: Button
    private lateinit var btnCycle20m: Button
    private lateinit var btnCycle30m: Button
    private lateinit var btnCycle45m: Button

    // 自定义运行时间
    private lateinit var etCustomMinutes: EditText
    private lateinit var btnApplyCustomCycle: Button

    // 音视频暂停联动开关
    private lateinit var switchPauseMedia: Switch

    private var isServiceRunning = false
    private var selectedCycleSeconds = EyeCareService.DEFAULT_WORK_CYCLE_SECONDS
    private var isPauseMediaEnabled = true

    // 前台每秒刷新倒计时 Handler
    private val uiUpdateHandler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            updateCountdownUI()
            uiUpdateHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadSavedPreferences()
        initViews()
        updatePresetButtonsVisual()
        updateCountdownUI()
    }

    /**
     * 从本地持久化存储加载用户偏好设置
     */
    private fun loadSavedPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        selectedCycleSeconds = prefs.getInt(KEY_WORK_CYCLE_SECONDS, EyeCareService.DEFAULT_WORK_CYCLE_SECONDS)
        isPauseMediaEnabled = prefs.getBoolean(KEY_PAUSE_MEDIA, true)
    }

    /**
     * 将当前设置持久化保存到本地
     */
    private fun savePreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_WORK_CYCLE_SECONDS, selectedCycleSeconds)
            .putBoolean(KEY_PAUSE_MEDIA, isPauseMediaEnabled)
            .apply()
    }

    private fun initViews() {
        tvUsageCountdown = findViewById(R.id.tvUsageCountdown)
        tvCountdownTip = findViewById(R.id.tvCountdownTip)
        tvStatus = findViewById(R.id.tvStatus)
        btnToggleService = findViewById(R.id.btnToggleService)
        btnTriggerTest = findViewById(R.id.btnTriggerTest)

        btnCycle10s = findViewById(R.id.btnCycle10s)
        btnCycle10m = findViewById(R.id.btnCycle10m)
        btnCycle15m = findViewById(R.id.btnCycle15m)
        btnCycle20m = findViewById(R.id.btnCycle20m)
        btnCycle30m = findViewById(R.id.btnCycle30m)
        btnCycle45m = findViewById(R.id.btnCycle45m)

        etCustomMinutes = findViewById(R.id.etCustomMinutes)
        btnApplyCustomCycle = findViewById(R.id.btnApplyCustomCycle)
        switchPauseMedia = findViewById(R.id.switchPauseMedia)

        // 初始填充设置值
        switchPauseMedia.isChecked = isPauseMediaEnabled

        // 绑定预设时间点击事件
        btnCycle10s.setOnClickListener { selectCycle(10, "10秒快速测试") }
        btnCycle10m.setOnClickListener { selectCycle(10 * 60, "10分钟") }
        btnCycle15m.setOnClickListener { selectCycle(15 * 60, "15分钟") }
        btnCycle20m.setOnClickListener { selectCycle(20 * 60, "20分钟(标准推荐)") }
        btnCycle30m.setOnClickListener { selectCycle(30 * 60, "30分钟") }
        btnCycle45m.setOnClickListener { selectCycle(45 * 60, "45分钟") }

        // 应用自定义分钟数
        btnApplyCustomCycle.setOnClickListener {
            val text = etCustomMinutes.text.toString().trim()
            val minutes = text.toIntOrNull()
            if (minutes != null && minutes > 0) {
                selectCycle(minutes * 60, "${minutes}分钟")
                etCustomMinutes.text?.clear()
            } else {
                Toast.makeText(this, "请输入有效的分钟数 (大于0)", Toast.LENGTH_SHORT).show()
            }
        }

        // 音视频暂停联动开关监听
        switchPauseMedia.setOnCheckedChangeListener { _, isChecked ->
            isPauseMediaEnabled = isChecked
            savePreferences()
            syncConfigToService()
            val tip = if (isChecked) "已开启：弹出遮罩时自动暂停抖音等音视频" else "已关闭：弹出遮罩时不打断音视频"
            Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
            updateUI()
        }

        // 启动 / 停止护眼服务
        btnToggleService.setOnClickListener {
            if (!hasOverlayPermission()) {
                requestOverlayPermission()
                return@setOnClickListener
            }

            if (isServiceRunning) {
                stopEyeCareService()
            } else {
                startEyeCareService()
            }
        }

        // 立即弹出遮罩测试
        btnTriggerTest.setOnClickListener {
            if (!hasOverlayPermission()) {
                requestOverlayPermission()
                return@setOnClickListener
            }
            val intent = Intent(this, EyeCareService::class.java).apply {
                action = EyeCareService.ACTION_TRIGGER_TEST
            }
            startService(intent)
        }
    }

    /**
     * 切换选中的运行周期时长
     * @param seconds 目标周期秒数
     * @param label 展示文案
     */
    private fun selectCycle(seconds: Int, label: String) {
        selectedCycleSeconds = seconds
        savePreferences()
        updatePresetButtonsVisual()
        syncConfigToService()
        updateCountdownUI()
        Toast.makeText(this, "运行时间已设置为: $label", Toast.LENGTH_SHORT).show()
    }

    /**
     * 同步当前最新配置到正在运行的后台服务
     */
    private fun syncConfigToService() {
        if (isServiceRunning) {
            val intent = Intent(this, EyeCareService::class.java).apply {
                action = EyeCareService.ACTION_UPDATE_CONFIG
                putExtra(EyeCareService.EXTRA_WORK_CYCLE_SECONDS, selectedCycleSeconds)
                putExtra(EyeCareService.EXTRA_PAUSE_MEDIA, isPauseMediaEnabled)
            }
            startService(intent)
        }
    }

    /**
     * 更新预设时长按钮的选中高亮样式
     * 选中状态：翠绿背景 + 发光浅白绿边框 + 墨绿高对比度文字 + 加粗 + 动态勾选符 (✓)
     * 未选中状态：深色沉稳卡片 + 细暗边框 + 次级浅灰文字 + 常规字重
     */
    private fun updatePresetButtonsVisual() {
        val presetList = listOf(
            Triple(10, btnCycle10s, "10秒(测试)"),
            Triple(10 * 60, btnCycle10m, "10分钟"),
            Triple(15 * 60, btnCycle15m, "15分钟"),
            Triple(20 * 60, btnCycle20m, "20分(推荐)"),
            Triple(30 * 60, btnCycle30m, "30分钟"),
            Triple(45 * 60, btnCycle45m, "45分钟")
        )

        val activeTextColor = Color.parseColor("#002914") // 极致高对比墨绿色
        val inactiveTextColor = Color.parseColor("#8E9CAE") // 低饱和暗灰蓝色

        val activeBg = getDrawable(R.drawable.bg_btn_preset_active)
        val inactiveBg = getDrawable(R.drawable.bg_btn_preset_inactive)

        presetList.forEach { (seconds, button, originalLabel) ->
            if (seconds == selectedCycleSeconds) {
                button.background = activeBg?.constantState?.newDrawable()?.mutate() ?: activeBg
                button.setTextColor(activeTextColor)
                button.typeface = Typeface.DEFAULT_BOLD
                button.text = "✓ $originalLabel"
            } else {
                button.background = inactiveBg?.constantState?.newDrawable()?.mutate() ?: inactiveBg
                button.setTextColor(inactiveTextColor)
                button.typeface = Typeface.DEFAULT
                button.text = originalLabel
            }
        }
    }

    /**
     * 启动护眼守护服务
     */
    private fun startEyeCareService() {
        val intent = Intent(this, EyeCareService::class.java).apply {
            action = EyeCareService.ACTION_START
            putExtra(EyeCareService.EXTRA_WORK_CYCLE_SECONDS, selectedCycleSeconds)
            putExtra(EyeCareService.EXTRA_PAUSE_MEDIA, isPauseMediaEnabled)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        isServiceRunning = true
        updateCountdownUI()
        Toast.makeText(this, "护眼服务已启动", Toast.LENGTH_SHORT).show()
    }

    /**
     * 停止护眼守护服务
     */
    private fun stopEyeCareService() {
        val intent = Intent(this, EyeCareService::class.java).apply {
            action = EyeCareService.ACTION_STOP
        }
        stopService(intent)

        isServiceRunning = false
        updateCountdownUI()
        Toast.makeText(this, "护眼服务已停止", Toast.LENGTH_SHORT).show()
    }

    /**
     * 检查是否具备全屏悬浮窗权限
     */
    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    /**
     * 引导跳转系统设置开启悬浮窗权限
     */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Toast.makeText(this, "需要授予【显示在其他应用上层】权限以展示护眼遮罩", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OVERLAY_PERMISSION) {
            if (hasOverlayPermission()) {
                Toast.makeText(this, "悬浮窗权限已授予！", Toast.LENGTH_SHORT).show()
                updateUI()
            } else {
                Toast.makeText(this, "未授予悬浮窗权限，无法展示护眼遮罩", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatCycleText(seconds: Int): String {
        return if (seconds < 60) {
            "${seconds} 秒 (测试)"
        } else {
            val mins = seconds / 60
            "${mins} 分钟"
        }
    }

    /**
     * 实时刷新倒计时看板展示
     */
    private fun updateCountdownUI() {
        val serviceRunning = EyeCareService.isRunning
        isServiceRunning = serviceRunning
        if (serviceRunning) {
            val remaining = EyeCareService.currentRemainingSeconds
            tvUsageCountdown.text = formatCountdownTimer(remaining)
            tvUsageCountdown.setTextColor(Color.parseColor("#38EF7D"))
            tvCountdownTip.text = "护眼守护运行中 · 仅在亮屏使用时倒计"
        } else {
            tvUsageCountdown.text = formatCountdownTimer(selectedCycleSeconds)
            tvUsageCountdown.setTextColor(Color.parseColor("#6A7D94"))
            tvCountdownTip.text = "服务未开启 · 点击下方按钮开启护眼守护"
        }
        updateUI()
    }

    /**
     * 格式化 mm:ss 倒计时时间字符串
     */
    private fun formatCountdownTimer(totalSeconds: Int): String {
        val mins = totalSeconds / 60
        val secs = totalSeconds % 60
        return String.format("%02d:%02d", mins, secs)
    }

    private fun updateUI() {
        val permGranted = hasOverlayPermission()
        val cycleText = formatCycleText(selectedCycleSeconds)

        tvStatus.text = buildString {
            append("服务运行状态: ").append(if (isServiceRunning) "运行中\n" else "已停止\n")
            append("当前设置周期: ").append(cycleText).append("\n")
            append("音视频联动: ").append(if (isPauseMediaEnabled) "遮罩弹出自动暂停，关闭恢复\n" else "不打断播放\n")
            append("悬浮窗权限: ").append(if (permGranted) "已授权\n" else "未授权 (启动将引导授权)\n")
            append("提醒模式: 纯视觉静音 (无声音、无震动)")
        }

        btnToggleService.text = if (isServiceRunning) "停止护眼守护" else "开启护眼守护"
    }

    override fun onResume() {
        super.onResume()
        updatePresetButtonsVisual()
        updateCountdownUI()
        uiUpdateHandler.post(uiUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiUpdateHandler.removeCallbacks(uiUpdateRunnable)
    }
}
