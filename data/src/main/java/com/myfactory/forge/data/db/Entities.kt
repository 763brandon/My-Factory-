package com.myfactory.forge.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val rootPath: String,
    val createdAtMillis: Long,
    val lastOpenedMillis: Long,
    val gitRemote: String?,
)

@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId")],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val providerConfigId: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val text: String,
    val createdAtMillis: Long,
    val toolCallsJson: String?,
    val toolResultsJson: String?,
)

@Entity(
    tableName = "tool_invocations",
    indices = [Index("sessionId"), Index("messageId")],
)
data class ToolInvocationEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val messageId: String,
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val status: String,
    val resultPreview: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long?,
    val requiredApproval: Boolean,
)

/**
 * A provider endpoint.
 *
 * There is no apiKey column, and there never should be. Only the alias under
 * which the key is filed in the platform keystore is persisted here.
 */
@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val displayName: String,
    val baseUrl: String,
    val model: String,
    val apiKeyAlias: String?,
    val maxOutputTokens: Int,
    val temperature: Double?,
    val extraHeadersJson: String,
    val toolsEnabled: Boolean,
)

@Entity(
    tableName = "checkpoints",
    indices = [Index("projectId")],
)
data class CheckpointEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val label: String,
    val createdAtMillis: Long,
    val fileCount: Int,
    val archiveBytes: Long,
    val automatic: Boolean,
    val sessionId: String?,
)

/**
 * The record of everything that touched files, ran commands, or left the
 * device. Append-only, and readable by the user in Settings.
 */
@Entity(
    tableName = "audit_log",
    indices = [Index("timestampMillis"), Index("category")],
)
data class AuditEntity(
    @PrimaryKey val id: String,
    val timestampMillis: Long,
    val category: String,
    val summary: String,
    val detail: String,
    val projectId: String?,
    val sessionId: String?,
)
