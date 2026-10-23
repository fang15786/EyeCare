package com.eyecare.app

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.AppOpsManager
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
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * EyeCare 20-20-20 主活动入口
 * 提供大字号使用时间倒计时展示、悬浮窗权限申请、服务启动/停止控制、运行周期时间设置以及音视频暂停联动配置
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_OVERLAY_PERMISSION = 1001
        private const val PREFS_NAME = EyeCareService.PREFS_NAME
        private const val KEY_WORK_CYCLE_SECONDS = EyeCareService.KEY_WORK_CYCLE_SECONDS
        private const val KEY_PAUSE_MEDIA = EyeCareService.KEY_PAUSE_MEDIA
        private const val KEY_SERVICE_ENABLED = EyeCareService.KEY_SERVICE_ENABLED
        private const val KEY_ANTI_KILL = EyeCareService.KEY_ANTI_KILL
    }

    // 倒计时核心看板控件
    private lateinit var tvUsageCountdown: TextView
    private lateinit var tvCountdownTip: TextView

    // 权限动态警示看板控件
    private lateinit var layoutPermissionCard: LinearLayout
    private lateinit var tvPermissionTitle: TextView
    private lateinit var tvPermissionDesc: TextView
    private lateinit var btnOpenSpecialPermissions: Button

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

    // 防快速清理 / 防误杀模式开关
    private lateinit var switchAntiKill: Switch

    private var isServiceRunning = false
    private var selectedCycleSeconds = EyeCareService.DEFAULT_WORK_CYCLE_SECONDS
    private var isPauseMediaEnabled = true
    private var isAntiKillEnabled = true

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
        applyAntiKillMode(isAntiKillEnabled)
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
        isAntiKillEnabled = prefs.getBoolean(KEY_ANTI_KILL, true)
    }

    /**
     * 将当前设置持久化保存到本地
     */
    private fun savePreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_WORK_CYCLE_SECONDS, selectedCycleSeconds)
            .putBoolean(KEY_PAUSE_MEDIA, isPauseMediaEnabled)
            .putBoolean(KEY_ANTI_KILL, isAntiKillEnabled)
            .apply()
    }

    /**
     * 初始化看板、权限卡片与开关控件
     */
    private fun initViews() {
        tvUsageCountdown = findViewById(R.id.tvUsageCountdown)
        tvCountdownTip = findViewById(R.id.tvCountdownTip)

        // 绑定权限看板卡片视图
        layoutPermissionCard = findViewById(R.id.layoutPermissionCard)
        tvPermissionTitle = findViewById(R.id.tvPermissionTitle)
        tvPermissionDesc = findViewById(R.id.tvPermissionDesc)
        btnOpenSpecialPermissions = findViewById(R.id.btnOpenSpecialPermissions)

        btnOpenSpecialPermissions.setOnClickListener {
            openSpecialPermissionSettings()
        }

        // 音视频暂停联动开关
        switchPauseMedia = findViewById(R.id.switchPauseMedia)
        switchPauseMedia.isChecked = isPauseMediaEnabled
        switchPauseMedia.setOnCheckedChangeListener { _, isChecked ->
            isPauseMediaEnabled = isChecked
            savePreferences()
            syncConfigToService()
            val tip = if (isChecked) "已开启：弹出遮罩时自动暂停抖音等音视频" else "已关闭：弹出遮罩时不打断音视频"
            Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
            updateUI()
        }

        // 防快速清理 / 防误杀模式开关
        switchAntiKill = findViewById(R.id.switchAntiKill)
        switchAntiKill.isChecked = isAntiKillEnabled
        switchAntiKill.setOnCheckedChangeListener { _, isChecked ->
            isAntiKillEnabled = isChecked
            savePreferences()
            applyAntiKillMode(isChecked)
            val tip = if (isChecked) {
                "已开启防误杀：已从多任务列表中隐藏，快速清后台绝不误杀！(随时通过通知栏或桌面进入)"
            } else {
                "已关闭防误杀：将在多任务列表中正常显示卡片"
            }
            Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
        }

        initPresetTimeButtons()
        initServiceActionButtons()
    }

    /**
     * 初始化运行时间预设按钮与自定义输入
     */
    private fun initPresetTimeButtons() {
        btnCycle10s = findViewById(R.id.btnCycle10s)
        btnCycle10m = findViewById(R.id.btnCycle10m)
        btnCycle15m = findViewById(R.id.btnCycle15m)
        btnCycle20m = findViewById(R.id.btnCycle20m)
        btnCycle30m = findViewById(R.id.btnCycle30m)
        btnCycle45m = findViewById(R.id.btnCycle45m)

        btnCycle10s.setOnClickListener { selectCycle(10, "10秒快速测试") }
        btnCycle10m.setOnClickListener { selectCycle(10 * 60, "10分钟") }
        btnCycle15m.setOnClickListener { selectCycle(15 * 60, "15分钟") }
        btnCycle20m.setOnClickListener { selectCycle(20 * 60, "20分钟(标准推荐)") }
        btnCycle30m.setOnClickListener { selectCycle(30 * 60, "30分钟") }
        btnCycle45m.setOnClickListener { selectCycle(45 * 60, "45分钟") }

        etCustomMinutes = findViewById(R.id.etCustomMinutes)
        btnApplyCustomCycle = findViewById(R.id.btnApplyCustomCycle)
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
    }

    /**
     * 初始化主控启停与测试操作按钮
     */
    private fun initServiceActionButtons() {
        btnToggleService = findViewById(R.id.btnToggleService)
        btnTriggerTest = findViewById(R.id.btnTriggerTest)

        btnToggleService.setOnClickListener {
            if (!hasOverlayPermission()) {
                requestOverlayPermission()
                return@setOnClickListener
            }

            if (isServiceRunning) {
                stopEyeCareService()
            } else {
                if (!hasBackgroundPopupPermission()) {
                    AlertDialog.Builder(this)
                        .setTitle("⚠️ 关键权限未开启提示")
                        .setMessage("检测到系统尚未允许【后台弹出界面】权限！\n\n小米/澎湃OS/vivo等机型在未开启此权限时，切出后台玩游戏时间到了会被系统强制拦截遮罩，只有切回本软件才会弹窗。\n\n强烈建议现在前往开启！")
                        .setPositiveButton("立即前往开启") { _, _ ->
                            openSpecialPermissionSettings()
                        }
                        .setNegativeButton("仍要直接启动") { _, _ ->
                            startEyeCareService()
                        }
                        .show()
                } else {
                    startEyeCareService()
                }
            }
        }

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
     * 动态应用防误杀保护模式（从多任务列表中隐藏或显示卡片）
     * 核心机制：
     * 1. 调用 ActivityManager.AppTask.setExcludeFromRecents(enabled)，在多任务列表中动态隐藏卡片；
     * 2. 当用户在多任务界面快速划掉应用或点击“一键清理”时，列表中不存在 EyeCare 卡片，彻底免疫被误杀；
     * 3. 用户可通过桌面图标或常驻通知随时秒开本界面。
     */
    private fun applyAntiKillMode(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.appTasks?.forEach { task ->
                    task.setExcludeFromRecents(enabled)
                }
                Log.d(TAG, "已动态应用 setExcludeFromRecents: $enabled")
            } catch (e: Exception) {
                Log.w(TAG, "设置 setExcludeFromRecents 异常: ${e.message}")
            }
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
        // 记录用户主动开启护眼守护状态，供 BootAndUnlockReceiver 解锁自启识别
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SERVICE_ENABLED, true)
            .apply()

        updateCountdownUI()
        Toast.makeText(this, "护眼服务已启动", Toast.LENGTH_SHORT).show()

        // 检查并引导加入电池优化白名单，防止后台休眠与冻结
        if (!isIgnoringBatteryOptimizations()) {
            requestIgnoreBatteryOptimizations()
        }
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
        // 记录用户主动停止状态，防止解锁屏幕时自动唤醒拉活
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SERVICE_ENABLED, false)
            .apply()

        updateCountdownUI()
        Toast.makeText(this, "护眼服务已停止", Toast.LENGTH_SHORT).show()
    }

    /**
     * 检查是否已加入系统电池优化白名单（无限制后台运行）
     */
    private fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            powerManager?.isIgnoringBatteryOptimizations(packageName) ?: true
        } else {
            true
        }
    }

    /**
     * 引导申请忽略电池优化权限
     */
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimizations()) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (ignored: Exception) {}
            }
        }
    }

    /**
     * 检查是否具备全屏悬浮窗权限 (标准 SYSTEM_ALERT_WINDOW)
     */
    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    /**
     * 深度检测当前设备是否具备【后台弹出界面】权限（针对小米 MIUI/澎湃 OS、vivo、OPPO 等）
     * OP_BACKGROUND_START_ACTIVITY 对应 code 为 10021
     * 这是导致“切出玩别的即使时间到了也不弹窗，切回应用才立刻弹窗”的绝对核心元凶
     */
    private fun hasBackgroundPopupPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return true
        return try {
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            // 10021 是小米/澎湃OS定义的 OP_BACKGROUND_START_ACTIVITY (后台弹出界面)
            val result = method.invoke(appOps, 10021, android.os.Process.myUid(), packageName) as Int
            result == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            // 非小米或不支持此 API 的设备默认放行
            true
        }
    }

    /**
     * 动态更新权限提示卡片视觉状态
     * 实时检测悬浮窗权限与后台弹出界面权限，向用户明确呈现当前状态
     */
    private fun updatePermissionCardVisual() {
        val hasOverlay = hasOverlayPermission()
        val hasBgPopup = hasBackgroundPopupPermission()

        when {
            !hasOverlay -> {
                // 缺少基础悬浮窗权限
                layoutPermissionCard.setBackgroundColor(Color.parseColor("#381919"))
                tvPermissionTitle.text = "🔴 关键权限未开启：悬浮窗权限"
                tvPermissionTitle.setTextColor(Color.parseColor("#FF5555"))
                tvPermissionDesc.text = "展示全屏半透明护眼遮罩必须先开启【显示在其他应用上层】权限。"
                btnOpenSpecialPermissions.text = "👉 立即开启【悬浮窗】权限"
                btnOpenSpecialPermissions.setBackgroundColor(Color.parseColor("#9C2727"))
            }
            !hasBgPopup -> {
                // 具备悬浮窗但缺少后台弹出界面权限（小米/澎湃OS等核心元凶）
                layoutPermissionCard.setBackgroundColor(Color.parseColor("#331E12"))
                tvPermissionTitle.text = "⚠️ 致命拦截警告：【后台弹出界面】被禁用"
                tvPermissionTitle.setTextColor(Color.parseColor("#FFA500"))
                tvPermissionDesc.text = "当前机型默认禁止后台弹窗！切出后台玩游戏时间到了会被系统强制拦截，只有点进本软件才会弹窗。请立即开启！"
                btnOpenSpecialPermissions.text = "👉 一键直达开启【后台弹出界面】"
                btnOpenSpecialPermissions.setBackgroundColor(Color.parseColor("#C25E00"))
            }
            else -> {
                // 全部权限就绪
                layoutPermissionCard.setBackgroundColor(Color.parseColor("#0F281E"))
                tvPermissionTitle.text = "✅ 后台弹窗权限已就绪"
                tvPermissionTitle.setTextColor(Color.parseColor("#38EF7D"))
                tvPermissionDesc.text = "【后台弹出界面】与悬浮窗均已允许，切出后台玩游戏或看视频时，时间到了将准时弹出全屏远眺遮罩。"
                btnOpenSpecialPermissions.text = "管理权限与省电策略"
                btnOpenSpecialPermissions.setBackgroundColor(Color.parseColor("#1B4D36"))
            }
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

    /**
     * 智能直达手机系统权限设置页
     * 优先直达小米/红米/澎湃OS专属权限编辑页（miui.intent.action.APP_PERM_EDITOR），
     * 直接展示【后台弹出界面】开关；若失败则降级到通用应用详情页
     */
    private fun openSpecialPermissionSettings() {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val isXiaomi = brand.contains("xiaomi") || brand.contains("redmi") ||
                brand.contains("poco") || manufacturer.contains("xiaomi")

        if (isXiaomi) {
            try {
                // 小米 / 澎湃OS 专属权限编辑页面（包含【后台弹出界面】开关）
                val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                    putExtra("extra_pkgname", packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                Toast.makeText(this, "请在列表中将【后台弹出界面】和【显示悬浮窗】设置为【允许】", Toast.LENGTH_LONG).show()
                return
            } catch (e: Exception) {
                Log.w(TAG, "打开小米专属权限编辑页失败，降级通用页: ${e.message}")
            }
        }

        try {
            // 通用应用详情页（所有安卓设备支持）
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "请进入【权限管理】，开启【后台弹出界面】与【悬浮窗】", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            requestOverlayPermission()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OVERLAY_PERMISSION) {
            updatePermissionCardVisual()
            if (hasOverlayPermission()) {
                Toast.makeText(this, "悬浮窗权限已授予！", Toast.LENGTH_SHORT).show()
                updateUI()
            } else {
                Toast.makeText(this, "未授予悬浮窗权限，无法展示护眼遮罩", Toast.LENGTH_SHORT).show()
            }
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
        btnToggleService.text = if (isServiceRunning) "停止护眼守护" else "开启护眼守护"
    }

    override fun onResume() {
        super.onResume()
        applyAntiKillMode(isAntiKillEnabled)
        updatePresetButtonsVisual()
        updateCountdownUI()
        updatePermissionCardVisual()
        uiUpdateHandler.post(uiUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiUpdateHandler.removeCallbacks(uiUpdateRunnable)
    }
}
