package com.myfactory.forge.data.repository

import com.myfactory.forge.core.ai.ProviderConfig
import com.myfactory.forge.core.ai.ProviderId
import com.myfactory.forge.core.checkpoint.Checkpoint
import com.myfactory.forge.core.session.AuditCategory
import com.myfactory.forge.core.session.AuditEntry
import com.myfactory.forge.core.session.Project
import com.myfactory.forge.core.session.Session
import com.myfactory.forge.core.session.StoredMessage
import com.myfactory.forge.core.session.StoredRole
import com.myfactory.forge.core.util.Json
import com.myfactory.forge.data.db.AuditEntity
import com.myfactory.forge.data.db.CheckpointEntity
import com.myfactory.forge.data.db.ForgeDatabase
import com.myfactory.forge.data.db.MessageEntity
import com.myfactory.forge.data.db.ProjectEntity
import com.myfactory.forge.data.db.ProviderConfigEntity
import com.myfactory.forge.data.db.SessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.UUID

/**
 * The single place Room entities are translated to and from the plain models
 * in :core. Keeping the mapping here is what lets :core stay Android-free and
 * therefore fully unit-testable.
 */
class ForgeRepository(private val db: ForgeDatabase) {

    private val headerSerializer = MapSerializer(String.serializer(), String.serializer())

    // ------------------------------------------------------------- projects

    fun observeProjects(): Flow<List<Project>> =
        db.projects().observeAll().map { list -> list.map { it.toModel() } }

    suspend fun project(id: String): Project? = db.projects().byId(id)?.toModel()

    suspend fun saveProject(project: Project) = db.projects().upsert(project.toEntity())

    suspend fun touchProject(id: String, millis: Long = System.currentTimeMillis()) =
        db.projects().touch(id, millis)

    suspend fun deleteProject(project: Project) = db.projects().delete(project.toEntity())

    // ------------------------------------------------------------- sessions

    fun observeSessions(projectId: String): Flow<List<Session>> =
        db.sessions().observeForProject(projectId).map { list -> list.map { it.toModel() } }

    suspend fun session(id: String): Session? = db.sessions().byId(id)?.toModel()

    suspend fun saveSession(session: Session) = db.sessions().upsert(session.toEntity())

    suspend fun deleteSession(id: String) = db.sessions().delete(id)

    // ------------------------------------------------------------- messages

    fun observeMessages(sessionId: String): Flow<List<StoredMessage>> =
        db.messages().observeForSession(sessionId).map { list -> list.map { it.toModel() } }

    suspend fun messages(sessionId: String): List<StoredMessage> =
        db.messages().forSession(sessionId).map { it.toModel() }

    suspend fun saveMessage(message: StoredMessage) = db.messages().upsert(message.toEntity())

    // ------------------------------------------------------------ providers

    fun observeProviderConfigs(): Flow<List<ProviderConfig>> =
        db.providerConfigs().observeAll().map { list -> list.mapNotNull { it.toModel() } }

    suspend fun providerConfig(id: String): ProviderConfig? =
        db.providerConfigs().byId(id)?.toModel()

    suspend fun providerConfigs(): List<ProviderConfig> =
        db.providerConfigs().all().mapNotNull { it.toModel() }

    suspend fun saveProviderConfig(config: ProviderConfig) =
        db.providerConfigs().upsert(config.toEntity())

    suspend fun deleteProviderConfig(id: String) = db.providerConfigs().delete(id)

    // ---------------------------------------------------------- checkpoints

    fun observeCheckpoints(projectId: String): Flow<List<Checkpoint>> =
        db.checkpoints().observeForProject(projectId).map { list -> list.map { it.toModel() } }

    suspend fun checkpoint(id: String): Checkpoint? = db.checkpoints().byId(id)?.toModel()

    suspend fun saveCheckpoint(checkpoint: Checkpoint) =
        db.checkpoints().upsert(checkpoint.toEntity())

    suspend fun deleteCheckpoint(id: String) = db.checkpoints().delete(id)

