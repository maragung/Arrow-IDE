package com.maragung.arrowide.templates

import com.maragung.arrowide.workspace.PathSafety
import java.io.File
import java.io.IOException

/** One file a template materializes at [relativePath] with [content]. */
data class TemplateFile(val relativePath: String, val content: String)

/** A ready-to-instantiate project skeleton (plan #47). */
data class ProjectTemplate(
    val id: String,
    val name: String,
    val description: String,
    /** Toolchain tool the project needs; null = no toolchain (Static Website). */
    val requiredToolId: String?,
    val files: List<TemplateFile>,
)

/** Outcome of [ProjectTemplates.create]. */
sealed interface TemplateCreateResult {
    /** Every template file written; [files] lists them in template order. */
    data class Created(val files: List<File>) : TemplateCreateResult
    data class Error(val message: String) : TemplateCreateResult
}

/**
 * Built-in project templates (plan #47). Every template is complete and
 * runnable as-is — real scripts, real markup, no placeholders to fill in.
 */
object ProjectTemplates {

    /** Creates a project from [template] inside [targetDir].
     *
     * Refuses when [targetDir] exists and is non-empty (the message says
     * so). Otherwise creates the directory tree and writes every file;
     * on any failure cleans up everything it created and returns
     * [TemplateCreateResult.Error]. Relative paths are validated with
     * [PathSafety], so a template can never write outside its target.
     */
    fun create(template: ProjectTemplate, targetDir: File): TemplateCreateResult {
        if (targetDir.exists() && !targetDir.isDirectory) {
            return TemplateCreateResult.Error("Target is not a directory: ${targetDir.absolutePath}")
        }
        if (targetDir.isDirectory && targetDir.listFiles()?.isNotEmpty() == true) {
            return TemplateCreateResult.Error("Target directory is not empty: ${targetDir.absolutePath}")
        }

        val createdFiles = mutableListOf<File>()
        val createdDirs = mutableListOf<File>() // deepest-first, for cleanup
        try {
            ensureDir(targetDir, createdDirs)
            val targetRoot = targetDir.canonicalFile
            for (entry in template.files) {
                val relative = entry.relativePath
                if (relative.isBlank() || relative.endsWith("/")) {
                    throw IOException("Invalid template file path: $relative")
                }
                val resolved = PathSafety.resolveWithin(targetDir, relative)
                if (resolved == targetRoot) {
                    throw IOException("Invalid template file path: $relative")
                }
                ensureDir(resolved.parentFile ?: targetDir, createdDirs)
                resolved.writeText(entry.content, Charsets.UTF_8)
                createdFiles += resolved
            }
        } catch (e: Exception) {
            cleanup(createdFiles, createdDirs)
            return TemplateCreateResult.Error(
                e.message ?: "Failed to create template '${template.id}'",
            )
        }
        return TemplateCreateResult.Created(createdFiles)
    }

