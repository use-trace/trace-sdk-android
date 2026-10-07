package io.usetrace.sdk

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The consent gate, against a fake [EventSender] that records the order of what it was asked to send.
 *
 * Order is what most of these tests are about. The consent call has to go before the held events, because on a
 * consent gated site the server buffers the first open and replays it once consent arrives, taking the anonymous
 * key from the consent call. An event that arrives first makes the server mint a key of its own and record it as
 * though it were the app's install id, which is a false provenance rather than a missing field, and nothing on the
 * client can see it happen. So these tests assert the sequence, not merely that both were sent.
 *
 * Robolectric, because the install id is a real file in a real no-backup directory, and what these tests also prove
 * is that nothing else is: the held queue lives in memory only.
 */
@RunWith(RobolectricTestRunner::class)
class ConsentGateTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** Every file in the SDK's only directory. */
    private fun written(): List<String> = context.noBackupFilesDir.listFiles().orEmpty().map { it.name }.sorted()

    @After
    fun tearDown() {
        TraceLog.debugLogging = false
        TraceLog.redirect(null)
    }

    /**
     * What the gate sent, in order, and nothing about how. Each call is recorded as a short word rather than an
     * object, so an assertion can be a list and read like the sequence it is checking.
     */
    private class RecordingSender : EventSender {

        val calls = mutableListOf<String>()
        val events = mutableListOf<Event>()
        val consentKeys = mutableListOf<String>()
        var accepts: Boolean = true

        override fun send(event: Event): Delivery {
            calls.add("event ${event.type}${event.eventName?.let { " $it" } ?: ""}")
            events.add(event)
            return if (accepts) Delivery.DELIVERED else Delivery.FAILED
        }

        override fun sendConsent(key: String, analytics: Boolean, marketing: Boolean): Boolean {
            calls.add("consent analytics=$analytics marketing=$marketing")
            consentKeys.add(key)
            return accepts
        }
    }

    private fun gate(sender: EventSender) = ConsentGate(context, sender)

    // Recorded with no key, as the SDK records it: the install id does not exist before consent is granted, and the
    // gate fills it in when the event is sent.
    private fun event(name: String, type: EventType = EventType.CUSTOM) = Event(
        type = type,
        anonUserKey = "",
        consentStatus = ConsentState.UNKNOWN,
        eventName = name,
    )

    @Test
    fun `an event recorded before the banner is answered is not sent`() {
        val sender = RecordingSender()
        val gate = gate(sender)

        gate.record(event("one"))

        assertEquals(ConsentState.UNKNOWN, gate.state)
        assertEquals(emptyList<String>(), sender.calls)
    }

    @Test
    fun `granting consent sends the held events, oldest first`() {
        val sender = RecordingSender()
        val gate = gate(sender)

        gate.record(event("first", EventType.FIRST_OPEN))
        gate.record(event("second"))
        gate.record(event("third"))
        gate.setConsent(analytics = true, marketing = false)

        assertEquals(ConsentState.GRANTED, gate.state)
        assertEquals(listOf("first", "second", "third"), sender.events.map { it.eventName })
    }

    @Test
    fun `granting consent sends the consent call before the held events`() {
        val sender = RecordingSender()
        val gate = gate(sender)

        gate.record(event("first", EventType.FIRST_OPEN))
        gate.record(event("second"))
        gate.setConsent(analytics = true, marketing = true)

        // Not "both were sent": the order is the whole point. The server takes the key for the replayed first open
        // from the consent call, so an event that overtakes it is recorded under a key the server invented.
        assertEquals(
            listOf("consent analytics=true marketing=true", "event FIRST_OPEN first", "event CUSTOM second"),
            sender.calls,
        )
    }

    @Test
    fun `the consent call carries the install id`() {
        val sender = RecordingSender()
        val gate = gate(sender)
        val id = InstallId.get(context)

        gate.setConsent(analytics = true, marketing = false)

        assertEquals(listOf(id), sender.consentKeys)
    }

    @Test
    fun `a held event is sent as granted, not as the unknown it was recorded under`() {
        val sender = RecordingSender()
        val gate = gate(sender)

        gate.record(event("first", EventType.FIRST_OPEN))
        gate.setConsent(analytics = true, marketing = false)

        // The server treats the payload's consent_status as authoritative, and a UK or EU site quarantines an
        // UNKNOWN one. An event flushed by a grant that still said UNKNOWN would be held back by the server after
        // the person had already agreed, which looks exactly like the SDK never sending it.
        assertEquals(listOf(ConsentState.GRANTED), sender.events.map { it.consentStatus })
    }

    @Test
    fun `denying consent discards the held events and sends none of them`() {
        val sender = RecordingSender()
        val gate = gate(sender)
        InstallId.get(context) // an earlier launch's grant, so there is a grant to withdraw

        gate.record(event("first", EventType.FIRST_OPEN))
        gate.record(event("second"))
        gate.setConsent(analytics = false, marketing = false)

        assertEquals(ConsentState.DENIED, gate.state)
        // The denial itself still goes, carrying the key, because that is what withdraws an earlier grant and
        // purges what it allowed. No held event goes with it.
        assertEquals(listOf("consent analytics=false marketing=false"), sender.calls)
        assertEquals(emptyList<Event>(), sender.events)
    }

    @Test
    fun `a denial before there is any identity mints none to announce itself`() {
        val sender = RecordingSender()
        val gate = gate(sender)

        gate.setConsent(analytics = false, marketing = false)

        // Nothing has been recorded, so there is no install id and nothing the server could withdraw. Minting one
        // in order to report a refusal would create the identifier the person has just declined.
        assertEquals(emptyList<String>(), sender.calls)
        assertEquals(null, InstallId.peek(context))
    }

    @Test
    fun `an event recorded after a grant is sent straight away`() {
        val sender = RecordingSender()
        val gate = gate(sender)

        gate.setConsent(analytics = true, marketing = false)
        gate.record(event("afterwards"))

        assertEquals(
            listOf("consent analytics=true marketing=false", "event CUSTOM afterwards"),
            sender.calls,
        )
        assertEquals(listOf(InstallId.peek(context)), sender.events.map { it.anonUserKey })
    }

    @Test
    fun `an event recorded after a denial is dropped and nothing is sent`() {
        val sender = RecordingSender()
        val gate = gate(sender)
        InstallId.get(context)

        gate.setConsent(analytics = false, marketing = false)
        sender.calls.clear()
        gate.record(event("afterwards"))

        assertEquals(emptyList<String>(), sender.calls)
        assertEquals("a dropped event should not be written to disk either", listOf("install_id"), written())
    }

    @Test
    fun `the queue is in memory only, so it does not survive process death`() {
        val sender = RecordingSender()

        // The first lifetime: the app is opened, the first open is recorded, the banner is still on screen and the
        // process is killed. The gate object goes with it, so the second lifetime gets a new one.
        gate(RecordingSender()).record(event("the install", EventType.FIRST_OPEN))
        assertEquals("nothing may be written before consent", emptyList<String>(), written())

        val next = gate(sender)
        next.setConsent(analytics = true, marketing = false)

        // Decided 6 October 2026: nothing is stored before consent, and losing what was held to a kill is the cost.
        // The next launch has written no first open flag, so it records its own first open.
        assertEquals(emptyList<Event>(), sender.events)
    }

    @Test
    fun `held events are sent under the install id the grant wrote`() {
        val sender = RecordingSender()
        val gate = gate(sender)

        gate.record(event("first", EventType.FIRST_OPEN))
        gate.record(event("second"))
        assertEquals(emptyList<String>(), written())
        gate.setConsent(analytics = true, marketing = false)

        val id = InstallId.peek(context)
        assertEquals(listOf(id, id), sender.events.map { it.anonUserKey })
        assertEquals(listOf(id), sender.consentKeys)
        assertEquals(listOf("first_open_sent", "install_id"), written())
    }

    @Test
    fun `the queue is bounded and the oldest event goes first`() {
        val sender = RecordingSender()
        val gate = gate(sender)

        repeat(105) { gate.record(event("event $it")) }
        gate.setConsent(analytics = true, marketing = false)

        // A banner nobody ever answers must not grow the queue without limit, and when something has to go it is the
        // oldest: the newest conversions are the ones still worth sending.
        assertEquals(100, sender.events.size)
        assertEquals("event 5", sender.events.first().eventName)
        assertEquals("event 104", sender.events.last().eventName)
    }

    @Test
    fun `a dropped event is logged, and the line carries no identity`() {
        val lines = mutableListOf<String>()
        TraceLog.debugLogging = true
        TraceLog.redirect { lines.add(it) }
        val gate = gate(RecordingSender())

        repeat(101) { gate.record(event("event $it")) }

        assertTrue("dropping an event should say so", lines.any { "dropped" in it })
        lines.forEach { line ->
            // The log redacts at the sink, so a redaction here means a line tried to carry an identity. The queue
            // is full of install ids and referrers, which makes this the easiest place in the SDK to leak one.
            assertFalse("a line had to be redacted, so something tried to log an identity: $line", TraceLog.REDACTED in line)
        }
    }
}
