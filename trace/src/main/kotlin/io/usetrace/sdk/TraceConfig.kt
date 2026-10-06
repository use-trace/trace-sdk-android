package io.usetrace.sdk

/**
 * What [Trace.initialise] needs: the site's api key, where to send to, and whether to say anything while it works.
 *
 * There is nothing else to configure. No sampling, no batching interval, no endpoint overrides, no opt in flags: a
 * setting is a promise to keep it working forever, and every one of those would be a way for two installs of the
 * same app to report differently.
 *
 * **It is not a data class.** A data class would put the api key in its own `toString`, and from there into a log
 * line or a crash report in somebody else's app, which is how a site key leaks. The same reasoning as [Event].
 */
public class TraceConfig @JvmOverloads constructor(
    /**
     * The site's api key, sent as `x-trace-api-key`. Found in the Trace dashboard under the site's settings.
     *
     * It is not a secret in the sense a server key is, because it ships inside an app a customer can unpack, and
     * the server treats it as the name of a site rather than as proof of anything. It is still not logged.
     */
    public val apiKey: String,
    /**
     * Where to send. The hosted API unless a self hosted deployment says otherwise, and a trailing slash does no
     * harm. An url this SDK cannot parse is reported once and then nothing is sent, rather than throwing.
     */
    public val apiUrl: String = "https://app.usetrace.io",
    /**
     * Whether the SDK writes what it is doing to logcat under the tag `Trace`. Off by default, because a library
     * that writes to someone else's logcat uninvited is a nuisance.
     *
     * It never writes an install id, a referrer or an event's contents, whatever this is set to: those are
     * stripped where the line is written, not where it is called. Turning this on is safe in a release build,
     * though there is no reason to.
     */
    public val debugLogging: Boolean = false,
) {

    /** The url and the flag. Never the api key, which is the whole reason this is written out by hand. */
    override fun toString(): String = "TraceConfig(apiUrl=$apiUrl, debugLogging=$debugLogging)"
}
