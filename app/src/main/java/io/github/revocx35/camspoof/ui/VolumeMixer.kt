package io.github.revocx35.camspoof.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.revocx35.camspoof.media.OverlayKind
import io.github.revocx35.camspoof.media.OverlayStore

/**
 * Volumes section: the microphone channel plus one channel per video overlay. Video sliders drive
 * the live MediaPlayer volume; the mic gain is exported for the audio-injection path (see
 * ARCHITECTURE.md "Audio").
 */
@Composable
fun VolumeMixer(
    store: OverlayStore,
    micGain: Float,
    onMicGain: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Volumes", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)

        Channel(
            label = "Microphone",
            value = micGain,
            muted = micGain <= 0f,
            onValue = onMicGain,
            onToggleMute = { onMicGain(if (micGain > 0f) 0f else 1f) },
            icon = "🎤", // 🎤
        )

        store.items.filter { it.kind == OverlayKind.VIDEO }.forEachIndexed { i, item ->
            Channel(
                label = "Video ${i + 1}",
                value = item.volume,
                muted = item.muted,
                onValue = { item.volume = it; item.muted = it <= 0f },
                onToggleMute = { item.muted = !item.muted },
                icon = "🎬", // 🎬 (video)
            )
        }
    }
}

@Composable
private fun Channel(
    label: String,
    value: Float,
    muted: Boolean,
    onValue: (Float) -> Unit,
    onToggleMute: () -> Unit,
    icon: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = onToggleMute) {
            Text(if (muted) "🔇" else icon) // 🔇 when muted
        }
        Text(label, Modifier.width(88.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Text("${(value * 100).toInt()}%", Modifier.width(44.dp),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    }
}
