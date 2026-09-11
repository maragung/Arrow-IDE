package com.maragung.arrowide.buildsystem

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * Build systems Arrow IDE knows how to drive (plan #24).
 *
 * Each kind maps to the toolchain tool the runner must have installed
 * before any command can be executed; [requiredToolId] is null when the
 * tool is not (yet) in [com.maragung.arrowide.toolchain.ToolCatalog] —
 * the UI must treat those as "install externally or skip".
 */
enum class BuildSystemKind(val displayName: String, val requiredToolId: String?) {
    NODE("Node.js", "nodejs"),
    PYTHON("Python", "python"),
    CMAKE("CMake", "cmake"),
    MAKE("Make", "make"),
    CARGO("Rust", null),
    GO("Go", null),
}

/** One runnable action derived from a real file in the project (plan #49). */
data class BuildCommand(
    /** Stable id: "run", "build", "test", "clean", or "configure". */
    val id: String,
    /** Human label shown on the run button, e.g. "Run". */
    val label: String,
    /** The real shell command, executed from the project directory. */
    val command: String,
    /** Origin note, e.g. "package.json script: build". */
    val description: String? = null,
)

/** A build system detected from marker files in a project directory. */
data class DetectedBuildSystem(
    val kind: BuildSystemKind,
    /** e.g. "my-app 1.2.0" or "Makefile: 6 targets". */
    val detail: String,
    val commands: List<BuildCommand>,
)

/**
 * Detects build systems from a project's actual files (plan #24).
 *
 * Honest by construction (plan #49): every [BuildCommand] comes from a
 * marker that really exists — package.json scripts, Makefile targets,
 * declared pytest dependencies… Nothing is ever invented.
 *
 * Multiple marker files yield multiple entries, in [BuildSystemKind]
 * declaration order (NODE, PYTHON, CMAKE, MAKE, CARGO, GO).
 */
class BuildSystemDetector {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Scans [projectDir] (recognition only — no execution, no mutation)
     * and returns every build system whose marker file is present.
     */
    fun detect(projectDir: File): List<DetectedBuildSystem> = listOfNotNull(
        detectNode(projectDir),
        detectPython(projectDir),
        detectCMake(projectDir),
        detectMake(projectDir),
        detectCargo(projectDir),
        detectGo(projectDir),
    )

    // ------------------------------------------------------------------ NODE

    /**
     * `package.json` -> detail "<name> <version>", commands strictly from
     * the `scripts` object. Run prefers `start`, falling back to `dev`.
     */
    private fun detectNode(dir: File): DetectedBuildSystem? {
        val file = File(dir, "package.json")
        if (!file.isFile) return null
        val root = try {
            json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
        } catch (e: Exception) {
            return null // unreadable/invalid JSON is not a usable marker
        } ?: return null

        val name = (root["name"] as? JsonPrimitive)?.contentOrNull
        val version = (root["version"] as? JsonPrimitive)?.contentOrNull
        val detail = listOfNotNull(name, version).joinToString(" ")
            .ifEmpty { "Node.js package" }

        val scripts = root["scripts"] as? JsonObject
        fun hasScript(key: String): Boolean =
            (scripts?.get(key) as? JsonPrimitive)?.contentOrNull != null

        val commands = mutableListOf<BuildCommand>()
        if (hasScript("start")) {
            commands += BuildCommand("run", "Run", "npm start", "package.json script: start")
        } else if (hasScript("dev")) {
            commands += BuildCommand("run", "Run", "npm run dev", "package.json script: dev")
        }
        if (hasScript("build")) {
            commands += BuildCommand("build", "Build", "npm run build", "package.json script: build")
        }
        if (hasScript("test")) {
            commands += BuildCommand("test", "Test", "npm test", "package.json script: test")
        }
        if (hasScript("clean")) {
            commands += BuildCommand("clean", "Clean", "npm run clean", "package.json script: clean")
        }
        return DetectedBuildSystem(BuildSystemKind.NODE, detail, commands)
    }

    // --------------------------------------------------------------- PYTHON

    /**
     * `pyproject.toml` / `requirements.txt` / `setup.py` -> PYTHON.
     * Test only when pytest is actually declared; Run only for a real
     * root `main.py`; Build only for a `[build-system]` table. No Clean —
     * there is no honest generic command for Python projects.
     */
    private fun detectPython(dir: File): DetectedBuildSystem? {
        val pyproject = File(dir, "pyproject.toml")
        val requirements = File(dir, "requirements.txt")
        val setupPy = File(dir, "setup.py")
        if (!pyproject.isFile && !requirements.isFile && !setupPy.isFile) return null

        val pyprojectText = if (pyproject.isFile) pyproject.readText(Charsets.UTF_8) else null
        val requirementsText = if (requirements.isFile) requirements.readText(Charsets.UTF_8) else null

        val commands = mutableListOf<BuildCommand>()
        if (File(dir, "main.py").isFile) {
            commands += BuildCommand("run", "Run", "python main.py", "runs the project entry point")
        }
        if (pyprojectText?.contains("[build-system]") == true) {
            commands += BuildCommand(
                "build", "Build", "python -m build",
                "pyproject.toml declares a [build-system]",
            )
        }
        if (declaresPytest(requirementsText, pyprojectText)) {
            commands += BuildCommand(
                "test", "Test", "python -m pytest",
                "pytest is declared as a dependency",
            )
        }
        return DetectedBuildSystem(BuildSystemKind.PYTHON, "Python project", commands)
    }

    /**
     * pytest counts as declared when requirements.txt pins the `pytest`
     * package itself (not `pytest-cov` etc.) or pyproject.toml mentions
     * pytest anywhere (dependencies or `[tool.pytest.*]` config).
     */
    private fun declaresPytest(requirementsText: String?, pyprojectText: String?): Boolean {
        if (requirementsText != null) {
            for (raw in requirementsText.lines()) {
                val line = raw.substringBefore("#").trim()
                if (PYTEST_REQUIREMENT.matches(line)) return true
            }
        }
        return pyprojectText?.contains("pytest") == true
    }

    // ----------------------------------------------------------------- CMAKE

    /** `CMakeLists.txt` -> configure/build always, test only when tests are enabled. */
    private fun detectCMake(dir: File): DetectedBuildSystem? {
        val cmakeLists = File(dir, "CMakeLists.txt")
        if (!cmakeLists.isFile) return null
        val text = cmakeLists.readText(Charsets.UTF_8)

        val commands = mutableListOf(
            BuildCommand("configure", "Configure", "cmake -B build", "generates the build directory"),
            BuildCommand("build", "Build", "cmake --build build", "compiles all targets"),
        )
        if (text.contains("enable_testing")) {
            commands += BuildCommand("test", "Test", "ctest --test-dir build", "runs the CTest suite")
        }
        commands += BuildCommand("clean", "Clean", "rm -rf build", "removes the build directory")
        return DetectedBuildSystem(BuildSystemKind.CMAKE, "CMake project", commands)
    }

    // ------------------------------------------------------------------ MAKE

    /** `Makefile` -> commands only for targets that really exist. */
    private fun detectMake(dir: File): DetectedBuildSystem? {
        val makefile = File(dir, "Makefile")
        if (!makefile.isFile) return null
        val targets = parseMakefileTargets(makefile.readText(Charsets.UTF_8))

        val commands = mutableListOf<BuildCommand>()
        if ("run" in targets) {
            commands += BuildCommand("run", "Run", "make run", "Makefile target: run")
        }
        val buildTarget = BUILD_TARGET_CANDIDATES.firstOrNull { it in targets }
        if (buildTarget != null) {
            commands += BuildCommand("build", "Build", "make $buildTarget", "Makefile target: $buildTarget")
        }
        if ("test" in targets) {
            commands += BuildCommand("test", "Test", "make test", "Makefile target: test")
        }
        if ("clean" in targets) {
            commands += BuildCommand("clean", "Clean", "make clean", "Makefile target: clean")
        }

        val targetWord = if (targets.size == 1) "target" else "targets"
        return DetectedBuildSystem(BuildSystemKind.MAKE, "Makefile: ${targets.size} $targetWord", commands)
    }

    // ----------------------------------------------------------------- CARGO

    /** `Cargo.toml` -> the four standard cargo commands, always real. */
    private fun detectCargo(dir: File): DetectedBuildSystem? {
        val cargoToml = File(dir, "Cargo.toml")
        if (!cargoToml.isFile) return null
        val packageName = parseCargoPackageName(cargoToml.readText(Charsets.UTF_8))
        val commands = listOf(
            BuildCommand("run", "Run", "cargo run"),
            BuildCommand("build", "Build", "cargo build --release"),
            BuildCommand("test", "Test", "cargo test"),
            BuildCommand("clean", "Clean", "cargo clean"),
        )
        return DetectedBuildSystem(BuildSystemKind.CARGO, packageName ?: "Rust project", commands)
    }

    // -------------------------------------------------------------------- GO

    /** `go.mod` -> run only when the root really holds a `package main` file. */
    private fun detectGo(dir: File): DetectedBuildSystem? {
        val goMod = File(dir, "go.mod")
        if (!goMod.isFile) return null
        val modulePath = parseGoModulePath(goMod.readText(Charsets.UTF_8))

        val commands = mutableListOf<BuildCommand>()
        if (hasGoMainPackage(dir)) {
            commands += BuildCommand("run", "Run", "go run .", "runs the main package in the project root")
        }
        commands += BuildCommand("build", "Build", "go build ./...")
        commands += BuildCommand("test", "Test", "go test ./...")
        return DetectedBuildSystem(BuildSystemKind.GO, modulePath ?: "Go module", commands)
    }

    private fun hasGoMainPackage(dir: File): Boolean =
        dir.listFiles { file -> file.isFile && file.extension == "go" }
            .orEmpty()
            .any { GO_MAIN_PACKAGE.containsMatchIn(it.readText(Charsets.UTF_8)) }

    // --------------------------------------------------------------- parsers

    /**
     * Extracts Makefile rule targets: `^[a-zA-Z][a-zA-Z0-9_-]*:`.
     * `.PHONY` and other special targets (leading `.`), pattern rules
     * (`%.o: %.c`) and variable assignments (`VAR := x`, `VAR:=x`) are
     * skipped; duplicates collapse. Order of first appearance is kept.
     */
    internal fun parseMakefileTargets(text: String): List<String> {
        val targets = LinkedHashSet<String>()
        for (raw in text.lines()) {
            val match = MAKE_TARGET.find(raw) ?: continue
            val afterColon = raw.substring(match.range.last + 1).trimStart()
            if (afterColon.startsWith("=")) continue // VAR:=value assignment
            targets += match.groupValues[1]
        }
        return targets.toList()
    }

    /** `name = "..."` inside the `[package]` table of a Cargo.toml. */
    internal fun parseCargoPackageName(text: String): String? {
        var inPackage = false
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inPackage = line == "[package]"
                continue
            }
            if (!inPackage) continue
            val match = CARGO_NAME.find(line) ?: continue
            return match.groupValues[1]
        }
        return null
    }

    /** The `module <path>` line of a go.mod (trailing `//` comments stripped). */
    internal fun parseGoModulePath(text: String): String? =
        text.lines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("module ") }
            ?.removePrefix("module ")
            ?.substringBefore("//")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private companion object {
        val BUILD_TARGET_CANDIDATES = listOf("build", "all")
        val MAKE_TARGET = Regex("""^([a-zA-Z][a-zA-Z0-9_-]*):""")
        // Plain (escaped) string: a raw string cannot end in a double quote.
        val CARGO_NAME = Regex("^name\\s*=\\s*\"([^\"]+)\"")
        val GO_MAIN_PACKAGE = Regex("""(?m)^\s*package main\b""")
        val PYTEST_REQUIREMENT = Regex("""^pytest([=<>~;\[\s].*)?$""")
    }
}
