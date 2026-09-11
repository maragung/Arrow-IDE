package com.maragung.arrowide.git

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Credentials for one remote URL. [password] holds the secret (a GitHub
 * PAT, an account password, ...); it must never appear in argv, stored
 * config or logs (plan #38) — only inside a temporary GIT_ASKPASS script
 * that is deleted right after use.
 */
data class GitCredentials(
    val username: String,
    val password: String,
)

/**
 * Credential lookup for remote URLs. Backed by Android secure storage in
 * the app; faked in unit tests.
 */
interface CredentialsProvider {

    /**
     * @param url the remote URL git is about to contact
     * @return credentials for it, or null to let git proceed unauthenticated
     */
    fun credentialsForUrl(url: String): GitCredentials?
}

/**
 * Bridges [CredentialsProvider] into git's askpass mechanism (plan #38).
 *
 * Git invokes the program in GIT_ASKPASS once per prompt, passing the
 * prompt text ("Username for 'https://github.com': " /
 * "Password for 'https://user@github.com': ") as the only argument, and
 * reads the answer from stdout. Per network operation we write a tiny
 * POSIX shell script that answers those prompts with the looked-up
 * credentials; the script lives in a caller-supplied cache directory,
 * is created with mode 0700 and is ALWAYS deleted afterwards — the token
 * never reaches argv, stored git config or the process environment.
 *
 * Only http(s) credentials are covered (what GIT_ASKPASS answers); SSH
 * key passphrases are out of scope.
 */
class AskpassCredentialsProvider(
    private val lookup: CredentialsProvider,
    private val cacheDir: File,
) : CredentialsProvider {

    override fun credentialsForUrl(url: String): GitCredentials? =
        lookup.credentialsForUrl(url)

    /**
     * Runs [block] with the askpass environment injected when credentials
     * exist for [url]; runs it untouched otherwise. The temporary script
     * is deleted in every case.
     *
     * @param block receives the extra environment (empty map when no
     *        credentials apply) and returns the git outcome
     */
    suspend fun <T> withAskpassEnv(
        url: String,
        block: suspend (Map<String, String>) -> T,
    ): T {
        val credentials = lookup.credentialsForUrl(url) ?: return block(emptyMap())
        val script = AskpassScript.create(cacheDir, credentials)
        return try {
            block(script.env())
        } finally {
            script.delete()
        }
    }
}

/**
 * The temporary askpass script itself. Created with mode 0700 — POSIX
 * permissions when the filesystem supports them (the file is born with
 * them, so the token is never briefly world-readable), File setters as a
 * fallback. Lives only for the duration of one git invocation.
 */
class AskpassScript private constructor(val file: File) {

    /** Environment that makes git (and only git) use this script. */
    fun env(): Map<String, String> = mapOf(
        "GIT_ASKPASS" to file.absolutePath,
        "ASKPASS" to file.absolutePath,
        "GIT_TERMINAL_PROMPT" to "0",
    )

    /** Deletes the script; safe to call twice. */
    fun delete() {
        try {
            file.delete()
        } catch (e: SecurityException) {
            // Best effort — the cache dir is app-private anyway.
        }
    }

    companion object {

        /**
         * Writes the askpass script for [credentials] into [cacheDir].
         *
         * @throws IOException when the script cannot be created
         */
        fun create(cacheDir: File, credentials: GitCredentials): AskpassScript {
            if (!cacheDir.isDirectory && !cacheDir.mkdirs()) {
                throw IOException("Cannot create askpass cache dir: $cacheDir")
            }
            val file = File(cacheDir, "git-askpass-${UUID.randomUUID()}.sh")
            // java.nio PosixFilePermissions is unavailable on Android, so the
            // script is created via java.io and restricted to the owner with
            // the File permission setters (0700 equivalent).
            try {
                if (!file.createNewFile()) {
                    throw IOException("Askpass script already exists: $file")
                }
                file.writeText(scriptContent(credentials), Charsets.UTF_8)
                file.setReadable(false, false)
                file.setWritable(false, false)
                file.setExecutable(false, false)
                file.setReadable(true, true)
                file.setWritable(true, true)
                file.setExecutable(true, true)
            } catch (e: IOException) {
                file.delete()
                throw e
            }
            return AskpassScript(file)
        }

        /**
         * The script text. `case` on the prompt string decides which
         * credential git asked for; values are single-quoted with the
         * standard `'\''` escaping so arbitrary characters (including
         * quotes) survive POSIX sh.
         */
        internal fun scriptContent(credentials: GitCredentials): String {
            val username = shSingleQuote(credentials.username)
            val password = shSingleQuote(credentials.password)
            return StringBuilder()
                .append("#!/system/bin/sh\n")
                .append("case \"$1\" in\n")
                .append("    *Username*) printf '%s' $username ;;\n")
                .append("    *Password*) printf '%s' $password ;;\n")
                .append("    *) printf '%s' '' ;;\n")
                .append("esac\n")
                .toString()
        }

        /** `it's` -> `'it'\''s'` — safe inside single quotes for POSIX sh. */
        private fun shSingleQuote(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"
    }
}
