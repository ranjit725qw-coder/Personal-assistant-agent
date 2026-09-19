package com.jarves.mh.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProviderCompatibilityTest {
    @Test
    fun nvidiaNimUsesVerifiedEndpointAndCoderModel() {
        val preset = OpenAiProviderPresets.NVIDIA_NIM

        assertEquals("nvidia-nim", preset.stableId)
        assertEquals("https://integrate.api.nvidia.com/v1", preset.baseUrl)
        assertEquals("qwen/qwen2.5-coder-32b-instruct", preset.defaultModel)
        assertEquals(
            "https://integrate.api.nvidia.com/v1/models",
            OpenAiProviderCompatibility.modelsEndpoint(preset.baseUrl),
        )
        assertEquals(
            "https://integrate.api.nvidia.com/v1/chat/completions",
            OpenAiProviderCompatibility.chatCompletionsEndpoint(preset.baseUrl),
        )
    }

    @Test
    fun baseUrlNormalizationAvoidsDuplicateSlashes() {
        assertEquals(
            "https://example.test/v1/chat/completions",
            OpenAiProviderCompatibility.chatCompletionsEndpoint(" https://example.test/v1/// ".trimEnd()),
        )
    }

    @Test
    fun bearerHeaderRequiresAndTrimsTheKey() {
        assertEquals(
            "Authorization" to "Bearer secret-key",
            OpenAiProviderCompatibility.bearerHeader("  secret-key  "),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun bearerHeaderRejectsBlankKeys() {
        OpenAiProviderCompatibility.bearerHeader("  ")
    }

    @Test
    fun providerErrorsAreActionableAndRedacted() {
        val failure = OpenAiProviderCompatibility.validationFailure(
            429,
            "Authorization Bearer secret-token and sk-supersecret123456 were rejected",
        )

        assertEquals("Rate limited", failure.label)
        assertTrue(failure.message.contains("retry"))
        assertTrue(failure.providerMessage!!.contains("Bearer ••••"))
        assertTrue(failure.providerMessage!!.contains("sk-••••"))
        assertFalse(failure.providerMessage!!.contains("secret-token"))
        assertFalse(failure.providerMessage!!.contains("supersecret"))
    }

    @Test
    fun validationAllowsProviderColdStarts() {
        assertEquals(12_000, OpenAiProviderCompatibility.CONNECT_TIMEOUT_MS)
        assertEquals(45_000, OpenAiProviderCompatibility.VALIDATION_READ_TIMEOUT_MS)
    }
}
