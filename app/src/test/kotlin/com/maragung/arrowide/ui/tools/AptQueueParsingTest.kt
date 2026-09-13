package com.maragung.arrowide.ui.tools

import com.maragung.arrowide.toolchain.ToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * apt queue → toolchain bridge (plan #4/#5): [parseAptQueueLine] decides
 * which queued shim commands this app can honestly apply, and
 * [aptPackageToToolId] maps apt package names onto catalog tool ids.
 */
class AptQueueParsingTest {

    // ------------------------------------------------------------------
    // parseAptQueueLine
    // ------------------------------------------------------------------

    @Test
    fun parsesAptInstallWithASinglePackage() {
        assertEquals(
            AptQueueAction.Install(listOf("nodejs")),
            parseAptQueueLine("apt install nodejs")
        )
    }

    @Test
    fun parsesAptGetInstallWithMultiplePackages() {
        assertEquals(
            AptQueueAction.Install(listOf("nodejs", "python", "git")),
            parseAptQueueLine("apt-get install nodejs python git")
        )
    }

    @Test
    fun acceptsYesFlagsBeforeAndAfterPackages() {
        assertEquals(
            AptQueueAction.Install(listOf("nodejs")),
            parseAptQueueLine("apt install -y nodejs")
        )
        assertEquals(
            AptQueueAction.Install(listOf("nodejs")),
            parseAptQueueLine("apt-get --yes install nodejs")
        )
        assertEquals(
            AptQueueAction.Install(listOf("curl", "wget")),
            parseAptQueueLine("apt install curl --yes wget -y")
        )
    }

    @Test
    fun parsesAptAndAptGetUpdate() {
        assertEquals(AptQueueAction.Update, parseAptQueueLine("apt update"))
        assertEquals(AptQueueAction.Update, parseAptQueueLine("apt-get update"))
        assertEquals(AptQueueAction.Update, parseAptQueueLine("apt -y update"))
    }

    @Test
    fun rejectsOtherVerbsAndPrograms() {
        assertNull(parseAptQueueLine("apt remove nodejs"))
        assertNull(parseAptQueueLine("apt-get purge vim"))
        assertNull(parseAptQueueLine("apt search curl"))
        assertNull(parseAptQueueLine("apt upgrade"))
        assertNull(parseAptQueueLine("dpkg -i foo.deb"))
        assertNull(parseAptQueueLine("apt"))
        assertNull(parseAptQueueLine(""))
        assertNull(parseAptQueueLine("   "))
    }

    @Test
    fun rejectsInstallWithoutPackagesOrWithUnsupportedOptions() {
        assertNull(parseAptQueueLine("apt install"))
        assertNull(parseAptQueueLine("apt install --no-install-recommends curl"))
    }

    // ------------------------------------------------------------------
    // aptPackageToToolId
    // ------------------------------------------------------------------

    @Test
    fun mapsObviousAliasesToCatalogIds() {
        assertEquals("nodejs", aptPackageToToolId("node"))
        assertEquals("nodejs", aptPackageToToolId("nodejs"))
        assertEquals("python", aptPackageToToolId("python"))
        assertEquals("python", aptPackageToToolId("python3"))
        assertEquals("git", aptPackageToToolId("git"))
        assertEquals("bash", aptPackageToToolId("bash"))
    }

    @Test
    fun returnsNullForUnknownPackages() {
        assertNull(aptPackageToToolId("vim"))
        assertNull(aptPackageToToolId("nonexistent-package"))
    }

    @Test
    fun everyCatalogToolIdResolvesToItself() {
        ToolCatalog.tools.forEach { tool ->
            assertEquals(tool.id, aptPackageToToolId(tool.id))
        }
    }

    @Test
    fun everyCatalogEntryIsReachableThroughTheParser() {
        val line = "apt install " + ToolCatalog.tools.joinToString(" ") { it.id }
        val action = parseAptQueueLine(line)
        assertTrue(action is AptQueueAction.Install)
        ToolCatalog.tools.forEach { tool ->
            assertEquals(tool.id, aptPackageToToolId(tool.packageName))
        }
    }

    // ------------------------------------------------------------------
    // aptSkipReason
    // ------------------------------------------------------------------

    @Test
    fun explainsUnsupportedVerbs() {
        assertEquals("'remove' is not supported", aptSkipReason("apt remove vim"))
        assertEquals("'search' is not supported", aptSkipReason("apt-get search curl"))
    }

    @Test
    fun explainsUnknownCommandsAndMissingPieces() {
        assertEquals("Unknown command 'frobnicate'", aptSkipReason("apt frobnicate x"))
        assertEquals("'install' needs at least one package name", aptSkipReason("apt install"))
        assertEquals("'apt' needs a command (e.g. 'apt install <package>')", aptSkipReason("apt"))
        assertEquals("Empty command", aptSkipReason("   "))
    }
}
