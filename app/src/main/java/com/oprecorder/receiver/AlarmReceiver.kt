package com.oprecorder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.oprecorder.service.SchedulerService
import com.oprecorder.util.ScheduleManager

/**
 * 闹钟广播接收器
 *
 * 当 AlarmManager 触发时，启动调度服务执行指定脚本
 * 执行完后重新调度下一次（daily/interval 模式需要持续触发）
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        const val EXTRA_SCRIPT_ID = "SCRIPT_ID"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val scriptId = intent.getLongExtra(EXTRA_SCRIPT_ID, -1)
        Log.d(TAG, "收到定时闹钟，脚本 ID: $scriptId")

        if (scriptId <= 0) return

        // 重新调度下一次（daily/interval 模式）
        ScheduleManager.rescheduleAfterExecution(context, scriptId)

        // 启动调度服务执行脚本
        val serviceIntent = Intent(context, SchedulerService::class.java).apply {
            action = "ACTION_PLAY_SCRIPT"
            putExtra("SCRIPT_ID", scriptId)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
