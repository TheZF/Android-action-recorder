package com.oprecorder.data

import androidx.room.*

@Dao
interface ScriptDao {

    // ==================== 脚本 CRUD ====================

    @Insert
    suspend fun insertScript(script: Script): Long

    @Update
    suspend fun updateScript(script: Script)

    @Delete
    suspend fun deleteScript(script: Script)

    @Query("DELETE FROM scripts WHERE id = :scriptId")
    suspend fun deleteScriptById(scriptId: Long)

    @Query("SELECT * FROM scripts ORDER BY createdAt DESC")
    suspend fun getAllScripts(): List<Script>

    @Query("SELECT * FROM scripts WHERE id = :id")
    suspend fun getScriptById(id: Long): Script?

    @Query("SELECT * FROM scripts WHERE scheduled = 1")
    suspend fun getScheduledScripts(): List<Script>

    // ==================== 操作动作 CRUD ====================

    @Insert
    suspend fun insertAction(action: ScriptAction): Long

    @Insert
    suspend fun insertActions(actions: List<ScriptAction>)

    @Update
    suspend fun updateAction(action: ScriptAction)

    @Delete
    suspend fun deleteAction(action: ScriptAction)

    @Query("DELETE FROM actions WHERE scriptId = :scriptId")
    suspend fun deleteActionsByScriptId(scriptId: Long)

    @Query("SELECT * FROM actions WHERE scriptId = :scriptId ORDER BY `order` ASC")
    suspend fun getActionsByScriptId(scriptId: Long): List<ScriptAction>

    @Query("SELECT COUNT(*) FROM actions WHERE scriptId = :scriptId")
    suspend fun getActionCount(scriptId: Long): Int
}
