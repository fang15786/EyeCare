package com.eyecare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 护眼助手核心前台守护服务
 *
 * 核心设计原则：
 * 1. 【纯视觉静默】：坚决无任何外放声音，无任何震动（未申请亦未调用任何 Vibrator 接口）。
 * 2. 【仅计亮屏】：动态监听 SCREEN_ON / SCREEN_OFF，黑屏锁屏期间完全挂起计时，亮屏期间继续累计。
 * 3. 【自动循环】：累计亮屏满 20 分钟弹出全屏半透明遮罩，20 秒倒计时归零后遮罩自动销毁，计时归零静默进入下一轮。
 */
class EyeCareService : Service() {

    companion object {
        private const val TAG = "EyeCareService"
        private const val NOTIFICATION_CHANNEL_ID = "eyecare_silent_channel"
        private const val NOTIFICATION_ID = 202020

        // 标准工作周期：20 分钟 = 1200 秒 (可由 Intent 传入测试模式参数)
        const val DEFAULT_WORK_CYCLE_SECONDS = 20 * 60
        // 远眺倒计时休息时长：20 秒
        const val REST_COUNTDOWN_SECONDS = 20

        // 控制动作 Action
        const val ACTION_START = "com.eyecare.app.ACTION_START"
        const val ACTION_STOP = "com.eyecare.app.ACTION_STOP"
        const val ACTION_TRIGGER_TEST = "com.eyecare.app.ACTION_TRIGGER_TEST"
        const val EXTRA_TEST_CYCLE_SECONDS = "extra_test_cycle_seconds"
    }

    // WindowManager 与悬浮窗视图
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var countDownTimer: CountDownTimer? = null

    // 状态与计时
    private var isScreenOn = true
    private var accumulatedScreenSeconds = 0
    private var currentWorkCycleSeconds = DEFAULT_WORK_CYCLE_SECONDS
    private var isOverlayShowing = false

    // 计时主循环 Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isScreenOn && !isOverlayShowing) {
                accumulatedScreenSeconds++
                // 累计达到工作周期上限，触发全屏远眺遮罩
                if (accumulatedScreenSeconds >= currentWorkCycleSeconds) {
                    showEyeCareOverlay()
                }
            }
            // 每秒执行一次
            mainHandler.postDelayed(this, 1000)
        }
    }

    // 亮屏与熄屏广播接收器
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "检测到屏幕点亮：恢复亮屏累计计时")
                    isScreenOn = true
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "检测到屏幕熄灭/锁屏：暂停计时")
                    isScreenOn = false
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 创建低优先级纯静默前台通知（无铃声、无震动、不弹出浮窗干扰）
        createSilentNotificationChannel()
        val notification = buildSilentNotification("护眼守护中：仅累计亮屏时间")
        startForeground(NOTIFICATION_ID, notification)

        // 检测初始屏幕亮灭状态
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        isScreenOn = powerManager.isInteractive

        // 注册屏幕开闭广播
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)

        // 启动主计时器循环
        mainHandler.post(timerRunnable)
        Log.i(TAG, "EyeCare 护眼服务启动成功，当前处于静默运行模式")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TRIGGER_TEST -> {
                // 手动立即触发遮罩测试
                showEyeCareOverlay()
            }
            else -> {
                // 允许从 Intent 中配置测试周期时长（如 10 秒快速验证）
                val customCycle = intent?.getIntExtra(EXTRA_TEST_CYCLE_SECONDS, DEFAULT_WORK_CYCLE_SECONDS)
                if (customCycle != null && customCycle > 0) {
                    currentWorkCycleSeconds = customCycle
                    Log.d(TAG, "已更新触发周期为: ${currentWorkCycleSeconds} 秒")
                }
            }
        }
        return START_STICKY
    }

    /**
     * 弹出全屏半透明悬浮遮罩并启动 20 秒倒计时
     * 依赖纯视觉引导，无声音、无任何震动
     */
    private fun showEyeCareOverlay() {
        if (isOverlayShowing) return

        // 检查系统悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "未获取 SYSTEM_ALERT_WINDOW 悬浮窗权限，无法展示遮罩")
            return
        }

        try {
            // 加载全屏遮罩视图
            val inflater = LayoutInflater.from(this)
            val view = inflater.inflate(R.layout.overlay_eye_care, null)
            overlayView = view

            // 配置全屏悬浮窗参数
            val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutParamsType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            // 添加全屏视图到 WindowManager
            windowManager?.addView(view, params)
            isOverlayShowing = true

            // 初始化倒计时文本
            val tvCountdown = view.findViewById<TextView>(R.id.tvCountdownSeconds)
            tvCountdown?.text = REST_COUNTDOWN_SECONDS.toString()

            // 启动 20 秒纯视觉倒计时（零声音、零震动）
            countDownTimer = object : CountDownTimer((REST_COUNTDOWN_SECONDS * 1000).toLong(), 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    val secondsLeft = (millisUntilFinished / 1000).toInt() + 1
                    tvCountdown?.text = secondsLeft.toString()
                }

                override fun onFinish() {
                    // 倒计时归零，自动淡出销毁遮罩并静默进入下一轮循环
                    tvCountdown?.text = "0"
                    dismissOverlayAndResetCycle()
                }
            }.start()

            Log.i(TAG, "护眼全屏半透明遮罩已展示，开始 20 秒远眺倒计时")
        } catch (e: Exception) {
            Log.e(TAG, "展示全屏悬浮遮罩失败: ${e.message}", e)
            isOverlayShowing = false
        }
    }

    /**
     * 销毁全屏悬浮遮罩，重置累计亮屏时间，静默开启下一轮 20 分钟循环
     */
    private fun dismissOverlayAndResetCycle() {
        countDownTimer?.cancel()
        countDownTimer = null

        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "移除悬浮窗视图异常: ${e.message}")
            }
        }
        overlayView = null
        isOverlayShowing = false

        // 清零累计亮屏秒数，开启下一轮静默循环
        accumulatedScreenSeconds = 0
        Log.i(TAG, "本轮远眺结束，遮罩已自动移除，重置计时并静默进入下一轮循环")
    }

    /**
     * 创建纯静音前台通知渠道 (IMPORTANCE_LOW：无声、无震动)
     */
    private fun createSilentNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "护眼助手静默服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 20-20-20 亮屏计时，无声音无震动"
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    /**
     * 构建低优先级前台通知
     */
    private fun buildSilentNotification(content: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("EyeCare 20-20-20")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理广播监听
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "注销广播接收器异常: ${e.message}")
        }

        // 清理定时器与 Handler
        mainHandler.removeCallbacks(timerRunnable)
        countDownTimer?.cancel()

        // 移除悬浮窗
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (ignored: Exception) {}
        }

        Log.i(TAG, "EyeCare 护眼服务已停止")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
