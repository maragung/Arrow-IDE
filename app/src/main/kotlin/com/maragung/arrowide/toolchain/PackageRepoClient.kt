package com.maragung.arrowide.toolchain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * One entry of a Debian-style repository `Packages` index.
 *
 * @param name     value of the `Package:` field (e.g. `nodejs`)
 * @param version  value of the `Version:` field
 * @param filename repository-relative path of the `.deb` (value of
 *                 `Filename:`, e.g. `pool/main/n/nodejs/nodejs_22.1.0_aarch64.deb`)
 * @param sha256   hex SHA-256 of the `.deb` file (value of `SHA256:`)
 * @param size     size of the `.deb` file in bytes (value of `Size:`)
 * @param depends  raw `Depends:` value, may be empty; see [DebDeps.parse]
 */
data class RepoPackage(
    val name: String,
    val version: String,
    val filename: String,
    val sha256: String,
    val size: Long,
    val depends: String,
) {
    /** Dependency names of this package, first alternative of each group. */
    val dependencyNames: List<String> get() = DebDeps.parse(depends)
}

/**
 * Shared parsing of Debian `Depends:` values (used both when reading the
 * index and when resolving).
 *
 * A raw value looks like:
 *
 *     libc, libssl >= 1.1, nodejs-current | nodejs-lts
 *
 * Groups are separated by `,`, alternatives within a group by `|`. For
 * alternative groups the first entry is chosen (the index resolution order
 * makes this deterministic). Version constraints (`(>= 1.1)` etc.) are
 * stripped and names trimmed.
 */
object DebDeps {

    /** Parses [depends] into package names; blank input yields an empty list. */
    fun parse(depends: String): List<String> {
        if (depends.isBlank()) return emptyList()
        return depends
            .split(',')
            .mapNotNull { group ->
                val first = group.split('|').firstOrNull() ?: return@mapNotNull null
                val name = stripVersionConstraint(first).trim()
                name.ifEmpty { null }
            }
            .distinct()
    }

    /** Removes a trailing ` (>= 1.2~3)` style constraint, if present. */
    private fun stripVersionConstraint(entry: String): String {
        val paren = entry.indexOf('(')
        val cut = if (paren >= 0) paren else entry.indexOf('<')
        return if (cut >= 0) entry.substring(0, cut) else entry
    }
}

/**
 * Loads the package index of a remote repository. Kept as an interface so
 * the whole toolchain can be unit-tested against a fake index.
 */
interface PackageRepoClient {

    /**
     * Repository roots (no trailing slash) the packages of the index can be
     * downloaded from, in mirror priority order. A `Filename:` field of the
     * index resolves against these.
     */
    val baseUrls: List<String>

    /**
     * Fetches and parses the `Packages` index for [arch].
     *
     * @throws IOException on network/parse failure
     */
    suspend fun loadIndex(arch: String): List<RepoPackage>
}

/**
 * Real [PackageRepoClient] against a Termux-style apt repository
 * (`{base}/dists/stable/main/binary-{arch}/Packages.gz`).
 *
 * Base URLs are tried in order; the first one that yields an index wins, so
 * a mirror can front the canonical host. All I/O runs on [Dispatchers.IO].
 *
 * @param baseUrls repository roots (no trailing slash), e.g.
 *                 `https://packages.termux.dev/apt/termux-main`
 */
class TermuxRepoClient(
    override val baseUrls: List<String> = listOf(ToolchainEnvironment.DEFAULT_REPO_BASE_URL),
) : PackageRepoClient {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val USER_AGENT = "ArrowIDE/0.1"
    }

    override suspend fun loadIndex(arch: String): List<RepoPackage> = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()
        for (base in baseUrls) {
            val url = "${base.trimEnd('/')}/dists/stable/main/binary-$arch/Packages.gz"
            try {
                return@withContext PackagesIndexParser.parse(fetchGzipped(url))
            } catch (e: IOException) {
                failures += "${url}: ${e.message}"
            }
        }
        throw IOException("Failed to load package index for $arch:\n${failures.joinToString("\n")}")
    }

    /** Downloads [url] and returns the gunzipped body as text. */
    private fun fetchGzipped(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $code for $url")
            }
            GZIPInputStream(connection.inputStream).bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Parser for the Debian `Packages` stanza format used by apt repositories.
 * Kept top-level (internal visibility for tests) because it is pure text
 * processing.
 *
 * Format: stanzas separated by one or more blank lines; each field is a
 * `Key: value` line; a line starting with a space continues the previous
 * field's value.
 */
internal object PackagesIndexParser {

    /** Field names are matched case-sensitively, as apt does. */
    private val FIELD_NAMES = setOf("Package", "Version", "Filename", "SHA256", "Size", "Depends")

    /**
     * Parses the raw (already gunzipped) content of a `Packages` index.
     * Stanzas missing `Package` or `Filename` are skipped; a bad `Size` is
     * treated as 0.
     */
    fun parse(text: String): List<RepoPackage> {
        val stanzas = mutableListOf<Map<String, String>>()
        val fields = mutableMapOf<String, String>()
        var lastField: String? = null

        fun finishStanza() {
            if (fields.isNotEmpty()) {
                stanzas += fields.toMap()
                fields.clear()
            }
            lastField = null
        }

        for (rawLine in text.lineSequence()) {
            if (rawLine.isBlank()) {
                finishStanza()
                continue
            }
            if (rawLine[0] == ' ' || rawLine[0] == '\t') {
                // Continuation line: append to the previous field.
                lastField?.let { fields[it] = fields[it].orEmpty() + "\n" + rawLine.trim() }
                continue
            }
            val sep = rawLine.indexOf(':')
            if (sep <= 0) continue
            val key = rawLine.substring(0, sep)
            val value = rawLine.substring(sep + 1).trim()
            if (key in FIELD_NAMES) {
                fields[key] = value
                lastField = key
            } else {
                lastField = null
            }
        }
        finishStanza()

        return stanzas.mapNotNull { stanza ->
            val name = stanza["Package"] ?: return@mapNotNull null
            val filename = stanza["Filename"] ?: return@mapNotNull null
            RepoPackage(
                name = name,
                version = stanza["Version"].orEmpty(),
                filename = filename,
                sha256 = stanza["SHA256"].orEmpty(),
                size = stanza["Size"]?.toLongOrNull() ?: 0L,
                depends = stanza["Depends"].orEmpty(),
            )
        }
    }
}
