package com.jarves.mh.ui

internal enum class GitHubProbeFailure { OFFLINE, UNAUTHORIZED, OTHER }

private val GITHUB_DEVICE_CODE = Regex("\\b[A-Z0-9]{4}-[A-Z0-9]{4}\\b")
private val GITHUB_SECRET = Regex(
    "(?i)\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}",
)

internal fun extractGitHubDeviceCode(output: String): String? =
    GITHUB_DEVICE_CODE.find(output)?.value

internal fun redactGitHubSensitiveOutput(output: String): String =
    output.replace(GITHUB_SECRET, "[REDACTED_GITHUB_TOKEN]")

internal fun classifyGitHubProbeFailure(output: String): GitHubProbeFailure {
    val lower = output.lowercase()
    return when {
        listOf(
            "could not resolve host", "no such host", "network is unreachable",
            "connection timed out", "connection refused", "tls handshake timeout",
            "context deadline exceeded", "temporary failure in name resolution",
        ).any(lower::contains) -> GitHubProbeFailure.OFFLINE
        listOf(
            "http 401", "bad credentials", "authentication failed",
            "not logged into", "authentication token is invalid", "requires authentication",
        ).any(lower::contains) -> GitHubProbeFailure.UNAUTHORIZED
        else -> GitHubProbeFailure.OTHER
    }
}
