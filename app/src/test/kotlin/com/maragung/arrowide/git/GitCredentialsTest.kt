package com.maragung.arrowide.git

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Plan #38: temporary askpass scripts, 0700, always deleted. */
class GitCredentialsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeLookup(private val credentials: GitCredentials?) : CredentialsProvider {
        override fun credentialsForUrl(url: String): GitCredentials? = credentials
    }

    // ---- script text ----------------------------------------------------------

    @Test
    fun scriptContentAnswersUsernameAndPasswordPrompts() {
        val content = AskpassScript.scriptContent(GitCredentials("alice", "s3cret"))

        assertEquals(
            "#!/system/bin/sh\n" +
                "case \"$1\" in\n" +
                "    *Username*) printf '%s' 'alice' ;;\n" +
                "    *Password*) printf '%s' 's3cret' ;;\n" +
                "    *) printf '%s' '' ;;\n" +
                "esac\n",
            content,
        )
    }

    @Test
    fun scriptContentEscapesSingleQuotesInValues() {
        val content = AskpassScript.scriptContent(GitCredentials("al'ice", "it's"))

        assertTrue(content.contains("*Username*) printf '%s' 'al'\\''ice' ;;"))
        assertTrue(content.contains("*Password*) printf '%s' 'it'\\''s' ;;"))
    }

    // ---- script lifecycle -------------------------------------------------------

    @Test
    fun createWritesExecutableOwnerOnlyScript() {
        val dir = tmp.newFolder()
        val script = AskpassScript.create(dir, GitCredentials("alice", "secret"))

        try {
            assertTrue(script.file.isFile)
            assertTrue("must be executable", script.file.canExecute())
            // Mode check via coreutils stat: java.nio.file.attribute's POSIX
            // types are hidden in android.jar, so they cannot be referenced
            // from unit-test sources. stat is present on the CI runners and
            // dev machines; where it is missing the owner-bit checks above
            // still apply.
            val mode = runCatching {
                ProcessBuilder("stat", "-c", "%a", script.file.absolutePath)
                    .start()
                    .inputStream.bufferedReader().readText().trim()
            }.getOrNull()
            assertEquals("700", mode)
            assertEquals(
                AskpassScript.scriptContent(GitCredentials("alice", "secret")),
                script.file.readText(),
            )
        } finally {
            script.delete()
        }
        assertFalse("delete removes the script", script.file.exists())
    }

    @Test
    fun envPointsGitAtTheScript() {
        val script = AskpassScript.create(tmp.newFolder(), GitCredentials("alice", "secret"))
        try {
            assertEquals(script.file.absolutePath, script.env()["GIT_ASKPASS"])
            assertEquals(script.file.absolutePath, script.env()["ASKPASS"])
            assertEquals("0", script.env()["GIT_TERMINAL_PROMPT"])
        } finally {
            script.delete()
        }
    }

    // ---- withAskpassEnv -----------------------------------------------------------

    @Test
    fun withAskpassEnvExposesScriptAndDeletesItAfterwards() = runBlocking {
        val dir = tmp.newFolder()
        val provider = AskpassCredentialsProvider(
            FakeLookup(GitCredentials("alice", "ghp-token")),
            dir,
        )

        var seenEnv: Map<String, String>? = null
        var contentDuringRun: String? = null
        val result: GitOutcome<Unit> = provider.withAskpassEnv("https://github.com/x/y.git") { env ->
            seenEnv = env
            // The script must exist and hold the credentials only for as
            // long as the wrapped git invocation is running.
            contentDuringRun = File(env.getValue("GIT_ASKPASS")).readText()
            GitOutcome.Ok(Unit)
        }

        assertTrue(result is GitOutcome.Ok<*>)
        val env = seenEnv!!
        val scriptFile = File(env.getValue("GIT_ASKPASS"))
        assertEquals("0", env["GIT_TERMINAL_PROMPT"])
        assertEquals(
            AskpassScript.scriptContent(GitCredentials("alice", "ghp-token")),
            contentDuringRun,
        )
        assertFalse("script deleted after the run", scriptFile.exists())
        assertTrue("cache dir is empty again", dir.listFiles()!!.isEmpty())
    }

    @Test
    fun withAskpassEnvWithoutCredentialsYieldsEmptyEnv() = runBlocking {
        val dir = tmp.newFolder()
        val provider = AskpassCredentialsProvider(FakeLookup(null), dir)

        var seenEnv: Map<String, String>? = null
        provider.withAskpassEnv("https://github.com/x/y.git") { env ->
            seenEnv = env
            GitOutcome.Ok(Unit)
        }

        assertTrue(seenEnv!!.isEmpty())
        assertTrue("no script created", dir.listFiles()!!.isEmpty())
    }

    @Test
    fun withAskpassEnvDeletesScriptWhenBlockThrows() = runBlocking {
        val dir = tmp.newFolder()
        val provider = AskpassCredentialsProvider(
            FakeLookup(GitCredentials("alice", "ghp-token")),
            dir,
        )

        var scriptFile: File? = null
        try {
            provider.withAskpassEnv("https://github.com/x/y.git") { env ->
                scriptFile = File(env.getValue("GIT_ASKPASS"))
                throw IllegalStateException("git blew up")
            }
        } catch (e: IllegalStateException) {
            // expected
        }

        assertTrue(scriptFile != null)
        assertFalse("script deleted even on failure", scriptFile!!.exists())
    }

    @Test
    fun delegatesLookupToWrappedProvider() {
        val credentials = GitCredentials("alice", "token")
        val provider = AskpassCredentialsProvider(FakeLookup(credentials), tmp.newFolder())

        assertEquals(credentials, provider.credentialsForUrl("https://github.com/x/y.git"))
    }

    @Test
    fun noCredentialsByDefault() {
        assertNull(FakeLookup(null).credentialsForUrl("https://github.com/x/y.git"))
    }
}
