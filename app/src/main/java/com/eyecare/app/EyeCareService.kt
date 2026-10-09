package com.eyecare.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
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
 * 6. 【底层硬件 RTC 闹钟双保险】：接入 AlarmManager.setExactAndAllowWhileIdle，防止大型游戏或系统极端降频导致后台不弹窗。
 */
class EyeCareService : Service() {

    companion object {
        private const val TAG = "EyeCareService"
        private const val NOTIFICATION_CHANNEL_ID = "eyecare_silent_channel"
        private const val NOTIFICATION_ID = 202020
        private const val HIGH_PRIORITY_CHANNEL_ID = "eyecare_alert_channel"
        private const val HIGH_PRIORITY_NOTIFICATION_ID = 202021

        // 持久化配置键名
        const val PREFS_NAME = "eyecare_prefs"
        const val KEY_WORK_CYCLE_SECONDS = "pref_work_cycle_seconds"
        const val KEY_PAUSE_MEDIA = "pref_pause_media"
        const val KEY_ACCUMULATED_SECONDS = "pref_accumulated_seconds"
        const val KEY_SERVICE_ENABLED = "pref_service_enabled"

        // 标准工作周期：20 分钟 = 1200 秒
        const val DEFAULT_WORK_CYCLE_SECONDS = 20 * 60
        // 远眺倒计时休息时长：20 秒
        const val REST_COUNTDOWN_SECONDS = 20

        // 控制动作 Action
        const val ACTION_START = "com.eyecare.app.ACTION_START"
        const val ACTION_STOP = "com.eyecare.app.ACTION_STOP"
        const val ACTION_TRIGGER_TEST = "com.eyecare.app.ACTION_TRIGGER_TEST"
        const val ACTION_UPDATE_CONFIG = "com.eyecare.app.ACTION_UPDATE_CONFIG"
        const val ACTION_EXACT_ALARM_TRIGGER = "com.eyecare.app.ACTION_EXACT_ALARM_TRIGGER"

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
    private var overlayContext: Context? = null
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

    // 唤醒锁：确保在亮屏使用手机期间，后台进程不被 Linux 内核的 cgroup freezer 冻结挂起
    private var wakeLock: PowerManager.WakeLock? = null

    // 硬件级 RTC 闹钟管理器：用于在玩大型游戏或后台降频时由系统底层硬件准时唤醒触发遮罩
    private var alarmManager: AlarmManager? = null
    private var exactAlarmPendingIntent: PendingIntent? = null

    /**
     * 获取用于弹出全局悬浮遮罩的专用 Context
     * Android 12+ (API 31+) 优先使用单例缓存的 createWindowContext，兼顾 Display 关联与避免重复创建泄露
     */
    private fun getOverlayContext(): Context {
        if (overlayContext == null) {
            overlayContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                } catch (e: Exception) {
                    Log.w(TAG, "创建 WindowContext 异常，回退使用 Service: ${e.message}")
                    this
                }
            } else {
                this
            }
        }
        return overlayContext ?: this
    }

    /**
     * 获取用于悬浮窗挂载的 WindowManager 实例
     */
    private fun getOverlayWindowManager(): WindowManager {
        val ctx = getOverlayContext()
        val wm = (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        windowManager = wm
        return wm
    }

    /**
     * 规划底层硬件 RTC 准时闹钟 (优先使用最高优先级 AlarmManager.setAlarmClock)
     * 核心机制：
     * 1. 采用 Android 拥有最高特权的 setAlarmClock，该接口专为用户感知型倒计时与闹钟设计；
     * 2. 完全豁免 Doze 深度休眠节流与 9~15 分钟批处理合并限制，确保第二轮及后续每一轮均准时唤醒；
     * 3. 在硬件芯片中断级别直接唤醒系统派发广播，彻底解决后台玩大型游戏或进程降频导致不弹窗的问题。
     * @param remainingSeconds 距离下一次远眺触发的剩余秒数
     */
    private fun scheduleHardwareExactAlarm(remainingSeconds: Int) {
        if (remainingSeconds <= 0) return
        try {
            if (alarmManager == null) {
                alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            }
            val am = alarmManager ?: return

            // 仅取消先前注册的硬件调度，严禁作废 PendingIntent 自身凭据
            cancelHardwareExactAlarm()

            val intent = Intent(this, EyeCareAlarmReceiver::class.java).apply {
                action = EyeCareAlarmReceiver.ACTION_EXACT_ALARM_TRIGGER
            }
            exactAlarmPendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 优先使用最高特权的 setAlarmClock (豁免一切 Doze 节流与批处理合并限制)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val triggerAtWallMillis = System.currentTimeMillis() + remainingSeconds * 1000L
                val showIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val showPendingIntent = PendingIntent.getActivity(
                    this,
                    1,
                    showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAtWallMillis, showPendingIntent)
                am.setAlarmClock(alarmClockInfo, exactAlarmPendingIntent!!)
                Log.d(TAG, "已向底层硬件 RTC 注册最高优先级 setAlarmClock 闹钟，将在 ${remainingSeconds} 秒后准时唤醒触发遮罩")
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val triggerAtElapsedMillis = SystemClock.elapsedRealtime() + remainingSeconds * 1000L
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsedMillis,
                    exactAlarmPendingIntent!!
                )
            } else {
                val triggerAtElapsedMillis = SystemClock.elapsedRealtime() + remainingSeconds * 1000L
                am.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsedMillis,
                    exactAlarmPendingIntent!!
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "注册硬件 RTC 闹钟异常: ${e.message}")
        }
    }

    /**
     * 屏幕熄灭、遮罩弹出或服务停止时，及时取消硬件闹钟调度
     * 关键规范：仅调用 alarmManager.cancel() 撤销系统调度，严禁调用 pendingIntent.cancel()！
     * 若调用 pendingIntent.cancel() 会导致底层的 IntentSender 被永久废弃，导致第二轮及后续无法重新注册。
     */
    private fun cancelHardwareExactAlarm() {
        try {
            exactAlarmPendingIntent?.let {
                alarmManager?.cancel(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "取消硬件 RTC 闹钟调度异常: ${e.message}")
        }
    }

    /**
     * 在屏幕点亮且用户使用中时获取 PARTIAL_WAKE_LOCK
     * 核心机制：Android 内核进程优化器（CachedAppOptimizer）对持有 WakeLock 的进程实行绝对豁免，
     * 彻底杜绝应用在切出后台后被内核直接冻结挂起（freezer.state = FROZEN），保障 20 分钟倒计时如期弹出
     */
    private fun acquireScreenWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EyeCare::ScreenOnTimerWakeLock")?.apply {
                setReferenceCounted(false)
            }
        }
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
                Log.d(TAG, "已获取 PARTIAL_WAKE_LOCK，保障亮屏期间后台主循环不被内核冻结挂起")
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取 WakeLock 异常: ${e.message}")
        }
    }

    /**
     * 在屏幕熄灭/锁屏或服务停止时释放 PARTIAL_WAKE_LOCK
     * 核心机制：熄屏后立即归还 CPU 控制权，允许设备正常进入深度休眠，杜绝无意义耗电
     */
    private fun releaseScreenWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "已释放 PARTIAL_WAKE_LOCK，允许系统正常深度休眠")
            }
        } catch (e: Exception) {
            Log.e(TAG, "释放 WakeLock 异常: ${e.message}")
        }
    }

    // 计时主循环 Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            // 实时权威核验：绝不仅依赖内存变量，直接调用系统硬件服务校验当前屏幕是否处于亮屏交互状态
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isInteractive = powerManager?.isInteractive ?: isScreenOn

            // 同步内部状态
            isScreenOn = isInteractive

            // 若屏幕处于熄灭/锁屏非交互状态，立即挂起并退出计时主循环，释放唤醒锁
            if (!isInteractive) {
                Log.d(TAG, "当前屏幕处于熄灭/锁屏非交互状态，主计时循环挂起")
                releaseScreenWakeLock()
                return
            }

            // 亮屏使用中，确保持有唤醒锁保障后台主线程免受内核冻结
            acquireScreenWakeLock()

            if (!isOverlayShowing) {
                val now = SystemClock.elapsedRealtime()
                val deltaMillis = now - lastScreenOnTimestamp

                // 核心修复：全额折算真实流逝秒数，彻底解决系统后台降频导致使用时长被大量丢弃的严重缺陷
                // 只要当前处于亮屏交互状态，无论后台被系统批处理延迟 1 秒、5 秒还是 10 秒唤醒，
                // 所有整秒差值（deltaSeconds）均全额计入累计亮屏时长，零丢失！
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
            } else {
                // 当正在展示遮罩时，持续将时间基准点校准为当前时刻，防止遮罩关闭后产生时钟差
                lastScreenOnTimestamp = SystemClock.elapsedRealtime()
            }

            // 仅在服务运行中且屏幕点亮时继续派发下一次校准轮询
            if (isRunning && isScreenOn) {
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    // 亮屏、熄屏与用户解锁广播接收器
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val now = SystemClock.elapsedRealtime()
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "检测到屏幕点亮：校准时间基准点、获取唤醒锁、布设硬件闹钟并恢复主计时循环")
                    val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    isScreenOn = powerManager?.isInteractive ?: true
                    lastScreenOnTimestamp = now
                    acquireScreenWakeLock()
                    scheduleHardwareExactAlarm(currentRemainingSeconds)
                    // 恢复主计时循环
                    mainHandler.removeCallbacks(timerRunnable)
                    mainHandler.post(timerRunnable)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "检测到屏幕熄灭/锁屏：结算尾随秒数、释放唤醒锁、取消硬件闹钟并暂停主计时循环")
                    // 在完全熄屏前，结算未满心跳周期的真实流逝秒数，防止熄屏边缘时间丢失
                    if (isScreenOn && !isOverlayShowing) {
                        val deltaMillis = now - lastScreenOnTimestamp
                        if (deltaMillis >= 1000) {
                            val deltaSeconds = (deltaMillis / 1000).toInt()
                            accumulatedScreenSeconds += deltaSeconds
                        }
                    }
                    isScreenOn = false
                    lastScreenOnTimestamp = now
                    saveAccumulatedTime()
                    // 彻底停止主计时循环、取消硬件闹钟并释放 WakeLock，杜绝黑屏期间无意义空转与耗电
                    mainHandler.removeCallbacks(timerRunnable)
                    cancelHardwareExactAlarm()
                    releaseScreenWakeLock()
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "检测到用户解锁手机：确认亮屏使用中、获取唤醒锁、布设硬件闹钟并恢复计时")
                    isScreenOn = true
                    lastScreenOnTimestamp = now
                    acquireScreenWakeLock()
                    scheduleHardwareExactAlarm(currentRemainingSeconds)
                    mainHandler.removeCallbacks(timerRunnable)
                    mainHandler.post(timerRunnable)
                }
            }
        }
    }

    /**
     * 确保前台服务契约已履约并刷新通知
     * 核心作用：不论通过 startForegroundService、系统重启还是闹钟广播唤醒，必须立即履约前台声明，
     * 彻底杜绝因 5 秒未调用 startForeground 被系统抛出 ForegroundServiceDidNotStartInTimeException 强杀。
     */
    private fun ensureForegroundRunning() {
        val initialContent = formatCountdownText(currentRemainingSeconds)
        val notification = buildSilentNotification(initialContent)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "履约 startForeground 声明异常: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getOverlayWindowManager()

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

        // 创建低优先级纯静默前台通知渠道并立即启动前台服务
        createSilentNotificationChannel()
        ensureForegroundRunning()

        // 检测初始屏幕亮灭状态并初始化时间戳、唤醒锁与硬件闹钟
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        isScreenOn = powerManager?.isInteractive ?: true
        lastScreenOnTimestamp = SystemClock.elapsedRealtime()
        if (isScreenOn) {
            acquireScreenWakeLock()
            scheduleHardwareExactAlarm(currentRemainingSeconds)
        }

        // 注册屏幕开闭与用户解锁广播
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }

        // 启动主计时器循环
        mainHandler.post(timerRunnable)
        Log.i(TAG, "EyeCare 护眼服务启动成功，运行周期: ${currentWorkCycleSeconds} 秒，当前剩余倒计时: ${currentRemainingSeconds} 秒")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 关键保活履约：不论通过 startForegroundService、系统重启还是闹钟广播调起，
        // 必须在入口第一毫秒无条件履约 startForeground，彻底杜绝 5 秒未履约被系统强杀！
        ensureForegroundRunning()

        if (intent == null) {
            // 系统内存紧张杀进程后自动通过 START_STICKY 重启拉起
            Log.i(TAG, "服务由系统自动恢复重启 (intent=null)，重新校准基准点并启动计时")
            loadPreferences()
            configuredCycleSeconds = currentWorkCycleSeconds
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            isScreenOn = powerManager?.isInteractive ?: true
            lastScreenOnTimestamp = SystemClock.elapsedRealtime()
            if (isScreenOn) {
                acquireScreenWakeLock()
                scheduleHardwareExactAlarm(currentRemainingSeconds)
                mainHandler.removeCallbacks(timerRunnable)
                mainHandler.post(timerRunnable)
            }
            return START_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                // 用户主动点击停止护眼，记录持久化状态，释放唤醒锁与硬件闹钟，防止解锁屏幕时误拉活
                setServiceEnabledPref(false)
                cancelHardwareExactAlarm()
                releaseScreenWakeLock()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TRIGGER_TEST -> {
                // 手动立即触发遮罩测试
                showEyeCareOverlay()
            }
            ACTION_EXACT_ALARM_TRIGGER -> {
                // 硬件 RTC 闹钟到期唤醒（大型游戏/重度后台降频时的绝对可靠唤醒源）
                Log.i(TAG, "收到系统底层硬件 RTC 闹钟到期唤醒指令，立即触发全屏远眺遮罩")
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                val isInteractive = powerManager?.isInteractive ?: isScreenOn
                if (isInteractive && !isOverlayShowing) {
                    showEyeCareOverlay()
                }
            }
            ACTION_UPDATE_CONFIG, ACTION_START -> {
                // 用户主动启动或更新配置，记录持久化状态为已启用
                setServiceEnabledPref(true)
                // 动态更新配置（支持自定义运行时间与媒体暂停开关）
                val customCycle = intent.getIntExtra(EXTRA_WORK_CYCLE_SECONDS, -1)
                val legacyTestCycle = intent.getIntExtra(EXTRA_TEST_CYCLE_SECONDS, -1)

                if (customCycle > 0) {
                    currentWorkCycleSeconds = customCycle
                } else if (legacyTestCycle > 0) {
                    currentWorkCycleSeconds = legacyTestCycle
                } else {
                    loadPreferences()
                }

                configuredCycleSeconds = currentWorkCycleSeconds
                lastScreenOnTimestamp = SystemClock.elapsedRealtime()
                if (accumulatedScreenSeconds >= currentWorkCycleSeconds) {
                    accumulatedScreenSeconds = 0
                    saveAccumulatedTime()
                }
                currentRemainingSeconds = (currentWorkCycleSeconds - accumulatedScreenSeconds).coerceAtLeast(0)
                lastNotificationRemainingSeconds = -1
                checkAndUpdateNotification(currentRemainingSeconds)

                if (intent.hasExtra(EXTRA_PAUSE_MEDIA)) {
                    isPauseMediaEnabled = intent.getBooleanExtra(EXTRA_PAUSE_MEDIA, true)
                }

                // 确保在亮屏状态下持有唤醒锁、布设硬件精确闹钟并启动计时循环
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                isScreenOn = powerManager?.isInteractive ?: true
                if (isScreenOn) {
                    acquireScreenWakeLock()
                    scheduleHardwareExactAlarm(currentRemainingSeconds)
                    mainHandler.removeCallbacks(timerRunnable)
                    mainHandler.post(timerRunnable)
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
            triggerEmergencyFullScreenNotification()
            return
        }

        // 取消硬件闹钟，防止重复触发
        cancelHardwareExactAlarm()

        try {
            val wm = getOverlayWindowManager()
            val ctx = getOverlayContext()

            // 防重复保护：若先前的 View 尚未清理，先安全移除
            if (overlayView != null) {
                try {
                    wm.removeView(overlayView)
                } catch (ignored: Exception) {}
                overlayView = null
            }

            // 加载全屏遮罩视图
            val inflater = LayoutInflater.from(ctx)
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
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
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
            Log.e(TAG, "展示全屏悬浮遮罩失败（可能遭遇系统后台拦截）: ${e.message}", e)
            isOverlayShowing = false
            // 触发降级双保险：最高优先级全屏横幅通知，直观提示用户远眺，绝不静默丢失提醒！
            triggerEmergencyFullScreenNotification()
        }
    }

    /**
     * 降级双保险机制：当厂商系统在极端场景下拦截了后台 WindowManager.addView 悬浮窗时，
     * 立即发送最高优先级（IMPORTANCE_HIGH）的全屏悬浮横幅通知（Heads-Up Notification）！
     * 在屏幕顶部浮动呈现远眺引导卡片，点击即可直达全屏远眺，彻底杜绝静默失败！
     */
    private fun triggerEmergencyFullScreenNotification() {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java) ?: return

            // 创建高优先级警示通知渠道（无声无震动，但允许悬浮横幅弹出）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val alertChannel = NotificationChannel(
                    HIGH_PRIORITY_CHANNEL_ID,
                    "护眼远眺提醒(高优先级)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "远眺时间到期时的紧急浮动通知"
                    enableVibration(false)
                    setSound(null, null)
                }
                notificationManager.createNotificationChannel(alertChannel)
            }

            // 点击或全屏唤起 Intent
            val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, HIGH_PRIORITY_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

            val alertNotification = builder
                .setContentTitle("👀 20-20-20 远眺时间到了！")
                .setContentText("已连续使用屏幕达到设定周期，请眺望远方 20 秒放松眼睛")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(HIGH_PRIORITY_NOTIFICATION_ID, alertNotification)
            Log.i(TAG, "已派发最高优先级全屏降级横幅通知")
        } catch (e: Exception) {
            Log.e(TAG, "派发全屏降级横幅通知异常: ${e.message}")
        }
    }

    /**
     * 销毁全屏悬浮遮罩，重置累计亮屏时间，静默开启下一轮循环
     */
    private fun dismissOverlayAndResetCycle() {
        countDownTimer?.cancel()
        countDownTimer = null

        overlayView?.let { view ->
            try {
                getOverlayWindowManager().removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "移除悬浮窗视图异常: ${e.message}")
            }
        }
        overlayView = null
        isOverlayShowing = false

        // 释放音频焦点，恢复外部音视频播放（如抖音等）
        abandonMediaPause()

        // 清除降级全屏通知（如果先前曾触发）
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.cancel(HIGH_PRIORITY_NOTIFICATION_ID)
        } catch (ignored: Exception) {}

        // 权威校准屏幕当前交互状态
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        isScreenOn = powerManager?.isInteractive ?: isScreenOn

        // 清零累计亮屏秒数，开启下一轮静默循环
        accumulatedScreenSeconds = 0
        saveAccumulatedTime()
        lastScreenOnTimestamp = SystemClock.elapsedRealtime()
        currentRemainingSeconds = currentWorkCycleSeconds
        lastNotificationRemainingSeconds = -1
        checkAndUpdateNotification(currentRemainingSeconds)

        // 确保下一轮远眺倒计时循环即刻恢复运转并持有唤醒锁与硬件精确闹钟
        if (isRunning && isScreenOn) {
            acquireScreenWakeLock()
            scheduleHardwareExactAlarm(currentRemainingSeconds)
            mainHandler.removeCallbacks(timerRunnable)
            mainHandler.post(timerRunnable)
        }
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
     * 将用户开启/停止服务的状态持久化存储至 SharedPreferences
     * 供 BootAndUnlockReceiver 在解锁屏幕与开机时判断是否需要自动唤醒拉起服务
     */
    private fun setServiceEnabledPref(enabled: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
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

        // 取消硬件闹钟并彻底释放凭据
        cancelHardwareExactAlarm()
        exactAlarmPendingIntent?.cancel()
        exactAlarmPendingIntent = null
        alarmManager = null

        // 清理降级通知
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.cancel(HIGH_PRIORITY_NOTIFICATION_ID)
        } catch (ignored: Exception) {}

        // 移除悬浮窗
        overlayView?.let { view ->
            try {
                getOverlayWindowManager().removeView(view)
            } catch (ignored: Exception) {}
        }
        overlayContext = null

        // 确保服务销毁时释放音频焦点与唤醒锁
        abandonMediaPause()
        releaseScreenWakeLock()
        wakeLock = null

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
