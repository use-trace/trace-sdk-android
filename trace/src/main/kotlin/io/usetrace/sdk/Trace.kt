package io.usetrace.sdk

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Trace for Android. This is the whole public surface: four members, and nothing a customer has to call in order.
 *
 * ```kotlin
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         Trace.initialise(this, TraceConfig(apiKey = "trace_your_site_key"))
 *         // On every launch, from whatever the app itself stored when the person answered the banner.
 *         Trace.setConsent(analytics = consentStore.analytics, marketing = consentStore.marketing)
 *     }
 * }
 * ```
 *
 * **It does four things.** It persists an install scoped anonymous key, sends `FIRST_OPEN` once with the Play Store
 * install referrer, sends conversions, and holds everything until the host app says what the person answered. It
 * does not do screen views, session tracking, automatically collected events, funnels or crash reporting, and it
 * never will: those are capabilities that have to be kept working across every future Android release, and this
 * SDK is deliberately not that.
 *
 * **It collects no advertising identifier and does no device fingerprinting.** Not the GAID, not a hardware id, not
 * a signature derived from the device. The install id is a random value with nothing of the device in it, so two
 * installs on one phone are two different people as far as Trace is concerned.
 *
 * **No method throws into the host app, and no method does network work on the calling thread.** Every send runs on
 * one background thread this object owns, so calling any of these from the main thread is safe and calling them
 * from any other thread is safe too. A method called before [initialise] does nothing and says so in logcat; it
 * does not throw, because an SDK that crashes its host has done more damage than the data it was collecting is
 * worth.
 *
 * **There is one background thread, on purpose.** The consent gate holds its lock across a send, which is safe only
 * because nothing else is ever inside it: one thread means an event cannot overtake the consent call that has to go
 * before it, and the order of a journey is the point. A second thread would interleave the two or deadlock on the
 * gate, so if this ever becomes a pool, the gate has to change first.
 *
 * **Nothing is written to the device before consent.** Before the person has answered, the first open and every
 * conversion are held in memory only, and no install id exists. A grant writes the install id and, once the first
 * open has been sent, the first open flag. A refusal writes nothing. An app killed before an answer loses what was
 * held, and its next launch records a first open again, because no flag was written.
 *
 * **A send that fails is not retried on a later launch.** The transport tries three times and then gives up, and
 * nothing the gate flushed is kept. So an app that was offline at the moment consent was granted loses what was
 * held, including its first open. The alternative, a queue that outlives the answer, is a queue some later launch
 * sends again, and a duplicated install is harder to see than a missing one. This is a known limit and it is written
 * in the README rather than hidden here.
 */
public object Trace {

    /**
     * How long the first open waits for the Play Store to produce a referrer before going without one.
     *
     * The first open is sent on the referrer client's answer, and a client that never calls back at all would
     * otherwise mean it is never sent, which turns a missing campaign into a missing install. Two and a half
     * seconds: long enough for a cold bind to Play Services on a slow or busy device, which is normally well under
     * a second, and short enough to finish inside the launch it belongs to. It only ever delays the SDK's own
     * background thread, and only on the first launch of an install.
     */
    internal const val REFERRER_DEADLINE_MILLIS: Long = 2_500

    private const val THREAD_NAME: String = "trace-sdk"

    /** The one conversion name the server has a type of its own for. Matched without regard to case. */
    private const val PURCHASE_NAME: String = "purchase"

    /** What the server keeps a metadata key for. It drops the rest without saying so, so the SDK says so. */
    private val metadataKey: Regex = Regex("^[A-Za-z0-9_]{1,64}$")

    private const val MAX_METADATA_KEYS: Int = 50

    // One thread, named so that a customer reading a stack trace or a thread dump knows whose it is, and a daemon
    // so that it never holds a process open. See the class comment: the gate depends on there being exactly one.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var gate: ConsentGate? = null

    /** The deadline in force. A field rather than the constant so the tests do not wait two and a half seconds. */
    internal var referrerDeadlineMillis: Long = REFERRER_DEADLINE_MILLIS

