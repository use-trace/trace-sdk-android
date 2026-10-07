package io.usetrace.sdk

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The transport, against a real HTTP server on a loopback port. Nothing is mocked: what these tests read is what
 * arrives on a socket, because three of the failures this guards against are invisible from the client side.
 *
 * The server answers an event with 202 and a consent call with 201, as the Trace API really does, so a transport
 * that only accepted 200 fails here rather than in production.
 */
@RunWith(RobolectricTestRunner::class)
class TransportTest {

    private val key = "auk_app_0123456789abcdef0123456789abcdef"

    /** A real Play Store referrer, percent encoding and click id intact. */
    private val referrer =
        "utm_source=google-play&utm_medium=cpc&utm_campaign=spring%20sale%2B10%25&gclid=EAIaIQobChMI%2Fnot-real"

    private var api: StubApi? = null

    @After
    fun tearDown() {
        api?.stop()
        TraceLog.debugLogging = false
        TraceLog.redirect(null)
    }

    private fun stub(eventStatus: Int = 202, consentStatus: Int = 201, delayMillis: Long = 0): StubApi =
        StubApi(eventStatus, consentStatus, delayMillis).also { api = it }

    private fun transport(url: String, timeoutMillis: Int = 10_000) =
        Transport(apiKey = "trace_pk_test", apiUrl = url, timeoutMillis = timeoutMillis)

    private fun firstOpen() = Event(
        type = EventType.FIRST_OPEN,
        anonUserKey = key,
        consentStatus = ConsentState.GRANTED,
        appVersion = "1.4.2",
        installReferrer = referrer,
    )

    /**
     * The README's "What to declare to the stores" says what leaves the device, for the Play Data safety form. This
     * is that list, read off the wire with every field filled in. A field added to an event or to the consent call
     * fails here until the README says what it is and this list names it.
     */
    @Test
    fun `every field that leaves the device is one the store declarations name`() {
        val declared = setOf(
            // The install id, and what kind of client sent it.
            "anon_user_key", "source_type", "platform", "store",
            // The event: what happened, when, under which consent answer, on which version of the app, and for a
            // first open the Play Store install referrer.
            "event_type", "timestamp", "consent_status", "app_version", "install_referrer",
            // A conversion: the app's name for it, its value and its metadata. The SDK never fills in the two
            // conversion_ fields today; they are named so that starting to send them is still a change seen here.
            "event_name", "value", "metadata", "conversion_type_id", "conversion_value",
            // The consent call: the two answers.
            "consent_analytics", "consent_marketing",
        )
        val api = stub()
        val transport = transport(api.url)

        transport.send(
            firstOpen().copy(
                eventName = "purchase",
                value = 1.0,
                conversionTypeId = "ct",
                conversionValue = 1.0,
                metadata = mapOf("plan" to "plus"),
            ),
        )
        transport.sendConsent(key, analytics = true, marketing = false)

        assertEquals(2, api.requests.size)
        assertEquals(declared, api.requests.flatMap { it.json().keys().asSequence().toList() }.toSet())
    }

    @Test
    fun `the api key travels on the event and on the consent call`() {
        val api = stub()
        val transport = transport(api.url)

        assertTrue(transport.send(firstOpen()))
        assertTrue(transport.sendConsent(key, analytics = true, marketing = false))

        assertEquals(2, api.requests.size)
        api.requests.forEach { assertEquals("trace_pk_test", it.header("x-trace-api-key")) }
        api.requests.forEach { assertEquals("application/json", it.header("content-type")) }
        assertEquals(listOf("/v1/event", "/v1/consent"), api.requests.map { it.path })
    }

