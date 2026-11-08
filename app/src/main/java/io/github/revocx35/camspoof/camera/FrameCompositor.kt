package io.github.revocx35.camspoof.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.revocx35.camspoof.feed.FeedFormat
import io.github.revocx35.camspoof.feed.FeedWriter
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** One drawable overlay in a captured [Scene], already resolved to a bitmap + normalized transform. */
data class DrawOverlay(
    val bitmap: Bitmap?,
    val centerX: Float,
    val centerY: Float,
    val widthFrac: Float,
    val aspect: Float,
    val rotationDeg: Float,
)

/** A snapshot of everything to draw for one output frame. Built on the main thread. */
data class Scene(
    val camera: Bitmap?,
    val overlays: List<DrawOverlay>,
    val mirror: Boolean,
)

/** The UI implements this; [snapshot] is always invoked on the main thread. */
fun interface SceneProvider {
    fun snapshot(): Scene
}

/**
 * Composites camera + overlays into [FeedFormat.PATH] at a target frame rate. The scene is pulled
 * from the UI on the main thread (CameraX `PreviewView.bitmap` and `TextureView.bitmap` must be
 * read there); the pixel composite and the memory-mapped write run on a dedicated worker thread so
 * the UI stays responsive.
 *
 * Canvas-based (CPU) compositing is deliberately simple for this first cut; a GL path is the
 * planned optimization (see ARCHITECTURE.md "Performance").
 */
class FrameCompositor(
    private val outWidth: Int,
    private val outHeight: Int,
    private val targetFps: Int,
    private val sceneProvider: SceneProvider,
) {
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(output)
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val yuv = YuvConvert(outWidth, outHeight)
    private val nv21 = ByteArray(FeedFormat.nv21Size(outWidth, outHeight))

    @Volatile private var running = false
    private var writer: FeedWriter? = null
    private var worker: Thread? = null

    /** Opens the feed and starts compositing. @return null on success, else an error message. */
    fun start(): String? {
        val w = try {
            FeedWriter.open(outWidth, outHeight)
        } catch (e: Exception) {
            return "Feed not writable (${e.message}). Run the root setup first."
        }
        writer = w
        running = true
        worker = thread(name = "camspoof-compositor") { loop(w) }
        return null
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
        writer?.close()
        writer = null
    }

    private fun loop(w: FeedWriter) {
        val frameNanos = 1_000_000_000L / targetFps
        while (running) {
            val t0 = System.nanoTime()
            try {
                val scene = fetchSceneOnMain()
                composite(scene)
                yuv.toNv21(output, nv21)
                w.writeFrame(nv21, scene.mirror)
            } catch (e: Exception) {
                Log.w("CamSpoof", "composite failed", e)
            }
            val elapsed = System.nanoTime() - t0
            val sleep = (frameNanos - elapsed) / 1_000_000L
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun fetchSceneOnMain(): Scene {
        val holder = arrayOfNulls<Scene>(1)
        val latch = CountDownLatch(1)
        main.post {
            holder[0] = try {
                sceneProvider.snapshot()
            } catch (e: Exception) {
                Scene(null, emptyList(), false)
            }
            latch.countDown()
        }
        latch.await()
        return holder[0] ?: Scene(null, emptyList(), false)
    }

    private fun composite(scene: Scene) {
        canvas.drawColor(Color.BLACK)
        scene.camera?.let { cam ->
            // Center-crop the camera frame to fill the output.
            val scale = maxOf(outWidth.toFloat() / cam.width, outHeight.toFloat() / cam.height)
            val dw = cam.width * scale
            val dh = cam.height * scale
            val left = (outWidth - dw) / 2f
            val top = (outHeight - dh) / 2f
            srcRect.set(0, 0, cam.width, cam.height)
            dstRect.set(left, top, left + dw, top + dh)
            canvas.drawBitmap(cam, srcRect, dstRect, paint)
        }
        for (ov in scene.overlays) {
            val bmp = ov.bitmap ?: continue
            val wPx = ov.widthFrac * outWidth
            val hPx = wPx / (if (ov.aspect > 0f) ov.aspect else 1f)
            canvas.save()
            canvas.translate(ov.centerX * outWidth, ov.centerY * outHeight)
            canvas.rotate(ov.rotationDeg)
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(-wPx / 2f, -hPx / 2f, wPx / 2f, hPx / 2f)
            canvas.drawBitmap(bmp, srcRect, dstRect, paint)
            canvas.restore()
        }
    }
}
