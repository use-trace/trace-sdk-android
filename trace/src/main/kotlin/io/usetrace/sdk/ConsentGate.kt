package io.usetrace.sdk

import android.content.Context
import java.io.File

/**
 * Holds events until the host app says what the person answered, then sends them or throws them away.
 *
 * The rule it exists for is hold, then send. Not send, then apologise: an event sent before the banner was answered
 * cannot be unsent, and the server deleting it later is not the same thing as never having had it.
 *
 * **The consent call goes before the held events, always.** On a consent gated site the server buffers an event it
 * receives without consent and replays it once consent arrives, taking the anonymous key from the consent call. An
 * event that overtakes the consent call makes the server mint a key of its own and record it as though it were this
 * app's install id, which is a false provenance, not a missing field, and nothing on this side can see it happen.
 *
 * `analytics` is the answer that gates events. `marketing` is passed on to the server for the record and never
 * decides whether an event is sent, so marketing alone discards the queue like any other refusal and does not even
 * mint an install id.
 *
 * **The queue is on disk, not in memory.** An app killed while its banner is still on screen would otherwise lose
 * its first open, and the first open is the one event that cannot be sent again: it carries the install referrer,
 * and the referrer is the channel that paid for the install. The file is in `Context.getNoBackupFilesDir()`, the
 * same directory as the install id and for the same reason: it carries that id, so a queue restored onto a later
 * install would send events for a visitor who no longer exists, and no host app's backup rules can reach in there.
 *
 * It does not persist the consent state. A new process starts at `UNKNOWN` and holds, until the host app tells it
 * what the person answered, which an app has to do on every launch anyway because the answer is the app's to keep.
 *
 * Like the transport it is synchronous and it never throws: every call belongs on the SDK's background executor,
 * which the public API owns.
 */
internal class ConsentGate(
    context: Context,
    private val sender: EventSender,
) {

    // The application context keeps the file off whatever short lived context was handed in, and may be null when
    // the SDK is reached from Application.attachBaseContext, so fall back rather than crash the host app.
    private val appContext: Context = context.applicationContext ?: context

    private val queueFile: File = File(appContext.noBackupFilesDir, FILE_NAME)

    /**
     * What the host app has said so far. `UNKNOWN` until [setConsent] is called, and events recorded in that state
     * are held rather than sent.
     *
     * Volatile because the public API may read it from any thread while the executor is writing it.
     */
    @Volatile
    internal var state: ConsentState = ConsentState.UNKNOWN
        private set

    /**
     * Takes one event: sends it, holds it, or drops it, according to [state].
     *
     * It never reports which of the three happened. A caller that could tell would be tempted to do something about
     * it, and there is nothing to do: an event held is not an event lost, and an event dropped was refused.
     */
    internal fun record(event: Event): Unit = synchronized(this) {
        when (state) {
            ConsentState.UNKNOWN -> hold(event)
            ConsentState.GRANTED -> sender.send(event.copy(consentStatus = ConsentState.GRANTED))
            // Nothing is kept for a later change of mind. A person who refuses and then agrees is tracked from the
            // moment they agreed, which is the whole of what consent means.
            ConsentState.DENIED -> TraceLog.log("${event.type} dropped, consent was refused")
        }
    }

    /**
     * Records the answer to the banner, tells the server, and then flushes or discards everything held.
     *
     * The order is the consent call, then the held events oldest first, because the server takes the key for a
     * replayed event from the consent call. The held events are stamped `GRANTED` as they go: the server treats the
     * payload's `consent_status` as authoritative, so an event flushed by a grant that still said `UNKNOWN` would be
     * quarantined after the person had already agreed, which from the outside looks exactly like never sending it.
     *
     * A refusal still sends the consent call, because that is what withdraws an earlier grant and purges what it
     * allowed, but only when this install already has an id. Minting one in order to report a refusal would create
     * the identifier the person has just declined.
     *
     * The queue is gone from disk by the time this returns, flushed or discarded. A send the server did not take is
     * not retried here: the transport has already tried three times, and a file that outlives the answer is a file
     * some later launch sends again.
     */
    internal fun setConsent(analytics: Boolean, marketing: Boolean): Unit = synchronized(this) {
        state = if (analytics) ConsentState.GRANTED else ConsentState.DENIED
        val held = readHeld()

        // get on a grant, peek on a refusal: a refusal reports an identity that exists and never creates one.
        val key = if (analytics) InstallId.get(appContext) else InstallId.peek(appContext)
        if (key == null) {
            TraceLog.log("consent refused before this install had an identity, so there is nothing to withdraw")
        } else {
            sender.sendConsent(key, analytics, marketing)
        }

        if (analytics) {
            TraceLog.log("consent granted, sending ${held.size} held event(s)")
            held.forEach { sender.send(it.copy(consentStatus = ConsentState.GRANTED)) }
        } else {
            TraceLog.log("consent refused, discarding ${held.size} held event(s)")
        }
        clearHeld()
    }

    private fun hold(event: Event) {
        val held = readHeld().toMutableList()
        held.add(event)
        if (held.size > MAX_HELD) {
            val dropped = held.size - MAX_HELD
            repeat(dropped) { held.removeAt(0) }
            // A banner nobody ever answers must not grow a file without limit, and when something has to go it is
            // the oldest: the newest conversions are the ones still worth sending.
            TraceLog.log("the held queue is full at $MAX_HELD, dropped the $dropped oldest held event(s)")
        }
        write(held)
        TraceLog.log("${event.type} held until the consent state is known, ${held.size} now held")
    }

    // One event per line, in the body it would have been sent as. The whole file is rewritten rather than appended
    // to: it is a hundred lines at most, and a rewrite is the same code as a drop. A write cut short by a process
    // dying leaves a broken last line, which the reader drops, so the events before it still survive.
    private fun write(held: List<Event>) {
        val failure = runCatching { queueFile.writeText(held.joinToString(separator = "\n") { it.toJson() }) }
        if (failure.isFailure) TraceLog.log("could not write the held queue, ${held.size} event(s) may be lost")
    }

    private fun readHeld(): List<Event> =
        runCatching { queueFile.readLines() }.getOrDefault(emptyList())
            .mapNotNull { line -> if (line.isBlank()) null else Event.fromJson(line) }

    // Gone, not emptied. A denial that leaves the events in a file has not discarded them, and a flush that leaves
    // them there sends them again on the next launch.
    private fun clearHeld() {
        val gone = runCatching { !queueFile.exists() || queueFile.delete() }.getOrDefault(false)
        if (!gone) {
            TraceLog.log("could not delete the held queue, emptying it instead")
            runCatching { queueFile.writeText("") }
        }
    }

    private companion object {

        private const val FILE_NAME: String = "held_events"

        /** The most events held at once. Past this the oldest goes, so an unanswered banner cannot fill a disk. */
        private const val MAX_HELD: Int = 100
    }
}