    /** Records [dir] and every missing ancestor so cleanup can undo it. */
    private fun ensureDir(dir: File, createdDirs: MutableList<File>) {
        if (dir.exists()) {
            if (!dir.isDirectory) throw IOException("Not a directory: ${dir.absolutePath}")
            return
        }
        val missing = generateSequence(dir) { it.parentFile }
            .takeWhile { !it.exists() }
            .toList() // deepest-first already
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Failed to create directory: ${dir.absolutePath}")
        }
        createdDirs.addAll(missing)
    }

    /**
     * Best-effort rollback: files first, then the directories we created,
     * deepest-first. `File.delete()` only removes empty directories, so
     * nothing the caller owned can be destroyed.
     */
    private fun cleanup(files: List<File>, dirs: List<File>) {
        for (file in files) file.delete()
        for (dir in dirs) dir.delete()
    }

    // ------------------------------------------------------------- node-basic

    private val nodeBasic = ProjectTemplate(
        id = "node-basic",
        name = "Node.js Basic",
        description = "Minimal Node.js app with a start script and a smoke test",
        requiredToolId = "nodejs",
        files = listOf(
            TemplateFile(
                "package.json",
                """
                {
                  "name": "node-basic-app",
                  "version": "1.0.0",
                  "private": true,
                  "description": "Minimal Node.js app created from the Arrow IDE template",
                  "main": "index.js",
                  "scripts": {
                    "start": "node index.js",
                    "test": "node -e \"const assert = require('assert'); const { greet } = require('./index.js'); assert.strictEqual(greet('world'), 'Hello, world!'); assert.strictEqual(greet('Arrow'), 'Hello, Arrow!'); console.log('All tests passed');\""
                  }
                }
                """.trimIndent(),
            ),
            TemplateFile(
                "index.js",
                """
                // Minimal but complete Node.js app: a pure function, a CLI entry
                // point, and a smoke test wired to `npm test` (see package.json).

                function greet(name) {
                  return "Hello, " + name + "!";
                }

                function main() {
                  console.log(greet("world"));
                }

                if (require.main === module) {
                  main();
                }

                module.exports = { greet };
                """.trimIndent(),
            ),
            TemplateFile(
                ".gitignore",
                """
                node_modules/
                """.trimIndent(),
            ),
        ),
    )

    // ------------------------------------------------------------- node-vite

    private val nodeVite = ProjectTemplate(
        id = "node-vite",
        name = "Vite Website",
        description = "Vite-powered vanilla JavaScript website with dev server and production build",
        requiredToolId = "nodejs",
        files = listOf(
            TemplateFile(
                "package.json",
                """
                {
                  "name": "node-vite-app",
                  "version": "1.0.0",
                  "private": true,
                  "type": "module",
                  "description": "Vanilla JavaScript website built with Vite, created from the Arrow IDE template",
                  "scripts": {
                    "dev": "vite",
                    "build": "vite build",
                    "preview": "vite preview"
                  },
                  "devDependencies": {
                    "vite": "^5.4.0"
                  }
                }
                """.trimIndent(),
            ),
            TemplateFile(
                "index.html",
                """
                <!doctype html>
                <html lang="en">
                  <head>
                    <meta charset="UTF-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                    <title>node-vite-app</title>
                  </head>
                  <body>
                    <h1>Vite app</h1>
                    <ul id="list"></ul>
                    <script type="module" src="/src/main.js"></script>
                  </body>
                </html>
                """.trimIndent(),
            ),
            TemplateFile(
                "src/main.js",
                """
                // Real DOM script: fills the page from data when it loads.

                const features = [
                  "Instant server start",
                  "Native ESM modules",
                  "Lightning fast HMR",
                ];

                const list = document.querySelector("#list");
                for (const feature of features) {
                  const item = document.createElement("li");
                  item.textContent = feature;
                  list.appendChild(item);
                }
                """.trimIndent(),
            ),
            TemplateFile(
                ".gitignore",
                """
                node_modules/
                dist/
                """.trimIndent(),
            ),
        ),
    )

    // ------------------------------------------------------------ react-vite

    private val reactVite = ProjectTemplate(
        id = "react-vite",
        name = "React + Vite",
        description = "React app with Vite, hot reload and a production build",
        requiredToolId = "nodejs",
        files = listOf(
            TemplateFile(
                "package.json",
                """
                {
                  "name": "react-vite-app",
                  "version": "1.0.0",
                  "private": true,
                  "type": "module",
                  "description": "React app built with Vite, created from the Arrow IDE template",
                  "scripts": {
                    "dev": "vite",
                    "build": "vite build",
                    "preview": "vite preview"
                  },
                  "dependencies": {
                    "react": "^18.3.1",
                    "react-dom": "^18.3.1"
                  },
                  "devDependencies": {
                    "@vitejs/plugin-react": "^4.3.1",
                    "vite": "^5.4.0"
                  }
                }
                """.trimIndent(),
            ),
            TemplateFile(
                "vite.config.js",
                """
                import { defineConfig } from "vite";
                import react from "@vitejs/plugin-react";

                export default defineConfig({
                  plugins: [react()],
                });
                """.trimIndent(),
            ),
            TemplateFile(
                "index.html",
                """
                <!doctype html>
                <html lang="en">
                  <head>
                    <meta charset="UTF-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                    <title>react-vite-app</title>
                  </head>
                  <body>
                    <div id="root"></div>
                    <script type="module" src="/src/main.jsx"></script>
                  </body>
                </html>
                """.trimIndent(),
            ),
            TemplateFile(
                "src/main.jsx",
                """
                import { useState } from "react";
                import "./App.css";

                function App() {
                  const [count, setCount] = useState(0);

                  return (
                    <main className="app">
                      <h1>Hello from React</h1>
                      <p>You clicked {count} times.</p>
                      <button onClick={() => setCount(count + 1)}>Click me</button>
                    </main>
                  );
                }

                export default App;
                """.trimIndent(),
            ),
            TemplateFile(
                "src/App.css",
                """
                .app {
                  max-width: 480px;
                  margin: 4rem auto;
                  font-family: system-ui, sans-serif;
                  text-align: center;
                }

                .app h1 {
                  color: #2f6fde;
                }

                .app button {
                  padding: 0.5rem 1.5rem;
                  border: none;
                  border-radius: 6px;
                  background: #2f6fde;
                  color: #fff;
                  font-size: 1rem;
                  cursor: pointer;
                }
                """.trimIndent(),
            ),
            TemplateFile(
                ".gitignore",
                """
                node_modules/
                dist/
                """.trimIndent(),
            ),
        ),
    )

    // ---------------------------------------------------------- python-basic

    private val pythonBasic = ProjectTemplate(
        id = "python-basic",
        name = "Python Basic",
        description = "Minimal Python 3 program with a testable pure function",
        requiredToolId = "python",
        files = listOf(
            TemplateFile(
                "main.py",
                """
                # Minimal but complete Python program with a testable pure function.


                def fizzbuzz(n):
                    if n % 15 == 0:
                        return "fizzbuzz"
                    if n % 3 == 0:
                        return "fizz"
                    if n % 5 == 0:
                        return "buzz"
                    return str(n)


                def main():
                    for i in range(1, 16):
                        print(fizzbuzz(i))


                if __name__ == "__main__":
                    main()
                """.trimIndent(),
            ),
            TemplateFile(
                "requirements.txt",
                """
                # Add runtime dependencies here, one per line, for example:
                # requests==2.32.0
                """.trimIndent(),
            ),
            TemplateFile(
                ".gitignore",
                """
                __pycache__/
                *.pyc
                """.trimIndent(),
            ),
        ),
    )

    // ------------------------------------------------------------- cpp-cmake

    private val cppCmake = ProjectTemplate(
        id = "cpp-cmake",
        name = "C++ (CMake)",
        description = "C++17 project with a CMake build and a CTest test target",
        requiredToolId = "cmake",
        files = listOf(
            TemplateFile(
                "CMakeLists.txt",
                """
                cmake_minimum_required(VERSION 3.16)
                project(arrow_cpp_template CXX)

                set(CMAKE_CXX_STANDARD 17)
                set(CMAKE_CXX_STANDARD_REQUIRED ON)

                enable_testing()

                add_executable(app main.cpp)

                # The test binary also compiles main.cpp, but with ARROW_TEST_BUILD
                # defined so the app's own main() is compiled out and only the pure
                # function is linked in.
                add_executable(tests test/test_main.cpp main.cpp)
                target_compile_definitions(tests PRIVATE ARROW_TEST_BUILD)

                add_test(NAME TemplateTests COMMAND tests)
                """.trimIndent(),
            ),
            TemplateFile(
                "main.cpp",
                """
                #include <iostream>

                // Pure function kept free of I/O so the test binary can exercise it.
                int add(int a, int b) {
                    return a + b;
                }

                #ifndef ARROW_TEST_BUILD
                int main() {
                    std::cout << "Hello from Arrow IDE! 2 + 3 = " << add(2, 3) << std::endl;
                    return 0;
                }
                #endif
                """.trimIndent(),
            ),
            TemplateFile(
                "test/test_main.cpp",
                """
                #include <iostream>

                int add(int a, int b);

                int main() {
                    const bool ok = add(2, 3) == 5 && add(-1, 1) == 0 && add(0, 0) == 0;
                    std::cout << (ok ? "All tests passed" : "TESTS FAILED") << std::endl;
                    return ok ? 0 : 1;
                }
                """.trimIndent(),
            ),
            TemplateFile(
                ".gitignore",
                """
                build/
                """.trimIndent(),
            ),
        ),
    )

    // --------------------------------------------------------- static-website

    private val staticWebsite = ProjectTemplate(
        id = "static-website",
        name = "Static Website",
        description = "Plain HTML, CSS and JavaScript website - no toolchain needed",
        requiredToolId = null,
        files = listOf(
            TemplateFile(
                "index.html",
                """
                <!doctype html>
                <html lang="en">
                  <head>
                    <meta charset="UTF-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                    <title>My Static Website</title>
                    <link rel="stylesheet" href="style.css" />
                  </head>
                  <body>
                    <main class="card">
                      <h1 id="heading">Hello, web!</h1>
                      <p id="clock">Loading current time...</p>
                      <button id="refresh">Refresh</button>
                    </main>
                    <script src="script.js"></script>
                  </body>
                </html>
                """.trimIndent(),
            ),
            TemplateFile(
                "style.css",
                """
                * {
                  box-sizing: border-box;
                }

                body {
                  margin: 0;
                  min-height: 100vh;
                  display: grid;
                  place-items: center;
                  font-family: system-ui, sans-serif;
                  background: #f4f6fa;
                  color: #222;
                }

                .card {
                  background: #fff;
                  padding: 2rem 3rem;
                  border-radius: 12px;
                  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
                  text-align: center;
                }

                .card h1 {
                  color: #2f6fde;
                  margin-top: 0;
                }

                .card button {
                  padding: 0.5rem 1.5rem;
                  border: none;
                  border-radius: 6px;
                  background: #2f6fde;
                  color: #fff;
                  font-size: 1rem;
                  cursor: pointer;
                }
                """.trimIndent(),
            ),
            TemplateFile(
                "script.js",
                """
                // Real behavior: keeps a clock in sync and reacts to a button click.

                function formatTime(date) {
                  return date.toLocaleTimeString();
                }

                function updateClock() {
                  document.getElementById("clock").textContent =
                    "Current time: " + formatTime(new Date());
                }

                document.getElementById("refresh").addEventListener("click", updateClock);

                updateClock();
                setInterval(updateClock, 1000);
                """.trimIndent(),
            ),
        ),
    )

    // ----------------------------------------------------------------- index

    // Kept below the private template properties on purpose: object
    // members initialize in declaration order, so `all` must come after
    // every template it references.

    /** All built-in templates, in menu order. */
    val all: List<ProjectTemplate> = listOf(
        nodeBasic,
        nodeVite,
        reactVite,
        pythonBasic,
        cppCmake,
        staticWebsite,
    )

    private val byIdMap: Map<String, ProjectTemplate> = all.associateBy { it.id }

    /** Template with [id], or null when the id is unknown. */
    fun byId(id: String): ProjectTemplate? = byIdMap[id]
}