    @Test
    fun `the user agent is not empty and says which sdk it is`() {
        val api = stub()
        val transport = transport(api.url)

        transport.send(firstOpen())
        transport.sendConsent(key, analytics = true, marketing = true)

        // This test exists because the failure is invisible. The server's bot guard matches an empty user agent and
        // then ignores the event behind a 200, so a transport that sent none would look like it was working.
        api.requests.forEach {
            val userAgent = it.header("user-agent").orEmpty()
            assertTrue("an empty user agent is read as a bot and dropped behind a 200", userAgent.isNotBlank())
            assertTrue("unexpected user agent: $userAgent", userAgent.startsWith("TraceSdkAndroid/"))
            assertTrue("the user agent should carry the sdk version", BuildConfig.SDK_VERSION in userAgent)
            // The same guard matches these words anywhere in the string, so the Android release must not be one.
            listOf("bot", "crawler", "spider", "headless", "curl", "wget").forEach { pattern ->
                assertFalse("the user agent matches the bot guard on $pattern", pattern in userAgent.lowercase())
            }
        }
    }

    @Test
    fun `every event says it is an android app installed from the play store`() {
        val api = stub()
        val transport = transport(api.url)

        transport.send(firstOpen())
        transport.send(
            Event(type = EventType.PURCHASE, anonUserKey = key, consentStatus = ConsentState.GRANTED, value = 9.99),
        )

        api.requests.forEach {
            val body = it.json()
            assertEquals("app", body.getString("source_type"))
            assertEquals("android", body.getString("platform"))
            assertEquals("play", body.getString("store"))
        }
    }

    @Test
    fun `the install id is on every event and on the consent call`() {
        val api = stub()
        val transport = transport(api.url)

        transport.send(firstOpen())
        transport.send(Event(type = EventType.CUSTOM, anonUserKey = key, consentStatus = ConsentState.GRANTED))
        transport.sendConsent(key, analytics = true, marketing = false)

        // The consent call is the one people leave it off, and the result is not a missing field. On a consent
        // gated site the server buffers the first open and replays it after consent, taking the key from the
        // consent call, so a consent call without one makes the server mint a key and record it as the app's own
        // install id. That is a false provenance, not a gap.
        assertEquals(3, api.requests.size)
        api.requests.forEach { assertEquals(key, it.json().getString("anon_user_key")) }
    }

    @Test
    fun `a consent call sends both answers as booleans`() {
        val api = stub()

        transport(api.url).sendConsent(key, analytics = true, marketing = false)

        val body = api.requests.single().json()
        assertTrue(body.getBoolean("consent_analytics"))
        assertFalse(body.getBoolean("consent_marketing"))
    }

    /**
     * Decided 7 October 2026 (decision 3 of docs/plans/APP_MODELLED_INSTALLS.md in use-trace/trace): the share of
     * people who said yes is worked out per platform, so the consent call says which platform answered. A refusal
     * carries it too, because a no is half of that share.
     */
    @Test
    fun `a consent call says it is from an android app`() {
        val api = stub()

        transport(api.url).sendConsent(key, analytics = false, marketing = false)

        assertEquals("android", api.requests.single().json().getString("platform"))
    }

    @Test
    fun `a first open sends the referrer byte for byte`() {
        val api = stub()

        transport(api.url).send(firstOpen())

        // The server parses the referrer, in one place. Anything decoded, trimmed or re-encoded here would be
        // classified differently at the two ends.
        assertEquals(referrer, api.requests.single().json().getString("install_referrer"))
    }

    @Test
    fun `an awkward campaign name arrives as json the server can parse`() {
        val api = stub()
        val awkward = "say \"hi\" \\ then\nnew\tline \u0001bell 😀 日本語 café"

        transport(api.url).send(
            Event(
                type = EventType.CUSTOM,
                anonUserKey = key,
                consentStatus = ConsentState.GRANTED,
                eventName = awkward,
                metadata = mapOf("campaign" to awkward),
            ),
        )

        // Parsed at the receiving end, from the bytes that crossed the socket, so a body whose declared encoding
        // disagreed with its content would fail here even though the writer looked right.
        val body = api.requests.single().json()
        assertEquals(awkward, body.getString("event_name"))
        assertEquals(awkward, body.getJSONObject("metadata").getString("campaign"))
    }

    @Test
    fun `the statuses the trace api really answers with are successes`() {
        val api = stub(eventStatus = 202, consentStatus = 201)
        val transport = transport(api.url)

        // POST /v1/event answers 202 and POST /v1/consent answers 201. A transport that only accepted 200 would
        // report every successful send as a failure, and the consent gate would hold events that had arrived.
        assertTrue("202 Accepted is what the event route answers", transport.send(firstOpen()))
        assertTrue("201 Created is what the consent route answers", transport.sendConsent(key, true, false))
    }

