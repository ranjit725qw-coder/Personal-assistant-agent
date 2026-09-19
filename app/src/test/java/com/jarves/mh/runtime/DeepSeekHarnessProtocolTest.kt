package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekHarnessProtocolTest {
    @Test
    fun deepSeekUsesOfficialNativeRoute() {
        val route = DeepSeekHarnessRouteMapper.forProfile(ProviderProfile(ProviderKind.DEEPSEEK))

        assertEquals("deepseek-official", route.name)
        assertEquals("DEEPSEEK_API_KEY", route.keyEnvironmentVariable)
        assertEquals("deepseek-v4-flash", route.model)
        assertNull(route.custom)
    }

    @Test
    fun anthropicCompatibleProvidersUseDeclaredCustomRoutes() {
        val route = DeepSeekHarnessRouteMapper.forProfile(
            ProviderProfile(
                kind = ProviderKind.CUSTOM,
                baseUrl = "https://gateway.example/anthropic/",
                model = "custom-model",
            ),
        )

        assertEquals("mh-custom", route.name)
        assertEquals(DeepSeekHarnessRouteMapper.FALLBACK_KEY_ENV, route.keyEnvironmentVariable)
        assertEquals(DshApiProtocol.ANTHROPIC_MESSAGES, route.custom?.protocol)
        assertEquals("https://gateway.example/anthropic", route.custom?.baseUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun claudeSubscriptionIsRejected() {
        DeepSeekHarnessRouteMapper.forProfile(ProviderProfile(ProviderKind.CLAUDE))
    }

    @Test
    fun settingsUseKeyEnvironmentWithoutEmbeddingTheSecret() {
        val route = DeepSeekHarnessRouteMapper.forProfile(ProviderProfile(ProviderKind.KIMI))
        val yaml = renderDeepSeekHarnessSettings(route)

        assertTrue(yaml.contains("apiKeyEnv: MH_DSH_API_KEY"))
        assertTrue(yaml.contains("api: anthropic-messages"))
        assertTrue(yaml.contains("baseURL: 'https://api.moonshot.ai/anthropic'"))
    }

    @Test
    fun outputParserSeparatesReasoningDiagnosticsAndAnswers() {
        assertEquals(
            DeepSeekHarnessLine.ReasoningHeading,
            DeepSeekHarnessOutputParser.parseLine("dsh: reasoning:"),
        )
        assertEquals(
            DeepSeekHarnessLine.Reasoning("checking files"),
            DeepSeekHarnessOutputParser.parseLine("dsh: reasoning: checking files"),
        )
        assertEquals(
            DeepSeekHarnessLine.Diagnostic("AUTH_ERROR: invalid key"),
            DeepSeekHarnessOutputParser.parseLine("dsh: AUTH_ERROR: invalid key"),
        )
        assertEquals(
            DeepSeekHarnessLine.Answer("Done"),
            DeepSeekHarnessOutputParser.parseLine("Done"),
        )
    }
}
