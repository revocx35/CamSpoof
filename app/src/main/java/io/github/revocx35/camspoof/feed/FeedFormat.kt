package io.github.revocx35.camspoof.feed

/**
 * The on-disk layout of the shared frame buffer that CamSpoof's compositor writes and the Frida
 * agent (running inside the target app) reads. It is a single memory-mapped file used as a
 * one-slot, lock-free seqlock:
 *
 *   - Writer: bump [OFF_SEQ] to an odd value, write header + frame bytes, bump [OFF_SEQ] to the
 *     next even value.
 *   - Reader: read seq (retry while odd), copy the frame, read seq again; if it changed, retry.
 *
 * This keeps readers tear-free without any cross-process lock. The same constants are mirrored in
 * `agent/index.js` — keep the two in step (see AGENTS.md).
 *
 * The file lives at [PATH], created 0666 by the root setup step so both the CamSpoof app uid and
 * the (non-root) target app uid can map it.
 */
object FeedFormat {
    const val DIR = "/data/local/tmp/camspoof"
    const val PATH = "$DIR/feed"
    const val AUDIO_PATH = "$DIR/audio"

    const val MAGIC = 0x31465343 // 'C','S','F','1' little-endian

    // Header field byte offsets.
    const val OFF_MAGIC = 0
    const val OFF_VERSION = 4
    const val OFF_WIDTH = 8
    const val OFF_HEIGHT = 12
    const val OFF_FORMAT = 16
    const val OFF_STRIDE = 20
    const val OFF_SIZE = 24
    const val OFF_SEQ = 28
    const val OFF_FLAGS = 32
    const val HEADER_SIZE = 64

    const val VERSION = 1

    // Pixel formats.
    const val FMT_RGBA_8888 = 1
    const val FMT_NV21 = 2

    // Flags bitmask.
    const val FLAG_MIRROR = 0x1 // front lens: horizontally mirrored

    /** Bytes in an NV21 (YUV 4:2:0, Y plane + interleaved VU) frame of [width] x [height]. */
    fun nv21Size(width: Int, height: Int): Int = width * height * 3 / 2

    /** Bytes needed for one NV21 frame plus the header. */
    fun fileSize(width: Int, height: Int): Long =
        HEADER_SIZE.toLong() + nv21Size(width, height).toLong()
}
