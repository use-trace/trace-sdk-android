# Android SDK, slice 1: install attribution

> **For agentic workers:** use `superpowers:subagent-driven-development` to implement this plan task by task.

**Goal:** an Android app sends its first open with the Play Store install referrer, and its conversions, so a paid
install lands in the channel that produced it.

**Architecture:** one Kotlin Android library, no third party HTTP dependency. A persisted install id, a consent
gate that holds events until the state is known, and a transport that speaks the existing `/v1/event` and
`/v1/consent` routes. Google's Play Install Referrer library is the only dependency, because nothing else can read
the referrer.

**Spec:** `docs/plans/APP_TRACKING.md` in `use-trace/trace`, and `.claude/rules/app-tracking.md` there, which is
binding. `CLAUDE.md` in this repository carries the subset that applies here.

## Global constraints

- British English in code comments, documentation and commit messages. No em dashes or en dashes anywhere.
- Kotlin, `minSdk 21`, `compileSdk 35`, JVM target 17. Android Gradle Plugin 8.x, Gradle 8.x.
- **The only runtime dependency is `com.android.installreferrer:installreferrer:2.2`.** No okhttp, no Retrofit, no
  Gson, no coroutines-beyond-stdlib. An SDK a customer embeds adds what it must and nothing else. Use
  `HttpURLConnection` and hand rolled JSON encoding.
- **No advertising identifiers, ever.** No GAID, no `AdvertisingIdClient`, no device fingerprint. This is published
  in the customer privacy notice, so it is a promise, not a preference.
- **A visitor identity never appears in a log line or an error report.** Log the event type and the outcome, never
  the install id.
- The SDK does five things and no more: persist an install id, send `FIRST_OPEN` once with the referrer, send
  conversions, `identify(hash)`, and hold events until consent is known. No screen views, no session tracking, no
  automatic events, no crash reporting.
- Every public symbol has KDoc saying what it does and what it does not.
- A failing test first, then the implementation.

## The contract this SDK speaks

Both routes take the header `x-trace-api-key: <site key>` and return JSON.

`POST {apiUrl}/v1/event`, fields this SDK sends:

| Field | Value |
| --- | --- |
| `event_type` | `FIRST_OPEN`, `PURCHASE`, or `CUSTOM` |
| `source_type` | always `app` |
| `platform` | always `android` |
| `store` | `play` |
| `app_version` | the host app's `versionName` |
| `install_referrer` | the raw Play referrer string, first open only |
| `anon_user_key` | the install id, always |
| `consent_status` | `GRANTED`, `DENIED` or `UNKNOWN` |
| `timestamp` | ISO 8601, the moment the event happened |
| `event_name`, `value`, `conversion_type_id`, `conversion_value`, `metadata` | conversions only |

`POST {apiUrl}/v1/consent`, fields: `consent_analytics`, `consent_marketing`, `anon_user_key`.

**Success is any 2xx, and it is not a 200.** `/v1/event` answers 202 and `/v1/consent` answers 201, verified in the
controllers, so a client that tested for 200 would read every successful send as a failure, retry a request the
server had already taken, and finally report the install as lost. Treat 200 to 299 as taken, a 4xx as refused and
not worth retrying, and a 5xx as worth retrying.

**`anon_user_key` on the consent call is not optional.** On a consent gated site the server buffers the first open
and replays it after consent, taking the key from the consent call. Omit it and the server mints its own key and
labels it as the app's install id, which is a false provenance. This is written in `.claude/rules/app-tracking.md`
in the main repository and it is the single easiest thing to get wrong here.

**The request must carry a non-empty `User-Agent`.** The server's bot guard treats an empty one as a bot and
silently ignores the event behind a 200. Send `TraceSdkAndroid/<sdk version> (Android <release>)`.

## Two decisions already made, do not reopen

- **An install id does not survive an uninstall.** It is held in `Context.getNoBackupFilesDir()`, which Android
  excludes from Auto Backup, from a device transfer and from a cross platform transfer, whatever the host app's
  backup rules say. Two reasons. An install id identifies one install, so an id that came back after a reinstall
  would count the reinstall as the same install and the install numbers would be wrong. And it is a visitor
  identity: restoring it would quietly join a person back to the journey history they had before they uninstalled,
  which someone who removed the app may reasonably treat as finished. A reinstall therefore mints a fresh id and
  counts as a new install. Over counting reinstalls is the better failure.
- **Android native only.** No React Native or Flutter wrapper in this slice. The public surface must contain no
  Android specific types in its signatures beyond `Context` at initialisation, so a wrapper stays possible later.

