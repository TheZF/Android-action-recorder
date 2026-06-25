package com.oprecorder.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import com.oprecorder.data.ActionType
import com.oprecorder.data.ScriptAction
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 无障碍服务：录制 + 回放引擎
 *
 * 录制方案：onMotionEvent（API 30+）
 * - 系统将触摸事件的副本发给 onMotionEvent，触摸本身正常到达底层应用
 * - 零拦截、零延迟、操作完全流畅
 * - onMotionEvent 中记录 DOWN/MOVE/UP 坐标，判断点击/长按/滑动
 *
 * 回放：使用 dispatchGesture 执行手势序列
 */
class OpAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "OpA11yService"
        private const val LONG_PRESS_MS = 500L
        private const val SWIPE_THRESHOLD_PX = 30f

        @Volatile
        var instance: OpAccessibilityService? = null
            private set

        @Volatile
        var isRecording: Boolean = false
            private set

        /** onMotionEvent 是否被系统调用过（用于自动降级检测） */
        @Volatile
        var motionEventReceived: Boolean = false
            private set

        var onActionRecorded: ((ScriptAction) -> Unit)? = null

    private var actionOrder: Int = 0
    private var lastRecordTime: Long = 0L

        fun isRunning(): Boolean = instance != null
    }

    // 回放
    private var playbackJob: Job? = null
    private val playbackScope get() = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // WakeLock：回放时唤醒+解锁+保持常亮
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null

    // 录制：触摸状态跟踪
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L
    private var lastMoveX = 0f
    private var lastMoveY = 0f
    private var currentScriptId: Long = -1

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "无障碍服务已连接, onMotionEvent 可用: ${Build.VERSION.SDK_INT >= 30}")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRecording = false
        playbackJob?.cancel()
        releaseWakeLocks()
        Log.d(TAG, "无障碍服务已销毁")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 录制由 onMotionEvent 负责，此处忽略
    }

    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务被中断")
    }

    // ==================== 录制：onMotionEvent 观察触摸 ====================

    /**
     * 系统将触摸事件的副本发到这里
     * 触摸本身已经正常到达底层应用，我们只是观察和记录
     * 零拦截、零延迟！
     */
    override fun onMotionEvent(event: MotionEvent) {
        if (!isRecording) return
        if (Build.VERSION.SDK_INT < 30) return

        motionEventReceived = true

        val rawX = event.rawX
        val rawY = event.rawY

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = rawX
                touchDownY = rawY
                touchDownTime = System.currentTimeMillis()
                lastMoveX = rawX
                lastMoveY = rawY
                Log.d(TAG, "Motion DOWN: ($rawX, $rawY)")
            }

            MotionEvent.ACTION_MOVE -> {
                lastMoveX = rawX
                lastMoveY = rawY
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val upX = if (event.actionMasked == MotionEvent.ACTION_UP) rawX else lastMoveX
                val upY = if (event.actionMasked == MotionEvent.ACTION_UP) rawY else lastMoveY
                val elapsed = System.currentTimeMillis() - touchDownTime
                val dx = upX - touchDownX
                val dy = upY - touchDownY
                val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                val action = when {
                    distance > SWIPE_THRESHOLD_PX -> ScriptAction(
                        scriptId = currentScriptId,
                        order = actionOrder++,
                        type = ActionType.SWIPE,
                        x = touchDownX, y = touchDownY,
                        x2 = upX, y2 = upY,
                        duration = 300L,
                        delayAfter = 0L
                    )
                    elapsed > LONG_PRESS_MS -> ScriptAction(
                        scriptId = currentScriptId,
                        order = actionOrder++,
                        type = ActionType.LONG_PRESS,
                        x = touchDownX, y = touchDownY,
                        duration = elapsed.coerceIn(500L, 3000L),
                        delayAfter = 0L
                    )
                    else -> ScriptAction(
                        scriptId = currentScriptId,
                        order = actionOrder++,
                        type = ActionType.TAP,
                        x = touchDownX, y = touchDownY,
                        duration = 100L,
                        delayAfter = 0L
                    )
                }

                recordAction(action)
                Log.d(TAG, "录制: ${action.type} at (${action.x}, ${action.y})")
            }
        }
    }

    private fun recordAction(action: ScriptAction) {
        lastRecordTime = System.currentTimeMillis()
        onActionRecorded?.invoke(action)
    }

    // ==================== 录制控制 ====================

    fun startRecording(scriptId: Long) {
        isRecording = true
        motionEventReceived = false
        actionOrder = 0
        lastRecordTime = 0L
        currentScriptId = scriptId
        Log.d(TAG, "开始录制（onMotionEvent 观察模式）")
    }

    fun stopRecording() {
        isRecording = false
        Log.d(TAG, "停止录制，共 $actionOrder 个操作")
    }

    /**
     * 派发单个手势（用于覆盖层录制转发，无需回调）
     */
    fun dispatchSingleGesture(action: ScriptAction) {
        // HOME/BACK 不走手势，直接 performGlobalAction
        when (action.type) {
            ActionType.HOME -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
            ActionType.BACK -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }
            else -> {}
        }
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch {
            val success = dispatchGestureAction(action)
            Log.d(TAG, "转发手势: ${action.type} at (${action.x}, ${action.y}) → $success")
        }
    }

    // ==================== 唤醒 + 解锁屏幕 ====================

    /**
     * 唤醒屏幕并尝试解锁
     * 1. 点亮屏幕
     * 2. 尝试上滑解锁（仅对滑动/无密码锁屏有效）
     * 3. 持有 PARTIAL_WAKE_LOCK 防止 CPU 休眠
     */
    @SuppressLint("WakelockTimeout")
    private suspend fun wakeUpAndUnlock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager

        // 1. 唤醒屏幕
        if (!pm.isInteractive) {
            screenWakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "OpRecorder:ScreenWake"
            ).apply {
                acquire(10000L) // 10 秒后自动释放，之后依赖 FLAG_KEEP_SCREEN_ON
            }
            Log.d(TAG, "屏幕已唤醒")
            delay(800) // 等屏幕亮起
        }

        // 2. 尝试上滑解锁（只对无密码/滑动锁屏有效）
        val kgm = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (kgm.isKeyguardLocked) {
            Log.d(TAG, "检测到锁屏，尝试上滑解锁")
            trySwipeToUnlock()
            delay(800) // 等解锁动画
        }

        // 3. 持有 CPU WakeLock
        if (cpuWakeLock == null) {
            cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpRecorder:Playback")
        }
        if (cpuWakeLock?.isHeld == false) {
            cpuWakeLock?.acquire()
            Log.d(TAG, "已获取 PARTIAL_WAKE_LOCK")
        }
    }

    /**
     * 在锁屏界面上滑解锁
     * 仅对无密码/滑动锁屏有效，有 PIN/图案/密码的锁屏无法自动解锁
     */
    private fun trySwipeToUnlock() {
        val dm = resources.displayMetrics
        val centerX = dm.widthPixels / 2f
        val fromY = dm.heightPixels * 0.8f
        val toY = dm.heightPixels * 0.2f

        val path = Path().apply {
            moveTo(centerX, fromY)
            lineTo(centerX, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "解锁滑动手势完成")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "解锁滑动手势取消")
            }
        }, null)
    }

    /** 释放所有 WakeLock */
    private fun releaseWakeLocks() {
        try { if (screenWakeLock?.isHeld == true) screenWakeLock?.release() } catch (_: Exception) {}
        screenWakeLock = null
        try { if (cpuWakeLock?.isHeld == true) cpuWakeLock?.release() } catch (_: Exception) {}
        cpuWakeLock = null
        Log.d(TAG, "WakeLock 已释放")
    }

    // ==================== 手势回放 ====================

    private suspend fun dispatchGestureAction(action: ScriptAction): Boolean {
        // HOME/BACK 用 performGlobalAction
        when (action.type) {
            ActionType.HOME -> {
                val result = performGlobalAction(GLOBAL_ACTION_HOME)
                Log.d(TAG, "回放 HOME → $result")
                return result
            }
            ActionType.BACK -> {
                val result = performGlobalAction(GLOBAL_ACTION_BACK)
                Log.d(TAG, "回放 BACK → $result")
                return result
            }
            ActionType.WAIT -> return true
            else -> {} // TAP, LONG_PRESS, SWIPE 走手势
        }

        return suspendCancellableCoroutine { continuation ->
            val path = Path()
            when (action.type) {
                ActionType.TAP -> path.moveTo(action.x, action.y)
                ActionType.LONG_PRESS -> path.moveTo(action.x, action.y)
                ActionType.SWIPE -> {
                    path.moveTo(action.x, action.y)
                    path.lineTo(action.x2, action.y2)
                }
                else -> {
                    continuation.resumeWith(Result.success(true))
                    return@suspendCancellableCoroutine
                }
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, action.duration))
                .build()

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    continuation.resumeWith(Result.success(true))
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    continuation.resumeWith(Result.success(false))
                }
            }, null)
        }
    }

    fun playScript(
        actions: List<ScriptAction>,
        loopCount: Int = 1,
        loopInterval: Long = 1000L,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
        onComplete: () -> Unit = {}
    ) {
        playbackJob?.cancel()

        playbackJob = playbackScope.launch {
            // 唤醒 + 解锁屏幕
            wakeUpAndUnlock()

            val totalLoops = if (loopCount <= 0) Int.MAX_VALUE else loopCount
            for (loop in 0 until totalLoops) {
                for ((index, action) in actions.withIndex()) {
                    if (!isActive) break
                    onProgress?.invoke(index, actions.size)
                    val success = dispatchGestureAction(action)
                    Log.d(TAG, "回放 [${loop + 1}][${index + 1}/${actions.size}] ${action.type} → $success")
                    // HOME/BACK 后至少等 1 秒让系统动画完成
                    val minDelay = when (action.type) {
                        ActionType.HOME, ActionType.BACK -> 1000L
                        else -> 0L
                    }
                    val actualDelay = maxOf(minDelay, action.delayAfter)
                    if (actualDelay > 0) delay(actualDelay)
                }
                if (loop < totalLoops - 1 && loopInterval > 0) delay(loopInterval)
            }
            releaseWakeLocks()
            onComplete()
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        releaseWakeLocks()
        Log.d(TAG, "回放已停止")
    }
}
