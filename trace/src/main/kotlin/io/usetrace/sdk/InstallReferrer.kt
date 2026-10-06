package io.usetrace.sdk

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Play Store install referrer, which is the only thing that says which campaign produced an install.
 *
 * **This object does not parse the referrer, and nothing here ever should.** The Play client hands over one URL
 * encoded query string and it is passed on exactly as it arrived, percent encoding intact. The server parses it,
 * in `packages/attribution-channel/src/install-referrer.ts`, and that is the one place a channel is decided. A
 * second parser here would drift from it and the same campaign would be classified differently depending on which
 * end you asked.
 *
 * It does not retry, does not cache and does not hold a client between calls. The referrer is only available for a
 * limited window after an install and a Play connection cannot be reused, so there is one client per [fetch] and
 * it is ended again before the answer is delivered.
 *
 * It does not log. The referrer carries campaign values, and a click id until the server strips it, so the string
 * is never written anywhere but the request body.
 */
internal object InstallReferrer {

    /**
     * Reads this install's Play Store referrer and hands it to [onResult] exactly once, on whichever thread the
     * Play client answers on.
     *
     * The answer is null when there is no referrer to be had: no Play Store on the device, the service
     * unavailable, the feature unsupported, or a client that threw. **That is a direct install, not an error.**
     * The caller still sends the first open, without a referrer; a referrer failure that stopped an install being
     * recorded would turn a missing campaign into a missing install, which is strictly worse.
     *
     * It does not throw, whatever the client does. A throw out of [onResult] itself is the caller's own and is not
     * swallowed, but the Play connection is ended before it leaves.
     *
     * It does not impose a deadline of its own: a Play client that never answers at all means [onResult] is never
     * called, so a caller must not make the first open wait on this.
     */
    @JvmStatic
    internal fun fetch(context: Context, onResult: (String?) -> Unit) {
        fetch(PlayReferrerClient(context), onResult)
    }

    // The injectable form, which the tests drive with a fake so that no Play Services are needed.
    internal fun fetch(client: ReferrerClient, onResult: (String?) -> Unit) {
        // The real client is allowed to answer late, on a thread of its own, and more than once. Only the thread
        // that wins this flag delivers, so a duplicate or a late callback is dropped rather than sending a second
        // install.
        val delivered = AtomicBoolean(false)

        fun deliver(referrer: String?) {
            if (!delivered.compareAndSet(false, true)) return
            try {
                onResult(referrer)
            } finally {
                // endConnection throws as readily as startConnection does, and an unended connection outlives the
                // one fetch it was built for, so it is ended whatever happened and its throw goes nowhere.
                runCatching { client.endConnection() }
            }
        }

        val started = runCatching {
            client.startConnection { responseCode ->
                val referrer =
                    if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                        runCatching { client.referrer() }.getOrNull()
                    } else {
                        null
                    }
                deliver(referrer)
            }
        }
        // A client that threw on the way in has no answer coming, so answer for it.
        if (started.isFailure) deliver(null)
    }
}

/**
 * The part of Play's `InstallReferrerClient` this SDK uses, behind an interface so the tests can drive the
 * failures a real Play Store produces. Internal: not part of the public surface.
 */
internal interface ReferrerClient {

    /** Connects, then reports Play's response code once the connection is up or has failed. */
    fun startConnection(onSetupFinished: (responseCode: Int) -> Unit)

    /** The raw referrer string, unparsed. Only meaningful after an `OK` response code. */
    fun referrer(): String?

    /** Releases the connection. Allowed to throw; the caller expects that. */
    fun endConnection()
}

private class PlayReferrerClient(context: Context) : ReferrerClient {

    private val client: InstallReferrerClient = InstallReferrerClient.newBuilder(context).build()

    override fun startConnection(onSetupFinished: (responseCode: Int) -> Unit) {
        client.startConnection(
            object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) = onSetupFinished(responseCode)

                // A service that went away before it answered is a referrer this SDK cannot read, so it is
                // reported as unavailable and becomes a direct install. Reconnecting would leave the caller
                // waiting on a service that may never come back, and the one shot guard ignores a later answer.
                override fun onInstallReferrerServiceDisconnected() =
                    onSetupFinished(InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE)
            },
        )
    }

    override fun referrer(): String? = client.installReferrer.installReferrer

    override fun endConnection() = client.endConnection()
}
