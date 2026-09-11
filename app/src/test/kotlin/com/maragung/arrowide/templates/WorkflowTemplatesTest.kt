package com.maragung.arrowide.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowTemplatesTest {

    private fun yaml(id: String): String {
        val template = WorkflowTemplates.byId(id)
        return WorkflowTemplates.generate(template!!)
    }

    // ------------------------------------------------------------------ index

    @Test
    fun `byId returns every workflow and null for unknown ids`() {
        val ids = WorkflowTemplates.all.map { it.id }
        assertEquals(
            listOf("node-ci", "python-ci", "cpp-ci", "android-ci", "build-release", "deploy"),
            ids,
        )
        for (id in ids) {
            assertEquals(id, WorkflowTemplates.byId(id)?.id)
        }
        assertNull(WorkflowTemplates.byId("nope"))
    }

    @Test
    fun `default file names are unique and end in yml`() {
        val names = WorkflowTemplates.all.map { it.defaultFileName }
        assertEquals(names.size, names.toSet().size)
        for (name in names) assertTrue(name.endsWith(".yml"))
    }

    // ------------------------------------------------------------ common shape

    @Test
    fun `every workflow is complete github actions yaml`() {
        for (template in WorkflowTemplates.all) {
            val yaml = WorkflowTemplates.generate(template)
            assertTrue("${template.id}: missing trigger", Regex("(?m)^on:").containsMatchIn(yaml))
            assertTrue("${template.id}: missing jobs", Regex("(?m)^jobs:").containsMatchIn(yaml))
            assertTrue("${template.id}: missing runner", yaml.contains("runs-on: ubuntu-latest"))
            assertTrue("${template.id}: missing checkout", yaml.contains("actions/checkout@"))
        }
    }

    @Test
    fun `ci workflows trigger on push and pull request`() {
        for (id in listOf("node-ci", "python-ci", "cpp-ci", "android-ci")) {
            val yaml = yaml(id)
            assertTrue("$id: missing push", yaml.contains("push:"))
            assertTrue("$id: missing pull_request", yaml.contains("pull_request:"))
        }
    }

    // ------------------------------------------------------------------- node

    @Test
    fun `node ci installs with npm ci and builds if present`() {
        val yaml = yaml("node-ci")
        assertTrue(yaml.contains("actions/setup-node@v4"))
        assertTrue(yaml.contains("node-version: 20"))
        assertTrue(yaml.contains("npm ci"))
        assertTrue(yaml.contains("npm run build --if-present"))
    }

    // ----------------------------------------------------------------- python

    @Test
    fun `python ci installs requirements and runs pytest`() {
        val yaml = yaml("python-ci")
        assertTrue(yaml.contains("actions/setup-python@v5"))
        assertTrue(yaml.contains("python-version:"))
        assertTrue(yaml.contains("pip install -r requirements.txt"))
        assertTrue(yaml.contains("python -m pytest"))
    }

    // -------------------------------------------------------------------- cpp

    @Test
    fun `cpp ci configures builds and tests with cmake`() {
        val yaml = yaml("cpp-ci")
        assertTrue(yaml.contains("cmake -B build"))
        assertTrue(yaml.contains("cmake --build build"))
        assertTrue(yaml.contains("ctest --test-dir build"))
    }

    // ---------------------------------------------------------------- android

    @Test
    fun `android ci sets up jdk 17 and runs the gradle wrapper`() {
        val yaml = yaml("android-ci")
        assertTrue(yaml.contains("actions/setup-java@v4"))
        assertTrue(yaml.contains("java-version: 17"))
        assertTrue(yaml.contains("./gradlew build"))
    }

    // ---------------------------------------------------------------- release

    @Test
    fun `build release triggers on version tags and uploads an artifact`() {
        val yaml = yaml("build-release")
        assertTrue(yaml.contains("tags:"))
        assertTrue(yaml.contains("- \"v*\""))
        assertTrue(yaml.contains("actions/upload-artifact@v4"))
        // Only first-party actions, no third-party release plugins.
        assertTrue(!yaml.contains("softprops"))
        assertTrue(Regex("(?m)^on:").containsMatchIn(yaml))
        // push trigger is the tag itself, not a branch.
        assertTrue(!yaml.contains("pull_request:"))
    }

    // ------------------------------------------------------------------ deploy

    @Test
    fun `deploy uploads an artifact named deploy`() {
        val yaml = yaml("deploy")
        assertTrue(yaml.contains("actions/upload-artifact@v4"))
        assertTrue(yaml.contains("name: deploy"))
        assertTrue(Regex("(?m)^on:").containsMatchIn(yaml))
    }

    @Test
    fun `workflows avoid placeholder markers`() {
        for (template in WorkflowTemplates.all) {
            val yaml = WorkflowTemplates.generate(template)
            assertTrue(
                "${template.id}: contains a placeholder",
                !yaml.contains("TODO") && !yaml.contains("placeholder") && !yaml.contains("<your"),
            )
        }
    }
}