    /** Returns the automatic checkpoints whose archives may now be removed. */
    suspend fun pruneAutomaticCheckpoints(projectId: String, keep: Int = 10): List<Checkpoint> {
        val stale = db.checkpoints().automaticBeyond(projectId, keep)
        stale.forEach { db.checkpoints().delete(it.id) }
        return stale.map { it.toModel() }
    }

    // ---------------------------------------------------------------- audit

    fun observeAudit(limit: Int = 500): Flow<List<AuditEntry>> =
        db.audit().observeRecent(limit).map { list -> list.map { it.toModel() } }

    suspend fun record(
        category: AuditCategory,
        summary: String,
        detail: String = "",
        projectId: String? = null,
        sessionId: String? = null,
    ) {
        db.audit().insert(
            AuditEntity(
                id = UUID.randomUUID().toString(),
                timestampMillis = System.currentTimeMillis(),
                category = category.name,
                summary = summary,
                detail = detail,
                projectId = projectId,
                sessionId = sessionId,
            ),
        )
    }

    suspend fun clearAudit() = db.audit().clear()

    // -------------------------------------------------------------- mapping

    private fun ProjectEntity.toModel() = Project(
        id, name, rootPath, createdAtMillis, lastOpenedMillis, gitRemote,
    )

    private fun Project.toEntity() = ProjectEntity(
        id, name, rootPath, createdAtMillis, lastOpenedMillis, gitRemote,
    )

    private fun SessionEntity.toModel() = Session(
        id, projectId, title, providerConfigId, createdAtMillis, updatedAtMillis,
        totalInputTokens, totalOutputTokens,
    )

    private fun Session.toEntity() = SessionEntity(
        id, projectId, title, providerConfigId, createdAtMillis, updatedAtMillis,
        totalInputTokens, totalOutputTokens,
    )

    private fun MessageEntity.toModel() = StoredMessage(
        id = id,
        sessionId = sessionId,
        // An unrecognised role means the row predates a rename; showing it as
        // a system note is better than dropping the user's history.
        role = runCatching { StoredRole.valueOf(role) }.getOrDefault(StoredRole.SYSTEM),
        text = text,
        createdAtMillis = createdAtMillis,
        toolCallsJson = toolCallsJson,
        toolResultsJson = toolResultsJson,
    )

    private fun StoredMessage.toEntity() = MessageEntity(
        id, sessionId, role.name, text, createdAtMillis, toolCallsJson, toolResultsJson,
    )

    /** Returns null when the stored provider name is no longer known. */
    private fun ProviderConfigEntity.toModel(): ProviderConfig? {
        val provider = ProviderId.fromNameOrNull(providerId) ?: return null
        val headers = runCatching {
            Json.storage.decodeFromString(headerSerializer, extraHeadersJson)
        }.getOrDefault(emptyMap())
        return ProviderConfig(
            id = id,
            providerId = provider,
            displayName = displayName,
            baseUrl = baseUrl,
            model = model,
            apiKeyAlias = apiKeyAlias,
            maxOutputTokens = maxOutputTokens,
            temperature = temperature,
            extraHeaders = headers,
            toolsEnabled = toolsEnabled,
        )
    }

    private fun ProviderConfig.toEntity() = ProviderConfigEntity(
        id = id,
        providerId = providerId.name,
        displayName = displayName,
        baseUrl = baseUrl,
        model = model,
        apiKeyAlias = apiKeyAlias,
        maxOutputTokens = maxOutputTokens,
        temperature = temperature,
        extraHeadersJson = Json.storage.encodeToString(headerSerializer, extraHeaders),
        toolsEnabled = toolsEnabled,
    )

    private fun CheckpointEntity.toModel() = Checkpoint(
        id, projectId, label, createdAtMillis, fileCount, archiveBytes, automatic, sessionId,
    )

    private fun Checkpoint.toEntity() = CheckpointEntity(
        id, projectId, label, createdAtMillis, fileCount, archiveBytes, automatic, sessionId,
    )

    private fun AuditEntity.toModel() = AuditEntry(
        id = id,
        timestampMillis = timestampMillis,
        category = runCatching { AuditCategory.valueOf(category) }
            .getOrDefault(AuditCategory.PERMISSION_DECISION),
        summary = summary,
        detail = detail,
        projectId = projectId,
        sessionId = sessionId,
    )
}
