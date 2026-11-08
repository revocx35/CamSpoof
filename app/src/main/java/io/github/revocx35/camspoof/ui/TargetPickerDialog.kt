package io.github.revocx35.camspoof.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.revocx35.camspoof.inject.TargetApp

@Composable
fun TargetPickerDialog(
    targets: List<TargetApp>,
    onPick: (TargetApp) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, targets) {
        if (query.isBlank()) targets
        else targets.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Target app") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 10.dp)
                        ) {
                            Text(app.label, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                            Text(app.packageName, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
    )
}
