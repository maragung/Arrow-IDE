package com.maragung.arrowide.github

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DTO parsing against realistic (trimmed) GitHub REST v3 JSON payloads —
 * unknown fields like node_id/url/user are present to prove
 * ignoreUnknownKeys, and field names follow the documented wire format.
 */
class GitHubModelsTest {

    @Test
    fun parsesUser() {
        val user = githubJson.decodeFromString<UserDto>(
            """
            {
              "login": "octocat",
              "id": 583231,
              "node_id": "MDQ6VXNlcjU4MzIzMQ==",
              "avatar_url": "https://github.com/images/error/octocat_happy.gif",
              "url": "https://api.github.com/users/octocat",
              "name": "The Octocat",
              "type": "User",
              "site_admin": false
            }
            """.trimIndent(),
        ).toModel()

        assertEquals("octocat", user.login)
        assertEquals("The Octocat", user.name)
        assertEquals("https://github.com/images/error/octocat_happy.gif", user.avatarUrl)
    }

    @Test
    fun parsesUserWithoutOptionalFields() {
        val user = githubJson.decodeFromString<UserDto>(
            """{"login": "ghost", "id": 10137}""",
        ).toModel()

        assertEquals("ghost", user.login)
        assertNull(user.name)
        assertEquals("", user.avatarUrl)
    }

    @Test
    fun parsesRepoListWithNestedOwner() {
        val repos = githubJson.decodeFromString<List<RepoDto>>(
            """
            [
              {
                "id": 1296269,
                "node_id": "MDEwOlJlcG9zaXRvcnkxMjk2MjY5",
                "name": "Hello-World",
                "full_name": "octocat/Hello-World",
                "owner": {
                  "login": "octocat",
                  "id": 583231,
                  "avatar_url": "https://github.com/images/error/octocat_happy.gif"
                },
                "private": false,
                "description": "This your first repo!",
                "default_branch": "master",
                "updated_at": "2011-01-26T19:01:12Z",
                "html_url": "https://github.com/octocat/Hello-World",
                "archived": false
              },
              {
                "id": 41986369,
                "name": "bootstrap",
                "owner": {"login": "twbs"},
                "private": true,
                "description": null,
                "default_branch": "main",
                "updated_at": "2026-09-10T08:00:00Z",
                "html_url": "https://github.com/twbs/bootstrap"
              }
            ]
            """.trimIndent(),
        ).map { it.toModel() }

        assertEquals(2, repos.size)
        val first = repos[0]
        assertEquals(1296269L, first.id)
        assertEquals("Hello-World", first.name)
        assertEquals("octocat", first.ownerLogin)
        assertEquals(false, first.isPrivate)
        assertEquals("master", first.defaultBranch)
        assertEquals("This your first repo!", first.description)
        assertEquals("2011-01-26T19:01:12Z", first.updatedAt)
        assertEquals("https://github.com/octocat/Hello-World", first.htmlUrl)

        val second = repos[1]
        assertEquals("twbs", second.ownerLogin)
        assertTrue(second.isPrivate)
        assertNull(second.description)
    }

    @Test
    fun parsesCommitFromAuthorDetail() {
        val commit = githubJson.decodeFromString<CommitDto>(
            """
            {
              "sha": "6dcb09b5b57875f334f61aebed695e2e4193db5e",
              "node_id": "MDY6Q29tbWl0NmRjYjA5YjViNTc4NzVmMzM0ZjYxYWViZWQ2OTVlMmU0MTkzZGI1ZQ==",
              "commit": {
                "message": "Fix all the bugs",
                "author": {
                  "name": "Monalisa Octocat",
                  "email": "mona@github.com",
                  "date": "2011-04-14T16:00:49Z"
                }
              },
              "author": {
                "login": "octocat",
                "id": 583231
              }
            }
            """.trimIndent(),
        ).toModel()

        assertEquals("6dcb09b5b57875f334f61aebed695e2e4193db5e", commit.sha)
        assertEquals("Fix all the bugs", commit.message)
        assertEquals("Monalisa Octocat", commit.authorName)
        assertEquals("mona@github.com", commit.authorEmail)
        assertEquals("2011-04-14T16:00:49Z", commit.date)
    }

    @Test
    fun parsesCommitFallingBackToAccountLogin() {
        val commit = githubJson.decodeFromString<CommitDto>(
            """
            {
              "sha": "abc123",
              "commit": {
                "message": "Webhook commit (no git author recorded)"
              },
              "author": {"login": "octocat-bot"}
            }
            """.trimIndent(),
        ).toModel()

        assertEquals("octocat-bot", commit.authorName)
        assertNull(commit.authorEmail)
        assertEquals("", commit.date)
    }

