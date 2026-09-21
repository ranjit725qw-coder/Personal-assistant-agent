package com.jarves.mh.ui
import org.junit.Assert.*
import org.junit.Test
class GitHubConnectSupportTest {
 @Test fun extractsDeviceCodeFromCliOutput(){ assertEquals("ABCD-1234", extractGitHubDeviceCode("Copy code ABCD-1234 then continue")); assertNull(extractGitHubDeviceCode("No device code yet")) }
 @Test fun separatesOfflineFromRevokedCredentials(){ assertEquals(GitHubProbeFailure.OFFLINE, classifyGitHubProbeFailure("could not resolve host: api.github.com")); assertEquals(GitHubProbeFailure.UNAUTHORIZED, classifyGitHubProbeFailure("HTTP 401: Bad credentials")) }
 @Test fun redactsKnownGitHubTokenFormats(){ val r=redactGitHubSensitiveOutput("token=ghp_123456789012345678901234567890123456"); assertFalse(r.contains("ghp_")) }
 @Test fun parsesRepositoryListForBrowser(){ val r=parseGitHubRepositories("""[{"nameWithOwner":"owner/private-repo","url":"https://github.com/owner/private-repo","isPrivate":true,"description":"Private work","updatedAt":"2026-09-20T20:00:00Z"}]"""); assertEquals(1,r.size); assertTrue(r.single().isPrivate) }
 @Test fun parsesWorkSnapshot(){ val s=parseGitHubWorkSnapshot("""__REPOSITORY__
yes
__BRANCH__
agent/update
__BASE__
main
__STATUS__
 M app.kt
__DIFF__
 app.kt | 2 +-
"""); assertTrue(s.isRepository); assertFalse(isProtectedGitBranch(s.branch,s.baseBranch)); assertTrue(isProtectedGitBranch("main",s.baseBranch)) }
 @Test fun extractsPullRequestUrl(){ assertEquals("https://github.com/owner/repo/pull/25", extractGitHubPullRequestUrl("https://github.com/owner/repo/pull/25")) }
 @Test fun createsSafeChatWorkflowDefaults(){ assertEquals("Fix login flow", defaultGitHubCommitMessage("  Fix   login flow  ", "App")); assertEquals(GitHubChatStage.IDLE, GitHubChatWorkflow().stage); assertEquals("git diff --check", GitHubChatWorkflow().checkCommand) }
 @Test fun ignoresUnsafeRepositoryUrls(){ assertTrue(parseGitHubRepositories("""[{"nameWithOwner":"owner/repo","url":"https://example.com/repo"}]""").isEmpty()) }
}
