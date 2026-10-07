package io.usetrace.sdk

import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the consent gate sends through, and the only part of the transport anything else in the SDK may depend on.
 *
 * It exists so a test can stand in for the network without copying the real class's shape: a hand written double
 * drifts from [Transport] the moment [Transport] changes, and a drifting double is a test that passes while the
 * thing it stands for is broken. Internal, like everything else here: a customer gets no transport API and cannot
 * replace the network layer.
 */
internal interface EventSender {

    /** Sends one event, returning whether the server took it. Never throws. */
    fun send(event: Event): Boolean

    /**
     * Sends the consent answers under [key], returning whether the server took them. Never throws.
     *
     * [key] is not optional: see [Transport.sendConsent] for what the server does without it. [firstAnswer] says
     * whether this is the install's first answer, so the server counts each install's answer once.
     */
    fun sendConsent(key: String, analytics: Boolean, marketing: Boolean, firstAnswer: Boolean = false): Boolean

    /**
     * Reports a refusal from an install that has no id, so the server can count the answer. It carries no
     * identifier of any kind: `consent_analytics` false, the marketing answer, the time, the platform and
     * `first_answer` true. Returns whether the server took it. Never throws.
     */
    fun sendFirstRefusal(marketing: Boolean): Boolean
}

/**
 * The only thing in this SDK that touches the network. `HttpURLConnection` and nothing else: a customer's app does
 * not gain okhttp because it gained Trace.
 *
 * **Neither method throws, ever.** They return false. A refused connection, a host that does not resolve, a server
 * that never answers, a typo in the configured api url: all of them are a false and a log line. This runs on the
 * ingest path of somebody else's app, where an exception is a crash the customer did not write and cannot fix.
 *
 * It does not know about consent and it does not queue. It sends what it is given, once, and says whether the
 * server took it. Holding an event until consent is known is the consent gate's job, and deciding what to do with a
 * false is the caller's.
 *
 * **Delivered means a 2xx with the Trace API's own answer, not any 2xx.** `/v1/event` answers 202 with
 * `"accepted": true`, and `/v1/consent` answers 201 with a boolean `cookie_set`. Until 7 October 2026 any 2xx
 * counted, and the default address reached the dashboard, which answers a POST with a web page and a 200: every
 * event was lost and nothing said so. A 2xx without that answer (a page, an empty body, some other JSON) is an
 * address that is not the Trace API. It is not delivered and it is not retried, because a wrong address stays wrong.
 * It is logged once per transport, whether or not the host app turned logging on, because a developer who never
 * turned it on is the one who needs to hear it, and without the body, which could hold anything.
 *
 * It is synchronous. Every call must be made on a background thread, which the public API owns.
 */