    /**
     * Starts the SDK and reports the install, once, with whatever the Play Store says produced it.
     *
     * Call it in `Application.onCreate`, with the application context. Calling it later works, but the referrer is
     * only available for a limited window after an install, so later means a worse chance of reading it. Calling it
     * a second time does nothing and says so: the install already happened.
     *
     * It returns immediately. Reading the referrer and recording the first open happen on the SDK's own thread
     * afterwards.
     *
     * **The first open is held in memory until [setConsent] is called**, and nothing is written to the device before
     * then. So nothing reaches Trace until the host app has said what the person answered, and an app that never
     * calls [setConsent] sends nothing and stores nothing, which is the correct behaviour rather than a bug.
     *
     * **The install is reported once, ever.** A flag in the directory Android never backs up records that it went,
     * written when the first open is sent after a grant. A kill before an answer writes no flag, so the next launch
     * records a first open again. A reinstall has no flag and no install id, so it counts as a new install.
     *
     * A blank api key is refused here, with a line in logcat, because nothing could be sent with it and the server
     * would answer 401 to every attempt without the SDK being able to say why.
     */
    @JvmStatic
    public fun initialise(context: Context, config: TraceConfig): Unit =
        initialise(context, config, sender = null, fetchReferrer = null)

    /**
     * Records what the person answered, which is what lets anything be sent at all.
     *
     * `analytics` is the answer that gates events. `marketing` is passed to the server for the record and does not
     * decide whether an event is sent, so marketing alone discards what was held like any other refusal.
     *
     * Granting mints and writes the install id if there is none yet, sends the consent call and then everything held,
     * oldest first. Refusing throws away everything held and writes nothing; when an earlier grant left an install id,
     * it also sends the consent call, which is what withdraws that grant and purges what it allowed. A person who
     * refuses and then agrees is tracked from the moment they agreed.
     *
     * **Call this on every launch, from the answer the app itself stored.** The SDK does not persist the answer, on
     * purpose: the consent record belongs to the app, which has to show it, change it and withdraw it, and two
     * copies of it would disagree. An app that calls this once after its banner and never again starts every later
     * launch holding events that it should be sending.
     *
     * It returns immediately and does no network work on the calling thread. Before [initialise] it does nothing.
     */
    @JvmStatic
    @JvmOverloads
    public fun setConsent(analytics: Boolean, marketing: Boolean = false) {
        val gate = gate ?: return TraceLog.warn(
            "Trace.setConsent was called before Trace.initialise, so the answer was not recorded",
        )
        submit { gate.setConsent(analytics, marketing) }
    }

    /**
     * Sends one conversion: a purchase, a sign up, a subscription, whatever the app counts as an outcome.
     *
     * [name] is the app's own name for it and is what a conversion rule in the Trace dashboard matches on. The one
     * name with a meaning here is `purchase`, in any case, which is sent as the server's own `PURCHASE` type so
     * that it reports as revenue; everything else is a custom conversion. A blank name sends nothing.
     *
     * [value] is the amount, in the currency the site is configured with in Trace.
     *
     * **[currency] is recorded, not applied.** Trace values a conversion in the site's configured currency today,
     * and the ingest route has no per conversion currency field, so this is carried in [metadata] under `currency`
     * rather than dropped. Nothing converts it: a value given in another currency is reported as a number in the
     * site's currency. It is sent so that it is already there when per conversion currency is supported, and so
     * that a customer sending it is not quietly ignored.
     *
     * [metadata] is anything else worth keeping with the conversion. The server keeps keys of letters, digits and
     * underscores, up to 50 of them, and drops the rest without complaint, so this logs which of yours it will
     * drop rather than letting them go missing in silence. Do not put anything identifying in it.
     *
     * The conversion is held if consent is still unknown, dropped if it was refused, and sent otherwise. It
     * returns immediately and does no network work on the calling thread. Before [initialise] it does nothing.
     */
    @JvmStatic
    @JvmOverloads
    public fun conversion(
        name: String,
        value: Double? = null,
        currency: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ) {
        val gate = gate ?: return TraceLog.warn(
            "Trace.conversion was called before Trace.initialise, so nothing was sent",
        )
        val context = appContext ?: return
        val conversionName = name.trim()
        if (conversionName.isEmpty()) return TraceLog.log("a conversion needs a name, so nothing was sent")

        val fields = if (currency == null) metadata else metadata + ("currency" to currency)
        reportWhatTheServerWouldDrop(fields)
        // Taken here rather than on the executor: a conversion recorded while the first open is still waiting on
        // the Play Store would otherwise be stamped with the time the queue reached it, and the order of a journey
        // is what the whole product reads.
        val at = Event.nowIso8601()

        submit {
            gate.record(
                Event(
                    type = if (conversionName.equals(PURCHASE_NAME, ignoreCase = true)) {
                        EventType.PURCHASE
                    } else {
                        EventType.CUSTOM
                    },
                    anonUserKey = "",
                    consentStatus = gate.state,
                    timestamp = at,
                    appVersion = appVersion(context),
                    eventName = conversionName,
                    value = value,
                    metadata = fields,
                ),
            )
        }
    }

