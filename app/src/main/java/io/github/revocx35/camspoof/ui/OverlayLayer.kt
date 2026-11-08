package io.github.revocx35.camspoof.ui

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.revocx35.camspoof.media.OverlayItem
import io.github.revocx35.camspoof.media.OverlayKind
import io.github.revocx35.camspoof.media.OverlayStore
import kotlin.math.abs

/**
 * Renders the overlays over the camera preview and handles direct manipulation:
 *  - one-finger drag -> move,
 *  - two-finger pinch -> resize,
 *  - two-finger twist -> rotate,
 *  - tap -> select.
 *
 * Transforms are written back to each [OverlayItem] in normalized terms, so the compositor
 * reproduces the exact same placement in the injected frame.
 */
@Composable
fun OverlayLayer(store: OverlayStore, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val density = LocalDensity.current

        store.items.forEach { item ->
            val wPx = (item.widthFrac * cw).coerceAtLeast(32f)
            val hPx = (wPx / if (item.aspect > 0f) item.aspect else 1f).coerceAtLeast(32f)
            val selected = store.selectedId == item.id

            Box(
                Modifier
                    .size(
                        with(density) { wPx.toDp() },
                        with(density) { hPx.toDp() },
                    )
                    .graphicsLayer {
                        // Position the item's centre at (centerX, centerY) of the container.
                        translationX = item.centerX * cw - wPx / 2f
                        translationY = item.centerY * ch - hPx / 2f
                        rotationZ = item.rotationDeg
                    }
                    .then(if (selected) Modifier.border(2.dp, Color(0xFF4FC3F7)) else Modifier)
                    .pointerInput(item.id) {
                        detectTapGestures(onTap = { store.selectedId = item.id })
                    }
                    .pointerInput(item.id) {
                        detectTransformGestures { _, pan, zoom, rotation ->
                            store.selectedId = item.id
                            item.centerX = (item.centerX + pan.x / cw).coerceIn(0f, 1f)
                            item.centerY = (item.centerY + pan.y / ch).coerceIn(0f, 1f)
                            if (abs(zoom - 1f) > 0.0001f) {
                                item.widthFrac = (item.widthFrac * zoom).coerceIn(0.05f, 3f)
                            }
                            item.rotationDeg += rotation
                        }
                    },
            ) {
                when (item.kind) {
                    OverlayKind.IMAGE -> ImageOverlayContent(item)
                    OverlayKind.VIDEO -> VideoOverlayContent(item)
                }
            }
        }
    }
}

@Composable
private fun ImageOverlayContent(item: OverlayItem) {
    val bmp = item.imageBitmap ?: return
    androidx.compose.foundation.Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * A looping [MediaPlayer] rendered to a [TextureView]. The TextureView doubles as the overlay's
 * [io.github.revocx35.camspoof.media.FrameSource] (`getBitmap`) so the compositor can sample the
 * current video frame, and its volume tracks the mixer.
 */
@Composable
private fun VideoOverlayContent(item: OverlayItem) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val texture = TextureView(ctx)
            var player: MediaPlayer? = null
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    player = MediaPlayer().apply {
                        setSurface(Surface(st))
                        setDataSource(ctx, item.uri)
                        isLooping = true
                        setOnVideoSizeChangedListener { _, vw, vh ->
                            if (vw > 0 && vh > 0) item.aspect = vw.toFloat() / vh.toFloat()
                        }
                        setOnPreparedListener {
                            setVolume(item.effectiveVolume, item.effectiveVolume)
                            start()
                        }
                        prepareAsync()
                    }
                    item.frameSource = io.github.revocx35.camspoof.media.FrameSource { texture.bitmap }
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    player?.release(); player = null; item.frameSource = null; return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    player?.setVolume(item.effectiveVolume, item.effectiveVolume)
                }
            }
            texture
        },
    )
}
