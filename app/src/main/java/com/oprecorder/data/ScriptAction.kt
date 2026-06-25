package com.oprecorder.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 操作类型
 */
enum class ActionType {
    /** 点击 */
    TAP,
    /** 长按 */
    LONG_PRESS,
    /** 滑动（从起点到终点） */
    SWIPE,
    /** 按HOME键 */
    HOME,
    /** 按BACK键 */
    BACK,
    /** 等待延迟 */
    WAIT
}

/**
 * 单个操作动作：点击/长按/滑动/等待
 */
@Entity(
    tableName = "actions",
    foreignKeys = [ForeignKey(
        entity = Script::class,
        parentColumns = ["id"],
        childColumns = ["scriptId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("scriptId")]
)
data class ScriptAction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 所属脚本 ID */
    val scriptId: Long,

    /** 在脚本中的执行顺序 */
    val order: Int,

    /** 动作类型 */
    val type: ActionType,

    /** 起始 X 坐标 */
    val x: Float = 0f,

    /** 起始 Y 坐标 */
    val y: Float = 0f,

    /** 终止 X 坐标（仅 SWIPE） */
    val x2: Float = 0f,

    /** 终止 Y 坐标（仅 SWIPE） */
    val y2: Float = 0f,

    /** 按压持续时间（毫秒）：TAP 默认 100，LONG_PRESS 默认 500 */
    val duration: Long = 100L,

    /** 执行后延迟（毫秒） */
    val delayAfter: Long = 300L
)
