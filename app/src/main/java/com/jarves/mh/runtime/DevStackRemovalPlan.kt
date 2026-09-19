package com.jarves.mh.runtime

import com.jarves.mh.model.DevStack

/** Side-effect-free description of files and packages owned by an optional stack. */
internal data class DevStackRemovalPlan(
    val stack: DevStack,
    val removable: Boolean,
    val packages: List<String> = emptyList(),
    val rootfsRelativePaths: List<String> = emptyList(),
    val blockedReason: String? = null,
) {
    init {
        require(packages.all(::isSafePackageName)) { "Unsafe package name in removal plan" }
        require(rootfsRelativePaths.all(::isSafeRelativePath)) { "Unsafe runtime path in removal plan" }
        if (!removable) {
            require(packages.isEmpty() && rootfsRelativePaths.isEmpty()) {
                "A blocked stack cannot contain removal actions"
            }
        }
    }
}

/**
 * Ownership map for optional development stacks.
 *
 * The core Web entry is deliberately protected because Node.js, npm, Git, and
 * the coding-agent runtime depend on it. Plans contain argument lists and
 * rootfs-relative paths instead of shell command strings, preventing command
 * injection when the executor is added to RuntimeInstaller.
 */
internal object DevStackRemovalPlanner {
    fun plan(stack: DevStack): DevStackRemovalPlan = when (stack) {
        DevStack.WEB -> DevStackRemovalPlan(
            stack = stack,
            removable = false,
            blockedReason = "Web tools are part of the core agent runtime and cannot be removed.",
        )
        DevStack.PYTHON -> DevStackRemovalPlan(
            stack = stack,
            removable = true,
            packages = listOf("python3", "python3-pip", "python3-venv"),
        )
        DevStack.ANDROID -> DevStackRemovalPlan(
            stack = stack,
            removable = true,
            rootfsRelativePaths = listOf(
                "opt/android-sdk",
                "opt/gradle/gradle-8.14.3",
                "usr/local/bin/gradle",
            ),
        )
        DevStack.CPP -> DevStackRemovalPlan(
            stack = stack,
            removable = true,
            packages = listOf("gcc", "g++", "make", "cmake", "gdb"),
        )
        DevStack.PHP -> DevStackRemovalPlan(
            stack = stack,
            removable = true,
            packages = listOf("php-cli", "php-common", "php-curl", "php-mbstring", "php-xml"),
            rootfsRelativePaths = listOf("usr/local/bin/composer"),
        )
    }
}

internal data class DevStackRemovalState(
    val installInProgress: Boolean,
    val removalInProgress: Boolean,
    val agentTaskRunning: Boolean,
    val terminalCommandRunning: Boolean,
)

internal sealed interface DevStackRemovalDecision {
    data class Allowed(val plan: DevStackRemovalPlan) : DevStackRemovalDecision
    data class Blocked(val message: String) : DevStackRemovalDecision
}

/** Central guard shared by the future Settings UI and RuntimeInstaller executor. */
internal object DevStackRemovalGuard {
    fun decide(stack: DevStack, state: DevStackRemovalState): DevStackRemovalDecision {
        val plan = DevStackRemovalPlanner.plan(stack)
        return when {
            !plan.removable -> DevStackRemovalDecision.Blocked(plan.blockedReason.orEmpty())
            state.installInProgress || state.removalInProgress ->
                DevStackRemovalDecision.Blocked("Wait for the current toolchain operation to finish.")
            state.agentTaskRunning || state.terminalCommandRunning ->
                DevStackRemovalDecision.Blocked("Stop running tasks and terminal commands before removing tools.")
            else -> DevStackRemovalDecision.Allowed(plan)
        }
    }
}

private fun isSafePackageName(value: String): Boolean =
    value.isNotBlank() && value.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9+._-]*"))

private fun isSafeRelativePath(value: String): Boolean {
    if (value.isBlank() || value.startsWith('/') || value.startsWith('~')) return false
    val segments = value.replace('\\', '/').split('/')
    return segments.none { it.isBlank() || it == "." || it == ".." }
}
