package com.eyecare.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * EyeCare 20-20-20 主活动入口
 * 提供悬浮窗权限申请、服务启动/停止控制以及快速测试模式
 */
class MainActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_OVERLAY_PERMISSION = 1001
    }

    private lateinit var tvStatus: TextView
    private lateinit var btnToggleService: Button
    private lateinit var btnTriggerTest: Button
    private lateinit var btnToggleTestMode: Button

    private var isServiceRunning = false
    private var isTestMode = true // 默认开启 10 秒测试模式便于快速调试

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        updateUI()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        btnToggleService = findViewById(R.id.btnToggleService)
        btnTriggerTest = findViewById(R.id.btnTriggerTest)
        btnToggleTestMode = findViewById(R.id.btnToggleTestMode)

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

        // 快速模式切换：10秒快速测试 vs 20分钟真实标准
        btnToggleTestMode.setOnClickListener {
            isTestMode = !isTestMode
            val modeName = if (isTestMode) "10 秒测试模式" else "20 分钟正式模式"
            Toast.makeText(this, "已切换为: $modeName", Toast.LENGTH_SHORT).show()
            updateUI()

            // 如果服务正在运行，同步更新服务的触发周期
            if (isServiceRunning) {
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
     * 启动护眼守护服务
     */
    private fun startEyeCareService() {
        val cycleSeconds = if (isTestMode) 10 else EyeCareService.DEFAULT_WORK_CYCLE_SECONDS
        val intent = Intent(this, EyeCareService::class.java).apply {
            action = EyeCareService.ACTION_START
            putExtra(EyeCareService.EXTRA_TEST_CYCLE_SECONDS, cycleSeconds)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        isServiceRunning = true
        updateUI()
        Toast.makeText(this, "护眼服务已启动 (纯视觉无震动)", Toast.LENGTH_SHORT).show()
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
        updateUI()
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

    private fun updateUI() {
        val permGranted = hasOverlayPermission()
        val modeText = if (isTestMode) "10 秒 (测试)" else "20 分钟 (标准)"

        tvStatus.text = buildString {
            append("服务运行状态: ").append(if (isServiceRunning) "运行中\n" else "已停止\n")
            append("当前触发周期: ").append(modeText).append("\n")
            append("悬浮窗权限: ").append(if (permGranted) "已授权\n" else "未授权 (点击启动将引导授权)\n")
            append("提醒模式: 纯视觉静音 (无声音、无震动)")
        }

        btnToggleService.text = if (isServiceRunning) "停止护眼守护" else "开启护眼守护"
        btnToggleTestMode.text = if (isTestMode) "切换为 20 分钟正式模式" else "切换为 10 秒测试模式"
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }
}
