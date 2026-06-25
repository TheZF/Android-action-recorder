package com.oprecorder.service

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.oprecorder.MainActivity
import com.oprecorder.R
import com.oprecorder.data.ActionType
import com.oprecorder.data.AppDatabase
import com.oprecorder.data.Script
import com.oprecorder.data.ScriptAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 悬浮控制服务
 *
 * 录制方案（双模式自动降级）：
 * 1. 优先 onMotionEvent（API 33+）：零拦截
 * 2. 降级 覆盖层 + FLAG_NOT_TOUCHABLE 切换转发：
 *    - overlay 始终在 WM 中，只切换 FLAG_NOT_TOUCHABLE
 *    - 不做 remove/add，避免 view 丢失
 *
 * 悬浮球：点击展开菜单（录制/回放/停止/关闭服务）
 */
class FloatingControlService : Service() {

    companion object {
        private const val TAG = "FloatingCtrl"
        private const val CHANNEL_ID = "floating_control"
        private const val NOTIFICATION_ID = 1001
        private const val LONG_PRESS_MS = 500L
        private const val SWIPE_THRESHOLD_PX = 30f
        private const val MOTION_EVENT_CHECK_DELAY = 3000L

        @Volatile
        var isRecordingActive: Boolean = false
            private set

        @Volatile
        var isPlaybackActive: Boolean = false
            private set

        var onStopRequested: (() -> Unit)? = null
    }

    private enum class RecordMode { MOTION_EVENT, OVERLAY }
    private var recordMode = RecordMode.OVERLAY

    private var windowManager: WindowManager? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private var floatingView: View? = null
    private var menuView: View? = null
    private var statusView: View? = null

    private var isRecording = false
    private var currentScriptId: Long = -1

    // 悬浮按钮拖拽
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var isMenuExpanded = false

    // 录制
    private var recordedStepCount = 0
    private val recordedActions = mutableListOf<ScriptAction>()

    // 覆盖层
    private var recordingOverlay: View? = null
    private var isForwarding = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L
    private var lastMoveX = 0f
    private var lastMoveY = 0f
    private var lastRecordTime = 0L

    // 最近完成的脚本（用于悬浮球回放）
    private var lastCompletedScriptId: Long = -1

