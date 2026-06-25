package com.oprecorder.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.oprecorder.R
import com.oprecorder.data.ActionType
import com.oprecorder.data.ScriptAction

class ActionAdapter(
    private val actions: MutableList<ScriptAction>,
    private val onDelete: (ScriptAction) -> Unit,
    private val onEdit: (ScriptAction) -> Unit
) : RecyclerView.Adapter<ActionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvType: TextView = view.findViewById(R.id.tv_action_type)
        val tvDetail: TextView = view.findViewById(R.id.tv_action_detail)
        val tvDelay: TextView = view.findViewById(R.id.tv_action_delay)
        val btnEdit: ImageButton = view.findViewById(R.id.btn_action_edit)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_action_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_action, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val action = actions[position]

        // 类型标签
        holder.tvType.text = when (action.type) {
            ActionType.TAP -> "点击"
            ActionType.LONG_PRESS -> "长按"
            ActionType.SWIPE -> "滑动"
            ActionType.HOME -> "主页"
            ActionType.BACK -> "返回"
            ActionType.WAIT -> "等待"
        }

        // 详情
        holder.tvDetail.text = when (action.type) {
            ActionType.TAP -> "坐标(${action.x.toInt()}, ${action.y.toInt()}) ${action.duration}ms"
            ActionType.LONG_PRESS -> "坐标(${action.x.toInt()}, ${action.y.toInt()}) ${action.duration}ms"
            ActionType.SWIPE -> "(${action.x.toInt()},${action.y.toInt()})→(${action.x2.toInt()},${action.y2.toInt()}) ${action.duration}ms"
            ActionType.HOME -> "按HOME键"
            ActionType.BACK -> "按BACK键"
            ActionType.WAIT -> "等待 ${action.duration}ms"
        }

        // 延迟
        holder.tvDelay.text = "+${action.delayAfter}ms"

        holder.btnEdit.setOnClickListener { onEdit(action) }
        holder.btnDelete.setOnClickListener { onDelete(action) }
    }

    override fun getItemCount() = actions.size
}
