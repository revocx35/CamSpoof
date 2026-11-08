package io.github.revocx35.camspoof.media

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList

enum class OverlayKind { IMAGE, VIDEO }

/**
 * One draggable / rotatable / resizable overlay placed on top of the camera preview and composited
 * into the injected feed.
 *
 * The transform is stored in **normalized, resolution-independent** terms so the same numbers place
 * the overlay identically in the on-screen preview and in the (differently sized) output frame:
 *  - [centerX], [centerY]: overlay centre as a fraction (0..1) of the target surface.
 *  - [widthFrac]: overlay width as a fraction (0..1) of the target surface width; the height is
 *    derived from [aspect] in pixels (`heightPx = widthPx / aspect`).
 *  - [rotationDeg]: clockwise rotation about the centre.
 *
 * Compose state is used directly so gesture updates in the UI and reads in the compositor see the
 * same values without extra plumbing.
 */
class OverlayItem(
    val id: Long,
    val kind: OverlayKind,
    val uri: Uri,
    /** Intrinsic width / height of the source media; 1f until known. */
    aspect: Float = 1f,
) {
    var aspect by mutableFloatStateOf(aspect)

    var centerX by mutableFloatStateOf(0.5f)
    var centerY by mutableFloatStateOf(0.5f)
    var widthFrac by mutableFloatStateOf(0.4f)
    var rotationDeg by mutableFloatStateOf(0f)

    /** Audio level for a VIDEO overlay, 0f..1f (ignored for images). */
    var volume by mutableFloatStateOf(1f)
    var muted by mutableStateOf(false)

    /** Live playback frame source for VIDEO overlays; set by the UI once the TextureView exists. */
    var frameSource: FrameSource? = null

    /** Decoded bitmap for IMAGE overlays; set once loaded. */
    var imageBitmap: android.graphics.Bitmap? = null

    val effectiveVolume: Float get() = if (muted) 0f else volume
}

/** Supplies the current frame of a video overlay as a Bitmap (implemented over a TextureView). */
fun interface FrameSource {
    fun currentFrame(reuse: android.graphics.Bitmap?): android.graphics.Bitmap?
}

/** Mutable, Compose-observable list of overlays with a monotonically increasing id generator. */
class OverlayStore {
    val items = emptyList<OverlayItem>().toMutableStateList()
    private var nextId = 1L

    var selectedId by mutableStateOf<Long?>(null)

    fun add(kind: OverlayKind, uri: Uri, aspect: Float): OverlayItem {
        val item = OverlayItem(nextId++, kind, uri, aspect)
        items.add(item)
        selectedId = item.id
        return item
    }

    fun remove(item: OverlayItem) {
        items.remove(item)
        if (selectedId == item.id) selectedId = items.lastOrNull()?.id
    }

    val selected: OverlayItem? get() = items.firstOrNull { it.id == selectedId }
}