    @Test
    fun `a plain 200 is a success too`() {
        val api = stub(eventStatus = 200)

        assertTrue(transport(api.url).send(firstOpen()))
    }

    @Test
    fun `a 400 is a failure and is not retried`() {
        val api = stub(eventStatus = 400)

        assertFalse(transport(api.url).send(firstOpen()))

        // A rejected payload is rejected again. Retrying it is noise that looks like a flaky network, and it is the
        // same refusal three times over on someone else's data allowance.
        assertEquals("a refused payload must be sent once and once only", 1, api.requests.size)
    }

    @Test
    fun `a 401 is not retried either`() {
        val api = stub(eventStatus = 401)

        assertFalse(transport(api.url).send(firstOpen()))

        assertEquals("a wrong api key will be wrong on the second attempt as well", 1, api.requests.size)
    }

    @Test
    fun `a 500 is retried, three attempts in total`() {
        val api = stub(eventStatus = 500)

        assertFalse(transport(api.url).send(firstOpen()))

        assertEquals("a server error is worth another try, but not forever", 3, api.requests.size)
    }

    @Test
    fun `a 500 that clears is a success without a duplicate`() {
        val api = stub(eventStatus = 500)
        api.statusAfterFirst = 202

        assertTrue(transport(api.url).send(firstOpen()))

        assertEquals(2, api.requests.size)
    }

    @Test
    fun `a refused connection returns false rather than throwing`() {
        // A port nothing is listening on. The SDK runs on the ingest path of somebody else's app, so send returns
        // an answer whatever the network did.
        val deadPort = ServerSocket(0).use { it.localPort }

        assertFalse(transport("http://127.0.0.1:$deadPort").send(firstOpen()))
    }

    @Test
    fun `a host that does not resolve returns false rather than throwing`() {
        assertFalse(transport("http://api.usetrace.invalid").send(firstOpen()))
    }

    @Test
    fun `a server that never answers times out and returns false`() {
        val api = stub(delayMillis = 1_000)

        assertFalse(transport(api.url, timeoutMillis = 150).send(firstOpen()))
    }

    @Test
    fun `a malformed api url returns false rather than throwing`() {
        // A typo in the customer's own configuration. It is not retried either: it will be malformed next time.
        assertFalse(transport("not a url at all").send(firstOpen()))
        assertFalse(transport("telnet://127.0.0.1:1").send(firstOpen()))
        assertFalse(transport("").sendConsent(key, true, false))
    }

    @Test
    fun `nothing the transport logs carries an identity`() {
        val lines = CopyOnWriteArrayList<String>()
        TraceLog.debugLogging = true
        TraceLog.redirect { lines.add(it) }

        // Every outcome, so every log line the transport can write is captured: accepted, refused, failed and
        // unreachable.
        val accepted = stub(eventStatus = 202)
        transport(accepted.url).send(firstOpen())
        transport(accepted.url).sendConsent(key, analytics = true, marketing = false)
        accepted.stop()
        val refused = stub(eventStatus = 400)
        transport(refused.url).send(firstOpen())
        refused.stop()
        val broken = stub(eventStatus = 500)
        transport(broken.url).send(firstOpen())
        val deadPort = ServerSocket(0).use { it.localPort }
        transport("http://127.0.0.1:$deadPort").send(firstOpen())

        assertTrue("the transport should say what happened, or this test proves nothing", lines.isNotEmpty())
        lines.forEach { line ->
            assertFalse("an install id reached the log: $line", key in line)
            assertFalse("a referrer reached the log: $line", "gclid" in line)
            assertFalse("a campaign value reached the log: $line", "google-play" in line)
            // The sink strips an identity, so a line that was redacted means something tried to log one. That is
            // the assertion which fails when a later change logs the key: it does not have to leak to be caught.
            assertFalse("a line had to be redacted, so something tried to log an identity: $line", TraceLog.REDACTED in line)
        }
    }
}