internal class Transport(
    private val apiKey: String,
    apiUrl: String,
    /** Connect and read timeout. Ten seconds in a real app; the tests use a short one deliberately. */
    private val timeoutMillis: Int = 10_000,
) : EventSender {

    private val baseUrl: String = apiUrl.trimEnd('/')

    /** Whether the configuration error has been logged. */
    private val warned = AtomicBoolean(false)

    /**
     * Sends one event to `POST /v1/event` and returns whether the server took it.
     *
     * True means a 2xx with `"accepted": true`. False means the server refused it, could not be reached, or was not
     * the Trace API, and the caller still holds the only copy: nothing here retries a 4xx, because a payload the
     * server rejected it will reject again.
     */
    override fun send(event: Event): Boolean {
        val accepted = post("/v1/event", event.toJson())
        TraceLog.log("${event.type} ${if (accepted) "accepted" else "not accepted"}")
        return accepted
    }

    /**
     * Sends the consent answers to `POST /v1/consent` and returns whether the server took them.
     *
     * **[key] is not optional and there is no overload without it.** On a consent gated site the server buffers the
     * first open and replays it once consent arrives, taking the anonymous key from this call. A consent call
     * without one makes the server mint a key of its own and record it as though it were the app's install id,
     * which is a false provenance rather than a missing field.
     */
    override fun sendConsent(key: String, analytics: Boolean, marketing: Boolean, firstAnswer: Boolean): Boolean =
        postConsent(
            "consent_analytics" to analytics,
            "consent_marketing" to marketing,
            "anon_user_key" to key,
            "first_answer" to firstAnswer,
        )

    override fun sendFirstRefusal(marketing: Boolean): Boolean =
        postConsent("consent_analytics" to false, "consent_marketing" to marketing, "first_answer" to true)

    // The share of people who said yes is worked out per platform, counting each install's answer once by
    // first_answer (decision 3 of APP_MODELLED_INSTALLS.md in use-trace/trace).
    private fun postConsent(vararg answer: Pair<String, Any?>): Boolean {
        val body = Json.obj(*answer, "timestamp" to Event.nowIso8601(), "platform" to "android")
        val accepted = post("/v1/consent", body)
        TraceLog.log("consent ${if (accepted) "accepted" else "not accepted"}")
        return accepted
    }

    private fun post(path: String, body: String): Boolean {
        // Built once, outside the retry loop: a url the SDK cannot parse will not parse on a second attempt either.
        val url = runCatching { URL(baseUrl + path) }.getOrNull()
        if (url == null) {
            TraceLog.log("cannot send $path: the configured api url is not a url")
            return false
        }

        var attempt = 1
        while (true) {
            val outcome = attempt(url, path, body)
            if (outcome.accepted) return true
            if (!outcome.worthRetrying || attempt == ATTEMPTS) {
                TraceLog.log("$path failed on attempt $attempt of $ATTEMPTS, ${outcome.reason}, giving up")
                return false
            }
            TraceLog.log("$path attempt $attempt of $ATTEMPTS failed, ${outcome.reason}, trying again")
            // A short backoff, because the caller may be holding the one copy of an install that cannot be sent
            // again, and a long one on a background thread outlives the launch it belongs to.
            if (!sleep(BACKOFF_MILLIS * attempt)) return false
            attempt++
        }
    }

    private class Outcome(val accepted: Boolean, val worthRetrying: Boolean, val reason: String)

    private fun attempt(url: URL, path: String, body: String): Outcome {
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-trace-api-key", apiKey)
                // Never empty. The server's bot guard reads an empty user agent as a bot and then ignores the
                // event behind a 200, so sending none is the one failure the SDK cannot see from here.
                setRequestProperty("User-Agent", userAgent)
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            // Read to the end so the socket can be reused rather than left to a finaliser.
            val answer = runCatching {
                (if (status in 200..299) connection.inputStream else connection.errorStream)?.use {
                    it.readBytes().decodeToString()
                }
            }.getOrNull()

            when {
                status in 200..299 && isTraceAnswer(answer, path) ->
                    Outcome(accepted = true, worthRetrying = false, reason = "accepted")
                status in 200..299 -> {
                    if (!warned.getAndSet(true)) {
                        TraceLog.warn(
                            "$path answered $status but not as the Trace API does, so nothing is being delivered: " +
                                "check the configured api url",
                        )
                    }
                    Outcome(false, worthRetrying = false, reason = "answered $status without the Trace API's answer")
                }
                // A 4xx is the server saying the payload is wrong, and it will say the same thing again. Retrying
                // is noise that reads as a flaky network and spends somebody else's data allowance three times.
                status in 400..499 -> Outcome(accepted = false, worthRetrying = false, reason = "refused with $status")
                else -> Outcome(accepted = false, worthRetrying = true, reason = "server answered $status")
            }
        } catch (networkFailure: IOException) {
            // No connection, no answer, or an answer too late. Worth another try: the next one may find a network.
            Outcome(accepted = false, worthRetrying = true, reason = "the request did not complete")
        } catch (notHttp: Exception) {
            // A configured url that parses but is not HTTP, so openConnection hands back something else. It will
            // not become HTTP on a second attempt.
            Outcome(accepted = false, worthRetrying = false, reason = "the configured api url is not an http url")
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** Whether [answer] is what the Trace API answers [path] with when it has taken the request. */
    private fun isTraceAnswer(answer: String?, path: String): Boolean {
        val json = runCatching { JSONObject(answer.orEmpty()) }.getOrNull() ?: return false
        return if (path == "/v1/consent") json.opt("cookie_set") is Boolean else json.opt("accepted") == true
    }

    // False when the wait was interrupted, which means whatever is sending is being shut down, so stop.
    private fun sleep(millis: Long): Boolean = try {
        Thread.sleep(millis)
        true
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private companion object {

        private const val ATTEMPTS: Int = 3
        private const val BACKOFF_MILLIS: Long = 250

        /**
         * `TraceSdkAndroid/<version> (Android <release>)`, and never empty: the version is compiled in and the
         * Android release falls back to a word rather than nothing, because an empty user agent is read as a bot.
         */
        private val userAgent: String =
            "TraceSdkAndroid/${BuildConfig.SDK_VERSION} (Android ${Build.VERSION.RELEASE ?: "unknown"})"
    }
}
