package com.myfactory.forge.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import com.myfactory.forge.core.checkpoint.Checkpoint
import com.myfactory.forge.ui.screens.CheckpointsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckpointsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val manual = Checkpoint(
        id = "cp1",
        projectId = "p1",
        label = "Before refactor",
        createdAtMillis = 1_700_000_000_000L,
        fileCount = 12,
        archiveBytes = 2048,
        automatic = false,
    )

    private fun show(
        checkpoints: List<Checkpoint> = listOf(manual),
        storageBytes: Long = 4096,
        onCreate: () -> Unit = {},
        onRestore: (Checkpoint) -> Unit = {},
        onDelete: (Checkpoint) -> Unit = {},
    ) {
        compose.render {
            CheckpointsScreen(
                checkpoints = checkpoints,
                storageBytes = storageBytes,
                onCreate = onCreate,
                onRestore = onRestore,
                onDelete = onDelete,
            )
        }
    }

    @Test
    fun `the create button takes a checkpoint`() {
        var created = 0
        show(onCreate = { created++ })

        compose.label("Take a checkpoint").performClick()

        assertEquals(1, created)
    }

    @Test
    fun `restore asks first because it discards later work`() {
        var restored: Checkpoint? = null
        show(onRestore = { restored = it })

        compose.node("Restore").performClick()

        compose.label("Restore “Before refactor”? Files changed since then will be lost.")
            .assertExists()
        assertTrue("nothing may be restored before confirming", restored == null)

        compose.node("Confirm").performClick()
        assertEquals(manual, restored)
    }

    @Test
    fun `cancelling the restore dialog changes nothing`() {
        var restored: Checkpoint? = null
        show(onRestore = { restored = it })

        compose.node("Restore").performClick()
        compose.node("Cancel").performClick()

        assertTrue(restored == null)
    }

    @Test
    fun `delete removes the checkpoint`() {
        var deleted: Checkpoint? = null
        show(onDelete = { deleted = it })

        compose.node("Delete").performClick()

        assertEquals(manual, deleted)
    }

    @Test
    fun `the storage cost is shown so snapshots do not fill the phone silently`() {
        show(storageBytes = 5 * 1024 * 1024)

        compose.labelContaining("5 MB").assertExists()
    }

    @Test
    fun `an automatic checkpoint is badged as such`() {
        show(checkpoints = listOf(manual.copy(id = "cp2", automatic = true)))

        compose.label("Automatic").assertExists()
    }

    @Test
    fun `the empty state explains when checkpoints appear`() {
        show(checkpoints = emptyList())

        compose.labelContaining("No checkpoints yet").assertExists()
        compose.label("Take a checkpoint").assertExists()
    }
}
