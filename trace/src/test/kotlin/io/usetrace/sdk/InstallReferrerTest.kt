package io.usetrace.sdk

import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The referrer is the only thing that says which campaign produced an install, and the Play client that carries it
 * is callback based, asynchronous, allowed to fail in several ways and allowed to throw on the way out.
 *
 * These tests hold the wrapper to four things. The string arrives unchanged, because the server is the one place
 * it is parsed. A failure is a direct install, not an exception in someone else's app. The connection is ended
 * whatever happened. And [InstallReferrer.fetch] answers exactly once, because the caller sends the first open on
 * that answer and two answers would send two installs.
 *
 * They drive an injected fake, so no Play Services are needed and the failures a real Play Store produces are all
 * reachable.
 */
class InstallReferrerTest {

    /**
     * One real referrer, with the percent encoding the Play Store leaves in it: a space, a plus and a per cent in
     * the campaign name, and a click id. Every assertion compares against this exact string, so any parsing,
     * decoding or trimming in the SDK fails the test.
     */
    private val raw =
        "utm_source=google-play&utm_medium=cpc&utm_campaign=spring%20sale%2B10%25&gclid=EAIaIQobChMI%2Fnot-real"

    private class FakeClient(
        private val referrer: String? = null,
        private val throwOnConnect: Boolean = false,
        private val throwOnEnd: Boolean = false,
    ) : ReferrerClient {

        val endCalls = AtomicInteger(0)
        private var listener: ((Int) -> Unit)? = null

        override fun startConnection(onSetupFinished: (Int) -> Unit) {
            if (throwOnConnect) throw IllegalStateException("no Play Store on this device")
            listener = onSetupFinished
        }

        override fun referrer(): String? = referrer

        override fun endConnection() {
            endCalls.incrementAndGet()
            if (throwOnEnd) throw IllegalStateException("the service has already gone")
        }

        /** What the real client does on its own thread once the connection is up, or has failed. */
        fun setupFinished(responseCode: Int) = listener!!(responseCode)
    }

    /** Collects what fetch answered, and how many times, so every test can assert exactly once. */
    private class Answers {
        val calls = AtomicInteger(0)
        var referrer: String? = null
        val record: (String?) -> Unit = { calls.incrementAndGet(); referrer = it }
    }

    @Test
    fun `a successful connection yields the referrer unchanged`() {
        val client = FakeClient(referrer = raw)
        val answers = Answers()

        InstallReferrer.fetch(client, answers.record)
        client.setupFinished(InstallReferrerResponse.OK)

        assertEquals("the referrer must arrive byte for byte, percent encoding intact", raw, answers.referrer)
        assertEquals(1, answers.calls.get())
        assertEquals(1, client.endCalls.get())
    }

    @Test
    fun `feature not supported is a direct install, not an error`() {
        assertDirectInstall(FakeClient(referrer = raw), InstallReferrerResponse.FEATURE_NOT_SUPPORTED)
    }

    @Test
    fun `service unavailable is a direct install, not an error`() {
        assertDirectInstall(FakeClient(referrer = raw), InstallReferrerResponse.SERVICE_UNAVAILABLE)
    }

    @Test
    fun `a client that throws on connect is a direct install, not an error`() {
        val client = FakeClient(throwOnConnect = true)
        val answers = Answers()

        InstallReferrer.fetch(client, answers.record)

        assertNull(answers.referrer)
        assertEquals("a failure to connect must still answer, or no install is ever sent", 1, answers.calls.get())
        assertEquals("the connection must be ended even when starting it threw", 1, client.endCalls.get())
    }

    @Test
    fun `a client that throws on endConnection still answers, and the throw does not escape`() {
        val client = FakeClient(referrer = raw, throwOnEnd = true)
        val answers = Answers()

        InstallReferrer.fetch(client, answers.record)
        client.setupFinished(InstallReferrerResponse.OK)

        assertEquals(raw, answers.referrer)
        assertEquals(1, answers.calls.get())
        assertEquals(1, client.endCalls.get())
    }

    @Test
    fun `a callback that throws still ends the connection`() {
        val client = FakeClient(referrer = raw)
        InstallReferrer.fetch(client) { throw IllegalStateException("the caller's own bug, on the Play thread") }

        // The caller's throw is the caller's, and it is not swallowed. What the SDK owes is the connection: ended
        // before the throw leaves, because a leaked Play connection outlives the one fetch it was built for.
        try {
            client.setupFinished(InstallReferrerResponse.OK)
        } catch (expected: IllegalStateException) {
            // The caller's own exception, on its way out.
        }

        assertEquals("the connection must be ended even when the callback threw", 1, client.endCalls.get())
    }

    @Test
    fun `a listener that fires twice answers once`() {
        val client = FakeClient(referrer = raw)
        val answers = Answers()

        InstallReferrer.fetch(client, answers.record)
        client.setupFinished(InstallReferrerResponse.OK)
        client.setupFinished(InstallReferrerResponse.OK)

        assertEquals("a second answer would send a second install", 1, answers.calls.get())
        assertEquals(1, client.endCalls.get())
    }

    @Test
    fun `two threads answering at once answer once`() {
        val client = FakeClient(referrer = raw)
        val answers = Answers()
        InstallReferrer.fetch(client, answers.record)

        // The real callback arrives on a binder thread, so a duplicate can race rather than queue behind the first.
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        repeat(2) {
            Thread {
                start.await()
                client.setupFinished(InstallReferrerResponse.OK)
                done.countDown()
            }.start()
        }
        start.countDown()
        done.await()

        assertEquals(1, answers.calls.get())
        assertEquals(1, client.endCalls.get())
    }

    private fun assertDirectInstall(client: FakeClient, responseCode: Int) {
        val answers = Answers()

        InstallReferrer.fetch(client, answers.record)
        client.setupFinished(responseCode)

        assertNull("a referrer the Play Store will not supply is no referrer", answers.referrer)
        assertEquals(1, answers.calls.get())
        assertEquals(1, client.endCalls.get())
    }
}
