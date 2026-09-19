package com.jarves.mh.runtime

import com.jarves.mh.model.DevStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevStackRemovalPlanTest {
    private val idle = DevStackRemovalState(
        installInProgress = false,
        removalInProgress = false,
        agentTaskRunning = false,
        terminalCommandRunning = false,
    )

    @Test
    fun coreWebRuntimeCannotBeRemoved() {
        val decision = DevStackRemovalGuard.decide(DevStack.WEB, idle)

        assertTrue(decision is DevStackRemovalDecision.Blocked)
        assertTrue((decision as DevStackRemovalDecision.Blocked).message.contains("core agent runtime"))
    }

    @Test
    fun androidPlanOnlyTargetsDedicatedRuntimePaths() {
        val plan = DevStackRemovalPlanner.plan(DevStack.ANDROID)

        assertTrue(plan.removable)
        assertTrue(plan.packages.isEmpty())
        assertEquals(
            listOf("opt/android-sdk", "opt/gradle/gradle-8.14.3", "usr/local/bin/gradle"),
            plan.rootfsRelativePaths,
        )
        assertTrue(plan.rootfsRelativePaths.none { it.startsWith('/') || ".." in it })
    }

    @Test
    fun packagePlansContainOnlyArgumentsNotShellCommands() {
        listOf(DevStack.PYTHON, DevStack.CPP, DevStack.PHP).forEach { stack ->
            val plan = DevStackRemovalPlanner.plan(stack)
            assertTrue(plan.removable)
            assertFalse(plan.packages.isEmpty())
            assertTrue(plan.packages.none { it.contains(' ') || it.contains(';') || it.contains('&') })
        }
    }

    @Test
    fun runningAgentTaskBlocksRemoval() {
        val decision = DevStackRemovalGuard.decide(
            DevStack.PYTHON,
            idle.copy(agentTaskRunning = true),
        )

        assertEquals(
            DevStackRemovalDecision.Blocked(
                "Stop running tasks and terminal commands before removing tools.",
            ),
            decision,
        )
    }

    @Test
    fun runningTerminalCommandBlocksRemoval() {
        val decision = DevStackRemovalGuard.decide(
            DevStack.CPP,
            idle.copy(terminalCommandRunning = true),
        )

        assertTrue(decision is DevStackRemovalDecision.Blocked)
    }

    @Test
    fun concurrentInstallOrRemovalBlocksAnotherRemoval() {
        assertTrue(
            DevStackRemovalGuard.decide(
                DevStack.PHP,
                idle.copy(installInProgress = true),
            ) is DevStackRemovalDecision.Blocked,
        )
        assertTrue(
            DevStackRemovalGuard.decide(
                DevStack.PHP,
                idle.copy(removalInProgress = true),
            ) is DevStackRemovalDecision.Blocked,
        )
    }

    @Test
    fun idleOptionalStackProducesAllowedPlan() {
        val decision = DevStackRemovalGuard.decide(DevStack.PYTHON, idle)

        assertTrue(decision is DevStackRemovalDecision.Allowed)
        val plan = (decision as DevStackRemovalDecision.Allowed).plan
        assertEquals(DevStack.PYTHON, plan.stack)
        assertTrue("python3" in plan.packages)
    }
}
