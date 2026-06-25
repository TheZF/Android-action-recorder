package com.oprecorder.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.oprecorder.data.AppDatabase
import com.oprecorder.data.Script
import com.oprecorder.receiver.AlarmReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 定时调度管理器
 *
 * 使用 AlarmManager 精确调度脚本回放
 * 支持三种模式：
 * - once: 一次性定时
 * - daily: 每天定时
 * - interval: 间隔执行（每隔 N 分钟）
 */
object ScheduleManager {

    private const val TAG = "ScheduleManager"
    private const val REQUEST_CODE_BASE = 10000

    /**
     * 为脚本设置定时闹钟
     */
    fun schedule(context: Context, script: Script) {
        if (!script.scheduled) {
            cancel(context, script)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val pendingIntent = createPendingIntent(context, script.id)

        when (script.scheduleRepeat) {
            "daily" -> {
                // 按选定星期执行（scheduleDays 位掩码）
                val triggerTime = findNextTriggerTime(
                    script.scheduleHour, script.scheduleMinute,
                    script.scheduleDays
                )

                setExactAlarm(alarmManager, triggerTime, pendingIntent)
                val daysDesc = describeDays(script.scheduleDays)
                Log.d(TAG, "已设置每周闹钟: ${script.scheduleHour}:${script.scheduleMinute} " +
                        "星期=$daysDesc " +
                        "触发=${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(triggerTime)} " +
                        "脚本=${script.name}")
            }

            "interval" -> {
                // 间隔执行
                val intervalMs = script.scheduleIntervalMin * 60 * 1000L
                val triggerTime = System.currentTimeMillis() + intervalMs

                setExactAlarm(alarmManager, triggerTime, pendingIntent)
                Log.d(TAG, "已设置间隔闹钟: 每${script.scheduleIntervalMin}分钟 " +
                        "脚本=${script.name}")
            }

            "once" -> {
                // 一次性定时
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, script.scheduleHour)
                    set(Calendar.MINUTE, script.scheduleMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis()) {
                        add(Calendar.DAY_OF_YEAR, 1)
                    }
                }
                val triggerTime = calendar.timeInMillis

                setExactAlarm(alarmManager, triggerTime, pendingIntent)
                Log.d(TAG, "已设置一次性闹钟: ${script.scheduleHour}:${script.scheduleMinute} " +
                        "脚本=${script.name}")
            }
        }
    }

    /**
     * 根据 scheduleDays 位掩码查找下一个触发时间
     * bit0=周一(1) bit1=周二(2) ... bit6=周日(64)，127=全选
     */
    private fun findNextTriggerTime(hour: Int, minute: Int, days: Int): Long {
        val now = System.currentTimeMillis()
        // 从今天开始，逐天查找
        for (offset in 0..7) {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, offset)
            }
            if (isDaySelected(calendar, days) && calendar.timeInMillis > now) {
                return calendar.timeInMillis
            }
        }
        // 兜底：1天后
        val fallback = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return fallback.timeInMillis
    }

    /** 判断 calendar 对应的星期是否被 scheduleDays 选中 */
    private fun isDaySelected(calendar: Calendar, days: Int): Boolean {
        // Calendar.DAY_OF_WEEK: Sunday=1, Monday=2, ... Saturday=7
        // 转换为 bit: Monday=bit0(1), Tuesday=bit1(2), ... Sunday=bit6(64)
        val dow = calendar.get(Calendar.DAY_OF_WEEK)
        val bit = when (dow) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 4
            Calendar.THURSDAY -> 8
            Calendar.FRIDAY -> 16
            Calendar.SATURDAY -> 32
            Calendar.SUNDAY -> 64
            else -> 0
        }
        return (days and bit) != 0
    }

    /** 将 scheduleDays 位掩码转为可读描述 */
    private fun describeDays(days: Int): String {
        if (days == 127) return "每天"
        val labels = arrayOf("一", "二", "三", "四", "五", "六", "日")
        val sb = StringBuilder()
        for (i in labels.indices) {
            if ((days and (1 shl i)) != 0) {
                if (sb.isNotEmpty()) sb.append(",")
                sb.append(labels[i])
            }
        }
        return "周${sb}"
    }

    private fun setExactAlarm(alarmManager: AlarmManager, triggerTime: Long, pendingIntent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }

    /**
     * 取消脚本的定时闹钟
     */
    fun cancel(context: Context, script: Script) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = createPendingIntent(context, script.id)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        Log.d(TAG, "已取消闹钟: 脚本=${script.name}")
    }

    /**
     * 重新调度所有已启用的定时脚本（开机后调用）
     */
    fun rescheduleAll(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getInstance(context)
            val scripts = db.scriptDao().getScheduledScripts()
            for (script in scripts) {
                schedule(context, script)
            }
            Log.d(TAG, "重新调度了 ${scripts.size} 个定时脚本")
        }
    }

    /**
     * 在一次性/每日闹钟触发后重新注册下一次
     */
    fun rescheduleAfterExecution(context: Context, scriptId: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getInstance(context)
            val script = db.scriptDao().getScriptById(scriptId) ?: return@launch
            if (script.scheduled) {
                schedule(context, script)
            }
        }
    }

    private fun createPendingIntent(context: Context, scriptId: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_SCRIPT_ID, scriptId)
        }
        return PendingIntent.getBroadcast(
            context,
            (REQUEST_CODE_BASE + scriptId).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
