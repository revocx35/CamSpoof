package io.github.revocx35.camspoof.feed

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Writes composited NV21 frames into the shared memory-mapped [FeedFormat.PATH] file using the
 * seqlock protocol, for the Frida agent in the target app to consume.
 *
 * [open] requires the file to already exist and be writable by this app's uid — the root setup
 * step ([io.github.revocx35.camspoof.inject.Injector.prepareFeed]) creates it 0666. If it is
 * missing or not writable (no root / setup not run yet), [open] throws and the UI reports it.
 *
 * Not thread-safe; call [writeFrame] from a single rendering thread.
 */
class FeedWriter private constructor(
    private val raf: RandomAccessFile,
    private val map: MappedByteBuffer,
    val width: Int,
    val height: Int,
) {
    private val frameBytes = FeedFormat.nv21Size(width, height)
    private var seq = 0

    /**
     * Publish one NV21 frame of [width]x[height]. [mirror] marks a front-lens (horizontally
     * mirrored) frame in the header flags. [nv21] must be exactly [FeedFormat.nv21Size] bytes.
     */
    fun writeFrame(nv21: ByteArray, mirror: Boolean) {
        require(nv21.size >= frameBytes) { "frame too small: ${nv21.size} < $frameBytes" }

        // Begin write: odd sequence.
        seq += 1
        map.putInt(FeedFormat.OFF_SEQ, seq)

        map.putInt(FeedFormat.OFF_MAGIC, FeedFormat.MAGIC)
        map.putInt(FeedFormat.OFF_VERSION, FeedFormat.VERSION)
        map.putInt(FeedFormat.OFF_WIDTH, width)
        map.putInt(FeedFormat.OFF_HEIGHT, height)
        map.putInt(FeedFormat.OFF_FORMAT, FeedFormat.FMT_NV21)
        map.putInt(FeedFormat.OFF_STRIDE, width)
        map.putInt(FeedFormat.OFF_SIZE, frameBytes)
        map.putInt(FeedFormat.OFF_FLAGS, if (mirror) FeedFormat.FLAG_MIRROR else 0)

        map.position(FeedFormat.HEADER_SIZE)
        map.put(nv21, 0, frameBytes)

        // End write: next even sequence.
        seq += 1
        map.putInt(FeedFormat.OFF_SEQ, seq)
    }

    fun close() {
        try {
            raf.channel.close()
        } catch (_: IOException) {
        }
        try {
            raf.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        /**
         * Map the feed file for writing, sizing it for a [width] x [height] NV21 frame.
         * @throws IOException if the file cannot be opened/mapped (typically: root setup not done).
         */
        @Throws(IOException::class)
        fun open(width: Int, height: Int): FeedWriter {
            val raf = RandomAccessFile(FeedFormat.PATH, "rw")
            val size = FeedFormat.fileSize(width, height)
            raf.setLength(size)
            val map = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, size)
            map.order(ByteOrder.LITTLE_ENDIAN)
            return FeedWriter(raf, map, width, height)
        }
    }
}
