package com.myfactory.forge.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY lastOpenedMillis DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun byId(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Query("UPDATE projects SET lastOpenedMillis = :millis WHERE id = :id")
    suspend fun touch(id: String, millis: Long)

    @Delete
    suspend fun delete(project: ProjectEntity)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE projectId = :projectId ORDER BY updatedAtMillis DESC")
    fun observeForProject(projectId: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAtMillis ASC")
    fun observeForSession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAtMillis ASC")
    suspend fun forSession(sessionId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)
}

@Dao
interface ToolInvocationDao {
    @Query("SELECT * FROM tool_invocations WHERE sessionId = :sessionId ORDER BY startedAtMillis ASC")
    fun observeForSession(sessionId: String): Flow<List<ToolInvocationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(invocation: ToolInvocationEntity)
}

@Dao
interface ProviderConfigDao {
    @Query("SELECT * FROM provider_configs ORDER BY displayName ASC")
    fun observeAll(): Flow<List<ProviderConfigEntity>>

    @Query("SELECT * FROM provider_configs")
    suspend fun all(): List<ProviderConfigEntity>

    @Query("SELECT * FROM provider_configs WHERE id = :id")
    suspend fun byId(id: String): ProviderConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: ProviderConfigEntity)

    @Query("DELETE FROM provider_configs WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface CheckpointDao {
    @Query("SELECT * FROM checkpoints WHERE projectId = :projectId ORDER BY createdAtMillis DESC")
    fun observeForProject(projectId: String): Flow<List<CheckpointEntity>>

    @Query("SELECT * FROM checkpoints WHERE id = :id")
    suspend fun byId(id: String): CheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(checkpoint: CheckpointEntity)

    @Query("DELETE FROM checkpoints WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Automatic checkpoints accumulate quickly. This keeps the newest [keep]
     * of them per project and reports which archives may now be deleted.
     */
    @Query(
        """
        SELECT * FROM checkpoints
        WHERE projectId = :projectId AND automatic = 1
        ORDER BY createdAtMillis DESC
        LIMIT -1 OFFSET :keep
        """,
    )
    suspend fun automaticBeyond(projectId: String, keep: Int): List<CheckpointEntity>
}

@Dao
interface AuditDao {
    @Query("SELECT * FROM audit_log ORDER BY timestampMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AuditEntity)

    @Query("DELETE FROM audit_log")
    suspend fun clear()

    @Query("DELETE FROM audit_log WHERE timestampMillis < :cutoffMillis")
    suspend fun pruneBefore(cutoffMillis: Long)
}
