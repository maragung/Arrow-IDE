package com.maragung.arrowide.toolchain

/**
 * Computes the transitive dependency closure of root packages against a
 * repository index (plan #4). Version constraints are intentionally not
 * evaluated: the Termux `stable` channel ships one version per package, so
 * name-based resolution is what `apt` effectively does there.
 *
 * Pure computation, no I/O; unit-tested against synthetic indexes.
 */
object DependencyResolver {

    /**
     * Resolves [roots] (package names) and all their transitive dependencies.
     *
     * The returned list is dependency-first: every package appears before
     * anything that depends on it, and dependencies of the same package keep
     * their declaration order. This is the install order.
     *
     * @throws ToolchainException when a dependency name is missing from
     *         [index], or when the dependency graph contains a cycle
     *         (defensive: the Termux index is a DAG, a cycle would mean a
     *         corrupted or hostile index)
     */
    fun resolve(index: Map<String, RepoPackage>, roots: List<String>): List<RepoPackage> {
        val ordered = LinkedHashSet<RepoPackage>()
        val visiting = HashSet<String>()

        fun visit(name: String, chain: List<String>) {
            if (name in visiting) {
                throw ToolchainException(
                    "Dependency cycle detected: ${(chain + name).joinToString(" -> ")}"
                )
            }
            val pkg = index[name]
                ?: throw ToolchainException(
                    "Package '$name' not found in repository index" +
                        (chain.lastOrNull()?.let { " (required by '$it')" } ?: "")
                )
            if (pkg in ordered) return
            visiting += name
            for (dep in pkg.dependencyNames) {
                visit(dep, chain + name)
            }
            visiting -= name
            ordered += pkg
        }

        for (root in roots) {
            visit(root, emptyList())
        }
        return ordered.toList()
    }
}