    /**
     * The identifier Trace holds for this install, or null when there is not one yet.
     *
     * **This is public because a person's rights depend on it.** Someone asking what Trace holds about them, or
     * asking for it to be deleted, has to be able to find their own identifier first. On the web that is in browser
     * settings, which an app user has nowhere to look at: so the app shows it, on its own privacy screen, and the
     * only place the app can get it is here.
     *
     * It is null until the person has granted consent, because no id exists before then, which is the truthful
     * answer rather than minting an id in order to display one. Reading it creates nothing and sends nothing.
     *
     * It reads one small file on the calling thread, so it is cheap enough for a privacy screen to show directly.
     * It is a visitor identity: show it to the person it belongs to, and do not log it or send it anywhere else.
     */
    @JvmStatic
    public val installId: String?
        get() = appContext?.let { InstallId.peek(it) }

    // The injectable form. The transport and the Play client are both seams, so the tests can assert what would
    // have been sent without a network and without Play Services, and so the deadline below can be driven.
    internal fun initialise(
        context: Context,
        config: TraceConfig,
        sender: EventSender?,
        fetchReferrer: ((Context, (String?) -> Unit) -> Unit)?,
    ) {
        synchronized(this) {
            if (gate != null) {
                return TraceLog.warn(
                    "Trace.initialise was called again, but the SDK is already initialised, so this call did nothing",
                )
            }
            if (config.apiKey.isBlank()) {
                return TraceLog.warn(
                    "Trace.initialise was given a blank api key, so nothing could be sent and nothing was started",
                )
            }

            // The application context, because this one is held for the life of the process and a short lived one
            // would leak an activity. Null when the SDK is reached from Application.attachBaseContext or from a
            // ContentProvider that runs before the application object exists, so fall back rather than crash.
            val application = context.applicationContext ?: context
            TraceLog.debugLogging = config.debugLogging
            val newGate = ConsentGate(application, sender ?: Transport(config.apiKey, config.apiUrl))
            appContext = application
            gate = newGate

            // Queued while this lock is held, which is safe: the work below takes the gate's lock and never this
            // one. It has to be queued before this returns, so that a setConsent on the next line cannot overtake
            // the first open it is meant to release.
            val fetch = fetchReferrer ?: InstallReferrer::fetch
            submit { sendFirstOpenIfNeeded(application, newGate, fetch) }
        }
    }

