package io.github.revocx35.camspoof.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun CameraScreen(vm: CamSpoofViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.onStart() }

    LaunchedEffect(Unit) {
        permissions.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO))
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(vm::addImage) }
    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { vm.addVideo(it, 16f / 9f) } }

    var showTargets by remember { mutableStateOf(false) }
    var showMixer by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Camera preview (compositor's camera source: previewView.bitmap).
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }.also { vm.bindPreview(lifecycleOwner, it) }
            },
        )

        // Draggable / resizable / rotatable overlays.
        OverlayLayer(vm.overlays, Modifier.fillMaxSize())

        // Controls.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xCC101418)).safeDrawingPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Lens chips.
            if (vm.lenses.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.lenses.forEach { lens ->
                        FilterChip(
                            selected = vm.currentLens?.cameraId == lens.cameraId,
                            onClick = { vm.selectLens(lifecycleOwner, lens) },
                            label = { Text(lens.label) },
                        )
                    }
                }
            }

            // Add media + delete selected.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("+ Image")
                }
                OutlinedButton(
                    onClick = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("+ Video")
                }
                vm.overlays.selected?.let { sel ->
                    OutlinedButton(onClick = { vm.overlays.remove(sel) }) {
                        Text("Delete")
                    }
                }
            }

            // Target + spoof toggle.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showTargets = true }, modifier = Modifier.weight(1f)) {
                    Text(vm.selectedTarget?.label ?: "Choose target app", maxLines = 1)
                }
                OutlinedButton(onClick = { showMixer = !showMixer }) { Text(if (showMixer) "Hide mix" else "Mix") }
                OutlinedButton(onClick = { vm.loadDiagnostics() }, enabled = vm.hasRoot) { Text("Diag") }
            }

            Button(
                onClick = { vm.toggleSpoof() },
                enabled = !vm.busy && vm.hasRoot,
                modifier = Modifier.fillMaxWidth(),
                colors = if (vm.spoofing) ButtonDefaults.buttonColors(containerColor = Color(0xFFB00020)) else ButtonDefaults.buttonColors(),
            ) {
                if (vm.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (vm.spoofing) "Stop spoofing" else "Start spoofing")
            }

            if (!vm.hasRoot) {
                Text("Root not available — grant CamSpoof root in KernelSU.", color = Color(0xFFFFB74D),
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(vm.status, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Color(0xFFBFC7CF))

            if (showMixer) {
                VolumeMixer(vm.overlays, vm.micGain, onMicGain = { vm.micGain = it })
            }
        }

        if (showTargets) {
            TargetPickerDialog(
                targets = vm.targets,
                onPick = { vm.selectedTarget = it; showTargets = false },
                onDismiss = { showTargets = false },
            )
        }

        vm.diagText?.let { text ->
            DiagnosticsDialog(
                text = text,
                onShare = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "CamSpoof diagnostics")
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(android.content.Intent.createChooser(send, "Share diagnostics"))
                },
                onDismiss = { vm.clearDiagnostics() },
            )
        }
    }
}
