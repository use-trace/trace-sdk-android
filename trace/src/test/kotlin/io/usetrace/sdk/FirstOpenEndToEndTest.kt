package io.usetrace.sdk

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * One launch, end to end: the real [Trace] object, the real consent gate, the real install id and the real
 * transport, against an HTTP server on a loopback port. The only thing standing in for anything is the Play Store
 * referrer client, which cannot run in a unit test.
 *
 * Every other suite here proves one piece on its own with the next one faked out. This one proves they agree, and
 * the thing they have to agree about is **order**. The server treats a consent call as the moment it learns the
 * anonymous key: on a consent gated site it buffers an event that arrives without consent and replays it once
 * consent turns up, taking the key from the consent call. An event that overtakes that call makes the server mint
 * a key of its own and record it as though it were this app's install id, which is a false provenance rather than
 * a missing field, and nothing on this side of the socket can see it happen. So these tests assert the sequence
 * requests arrived in, not the set of requests that arrived.
 *
 * They read the bytes that crossed the socket, which is the only place the agreement is visible.
 */
@RunWith(RobolectricTestRunner::class)
class FirstOpenEndToEndTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val apiKey = "trace_pk_end_to_end"

    /** One real Play Store referrer, percent encoding and click id intact, compared byte for byte. */
    private val referrer =
        "utm_source=google-play&utm_medium=cpc&utm_campaign=spring%20sale%2B10%25&gclid=EAIaIQobChMI%2Fnot-real"

    private var api: StubApi? = null

    @Before
    fun setUp() {
        Trace.resetForTest()
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).versionName = "4.2.0"
    }

    @After
    fun tearDown() {
        Trace.resetForTest()
        api?.stop()
        TraceLog.debugLogging = false
        TraceLog.redirect(null)
    }

    /**
     * The sequence a real app follows on the launch after an install: start the SDK while the banner is still on
     * screen, record a conversion, then pass on what the person answered.
     *
     * Nothing is faked but the Play client. [Trace.initialise] is given no sender, so it builds its own [Transport]
     * and talks to the stub over a socket.
     */
    private fun launch(api: StubApi) {
        Trace.initialise(
            context,
            TraceConfig(apiKey = apiKey, apiUrl = api.url, debugLogging = true),
            sender = null,
        ) { _, onResult -> onResult(referrer) }
        Trace.awaitIdle()

        Trace.conversion("purchase", value = 30.0, currency = "GBP")
        Trace.awaitIdle()

        Trace.setConsent(analytics = true, marketing = false)
        Trace.awaitIdle()
    }

    @Test
    fun `a launch sends the consent call, then the first open, then the conversion, and nothing else`() {
        val api = StubApi().also { this.api = it }

        Trace.initialise(
            context,
            TraceConfig(apiKey = apiKey, apiUrl = api.url, debugLogging = true),
            sender = null,
        ) { _, onResult -> onResult(referrer) }
        Trace.awaitIdle()
        Trace.conversion("purchase", value = 30.0, currency = "GBP")
        Trace.awaitIdle()

        // Nothing at all while the banner is unanswered. Hold, then send: an event sent before the person answered
        // cannot be unsent, and the server deleting it afterwards is not the same as never having had it.
        assertEquals("nothing may be sent while consent is unknown", emptyList<String>(), api.requests.map { it.path })

        Trace.setConsent(analytics = true, marketing = false)
        Trace.awaitIdle()

        val installId = InstallId.peek(context)
        assertNotNull("the grant should have minted an install id", installId)

        // The sequence, not the set. The consent call first, because the server takes the key from it when it
        // replays what it buffered.
        assertEquals(listOf("/v1/consent", "/v1/event", "/v1/event"), api.requests.map { it.path })

        val consent = api.requests[0].json()
        assertEquals("the consent call carries the install id or the server mints one", installId, consent.getString("anon_user_key"))
        assertTrue(consent.getBoolean("consent_analytics"))

        val firstOpen = api.requests[1].json()
        assertEquals("FIRST_OPEN", firstOpen.getString("event_type"))
        assertEquals("app", firstOpen.getString("source_type"))
        assertEquals("android", firstOpen.getString("platform"))
        assertEquals("play", firstOpen.getString("store"))
        assertEquals("4.2.0", firstOpen.getString("app_version"))
        // Raw. The server parses a referrer in one place, so anything decoded or re-encoded here would be
        // classified differently at the two ends.
        assertEquals(referrer, firstOpen.getString("install_referrer"))
        assertEquals(installId, firstOpen.getString("anon_user_key"))

        val conversion = api.requests[2].json()
        assertEquals("PURCHASE", conversion.getString("event_type"))
        assertEquals("purchase", conversion.getString("event_name"))
        assertEquals(30.0, conversion.getDouble("value"), 0.0)
        assertEquals("GBP", conversion.getJSONObject("metadata").getString("currency"))
        assertEquals("one install, so one key on all three", installId, conversion.getString("anon_user_key"))

        api.requests.forEach {
            assertEquals(apiKey, it.header("x-trace-api-key"))
            // An empty user agent is read as a bot and the event is dropped behind a 200, so this is the one
            // header whose absence is invisible from the client side.
            assertTrue("an empty user agent is read as a bot", it.header("user-agent").orEmpty().isNotBlank())
        }

        assertEquals("a launch sends three requests and no fourth", 3, api.requests.size)
    }

    @Test
    fun `the flushed events say granted, not the unknown they were recorded under`() {
        val api = StubApi().also { this.api = it }

        launch(api)

        // Both events were built while the state was UNKNOWN and held in memory in that form. The server treats the
        // payload's consent_status as authoritative and quarantines UNKNOWN on a UK or EU site, so an event
        // flushed by a grant that still said UNKNOWN would be held back after the person had already agreed. From
        // the outside that is indistinguishable from never having sent it, which is why this is asserted on the
        // wire rather than trusted.
        // Picked by route, not by position: whether the consent call came first is the other test's assertion, and
        // this one should fail on the status and on nothing else.
        val flushed = api.requests.filter { it.path == "/v1/event" }.map { it.json() }
        assertEquals(listOf("FIRST_OPEN", "PURCHASE"), flushed.map { it.getString("event_type") })
        flushed.forEach {
            assertEquals(
                "a held event flushed as UNKNOWN is quarantined after the person agreed",
                "GRANTED",
                it.getString("consent_status"),
            )
        }
    }

    @Test
    fun `a whole launch over a real socket logs nothing that had to be redacted`() {
        val lines = mutableListOf<String>()
        TraceLog.redirect { lines.add(it) }
        val api = StubApi().also { this.api = it }

        launch(api)

        // The only path where the gate and the transport both run for real, so the only one where a line written by
        // one of them about a value handled by the other can slip through. The sink strips an identity, so a
        // redaction means something tried to log one: this fails without the value ever leaving the process.
        assertTrue("the launch should have said something", lines.isNotEmpty())
        lines.forEach {
            assertTrue("a line had to be redacted, so something tried to log an identity: $it", TraceLog.REDACTED !in it)
        }
    }
}
