package io.usetrace.sdk

import android.content.Context
import java.io.File

/**
 * Holds events until the host app says what the person answered, then sends them or throws them away.
 *
 * The rule it exists for is hold, then send. Not send, then apologise: an event sent before the banner was answered
 * cannot be unsent, and the server deleting it later is not the same thing as never having had it.
 *
 * **Nothing is written to the device before consent.** Decided 6 October 2026, before the first release. The held
 * events live in memory only, the install id is minted and written by the grant, and the first open flag is written
 * when the first open is sent. A refusal writes nothing. The cost is accepted: an app killed with its banner still on
 * screen loses what was held, and its next launch, finding no first open flag, records a first open again.
 *
 * **The consent call goes before the held events, always.** On a consent gated site the server buffers an event it
 * receives without consent and replays it once consent arrives, taking the anonymous key from the consent call. An
 * event that overtakes the consent call makes the server mint a key of its own and record it as though it were this
 * app's install id, which is a false provenance, not a missing field, and nothing on this side can see it happen.
 *
 * Events are recorded with no key, because before a grant there is none, and the gate stamps the install id on each
 * one as it sends it.
 *
 * `analytics` is the answer that gates events. `marketing` is passed on to the server for the record and never
 * decides whether an event is sent, so marketing alone discards the queue like any other refusal and does not even
 * mint an install id.
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

    // The application context keeps the files off whatever short lived context was handed in, and may be null when
    // the SDK is reached from Application.attachBaseContext, so fall back rather than crash the host app.
    private val appContext: Context = context.applicationContext ?: context

    /** What is held while the state is `UNKNOWN`, oldest first. In memory only, never on disk. */
    private val held = ArrayList<Event>()

    /** Whether this process has reported a refusal from an install with no id. In memory only, never on disk. */
    private var refusalReported = false

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
            ConsentState.GRANTED -> send(event, InstallId.get(appContext))
            // Nothing is kept for a later change of mind. A person who refuses and then agrees is tracked from the
            // moment they agreed, which is the whole of what consent means.
            ConsentState.DENIED -> TraceLog.log("${event.type} dropped, consent was refused")
        }
    }

    /**
     * Records the answer to the banner, tells the server, and then flushes or discards everything held.
     *
     * A grant mints and writes the install id, if there is none yet, sends the consent call, then the held events
     * oldest first. The held events are stamped `GRANTED` as they go: the server treats the payload's
     * `consent_status` as authoritative, so an event flushed by a grant that still said `UNKNOWN` would be
     * quarantined after the person had already agreed, which from the outside looks exactly like never sending it.
     *
     * A refusal still sends the consent call, because that is what withdraws an earlier grant and purges what it
     * allowed, but only when this install already has an id. Minting one in order to report a refusal would create
     * the identifier the person has just declined.
     *
     * Nothing is held by the time this returns, flushed or discarded. A send the server did not take is not retried
     * here: the transport has already tried three times.
     */
    internal fun setConsent(analytics: Boolean, marketing: Boolean): Unit = synchronized(this) {
        state = if (analytics) ConsentState.GRANTED else ConsentState.DENIED

        // The install's first answer is the grant that mints its id. Read before get can mint it.
        val minting = analytics && InstallId.peek(appContext) == null
        // get on a grant, peek on a refusal: a refusal reports an identity that exists and never creates one.
        val key = if (analytics) InstallId.get(appContext) else InstallId.peek(appContext)
        if (key != null) {
            sender.sendConsent(key, analytics, marketing, firstAnswer = analytics)
        } else if (true) {
            // Counted, never identified: no key, and nothing written to remember it, because nothing may be written
            // before a grant. So once a process, in the install's first day, when the banner is answered.
            // ponytail: a refuser whose process restarts in the first day is counted again, and one who first answers
            // after it is not counted. Exact needs one empty file written on a refusal, which the rules forbid today.
            TraceLog.log("consent refused before this install had an identity, reporting the answer with no identifier")
            refusalReported = sender.sendFirstRefusal(marketing)
        } else {
            TraceLog.log("consent refused before this install had an identity, so there is nothing to withdraw")
        }

        if (key != null && analytics) {
            TraceLog.log("consent granted, sending ${held.size} held event(s)")
            held.forEach { send(it, key) }
        } else {
            TraceLog.log("consent refused, discarding ${held.size} held event(s)")
        }
        held.clear()
    }

    // The Play Store's own record of when the app was first installed, read, never written. Unknown counts as old.
    private fun installedWithinADay(): Boolean = runCatching {
        val installed = appContext.packageManager.getPackageInfo(appContext.packageName, 0).firstInstallTime
        installed > 0 && System.currentTimeMillis() - installed in 0L until DAY_MILLIS
    }.getOrDefault(false)

    // Stamped with the key and GRANTED here, because neither was known when the event was recorded.
    private fun send(event: Event, key: String) {
        sender.send(event.copy(anonUserKey = key, consentStatus = ConsentState.GRANTED))
        if (event.type == EventType.FIRST_OPEN) recordFirstOpenSent(appContext)
    }

    private fun hold(event: Event) {
        held.add(event)
        if (held.size > MAX_HELD) {
            val dropped = held.size - MAX_HELD
            repeat(dropped) { held.removeAt(0) }
            // A banner nobody ever answers must not grow the queue without limit, and when something has to go it is
            // the oldest: the newest conversions are the ones still worth sending.
            TraceLog.log("the held queue is full at $MAX_HELD, dropped the $dropped oldest held event(s)")
        }
        TraceLog.log("${event.type} held in memory until the consent state is known, ${held.size} now held")
    }

    internal companion object {

        private const val FIRST_OPEN_FLAG: String = "first_open_sent"

        /** The most events held at once. Past this the oldest goes, so an unanswered banner cannot fill memory. */
        private const val MAX_HELD: Int = 100

        private const val DAY_MILLIS: Long = 24 * 60 * 60 * 1000L

        /**
         * The file that says the install was reported, in the directory Android never backs up: a flag restored onto
         * a fresh install would suppress that install's first open, which cannot be sent again.
         */
        internal fun firstOpenFlag(context: Context): File = File(context.noBackupFilesDir, FIRST_OPEN_FLAG)

        // Written after the first open has gone to the transport, not before: a flag written first would suppress an
        // install that was never sent. It is written whether or not the server took it, because there is no retry
        // across launches (see the class comment on Trace).
        private fun recordFirstOpenSent(context: Context) {
            val kept = runCatching { firstOpenFlag(context).writeText(Event.nowIso8601()) }.isSuccess
            if (!kept) TraceLog.log("could not record that the install was reported, so a later launch may report it again")
        }
    }
}
