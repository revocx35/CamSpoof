package io.github.revocx35.camspoof.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shows the agent's recon log (how the target used the camera) with a Share action. */
@Composable
fun DiagnosticsDialog(
    text: String,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onShare) { Text("Share") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Diagnostics") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "How the target app used the camera since the last Start. Share this so it can be analysed.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())
                ) {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        },
    )
}
