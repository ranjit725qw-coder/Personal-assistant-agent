package com.jarves.mh.ui

import org.json.JSONArray

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


data class GitHubRepository(
    val nameWithOwner: String,
    val cloneUrl: String,
    val isPrivate: Boolean,
    val description: String,
    val updatedAt: String,
) {
    val name: String get() = nameWithOwner.substringAfterLast('/')
}

data class GitHubWorkSnapshot(
    val isRepository: Boolean = false,
    val branch: String = "",
    val baseBranch: String = "main",
    val status: String = "",
    val diffSummary: String = "",
)

internal fun isProtectedGitBranch(branch: String, baseBranch: String): Boolean =
    branch.isBlank() || branch == "main" || branch == "master" || branch == baseBranch

internal fun parseGitHubWorkSnapshot(output: String): GitHubWorkSnapshot {
    fun section(name: String, next: String?): String {
        val marker = "__${name}__\n"
        val start = output.indexOf(marker)
        if (start < 0) return ""
        val bodyStart = start + marker.length
        val end = next?.let { nextName ->
            output.indexOf("__${nextName}__\n", bodyStart).takeIf { index -> index >= 0 }
        } ?: output.length
        return output.substring(bodyStart, end).trim()
    }
    val repository = section("REPOSITORY", "BRANCH") == "yes"
    return GitHubWorkSnapshot(
        isRepository = repository,
        branch = section("BRANCH", "BASE"),
        baseBranch = section("BASE", "STATUS").ifBlank { "main" },
        status = section("STATUS", "DIFF"),
        diffSummary = section("DIFF", null),
    )
}

internal fun extractGitHubPullRequestUrl(output: String): String? =
    Regex("""https://github\.com/[^\s/]+/[^\s/]+/pull/\d+""".replace("\\\\", "\\")).findAll(output).lastOrNull()?.value

internal fun parseGitHubRepositories(json: String): List<GitHubRepository> {
    val array = JSONArray(json)
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val nameWithOwner = item.optString("nameWithOwner").trim()
        val cloneUrl = item.optString("url").trim()
        if (nameWithOwner.isBlank() || !cloneUrl.startsWith("https://github.com/")) return@mapNotNull null
        GitHubRepository(
            nameWithOwner = nameWithOwner,
            cloneUrl = cloneUrl.removeSuffix("/") + ".git",
            isPrivate = item.optBoolean("isPrivate"),
            description = item.optString("description").trim(),
            updatedAt = item.optString("updatedAt").trim(),
        )
    }.distinctBy { it.nameWithOwner.lowercase() }
        .sortedWith(compareByDescending<GitHubRepository> { it.updatedAt }.thenBy { it.nameWithOwner.lowercase() })
}
