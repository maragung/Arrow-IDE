package com.maragung.arrowide.buildsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BuildSystemDetectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val detector = BuildSystemDetector()

    private fun commandIds(system: DetectedBuildSystem): List<String> =
        system.commands.map { it.id }

    private fun commandFor(system: DetectedBuildSystem, id: String): String =
        system.commands.first { it.id == id }.command

    // ------------------------------------------------------------------ empty

    @Test
    fun `empty directory detects nothing`() {
        val dir = tmp.newFolder()
        assertTrue(detector.detect(dir).isEmpty())
    }

    @Test
    fun `unparseable package json is not a marker`() {
        val dir = tmp.newFolder()
        File(dir, "package.json").writeText("this is not json {{{")
        assertTrue(detector.detect(dir).isEmpty())
    }

    // ------------------------------------------------------------------- node

    @Test
    fun `package json with start build test scripts yields node commands`() {
        val dir = tmp.newFolder()
        File(dir, "package.json").writeText(
            """
            {
              "name": "my-app",
              "version": "1.2.0",
              "scripts": {
                "start": "node index.js",
                "build": "vite build",
                "test": "node --test"
              }
            }
            """.trimIndent(),
        )

        val detected = detector.detect(dir)
        assertEquals(listOf(BuildSystemKind.NODE), detected.map { it.kind })

        val node = detected.single()
        assertEquals("my-app 1.2.0", node.detail)
        assertEquals(listOf("run", "build", "test"), commandIds(node))
        assertEquals("npm start", commandFor(node, "run"))
        assertEquals("npm run build", commandFor(node, "build"))
        assertEquals("npm test", commandFor(node, "test"))
    }

    @Test
    fun `dev script becomes run when start is absent`() {
        val dir = tmp.newFolder()
        File(dir, "package.json").writeText(
            """
            {
              "name": "site",
              "version": "0.1.0",
              "scripts": { "dev": "vite" }
            }
            """.trimIndent(),
        )

        val node = detector.detect(dir).single()
        assertEquals(listOf("run"), commandIds(node))
        assertEquals("npm run dev", commandFor(node, "run"))
    }

    @Test
    fun `package json without scripts yields no commands`() {
        val dir = tmp.newFolder()
        File(dir, "package.json").writeText("""{"name":"bare","version":"0.0.1"}""")

        val node = detector.detect(dir).single()
        assertEquals(BuildSystemKind.NODE, node.kind)
        assertEquals("bare 0.0.1", node.detail)
        assertTrue(node.commands.isEmpty())
    }

    @Test
    fun `package json without name falls back to generic detail`() {
        val dir = tmp.newFolder()
        File(dir, "package.json").writeText("""{"scripts":{"start":"node x.js"}}""")

        val node = detector.detect(dir).single()
        assertEquals("Node.js package", node.detail)
    }

    @Test
    fun `start null value is not a script`() {
        val dir = tmp.newFolder()
        File(dir, "package.json").writeText("""{"scripts":{"start":null,"dev":"vite"}}""")

        val node = detector.detect(dir).single()
        assertEquals("npm run dev", commandFor(node, "run"))
    }

    // ---------------------------------------------------------------- python

    @Test
    fun `requirements with pytest and root main py yields run and test`() {
        val dir = tmp.newFolder()
        File(dir, "requirements.txt").writeText(
            """
            requests==2.31.0
            pytest==8.0.0
            """.trimIndent(),
        )
        File(dir, "main.py").writeText("print('hi')\n")

        val python = detector.detect(dir).single()
        assertEquals(BuildSystemKind.PYTHON, python.kind)
        assertEquals("Python project", python.detail)
        assertEquals(listOf("run", "test"), commandIds(python))
        assertEquals("python main.py", commandFor(python, "run"))
        assertEquals("python -m pytest", commandFor(python, "test"))
    }

    @Test
    fun `pyproject with build-system and pytest dep yields build and test`() {
        val dir = tmp.newFolder()
        File(dir, "pyproject.toml").writeText(
            """
            [build-system]
            requires = ["setuptools>=61"]
            build-backend = "setuptools.build_meta"

            [project]
            name = "lib"
            dependencies = ["httpx", "pytest"]
            """.trimIndent(),
        )

        val python = detector.detect(dir).single()
        // No main.py in the root -> no run command.
        assertEquals(listOf("build", "test"), commandIds(python))
        assertEquals("python -m build", commandFor(python, "build"))
        assertEquals("python -m pytest", commandFor(python, "test"))
    }

    @Test
    fun `tests directory without declared pytest yields no test command`() {
        val dir = tmp.newFolder()
        File(dir, "requirements.txt").writeText("requests==2.31.0\n")
        File(dir, "main.py").writeText("print('hi')\n")
        File(dir, "tests").mkdirs()
        File(dir, "tests/test_app.py").writeText("def test_nothing():\n    pass\n")

        val python = detector.detect(dir).single()
        assertEquals(listOf("run"), commandIds(python))
    }

    @Test
    fun `pytest-cov in requirements is not pytest`() {
        val dir = tmp.newFolder()
        File(dir, "requirements.txt").writeText("pytest-cov==4.1.0\n")

        val python = detector.detect(dir).single()
        assertTrue(python.commands.isEmpty())
    }

    @Test
    fun `requirements comment mentioning pytest does not count`() {
        val dir = tmp.newFolder()
        File(dir, "requirements.txt").writeText("# pytest is coming soon\nflask==3.0.0\n")

        val python = detector.detect(dir).single()
        assertTrue(python.commands.isEmpty())
    }

    // ------------------------------------------------------------------ cmake

    @Test
    fun `cmake lists with enable_testing yields configure build test clean`() {
        val dir = tmp.newFolder()
        File(dir, "CMakeLists.txt").writeText(
            """
            cmake_minimum_required(VERSION 3.16)
            project(demo CXX)
            enable_testing()
            add_executable(demo main.cpp)
            add_test(NAME DemoTest COMMAND demo)
            """.trimIndent(),
        )

        val cmake = detector.detect(dir).single()
        assertEquals(BuildSystemKind.CMAKE, cmake.kind)
        assertEquals("CMake project", cmake.detail)
        assertEquals(listOf("configure", "build", "test", "clean"), commandIds(cmake))
        assertEquals("cmake -B build", commandFor(cmake, "configure"))
        assertEquals("cmake --build build", commandFor(cmake, "build"))
        assertEquals("ctest --test-dir build", commandFor(cmake, "test"))
        assertEquals("rm -rf build", commandFor(cmake, "clean"))
    }

    @Test
    fun `cmake lists without enable_testing has no test command`() {
        val dir = tmp.newFolder()
        File(dir, "CMakeLists.txt").writeText(
            """
            cmake_minimum_required(VERSION 3.16)
            project(demo CXX)
            add_executable(demo main.cpp)
            """.trimIndent(),
        )

        val cmake = detector.detect(dir).single()
        assertEquals(listOf("configure", "build", "clean"), commandIds(cmake))
    }

    // ------------------------------------------------------------------- make

    @Test
    fun `makefile targets map to commands and phony patterns and vars are skipped`() {
        val dir = tmp.newFolder()
        File(dir, "Makefile").writeText(
            ".PHONY: build test clean\n" +
                "CC = gcc\n" +
                "SRCS := main.c\n" +
                "build: main.c\n" +
                "\t\$(CC) -o app main.c\n" +
                "all: build\n" +
                "test: build\n" +
                "\t./app\n" +
                "clean:\n" +
                "\trm -f app\n" +
                "run: build\n" +
                "\t./app\n" +
                "%.o: %.c\n" +
                "\t\$(CC) -c \$< -o \$@\n",
        )

        val make = detector.detect(dir).single()
        assertEquals(BuildSystemKind.MAKE, make.kind)
        // build, all, test, clean, run — not .PHONY, not CC/SRCS, not %.o.
        assertEquals("Makefile: 5 targets", make.detail)
        assertEquals(listOf("run", "build", "test", "clean"), commandIds(make))
        assertEquals("make run", commandFor(make, "run"))
        // "build" wins over "all" when both exist.
        assertEquals("make build", commandFor(make, "build"))
        assertEquals("make test", commandFor(make, "test"))
        assertEquals("make clean", commandFor(make, "clean"))
    }

    @Test
    fun `makefile with only all maps build to make all`() {
        val dir = tmp.newFolder()
        File(dir, "Makefile").writeText("all:\n\techo built\n")

        val make = detector.detect(dir).single()
        assertEquals("Makefile: 1 target", make.detail)
        assertEquals(listOf("build"), commandIds(make))
        assertEquals("make all", commandFor(make, "build"))
    }

    @Test
    fun `makefile target parsing is exposed for tests`() {
        val targets = detector.parseMakefileTargets(
            "VAR:=1\n" +
                "a b: dep\n" + // contains a space -> no match for the whole line
                "ok-other_1:\n" +
                "ok-other_1: extra rule duplicate\n",
        )
        assertEquals(listOf("ok-other_1"), targets)
    }

    // ------------------------------------------------------------------ cargo

    @Test
    fun `cargo toml yields all four cargo commands`() {
        val dir = tmp.newFolder()
        File(dir, "Cargo.toml").writeText(
            """
            [package]
            name = "my-crate"
            version = "0.1.0"
            edition = "2021"

            [dependencies]
            """.trimIndent(),
        )

        val cargo = detector.detect(dir).single()
        assertEquals(BuildSystemKind.CARGO, cargo.kind)
        assertEquals("my-crate", cargo.detail)
        assertEquals(listOf("run", "build", "test", "clean"), commandIds(cargo))
        assertEquals("cargo run", commandFor(cargo, "run"))
        assertEquals("cargo build --release", commandFor(cargo, "build"))
        assertEquals("cargo test", commandFor(cargo, "test"))
        assertEquals("cargo clean", commandFor(cargo, "clean"))
    }

    @Test
    fun `cargo toml without package name falls back to rust project`() {
        val dir = tmp.newFolder()
        File(dir, "Cargo.toml").writeText("[workspace]\nmembers = []\n")

        val cargo = detector.detect(dir).single()
        assertEquals("Rust project", cargo.detail)
    }

    // --------------------------------------------------------------------- go

    @Test
    fun `go module with package main in root yields run build test`() {
        val dir = tmp.newFolder()
        File(dir, "go.mod").writeText("module example.com/hello\n\ngo 1.22\n")
        File(dir, "main.go").writeText("package main\n\nimport \"fmt\"\n\nfunc main() {\n\tfmt.Println(\"hi\")\n}\n")

        val go = detector.detect(dir).single()
        assertEquals(BuildSystemKind.GO, go.kind)
        assertEquals("example.com/hello", go.detail)
        assertEquals(listOf("run", "build", "test"), commandIds(go))
        assertEquals("go run .", commandFor(go, "run"))
        assertEquals("go build ./...", commandFor(go, "build"))
        assertEquals("go test ./...", commandFor(go, "test"))
    }

    @Test
    fun `go module without package main has no run command`() {
        val dir = tmp.newFolder()
        File(dir, "go.mod").writeText("module example.com/lib\n\ngo 1.22\n")
        File(dir, "lib.go").writeText("package lib\n\nfunc Add(a, b int) int { return a + b }\n")

        val go = detector.detect(dir).single()
        assertEquals("example.com/lib", go.detail)
        assertEquals(listOf("build", "test"), commandIds(go))
    }

    // --------------------------------------------------------------- multiple

    @Test
    fun `multiple markers coexist in declaration order`() {
        val dir = tmp.newFolder()
        File(dir, "package.json").writeText("""{"name":"combo","version":"1.0.0","scripts":{"build":"x"}}""")
        File(dir, "requirements.txt").writeText("pytest==8.0.0\n")
        File(dir, "CMakeLists.txt").writeText("project(x)\nadd_executable(x main.cpp)\n")
        File(dir, "Makefile").writeText("build:\n\techo hi\n")
        File(dir, "Cargo.toml").writeText("[package]\nname = \"combo-rs\"\n")
        File(dir, "go.mod").writeText("module example.com/combo\n")

        val kinds = detector.detect(dir).map { it.kind }
        assertEquals(
            listOf(
                BuildSystemKind.NODE,
                BuildSystemKind.PYTHON,
                BuildSystemKind.CMAKE,
                BuildSystemKind.MAKE,
                BuildSystemKind.CARGO,
                BuildSystemKind.GO,
            ),
            kinds,
        )
    }

    @Test
    fun `kinds without toolchain catalog entry carry null required tool`() {
        val dir = tmp.newFolder()
        File(dir, "Cargo.toml").writeText("[package]\nname = \"x\"\n")
        val cargo = detector.detect(dir).single()
        assertEquals(null, cargo.kind.requiredToolId)
    }
}
