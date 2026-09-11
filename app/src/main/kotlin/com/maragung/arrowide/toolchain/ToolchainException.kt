package com.maragung.arrowide.toolchain

/**
 * Thrown for every toolchain failure: missing packages in the repository
 * index, checksum mismatches, corrupt archives and install/rollback errors.
 *
 * Kept as a single type (with a descriptive message) so the UI layer can
 * surface any of these uniformly in the tool status without catching a
 * dozen exception types.
 */
class ToolchainException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
