package com.oprecorder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 脚本实体：一组录制或手动创建的操作序列
 */
@Entity(tableName = "scripts")
data class Script(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 脚本名称 */
    val name: String,

    /** 创建时间（时间戳毫秒） */
    val createdAt: Long = System.currentTimeMillis(),

    /** 循环播放次数，0=无限循环 */
    val loopCount: Int = 1,

    /** 循环间间隔（毫秒） */
    val loopInterval: Long = 1000L,

    /** 是否启用定时调度 */
    val scheduled: Boolean = false,

    /** 定时调度 - 小时（0-23） */
    val scheduleHour: Int = -1,

    /** 定时调度 - 分钟（0-59） */
    val scheduleMinute: Int = -1,

    /** 重复类型：once / daily / interval */
    val scheduleRepeat: String = "once",

    /** 间隔调度 - 间隔分钟数（仅 scheduleRepeat=interval 时有效） */
    val scheduleIntervalMin: Int = 0,

    /** 定时调度 - 星期选择位掩码（bit0=周一...bit6=周日，0=未选） */
    val scheduleDays: Int = 0,

    /** 上次执行时间 */
    val lastExecutedAt: Long = 0
)
