package com.termpilot.app.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * The autonomous agent loop. The model pursues the user's [goal] by calling
 * the `run_command` tool; each call runs on the SSH connection through a NEW
 * exec channel (never the interactive shell), and the result is fed back to
 * the model until it answers without tool calls or [maxIterations] is hit.
 *
 * Guardrails:
 * - The caller owns the coroutine scope: cancelling it (the STOP button)
 *   aborts the loop immediately, including a blocked command.
 * - Every command is echoed to the terminal UI via [Runner.onCommandEcho]
 *   and reported through [onEvent] for the transcript panel.
 * - Per-command timeouts (default from settings) bound each exec channel.
 * - Command output is truncated before being sent back to the model to bound
 *   context growth.
 */
class AgentLoop(private val llm: LlmClient) {

    data class CommandResult(
        val command: String,
        val stdout: String,
        val stderr: String,
        val exitCode: Int?,
        val timedOut: Boolean,
    )

    /** Implemented by the SSH layer (backed by sshj exec channels). */
    interface Runner {
        suspend fun runCommand(command: String, timeoutSeconds: Int): CommandResult
        /** Show the command in the terminal view, e.g. prefixed with `$ `. */
        fun onCommandEcho(text: String)
    }

    sealed interface Event {
        data class Status(val text: String) : Event
        data class AssistantText(val text: String) : Event
        data class CommandStart(val command: String, val timeoutSeconds: Int) : Event
        data class CommandEnd(val result: CommandResult) : Event
        data class Finished(val summary: String) : Event
        data class Failed(val error: String) : Event
        data object Cancelled : Event
    }

    suspend fun run(
        goal: String,
        config: LlmClient.Config,
        runner: Runner,
        defaultTimeoutSeconds: Int,
        maxIterations: Int = 25,
        onEvent: (Event) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val messages = mutableListOf(
            LlmClient.ChatMessage("system", SYSTEM_PROMPT),
            LlmClient.ChatMessage("user", goal),
        )
        onEvent(Event.Status("Agent started — pursuing goal"))
        try {
            repeat(maxIterations) { iter ->
                coroutineContext.ensureActive()
                onEvent(Event.Status("Thinking… (step ${iter + 1}/$maxIterations)"))
                val reply = llm.chat(config, messages, listOf(llm.runCommandTool))

                val text = reply.content
                if (!text.isNullOrBlank()) {
                    messages.add(LlmClient.ChatMessage("assistant", text))
                    onEvent(Event.AssistantText(text))
                } else if (reply.toolCalls.isEmpty()) {
                    // Empty, tool-less reply: treat as done with no summary.
                    onEvent(Event.Finished("(no further output)"))
                    return@withContext
                }

                if (reply.toolCalls.isEmpty()) {
                    onEvent(Event.Finished(text ?: "(done)"))
                    return@withContext
                }

                // Record the assistant turn that issued the tool calls (OpenAI
                // requires tool_calls to be echoed back on the assistant message).
                messages.add(reply)

                for (tc in reply.toolCalls) {
                    coroutineContext.ensureActive()
                    if (tc.name != "run_command") {
                        messages.add(toolResult(tc, "error: unknown tool '${tc.name}'"))
                        continue
                    }
                    val args = runCatching { JSONObject(tc.argumentsJson) }.getOrDefault(JSONObject())
                    val command = args.optString("command", "").trim()
                    val timeout = args.optInt("timeout_seconds", defaultTimeoutSeconds)
                        .coerceIn(5, 600)
                    if (command.isEmpty()) {
                        messages.add(toolResult(tc, "error: empty command"))
                        continue
                    }
                    onEvent(Event.CommandStart(command, timeout))
                    runner.onCommandEcho("$ $command")
                    val result = runner.runCommand(command, timeout)
                    onEvent(Event.CommandEnd(result))
                    messages.add(toolResult(tc, formatResult(result)))
                }
            }
            onEvent(Event.Finished("(iteration budget of $maxIterations exhausted — stopping)"))
        } catch (e: CancellationException) {
            onEvent(Event.Cancelled)
            throw e
        } catch (e: Exception) {
            onEvent(Event.Failed(e.message ?: e.toString()))
        }
    }

    private fun toolResult(tc: LlmClient.ToolCall, content: String) =
        LlmClient.ChatMessage(
            role = "tool",
            content = content,
            toolCallId = tc.id,
        )

    private fun formatResult(r: CommandResult): String = buildString {
        append("exit_code=")
        append(r.exitCode?.toString() ?: if (r.timedOut) "timeout" else "unknown")
        if (r.timedOut) append(" (timed out)")
        append("\n--- stdout ---\n").append(r.stdout.truncate(MAX_OUTPUT))
        if (r.stderr.isNotBlank()) append("\n--- stderr ---\n").append(r.stderr.truncate(MAX_OUTPUT))
    }

    private fun String.truncate(n: Int): String =
        if (length <= n) this
        else take(n) + "\n…[truncated ${length - n} chars]"

    companion object {
        const val MAX_OUTPUT = 8000

        const val SYSTEM_PROMPT = """You are TermPilot Agent, an autonomous Linux sysadmin operating over SSH. The user gives you a goal; you achieve it by running shell commands via the run_command tool.

Rules:
- Work step by step. Inspect before you act (e.g. `ls`, `df -h`, `systemctl status`) when the state is unknown.
- Prefer precise, non-interactive commands. Never run interactive programs (no vim, nano, top without -b, ssh, passwd).
- Be careful with destructive commands (rm -rf, mkfs, dd, systemctl stop, firewall changes). If the goal genuinely needs them, say what you are about to do first, then do it.
- Do NOT use sudo unless the goal explicitly requires privilege escalation.
- Keep going until the goal is done, then reply with a short summary of what you did and the outcome. Explain briefly what you are doing as you go.
- If a command times out or fails, diagnose (check logs, permissions, paths) rather than blindly retrying.
- Stay within the goal's scope. Do not exfiltrate data or touch unrelated services."""
    }
}
