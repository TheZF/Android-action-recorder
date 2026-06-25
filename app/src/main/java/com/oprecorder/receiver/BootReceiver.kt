package com.oprecorder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.oprecorder.util.ScheduleManager

/**
 * 开机广播接收器
 *
 * 设备重启后，重新注册所有定时闹钟
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            Log.d(TAG, "设备启动完成，重新注册定时闹钟")
            ScheduleManager.rescheduleAll(context)
        }
    }
}
