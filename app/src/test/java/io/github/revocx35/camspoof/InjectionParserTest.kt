package io.github.revocx35.camspoof

import io.github.revocx35.camspoof.inject.InjectionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InjectionParserTest {

    @Test
    fun success_withHooksAndReady() {
        val raw = """
            csf:pid 4531
            csf:loaded
            csf:feed 1280x720
            csf:hook android.hardware.Camera ok
            csf:hook android.media.ImageReader ok
            csf:ready
        """.trimIndent()
        val r = InjectionParser.parse(raw, "Some App")
        assertTrue(r.success)
        assertEquals("1280x720", r.feed)
        assertEquals(2, r.hooks.size)
        assertTrue(r.summary.contains("Some App"))
        assertTrue(r.summary.contains("1280x720"))
    }

    @Test
    fun noPid_isFailureWithClearMessage() {
        val r = InjectionParser.parse("csf:no-pid\n", "Some App")
        assertFalse(r.success)
        assertTrue(r.summary.contains("isn't running"))
    }

    @Test
    fun error_takesPrecedence() {
        val raw = "csf:loaded\ncsf:error feed-open-failed\ncsf:ready\n"
        val r = InjectionParser.parse(raw, "X")
        assertFalse(r.success)
        assertTrue(r.summary.contains("feed-open-failed"))
    }

    @Test
    fun readyButNoHooks_isFailure() {
        val raw = "csf:loaded\ncsf:feed 1280x720\ncsf:ready\n"
        val r = InjectionParser.parse(raw, "X")
        assertFalse(r.success)
        assertTrue(r.summary.contains("no camera hooks"))
    }

    @Test
    fun loadedButNotReady() {
        val r = InjectionParser.parse("csf:loaded\n", "X")
        assertFalse(r.success)
        assertTrue(r.summary.contains("did not become ready"))
    }

    @Test
    fun garbageOnly_isFailure() {
        val r = InjectionParser.parse("Unable to attach: process not found\n", "X")
        assertFalse(r.success)
        assertTrue(r.summary.contains("did not load"))
    }

    @Test
    fun skippedHooksAreNotCounted() {
        val raw = "csf:loaded\ncsf:hook android.hardware.Camera skip\ncsf:ready\n"
        val r = InjectionParser.parse(raw, "X")
        assertFalse(r.success)
        assertEquals(0, r.hooks.size)
    }
}
