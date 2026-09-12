package com.myfactory.forge.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.myfactory.forge.core.session.Project
import com.myfactory.forge.ui.screens.ProjectsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Presses the controls on the projects list and asserts what actually
 * happened.
 *
 * These run on the JVM under Robolectric rather than on a device, so they
 * execute on every push. A button that stops doing anything fails the build
 * here rather than being discovered by a user.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val sample = Project(
        id = "p1",
        name = "Demo project",
        rootPath = "/data/projects/p1",
        createdAtMillis = 1_700_000_000_000L,
        lastOpenedMillis = 1_700_000_000_000L,
    )

    private fun show(
        projects: List<Project> = listOf(sample),
        onOpen: (Project) -> Unit = {},
        onCreate: (String) -> Unit = {},
        onDelete: (Project) -> Unit = {},
    ) {
        compose.render {
            ProjectsScreen(
                projects = projects,
                onOpen = onOpen,
                onCreate = onCreate,
                onDelete = onDelete,
            )
        }
    }

    @Test
    fun `tapping a project opens it`() {
        var opened: Project? = null
        show(onOpen = { opened = it })

        compose.node("Demo project").performClick()

        assertEquals(sample, opened)
    }

    @Test
    fun `the new project button opens a dialog and create is blocked until a name is typed`() {
        show(projects = emptyList())

        compose.label("New project").performClick()

        // The dialog's confirm button must not accept an empty name.
        compose.node("Create").assertIsNotEnabled()
        compose.textField().performTextInput("My app")
        compose.node("Create").assertIsEnabled()
    }

    @Test
    fun `create passes the typed name through`() {
        var created: String? = null
        show(projects = emptyList(), onCreate = { created = it })

        compose.label("New project").performClick()
        compose.textField().performTextInput("My app")
        compose.node("Create").performClick()

        assertEquals("My app", created)
    }

    @Test
    fun `cancel closes the dialog without creating anything`() {
        var created: String? = null
        show(projects = emptyList(), onCreate = { created = it })

        compose.label("New project").performClick()
        compose.textField().performTextInput("Discarded")
        compose.node("Cancel").performClick()

        compose.node("Create").assertDoesNotExist()
        assertTrue(created == null)
    }

    @Test
    fun `delete asks before removing anything`() {
        var deleted: Project? = null
        show(onDelete = { deleted = it })

        compose.node("Delete project").performClick()

        // Naming the project in the confirmation is the point: deleting the
        // wrong one is unrecoverable.
        compose.label("Delete “Demo project” and all its files? This cannot be undone.")
            .assertExists()
        assertTrue("nothing may be deleted before confirming", deleted == null)

        compose.node("Confirm").performClick()
        assertEquals(sample, deleted)
    }

    @Test
    fun `cancelling the delete dialog leaves the project alone`() {
        var deleted: Project? = null
        show(onDelete = { deleted = it })

        compose.node("Delete project").performClick()
        compose.node("Cancel").performClick()

        assertTrue(deleted == null)
    }

    @Test
    fun `the empty state explains what to do`() {
        show(projects = emptyList())

        compose.label("No projects yet").assertExists()
        compose.label("New project").assertExists()
    }
}