    // WakeLock：回放时保持屏幕常亮 + CPU 不休眠
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("操作录制器运行中"))
        showFloatingButton()
        Log.d(TAG, "悬浮控制服务已创建")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) stopRecording()
        hideMenu()
        removeStatusView()
        removeRecordingOverlay()
        removeFloatingButton()
        releaseWakeLocks()
        isRecording = false
        isRecordingActive = false
        isForwarding = false
        isPlaybackActive = false
        Log.d(TAG, "悬浮控制服务已销毁")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_START_RECORDING" -> startRecording()
            "ACTION_STOP_RECORDING" -> stopRecording()
            "ACTION_PLAY_LAST" -> playLastScript()
            "ACTION_STOP_SERVICE" -> {
                if (isRecording) stopRecording()
                stopSelf()
            }
        }
        return START_STICKY
    }

    // ==================== 通知 ====================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "操作录制器", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "保持录制器和回放器后台运行" }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, FloatingControlService::class.java).apply {
            action = "ACTION_STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("操作录制器")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_record)
            .setContentIntent(pendingIntent)
            .addAction(0, "停止", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ==================== 悬浮按钮 ====================

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingButton() {
        val btn = ImageView(this).apply {
            setImageResource(R.drawable.ic_record)
            setBackgroundResource(R.drawable.bg_floating_btn)
            val size = (40 * resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setContentDescription("操作录制器")
        }

        val dm = resources.displayMetrics
        val size = (48 * dm.density).toInt()

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - size - 10
            y = dm.heightPixels / 2
        }

        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > 10 || abs(dy) > 10) isDragging = true
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager?.updateViewLayout(btn, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        if (isMenuExpanded) hideMenu() else showMenu()
                    }
                    true
                }
                else -> false
            }
        }

        floatingView = btn
        windowManager?.addView(btn, params)
    }

    private fun removeFloatingButton() {
        floatingView?.let { windowManager?.removeView(it); floatingView = null }
    }

    // ==================== 菜单 ====================

    @SuppressLint("SetTextI18n")
    private fun showMenu() {
        if (menuView != null) return
        isMenuExpanded = true

        val dm = resources.displayMetrics
        val padding = (14 * dm.density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF5F5F5F5.toInt())
            setPadding(padding, padding / 2, padding, padding / 2)
        }

        // 录制/停止
        val btnRecord = TextView(this).apply {
            text = if (isRecording) "■ 停止录制" else "● 开始录制"
            setTextColor(if (isRecording) 0xFFE53935.toInt() else 0xFF1976D2.toInt())
            textSize = 15f
            setPadding(0, padding / 2, 0, padding / 2)
            setOnClickListener {
                hideMenu()
                if (isRecording) stopRecording() else startRecording()
            }
        }
        layout.addView(btnRecord)

        // 回放上一次
        val btnPlay = TextView(this).apply {
            text = "▶ 回放上次"
            setTextColor(0xFF2E7D32.toInt())
            textSize = 15f
            setPadding(0, padding / 2, 0, padding / 2)
            visibility = if (lastCompletedScriptId > 0 && !isRecording) View.VISIBLE else View.GONE
            setOnClickListener {
                hideMenu()
                playLastScript()
            }
        }
        layout.addView(btnPlay)

        // 分隔线
        layout.addView(View(this).apply {
            setBackgroundColor(0x1F000000)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        val btnStop = TextView(this).apply {
            text = "✕ 关闭服务"
            setTextColor(0xFF666666.toInt())
            textSize = 15f
            setPadding(0, padding / 2, 0, padding / 2)
            setOnClickListener {
                hideMenu()
                if (isRecording) stopRecording()
                stopSelf()
            }
        }
        layout.addView(btnStop)

        val menuW = (150 * dm.density).toInt()
        val fabParams = floatingView?.layoutParams as? WindowManager.LayoutParams
        val menuParams = WindowManager.LayoutParams(
            menuW, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (fabParams?.x ?: 0) - menuW - 8
            y = fabParams?.y ?: 0
        }

        layout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hideMenu()
                true
            } else false
        }

        menuView = layout
        windowManager?.addView(layout, menuParams)
    }

    private fun hideMenu() {
        isMenuExpanded = false
        menuView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            menuView = null
        }
    }

    // ==================== 悬浮球回放 ====================

    @SuppressLint("SetTextI18n")
    private fun playLastScript() {
        val scriptId = lastCompletedScriptId
        if (scriptId <= 0) {
            Toast.makeText(this, "没有可回放的脚本", Toast.LENGTH_SHORT).show()
            return
        }
        val a11y = OpAccessibilityService.instance
        if (a11y == null) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
            return
        }
        if (isPlaybackActive) {
            Toast.makeText(this, "正在回放中", Toast.LENGTH_SHORT).show()
            return
        }

        isPlaybackActive = true
        wakeUpScreen()
        showPlaybackStatusBar()

        scope.launch {
            val db = AppDatabase.getInstance(this@FloatingControlService)
            val script = kotlinx.coroutines.withContext(Dispatchers.IO) {
                db.scriptDao().getScriptById(scriptId)
            }
            val actions = kotlinx.coroutines.withContext(Dispatchers.IO) {
                db.scriptDao().getActionsByScriptId(scriptId)
            }

            if (script == null || actions.isEmpty()) {
                Toast.makeText(this@FloatingControlService, "脚本为空", Toast.LENGTH_SHORT).show()
                isPlaybackActive = false
                removeStatusView()
                releaseWakeLocks()
                return@launch
            }

            updateNotification("回放中: ${script.name}")
            a11y.playScript(
                actions = actions,
                loopCount = script.loopCount,
                loopInterval = script.loopInterval,
                onComplete = {
                    updateNotification("回放完成")
                    Log.d(TAG, "悬浮球回放完成: ${script.name}")
                    isPlaybackActive = false
                    removeStatusView()
                    releaseWakeLocks()
                }
            )
        }
    }

    // ==================== 录制控制 ====================

    @SuppressLint("SetTextI18n")
    private fun startRecording() {
        if (isRecording) return
        isRecording = true
        isRecordingActive = true
        recordedStepCount = 0
        recordedActions.clear()
        lastRecordTime = 0L

        (floatingView as? ImageView)?.setImageResource(R.drawable.ic_stop)
        updateNotification("正在录制... 0 步")
        showRecordingStatusBar()

        scope.launch {
            val db = AppDatabase.getInstance(this@FloatingControlService)
            currentScriptId = kotlinx.coroutines.withContext(Dispatchers.IO) {
                db.scriptDao().insertScript(
                    Script(name = "录制_${System.currentTimeMillis() % 10000}")
                )
            }

            if (Build.VERSION.SDK_INT >= 33 && OpAccessibilityService.instance != null) {
                startMotionEventMode()
            } else {
                switchToOverlayMode()
            }
        }
    }

    private fun startMotionEventMode() {
        recordMode = RecordMode.MOTION_EVENT
        OpAccessibilityService.onActionRecorded = { action ->
            val fixedAction = action.copy(scriptId = currentScriptId)
            // 回写上一个操作的 delayAfter
            if (recordedActions.isNotEmpty() && fixedAction.delayAfter == 0L) {
                val now = System.currentTimeMillis()
                val delaySinceLast = if (lastRecordTime > 0) (now - lastRecordTime) else 0L
                if (delaySinceLast > 0) {
                    val lastIndex = recordedActions.lastIndex
                    recordedActions[lastIndex] = recordedActions[lastIndex].copy(
                        delayAfter = delaySinceLast.coerceIn(0, 20000)
                    )
                }
            }
            lastRecordTime = System.currentTimeMillis()
            recordedActions.add(fixedAction)
            recordedStepCount++
            updateNotification("正在录制... $recordedStepCount 步")
        }
        OpAccessibilityService.instance?.startRecording(currentScriptId)

        scope.launch {
            delay(MOTION_EVENT_CHECK_DELAY)
            if (!isRecording) return@launch
            if (!OpAccessibilityService.motionEventReceived) {
                OpAccessibilityService.instance?.stopRecording()
                OpAccessibilityService.onActionRecorded = null
                switchToOverlayMode()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun switchToOverlayMode() {
        recordMode = RecordMode.OVERLAY
        showRecordingOverlay()
        updateRecordingStatusText("● REC 覆盖层模式")
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        isRecordingActive = false
        isForwarding = false

        (floatingView as? ImageView)?.setImageResource(R.drawable.ic_record)

        if (recordMode == RecordMode.MOTION_EVENT) {
            OpAccessibilityService.instance?.stopRecording()
            OpAccessibilityService.onActionRecorded = null
        }

        removeRecordingOverlay()
        removeStatusView()

        val actionsToSave = recordedActions.toList()
        val scriptId = currentScriptId
        scope.launch {
            val db = AppDatabase.getInstance(this@FloatingControlService)
            if (scriptId > 0 && actionsToSave.isNotEmpty()) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    db.scriptDao().insertActions(actionsToSave)
                }
                lastCompletedScriptId = scriptId
                Log.d(TAG, "已保存 ${actionsToSave.size} 个操作到数据库 (scriptId=$scriptId)")
            } else if (scriptId > 0) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    db.scriptDao().deleteScriptById(scriptId)
                }
                Log.d(TAG, "无操作录制，已删除空脚本")
            }
            recordedActions.clear()
        }

        updateNotification("录制完成，$recordedStepCount 步")
        onStopRequested?.invoke()
        Log.d(TAG, "录制停止，$recordedStepCount 步")
    }

    // ==================== 覆盖层录制 ====================

    @SuppressLint("ClickableViewAccessibility")
    private fun showRecordingOverlay() {
        if (recordingOverlay != null) return

        val overlay = View(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        overlay.setOnTouchListener { _, event ->
            if (!isRecording || isForwarding) return@setOnTouchListener false
            handleOverlayTouch(event)
            true
        }

        recordingOverlay = overlay
        windowManager?.addView(overlay, params)
        bringViewsAboveOverlay()
    }

    private fun removeRecordingOverlay() {
        recordingOverlay?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            recordingOverlay = null
        }
    }

    /** 把悬浮球和状态条提到覆盖层之上 */
    private fun bringViewsAboveOverlay() {
        reAddViewOnTop(floatingView)
        reAddViewOnTop(statusView)
    }

    private fun reAddViewOnTop(view: View?) {
        val v = view ?: return
        val params = (v.layoutParams as? WindowManager.LayoutParams) ?: return
        try {
            windowManager?.removeView(v)
            windowManager?.addView(v, params)
        } catch (e: Exception) {
            Log.e(TAG, "reAddViewOnTop 失败", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleOverlayTouch(event: MotionEvent) {
        val rawX = event.rawX
        val rawY = event.rawY

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = rawX
                touchDownY = rawY
                touchDownTime = System.currentTimeMillis()
                lastMoveX = rawX
                lastMoveY = rawY
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

                // 把和上一个操作的间隔写回上一个操作的 delayAfter
                val now = System.currentTimeMillis()
                if (lastRecordTime > 0 && recordedActions.isNotEmpty()) {
                    val delaySinceLast = (now - lastRecordTime).coerceIn(0, 20000)
                    val lastIndex = recordedActions.lastIndex
                    recordedActions[lastIndex] = recordedActions[lastIndex].copy(
                        delayAfter = delaySinceLast
                    )
                }

                val action = when {
                    distance > SWIPE_THRESHOLD_PX -> ScriptAction(
                        scriptId = currentScriptId,
                        order = recordedActions.size,
                        type = ActionType.SWIPE,
                        x = touchDownX, y = touchDownY,
                        x2 = upX, y2 = upY,
                        duration = 300L,
                        delayAfter = 0L
                    )
                    elapsed > LONG_PRESS_MS -> ScriptAction(
                        scriptId = currentScriptId,
                        order = recordedActions.size,
                        type = ActionType.LONG_PRESS,
                        x = touchDownX, y = touchDownY,
                        duration = elapsed.coerceIn(500L, 3000L),
                        delayAfter = 0L
                    )
                    else -> ScriptAction(
                        scriptId = currentScriptId,
                        order = recordedActions.size,
                        type = ActionType.TAP,
                        x = touchDownX, y = touchDownY,
                        duration = 100L,
                        delayAfter = 0L
                    )
                }

                lastRecordTime = System.currentTimeMillis()
                recordedActions.add(action)
                recordedStepCount++
                updateNotification("正在录制... $recordedStepCount 步")
                Log.d(TAG, "覆盖层录制: ${action.type} at (${action.x}, ${action.y})")

                forwardGesture(action)
            }
        }
    }

    /**
     * 转发手势：FLAG_NOT_TOUCHABLE 切换方案
     *
     * overlay 始终留在 WM 中，不 remove/add。
     * 1. 设 FLAG_NOT_TOUCHABLE = overlay 不吃触摸
     * 2. 延迟 50ms → dispatchGesture
     * 3. 延迟 150ms → 去掉 FLAG_NOT_TOUCHABLE = overlay 恢复拦截
     * 4. 安全超时 800ms 强制恢复
     */
    private fun forwardGesture(action: ScriptAction) {
        val a11y = OpAccessibilityService.instance
        if (a11y == null) {
            Log.w(TAG, "无障碍服务未运行，无法转发手势")
            return
        }

        isForwarding = true
        setOverlayTouchable(false)

        scope.launch {
            delay(50)
            if (!isRecording) { isForwarding = false; return@launch }

            val forwardAction = when (action.type) {
                ActionType.TAP -> action.copy(duration = 16L)
                ActionType.LONG_PRESS -> action.copy(duration = 500L)
                ActionType.SWIPE -> action.copy(duration = 100L)
                ActionType.HOME, ActionType.BACK, ActionType.WAIT -> action
            }
            a11y.dispatchSingleGesture(forwardAction)

            delay(150)
            if (!isRecording) { isForwarding = false; return@launch }
            setOverlayTouchable(true)
            isForwarding = false
        }

        // 防死锁
        scope.launch {
            delay(800)
            if (isForwarding && isRecording) {
                Log.w(TAG, "转发超时，强制恢复覆盖层")
                setOverlayTouchable(true)
                isForwarding = false
            }
        }
    }

    private fun setOverlayTouchable(touchable: Boolean) {
        val overlay = recordingOverlay ?: return
        val params = overlay.layoutParams as? WindowManager.LayoutParams ?: return
        if (touchable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            windowManager?.updateViewLayout(overlay, params)
        } catch (e: Exception) {
            Log.e(TAG, "setOverlayTouchable 失败", e)
        }
    }

    // ==================== 录制状态条（右上角小浮动条） ====================

    @SuppressLint("SetTextI18n")
    private fun showRecordingStatusBar() {
        val padding = (6 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xDD222222.toInt())
            setPadding(padding, padding, padding, padding)
            gravity = Gravity.CENTER_VERTICAL
        }

        val recText = TextView(this).apply {
            text = "●REC"
            setTextColor(0xFFFF4444.toInt())
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            id = View.generateViewId()
            setPadding(0, 0, padding, 0)
        }
        container.addView(recText)

        val btnHome = TextView(this).apply {
            text = " H "
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundColor(0x66000000.toInt())
            setPadding(padding / 2, 0, padding / 2, 0)
            setOnClickListener {
                recordSystemAction(ActionType.HOME)
                OpAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
        }
        container.addView(btnHome)

        val btnBack = TextView(this).apply {
            text = " B "
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundColor(0x66000000.toInt())
            setPadding(padding / 2, 0, padding / 2, 0)
            setOnClickListener {
                recordSystemAction(ActionType.BACK)
                OpAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
        }
        container.addView(btnBack)

        val dm = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = (4 * dm.density).toInt()
            x = (4 * dm.density).toInt()
        }

        statusView = container
        windowManager?.addView(container, params)
    }

    /** 记录系统操作（HOME/BACK） */
    @SuppressLint("SetTextI18n")
    private fun recordSystemAction(type: ActionType) {
        val now = System.currentTimeMillis()

        // 把和上一个操作的间隔写回上一个操作的 delayAfter
        if (lastRecordTime > 0 && recordedActions.isNotEmpty()) {
            val delaySinceLast = (now - lastRecordTime).coerceIn(0, 20000)
            val lastIndex = recordedActions.lastIndex
            recordedActions[lastIndex] = recordedActions[lastIndex].copy(
                delayAfter = delaySinceLast
            )
        }

        val action = ScriptAction(
            scriptId = currentScriptId,
            order = recordedActions.size,
            type = type,
            duration = 0L,
            delayAfter = 0L
        )
        lastRecordTime = System.currentTimeMillis()
        recordedActions.add(action)
        recordedStepCount++
        updateNotification("正在录制... $recordedStepCount 步")
        Log.d(TAG, "录制系统操作: $type")
    }

    /** 更新状态条文字 */
    private fun updateRecordingStatusText(text: String) {
        val container = statusView as? LinearLayout ?: return
        val recText = container.findViewById<View>(View.generateViewId()) as? TextView
        // 简单刷新
    }

    // ==================== 回放状态条（右上角，带 HOME/BACK） ====================

    @SuppressLint("SetTextI18n")
    private fun showPlaybackStatusBar() {
        val padding = (6 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xDD222222.toInt())
            setPadding(padding, padding, padding, padding)
            gravity = Gravity.CENTER_VERTICAL
        }

        val playText = TextView(this).apply {
            text = "▶ PLAY"
            setTextColor(0xFF4CAF50.toInt())
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, padding, 0)
        }
        container.addView(playText)

        val btnHome = TextView(this).apply {
            text = " H "
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundColor(0x66000000.toInt())
            setPadding(padding / 2, 0, padding / 2, 0)
            setOnClickListener {
                OpAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
        }
        container.addView(btnHome)

        val btnBack = TextView(this).apply {
            text = " B "
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundColor(0x66000000.toInt())
            setPadding(padding / 2, 0, padding / 2, 0)
            setOnClickListener {
                OpAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
        }
        container.addView(btnBack)

        val dm = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = (4 * dm.density).toInt()
            x = (4 * dm.density).toInt()
        }

        statusView = container
        windowManager?.addView(container, params)
    }

    // ==================== WakeLock 管理 ====================

    /** 唤醒屏幕 + 持有 CPU WakeLock，确保回放期间不休眠 */
    @SuppressLint("WakelockTimeout")
    private fun wakeUpScreen() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager

        // 先唤醒屏幕（如果息屏了）
        if (!pm.isInteractive) {
            screenWakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "OpRecorder:ScreenWake"
            ).apply {
                acquire(5000L) // 5 秒后自动释放，之后由 FLAG_KEEP_SCREEN_ON 保持
            }
            Log.d(TAG, "屏幕已唤醒")
        }

        // 持有 CPU WakeLock 防止休眠
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpRecorder:Playback")
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire()
            Log.d(TAG, "已获取 PARTIAL_WAKE_LOCK")
        }
    }

    private fun releaseWakeLocks() {
        try {
            if (screenWakeLock?.isHeld == true) screenWakeLock?.release()
        } catch (_: Exception) {}
        screenWakeLock = null

        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null

        Log.d(TAG, "WakeLock 已释放")
    }

    private fun removeStatusView() {
        statusView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            statusView = null
        }
    }
}
