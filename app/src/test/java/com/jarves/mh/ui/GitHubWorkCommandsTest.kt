package com.jarves.mh.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubWorkCommandsTest {
    @Test fun snapshotEmitsStableSections() {
        val command = GitHubWorkCommands.snapshot()
        listOf("__REPOSITORY__", "__BRANCH__", "__BASE__", "__STATUS__", "__DIFF__")
            .forEach { assertTrue(command.contains(it)) }
    }

    @Test fun branchPreparationQuotesUntrustedSeed() {
        val command = GitHubWorkCommands.prepareBranch("agent/test'branch")
        assertTrue(command.contains("git switch -c"))
        assertTrue(command.contains("agent/test"))
        assertTrue(command.contains("main|master"))
    }

    @Test fun publicationRetainsProtectedBranchGuardAndSafeOperations() {
        val command = GitHubWorkCommands.publish("main", "Fix user's flow", "Improve user's flow", "Body with user's check")
        assertTrue(command.contains("Refusing to publish from a protected branch"))
        assertTrue(command.contains("git commit -m"))
        assertTrue(command.contains("gh pr create"))
        assertTrue(command.contains("Fix user"))
        assertFalse(command.contains("--force"))
    }
}
