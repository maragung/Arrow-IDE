package com.maragung.arrowide.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.search.ProjectSearcher
import com.maragung.arrowide.search.SearchMatch
import com.maragung.arrowide.search.SearchOptions
import java.io.File
import kotlinx.coroutines.launch

/**
 * Project-wide search (plan #31): plain-text search across every file of
 * the open workspace (vendored dirs skipped), results as file/line rows;
 * tapping a result opens the file in the editor at that line via
 * [onOpenMatch].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    workspace: File?,
    onOpenMatch: (File, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val searcher = remember { ProjectSearcher() }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SearchMatch>?>(null) }

    fun runSearch() {
        val root = workspace ?: return
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            results = null
            return
        }
        searching = true
        scope.launch {
            results = searcher.search(
                root,
                trimmed,
                SearchOptions(caseSensitive = caseSensitive),
            )
            searching = false
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Search") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search in project") },
                    singleLine = true,
                    enabled = workspace != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { runSearch() },
                    enabled = workspace != null && query.isNotBlank(),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                FilterChip(
                    selected = caseSensitive,
                    onClick = { caseSensitive = !caseSensitive },
                    label = { Text("Match case") },
                )
                results?.let {
                    Text(
                        text = "${it.size} match" + if (it.size == 1) "" else "es",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }

            when {
                workspace == null -> Empty("Open a project first.")
                searching -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                results == null -> Empty("Type a query and press search.")
                results.orEmpty().isEmpty() -> Empty("No matches for \"${query.trim()}\".")
                else -> ResultList(
                    results = results.orEmpty(),
                    workspace = workspace,
                    onOpenMatch = onOpenMatch,
                )
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultList(
    results: List<SearchMatch>,
    workspace: File,
    onOpenMatch: (File, Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        items(results, key = { "${it.file.path}:${it.lineNumber}" }) { match ->
            ResultRow(match = match, workspace = workspace, onOpenMatch = onOpenMatch)
        }
    }
}

@Composable
private fun ResultRow(
    match: SearchMatch,
    workspace: File,
    onOpenMatch: (File, Int) -> Unit,
) {
    val relative = match.file.relativeToOrSelf(workspace).path
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenMatch(match.file, match.lineNumber) },
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = "$relative : ${match.lineNumber}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = match.lineText.trim(),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
