package com.jarves.mh.runtime

/**
 * Returns true only for a native ARM64 Android runtime.
 *
 * Some translated x86/x86_64 environments advertise arm64-v8a in the ABI list,
 * but the embedded PRoot/runtime binaries still cannot execute there. Checking
 * both the kernel architecture and Android ABI list avoids a misleading setup.
 */
internal fun supportsArm64Runtime(supportedAbis: Array<String>, osArchitecture: String?): Boolean {
    val kernelIsArm64 = osArchitecture.equals("aarch64", ignoreCase = true) ||
        osArchitecture.equals("arm64", ignoreCase = true)
    return kernelIsArm64 && supportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) }
}
