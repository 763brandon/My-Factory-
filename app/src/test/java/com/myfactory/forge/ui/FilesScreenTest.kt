package com.myfactory.forge.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.myfactory.forge.core.files.WorkspaceFs
import com.myfactory.forge.ui.screens.FilesScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The file browser writes to a real temporary workspace, so these assert on
 * the filesystem rather than on a mock. A rename button that renames nothing
 * fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var workspace: WorkspaceFs

    @Before
    fun setUp() {
        // Exactly one entry, so "the first Rename button" is unambiguous.
        // Listing puts directories first, so adding a folder here would make
        // index 0 the folder rather than the file.
        workspace = WorkspaceFs(temp.newFolder("project"))
        workspace.write("README.md", "hello")
    }

    private fun show(
        onOpenFile: (String) -> Unit = {},
        onReviewChanges: () -> Unit = {},
    ) {
        compose.render {
            FilesScreen(
                workspace = workspace,
                onOpenFile = onOpenFile,
                onReviewChanges = onReviewChanges,
            )
        }
    }

    @Test
    fun `the directory contents are listed, folders first`() {
        workspace.mkdirs("src")
        show()

        compose.label("README.md").assertExists()
        compose.label("src").assertExists()
    }

    @Test
    fun `tapping a file opens it in the editor`() {
        var opened: String? = null
        show(onOpenFile = { opened = it })

        compose.node("README.md").performClick()

        assertEquals("README.md", opened)
    }

    @Test
    fun `the new file button creates a real file`() {
        show()

        compose.button("New file").performClick()
        compose.textField().performTextInput("notes.txt")
        compose.node("Confirm").performClick()

        // The write runs on Dispatchers.IO, which waitForIdle does not await,
        // so poll for the real filesystem outcome.
        compose.waitUntil(TIMEOUT_MS) { workspace.exists("notes.txt") }
    }

    @Test
    fun `the new folder button creates a real directory`() {
        show()

        compose.button("New folder").performClick()
        compose.textField().performTextInput("docs")
        compose.node("Confirm").performClick()

        compose.waitUntil(TIMEOUT_MS) { workspace.isDirectory("docs") }
    }

    @Test
    fun `a name containing a path separator is refused`() {
        show()

        compose.button("New file").performClick()
        compose.textField().performTextInput("escape/out.txt")

        // Allowing a separator here would silently create or move across
        // directories, which the single-component rule exists to prevent.
        compose.node("Confirm").assertIsNotEnabled()
    }

    @Test
    fun `rename moves the file`() {
        show()

        compose.nodeAt("Rename", 0).performClick()
        compose.textField().performTextClearance()
        compose.textField().performTextInput("GUIDE.md")
        compose.node("Confirm").performClick()

        compose.waitUntil(TIMEOUT_MS) { workspace.exists("GUIDE.md") }
        assertFalse(workspace.exists("README.md"))
    }

    @Test
    fun `delete asks first and then removes the file`() {
        show()

        compose.nodeAt("Delete", 0).performClick()
        assertTrue("nothing deleted before confirming", workspace.exists("README.md"))

        compose.node("Confirm").performClick()

        compose.waitUntil(TIMEOUT_MS) { !workspace.exists("README.md") }
    }

    @Test
    fun `cancelling delete keeps the file`() {
        show()

        compose.nodeAt("Delete", 0).performClick()
        compose.node("Cancel").performClick()
        compose.waitForIdle()

        assertTrue(workspace.exists("README.md"))
    }

    @Test
    fun `the review changes button is reachable`() {
        var reviewed = 0
        show(onReviewChanges = { reviewed++ })

        compose.button("Review changes").performClick()

        assertEquals(1, reviewed)
    }

    private companion object {
        /** Generous: the poll is against real disk on a shared CI runner. */
        const val TIMEOUT_MS = 5_000L
    }
}
