package com.jarves.mh.runtime

import org.json.JSONObject

/** Parsed events emitted by Antigravity CLI's stream-json protocol. */
internal sealed interface AntigravityProtocolEvent {
    data class Initialized(val conversationId: String?) : AntigravityProtocolEvent
    data class Text(val value: String) : AntigravityProtocolEvent
    data class ToolStarted(val name: String, val detail: String) : AntigravityProtocolEvent
    data class ToolCompleted(val name: String, val summary: String) : AntigravityProtocolEvent
    data class Result(
        val conversationId: String?,
        val status: String,
        val response: String?,
        val error: String?,
    ) : AntigravityProtocolEvent
}

internal object AntigravityProtocolParser {
    fun parse(line: String): AntigravityProtocolEvent? {
        val frame = runCatching { JSONObject(line) }.getOrNull() ?: return null
        return when (frame.optString("event")) {
            "init" -> AntigravityProtocolEvent.Initialized(
                frame.optString("conversation_id").ifBlank { null },
            )
            "step_update" -> parseStep(frame.optJSONObject("step_update") ?: return null)
            "result" -> {
                val result = frame.optJSONObject("result") ?: return null
                AntigravityProtocolEvent.Result(
                    conversationId = result.optString("conversation_id").ifBlank { null },
                    status = result.optString("status"),
                    response = result.optString("response").ifBlank { null },
                    error = result.optString("error").ifBlank { null },
                )
            }
            else -> null
        }
    }

    private fun parseStep(step: JSONObject): AntigravityProtocolEvent? {
        return when (step.optString("step_type")) {
            "agent_response" -> step.optString("text_delta")
                .takeIf(String::isNotEmpty)
                ?.let(AntigravityProtocolEvent::Text)
            "tool" -> {
                val info = step.optJSONObject("tool_info")
                val rawName = info?.optString("name").orEmpty().ifBlank { step.optString("tool_name", "Tool") }
                val parameters = info?.optJSONObject("parameters")
                val name = antigravityToolName(rawName)
                val detail = antigravityToolDetail(parameters).ifBlank { "Working in the project" }
                if (step.optString("state").equals("DONE", ignoreCase = true)) {
                    AntigravityProtocolEvent.ToolCompleted(name, detail)
                } else {
                    AntigravityProtocolEvent.ToolStarted(name, detail)
                }
            }
            else -> null
        }
    }
}

private fun antigravityToolName(raw: String): String = when (raw.lowercase()) {
    "run_command", "bash", "shell" -> "Bash"
    "write_to_file", "write", "create" -> "Write"
    "replace_file_content", "edit" -> "Edit"
    "read_file", "read", "view" -> "Read"
    "grep", "search" -> "Grep"
    "glob" -> "Glob"
    else -> raw.replaceFirstChar { it.uppercase() }
}

private fun antigravityToolDetail(parameters: JSONObject?): String {
    if (parameters == null) return ""
    return listOf("CommandLine", "TargetFile", "FilePath", "path", "query", "pattern")
        .firstNotNullOfOrNull { key -> parameters.optString(key).takeIf(String::isNotBlank) }
        .orEmpty()
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(240)
}

/**
 * Builds the non-interactive Antigravity CLI command.
 * Existing conversations keep their exact model and do not receive a conflicting
 * effort flag; new projects use the configured reasoning effort.
 */
internal fun antigravityCommand(
    model: String,
    effort: String,
    conversationId: String?,
): List<String> = buildList {
    add("/usr/local/bin/agy")
    addAll(listOf("--input-format", "stream-json"))
    addAll(listOf("--output-format", "stream-json"))
    addAll(listOf("--print-timeout", "60m"))
    add("--dangerously-skip-permissions")
    if (model.isNotBlank()) addAll(listOf("--model", model))
    if (conversationId.isNullOrBlank()) {
        add("--new-project")
        // Model IDs returned by `agy models` already encode their reasoning
        // level (for example, `-high`). Passing both --model and --effort makes
        // the real task exit with code 1 even though the model probe succeeds.
        if (model.isBlank() && effort.isNotBlank()) addAll(listOf("--effort", effort))
    } else {
        addAll(listOf("--conversation", conversationId))
    }
}

internal fun antigravityWorkspacePrompt(projectSlug: String, prompt: String): String = buildString {
    appendLine("The current project is mounted at /workspace/$projectSlug.")
    appendLine("Create and edit files directly in that directory.")
    appendLine("Do not create project output outside /workspace/$projectSlug.")
    appendLine()
    append(prompt)
}

/** Extracts agy's Google PKCE URL even when its terminal renderer wraps lines. */
internal fun extractAntigravityGoogleOAuthUrl(output: String): String? {
    val compact = output.replace(Regex("[\\r\\n\\t ]+"), "")
    GOOGLE_OAUTH_URL.find(compact)?.value
        ?.takeIf { "client_id=" in it && "code_challenge=" in it }
        ?.let { return it }
    if (!output.contains("Select login method", true) &&
        listOf("browser", "visit", "open", "code", "paste").any { output.contains(it, true) }
    ) {
        val candidates = Regex("https://[^\\s\"']{20,}")
            .findAll(compact)
            .map { it.value.trimEnd { char -> char !in URL_CHARACTERS } }
            .filter { it.length >= 30 && "." in it }
            .toList()
        return candidates.firstOrNull { "google" in it } ?: candidates.firstOrNull()
    }
    return null
}

private val URL_CHARACTERS = ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" +
    "-._~:/?#[]@!$&'()*+,;=%").toSet()

private val GOOGLE_OAUTH_URL = Regex(
    "https://accounts\\.google\\.com/[^\\s\\\"'<>]*?[?&]state=[A-Za-z0-9._~-]+",
)