    @Test
    fun parsesPullRequests() {
        val pulls = githubJson.decodeFromString<List<PullRequestDto>>(
            """
            [
              {
                "number": 1347,
                "state": "open",
                "title": "new-feature",
                "body": "Please pull these changes",
                "updated_at": "2026-09-01T10:00:00Z",
                "user": {"login": "octocat"}
              }
            ]
            """.trimIndent(),
        ).map { it.toModel() }

        assertEquals(1, pulls.size)
        assertEquals(1347, pulls[0].number)
        assertEquals("new-feature", pulls[0].title)
        assertEquals("open", pulls[0].state)
        assertEquals("2026-09-01T10:00:00Z", pulls[0].updatedAt)
    }

    @Test
    fun parsesWorkflowRunsWithHeadFields() {
        val response = githubJson.decodeFromString<WorkflowRunsResponseDto>(
            """
            {
              "total_count": 1,
              "workflow_runs": [
                {
                  "id": 30433642,
                  "name": "Build Android",
                  "node_id": "MDExOldvcmtmbG93IFJ1bjMwNDMzNjQy",
                  "head_branch": "main",
                  "head_sha": "88cade7653e70d3413a071022b8a5cd8d1c5b09e",
                  "status": "completed",
                  "conclusion": "success",
                  "url": "https://api.github.com/repos/octocat/Hello-World/actions/runs/30433642",
                  "created_at": "2026-09-10T09:00:00Z",
                  "updated_at": "2026-09-10T09:05:00Z",
                  "html_url": "https://github.com/octocat/Hello-World/actions/runs/30433642"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, response.totalCount)
        val run = response.workflowRuns.single().toModel()
        assertEquals(30433642L, run.id)
        assertEquals("Build Android", run.name)
        assertEquals("completed", run.status)
        assertEquals("success", run.conclusion)
        assertEquals("main", run.headBranch)
        assertEquals("88cade7653e70d3413a071022b8a5cd8d1c5b09e", run.headSha)
        assertEquals("2026-09-10T09:00:00Z", run.createdAt)
        assertEquals("2026-09-10T09:05:00Z", run.updatedAt)
        assertEquals(
            "https://github.com/octocat/Hello-World/actions/runs/30433642",
            run.htmlUrl,
        )
    }

    @Test
    fun parsesJobsWithSteps() {
        val response = githubJson.decodeFromString<JobsResponseDto>(
            """
            {
              "total_count": 1,
              "jobs": [
                {
                  "id": 399444496,
                  "run_id": 30433642,
                  "name": "build",
                  "node_id": "MDExOkphYjM5OTQ0NDQ5Ng==",
                  "status": "completed",
                  "conclusion": "success",
                  "started_at": "2026-09-10T09:00:05Z",
                  "completed_at": "2026-09-10T09:04:55Z",
                  "steps": [
                    {
                      "name": "Set up job",
                      "number": 1,
                      "status": "completed",
                      "conclusion": "success"
                    },
                    {
                      "name": "Run npm install",
                      "number": 2,
                      "status": "completed",
                      "conclusion": "success"
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, response.totalCount)
        val job = response.jobs.single().toModel()
        assertEquals(399444496L, job.id)
        assertEquals("build", job.name)
        assertEquals("completed", job.status)
        assertEquals("success", job.conclusion)
        assertEquals("2026-09-10T09:00:05Z", job.startedAt)
        assertEquals("2026-09-10T09:04:55Z", job.completedAt)
        assertEquals(2, job.steps.size)
        assertEquals(GithubStep("Set up job", 1, "completed", "success"), job.steps[0])
        assertEquals("Run npm install", job.steps[1].name)
    }

    @Test
    fun parsesWorkflowContentEntries() {
        val entries = githubJson.decodeFromString<List<ContentEntryDto>>(
            """
            [
              {
                "name": "android.yml",
                "path": ".github/workflows/android.yml",
                "sha": "e5c9b9f",
                "size": 1234,
                "type": "file",
                "download_url": "https://raw.githubusercontent.com/octocat/Hello-World/main/.github/workflows/android.yml"
              },
              {
                "name": "templates",
                "path": ".github/workflows/templates",
                "type": "dir",
                "download_url": null
              }
            ]
            """.trimIndent(),
        ).map { it.toModel() }

        assertEquals(2, entries.size)
        assertEquals("android.yml", entries[0].name)
        assertEquals(".github/workflows/android.yml", entries[0].path)
        assertEquals("file", entries[0].type)
        assertEquals(
            "https://raw.githubusercontent.com/octocat/Hello-World/main/.github/workflows/android.yml",
            entries[0].downloadUrl,
        )
        assertEquals("dir", entries[1].type)
        assertNull(entries[1].downloadUrl)
    }
}
