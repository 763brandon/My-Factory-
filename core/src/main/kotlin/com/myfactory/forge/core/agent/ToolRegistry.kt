package com.myfactory.forge.core.agent

import com.myfactory.forge.core.agent.tools.ApplyPatchTool
import com.myfactory.forge.core.agent.tools.DeleteFileTool
import com.myfactory.forge.core.agent.tools.EditFileTool
import com.myfactory.forge.core.agent.tools.ListDirectoryTool
import com.myfactory.forge.core.agent.tools.ProjectOverviewTool
import com.myfactory.forge.core.agent.tools.ReadFileTool
import com.myfactory.forge.core.agent.tools.RunCommandTool
import com.myfactory.forge.core.agent.tools.SearchTool
import com.myfactory.forge.core.agent.tools.WriteFileTool
import com.myfactory.forge.core.capability.Capabilities
import com.myfactory.forge.core.capability.LinuxStrategy

/**
 * Chooses which tools the model is told about.
 *
 * Advertising a tool the device cannot honour wastes turns: the model calls
 * it, gets an error, apologises, and tries again. On a device with no Linux
 * runtime, run_command is simply not offered.
 */
object ToolRegistry {

    /** Read-only tools, available on every device. */
    fun readOnlyTools(): List<AgentTool> = listOf(
        ProjectOverviewTool(),
        ReadFileTool(),
        ListDirectoryTool(),
        SearchTool(),
    )

    /** Tools that change files. All of these require approval. */
    fun writeTools(): List<AgentTool> = listOf(
        WriteFileTool(),
        EditFileTool(),
        ApplyPatchTool(),
        DeleteFileTool(),
    )

    fun forCapabilities(
        capabilities: Capabilities,
        allowWrites: Boolean = true,
        allowCommands: Boolean = true,
    ): List<AgentTool> = buildList {
        addAll(readOnlyTools())
        if (allowWrites) addAll(writeTools())
        if (allowCommands && capabilities.linuxStrategy != LinuxStrategy.NONE) {
            add(RunCommandTool())
        }
    }

    /** Every tool, used by tests and by the tool reference in Settings. */
    fun all(): List<AgentTool> = readOnlyTools() + writeTools() + RunCommandTool()
}
