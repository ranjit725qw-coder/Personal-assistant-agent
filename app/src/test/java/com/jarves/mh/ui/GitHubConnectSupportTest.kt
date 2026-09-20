package com.jarves.mh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

class GitHubConnectSupportTest {
    @Test fun extractsDeviceCodeFromCliOutput() {
        assertEquals("ABCD-1234", extractGitHubDeviceCode("Copy code ABCD-1234 then continue"))
        assertNull(extractGitHubDeviceCode("No device code yet"))
    }

    @Test fun separatesOfflineFromRevokedCredentials() {
        assertEquals(GitHubProbeFailure.OFFLINE, classifyGitHubProbeFailure("could not resolve host: api.github.com"))
        assertEquals(GitHubProbeFailure.UNAUTHORIZED, classifyGitHubProbeFailure("HTTP 401: Bad credentials"))
        assertEquals(GitHubProbeFailure.OTHER, classifyGitHubProbeFailure("unexpected CLI failure"))
    }

    @Test fun redactsKnownGitHubTokenFormats() {
        val redacted = redactGitHubSensitiveOutput("token=ghp_123456789012345678901234567890123456")
        assertFalse(redacted.contains("ghp_"))
        assertEquals("token=[REDACTED_GITHUB_TOKEN]", redacted)
    }
}
