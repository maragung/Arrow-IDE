package com.maragung.arrowide.templates

import com.maragung.arrowide.toolchain.ToolCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectTemplatesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------ index

    @Test
    fun `byId returns every template and null for unknown ids`() {
        val ids = ProjectTemplates.all.map { it.id }
        assertEquals(
            listOf("node-basic", "node-vite", "react-vite", "python-basic", "cpp-cmake", "static-website"),
            ids,
        )
        for (id in ids) {
            assertEquals(id, ProjectTemplates.byId(id)?.id)
        }
        assertNull(ProjectTemplates.byId("does-not-exist"))
    }

    @Test
    fun `template ids and file paths are unique`() {
        val ids = ProjectTemplates.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (template in ProjectTemplates.all) {
            val paths = template.files.map { it.relativePath }
            assertEquals(paths.size, paths.toSet().size)
        }
    }

    @Test
    fun `required tools reference real catalog entries or are null`() {
        for (template in ProjectTemplates.all) {
            val tool = template.requiredToolId
            assertTrue(
                "unknown tool '${tool}' for template '${template.id}'",
                tool == null || ToolCatalog.byId(tool) != null,
            )
        }
        assertEquals(null, ProjectTemplates.byId("static-website")?.requiredToolId)
    }

    // ----------------------------------------------------------------- create

    @Test
    fun `create materializes every template into a fresh directory`() {
        for (template in ProjectTemplates.all) {
            val target = File(tmp.root, "projects/${template.id}")
            val result = ProjectTemplates.create(template, target)

            val created = result as? TemplateCreateResult.Created
            assertNotNull("template '${template.id}' failed: $result", created)
            assertEquals(template.files.size, created!!.files.size)

            val written = created.files.map {
                it.relativeTo(target.canonicalFile).invariantSeparatorsPath
            }
            assertEquals(template.files.map { it.relativePath }, written)
            for ((i, entry) in template.files.withIndex()) {
                assertEquals(entry.content, created.files[i].readText())
            }
        }
    }

    @Test
    fun `create builds nested directories`() {
        val target = File(tmp.root, "nested/cpp")
        val result = ProjectTemplates.create(ProjectTemplates.byId("cpp-cmake")!!, target)

        assertTrue(result is TemplateCreateResult.Created)
        assertTrue(File(target, "test/test_main.cpp").isFile)
        assertTrue(File(target, "CMakeLists.txt").isFile)
        assertTrue(File(target, ".gitignore").isFile)
    }

    @Test
    fun `node basic package json is valid and runnable`() {
        val target = File(tmp.root, "node")
        ProjectTemplates.create(ProjectTemplates.byId("node-basic")!!, target)

        val pkg = Json.parseToJsonElement(File(target, "package.json").readText()).jsonObject
        assertEquals("node index.js", pkg["scripts"]!!.jsonObject["start"]!!.jsonPrimitive.content)
        assertTrue(File(target, "index.js").readText().contains("module.exports"))
        // trimIndent() drops the blank closing line, so template contents
        // carry no trailing newline.
        assertEquals("node_modules/", File(target, ".gitignore").readText())
    }

    @Test
    fun `cpp cmake template declares a ctest target`() {
        val target = File(tmp.root, "cpp")
        ProjectTemplates.create(ProjectTemplates.byId("cpp-cmake")!!, target)

        val cmake = File(target, "CMakeLists.txt").readText()
        assertTrue(cmake.contains("enable_testing()"))
        assertTrue(cmake.contains("add_test("))
    }

    // ------------------------------------------------------------ refusals

    @Test
    fun `create refuses a non-empty target directory`() {
        val target = tmp.newFolder()
        File(target, "keepme.txt").writeText("user data")

        val result = ProjectTemplates.create(ProjectTemplates.byId("node-basic")!!, target)

        assertTrue(result is TemplateCreateResult.Error)
        assertTrue(
            (result as TemplateCreateResult.Error).message.contains("not empty", ignoreCase = true),
        )
        // The pre-existing content is untouched.
        assertEquals("user data", File(target, "keepme.txt").readText())
        assertFalse(File(target, "package.json").exists())
    }

    @Test
    fun `create refuses when the target is a file`() {
        val target = tmp.newFile()

        val result = ProjectTemplates.create(ProjectTemplates.byId("python-basic")!!, target)

        assertTrue(result is TemplateCreateResult.Error)
        assertTrue((result as TemplateCreateResult.Error).message.contains("not a directory"))
    }

    @Test
    fun `create reuses an existing empty directory`() {
        val target = tmp.newFolder()
        val result = ProjectTemplates.create(ProjectTemplates.byId("python-basic")!!, target)

        assertTrue(result is TemplateCreateResult.Created)
        assertTrue(File(target, "main.py").isFile)
    }

    // ------------------------------------------------------------- rollback

    @Test
    fun `create cleans up after a path traversal failure`() {
        val target = File(tmp.root, "victim")
        val evil = ProjectTemplate(
            id = "evil",
            name = "Evil",
            description = "tries to escape",
            requiredToolId = null,
            files = listOf(
                TemplateFile("ok.txt", "hello"),
                TemplateFile("../escape.txt", "should never be written"),
            ),
        )

        val result = ProjectTemplates.create(evil, target)

        assertTrue(result is TemplateCreateResult.Error)
        // Nothing the template created survives, and nothing escaped.
        assertFalse(File(target, "ok.txt").exists())
        assertFalse(File(tmp.root, "escape.txt").exists())
        assertFalse(target.exists())
    }

    @Test
    fun `create rejects a template path that resolves to the target root`() {
        val target = File(tmp.root, "rooty")
        val degenerate = ProjectTemplate(
            id = "degenerate",
            name = "Degenerate",
            description = "path equals the root",
            requiredToolId = null,
            files = listOf(TemplateFile(".", "content")),
        )

        val result = ProjectTemplates.create(degenerate, target)

        assertTrue(result is TemplateCreateResult.Error)
        assertFalse(target.exists())
    }
}
