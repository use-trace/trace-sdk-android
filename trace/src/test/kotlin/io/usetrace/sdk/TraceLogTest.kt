package io.usetrace.sdk

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SDK's log is the one place a visitor identity could leak into somebody else's crash reporter, and the rule
 * "never log the install id" is a rule a future change forgets. So these tests hold the log to a shape where
 * forgetting is not enough to leak.
 *
 * Three properties. It is silent unless the host app asked for it. It can be redirected, which is what makes an
 * assertion about log output possible at all. And a value shaped like an identity does not come out the far side,
 * whatever the call site did, which is the structural part: the sink strips it rather than trusting the caller.
 */
class TraceLogTest {

    private val lines = mutableListOf<String>()

    private fun capture() {
        TraceLog.debugLogging = true
        TraceLog.redirect { lines.add(it) }
    }

    @After
    fun reset() {
        TraceLog.debugLogging = false
        TraceLog.redirect(null)
    }

    @Test
    fun `nothing is logged unless the host app turned logging on`() {
        TraceLog.redirect { lines.add(it) }
        TraceLog.debugLogging = false

        TraceLog.log("FIRST_OPEN accepted")

        assertEquals("an SDK that writes to logcat by default is a nuisance in someone else's app", 0, lines.size)
    }

    @Test
    fun `a line is logged when the host app turned logging on`() {
        capture()

        TraceLog.log("FIRST_OPEN accepted")

        assertEquals(listOf("FIRST_OPEN accepted"), lines)
    }

    @Test
    fun `an install id cannot be logged, however it is passed in`() {
        capture()

        TraceLog.log("sent FIRST_OPEN for auk_app_0123456789abcdef0123456789abcdef")
        TraceLog.log("key 0123456789abcdef0123456789abcdef refused")

        assertTrue("the sink must strip an identity, not the call site", lines.all { TraceLog.REDACTED in it })
        assertFalse("an install id reached the log", lines.any { "0123456789abcdef" in it })
    }

    @Test
    fun `a referrer cannot be logged, because it carries the campaign and a click id`() {
        capture()

        TraceLog.log(
            "referrer utm_source=google-play&utm_campaign=spring%20sale&gclid=EAIaIQobChMI%2Fnot-real read",
        )

        assertTrue(TraceLog.REDACTED in lines.single())
        assertFalse("a campaign value reached the log", "google-play" in lines.single())
        assertFalse("a click id reached the log", "gclid" in lines.single())
    }

    @Test
    fun `an email cannot be logged, because identify refuses one and has to say so`() {
        capture()

        TraceLog.log("identify refused: someone@example.com is not a hash")

        assertTrue(TraceLog.REDACTED in lines.single())
        assertFalse("an email address reached the log", "example.com" in lines.single())
    }

    @Test
    fun `an ordinary outcome line survives intact`() {
        capture()

        // The whole point of the log is this line, so redaction must not eat it. An outcome, a status and a count
        // are what the SDK is allowed to say.
        TraceLog.log("PURCHASE refused with 400, attempt 1 of 3, giving up")

        assertEquals("PURCHASE refused with 400, attempt 1 of 3, giving up", lines.single())
    }

    @Test
    fun `a misuse of the api is reported even though logging is off`() {
        TraceLog.redirect { lines.add(it) }
        TraceLog.debugLogging = false

        TraceLog.warn("Trace.conversion was called before Trace.initialise, so nothing was sent")

        // debugLogging cannot be set before initialise, and calling the api before initialise is the one mistake
        // that happens before it. A warning only the correctly configured app can read is a warning nobody reads.
        assertEquals(
            listOf("Trace.conversion was called before Trace.initialise, so nothing was sent"),
            lines,
        )
    }

    @Test
    fun `a warning is redacted like any other line`() {
        TraceLog.redirect { lines.add(it) }
        TraceLog.debugLogging = false

        TraceLog.warn("identify refused someone@example.com")

        // Louder is not laxer. A line that skips the debug flag must not skip the sink that strips an identity.
        assertTrue(TraceLog.REDACTED in lines.single())
        assertFalse("an email address reached the log", "example.com" in lines.single())
    }
}
