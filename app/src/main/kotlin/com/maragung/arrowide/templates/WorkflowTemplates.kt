package com.maragung.arrowide.templates

/** Metadata for a GitHub Actions workflow a project can adopt (plan #48). */
data class WorkflowTemplate(
    val id: String,
    val name: String,
    val description: String,
    /** Suggested file name under `.github/workflows/`, e.g. "node-ci.yml". */
    val defaultFileName: String,
)

/**
 * Built-in GitHub Actions workflow templates (plan #48).
 *
 * [generate] returns complete, valid YAML using only first-party actions
 * (actions/checkout, actions/setup-*, actions/upload-artifact) on
 * ubuntu-latest, triggered by push and pull_request (or a tag push for
 * releases). No placeholder markers: every step is runnable as-is, and
 * users adapt steps to their project when needed.
 */
object WorkflowTemplates {

    // ------------------------------------------------------------------- YAML
    // Private generators come first: object members initialize in
    // declaration order and `yamlById` below references them.

    private val NODE_CI = """
        name: Node CI

        on:
          push:
            branches: [main]
          pull_request:

        jobs:
          build:
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4

              - uses: actions/setup-node@v4
                with:
                  node-version: 20

              - name: Install dependencies
                run: npm ci

              - name: Build
                run: npm run build --if-present
    """.trimIndent()

    private val PYTHON_CI = """
        name: Python CI

        on:
          push:
            branches: [main]
          pull_request:

        jobs:
          test:
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4

              - uses: actions/setup-python@v5
                with:
                  python-version: "3.12"

              - name: Install dependencies
                run: pip install -r requirements.txt

              - name: Run tests
                run: python -m pytest
    """.trimIndent()

    private val CPP_CI = """
        name: C++ CI

        on:
          push:
            branches: [main]
          pull_request:

        jobs:
          build:
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4

              - name: Configure
                run: cmake -B build

              - name: Build
                run: cmake --build build

              - name: Test
                run: ctest --test-dir build --output-on-failure
    """.trimIndent()

    private val ANDROID_CI = """
        name: Android CI

        on:
          push:
            branches: [main]
          pull_request:

        jobs:
          build:
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4

              - uses: actions/setup-java@v4
                with:
                  distribution: temurin
                  java-version: 17

              - name: Build with Gradle
                run: |
                  chmod +x gradlew
                  ./gradlew build
    """.trimIndent()

    private val BUILD_RELEASE = """
        name: Build Release

        on:
          push:
            tags:
              - "v*"

        jobs:
          release:
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4

              - name: Package source at tag
                run: |
                  mkdir -p dist
                  tar -czf "dist/${'$'}{{ github.event.repository.name }}-${'$'}{{ github.ref_name }}.tar.gz" --exclude=dist .

              - name: Upload release artifact
                uses: actions/upload-artifact@v4
                with:
                  name: release-${'$'}{{ github.ref_name }}
                  path: dist/
    """.trimIndent()

    private val DEPLOY = """
        name: Deploy

        on:
          push:
            branches: [main]

        jobs:
          deploy:
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4

              - name: Package deploy bundle
                run: |
                  mkdir -p deploy
                  tar -czf deploy/bundle.tar.gz --exclude=deploy .

              - name: Upload deploy bundle
                uses: actions/upload-artifact@v4
                with:
                  name: deploy
                  path: deploy/bundle.tar.gz
    """.trimIndent()

    // ------------------------------------------------------------------ index

    val all: List<WorkflowTemplate> = listOf(
        WorkflowTemplate(
            id = "node-ci",
            name = "Node.js CI",
            description = "Installs dependencies with npm ci and builds the project",
            defaultFileName = "node-ci.yml",
        ),
        WorkflowTemplate(
            id = "python-ci",
            name = "Python CI",
            description = "Installs requirements.txt and runs pytest",
            defaultFileName = "python-ci.yml",
        ),
        WorkflowTemplate(
            id = "cpp-ci",
            name = "C/C++ CI",
            description = "Configures, builds and tests a CMake project",
            defaultFileName = "cpp-ci.yml",
        ),
        WorkflowTemplate(
            id = "android-ci",
            name = "Android CI",
            description = "Builds an Android project with the Gradle wrapper on JDK 17",
            defaultFileName = "android-ci.yml",
        ),
        WorkflowTemplate(
            id = "build-release",
            name = "Build Release",
            description = "Packages the tagged source and uploads it as a release artifact",
            defaultFileName = "build-release.yml",
        ),
        WorkflowTemplate(
            id = "deploy",
            name = "Deploy",
            description = "Packages the project and uploads a deploy bundle artifact",
            defaultFileName = "deploy.yml",
        ),
    )

    private val yamlById: Map<String, String> = mapOf(
        "node-ci" to NODE_CI,
        "python-ci" to PYTHON_CI,
        "cpp-ci" to CPP_CI,
        "android-ci" to ANDROID_CI,
        "build-release" to BUILD_RELEASE,
        "deploy" to DEPLOY,
    )

    init {
        val templateIds = all.map { it.id }.toSet()
        check(yamlById.keys == templateIds) {
            "Every workflow template must have exactly one YAML generator"
        }
    }

    private val byIdMap: Map<String, WorkflowTemplate> = all.associateBy { it.id }

    /** Workflow with [id], or null when the id is unknown. */
    fun byId(id: String): WorkflowTemplate? = byIdMap[id]

    /**
     * Returns the complete workflow YAML for [template] — write it to
     * `<project>/.github/workflows/<defaultFileName>`.
     */
    fun generate(template: WorkflowTemplate): String =
        yamlById[template.id]
            ?: error("No workflow YAML for template id '${template.id}'")
}
