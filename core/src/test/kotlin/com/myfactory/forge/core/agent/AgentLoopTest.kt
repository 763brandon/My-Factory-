package com.myfactory.forge.core.agent

import com.myfactory.forge.core.ai.AiError
import com.myfactory.forge.core.ai.AiProvider
import com.myfactory.forge.core.ai.ChatRequest
import com.myfactory.forge.core.ai.ModelInfo
import com.myfactory.forge.core.ai.ProviderConfig
import com.myfactory.forge.core.ai.ProviderId
import com.myfactory.forge.core.ai.Role
import com.myfactory.forge.core.ai.StopReason
import com.myfactory.forge.core.ai.StreamEvent
import com.myfactory.forge.core.ai.ToolCall
import com.myfactory.forge.core.files.WorkspaceFs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The approval gate is the security model of this app, so these tests exist to
 * prove one thing above all: a tool that requires approval does not run
 * without it, under any sequence of model output.
 */
class AgentLoopTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val config = ProviderConfig(
        id = "cfg",
        providerId = ProviderId.ANTHROPIC,
        displayName = "Test",
        baseUrl = "https://example.invalid",
        model = "test-model",
    )

    /** Replays a scripted sequence of turns, one per call to stream(). */
    private class ScriptedProvider(
        private val turns: List<List<StreamEvent>>,
    ) : AiProvider {
        var callCount = 0
            private set
        val requests = mutableListOf<ChatRequest>()

        override val id = ProviderId.ANTHROPIC
        override fun defaultBaseUrl() = "https://example.invalid"
        override fun fallbackModels() = listOf(ModelInfo("test-model"))
        override suspend fun listModels(config: ProviderConfig, apiKey: String?) =
            Result.success(fallbackModels())

        override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
            requests += request
            val turn = turns.getOrElse(callCount) {
                listOf(StreamEvent.Completed(StopReason.END_TURN))
            }
            callCount++
            turn.forEach { emit(it) }
        }
    }

    private class RecordingGate(
        private val decide: (ApprovalRequest) -> ApprovalDecision,
    ) : ApprovalGate {
        val seen = mutableListOf<ApprovalRequest>()
        override suspend fun request(request: ApprovalRequest): ApprovalDecision {
            seen += request
            return decide(request)
        }
    }

    private fun context(): ToolContext {
        val workspace = WorkspaceFs(temp.newFolder("project"))
        return ToolContext(workspace = workspace, shell = null, checkpoints = null)
    }

    private fun textTurn(text: String) = listOf(
        StreamEvent.TextDelta(text),
        StreamEvent.Completed(StopReason.END_TURN),
    )

    private fun toolTurn(name: String, args: String, id: String = "call_1") = listOf(
        StreamEvent.ToolCallStarted(id, name),
        StreamEvent.ToolCallCompleted(ToolCall(id, name, args)),
        StreamEvent.Completed(StopReason.TOOL_USE),
    )

    private fun loop(
        provider: AiProvider,
        gate: ApprovalGate,
        context: ToolContext,
        tools: List<AgentTool> = ToolRegistry.all(),
        maxIterations: Int = 25,
    ) = AgentLoop(
        provider = provider,
        config = config,
        apiKeyProvider = { "key" },
        tools = tools,
        approvals = gate,
        context = context,
        maxIterations = maxIterations,
    )

    @Test
    fun `a plain answer completes in one turn`() = runTest {
        val provider = ScriptedProvider(listOf(textTurn("Hello.")))
        val events = loop(provider, ApprovalGate.DENY_ALL, context())
            .run(AgentSessionState(), "hi").toList()

        assertEquals("Hello.", events.filterIsInstance<AgentEvent.TextDelta>().single().text)
        assertEquals(1, events.filterIsInstance<AgentEvent.TurnComplete>().size)
        assertEquals(1, provider.callCount)
    }

    @Test
    fun `a read-only tool runs without asking the user`() = runTest {
        val context = context()
        context.workspace.write("a.txt", "hello")
        val provider = ScriptedProvider(
            listOf(
                toolTurn("read_file", "{\"path\":\"a.txt\"}"),
                textTurn("It says hello."),
            ),
        )
        val gate = RecordingGate { ApprovalDecision.Approve }

        val events = loop(provider, gate, context).run(AgentSessionState(), "read it").toList()

        assertTrue("read_file must not prompt", gate.seen.isEmpty())
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertFalse(finished.outcome.isError)
        assertTrue(finished.outcome.content.contains("hello"))
    }

    @Test
    fun `a write is blocked until the user approves and then happens`() = runTest {
        val context = context()
        val provider = ScriptedProvider(
            listOf(
                toolTurn("write_file", "{\"path\":\"new.txt\",\"content\":\"written\"}"),
                textTurn("Done."),
            ),
        )
        val gate = RecordingGate { ApprovalDecision.Approve }

        val events = loop(provider, gate, context).run(AgentSessionState(), "write it").toList()

        assertEquals(1, gate.seen.size)
        assertEquals("write_file", gate.seen.single().toolName)
        assertEquals(1, events.filterIsInstance<AgentEvent.ApprovalRequired>().size)
        assertEquals("written", context.workspace.read("new.txt").text)
    }

    @Test
    fun `rejecting a write leaves the disk untouched and tells the model why`() = runTest {
        val context = context()
        val provider = ScriptedProvider(
            listOf(
                toolTurn("write_file", "{\"path\":\"nope.txt\",\"content\":\"x\"}"),
                textTurn("Understood."),
            ),
        )
        val gate = RecordingGate { ApprovalDecision.Reject("Not this file.") }

        val events = loop(provider, gate, context).run(AgentSessionState(), "write it").toList()

        assertFalse("the file must not exist", context.workspace.exists("nope.txt"))
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolRejected>().size)
        // No ToolStarted means execute() was never reached.
        assertTrue(events.filterIsInstance<AgentEvent.ToolStarted>().isEmpty())

        // The rejection is fed back so the model can respond to it.
        val followUp = provider.requests.last().messages.last()
        assertEquals(Role.TOOL, followUp.role)
        assertTrue(followUp.toolResults.single().isError)
        assertTrue(followUp.toolResults.single().content.contains("Not this file."))
    }

    @Test
    fun `always-allow suppresses the prompt for later calls to the same tool`() = runTest {
        val context = context()
        val provider = ScriptedProvider(
            listOf(
                toolTurn("write_file", "{\"path\":\"one.txt\",\"content\":\"1\"}", "c1"),
                toolTurn("write_file", "{\"path\":\"two.txt\",\"content\":\"2\"}", "c2"),
                textTurn("Both written."),
            ),
        )
        val gate = RecordingGate { ApprovalDecision.AlwaysAllowTool("write_file") }

        val events = loop(provider, gate, context).run(AgentSessionState(), "write both").toList()

        assertEquals("only the first call should prompt", 1, gate.seen.size)
        assertEquals("1", context.workspace.read("one.txt").text)
        assertEquals("2", context.workspace.read("two.txt").text)
        assertTrue(events.filterIsInstance<AgentEvent.ToolApproved>().first().remembered)
    }

    @Test
    fun `a session approval carried in state skips the prompt entirely`() = runTest {
        val context = context()
        val provider = ScriptedProvider(
            listOf(
                toolTurn("write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}"),
                textTurn("ok"),
            ),
        )
        val gate = RecordingGate { ApprovalDecision.Reject("should not be asked") }

        loop(provider, gate, context)
            .run(AgentSessionState(sessionApprovals = setOf("write_file")), "go")
            .toList()

        assertTrue(gate.seen.isEmpty())
        assertEquals("x", context.workspace.read("a.txt").text)
    }

    @Test
    fun `the approval request carries a diff the user can read`() = runTest {
        val context = context()
        context.workspace.write("a.txt", "before")
        val provider = ScriptedProvider(
            listOf(
                toolTurn(
                    "edit_file",
                    "{\"path\":\"a.txt\",\"old_string\":\"before\",\"new_string\":\"after\"}",
                ),
                textTurn("done"),
            ),
        )
        val gate = RecordingGate { ApprovalDecision.Approve }

        loop(provider, gate, context).run(AgentSessionState(), "edit").toList()

        val preview = gate.seen.single().previewDiff
        assertTrue("a diff is required for informed consent", preview != null)
        assertTrue(preview!!.contains("-before"))
        assertTrue(preview.contains("+after"))
    }

    @Test
    fun `a command approval shows the command line`() = runTest {
        val provider = ScriptedProvider(
            listOf(toolTurn("run_command", "{\"command\":\"ls -la\"}"), textTurn("done")),
        )
        val gate = RecordingGate { ApprovalDecision.Reject("no shell today") }

        loop(provider, gate, context()).run(AgentSessionState(), "run").toList()

        assertEquals("ls -la", gate.seen.single().command)
    }

    @Test
    fun `an unknown tool name is reported back instead of ending the run`() = runTest {
        val provider = ScriptedProvider(
            listOf(toolTurn("teleport", "{}"), textTurn("Sorry, my mistake.")),
        )

        val events = loop(provider, ApprovalGate.ALLOW_ALL, context())
            .run(AgentSessionState(), "go").toList()

        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertTrue(finished.outcome.isError)
        assertTrue(finished.outcome.content.contains("No tool named 'teleport'"))
        assertEquals(1, events.filterIsInstance<AgentEvent.TurnComplete>().size)
    }

    @Test
    fun `malformed tool arguments are reported back as an error`() = runTest {
        val provider = ScriptedProvider(
            listOf(toolTurn("read_file", "{not json"), textTurn("Retrying.")),
        )

        val events = loop(provider, ApprovalGate.ALLOW_ALL, context())
            .run(AgentSessionState(), "go").toList()

        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertTrue(finished.outcome.isError)
        assertTrue(finished.outcome.content.contains("not valid JSON"))
    }

    @Test
    fun `a tool that throws is caught and surfaced as a tool error`() = runTest {
        val exploding = object : AgentTool {
            override val spec = com.myfactory.forge.core.ai.ToolSpec(
                "boom",
                "throws",
                com.myfactory.forge.core.ai.JsonSchemas.obj(emptyMap()),
            )
            override val requiresApproval = false
            override fun describe(arguments: kotlinx.serialization.json.JsonObject) = "boom"
            override suspend fun execute(
                arguments: kotlinx.serialization.json.JsonObject,
                context: ToolContext,
            ): ToolOutcome = error("kaboom")
        }
        val provider = ScriptedProvider(listOf(toolTurn("boom", "{}"), textTurn("ok")))

        val events = loop(provider, ApprovalGate.ALLOW_ALL, context(), listOf(exploding))
            .run(AgentSessionState(), "go").toList()

        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertTrue(finished.outcome.isError)
        assertTrue(finished.outcome.content.contains("kaboom"))
        assertEquals(1, events.filterIsInstance<AgentEvent.TurnComplete>().size)
    }

    @Test
    fun `a provider failure ends the run without throwing`() = runTest {
        val provider = ScriptedProvider(
            listOf(listOf(StreamEvent.Failed(AiError.Auth("bad key")))),
        )

        val events = loop(provider, ApprovalGate.ALLOW_ALL, context())
            .run(AgentSessionState(), "go").toList()

        val failure = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failure.error is AiError.Auth)
        assertTrue(events.filterIsInstance<AgentEvent.TurnComplete>().isEmpty())
    }

    @Test
    fun `a model that loops forever is stopped by the iteration cap`() = runTest {
        // Every turn asks for another tool, so only the cap ends this.
        val repeating = List(30) { toolTurn("list_directory", "{}", "c$it") }
        val provider = ScriptedProvider(repeating)

        val events = loop(provider, ApprovalGate.ALLOW_ALL, context(), maxIterations = 4)
            .run(AgentSessionState(), "go").toList()

        assertEquals(4, provider.callCount)
        assertEquals(1, events.filterIsInstance<AgentEvent.IterationLimitReached>().size)
    }

    @Test
    fun `conversation state accumulates so the next request has full history`() = runTest {
        val context = context()
        context.workspace.write("a.txt", "hello")
        val provider = ScriptedProvider(
            listOf(toolTurn("read_file", "{\"path\":\"a.txt\"}"), textTurn("It says hello.")),
        )
        var latest = AgentSessionState()

        loop(provider, ApprovalGate.ALLOW_ALL, context)
            .run(AgentSessionState(), "read it") { latest = it }
            .toList()

        // user, assistant+toolcall, tool results, assistant answer
        assertEquals(4, latest.messages.size)
        assertEquals(Role.USER, latest.messages[0].role)
        assertEquals(Role.ASSISTANT, latest.messages[1].role)
        assertEquals(Role.TOOL, latest.messages[2].role)
        assertEquals(Role.ASSISTANT, latest.messages[3].role)
    }

    @Test
    fun `tools offered to the model match what the device can actually do`() {
        val noLinux = com.myfactory.forge.core.capability.CapabilityDetector.detect(
            com.myfactory.forge.core.capability.DeviceProfile.UNKNOWN.copy(
                freeDataBytes = 10 * 1024 * 1024,
            ),
        )
        val names = ToolRegistry.forCapabilities(noLinux).map { it.spec.name }

        assertFalse("do not advertise a shell that does not exist", "run_command" in names)
        assertTrue("read_file" in names)
        assertTrue("write_file" in names)
    }

    @Test
    fun `read-only mode offers no mutating tools at all`() {
        val full = com.myfactory.forge.core.capability.CapabilityDetector.detect(
            com.myfactory.forge.core.capability.DeviceProfile(
                supportedAbis = listOf("arm64-v8a"),
                totalRamBytes = 8L * 1024 * 1024 * 1024,
                availableRamBytes = 4L * 1024 * 1024 * 1024,
                cpuCores = 8,
                sdkInt = 34,
                flaggedLowRam = false,
                freeDataBytes = 16L * 1024 * 1024 * 1024,
                hasWebView = true,
                hasPtyLibrary = true,
                hasProotBinary = true,
            ),
        )
        val names = ToolRegistry.forCapabilities(full, allowWrites = false, allowCommands = false)
            .map { it.spec.name }

        assertTrue(names.none { it in setOf("write_file", "edit_file", "apply_patch", "delete_file", "run_command") })
    }

    @Test
    fun `every mutating tool declares that it needs approval`() {
        // A new tool added without this flag would silently bypass the gate.
        val mutating = setOf("write_file", "edit_file", "apply_patch", "delete_file", "run_command")
        val offenders = ToolRegistry.all()
            .filter { it.spec.name in mutating && !it.requiresApproval }

        assertTrue("these bypass the approval gate: $offenders", offenders.isEmpty())
    }
}
