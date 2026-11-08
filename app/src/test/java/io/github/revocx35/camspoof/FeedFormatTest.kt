package io.github.revocx35.camspoof

import io.github.revocx35.camspoof.feed.FeedFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedFormatTest {
    @Test
    fun nv21Size_isThreeHalvesOfPixels() {
        assertEquals(1280 * 720 * 3 / 2, FeedFormat.nv21Size(1280, 720))
    }

    @Test
    fun fileSize_isHeaderPlusFrame() {
        val w = 1280; val h = 720
        assertEquals(
            FeedFormat.HEADER_SIZE.toLong() + FeedFormat.nv21Size(w, h).toLong(),
            FeedFormat.fileSize(w, h),
        )
    }

    @Test
    fun seqOffset_matchesAgentContract() {
        // The Frida agent hard-codes OFF_SEQ=28; guard against drift.
        assertEquals(28, FeedFormat.OFF_SEQ)
        assertEquals(64, FeedFormat.HEADER_SIZE)
    }
}
