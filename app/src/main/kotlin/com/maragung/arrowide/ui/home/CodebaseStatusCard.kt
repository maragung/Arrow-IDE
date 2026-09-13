package com.maragung.arrowide.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.codebase.CodebaseStatus
import com.maragung.arrowide.codebase.CodebaseStatusProvider
import java.io.File

/**
 * Codebase awareness (plan #31): the current workspace's git branch, dirty
 * count, build system and interpreter versions, shown under the workspace
 * banner. Only real facts appear — an unknown tool is simply not shown
 * (plan #49), never a placeholder version.
 */
@Composable
fun CodebaseStatusCard(
    provider: CodebaseStatusProvider,
    workspace: File?,
    modifier: Modifier = Modifier,
) {
    val status by provider.status(workspace).collectAsState(
        initial = CodebaseStatus(
            workspacePath = workspace?.absolutePath,
            projectName = workspace?.name,
            gitBranch = null,
            gitDirtyCount = null,
            buildSystemName = null,
            nodeVersion = null,
            pythonVersion = null,
        ),
    )

    val facts = buildList {
        status.gitBranch?.let { branch ->
            add(
                branch + when (status.gitDirtyCount) {
                    null -> ""
                    0 -> " · clean"
                    else -> " · ${status.gitDirtyCount} changed"
                },
            )
        }
        status.buildSystemName?.let { add(it) }
        status.nodeVersion?.let { add("Node $it") }
        status.pythonVersion?.let { add("Python $it") }
    }
    if (facts.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            facts.forEach { fact ->
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(fact, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}
