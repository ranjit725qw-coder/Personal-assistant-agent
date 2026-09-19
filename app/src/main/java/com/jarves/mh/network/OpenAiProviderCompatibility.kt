package com.jarves.mh.network

/** A key-based OpenAI-compatible provider preset. */
internal data class OpenAiProviderPreset(
    val stableId: String,
    val title: String,
    val baseUrl: String,
    val defaultModel: String,
)

internal object OpenAiProviderPresets {
    val NVIDIA_NIM = OpenAiProviderPreset(
        stableId = "nvidia-nim",
        title = "NVIDIA NIM",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        defaultModel = "qwen/qwen2.5-coder-32b-instruct",
    )
}

/**
 * Normalized endpoints and request policy for OpenAI-compatible providers.
 * Keeping these rules independent from UI state lets model discovery, validation,
 * and future agent runtimes share one verified contract.
 */
internal object OpenAiProviderCompatibility {
    const val CONNECT_TIMEOUT_MS = 12_000
    const val VALIDATION_READ_TIMEOUT_MS = 45_000

    fun normalizedBaseUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    fun modelsEndpoint(baseUrl: String): String =
        "${normalizedBaseUrl(baseUrl)}/models"

    fun chatCompletionsEndpoint(baseUrl: String): String =
        "${normalizedBaseUrl(baseUrl)}/chat/completions"

    fun bearerHeader(apiKey: String): Pair<String, String> {
        require(apiKey.isNotBlank()) { "API key is required" }
        return "Authorization" to "Bearer ${apiKey.trim()}"
    }

    fun validationFailure(
        httpCode: Int,
        providerMessage: String? = null,
    ): ProviderValidationFailure = when (httpCode) {
        401, 403 -> ProviderValidationFailure(
            label = "Rejected",
            message = "Check this API key or select another saved key.",
            providerMessage = sanitizeProviderMessage(providerMessage),
        )
        404 -> ProviderValidationFailure(
            label = "Endpoint error",
            message = "Check the Base URL and selected gateway protocol.",
            providerMessage = sanitizeProviderMessage(providerMessage),
        )
        429 -> ProviderValidationFailure(
            label = "Rate limited",
            message = "Wait a moment, then retry or use another API key.",
            providerMessage = sanitizeProviderMessage(providerMessage),
        )
        in 500..599 -> ProviderValidationFailure(
            label = "Provider error",
            message = "The provider is temporarily unavailable. Try again shortly.",
            providerMessage = sanitizeProviderMessage(providerMessage),
        )
        else -> ProviderValidationFailure(
            label = "Request failed",
            message = "Review the model, protocol, and endpoint settings.",
            providerMessage = sanitizeProviderMessage(providerMessage),
        )
    }

    fun sanitizeProviderMessage(message: String?): String? {
        val cleaned = message.orEmpty()
            .replace(Regex("(?i)bearer\\s+\\S+"), "Bearer ••••")
            .replace(Regex("(?i)sk-[a-z0-9_-]{8,}"), "sk-••••")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(280)
        return cleaned.ifBlank { null }
    }
}

internal data class ProviderValidationFailure(
    val label: String,
    val message: String,
    val providerMessage: String? = null,
)
