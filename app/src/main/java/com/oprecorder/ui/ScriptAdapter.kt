package com.oprecorder.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.oprecorder.R
import com.oprecorder.data.Script

class ScriptAdapter(
    private val scripts: MutableList<Script>,
    private val actionCounts: MutableMap<Long, Int>,   // scriptId → actionCount
    private val onPlay: (Script) -> Unit,
    private val onSchedule: (Script) -> Unit,
    private val onEdit: (Script) -> Unit,
    private val onDelete: (Script) -> Unit
) : RecyclerView.Adapter<ScriptAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_script_name)
        val tvInfo: TextView = view.findViewById(R.id.tv_script_info)
        val tvSchedule: TextView = view.findViewById(R.id.tv_schedule_info)
        val btnPlay: ImageButton = view.findViewById(R.id.btn_play)
        val btnSchedule: ImageButton = view.findViewById(R.id.btn_schedule)
        val btnEdit: ImageButton = view.findViewById(R.id.btn_edit)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_script, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val script = scripts[position]

        holder.tvName.text = script.name

        // 信息行：步骤数 + 循环
        val stepCount = actionCounts[script.id] ?: 0
        val loopText = if (script.loopCount <= 0) "无限循环" else "循环${script.loopCount}次"
        holder.tvInfo.text = "${stepCount}步 | $loopText"

        // 定时信息
        if (script.scheduled) {
            val scheduleText = when (script.scheduleRepeat) {
                "daily" -> {
                    val daysDesc = describeDays(script.scheduleDays)
                    "$daysDesc ${String.format("%02d:%02d", script.scheduleHour, script.scheduleMinute)}"
                }
                "interval" -> "每${script.scheduleIntervalMin}分钟"
                else -> "${String.format("%02d:%02d", script.scheduleHour, script.scheduleMinute)} 一次"
            }
            holder.tvSchedule.text = scheduleText
            holder.tvSchedule.visibility = View.VISIBLE
        } else {
            holder.tvSchedule.visibility = View.GONE
        }

        // 按钮事件
        holder.btnPlay.setOnClickListener { onPlay(script) }
        holder.btnSchedule.setOnClickListener { onSchedule(script) }
        holder.btnEdit.setOnClickListener { onEdit(script) }
        holder.btnDelete.setOnClickListener { onDelete(script) }

        // 已定时的脚本，闹钟按钮高亮
        val tint = if (script.scheduled) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt() // 绿/灰
        holder.btnSchedule.imageTintList = android.content.res.ColorStateList.valueOf(tint)
    }

    override fun getItemCount() = scripts.size

    /** 将 scheduleDays 位掩码转为可读描述 */
    private fun describeDays(days: Int): String {
        if (days == 0) return "未选星期"
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
}
