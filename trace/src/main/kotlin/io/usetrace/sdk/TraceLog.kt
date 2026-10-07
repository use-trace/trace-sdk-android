package io.usetrace.sdk

import android.util.Log

/**
 * The SDK's own log, which a customer never sees unless they ask for it, and which cannot carry an identity.
 *
 * Internal on purpose: an SDK a customer embeds does not hand them a logging API, it keeps quiet and stays out of
 * the way. It is silent until `debugLogging` is set from `TraceConfig` at initialisation, because a library that
 * writes to logcat by default is a nuisance in somebody else's app.
 *
 * **A value shaped like an identity does not get out, whatever the call site wrote.** Every line passes through
 * [redact] before it reaches the sink, so the install id, a referrer, a hashed identifier and an email address are
 * replaced by [REDACTED] at the point of writing rather than at the point of calling. That is deliberate: "never
 * log the install id" is a rule a later change forgets, while a sink that strips one is a property of the code. The
 * test that catches the forgetting is the one asserting the SDK's own output contains no [REDACTED] at all: a
 * redaction in normal running means something tried, and the suite goes red.
 *
 * Redaction is a net and not a parser. It is the second line of defence; the first is that [Event] does not put the
 * key or the referrer in its own `toString`, so the easiest accident does not even reach here.
 */
internal object TraceLog {

    /** What replaces anything shaped like an identity. A line containing this is a bug, not an outcome. */
    internal const val REDACTED: String = "<redacted>"

    private const val TAG: String = "Trace"

    /**
     * Whether anything is written at all. Off unless the host app set `debugLogging` on its `TraceConfig`.
     * Volatile because it is set on the thread that initialises and read on the executor that sends.
     */
    @Volatile
    internal var debugLogging: Boolean = false

    @Volatile
    private var sink: ((String) -> Unit)? = null

    /**
     * Sends the log somewhere other than logcat, or back to logcat with null. The tests use it to capture output,
     * which is the only way an assertion about what the SDK logged can exist.
     */
    internal fun redirect(sink: ((String) -> Unit)?) {
        this.sink = sink
    }

    /**
     * Writes one line, if logging is on, with anything identity shaped removed first.
     *
     * It never throws: a log line is never worth an exception on the ingest path of someone else's app.
     */
    internal fun log(message: String) {
        if (!debugLogging) return
        write(message, Log.DEBUG)
    }

    /**
     * Writes one line whether or not logging was turned on, with anything identity shaped removed first.
     *
     * For the two mistakes a host app can make without having turned logging on: calling the api before
     * [Trace.initialise], where `debugLogging` has not arrived yet, and configuring an api url that is not the Trace
     * API, which [Transport] says once. Nothing in normal running uses this, so a quiet app stays quiet.
     *
     * It is redacted like any other line. Louder is not laxer.
     */
    internal fun warn(message: String) {
        write(message, Log.WARN)
    }

    private fun write(message: String, priority: Int) {
        val safe = runCatching { redact(message) }.getOrDefault(REDACTED)
        runCatching {
            val target = sink
            if (target != null) target(safe) else Log.println(priority, TAG, safe)
        }
    }

    // Four shapes, each one an identity Trace holds or refuses:
    //  - auk_... the anonymous key family, which is what an install id is
    //  - a long run of hex, which is a bare install id without its prefix, or a hashed identifier
    //  - a token carrying a tracking parameter, which is what a Play Store referrer is
    //  - a token shaped like an email address, which must never reach a log whatever line carries it
    // The SDK's own lines are an event type, an outcome, a status code and a count, so none of them matches.
    private val identityShaped: Regex = Regex(
        "auk_[A-Za-z0-9_-]+" +
            "|[0-9a-f]{32,}" +
            "|\\S*(?:utm_[a-z]+|gclid|gbraid|wbraid|fbclid|msclkid|ttclid|twclid|referrer)=\\S*" +
            "|\\S+@\\S+\\.\\S+",
        RegexOption.IGNORE_CASE,
    )

    private fun redact(message: String): String = identityShaped.replace(message, REDACTED)
}
