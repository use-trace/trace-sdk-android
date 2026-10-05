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
import java.io.File

/**
 * The consent gate, against a fake [EventSender] that records the order of what it was asked to send.
 *
 * Order is what most of these tests are about. The consent call has to go before the held events, because on a
 * consent gated site the server buffers the first open and replays it once consent arrives, taking the anonymous
 * key from the consent call. An event that arrives first makes the server mint a key of its own and record it as
 * though it were the app's install id, which is a false provenance rather than a missing field, and nothing on the
 * client can see it happen. So these tests assert the sequence, not merely that both were sent.
 *
 * Robolectric, because the held queue is a real file in a real no-backup directory and the test that matters most
 * is the one that reads it back through a second gate.
 */
@RunWith(RobolectricTestRunner::class)
class ConsentGateTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val queueFile: File get() = File(context.noBackupFilesDir, "held_events")

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

        override fun send(event: Event): Boolean {
            calls.add("event ${event.type}${event.eventName?.let { " $it" } ?: ""}")
            events.add(event)
            return accepts
        }

        override fun sendConsent(key: String, analytics: Boolean, marketing: Boolean): Boolean {
            calls.add("consent analytics=$analytics marketing=$marketing")
            consentKeys.add(key)
            return accepts
        }
    }

    private fun gate(sender: EventSender) = ConsentGate(context, sender)

    private fun event(name: String, type: EventType = EventType.CUSTOM) = Event(
        type = type,
        anonUserKey = InstallId.get(context),
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
        assertFalse("an event sent straight away should not also be held", queueFile.exists())
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
        assertFalse("a dropped event should not be written to disk either", queueFile.exists())
    }

    @Test
    fun `the queue survives process death`() {
        val sender = RecordingSender()

        // The first lifetime: the app is opened, the first open is recorded, the banner is still on screen and the
        // process is killed. The gate object goes with it, so the second lifetime gets a new one.
        gate(RecordingSender()).record(event("the install", EventType.FIRST_OPEN))

        val next = gate(sender)
        assertEquals(ConsentState.UNKNOWN, next.state)
        next.setConsent(analytics = true, marketing = false)

        // The install is the one event that cannot be sent again, so losing it to a kill is losing the channel that
        // paid for it. Nothing in memory can prove this: it has to come off disk.
        assertEquals(listOf("the install"), sender.events.map { it.eventName })
        assertEquals(EventType.FIRST_OPEN, sender.events.single().type)
    }

    @Test
    fun `the queue is bounded and the oldest event goes first`() {
        val sender = RecordingSender()
        val gate = gate(sender)

        repeat(105) { gate.record(event("event $it")) }
        gate.setConsent(analytics = true, marketing = false)

        // A banner nobody ever answers must not grow a file without limit, and when something has to go it is the
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

    @Test
    fun `a flush leaves nothing on disk`() {
        val sender = RecordingSender()
        val gate = gate(sender)

        gate.record(event("first", EventType.FIRST_OPEN))
        assertTrue("a held event should be on disk before the flush", queueFile.isFile)
        gate.setConsent(analytics = true, marketing = false)

        assertFalse("a flushed queue left its events on disk, so a later launch would send them again", queueFile.exists())
    }

    @Test
    fun `a discard leaves nothing on disk`() {
        val gate = gate(RecordingSender())

        gate.record(event("first", EventType.FIRST_OPEN))
        assertTrue("a held event should be on disk before the denial", queueFile.isFile)
        gate.setConsent(analytics = false, marketing = false)

        // Emptying a list in memory is not a discard. A denial that leaves the events in a file has kept them, and
        // the next launch would read them back and send them.
        assertFalse("a denial left the events it discarded on disk", queueFile.exists())
    }

    @Test
    fun `the queue is held where android never backs it up`() {
        gate(RecordingSender()).record(event("first", EventType.FIRST_OPEN))

        // The same directory as the install id, for the same reason: the queue carries that id, so a queue restored
        // onto a later install would send events for a visitor who no longer exists. getNoBackupFilesDir is the one
        // place a host app's own backup rules cannot opt back in.
        assertTrue("nothing was written to the no-backup directory", queueFile.isFile)
        assertFalse("the queue is in files, which Auto Backup includes", File(context.filesDir, "held_events").exists())
    }
}
