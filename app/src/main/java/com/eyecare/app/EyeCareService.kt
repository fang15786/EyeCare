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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
 * 2. 【仅计亮屏】：动态监听 SCREEN_ON / SCREEN_OFF，黑屏锁屏期间完全挂起计时，亮屏期间基于 SystemClock 真实时钟差值精准累计。
 * 3. 【实时倒计时】：通知栏与外部界面实时展示“距离下次远眺”剩余使用倒计时，秒数公开可查。
 * 4. 【防杀接力】：累计时长持久化存储，若系统后台杀进程重启后无缝接力继续倒数，防止重置归零。
 * 5. 【自动循环】：累计亮屏满周期后弹出全屏半透明遮罩，20 秒倒计时归零后遮罩自动销毁，静默进入下一轮。
 */
class EyeCareService : Service() {

    companion object {
        private const val TAG = "EyeCareService"
        private const val NOTIFICATION_CHANNEL_ID = "eyecare_silent_channel"
        private const val NOTIFICATION_ID = 202020

        // 持久化配置键名
        const val PREFS_NAME = "eyecare_prefs"
        const val KEY_WORK_CYCLE_SECONDS = "pref_work_cycle_seconds"
        const val KEY_PAUSE_MEDIA = "pref_pause_media"
        const val KEY_ACCUMULATED_SECONDS = "pref_accumulated_seconds"

        // 标准工作周期：20 分钟 = 1200 秒
        const val DEFAULT_WORK_CYCLE_SECONDS = 20 * 60
        // 远眺倒计时休息时长：20 秒
        const val REST_COUNTDOWN_SECONDS = 20

        // 控制动作 Action
        const val ACTION_START = "com.eyecare.app.ACTION_START"
        const val ACTION_STOP = "com.eyecare.app.ACTION_STOP"
        const val ACTION_TRIGGER_TEST = "com.eyecare.app.ACTION_TRIGGER_TEST"
        const val ACTION_UPDATE_CONFIG = "com.eyecare.app.ACTION_UPDATE_CONFIG"

        // Intent 传递参数 Key
        const val EXTRA_WORK_CYCLE_SECONDS = "extra_work_cycle_seconds"
        const val EXTRA_PAUSE_MEDIA = "extra_pause_media"
        const val EXTRA_TEST_CYCLE_SECONDS = "extra_test_cycle_seconds"

        // 全局公开响应状态（供 MainActivity 实时观测绑定，线程可见）
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentRemainingSeconds: Int = DEFAULT_WORK_CYCLE_SECONDS
            private set

        @Volatile
        var configuredCycleSeconds: Int = DEFAULT_WORK_CYCLE_SECONDS
            private set
    }

    // WindowManager 与悬浮窗视图
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var countDownTimer: CountDownTimer? = null

    // 音频焦点管理器：用于在弹出遮罩时暂停外部音视频（如抖音、快手、音乐），遮罩关闭时恢复
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasRequestedAudioFocus = false
    private var isPauseMediaEnabled = true

    // 状态与计时
    private var isScreenOn = true
    private var accumulatedScreenSeconds = 0
    private var currentWorkCycleSeconds = DEFAULT_WORK_CYCLE_SECONDS
    private var isOverlayShowing = false

    // 高精度时间戳记录（毫秒，防系统休眠与后台降频）
    private var lastScreenOnTimestamp = 0L
    private var lastNotificationRemainingSeconds = -1

    // 计时主循环 Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isScreenOn && !isOverlayShowing) {
                val now = SystemClock.elapsedRealtime()
                val deltaMillis = now - lastScreenOnTimestamp
                if (deltaMillis >= 1000) {
                    val deltaSeconds = (deltaMillis / 1000).toInt()
                    accumulatedScreenSeconds += deltaSeconds
                    lastScreenOnTimestamp += deltaSeconds * 1000L
                    saveAccumulatedTime()
                }

                val remaining = (currentWorkCycleSeconds - accumulatedScreenSeconds).coerceAtLeast(0)
                currentRemainingSeconds = remaining

                // 动态刷新通知栏使用倒计时
                checkAndUpdateNotification(remaining)