## File structure

```
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
trace/
  build.gradle.kts
  src/main/AndroidManifest.xml
  src/main/kotlin/io/usetrace/sdk/
    Trace.kt              public API, the only file a customer reads
    TraceConfig.kt        api key, api url, debug logging flag
    InstallId.kt          persist in the no-backup directory, expose
    InstallReferrer.kt    Play Install Referrer wrapper
    Transport.kt          HttpURLConnection, JSON, User-Agent, retry
    ConsentGate.kt        hold, flush, discard
    Event.kt             the payload shape
  src/test/kotlin/io/usetrace/sdk/   unit tests, JVM, no device
```

---

### Task 1: the Gradle project and a CI job that builds it

**Files:** `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `trace/build.gradle.kts`,
`trace/src/main/AndroidManifest.xml`, `.github/workflows/ci.yml` (modify), `.gitignore`

An Android library module named `trace`, group `io.usetrace`, artefact `trace-sdk-android`, version `0.1.0`.
`minSdk 21`, `compileSdk 35`, JVM target 17, Kotlin JVM toolchain 17. The only implementation dependency is
`com.android.installreferrer:installreferrer:2.2`. Test dependencies: `junit:junit:4.13.2` and
`org.robolectric:robolectric:4.13` for the tests that need a `Context`.

- [ ] Add a `build` and a `unit tests` job to `.github/workflows/ci.yml`, beside the existing copy rules job, using
  `actions/setup-java@v4` with Temurin 17 and `gradle/actions/setup-gradle@v4`. Remove the comment in that file
  saying the build jobs arrive with the first code, because they have.
- [ ] `./gradlew :trace:assembleRelease` and `./gradlew :trace:testDebugUnitTest` both pass on a clean checkout.
- [ ] Commit.

**Produces:** a buildable library module. Every later task adds to `trace/src`.

---

### Task 2: the install id, which does not survive an uninstall

**Files:** `trace/src/main/kotlin/io/usetrace/sdk/InstallId.kt`,
`trace/src/test/kotlin/io/usetrace/sdk/InstallIdTest.kt`

**Produces:** `InstallId.get(context: Context): String` and `InstallId.peek(context: Context): String?`

- [ ] **Write the failing tests** (Robolectric, so a real file system and a real `Context`):
  - `get` returns the same value on a second call, because an install id that changes is not an install id.
  - `get` returns a value matching `^auk_app_[0-9a-f]{32}$`. The `auk_` prefix is what the server's other keys use;
    `app_` says where it came from.
  - `peek` returns null before any `get`, and the id afterwards. The host app needs to read the id without
    creating one, so a privacy screen can say "no id yet" truthfully.
  - Two different `Context`s with the same package share the id.
- [ ] **Implement.** A single file named `install_id` in `Context.getNoBackupFilesDir()`, holding
  `"auk_app_" + UUID.randomUUID().toString().replace("-", "")`. Generate with `java.util.UUID`, never from
  anything about the device. A plain file read and write, not `SharedPreferences`.
- [ ] **Keep it out of a backup, with the mechanism no host app can override.** `getNoBackupFilesDir()` is enough
  on its own: Android's documentation says files in it "are always excluded even if you try to include them", and
  that a backup mode left out of an app's data extraction rules is "fully enabled for all content except for
  no-backup and cache directories". So there is no `trace_backup_rules.xml`, no `data_extraction_rules.xml` and no
  `android:fullBackupContent` or `android:dataExtractionRules` on the library manifest. Those were the earlier
  design and they do not hold: a library manifest attribute merges only into an app that sets none of its own, an
  app that sets its own gets a merge conflict, and the `tools:replace` the merger suggests then drops the library's
  rules with no warning at all. Verified against a throwaway host app module. KDoc the guarantee, and say plainly
  that an integrator has nothing to carry across.
- [ ] **A test that the id is stored under `getNoBackupFilesDir()`**, not merely that it reads back, because
  reading back would pass from any directory. Name it after the behaviour.
- [ ] Commit.

---

### Task 3: reading the Play Store install referrer

**Files:** `trace/src/main/kotlin/io/usetrace/sdk/InstallReferrer.kt`,
`trace/src/test/kotlin/io/usetrace/sdk/InstallReferrerTest.kt`

**Produces:** `InstallReferrer.fetch(context: Context, onResult: (String?) -> Unit)`

The Play Install Referrer client is callback based, connects asynchronously, and must be ended. It returns
`installReferrer` as one URL encoded query string, which is exactly what the server's `parseInstallReferrer`
expects, so **send it raw and parse nothing here.** Parsing it twice is how the two sides drift apart.

- [ ] **Write the failing tests** against an injected fake client interface, so no Play Services are needed:
  - a connection that succeeds yields the referrer string unchanged, including percent encoding
  - `FEATURE_NOT_SUPPORTED` yields null, and does not throw
  - `SERVICE_UNAVAILABLE` yields null, and does not throw
  - a client that throws on connect yields null, and does not throw
  - the client is ended in every one of those cases, including the throwing one
  - `onResult` is called exactly once, never twice, in every case
- [ ] **Implement.** Wrap `InstallReferrerClient` behind a small internal interface so the tests above can drive it.
  Call `endConnection()` in a `finally`. A referrer this SDK cannot read is a direct install, not an error: the
  first open is still sent, with no referrer.
- [ ] Commit.

---

### Task 4: the transport

**Files:** `trace/src/main/kotlin/io/usetrace/sdk/Transport.kt`,
`trace/src/main/kotlin/io/usetrace/sdk/Event.kt`,
`trace/src/test/kotlin/io/usetrace/sdk/TransportTest.kt`

**Produces:** `Transport.send(event: Event): Boolean` and `Transport.sendConsent(key: String, analytics: Boolean, marketing: Boolean): Boolean`

- [ ] **Write the failing tests** against a JVM `HttpServer` stub on a loopback port, asserting what arrives:
  - the `x-trace-api-key` header carries the configured key
  - the `User-Agent` is non-empty and starts `TraceSdkAndroid/`. **This test exists because an empty one makes the
    server treat the event as a bot and ignore it behind a 200.**
  - `source_type` is `app` and `platform` is `android` on every event
  - `anon_user_key` is present on every event, and on the consent call
  - a `FIRST_OPEN` carrying a referrer sends it byte for byte, percent encoding intact
  - JSON encodes a quote, a backslash and a newline in a campaign name without producing invalid JSON
  - a 2xx returns true, with the stub answering 202 for an event and 201 for a consent call as the API really does,
    because a client that only accepted 200 would read every successful send as a failure
  - a 400 returns false and does not retry, because a rejected payload will be rejected again
  - a 500 retries, and gives up after three attempts in total
  - a connection refused returns false rather than throwing out of `send`
  - no log line contains the install id, asserted by capturing the SDK's own log output
- [ ] **Implement.** `HttpURLConnection`, `POST`, `Content-Type: application/json`, connect and read timeouts of
  ten seconds, hand rolled JSON with a string escaper. Retry on a 5xx or an `IOException` only, with a short
  backoff, three attempts. Never retry a 4xx.
- [ ] Commit.

---

### Task 5: the consent gate

**Files:** `trace/src/main/kotlin/io/usetrace/sdk/ConsentGate.kt`,
`trace/src/test/kotlin/io/usetrace/sdk/ConsentGateTest.kt`

**Produces:** `ConsentGate` with `record(event: Event)`, `setConsent(analytics: Boolean, marketing: Boolean)`, and
`state: ConsentState` where `ConsentState` is `UNKNOWN`, `GRANTED` or `DENIED`.

The rule from the spec: hold events until the consent state is known, then flush or discard. Do not send and
apologise later.

- [ ] **Write the failing tests** against a fake transport that records calls:
  - an event recorded while `UNKNOWN` is not sent
  - granting consent sends the held events, oldest first, because a journey's order is the point
  - granting consent sends the consent call **before** the held events, so the server has the key when it replays
  - denying consent discards the held events and sends none of them
  - an event recorded after a grant is sent immediately, not queued
  - an event recorded after a denial is dropped, and nothing is sent
  - the queue survives process death: events held before a restart are still sent after consent is granted in a
    later process. **Persist the queue**, because an app killed before the banner is answered otherwise loses the
    install, and the install is the one event that cannot be sent again.
  - the queue is bounded, and the oldest event is dropped first past the bound, so a never answered banner cannot
    grow without limit. Bound it at 100 events and log when one is dropped.
  - the held queue is cleared from disk after a flush and after a discard, so a denial leaves nothing behind
- [ ] **Implement.** Persist the queue as JSON lines in the SDK's own file in `context.noBackupFilesDir`, the same
  directory as the install id and for the same reason: it carries that id, so a restored queue would say the same
  wrong thing.
- [ ] Commit.

---

### Task 6: the public API

**Files:** `trace/src/main/kotlin/io/usetrace/sdk/Trace.kt`,
`trace/src/main/kotlin/io/usetrace/sdk/TraceConfig.kt`,
`trace/src/test/kotlin/io/usetrace/sdk/TraceTest.kt`

**Produces:** the only surface a customer touches:

```kotlin
object Trace {
    fun initialise(context: Context, config: TraceConfig)
    fun setConsent(analytics: Boolean, marketing: Boolean = false)
    fun conversion(name: String, value: Double? = null, currency: String? = null, metadata: Map<String, String> = emptyMap())
    fun identify(hashedIdentifier: String)
    val installId: String?
}
```

`TraceConfig(apiKey: String, apiUrl: String = "https://app.usetrace.io", debugLogging: Boolean = false)`.

- [ ] **Write the failing tests:**
  - `initialise` sends a `FIRST_OPEN` on the first ever launch, carrying the referrer and the app version
  - `initialise` sends **no** `FIRST_OPEN` on the second launch, ever, because the install happened once. Pin this
    with a persisted flag separate from the install id, and assert across two simulated process lifetimes.
  - the `FIRST_OPEN` is held, not sent, while consent is `UNKNOWN`
  - `conversion` before `initialise` does nothing and logs a clear message, rather than throwing into the host app.
    **An SDK must not crash its host.**
  - `identify` sends the hash it was given and never a raw email: a value containing `@` is refused with a clear
    log line and nothing is sent. The server never matches on an email, so sending one is both useless and a
    liability.
  - `installId` returns null before `initialise` and the id afterwards, so a privacy screen can show it
  - every public method is safe to call from any thread, and none of them does network work on the calling thread
- [ ] **Implement.** All network work on a single background executor. `installId` reads through `InstallId.peek`.
  KDoc on `installId` must say why it exists: a person's access and erasure rights depend on being able to find
  their own identifier, and an app has no browser settings to read it from.
- [ ] Commit.

---

### Task 7: one end to end test against a stub server

**Files:** `trace/src/test/kotlin/io/usetrace/sdk/FirstOpenEndToEndTest.kt`, `README.md` (modify)

**Produces:** proof the five pieces work together, and a README a customer can follow.

- [ ] **Write the test.** A loopback `HttpServer` standing in for the API. Drive the real `Trace` object through
  the sequence a real app follows: initialise with consent unknown, a conversion recorded, then consent granted.
  Assert, on the server side:
  - the consent call arrives first, carrying the install id
  - then the `FIRST_OPEN`, with `source_type` `app`, `platform` `android`, `store` `play`, the app version, the raw
    referrer and the same install id
  - then the conversion, with the same install id
  - all three carry the api key and a non-empty `User-Agent`
  - nothing else is sent
- [ ] **Write the README** customer facing section: add the dependency, initialise in `Application.onCreate`, wire
  `setConsent` to the app's own consent interface, send a conversion, and where to find the install id for a
  privacy screen. Say plainly that the SDK collects no advertising identifier and does no device fingerprinting.
- [ ] Commit.

---

## Deliberately out of scope

- iOS, which is its own slice and can never be per person.
- The identity bridge. `identify(hash)` sends the hash; nothing joins it to a web journey until that slice.
- Maven publication. The artefact builds; releasing it is a separate job with its own signing decisions.
- Screen views, sessions, automatic events, funnels, crash reporting. Permanently out of scope, not deferred.

## Known limits, to be written into the README, not hidden

- A reinstall mints a fresh install id and counts as a new install.
- An install referrer the Play Store will not supply makes the install direct, not an error.
- The install id is held in `getNoBackupFilesDir()`, so it is never backed up, transferred or restored, and a host
  app's own backup configuration cannot change that.

## Decision, 6 October 2026: `identify` removed before first merge

`identify(hashedIdentifier)` was built under Task 6 and removed before the SDK was first merged. The plan above is
left as it was written; this records what changed.

It sent a hashed account identifier, and the README example hashed an email. On 11 September 2026 Trace decided not
to match on a hashed email or customer id (`docs/CONSENT_REMEDIATION.md` item 11 in the `trace` repository): ingest
stopped reading them, the server SDKs stopped sending them, and the customer privacy notice says Trace uses
pseudonymised identifiers rather than names or emails. A hashed email is still personal data under UK and EU GDPR,
so shipping this method would have contradicted what customers have already told their visitors.

It was removed outright rather than left as a method that does nothing, because a customer calling it would
reasonably assume it works. The SDK now does four things: persist an install id, send `FIRST_OPEN` once with the
referrer, send conversions, and hold events until consent is known. A hashed identifier comes back only as its own
reviewed slice, with the privacy notice changed to say so in the same release. The logger still redacts anything
shaped like an email address, because an email must never reach a log whatever produced it.
