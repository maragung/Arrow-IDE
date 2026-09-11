package com.maragung.arrowide.toolchain

/**
 * Curated list of installable tools shown by the Tools screen (plan #4/#5).
 *
 * Each entry maps a stable UI id to a package name in the repository index.
 * Entries marked `optional` may not exist for every architecture (plan #4:
 * "jika binary kompatibel tersedia"); the UI greys them out when the loaded
 * index has no such package.
 */
data class CatalogTool(
    val id: String,
    val packageName: String,
    val displayName: String,
    val description: String,
    val optional: Boolean,
)

/** Static catalog; ids are stable identifiers used by [ToolchainManager]. */
object ToolCatalog {

    val tools: List<CatalogTool> = listOf(
        CatalogTool(
            id = "nodejs",
            packageName = "nodejs",
            displayName = "Node.js",
            description = "JavaScript runtime; includes npm and npx",
            optional = false,
        ),
        CatalogTool(
            id = "python",
            packageName = "python",
            displayName = "Python 3",
            description = "Python 3 interpreter; includes pip",
            optional = false,
        ),
        CatalogTool(
            id = "git",
            packageName = "git",
            displayName = "Git",
            description = "Distributed version control system",
            optional = false,
        ),
        CatalogTool(
            id = "clang",
            packageName = "clang",
            displayName = "Clang / LLVM",
            description = "C and C++ compiler toolchain",
            optional = false,
        ),
        CatalogTool(
            id = "cmake",
            packageName = "cmake",
            displayName = "CMake",
            description = "Cross-platform build system generator",
            optional = false,
        ),
        CatalogTool(
            id = "make",
            packageName = "make",
            displayName = "GNU Make",
            description = "Build automation tool",
            optional = false,
        ),
        CatalogTool(
            id = "curl",
            packageName = "curl",
            displayName = "curl",
            description = "Command-line HTTP client",
            optional = false,
        ),
        CatalogTool(
            id = "wget",
            packageName = "wget",
            displayName = "wget",
            description = "Non-interactive network downloader",
            optional = false,
        ),
        CatalogTool(
            id = "zip",
            packageName = "zip",
            displayName = "zip",
            description = "Create ZIP archives",
            optional = false,
        ),
        CatalogTool(
            id = "unzip",
            packageName = "unzip",
            displayName = "unzip",
            description = "Extract ZIP archives",
            optional = false,
        ),
        CatalogTool(
            id = "tar",
            packageName = "tar",
            displayName = "tar",
            description = "Tape archive tool (tar/gzip)",
            optional = false,
        ),
        CatalogTool(
            id = "jq",
            packageName = "jq",
            displayName = "jq",
            description = "Command-line JSON processor",
            optional = true,
        ),
        CatalogTool(
            id = "openssl",
            packageName = "openssl",
            displayName = "OpenSSL",
            description = "TLS toolkit and crypto utilities",
            optional = true,
        ),
        CatalogTool(
            id = "openssh",
            packageName = "openssh",
            displayName = "OpenSSH",
            description = "SSH client and tools",
            optional = true,
        ),
        CatalogTool(
            id = "rsync",
            packageName = "rsync",
            displayName = "rsync",
            description = "Fast incremental file transfer",
            optional = true,
        ),
        CatalogTool(
            id = "pnpm",
            packageName = "pnpm",
            displayName = "pnpm",
            description = "Fast, disk-efficient Node.js package manager",
            optional = true,
        ),
        CatalogTool(
            id = "yarn",
            packageName = "yarn",
            displayName = "Yarn",
            description = "Node.js package manager",
            optional = true,
        ),
        CatalogTool(
            id = "bun",
            packageName = "bun",
            displayName = "Bun",
            description = "All-in-one JavaScript runtime and toolkit",
            optional = true,
        ),
    )

    private val byId: Map<String, CatalogTool> = tools.associateBy { it.id }

    /** Catalog entry for [id], or null when the id is unknown. */
    fun byId(id: String): CatalogTool? = byId[id]
}
