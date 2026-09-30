package com.eyecare.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 静态开机、解锁屏幕与版本覆盖自启广播接收器
 *
 * 核心机制：
 * 1. 静态注册在 AndroidManifest.xml 中，不受 Service 进程被杀死的影响。
 * 2. 监听 ACTION_USER_PRESENT（用户解锁屏幕），当进程在锁屏深睡眠中被系统强杀后，
 *    用户只要解锁屏幕，系统底层即触发该广播，将应用唤醒。
 * 3. 严格遵循用户主动意图：只有在用户主动开启护眼守护（KEY_SERVICE_ENABLED == true）时才拉起服务，
 *    若用户主动关闭了服务，解锁时绝不擅自拉活。
 */
class BootAndUnlockReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootAndUnlockReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        Log.i(TAG, "收到系统广播: $action")

        when (action) {
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                checkAndWakeService(context, action)
            }
        }
    }

    /**
     * 校验用户启用状态，并在未运行时拉起 EyeCareService
     */
    private fun checkAndWakeService(context: Context, triggerAction: String) {
        val prefs = context.getSharedPreferences(EyeCareService.PREFS_NAME, Context.MODE_PRIVATE)
        val isServiceEnabledByUser = prefs.getBoolean(EyeCareService.KEY_SERVICE_ENABLED, false)

        if (!isServiceEnabledByUser) {
            Log.d(TAG, "用户之前未开启护眼守护或已主动停止，不执行自动唤醒")
            return
        }

        if (EyeCareService.isRunning) {
            Log.d(TAG, "护眼守护服务已在运行中，无需重复拉起")
            return
        }

        Log.i(TAG, "由广播 [$triggerAction] 触发：用户已启用护眼守护，正在自动唤醒拉起 EyeCareService 前台服务...")

        try {
            val cycleSeconds = prefs.getInt(EyeCareService.KEY_WORK_CYCLE_SECONDS, EyeCareService.DEFAULT_WORK_CYCLE_SECONDS)
            val pauseMedia = prefs.getBoolean(EyeCareService.KEY_PAUSE_MEDIA, true)

            val serviceIntent = Intent(context, EyeCareService::class.java).apply {
                this.action = EyeCareService.ACTION_START
                putExtra(EyeCareService.EXTRA_WORK_CYCLE_SECONDS, cycleSeconds)
                putExtra(EyeCareService.EXTRA_PAUSE_MEDIA, pauseMedia)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "EyeCareService 前台服务自动唤醒启动指令已派发成功")
        } catch (e: Exception) {
            Log.e(TAG, "自动唤醒 EyeCareService 异常: ${e.message}", e)
        }
    }
}
