package com.oprecorder.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.oprecorder.MainActivity
import com.oprecorder.R
import com.oprecorder.data.AppDatabase
import com.oprecorder.data.ScriptAction
import kotlinx.coroutines.*

/**
 * 调度服务
 *
 * 职责：
 * 1. 前台保活，确保息屏后进程不被杀
 * 2. 持有 WakeLock 保证 CPU 运行
 * 3. 从 AlarmReceiver 接收定时触发，执行脚本回放
 */
class SchedulerService : Service() {

    companion object {
        private const val TAG = "SchedulerService"
        private const val CHANNEL_ID = "scheduler_service"
        private const val NOTIFICATION_ID = 2001

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isPlaying: Boolean = false
            private set
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var db: AppDatabase? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        db = AppDatabase.getInstance(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("等待定时任务..."))
        acquireWakeLock()
        Log.d(TAG, "调度服务已创建")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
        releaseWakeLock()
        Log.d(TAG, "调度服务已销毁")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_PLAY_SCRIPT" -> {
                val scriptId = intent.getLongExtra("SCRIPT_ID", -1)
                if (scriptId > 0) {
                    scope.launch {
                        playScript(scriptId)
                    }
                }
            }
            "ACTION_STOP" -> {
                OpAccessibilityService.instance?.stopPlayback()
                isPlaying = false
                updateNotification("等待定时任务...")
            }
        }
        return START_STICKY
    }

    // ==================== WakeLock ====================

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OpRecorder::SchedulerWakeLock"
        ).apply {
            acquire(12 * 60 * 60 * 1000L) // 最多 12 小时
        }
        Log.d(TAG, "WakeLock 已获取")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    // ==================== 唤醒屏幕 ====================

    private fun wakeUpScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "OpRecorder::ScreenWake"
            )
            wakeLock.acquire(30000L) // 亮屏 30 秒
            wakeLock.release()
            Log.d(TAG, "屏幕已唤醒")
        }
    }

    // ==================== 脚本回放 ====================

    private suspend fun playScript(scriptId: Long) {
        val a11y = OpAccessibilityService.instance
        if (a11y == null) {
            Log.e(TAG, "无障碍服务未启动，无法回放")
            updateNotification("回放失败：未开启无障碍服务")
            return
        }

        val script = db?.scriptDao()?.getScriptById(scriptId) ?: return
        val actions = db?.scriptDao()?.getActionsByScriptId(scriptId) ?: return

        if (actions.isEmpty()) {
            updateNotification("脚本「${script.name}」无操作，跳过")
            return
        }

        isPlaying = true
        wakeUpScreen()
        updateNotification("正在回放: ${script.name}")

        // 等待屏幕稳定
        delay(1000)

        a11y.playScript(
            actions = actions,
            loopCount = script.loopCount,
            loopInterval = script.loopInterval,
            onProgress = { current, total ->
                updateNotification("回放中: ${script.name} [${current + 1}/$total]")
            },
            onComplete = {
                isPlaying = false
                updateNotification("回放完成: ${script.name}")

                // 更新最后执行时间
                scope.launch {
                    val updated = script.copy(lastExecutedAt = System.currentTimeMillis())
                    db?.scriptDao()?.updateScript(updated)

                    // 回放完成后延迟停止服务，避免通知一直残留
                    delay(3000)
                    stopSelf()
                }
            }
        )
    }

    // ==================== 通知 ====================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "定时调度",
            NotificationManager.IMPORTANCE_DEFAULT  // 提高优先级，回放状态更醒目
        ).apply {
            description = "定时执行脚本的后台服务"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 100, 100, 100)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SchedulerService::class.java).apply {
            action = "ACTION_STOP"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("操作录制器 - 定时调度")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_schedule)
            .setContentIntent(pendingIntent)
            .addAction(0, "停止", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
