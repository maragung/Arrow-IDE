package com.maragung.arrowide.toolchain

import java.io.File

/**
 * Locations and target architecture for the download-and-install toolchain
 * (plan #4-#7). Pure data holder with no I/O of its own; the app layer
 * creates it from its private storage directories.
 *
 * The layout mirrors Termux: every package's `data.tar` carries paths under
 * `./usr/...`, so [prefixDir] *is* the `/usr` of the installed environment
 * (`bin/node`, `lib/...`, ...). [cacheDir] holds downloaded `.deb` files and
 * is safe to wipe at any time.
 */
data class ToolchainEnvironment(
    val prefixDir: File,
    val cacheDir: File,
    val arch: String,
) {

    companion object {

        /** Default Termux package repository (HTTPS, plan #39). */
        const val DEFAULT_REPO_BASE_URL = "https://packages.termux.dev/apt/termux-main"

        /**
         * Maps an Android ABI (as returned by `Build.SUPPORTED_ABIS`) to the
         * Debian-style architecture name used by the repository index.
         *
         * @throws IllegalArgumentException for any ABI this app does not ship
         */
        fun fromAndroid(archAbi: String, prefixDir: File, cacheDir: File): ToolchainEnvironment {
            val arch = when (archAbi) {
                "arm64-v8a" -> "aarch64"
                "armeabi-v7a" -> "arm"
                "x86_64" -> "x86_64"
                else -> throw IllegalArgumentException("Unsupported ABI for toolchain: $archAbi")
            }
            return ToolchainEnvironment(prefixDir, cacheDir, arch)
        }
    }
}
