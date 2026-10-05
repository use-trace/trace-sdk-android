package io.usetrace.sdk

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The three event types this SDK sends. The server knows more; the SDK's boundary is these. */
internal enum class EventType {
    /** The install. Sent once, ever, and the only app event that can carry a channel. */
    FIRST_OPEN,

    /** A purchase conversion. */
    PURCHASE,

    /** Any other conversion the host app names. */
    CUSTOM,
}

/**
 * What the host app has told the SDK about consent, and therefore whether an event may be sent at all.
 *
 * `UNKNOWN` is not a third opinion, it is "nobody has answered the banner yet", and an event recorded in that state
 * is held rather than sent. The names are the wire values the server's `consent_status` accepts.
 */
internal enum class ConsentState {
    UNKNOWN,
    GRANTED,
    DENIED,
}

/**
 * One event, in the shape `POST /v1/event` takes.
 *
 * It is the whole payload and nothing else: no screen, no session, no device. `source_type`, `platform` and `store`
 * are not fields here because they are not variables: this SDK is an Android app on the Play Store and says so on
 * every event. The server infers an app from a `FIRST_OPEN` as a backstop, and this does not rely on the backstop.
 *
 * The timestamp is taken when the event is constructed, not when it is sent, because an event held behind a consent
 * banner may be sent long after it happened and the journey's order is the point.
 *
 * **Its `toString` says the type and the time and nothing else.** A data class would hand the install id and the
 * referrer to anything that interpolated an event into a string, which is how an identity reaches a log line or a
 * crash report in somebody else's app. [TraceLog] would strip it; this stops the accident one step earlier.
 */
internal data class Event(
    val type: EventType,
    /** The install id. Required on every event: the server refuses an install without one, deliberately. */
    val anonUserKey: String,
    val consentStatus: ConsentState,
    val timestamp: String = nowIso8601(),
    /** The host app's `versionName`, or null when the SDK could not read it. */
    val appVersion: String? = null,
    /** The raw Play Store referrer, first open only, unparsed. */
    val installReferrer: String? = null,
    val eventName: String? = null,
    val value: Double? = null,
    val conversionTypeId: String? = null,
    val conversionValue: Double? = null,
    val metadata: Map<String, String> = emptyMap(),
) {

    /** The request body for `POST /v1/event`. Fields with nothing in them are left out rather than sent as null. */
    internal fun toJson(): String = Json.obj(
        "event_type" to type.name,
        "source_type" to SOURCE_TYPE,
        "platform" to PLATFORM,
        "store" to STORE,
        "anon_user_key" to anonUserKey,
        "consent_status" to consentStatus.name,
        "timestamp" to timestamp,
        "app_version" to appVersion,
        "install_referrer" to installReferrer,
        "event_name" to eventName,
        "value" to value,
        "conversion_type_id" to conversionTypeId,
        "conversion_value" to conversionValue,
        "metadata" to metadata.ifEmpty { null },
    )

    override fun toString(): String = "Event($type at $timestamp)"

    internal companion object {

        internal const val SOURCE_TYPE: String = "app"
        internal const val PLATFORM: String = "android"
        internal const val STORE: String = "play"

        /**
         * Now, in the one format the server accepts. It validates with class-validator's `IsDateString`, so a home
         * grown format fails the whole event rather than the one field.
         *
         * `SimpleDateFormat` rather than `java.time`, because `minSdk` is 21 and this SDK does not make a customer
         * turn on desugaring. A new formatter per call: they are not thread safe and an event is not a hot loop.
         */
        internal fun nowIso8601(): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date())
    }
}

/**
 * Just enough JSON to write a request body, because this SDK carries no JSON library: a customer's app should not
 * gain Gson because it gained Trace.
 *
 * It writes, it does not read, and it only handles what a Trace payload holds: strings, numbers, booleans and one
 * level of string to string map. Anything else is written as its string form rather than refused, because an event
 * is not worth an exception in somebody else's app.
 */
internal object Json {

    /** An object from the pairs given, in order, leaving out every pair whose value is null. */
    internal fun obj(vararg fields: Pair<String, Any?>): String =
        fields.filter { it.second != null }
            .joinToString(separator = ",", prefix = "{", postfix = "}") { (name, value) ->
                "${string(name)}:${value(value)}"
            }

    private fun value(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        // A non-finite double has no JSON form, and NaN would be written as a bare token no parser accepts, so it
        // becomes null and the server treats the field as absent.
        is Double -> if (value.isFinite()) value.toString() else "null"
        is Float -> if (value.isFinite()) value.toString() else "null"
        is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (k, v) ->
            "${string(k.toString())}:${value(v)}"
        }
        else -> string(value.toString())
    }

    /**
     * One JSON string, escaped so that what comes out parses.
     *
     * Everything outside printable ASCII becomes a `\u` escape, which is wider than JSON demands but makes the body
     * pure ASCII: an emoji is written as the surrogate pair JSON requires and the encoding of the request body
     * cannot disagree with the encoding of the document. Real campaign names carry tabs, control characters and
     * non-ASCII, and a writer that produces invalid JSON on an emoji is worse than the dependency it saved.
     */
    internal fun string(value: String): String {
        val out = StringBuilder(value.length + 2).append('"')
        for (character in value) {
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                else ->
                    if (character.code in 0x20..0x7e) {
                        out.append(character)
                    } else {
                        out.append(String.format(Locale.US, "\\u%04x", character.code))
                    }
            }
        }
        return out.append('"').toString()
    }
}
