package com.eyecare.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 硬件级精确闹钟广播接收器
 * 核心机制：
 * 由系统底层硬件 RTC (AlarmManager.setExactAndAllowWhileIdle) 精确调度唤醒。
 * 即使切到其他大型游戏运行、系统进程降频或 Looper 延迟批处理，也能在倒计时满额时刻准时唤醒并触发遮罩。
 */
class EyeCareAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "EyeCareAlarmReceiver"
        const val ACTION_EXACT_ALARM_TRIGGER = "com.eyecare.app.ACTION_EXACT_ALARM_TRIGGER"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        Log.i(TAG, "收到系统底层硬件级精确闹钟唤醒广播: ${intent.action}")

        if (intent.action == ACTION_EXACT_ALARM_TRIGGER) {
            val serviceIntent = Intent(context, EyeCareService::class.java).apply {
                action = EyeCareService.ACTION_EXACT_ALARM_TRIGGER
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "派发闹钟到期指令至 EyeCareService 异常: ${e.message}", e)
            }
        }
    }
}
