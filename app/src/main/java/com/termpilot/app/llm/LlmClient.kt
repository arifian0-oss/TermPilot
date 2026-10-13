package com.termpilot.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Minimal OpenAI-compatible chat-completions client (works with OpenAI,
 * OpenRouter, llama.cpp server, vLLM, etc.). JSON is handled with org.json
 * to avoid extra dependencies.
 */
class LlmClient {

    data class Config(val baseUrl: String, val apiKey: String, val model: String)

    data class ToolCall(val id: String, val name: String, val argumentsJson: String)

    data class ChatMessage(
        val role: String,
        val content: String?,
        val toolCalls: List<ToolCall> = emptyList(),
        val toolCallId: String? = null,
    )

    data class ToolDef(val name: String, val description: String, val parameters: JSONObject)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** The single tool the agent may call. */
    val runCommandTool = ToolDef(
        name = "run_command",
        description = "Execute a shell command on the remote host over SSH and return " +
            "its output. Prefer non-interactive commands. Do NOT use sudo unless the " +
            "user's goal explicitly requires privilege escalation.",
        parameters = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("command", JSONObject()
                    .put("type", "string")
                    .put("description", "The shell command to execute (sh syntax)."))
                .put("timeout_seconds", JSONObject()
                    .put("type", "integer")
                    .put("description", "Kill the command after this many seconds.")
                    .put("default", 60)))
            .put("required", JSONArray().put("command")),
    )

    /** GET {baseUrl}/models — used by the "Test connection" button. */
    suspend fun testConnection(config: Config): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(config.baseUrl.trimEnd('/') + "/models")
                .header("Authorization", "Bearer ${config.apiKey}")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(300)}")
                val models = mutableListOf<String>()
                val data = JSONObject(body).optJSONArray("data")
                for (i in 0 until (data?.length() ?: 0)) {
                    data.getJSONObject(i).optString("id").takeIf { it.isNotBlank() }?.let(models::add)
                }
                models
            }
        }
    }

    /** POST {baseUrl}/chat/completions with tool support. Retries transient
     * network failures (mobile networks drop often mid-"thinking"). */
    suspend fun chat(
        config: Config,
        messages: List<ChatMessage>,
        tools: List<ToolDef>,
    ): ChatMessage = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                return@withContext executeChat(config, messages, tools)
            } catch (e: Exception) {
                lastError = e
                if (!isTransient(e) || attempt == 2) throw e
                delay(1500L * (attempt + 1))
            }
        }
        throw lastError!!
    }

    private fun isTransient(e: Exception): Boolean = when (e) {
        is UnknownHostException, is SocketTimeoutException,
        is ConnectException, is SocketException -> true
        is LlmHttpException -> e.code == 429 || e.code in 500..599
        else -> false
    }

    class LlmHttpException(val code: Int, body: String) :
        Exception("LLM HTTP $code: ${body.take(500)}")

    private fun executeChat(
        config: Config,
        messages: List<ChatMessage>,
        tools: List<ToolDef>,
    ): ChatMessage {
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", JSONArray().apply {
                messages.forEach { m ->
                    val o = JSONObject().put("role", m.role)
                    m.content?.let { o.put("content", it) }
                    if (m.toolCalls.isNotEmpty()) {
                        o.put("tool_calls", JSONArray().apply {
                            m.toolCalls.forEach { tc ->
                                put(JSONObject()
                                    .put("id", tc.id)
                                    .put("type", "function")
                                    .put("function", JSONObject()
                                        .put("name", tc.name)
                                        .put("arguments", tc.argumentsJson)))
                            }
                        })
                    }
                    m.toolCallId?.let { o.put("tool_call_id", it) }
                    put(o)
                }
            })
            .put("tools", JSONArray().apply {
                tools.forEach { t ->
                    put(JSONObject()
                        .put("type", "function")
                        .put("function", JSONObject()
                            .put("name", t.name)
                            .put("description", t.description)
                            .put("parameters", t.parameters)))
                }
            })
            .put("tool_choice", "auto")

        val req = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LlmHttpException(resp.code, text)
            val msg = JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message")
            val toolCalls = mutableListOf<ToolCall>()
            val tcArr = msg.optJSONArray("tool_calls")
            for (i in 0 until (tcArr?.length() ?: 0)) {
                val tc = tcArr.getJSONObject(i)
                val fn = tc.getJSONObject("function")
                toolCalls.add(ToolCall(
                    id = tc.optString("id", "call_$i"),
                    name = fn.getString("name"),
                    argumentsJson = fn.optString("arguments", "{}"),
                ))
            }
            ChatMessage(
                role = msg.optString("role", "assistant"),
                content = (if (msg.isNull("content")) null else msg.optString("content", null))
                    ?.takeIf { it.isNotEmpty() },
                toolCalls = toolCalls,
            )
        }
    }
}
