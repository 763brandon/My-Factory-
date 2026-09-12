package com.myfactory.forge.ui

import androidx.compose.ui.test.junit4.createComposeRule
import com.myfactory.forge.core.agent.ApprovalRequest
import com.myfactory.forge.ui.components.ApprovalSheet
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The approval sheet is the app's security boundary: nothing the agent does
 * to a file or the shell happens until one of these buttons is pressed.
 *
 * These tests press them and assert exactly one callback fires, and that the
 * sheet shows the user what they are consenting to rather than a bare
 * yes/no.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApprovalSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val newline: String = 10.toChar().toString()

    private fun writeRequest(diff: String? = null) = ApprovalRequest(
        callId = "call_1",
        toolName = "write_file",
        summary = "Write src/App.kt",
        arguments = JsonObject(emptyMap()),
        previewDiff = diff,
    )

    private fun commandRequest() = ApprovalRequest(
        callId = "call_2",
        toolName = "run_command",
        summary = "Run: rm -rf build",
        arguments = JsonObject(emptyMap()),
        command = "rm -rf build",
    )

    private class Decisions {
        var allowOnce = 0
        var allowSession = 0
        var deny = 0
        val total get() = allowOnce + allowSession + deny
    }

    private fun show(
        request: ApprovalRequest,
        allowSessionApprovals: Boolean = true,
        shellDescription: String? = null,
    ): Decisions {
        val decisions = Decisions()
        compose.render {
            ApprovalSheet(
                request = request,
                allowSessionApprovals = allowSessionApprovals,
                shellDescription = shellDescription,
                onAllowOnce = { decisions.allowOnce++ },
                onAllowSession = { decisions.allowSession++ },
                onDeny = { decisions.deny++ },
            )
        }
        return decisions
    }

    @Test
    fun `allow once fires exactly one decision`() {
        val decisions = show(writeRequest())

        compose.node("Allow once").invokeClick()

        assertEquals(1, decisions.allowOnce)
        assertEquals("exactly one decision may be emitted", 1, decisions.total)
    }

    @Test
    fun `allow for this session fires its own decision`() {
        val decisions = show(writeRequest())

        compose.node("Allow for this session").invokeClick()

        assertEquals(1, decisions.allowSession)
        assertEquals(1, decisions.total)
    }

    @Test
    fun `decline fires a rejection`() {
        val decisions = show(writeRequest())

        compose.node("Decline").invokeClick()

        assertEquals(1, decisions.deny)
        assertEquals(1, decisions.total)
    }

    @Test
    fun `session approval is hidden when the user has turned it off`() {
        show(writeRequest(), allowSessionApprovals = false)

        compose.node("Allow for this session").assertDoesNotExist()
        // The other two must still be there, or the sheet would be unanswerable.
        compose.node("Allow once").assertExists()
        compose.node("Decline").assertExists()
    }

    @Test
    fun `a write shows the diff so consent is informed`() {
        val diff = listOf(
            "--- src/App.kt",
            "+++ src/App.kt",
            "@@ -1 +1 @@",
            "-val greeting = OLD",
            "+val greeting = NEW",
        ).joinToString(newline)

        show(writeRequest(diff))

        compose.label("The agent wants to change a file.").assertExists()
        compose.labelContaining("-val greeting = OLD").assertExists()
        compose.labelContaining("+val greeting = NEW").assertExists()
    }

    @Test
    fun `a command shows the exact command line and where it runs`() {
        show(commandRequest(), shellDescription = "Alpine 3.20, mounted at /workspace")

        compose.label("The agent wants to run a command.").assertExists()
        compose.label("rm -rf build").assertExists()
        compose.labelContaining("Alpine 3.20").assertExists()
    }

    @Test
    fun `a request with no preview says so rather than showing nothing`() {
        show(writeRequest(diff = null))

        compose.label("No preview is available for this action.").assertExists()
    }

    @Test
    fun `nothing is decided until a button is pressed`() {
        val decisions = show(writeRequest("--- a" + newline + "+++ a"))

        compose.label("Approve this action?").assertExists()

        assertTrue("merely showing the sheet must decide nothing", decisions.total == 0)
    }
}
