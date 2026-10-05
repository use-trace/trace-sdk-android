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

**`anon_user_key` on the consent call is not optional.** On a consent gated site the server buffers the first open
and replays it after consent, taking the key from the consent call. Omit it and the server mints its own key and
labels it as the app's install id, which is a false provenance. This is written in `.claude/rules/app-tracking.md`
in the main repository and it is the single easiest thing to get wrong here.

**The request must carry a non-empty `User-Agent`.** The server's bot guard treats an empty one as a bot and
silently ignores the event behind a 200. Send `TraceSdkAndroid/<sdk version> (Android <release>)`.

## Two decisions already made, do not reopen

- **An install id does not survive an uninstall.** It is excluded from Android Auto Backup. Auto Backup is opt out,
  so a restored key can land on a resold or factory reset device and join a previous owner's installs to a new
  person. A reinstall therefore mints a fresh id and counts as a new install. Over counting reinstalls is the
  better failure.
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
  src/main/res/xml/trace_backup_rules.xml
  src/main/kotlin/io/usetrace/sdk/
    Trace.kt              public API, the only file a customer reads
    TraceConfig.kt        api key, api url, debug logging flag
    InstallId.kt          persist, expose, exclude from backup
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
`trace/src/main/res/xml/trace_backup_rules.xml`, `trace/src/main/AndroidManifest.xml` (modify),
`trace/src/test/kotlin/io/usetrace/sdk/InstallIdTest.kt`

**Produces:** `InstallId.get(context: Context): String` and `InstallId.peek(context: Context): String?`

- [ ] **Write the failing tests** (Robolectric, so a real `SharedPreferences`):
  - `get` returns the same value on a second call, because an install id that changes is not an install id.
  - `get` returns a value matching `^auk_app_[0-9a-f]{32}$`. The `auk_` prefix is what the server's other keys use;
    `app_` says where it came from.
  - `peek` returns null before any `get`, and the id afterwards. The host app needs to read the id without
    creating one, so a privacy screen can say "no id yet" truthfully.
  - Two different `Context`s with the same package share the id.
- [ ] **Implement.** A dedicated `SharedPreferences` file named `io.usetrace.sdk.installid`, key `install_id`,
  value `"auk_app_" + UUID.randomUUID().toString().replace("-", "")`. Generate with `java.util.UUID`, never from
  anything about the device.
- [ ] **Exclude it from backup.** `trace/src/main/res/xml/trace_backup_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  The install id must not survive an uninstall. Auto Backup is opt out, so a restored id can land on a resold or
  factory reset device and join a previous owner's installs to a new person. Over counting a reinstall is the
  better failure, so this file is deliberate, not an oversight.
-->
<full-backup-content>
    <exclude domain="sharedpref" path="io.usetrace.sdk.installid.xml" />
</full-backup-content>
```

  And a `data_extraction_rules.xml` equivalent for Android 12 and later, excluding the same file from both
  `cloud-backup` and `device-transfer`. Reference both from the library manifest so a host app inherits them by
  manifest merge, and KDoc that a host app overriding `android:fullBackupContent` must carry the exclusion across.
- [ ] **A test that the exclusion exists**, asserting both XML files contain that path. It is not testable at
  runtime, so pin it as a file assertion rather than leave it unguarded. Name it after the behaviour.
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
  - a 200 returns true; a 400 returns false and does not retry, because a rejected payload will be rejected again
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
- [ ] **Implement.** Persist the queue as JSON lines in the SDK's own file in `context.filesDir`. Exclude that file
  from backup too, by the same reasoning as the install id.
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
- A host app that overrides `android:fullBackupContent` must carry the backup exclusions across, or the install id
  can survive an uninstall and reach a different person.