    private fun sendFirstOpenIfNeeded(
        context: Context,
        gate: ConsentGate,
        fetchReferrer: (Context, (String?) -> Unit) -> Unit,
    ) {
        if (runCatching { ConsentGate.firstOpenFlag(context).exists() }.getOrDefault(false)) {
            TraceLog.log("the first open was reported on an earlier launch, so this launch reports none")
            return
        }

        gate.record(
            Event(
                type = EventType.FIRST_OPEN,
                // The gate stamps the install id when it sends the event. Before a grant there is none to stamp.
                anonUserKey = "",
                consentStatus = gate.state,
                appVersion = appVersion(context),
                installReferrer = awaitReferrer(context, fetchReferrer),
            ),
        )
        ConsentGate.firstOpenFlag(context).writeText("launch")
        // No flag here. The gate writes it when the first open is sent, so a launch that ends before an answer
        // writes nothing and the next launch reports the install instead.
    }

    /**
     * The referrer, or null, within [referrerDeadlineMillis].
     *
     * The deadline is the whole point of this function. `InstallReferrer.fetch` answers once for every response code
     * the Play Store produces, but a client that never calls back at all produces no response code and therefore no
     * answer, and the first open is sent on that answer. Waiting on it without a limit would lose the install, which
     * is the one event that cannot be sent again. A late answer after the deadline is ignored by the caller, because
     * by then the install has gone without it, and the send once flag stops a second one.
     */
    private fun awaitReferrer(context: Context, fetchReferrer: (Context, (String?) -> Unit) -> Unit): String? {
        val answered = CountDownLatch(1)
        val referrer = AtomicReference<String?>(null)

        runCatching {
            fetchReferrer(context) { value ->
                referrer.set(value)
                answered.countDown()
            }
        }.onFailure {
            // fetch does not throw, but a fake or a future version might, and a throw here would otherwise wait
            // out the whole deadline for an answer that is never coming.
            answered.countDown()
        }

        val inTime = try {
            answered.await(referrerDeadlineMillis, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!inTime) {
            TraceLog.log(
                "the play referrer client did not answer within $referrerDeadlineMillis ms, " +
                    "so the install is reported as a direct one",
            )
        }
        return referrer.get()
    }

    // The payload is not changed, only reported. What the server keeps is the server's decision, and renaming a
    // customer's key here would be a second surprise on top of the first.
    private fun reportWhatTheServerWouldDrop(metadata: Map<String, String>) {
        val dropped = metadata.keys.filterNot { metadataKey.matches(it) }
        if (dropped.isNotEmpty()) {
            TraceLog.log(
                "the server keeps metadata keys of letters, digits and underscores only, " +
                    "so it will drop: ${dropped.joinToString()}",
            )
        }
        if (metadata.size > MAX_METADATA_KEYS) {
            TraceLog.log("the server keeps at most $MAX_METADATA_KEYS metadata keys and this one has ${metadata.size}")
        }
    }

    private fun appVersion(context: Context): String? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    // Nothing reaches the host app from in here. A task that threw would otherwise be reported by the executor on
    // a thread the customer did not create, which reads as a crash in their app.
    private fun submit(work: () -> Unit) {
        val queued = runCatching {
            executor.execute {
                runCatching { work() }.onFailure {
                    TraceLog.log("a background task failed with ${it.javaClass.simpleName} and was not passed on")
                }
            }
        }
        if (queued.isFailure) TraceLog.log("the SDK's own thread refused the work, so nothing was sent")
    }

    /**
     * Waits until everything queued so far has run. For the tests: the public surface is asynchronous on purpose,
     * and an assertion about what was sent needs somewhere to wait.
     */
    internal fun awaitIdle(timeoutMillis: Long = 10_000) {
        val idle = CountDownLatch(1)
        runCatching { executor.execute { idle.countDown() } }
        runCatching { idle.await(timeoutMillis, TimeUnit.MILLISECONDS) }
    }

    /**
     * Forgets everything held in memory, which is what a process death does. For the tests: this object outlives a
     * test method, and the test that matters most is the one that initialises twice in two simulated processes.
     *
     * It deliberately does not touch what is on disk, because that is exactly what survives a process death: the
     * install id and the first open flag, once a grant has written them.
     */
    internal fun resetForTest() {
        awaitIdle()
        synchronized(this) {
            appContext = null
            gate = null
            referrerDeadlineMillis = REFERRER_DEADLINE_MILLIS
        }
    }
}
