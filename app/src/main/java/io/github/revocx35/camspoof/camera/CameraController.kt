package io.github.revocx35.camspoof.camera

import android.content.Context
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** A selectable physical camera on the device. */
data class LensInfo(
    val cameraId: String,
    val label: String,
    val facing: Int, // CameraSelector.LENS_FACING_*
)

/**
 * Thin wrapper over CameraX that lists the device's physical lenses and binds a [Preview] of the
 * chosen one to a [PreviewView]. The PreviewView is the compositor's camera-frame source
 * (`previewView.bitmap`), so no separate ImageAnalysis pipeline is needed for stage 1.
 */
class CameraController(private val context: Context) {

    private var provider: ProcessCameraProvider? = null

    suspend fun init(): List<LensInfo> {
        val p = awaitProvider()
        provider = p
        return enumerate(p)
    }

    private fun enumerate(p: ProcessCameraProvider): List<LensInfo> {
        val lenses = p.availableCameraInfos.mapNotNull { info ->
            val id = runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull() ?: return@mapNotNull null
            LensInfo(id, labelFor(info, id), facingOf(info))
        }
        // Stable order: back lenses first, then front, by id.
        return lenses.sortedWith(compareBy({ it.facing != CameraSelector.LENS_FACING_BACK }, { it.cameraId }))
    }

    private fun facingOf(info: CameraInfo): Int = when {
        info.lensFacing == CameraSelector.LENS_FACING_FRONT -> CameraSelector.LENS_FACING_FRONT
        else -> CameraSelector.LENS_FACING_BACK
    }

    private fun labelFor(info: CameraInfo, id: String): String {
        val facing = if (facingOf(info) == CameraSelector.LENS_FACING_FRONT) "Front" else "Back"
        return "$facing · cam $id"
    }

    /** Bind a preview of the lens with [cameraId] to [previewView]. Returns the resolved lens. */
    fun bind(lifecycleOwner: LifecycleOwner, previewView: PreviewView, cameraId: String) {
        val p = provider ?: return
        p.unbindAll()
        val selector = CameraSelector.Builder()
            .addCameraFilter { infos ->
                infos.filter { runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() == cameraId }
            }
            .build()
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        p.bindToLifecycle(lifecycleOwner, selector, preview)
    }

    fun unbind() {
        provider?.unbindAll()
    }

    private suspend fun awaitProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
}
