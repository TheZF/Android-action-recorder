package com.oprecorder

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.oprecorder.data.ActionType
import com.oprecorder.data.AppDatabase
import com.oprecorder.data.Script
import com.oprecorder.data.ScriptAction
import com.oprecorder.databinding.ActivityScriptDetailBinding
import com.oprecorder.ui.ActionAdapter
import com.oprecorder.util.ScheduleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScriptDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScriptDetailBinding
    private lateinit var db: AppDatabase
    private lateinit var actionAdapter: ActionAdapter

    private var scriptId: Long = -1
    private var currentScript: Script? = null
    private var actions = mutableListOf<ScriptAction>()

    // 当前选择的定时时间
    private var selectedHour: Int = 8
    private var selectedMinute: Int = 0

    // 星期选择 chips 引用
    private val dayChips by lazy {
        listOf(
            binding.chipMon, binding.chipTue, binding.chipWed,
            binding.chipThu, binding.chipFri, binding.chipSat, binding.chipSun
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScriptDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getInstance(this)
        scriptId = intent.getLongExtra("SCRIPT_ID", -1)
        if (scriptId <= 0) {
            finish()
            return
        }

        setupViews()
        loadData()
    }

    private fun setupViews() {
        // 返回按钮
        binding.btnBack.setOnClickListener { finish() }

        // 操作列表
        actionAdapter = ActionAdapter(
            actions = actions,
            onDelete = { action -> deleteAction(action) },
            onEdit = { action -> editActionDialog(action) }
        )
        binding.rvActions.layoutManager = LinearLayoutManager(this)
        binding.rvActions.adapter = actionAdapter

        // 保存按钮
        binding.btnSave.setOnClickListener { saveScript() }

        // 添加操作按钮
        binding.btnAddTap.setOnClickListener { addAction(ActionType.TAP) }
        binding.btnAddLongPress.setOnClickListener { addAction(ActionType.LONG_PRESS) }
        binding.btnAddSwipe.setOnClickListener { addAction(ActionType.SWIPE) }
        binding.btnAddWait.setOnClickListener { addAction(ActionType.WAIT) }
        binding.btnAddHome.setOnClickListener { addAction(ActionType.HOME) }
        binding.btnAddBack.setOnClickListener { addAction(ActionType.BACK) }

        // 循环设置
        binding.switchLoop.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutLoopSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // 定时调度
        binding.switchSchedule.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutScheduleSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // 重复类型下拉框
        val repeatOptions = arrayOf("一次性", "按周", "自定义间隔")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, repeatOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerRepeat.adapter = adapter

        // 重复类型切换时显示/隐藏星期选择
        binding.spinnerRepeat.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                binding.layoutWeekDays.visibility = if (position == 1) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 点击时间文字弹出时间选择器
        binding.tvScheduleTime.setOnClickListener {
            showTimePicker()
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            // 加载脚本信息
            currentScript = withContext(Dispatchers.IO) {
                db.scriptDao().getScriptById(scriptId)
            }

            currentScript?.let { script ->
                binding.etName.setText(script.name)
                binding.switchLoop.isChecked = script.loopCount != 1
                binding.etLoopCount.setText(
                    if (script.loopCount <= 0) "0" else script.loopCount.toString()
                )
                binding.etLoopInterval.setText(script.loopInterval.toString())

                binding.switchSchedule.isChecked = script.scheduled
                binding.spinnerRepeat.setSelection(
                    when (script.scheduleRepeat) {
                        "daily" -> 1
                        "interval" -> 2
                        else -> 0
                    }
                )
                binding.etIntervalMin.setText(script.scheduleIntervalMin.toString())

                // 设置星期选择
                setDaysFromMask(script.scheduleDays)

                // 恢复时间选择
                if (script.scheduleHour >= 0 && script.scheduleMinute >= 0) {
                    selectedHour = script.scheduleHour
                    selectedMinute = script.scheduleMinute
                }
                updateTimeDisplay()
            }

            // 加载操作列表
            val list = withContext(Dispatchers.IO) {
                db.scriptDao().getActionsByScriptId(scriptId)
            }
            actions.clear()
            actions.addAll(list)
            actionAdapter.notifyDataSetChanged()

            updateSummary()
        }
    }

    // ==================== 时间选择器 ====================

    private fun showTimePicker() {
        TimePickerDialog(
            this,
            { _, hour, minute ->
                selectedHour = hour
                selectedMinute = minute
                updateTimeDisplay()
            },
            selectedHour, selectedMinute, true
        ).show()
    }

    private fun updateTimeDisplay() {
        binding.tvScheduleTime.text = String.format("%02d:%02d", selectedHour, selectedMinute)
    }

    // ==================== 操作管理 ====================

    private fun addAction(type: ActionType) {
        val action = ScriptAction(
            scriptId = scriptId,
            order = actions.size,
            type = type,
            x = 540f,
            y = 960f,
            x2 = 540f,
            y2 = 600f,
            duration = when (type) {
                ActionType.TAP -> 100L
                ActionType.LONG_PRESS -> 500L
                ActionType.SWIPE -> 300L
                ActionType.HOME -> 0L
                ActionType.BACK -> 0L
                ActionType.WAIT -> 1000L
            },
            delayAfter = 300L
        )
        editActionDialog(action, isNew = true)
    }

    private fun editActionDialog(action: ScriptAction, isNew: Boolean = false) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_action, null)
        val etX = dialogView.findViewById<android.widget.EditText>(R.id.et_x)
        val etY = dialogView.findViewById<android.widget.EditText>(R.id.et_y)
        val etX2 = dialogView.findViewById<android.widget.EditText>(R.id.et_x2)
        val etY2 = dialogView.findViewById<android.widget.EditText>(R.id.et_y2)
        val etDuration = dialogView.findViewById<android.widget.EditText>(R.id.et_duration)
        val etDelay = dialogView.findViewById<android.widget.EditText>(R.id.et_delay)
        val layoutSwipe = dialogView.findViewById<View>(R.id.layout_swipe)

        val needsCoords = action.type != ActionType.WAIT && action.type != ActionType.HOME && action.type != ActionType.BACK
        layoutSwipe.visibility = if (action.type == ActionType.SWIPE) View.VISIBLE else View.GONE
        etX.visibility = if (needsCoords) View.VISIBLE else View.GONE
        etY.visibility = if (needsCoords) View.VISIBLE else View.GONE
        etDuration.visibility = if (needsCoords || action.type == ActionType.WAIT) View.VISIBLE else View.GONE

        etX.setText(action.x.toInt().toString())
        etY.setText(action.y.toInt().toString())
        etX2.setText(action.x2.toInt().toString())
        etY2.setText(action.y2.toInt().toString())
        etDuration.setText(action.duration.toString())
        etDelay.setText(action.delayAfter.toString())

        val title = when (action.type) {
            ActionType.TAP -> "编辑点击"
            ActionType.LONG_PRESS -> "编辑长按"
            ActionType.SWIPE -> "编辑滑动"
            ActionType.HOME -> "编辑主页键"
            ActionType.BACK -> "编辑返回键"
            ActionType.WAIT -> "编辑等待"
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                val updated = action.copy(
                    x = etX.text.toString().toFloatOrNull() ?: action.x,
                    y = etY.text.toString().toFloatOrNull() ?: action.y,
                    x2 = etX2.text.toString().toFloatOrNull() ?: action.x2,
                    y2 = etY2.text.toString().toFloatOrNull() ?: action.y2,
                    duration = etDuration.text.toString().toLongOrNull() ?: action.duration,
                    delayAfter = etDelay.text.toString().toLongOrNull() ?: action.delayAfter,
                    scriptId = scriptId,
                    order = if (isNew) actions.size else action.order
                )

                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        if (isNew) {
                            db.scriptDao().insertAction(updated)
                        } else {
                            db.scriptDao().updateAction(updated)
                        }
                    }
                    loadData()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteAction(action: ScriptAction) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.scriptDao().deleteAction(action)
            }
            loadData()
        }
    }

    // ==================== 保存 ====================

    private fun saveScript() {
        val name = binding.etName.text.toString().ifBlank { "未命名脚本" }
        val loopCount = if (binding.switchLoop.isChecked) {
            binding.etLoopCount.text.toString().toIntOrNull() ?: 1
        } else 1
        val loopInterval = binding.etLoopInterval.text.toString().toLongOrNull() ?: 1000L

        val scheduled = binding.switchSchedule.isChecked
        val repeatType = when (binding.spinnerRepeat.selectedItemPosition) {
            1 -> "daily"
            2 -> "interval"
            else -> "once"
        }
        val intervalMin = binding.etIntervalMin.text.toString().toIntOrNull() ?: 0
        val scheduleDays = getDaysMask()

        lifecycleScope.launch {
            val script = currentScript?.copy(
                name = name,
                loopCount = loopCount,
                loopInterval = loopInterval,
                scheduled = scheduled,
                scheduleHour = selectedHour,
                scheduleMinute = selectedMinute,
                scheduleRepeat = repeatType,
                scheduleIntervalMin = intervalMin,
                scheduleDays = scheduleDays
            ) ?: return@launch

            withContext(Dispatchers.IO) {
                db.scriptDao().updateScript(script)
                if (scheduled) {
                    ScheduleManager.schedule(this@ScriptDetailActivity, script)
                } else {
                    ScheduleManager.cancel(this@ScriptDetailActivity, script)
                }
            }

            Toast.makeText(this@ScriptDetailActivity, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ==================== 星期选择辅助 ====================

    /** 从位掩码设置 chip 选中状态 */
    private fun setDaysFromMask(mask: Int) {
        dayChips.forEachIndexed { index, chip ->
            chip.isChecked = (mask and (1 shl index)) != 0
        }
    }

    /** 从 chip 选中状态计算位掩码 */
    private fun getDaysMask(): Int {
        var mask = 0
        dayChips.forEachIndexed { index, chip ->
            if (chip.isChecked) mask = mask or (1 shl index)
        }
        // 如果一个都没选，默认全选
        return if (mask == 0) 127 else mask
    }

    private fun updateSummary() {
        binding.tvActionCount.text = "${actions.size} 个操作"
        val totalDuration = actions.sumOf { it.duration + it.delayAfter }
        binding.tvTotalDuration.text = "预计时长: ${totalDuration}ms"
    }
}
