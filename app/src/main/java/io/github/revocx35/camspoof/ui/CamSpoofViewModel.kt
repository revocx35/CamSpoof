package io.github.revocx35.camspoof.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import io.github.revocx35.camspoof.camera.CameraController
import io.github.revocx35.camspoof.camera.DrawOverlay
import io.github.revocx35.camspoof.camera.FrameCompositor
import io.github.revocx35.camspoof.camera.LensInfo
import io.github.revocx35.camspoof.camera.Scene
import io.github.revocx35.camspoof.camera.SceneProvider
import io.github.revocx35.camspoof.inject.AppRepository
import io.github.revocx35.camspoof.inject.InjectionResult
import io.github.revocx35.camspoof.inject.Injector
import io.github.revocx35.camspoof.inject.TargetApp
import io.github.revocx35.camspoof.media.OverlayKind
import io.github.revocx35.camspoof.media.OverlayStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Output resolution of the injected feed (also the compositor canvas size). */
private const val OUT_W = 1280
private const val OUT_H = 720
private const val OUT_FPS = 30

class CamSpoofViewModel(app: Application) : AndroidViewModel(app), SceneProvider {

    private val controller = CameraController(app)
    private val injector = Injector(app)
    val overlays = OverlayStore()

    var lenses by mutableStateOf<List<LensInfo>>(emptyList())
        private set
    var currentLens by mutableStateOf<LensInfo?>(null)
        private set

    var hasRoot by mutableStateOf(false)
        private set
    var micGain by mutableStateOf(1f)

    /** Injection / feed status surfaced in the UI. */
    var status by mutableStateOf("Not spoofing. Pick a target app and tap Start.")
        private set
    var spoofing by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set

    var targets by mutableStateOf<List<TargetApp>>(emptyList())
        private set
    var selectedTarget by mutableStateOf<TargetApp?>(null)

    /** Agent diagnostics log text, shown in a dialog when non-null. */
    var diagText by mutableStateOf<String?>(null)

    private var previewView: PreviewView? = null
    private var compositor: FrameCompositor? = null

    fun onStart() {
        viewModelScope.launch {
            hasRoot = withContext(Dispatchers.IO) { injector.hasRoot() }
            lenses = runCatching { controller.init() }.getOrDefault(emptyList())
            if (currentLens == null) currentLens = lenses.firstOrNull()
            targets = withContext(Dispatchers.IO) { AppRepository.installedTargets(getApplication()) }
        }
    }

    fun bindPreview(owner: LifecycleOwner, view: PreviewView) {
        previewView = view
        currentLens?.let { controller.bind(owner, view, it.cameraId) }
    }

    fun selectLens(owner: LifecycleOwner, lens: LensInfo) {
        currentLens = lens
        previewView?.let { controller.bind(owner, it, lens.cameraId) }
    }

    fun addImage(uri: Uri) {
        viewModelScope.launch {
            val (bmp, aspect) = withContext(Dispatchers.IO) { decodeImage(uri) }
            val item = overlays.add(OverlayKind.IMAGE, uri, aspect)
            item.imageBitmap = bmp
        }
    }

    fun addVideo(uri: Uri, aspect: Float) {
        overlays.add(OverlayKind.VIDEO, uri, aspect)
    }

    private fun decodeImage(uri: Uri): Pair<Bitmap?, Float> = try {
        val src = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { dec, _, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            dec.isMutableRequired = false
        }
        val safe = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
        safe to (safe.width.toFloat() / safe.height.toFloat())
    } catch (e: Exception) {
        null to 1f
    }

    // --- spoofing lifecycle ---

    fun toggleSpoof() {
        if (spoofing) stopSpoof() else startSpoof()
    }

    private fun startSpoof() {
        val target = selectedTarget
        if (target == null) {
            status = "Choose a target app first."
            return
        }
        busy = true
        viewModelScope.launch {
            val setup = withContext(Dispatchers.IO) {
                injector.prepareFeed()
            }
            // Begin writing frames before injecting so the agent sees data immediately.
            val comp = FrameCompositor(OUT_W, OUT_H, OUT_FPS, this@CamSpoofViewModel)
            val err = comp.start()
            if (err != null) {
                status = err
                busy = false
                return@launch
            }
            compositor = comp
            val result: InjectionResult = withContext(Dispatchers.IO) { injector.inject(target) }
            status = if (result.success) {
                result.summary + " Now OPEN THE CAMERA in the target app (if it was already open, close and reopen it). Tap Diag to see how it uses the camera."
            } else {
                result.summary
            }
            spoofing = result.success
            if (!result.success) {
                comp.stop()
                compositor = null
            }
            busy = false
        }
    }

    fun loadDiagnostics() {
        diagText = "Loading…"
        viewModelScope.launch {
            diagText = withContext(Dispatchers.IO) { injector.readDiagnostics() }
        }
    }

    fun clearDiagnostics() { diagText = null }

    private fun stopSpoof() {
        compositor?.stop()
        compositor = null
        spoofing = false
        status = "Stopped. The target app keeps running; restart it to drop the hook."
    }

    override fun snapshot(): Scene {
        val cam = runCatching { previewView?.bitmap }.getOrNull()
        val mirror = currentLens?.facing == CameraSelector.LENS_FACING_FRONT
        val draws = overlays.items.map { item ->
            val bmp = when (item.kind) {
                OverlayKind.IMAGE -> item.imageBitmap
                OverlayKind.VIDEO -> item.frameSource?.currentFrame(null)
            }
            DrawOverlay(bmp, item.centerX, item.centerY, item.widthFrac, item.aspect, item.rotationDeg)
        }
        return Scene(cam, draws, mirror)
    }

    override fun onCleared() {
        compositor?.stop()
        controller.unbind()
    }
}
