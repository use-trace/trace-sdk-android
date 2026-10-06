package io.usetrace.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The payload, and the hand rolled JSON that carries it. There is no JSON library in this SDK, so the escaper is
 * the SDK's own code and these tests are the only thing standing between a campaign name and a 400.
 *
 * Every assertion parses the body rather than searching it for a substring. A writer that merely contains the right
 * characters but produces a document no parser accepts is worse than the dependency it saved: the server rejects
 * the whole event and the install is lost. Robolectric is here for `org.json`, which is Android's own parser, so
 * what passes here is what the device would produce.
 */
@RunWith(RobolectricTestRunner::class)
class EventTest {

    private val key = "auk_app_0123456789abcdef0123456789abcdef"

    private fun firstOpen(referrer: String? = null) = Event(
        type = EventType.FIRST_OPEN,
        anonUserKey = key,
        consentStatus = ConsentState.GRANTED,
        appVersion = "1.4.2",
        installReferrer = referrer,
    )

    @Test
    fun `a first open carries what the server needs to call it an app install`() {
        val body = JSONObject(firstOpen().toJson())

        assertEquals("FIRST_OPEN", body.getString("event_type"))
        assertEquals("app", body.getString("source_type"))
        assertEquals("android", body.getString("platform"))
        assertEquals("play", body.getString("store"))
        assertEquals("1.4.2", body.getString("app_version"))
        assertEquals(key, body.getString("anon_user_key"))
        assertEquals("GRANTED", body.getString("consent_status"))
    }

    @Test
    fun `a field with nothing in it is left out rather than sent as null`() {
        val body = JSONObject(firstOpen().toJson())

        assertFalse("an install has no conversion value", body.has("conversion_value"))
        assertFalse("an install has no referrer when the Play Store would not supply one", body.has("install_referrer"))
    }

    @Test
    fun `the timestamp is the shape the server will accept`() {
        val timestamp = JSONObject(firstOpen().toJson()).getString("timestamp")

        // The server validates it with class-validator's IsDateString, so a home grown format fails the whole
        // event, not just the field. Milliseconds and a Z, in UTC.
        assertTrue(
            "unexpected timestamp: $timestamp",
            Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$").matches(timestamp),
        )
    }

    @Test
    fun `a referrer is encoded byte for byte, percent encoding intact`() {
        val raw = "utm_source=google-play&utm_campaign=spring%20sale%2B10%25&gclid=EAIaIQobChMI%2Fnot-real"

        val body = JSONObject(firstOpen(referrer = raw).toJson())

        assertEquals(raw, body.getString("install_referrer"))
    }

    @Test
    fun `an awkward campaign name survives as valid json`() {
        // A quote and a backslash break a naive writer. A newline and a tab are the ones a writer that only
        // handles those two still gets wrong. A bell character is a control character JSON has no short form for.
        // The emoji and the Japanese are there because a hand rolled writer that mangles a surrogate pair produces
        // a document no parser accepts, which is the failure this whole test exists to prevent.
        val awkward = "say \"hi\" \\ then\nnew\tline \u0001bell \u007f del 😀 日本語 café"

        val event = Event(
            type = EventType.CUSTOM,
            anonUserKey = key,
            consentStatus = ConsentState.GRANTED,
            eventName = awkward,
            metadata = mapOf(awkward to awkward),
        )

        val body = JSONObject(event.toJson())
        assertEquals(awkward, body.getString("event_name"))
        assertEquals(awkward, body.getJSONObject("metadata").getString(awkward))
    }

    @Test
    fun `a conversion carries its value and its metadata`() {
        val event = Event(
            type = EventType.PURCHASE,
            anonUserKey = key,
            consentStatus = ConsentState.GRANTED,
            eventName = "checkout",
            value = 12.5,
            conversionValue = 12.5,
            conversionTypeId = "ct_42",
            metadata = mapOf("plan" to "plus"),
        )

        val body = JSONObject(event.toJson())
        assertEquals("checkout", body.getString("event_name"))
        assertEquals(12.5, body.getDouble("value"), 0.0)
        assertEquals(12.5, body.getDouble("conversion_value"), 0.0)
        assertEquals("ct_42", body.getString("conversion_type_id"))
        assertEquals("plus", body.getJSONObject("metadata").getString("plan"))
    }


    @Test
    fun `an event read back from its own json is the event that was written`() {
        // The consent gate holds events on disk in exactly this form, so the wire body is also the stored body:
        // one format, which cannot disagree with itself. Everything awkward is in here because a queue written
        // before a process died is read by the next process and nothing gets a second chance to escape it.
        val awkward = "say \"hi\" \\ then\nnew\tline 😀 日本語"
        val event = Event(
            type = EventType.PURCHASE,
            anonUserKey = key,
            consentStatus = ConsentState.UNKNOWN,
            appVersion = "1.4.2",
            installReferrer = "utm_source=google-play&gclid=EAIaIQobChMI%2Fnot-real",
            eventName = awkward,
            value = 12.5,
            conversionTypeId = "ct_42",
            conversionValue = 12.5,
            metadata = mapOf(awkward to awkward),
        )

        assertEquals(event, Event.fromJson(event.toJson()))
    }

    @Test
    fun `an event carried over one line holds no newline, so a line is a whole event`() {
        val event = Event(
            type = EventType.CUSTOM,
            anonUserKey = key,
            consentStatus = ConsentState.UNKNOWN,
            eventName = "two\nlines\r\nand a return",
        )

        assertFalse("a stored event spanning two lines would be read back as two broken ones", "\n" in event.toJson())
    }

    @Test
    fun `a half written or unparsable line is not an event`() {
        // The last line of a queue file written by a process that was killed mid write. It is dropped, because the
        // alternative is an exception on the launch path of somebody else's app.
        assertNull(Event.fromJson("{\"event_type\":\"FIRST_OPEN\",\"anon_user_k"))
        assertNull(Event.fromJson(""))
        // An event type a later SDK version sends and this one has never heard of.
        assertNull(Event.fromJson("{\"event_type\":\"SCREEN_VIEW\",\"anon_user_key\":\"$key\",\"timestamp\":\"now\"}"))
        // An event with no key could not be sent anyway: the server refuses an install without one, deliberately.
        assertNull(Event.fromJson("{\"event_type\":\"FIRST_OPEN\",\"timestamp\":\"now\"}"))
    }

    @Test
    fun `an event cannot print its own identity`() {
        val raw = "utm_source=google-play&gclid=EAIaIQobChMI%2Fnot-real"

        val printed = firstOpen(referrer = raw).toString()

        // A data class would hand the install id and the referrer to anything that interpolated an event into a
        // string, which is the easiest way for an identity to reach a log line or a crash report. The log would
        // strip it, but the accident should not get that far: the type itself does not say.
        assertFalse("an event's toString revealed the install id", key in printed)
        assertFalse("an event's toString revealed the referrer", "gclid" in printed)
        assertTrue("it should still say what the event was", "FIRST_OPEN" in printed)
    }
}
