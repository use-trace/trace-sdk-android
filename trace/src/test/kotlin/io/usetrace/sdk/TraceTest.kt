package io.usetrace.sdk

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * The public surface, which is the only file a customer reads and the only one they can misuse.
 *
 * Most of these tests are about what the SDK refuses to do. It must not crash its host, so every method called
 * before [Trace.initialise] does nothing and says so. It must not send an install twice, because the first open is
 * the one event that carries the channel and cannot be sent again. It must not send a referrer it was never given,
 * and it must not wait forever for one that never arrives. And it must not do any of its work on the thread it was
 * called from.
 *
 * The transport and the Play referrer client are both injected, so the suite needs no network and no Play Services.
 * Robolectric, because the send once flag and the held queue are real files in a real no-backup directory, and the
 * test that matters most is the one that reads the flag back in a second process.
 */
@RunWith(RobolectricTestRunner::class)
class TraceTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** One real referrer, with the percent encoding the Play Store leaves in it. Compared byte for byte. */
    private val raw = "utm_source=google-play&utm_medium=cpc&utm_campaign=spring%20sale%2B10%25"

    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        Trace.resetForTest()
        TraceLog.redirect { lines.add(it) }
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).versionName = "4.2.0"
    }

    @After
    fun tearDown() {
        Trace.resetForTest()
        TraceLog.debugLogging = false
        TraceLog.redirect(null)
    }

    /** What the SDK asked the network to do, in order, and on which thread it asked. */
    private class RecordingSender : EventSender {

        val calls = mutableListOf<String>()
        val events = mutableListOf<Event>()
        val consentKeys = mutableListOf<String>()
        val threads = mutableSetOf<String>()

        override fun send(event: Event): Boolean = synchronized(this) {
            calls.add("event ${event.type}${event.eventName?.let { " $it" } ?: ""}")
            events.add(event)
            threads.add(Thread.currentThread().name)
            true
        }

        override fun sendConsent(key: String, analytics: Boolean, marketing: Boolean): Boolean = synchronized(this) {
            calls.add("consent analytics=$analytics marketing=$marketing")
            consentKeys.add(key)
            threads.add(Thread.currentThread().name)
            true
        }
    }

    private fun config(debugLogging: Boolean = true) =
        TraceConfig(apiKey = "trc_test_key", apiUrl = "http://127.0.0.1:9", debugLogging = debugLogging)

    /**
     * Initialises with a fake transport and a fake Play client, then waits for the launch work to finish.
     *
     * [referrer] null with [answers] true is a device with no referrer to give, which is a direct install.
     * [answers] false is the client that never calls back at all, which is what the deadline exists for.
     */
    private fun initialise(
        sender: EventSender,
        referrer: String? = null,
        answers: Boolean = true,
        debugLogging: Boolean = true,
    ) {
        Trace.initialise(context, config(debugLogging), sender) { _, onResult ->
            if (answers) onResult(referrer)
        }
        Trace.awaitIdle()
    }

    @Test
    fun `the first open carries the referrer, the app version and the install id`() {
        val sender = RecordingSender()
        initialise(sender, referrer = raw)

        Trace.setConsent(analytics = true)
        Trace.awaitIdle()

        val firstOpen = sender.events.single()
        assertEquals(EventType.FIRST_OPEN, firstOpen.type)
        assertEquals("the referrer must arrive byte for byte, percent encoding intact", raw, firstOpen.installReferrer)
        assertEquals("4.2.0", firstOpen.appVersion)
        assertEquals(InstallId.peek(context), firstOpen.anonUserKey)
        assertEquals(listOf(InstallId.peek(context)), sender.consentKeys)
    }

    @Test
    fun `a play client that never answers still sends the install`() {
        val sender = RecordingSender()
        Trace.referrerDeadlineMillis = 50

        initialise(sender, answers = false)
        Trace.setConsent(analytics = true)
        Trace.awaitIdle()

        // The first open is sent on the referrer's answer, and a client that never calls back means there is no
        // answer to send it on. A missing campaign is a direct install; a missing install is a lost customer.
        val firstOpen = sender.events.single()
        assertEquals(EventType.FIRST_OPEN, firstOpen.type)
        assertNull("no referrer arrived, so none may be sent", firstOpen.installReferrer)
        assertTrue("the deadline should say it fired", lines.any { "did not answer" in it })
    }

    @Test
    fun `the referrer deadline is the one chosen, not whatever a test left behind`() {
        // Pinned so that changing the wait is a decision somebody makes on purpose. Two and a half seconds is long
        // enough for a cold bind to Play Services on a slow device and short enough to sit inside one launch.
        assertEquals(2_500L, Trace.REFERRER_DEADLINE_MILLIS)
        assertEquals(Trace.REFERRER_DEADLINE_MILLIS, Trace.referrerDeadlineMillis)
    }

    @Test
    fun `the first open is held, not sent, while consent is unknown`() {
        val sender = RecordingSender()

        initialise(sender, referrer = raw)

        assertEquals(emptyList<String>(), sender.calls)
        assertFalse("a held first open is held in memory, never on disk", File(context.noBackupFilesDir, "held_events").exists())
    }

    @Test
    fun `the first open is sent once, ever`() {
        val first = RecordingSender()
        initialise(first, referrer = raw)
        Trace.setConsent(analytics = true)
        Trace.awaitIdle()
        assertEquals(listOf(EventType.FIRST_OPEN), first.events.map { it.type })

        // The process dies. The install id and the send once flag are on disk; everything in memory is not.
        Trace.resetForTest()

        val second = RecordingSender()
        initialise(second, referrer = raw)
        Trace.setConsent(analytics = true)
        Trace.awaitIdle()

        // The install happened once, so it is reported once. A second one would count one customer twice and give
        // the channel credit it did not earn.
        assertEquals(emptyList<Event>(), second.events)
    }

    @Test
    fun `the send once flag is kept where android never backs it up`() {
        initialise(RecordingSender(), referrer = raw)
        Trace.setConsent(analytics = true)
        Trace.awaitIdle()

        // Beside the install id and the held queue, and for a sharper reason than either: a flag restored onto a
        // fresh install would suppress that install's first open, and the install is the one event that cannot be
        // sent again. A missing flag over counts; a restored one loses the customer.
        assertTrue(File(context.noBackupFilesDir, "first_open_sent").isFile)
        assertFalse("the flag is in files, which Auto Backup includes", File(context.filesDir, "first_open_sent").exists())
    }

    @Test
    fun `initialising twice does not send two installs`() {
        val sender = RecordingSender()
        initialise(sender, referrer = raw)
        initialise(sender, referrer = raw)

        Trace.setConsent(analytics = true)
        Trace.awaitIdle()

        assertEquals(listOf(EventType.FIRST_OPEN), sender.events.map { it.type })
        assertTrue("a second initialise should say it was ignored", lines.any { "already initialised" in it })
    }

    @Test
    fun `an api key that is blank initialises nothing and says so`() {
        Trace.initialise(context, TraceConfig(apiKey = "   "))
        Trace.awaitIdle()

        // Nothing can be sent without a key, and the server answers 401, which the SDK reports as a refusal rather
        // than as the configuration mistake it is. So it is refused here, where the cause is still visible.
        assertNull("no install id should be minted for a key that cannot send anything", InstallId.peek(context))
        assertNull(Trace.installId)
        assertTrue(lines.any { "api key" in it })
    }

    @Test
    fun `every public method before initialise does nothing rather than throwing`() {
        // No initialise at all. An SDK that throws here crashes an app on a code path the customer cannot see.
        Trace.setConsent(analytics = true)
        Trace.conversion("purchase", value = 10.0)

        assertNull(Trace.installId)
        assertNull("nothing may be persisted before initialise either", InstallId.peek(context))
        assertEquals(2, lines.count { "before Trace.initialise" in it })
    }

    @Test
    fun `a conversion before initialise is reported even with logging off`() {
        TraceLog.debugLogging = false

        Trace.conversion("signup")

        // debugLogging arrives with the config at initialisation, so a misuse that happens earlier cannot be told
        // to speak up. This one line ignores the flag, because otherwise the mistake looks like silence.
        assertEquals(1, lines.size)
        assertTrue("Trace.conversion" in lines.single())
    }

    @Test
    fun `a conversion carries its name, its value and its currency`() {
        val sender = RecordingSender()
        initialise(sender)
        Trace.setConsent(analytics = true)
        Trace.awaitIdle()
        sender.events.clear()

        Trace.conversion("subscription", value = 12.5, currency = "GBP", metadata = mapOf("plan" to "plus"))
        Trace.awaitIdle()

        val conversion = sender.events.single()
        assertEquals(EventType.CUSTOM, conversion.type)
        assertEquals("subscription", conversion.eventName)
        assertEquals(12.5, conversion.value!!, 0.0)
        // There is no currency field on the v1 ingest route, and the route strips what it does not know without
        // saying so, so a currency sent as a field of its own would vanish. It goes in metadata, where it is kept.
        assertEquals(mapOf("plan" to "plus", "currency" to "GBP"), conversion.metadata)
    }

    @Test
    fun `a conversion named purchase is sent as a purchase`() {
        val sender = RecordingSender()
        initialise(sender)
        Trace.setConsent(analytics = true)
        Trace.awaitIdle()

        Trace.conversion("Purchase", value = 30.0)
        Trace.conversion("newsletter")
        Trace.awaitIdle()

        // The server has a PURCHASE type of its own and reports revenue from it, so the one name every shop uses
        // maps onto it rather than landing in CUSTOM with the rest.
        assertEquals(listOf(EventType.PURCHASE, EventType.CUSTOM), sender.events.drop(1).map { it.type })
    }

    @Test
    fun `a conversion with no name does nothing`() {
        val sender = RecordingSender()
        initialise(sender)
        Trace.setConsent(analytics = true)
        Trace.awaitIdle()
        sender.events.clear()

        Trace.conversion("  ")
        Trace.awaitIdle()

        assertEquals(emptyList<Event>(), sender.events)
        assertTrue(lines.any { "needs a name" in it })
    }

    @Test
    fun `a metadata key the server would drop is reported here instead`() {
        val sender = RecordingSender()
        initialise(sender)
        Trace.setConsent(analytics = true)
        Trace.awaitIdle()

        Trace.conversion("signup", metadata = mapOf("order id" to "9"))
        Trace.awaitIdle()

        // The server keeps metadata keys matching [A-Za-z0-9_]{1,64} and drops the rest in silence, so the only
        // place a customer can learn that their key went missing is here.
        assertTrue(lines.any { "metadata" in it && "order id" in it })
    }

    @Test
    fun `installId is null until consent is granted and the id afterwards`() {
        assertNull("a privacy screen must be able to say there is no id yet, truthfully", Trace.installId)

        val sender = RecordingSender()
        initialise(sender, referrer = raw)
        assertNull("no id exists before the person has agreed", Trace.installId)

        Trace.setConsent(analytics = true)
        Trace.awaitIdle()

        assertNotNull(Trace.installId)
        assertTrue(Trace.installId!!.startsWith("auk_app_"))
        assertEquals(InstallId.peek(context), Trace.installId)
    }

    @Test
    fun `nothing is sent on the thread the api was called from`() {
        val sender = RecordingSender()
        val caller = Thread.currentThread().name

        initialise(sender)
        Trace.setConsent(analytics = true)
        Trace.conversion("purchase", value = 1.0)
        Trace.awaitIdle()

        // An SDK that sends on the caller's thread puts a ten second socket timeout on whatever called it, which
        // on Android is an application not responding dialogue the customer gets blamed for.
        assertFalse("work ran on the calling thread", sender.threads.contains(caller))
        assertEquals(setOf("trace-sdk"), sender.threads)
    }

    @Test
    fun `a whole launch logs nothing that had to be redacted`() {
        val sender = RecordingSender()
        initialise(sender, referrer = raw)
        Trace.setConsent(analytics = true)
        Trace.conversion("purchase", value = 30.0, currency = "GBP")
        Trace.awaitIdle()

        assertTrue("the launch should have said something", lines.isNotEmpty())
        lines.forEach { line ->
            // The log redacts at the sink, so a redaction means a line tried to carry an install id or a referrer.
            // This path handles both, which makes it the easiest place to leak one.
            assertFalse("a line had to be redacted, so something tried to log an identity: $line", TraceLog.REDACTED in line)
        }
    }

    @Test
    fun `a refusal discards the install rather than sending it`() {
        val sender = RecordingSender()
        initialise(sender, referrer = raw)

        Trace.setConsent(analytics = false)
        Trace.awaitIdle()

        // No consent call either: there is no install id to withdraw under, and minting one to report a refusal
        // would create the identifier the person has just declined.
        assertEquals(emptyList<String>(), sender.calls)
    }

    // Decided 6 October 2026, before the first release: nothing is stored before consent. The first open waits in
    // memory only; the id and the install are written the moment the person accepts, and a refusal never writes an
    // identifier. These are that decision as tests, over the real directory the SDK writes to.

    /** Every file the SDK has written. Its only directory is the no-backup one. */
    private fun written(): List<String> = context.noBackupFilesDir.listFiles().orEmpty().map { it.name }.sorted()

    @Test
    fun `a fresh install that never answers writes no file at all`() {
        val sender = RecordingSender()

        initialise(sender, referrer = raw)
        Trace.conversion("purchase", value = 30.0)
        Trace.awaitIdle()

        assertEquals("nothing may be written to the device before the person has answered", emptyList<String>(), written())
        assertEquals(emptyList<String>(), sender.calls)
        assertNull(Trace.installId)
    }

    @Test
    fun `a refusal writes no identifier and sends nothing`() {
        val sender = RecordingSender()

        initialise(sender, referrer = raw)
        Trace.conversion("purchase", value = 30.0)
        Trace.setConsent(analytics = false, marketing = true)
        Trace.awaitIdle()

        assertEquals("a refusal may write nothing at all", emptyList<String>(), written())
        assertEquals(emptyList<String>(), sender.calls)
        assertNull(Trace.installId)
    }

    @Test
    fun `acceptance writes the id, sends exactly one first open with it, then the held events in order`() {
        val sender = RecordingSender()

        initialise(sender, referrer = raw)
        Trace.conversion("signup")
        Trace.conversion("purchase", value = 30.0)
        Trace.setConsent(analytics = true)
        Trace.awaitIdle()

        val id = InstallId.peek(context)
        assertNotNull("the grant should have written the install id", id)
        assertEquals(listOf("first_open_sent", "install_id"), written())
        assertEquals(
            listOf(
                "consent analytics=true marketing=false",
                "event FIRST_OPEN",
                "event CUSTOM signup",
                "event PURCHASE purchase",
            ),
            sender.calls,
        )
        assertEquals(listOf(id), sender.consentKeys)
        assertEquals("every event carries the id the grant wrote", listOf(id, id, id), sender.events.map { it.anonUserKey })
        assertEquals(raw, sender.events.first().installReferrer)
    }

    @Test
    fun `a conversion held before acceptance is sent after it`() {
        val sender = RecordingSender()
        initialise(sender, referrer = raw)

        Trace.conversion("purchase", value = 30.0, currency = "GBP")
        Trace.awaitIdle()
        assertEquals(emptyList<String>(), sender.calls)

        Trace.setConsent(analytics = true)
        Trace.awaitIdle()

        val purchase = sender.events.single { it.type == EventType.PURCHASE }
        assertEquals("event PURCHASE purchase", sender.calls.last())
        assertEquals(ConsentState.GRANTED, purchase.consentStatus)
        assertEquals(InstallId.peek(context), purchase.anonUserKey)
        assertEquals(30.0, purchase.value!!, 0.0)
    }

    @Test
    fun `a restart before any answer is a first open again`() {
        initialise(RecordingSender(), referrer = raw)
        Trace.conversion("signup")
        Trace.awaitIdle()

        // The process is killed with the banner still on screen. Nothing was written, so the next launch knows
        // nothing of this one: the held conversion is lost, which is the accepted cost, and the install is new.
        Trace.resetForTest()
        assertEquals(emptyList<String>(), written())

        val sender = RecordingSender()
        initialise(sender, referrer = raw)
        Trace.setConsent(analytics = true)
        Trace.awaitIdle()

        assertEquals(listOf("consent analytics=true marketing=false", "event FIRST_OPEN"), sender.calls)
        assertEquals(InstallId.peek(context), sender.events.single().anonUserKey)
        assertEquals(raw, sender.events.single().installReferrer)
    }
}
