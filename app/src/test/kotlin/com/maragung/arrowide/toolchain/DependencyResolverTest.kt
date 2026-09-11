package com.maragung.arrowide.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DependencyResolverTest {

    private fun pkg(name: String, depends: String = "") =
        RepoPackage(
            name = name,
            version = "1.0",
            filename = "pool/$name.deb",
            sha256 = "00",
            size = 1,
            depends = depends,
        )

    private fun index(vararg packages: RepoPackage) =
        packages.associateBy { it.name }

    @Test
    fun resolvesTransitiveDependenciesDependencyFirst() {
        val index = index(
            pkg("app", "libb, libc"),
            pkg("libb", "libc"),
            pkg("libc"),
        )

        val resolved = DependencyResolver.resolve(index, listOf("app"))

        // libc before libb, libb before app.
        assertEquals(listOf("libc", "libb", "app"), resolved.map { it.name })
    }

    @Test
    fun sharedDependencyIsInstalledOnce() {
        val index = index(
            pkg("app", "libb, libc"),
            pkg("libb", "libc"),
            pkg("libc"),
        )

        val resolved = DependencyResolver.resolve(index, listOf("app"))

        assertEquals(1, resolved.count { it.name == "libc" })
    }

    @Test
    fun multipleRootsResolveTogether() {
        val index = index(
            pkg("a", "shared"),
            pkg("b", "shared"),
            pkg("shared"),
        )

        val resolved = DependencyResolver.resolve(index, listOf("a", "b"))

        assertEquals(listOf("shared", "a", "b"), resolved.map { it.name })
    }

    @Test
    fun missingRootIsRejected() {
        val index = index(pkg("present"))

        try {
            DependencyResolver.resolve(index, listOf("absent"))
            fail("expected ToolchainException")
        } catch (e: ToolchainException) {
            assertTrue(e.message!!.contains("absent"))
        }
    }

    @Test
    fun missingTransitiveDependencyNamesTheCulprit() {
        val index = index(
            pkg("app", "ghost"),
        )

        try {
            DependencyResolver.resolve(index, listOf("app"))
            fail("expected ToolchainException")
        } catch (e: ToolchainException) {
            assertTrue(e.message!!.contains("ghost"))
            assertTrue(e.message!!.contains("app"))
        }
    }

    @Test
    fun cyclesAreRejected() {
        val index = index(
            pkg("a", "b"),
            pkg("b", "a"),
        )

        try {
            DependencyResolver.resolve(index, listOf("a"))
            fail("expected ToolchainException")
        } catch (e: ToolchainException) {
            assertTrue(e.message!!.contains("cycle"))
        }
    }

    @Test
    fun selfCycleIsRejected() {
        val index = index(pkg("a", "a"))

        try {
            DependencyResolver.resolve(index, listOf("a"))
            fail("expected ToolchainException")
        } catch (e: ToolchainException) {
            assertTrue(e.message!!.contains("cycle"))
        }
    }

    @Test
    fun dependencyAlternativesResolveToTheFirstCandidate() {
        val index = index(
            pkg("app", "real | ghost"),
            pkg("real"),
            pkg("ghost"),
        )

        val resolved = DependencyResolver.resolve(index, listOf("app"))

        // Only the first alternative is followed (deterministic policy).
        assertEquals(listOf("real", "app"), resolved.map { it.name })
    }
}
