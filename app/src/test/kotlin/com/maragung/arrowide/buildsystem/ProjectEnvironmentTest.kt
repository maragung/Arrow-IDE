package com.maragung.arrowide.buildsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectEnvironmentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val env = ProjectEnvironment()

    // ------------------------------------------------------------------ parse

    @Test
    fun `parseDotEnv returns null when file is absent`() {
        assertNull(env.parseDotEnv(File(tmp.newFolder(), ".env")))
    }

    @Test
    fun `parseDotEnv reads key value pairs skipping comments and blanks`() {
        val file = tmp.newFile()
        file.writeText(
            """
            # Arrow IDE project environment
            API_KEY=abc123

            DATABASE_URL=postgres://localhost/app
            EMPTY=
            PORT=8080
            """.trimIndent(),
        )

        val vars = env.parseDotEnv(file)!!
        assertEquals(
            listOf(
                EnvVar("API_KEY", "abc123", sensitive = true),
                EnvVar("DATABASE_URL", "postgres://localhost/app", sensitive = false),
                EnvVar("EMPTY", "", sensitive = false),
                EnvVar("PORT", "8080", sensitive = false),
            ),
            vars,
        )
    }

    @Test
    fun `parseDotEnv strips single and double quotes`() {
        val file = tmp.newFile()
        file.writeText(
            """
            GREETING="hello world"
            SINGLE='single value'
            ESCAPED="say \"hi\" and \\n"
            """.trimIndent(),
        )

        val vars = env.parseDotEnv(file)!!
        assertEquals("hello world", vars[0].value)
        assertEquals("single value", vars[1].value)
        assertEquals("say \"hi\" and \\n", vars[2].value)
    }

    @Test
    fun `parseDotEnv ignores malformed lines`() {
        val file = tmp.newFile()
        file.writeText("JUST_A_WORD\n=NO_NAME\n=NO_NAME\nNAME=ok\n")

        val vars = env.parseDotEnv(file)!!
        assertEquals(listOf(EnvVar("NAME", "ok", sensitive = false)), vars)
    }

    @Test
    fun `parseDotEnv returns empty list for empty file`() {
        val file = tmp.newFile()
        assertEquals(emptyList<EnvVar>(), env.parseDotEnv(file))
    }

    // ------------------------------------------------------------------ write

    @Test
    fun `writeDotEnv writes plain key value lines`() {
        val file = tmp.newFile()
        env.writeDotEnv(
            file,
            listOf(
                EnvVar("NAME", "arrow", sensitive = false),
                EnvVar("API_KEY", "abc123", sensitive = true),
            ),
        )
        assertEquals("NAME=arrow\nAPI_KEY=abc123\n", file.readText())
    }

    @Test
    fun `writeDotEnv quotes values that need it and round trips`() {
        val original = listOf(
            EnvVar("NAME", "arrow", sensitive = false),
            EnvVar("GREETING", "hello world", sensitive = false),
            EnvVar("SECRET", "do not leak", sensitive = true),
            EnvVar("QUOTED", "has \"quotes\" #2", sensitive = false),
            EnvVar("EMPTY", "", sensitive = false),
        )
        val file = tmp.newFile()
        env.writeDotEnv(file, original)

        // Sensitive flags are derived from names, so the round trip is exact.
        assertEquals(original, env.parseDotEnv(file))
    }

    @Test
    fun `writeDotEnv writes empty list as empty file`() {
        val file = tmp.newFile()
        env.writeDotEnv(file, emptyList())
        assertEquals(emptyList<EnvVar>(), env.parseDotEnv(file))
    }

    // ------------------------------------------------------------ sensitivity

    @Test
    fun `looksSensitive matches secret-looking names`() {
        for (name in listOf(
            "API_KEY",
            "GITHUB_TOKEN",
            "MY_SECRET",
            "DB_PASSWORD",
            "USER_PASSWD",
            "CLIENT_CREDENTIALS",
            "PRIVATE_KEY",
            "aws_secret_access_key",
        )) {
            assertTrue("expected '$name' to be sensitive", env.looksSensitive(name))
        }
    }

    @Test
    fun `looksSensitive leaves ordinary names alone`() {
        for (name in listOf(
            "DATABASE_URL",
            "USERNAME",
            "PORT",
            "HOME",
            "NODE_ENV",
            "LOG_LEVEL",
        )) {
            assertFalse("expected '$name' to be non-sensitive", env.looksSensitive(name))
        }
    }
}
