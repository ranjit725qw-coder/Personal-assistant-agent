package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile

/** Provider API variants understood by DeepSeek Harness custom routes. */
internal enum class DshApiProtocol(val wireName: String) {
    ANTHROPIC_MESSAGES("anthropic-messages"),
    OPENAI_COMPLETIONS("openai-completions"),
    OPENAI_RESPONSES("openai-responses"),
}

/** A fully resolved, non-interactive DeepSeek Harness provider route. */
internal data class DeepSeekHarnessRoute(
    val name: String,
    val keyEnvironmentVariable: String,
    val model: String,
    val custom: DeepSeekHarnessCustomRoute? = null,
)

internal data class DeepSeekHarnessCustomRoute(
    val protocol: DshApiProtocol,
    val baseUrl: String,
)

/**
 * Maps the app's existing provider profiles to DeepSeek Harness routes.
 *
 * Claude subscription login is intentionally rejected because DSH requires a
 * key-based provider. The current Claude Code runtime remains the owner of
 * subscription-token sessions.
 */
internal object DeepSeekHarnessRouteMapper {
    const val FALLBACK_KEY_ENV = "MH_DSH_API_KEY"

    fun forProfile(profile: ProviderProfile): DeepSeekHarnessRoute {
        val model = profile.model.ifBlank { profile.kind.defaultModel }
        val baseUrl = profile.baseUrl.trimEnd('/')
        return when (profile.kind) {
            ProviderKind.DEEPSEEK -> DeepSeekHarnessRoute(
                name = "deepseek-official",
                keyEnvironmentVariable = "DEEPSEEK_API_KEY",
                model = model,
            )
            ProviderKind.ANTHROPIC -> customRoute("mh-anthropic", model, baseUrl)
            ProviderKind.LLM_ROUTER -> customRoute("mh-openrouter", model, baseUrl)
            ProviderKind.KIMI -> customRoute("mh-kimi", model, baseUrl)
            ProviderKind.CUSTOM -> customRoute("mh-custom", model, baseUrl)
            ProviderKind.CLAUDE -> throw IllegalArgumentException(
                "Claude subscription login is not supported by DeepSeek Harness",
            )
        }
    }

    private fun customRoute(
        name: String,
        model: String,
        baseUrl: String,
    ) = DeepSeekHarnessRoute(
        name = name,
        keyEnvironmentVariable = FALLBACK_KEY_ENV,
        model = model,
        custom = DeepSeekHarnessCustomRoute(DshApiProtocol.ANTHROPIC_MESSAGES, baseUrl),
    )
}

/** Builds the deterministic `$DSH_HOME/settings.yaml` used by the runtime. */
internal fun renderDeepSeekHarnessSettings(route: DeepSeekHarnessRoute): String = buildString {
    appendLine("agent-default-model:")
    appendLine("  provider: ${route.name}")
    appendLine("  model: ${yamlQuote(route.model)}")
    route.custom?.let { custom ->
        appendLine("llm-pi-ai:")
        appendLine("  providers:")
        appendLine("    ${route.name}:")
        appendLine("      apiKeyEnv: ${route.keyEnvironmentVariable}")
        appendLine("      api: ${custom.protocol.wireName}")
        appendLine("      baseURL: ${yamlQuote(custom.baseUrl)}")
        appendLine("      models:")
        appendLine("        - id: ${yamlQuote(route.model)}")
    }
}

private fun yamlQuote(value: String): String = "'${value.replace("'", "''")}'"

/** One classified line from DSH's merged stdout/stderr capture. */
internal sealed interface DeepSeekHarnessLine {
    data object ReasoningHeading : DeepSeekHarnessLine
    data class Reasoning(val text: String) : DeepSeekHarnessLine
    data class Diagnostic(val text: String) : DeepSeekHarnessLine
    data class Answer(val text: String) : DeepSeekHarnessLine
}

internal object DeepSeekHarnessOutputParser {
    fun parseLine(rawLine: String): DeepSeekHarnessLine {
        val line = rawLine.trim()
        if (line.startsWith("dsh:")) {
            val body = line.removePrefix("dsh:").trim()
            if (body.startsWith("reasoning:")) {
                val reasoning = body.removePrefix("reasoning:").trim()
                return if (reasoning.isBlank()) {
                    DeepSeekHarnessLine.ReasoningHeading
                } else {
                    DeepSeekHarnessLine.Reasoning(reasoning)
                }
            }
            return DeepSeekHarnessLine.Diagnostic(body.ifBlank { line })
        }
        return DeepSeekHarnessLine.Answer(line)
    }
}