                // 累计达到工作周期上限，触发全屏远眺遮罩
                if (accumulatedScreenSeconds >= currentWorkCycleSeconds) {
                    showEyeCareOverlay()
                }
            }
            // 每秒轮询校准
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
                    lastScreenOnTimestamp = SystemClock.elapsedRealtime()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "检测到屏幕熄灭/锁屏：暂停计时")
                    if (isScreenOn) {
                        val now = SystemClock.elapsedRealtime()
                        val deltaMillis = now - lastScreenOnTimestamp
                        if (deltaMillis >= 1000) {
                            val deltaSeconds = (deltaMillis / 1000).toInt()
                            accumulatedScreenSeconds += deltaSeconds
                        }
                        saveAccumulatedTime()
                    }
                    isScreenOn = false
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 从持久化偏好载入用户配置
        loadPreferences()
        configuredCycleSeconds = currentWorkCycleSeconds

        // 载入历史累计秒数（防后台被杀重启归零）
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        accumulatedScreenSeconds = prefs.getInt(KEY_ACCUMULATED_SECONDS, 0)
        if (accumulatedScreenSeconds >= currentWorkCycleSeconds) {
            accumulatedScreenSeconds = 0
            saveAccumulatedTime()
        }
        currentRemainingSeconds = (currentWorkCycleSeconds - accumulatedScreenSeconds).coerceAtLeast(0)

        // 创建低优先级纯静默前台通知（实时展示使用倒计时）
        createSilentNotificationChannel()
        val initialContent = formatCountdownText(currentRemainingSeconds)
        val notification = buildSilentNotification(initialContent)
        startForeground(NOTIFICATION_ID, notification)

        // 检测初始屏幕亮灭状态并初始化时间戳
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        isScreenOn = powerManager.isInteractive
        lastScreenOnTimestamp = SystemClock.elapsedRealtime()

        // 注册屏幕开闭广播
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)

        // 启动主计时器循环
        mainHandler.post(timerRunnable)
        Log.i(TAG, "EyeCare 护眼服务启动成功，运行周期: ${currentWorkCycleSeconds} 秒，当前剩余倒计时: ${currentRemainingSeconds} 秒")
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
            ACTION_UPDATE_CONFIG, ACTION_START -> {
                // 动态更新配置（支持自定义运行时间与媒体暂停开关）
                val customCycle = intent?.getIntExtra(EXTRA_WORK_CYCLE_SECONDS, -1) ?: -1
                val legacyTestCycle = intent?.getIntExtra(EXTRA_TEST_CYCLE_SECONDS, -1) ?: -1

                if (customCycle > 0) {
                    currentWorkCycleSeconds = customCycle
                } else if (legacyTestCycle > 0) {
                    currentWorkCycleSeconds = legacyTestCycle
                } else {
                    loadPreferences()
                }

                configuredCycleSeconds = currentWorkCycleSeconds
                if (accumulatedScreenSeconds >= currentWorkCycleSeconds) {
                    accumulatedScreenSeconds = 0
                    saveAccumulatedTime()
                }
                currentRemainingSeconds = (currentWorkCycleSeconds - accumulatedScreenSeconds).coerceAtLeast(0)
                lastNotificationRemainingSeconds = -1
                checkAndUpdateNotification(currentRemainingSeconds)

                if (intent != null && intent.hasExtra(EXTRA_PAUSE_MEDIA)) {
                    isPauseMediaEnabled = intent.getBooleanExtra(EXTRA_PAUSE_MEDIA, true)
                }

                Log.d(TAG, "已更新配置: 运行周期=${currentWorkCycleSeconds}秒, 剩余倒计时=${currentRemainingSeconds}秒")
            }
        }
        return START_STICKY
    }

    /**
     * 弹出全屏半透明悬浮遮罩并启动 20 秒倒计时
     * 1. 采用 Service 原生稳定的 getSystemService(Context.WINDOW_SERVICE) 与标准 LayoutInflater，避免 Context 缺少 Display 异常；
     * 2. 注入 FLAG_NOT_FOCUSABLE、FLAG_LAYOUT_IN_SCREEN、FLAG_LAYOUT_NO_LIMITS 及挖孔屏切区全屏延展，确保绝对穿透覆盖在抖音等全屏沉浸应用上方；
     * 3. 增强视图重入与防重复挂载保护。
     */
    private fun showEyeCareOverlay() {
        if (isOverlayShowing) return

        // 检查系统悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "未获取 SYSTEM_ALERT_WINDOW 悬浮窗权限，无法展示遮罩")
            return
        }

        try {
            // 防重复保护：若先前的 View 尚未清理，先安全移除
            if (overlayView != null) {
                try {
                    windowManager?.removeView(overlayView)
                } catch (ignored: Exception) {}
                overlayView = null
            }

            // 获取系统原生 WindowManager
            if (windowManager == null) {
                windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            }
            val wm = windowManager ?: return

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
                // 彻底延展至全屏幕边缘（包括刘海与挖孔区域），确保无死角覆盖抖音等沉浸式全屏应用
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            // 添加全屏视图到 WindowManager
            wm.addView(view, params)
            isOverlayShowing = true

            // 请求瞬态音频焦点以暂停外部音视频播放（如抖音等）
            requestMediaPause()

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

        // 释放音频焦点，恢复外部音视频播放（如抖音等）
        abandonMediaPause()

        // 清零累计亮屏秒数，开启下一轮静默循环
        accumulatedScreenSeconds = 0
        saveAccumulatedTime()
        lastScreenOnTimestamp = SystemClock.elapsedRealtime()
        currentRemainingSeconds = currentWorkCycleSeconds
        lastNotificationRemainingSeconds = -1
        checkAndUpdateNotification(currentRemainingSeconds)
        Log.i(TAG, "本轮远眺结束，遮罩已自动移除，重置计时并静默进入下一轮循环")
    }

    /**
     * 将累计秒数持久化存储至 SharedPreferences
     */
    private fun saveAccumulatedTime() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_ACCUMULATED_SECONDS, accumulatedScreenSeconds).apply()
    }

    /**
     * 智能检查并刷新前台通知栏倒计时
     * 倒计时 <= 30 秒时每秒刷新；> 30 秒时每 5 秒刷新，兼顾视觉即时性与系统性能
     */
    private fun checkAndUpdateNotification(remaining: Int) {
        if (lastNotificationRemainingSeconds == -1 || remaining <= 30 || Math.abs(lastNotificationRemainingSeconds - remaining) >= 5) {
            lastNotificationRemainingSeconds = remaining
            val contentText = formatCountdownText(remaining)
            val notification = buildSilentNotification(contentText)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 格式化倒计时文本展示文案
     */
    private fun formatCountdownText(remainingSeconds: Int): String {
        val cycleLabel = if (currentWorkCycleSeconds < 60) "${currentWorkCycleSeconds}秒" else "${currentWorkCycleSeconds / 60}分钟"
        return if (remainingSeconds < 60) {
            "距离下次远眺倒计时: ${remainingSeconds}秒 (周期: $cycleLabel)"
        } else {
            val mins = remainingSeconds / 60
            val secs = remainingSeconds % 60
            if (secs == 0) {
                "距离下次远眺倒计时: ${mins}分钟 (周期: $cycleLabel)"
            } else {
                "距离下次远眺倒计时: ${mins}分${secs}秒 (周期: $cycleLabel)"
            }
        }
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
            .setContentTitle("EyeCare 护眼守护中")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        saveAccumulatedTime()

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

        // 确保服务销毁时释放音频焦点
        abandonMediaPause()

        Log.i(TAG, "EyeCare 护眼服务已停止")
    }

    /**
     * 从 SharedPreferences 载入运行周期和暂停媒体偏好设置
     */
    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentWorkCycleSeconds = prefs.getInt(KEY_WORK_CYCLE_SECONDS, DEFAULT_WORK_CYCLE_SECONDS)
        isPauseMediaEnabled = prefs.getBoolean(KEY_PAUSE_MEDIA, true)
    }

    /**
     * 申请瞬态音频焦点，触发正在播放的外部应用（如抖音、快手、音乐）暂停
     */
    private fun requestMediaPause() {
        if (!isPauseMediaEnabled) return
        try {
            if (audioManager == null) {
                audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            }
            val am = audioManager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* 静默监听焦点变动 */ }
                    .build()

                val res = am.requestAudioFocus(audioFocusRequest!!)
                hasRequestedAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            } else {
                @Suppress("DEPRECATION")
                val res = am.requestAudioFocus(
                    { /* 静默监听焦点变动 */ },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                hasRequestedAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            }
            Log.d(TAG, "申请瞬态音频焦点成功，已请求暂停外部音视频: $hasRequestedAudioFocus")
        } catch (e: Exception) {
            Log.e(TAG, "申请瞬态音频焦点异常: ${e.message}", e)
        }
    }

    /**
     * 释放瞬态音频焦点，通知系统让先前的媒体应用（如抖音等）恢复播放
     */
    private fun abandonMediaPause() {
        if (!hasRequestedAudioFocus) return
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus { /* 静默释放 */ }
            }
            hasRequestedAudioFocus = false
            Log.d(TAG, "已释放瞬态音频焦点，已通知外部音视频恢复播放")
        } catch (e: Exception) {
            Log.e(TAG, "释放瞬态音频焦点异常: ${e.message}", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
