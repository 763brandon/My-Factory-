package com.myfactory.forge.core.checkpoint

import kotlinx.serialization.Serializable

/**
 * A snapshot of the workspace at a point in time.
 *
 * Checkpoints are what make it safe to let an agent write to your files: the
 * user can always get back to where they were, without needing the project to
 * be a git repository.
 */
@Serializable
data class Checkpoint(
    val id: String,
    val projectId: String,
    val label: String,
    val createdAtMillis: Long,
    val fileCount: Int,
    val archiveBytes: Long,
    /** Set when a checkpoint was taken automatically before an agent write. */
    val automatic: Boolean = false,
    /** The session that triggered it, when there was one. */
    val sessionId: String? = null,
)
