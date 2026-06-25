package com.oprecorder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.oprecorder.data.AppDatabase
import com.oprecorder.data.Script
import com.oprecorder.databinding.ActivityMainBinding
import com.oprecorder.service.FloatingControlService
import com.oprecorder.service.OpAccessibilityService
import com.oprecorder.service.SchedulerService
import com.oprecorder.ui.ScriptAdapter
import com.oprecorder.util.ScheduleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: AppDatabase
    private lateinit var adapter: ScriptAdapter
    private var scripts = mutableListOf<Script>()
    private val actionCounts = mutableMapOf<Long, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getInstance(this)

        setupRecyclerView()
        setupFab()
        loadScripts()
    }

    override fun onResume() {
        super.onResume()
        loadScripts()
        // 不在这里弹权限检查！只在用户点操作时才检查
    }

    // ==================== 列表 ====================

    private fun setupRecyclerView() {
        adapter = ScriptAdapter(
            scripts = scripts,
            actionCounts = actionCounts,
            onPlay = { script -> playScript(script) },
            onSchedule = { script -> showScheduleDialog(script) },
            onEdit = { script -> openDetail(script.id) },
            onDelete = { script -> deleteScript(script) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun loadScripts() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                db.scriptDao().getAllScripts()
            }
            scripts.clear()
            scripts.addAll(list)

            // 加载每个脚本的操作步骤数
            actionCounts.clear()
            withContext(Dispatchers.IO) {
                for (script in list) {
                    actionCounts[script.id] = db.scriptDao().getActionCount(script.id)
                }
            }

            adapter.notifyDataSetChanged()

            binding.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    // ==================== FAB ====================

    private fun setupFab() {
        binding.fabRecord.setOnClickListener {
            if (!ensureOverlayPermission()) return@setOnClickListener
            if (!ensureAccessibilityService()) return@setOnClickListener
            startRecording()
        }

        binding.fabManual.setOnClickListener {
            createEmptyScript()
        }
    }

    // ==================== 录制 ====================

    private fun startRecording() {
        val intent = Intent(this, FloatingControlService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        binding.root.postDelayed({
            val recordIntent = Intent(this, FloatingControlService::class.java).apply {
                action = "ACTION_START_RECORDING"
            }
            startService(recordIntent)
        }, 500)

        Toast.makeText(this, "悬浮按钮已出现，点击它开始录制", Toast.LENGTH_LONG).show()
    }

    // ==================== 回放 ====================

    private fun playScript(script: Script) {
        if (!ensureAccessibilityService()) return

        lifecycleScope.launch {
            val actions = withContext(Dispatchers.IO) {
                db.scriptDao().getActionsByScriptId(script.id)
            }

            if (actions.isEmpty()) {
                Toast.makeText(this@MainActivity, "脚本无操作步骤", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val a11y = OpAccessibilityService.instance
            if (a11y == null) {
                Toast.makeText(this@MainActivity, "无障碍服务未启动，请先在设置中开启", Toast.LENGTH_LONG).show()
                return@launch
            }

            // 启动前台保活服务
            val schedulerIntent = Intent(this@MainActivity, SchedulerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(schedulerIntent)
            }

            moveTaskToBack(true)

            Toast.makeText(this@MainActivity, "1秒后开始执行，请勿操作手机", Toast.LENGTH_SHORT).show()

            binding.root.postDelayed({
                a11y.playScript(
                    actions = actions,
                    loopCount = script.loopCount,
                    loopInterval = script.loopInterval,
                    onProgress = { current, total ->
                        // 更新通知显示进度
                        val schedulerIntent = Intent(this@MainActivity, SchedulerService::class.java)
                        startService(schedulerIntent)
                    },
                    onComplete = {
                        // 回放结束后自动停止前台保活服务
                        stopService(Intent(this@MainActivity, SchedulerService::class.java))
                        // 显示完成提示（通过悬浮窗通知，因为 App 已到后台）
                        Log.d("MainActivity", "回放完成: ${script.name}")
                    }
                )
             }, 1000)
        }
    }

    // ==================== 定时调度 ====================

    private fun showScheduleDialog(script: Script) {
        val options = if (script.scheduled) {
            arrayOf("取消定时", "重新设置定时")
        } else {
            arrayOf("一次性", "按周", "自定义间隔")
        }
        AlertDialog.Builder(this)
            .setTitle("定时 - ${script.name}")
            .setItems(options) { _, which ->
                if (script.scheduled) {
                    when (which) {
                        0 -> cancelSchedule(script)
                        1 -> showScheduleTypeDialog(script)
                    }
                } else {
                    when (which) {
                        0 -> showTimePicker(script, "once")
                        1 -> showTimePicker(script, "daily")
                        2 -> showIntervalPicker(script)
                    }
                }
            }
            .show()
    }

    private fun showScheduleTypeDialog(script: Script) {
        val options = arrayOf("一次性", "按周", "自定义间隔")
        AlertDialog.Builder(this)
            .setTitle("设置定时 - ${script.name}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showTimePicker(script, "once")
                    1 -> showTimePicker(script, "daily")
                    2 -> showIntervalPicker(script)
                }
            }
            .show()
    }

    private fun cancelSchedule(script: Script) {
        AlertDialog.Builder(this)
            .setTitle("取消定时")
            .setMessage("确定要取消「${script.name}」的定时执行吗？\n脚本不会被删除。")
            .setPositiveButton("取消定时") { _, _ ->
                lifecycleScope.launch {
                    val updated = script.copy(scheduled = false)
                    withContext(Dispatchers.IO) {
                        ScheduleManager.cancel(this@MainActivity, script)
                        db.scriptDao().updateScript(updated)
                    }
                    Toast.makeText(this@MainActivity, "定时已取消", Toast.LENGTH_SHORT).show()
                    loadScripts()
                }
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun showTimePicker(script: Script, repeat: String) {
        val hour = script.scheduleHour.let { if (it < 0) 8 else it }
        val minute = script.scheduleMinute.let { if (it < 0) 0 else it }

        android.app.TimePickerDialog(
            this,
            { _, h, m ->
                saveSchedule(script, repeat, hour = h, minute = m, intervalMin = 0)
            },
            hour, minute, true
        ).show()
    }

    private fun showIntervalPicker(script: Script) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "间隔分钟数"
            setText("30")
        }
        AlertDialog.Builder(this)
            .setTitle("设置间隔")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val min = input.text.toString().toIntOrNull() ?: 30
                saveSchedule(script, "interval", hour = 0, minute = 0, intervalMin = min)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveSchedule(
        script: Script,
        repeat: String,
        hour: Int,
        minute: Int,
        intervalMin: Int
    ) {
        lifecycleScope.launch {
            val updated = script.copy(
                scheduled = true,
                scheduleHour = hour,
                scheduleMinute = minute,
                scheduleRepeat = repeat,
                scheduleIntervalMin = intervalMin
            )
            withContext(Dispatchers.IO) {
                db.scriptDao().updateScript(updated)
                ScheduleManager.schedule(this@MainActivity, updated)
            }

            val intent = Intent(this@MainActivity, SchedulerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            }

            Toast.makeText(
                this@MainActivity,
                "定时已设置: ${if (repeat == "interval") "每${intervalMin}分钟" else "$hour:$minute"}",
                Toast.LENGTH_SHORT
            ).show()
            loadScripts()
        }
    }

    // ==================== 脚本管理 ====================

    private fun createEmptyScript() {
        lifecycleScope.launch {
            val name = "脚本_${System.currentTimeMillis() % 10000}"
            val id = withContext(Dispatchers.IO) {
                db.scriptDao().insertScript(Script(name = name))
            }
            openDetail(id)
        }
    }

    private fun openDetail(scriptId: Long) {
        val intent = Intent(this, ScriptDetailActivity::class.java)
        intent.putExtra("SCRIPT_ID", scriptId)
        startActivity(intent)
    }

    private fun deleteScript(script: Script) {
        AlertDialog.Builder(this)
            .setTitle("删除脚本")
            .setMessage("确定要删除「${script.name}」吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        ScheduleManager.cancel(this@MainActivity, script)
                        db.scriptDao().deleteScriptById(script.id)
                    }
                    loadScripts()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 权限检查（只在用户操作时触发） ====================

    /**
     * 检查悬浮窗权限，未开启则弹窗引导
     * @return true=已有权限
     */
    private fun ensureOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("录制操作需要悬浮窗权限来显示控制按钮。")
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                }
                .setNegativeButton("取消", null)
                .show()
            return false
        }
        return true
    }

    /**
     * 检查无障碍服务是否运行
     * 优先看 OpAccessibilityService.instance（最可靠），兜底读 Settings.Secure
     * @return true=服务已在运行
     */
    private fun ensureAccessibilityService(): Boolean {
        // 方法1：直接检查服务实例是否存在（最可靠）
        if (OpAccessibilityService.instance != null) return true

        // 方法2：兜底读系统设置
        if (isAccessibilityEnabledInSettings()) return true

        // 服务未运行，弹窗引导
        AlertDialog.Builder(this)
            .setTitle("需要无障碍服务")
            .setMessage("录制和回放操作需要开启无障碍服务。\n\n设置路径：无障碍 → 操作录制器 → 开启")
            .setPositiveButton("去设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("取消", null)
            .show()
        return false
    }

    /**
     * 读取系统设置中的已启用无障碍服务列表
     */
    private fun isAccessibilityEnabledInSettings(): Boolean {
        try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val serviceName = "$packageName/com.oprecorder.service.OpAccessibilityService"
            return enabledServices.contains(serviceName)
        } catch (e: Exception) {
            return false
        }
    }
}
