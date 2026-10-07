# Trace Android SDK

Install attribution and conversions for Android apps, for [Trace](https://usetrace.io).

The SDK is deliberately small. It does four things and nothing else:

1. Persists an install scoped anonymous key.
2. Sends `FIRST_OPEN` once, with the Play Store install referrer.
3. Sends conversions.
4. Holds events until the consent state is known, then flushes or discards them.

It does not do screen views, session tracking, automatically collected events, funnels or crash reporting. Every
capability it gains has to be maintained across Android releases forever, so adding one is a product decision.

**It collects no advertising identifier and does no device fingerprinting.** No Google Advertising ID, no
`AdvertisingIdClient`, no hardware identifier, no signature derived from the device. The install id is a random
value with nothing of the device in it, so two installs of your app on one phone are two unrelated installs as far
as Trace is concerned. That sentence is published in the Trace privacy notice, so it is a promise the SDK keeps
rather than a position it takes.

**It takes and sends no hashed email and no other personal identifier.** There is no method for passing an email
address, a hash of one, a customer id or an account id, and the SDK never collects one itself. Trace does not match
on hashed emails or customer ids, and its privacy notice says it uses pseudonymised identifiers rather than names or
emails, so that too is a promise the SDK keeps.

## What you need

- `minSdk 21` or higher.
- `android.permission.INTERNET` in your app. The library declares no permissions and no components of its own, so
  it inherits yours, and nothing can be sent without that one.
- Your site's api key, from the Trace dashboard under the site's settings. It reads `trace_` followed by a long
  hexadecimal string.

There is nothing to carry across for backup configuration. The SDK's files live in
`Context.getNoBackupFilesDir()`, which Android excludes from Auto Backup and from a device transfer whatever your
app's own backup rules say, so you do not need a `data_extraction_rules.xml` entry and adding one would do nothing.

## Adding the dependency

The artefact is `io.usetrace:trace-sdk-android`. It is **not published to Maven Central yet**: publication is a
separate piece of work with its own signing decisions. Until it is published, build the AAR from this repository
with `./gradlew :trace:assembleRelease` and add it to your app as a local file. Once it is published, the
declaration is:

```kotlin
dependencies {
    implementation("io.usetrace:trace-sdk-android:0.1.0")
}
```

It brings one transitive dependency, `com.android.installreferrer:installreferrer`, because nothing else can read
the Play Store install referrer. No HTTP client, no JSON library, no coroutines.

## Initialising

Call `Trace.initialise` in `Application.onCreate`, with the application context.

```kotlin
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        Trace.initialise(this, TraceConfig(apiKey = "trace_your_site_key"))

        // On every launch, from whatever your app stored when the person answered your consent banner.
        Trace.setConsent(
            analytics = consentStore.analyticsAllowed,
            marketing = consentStore.marketingAllowed,
        )
    }
}
```

`initialise` returns at once. Reading the referrer and recording the first open happen afterwards on one background
thread the SDK owns, so no method here does network work on the thread that called it
and none of them throws into your app.

Call it in `onCreate` rather than later. The Play Store only offers the install referrer for a limited window after
an install, so a later call has a worse chance of reading it. Calling `initialise` a second time does nothing and
says so in logcat: the install happened once.

`TraceConfig` takes three things and has nothing else to configure:

| Parameter | Default | What it is |
| --- | --- | --- |
| `apiKey` | required | The site's api key, sent as `x-trace-api-key`. |
| `apiUrl` | `https://app.usetrace.io/api-proxy` | Where to send. Change it only for a self hosted deployment. |
| `debugLogging` | `false` | Whether the SDK writes what it is doing to logcat under the tag `Trace`. |

`debugLogging` never writes an install id, a referrer or an event's contents, whatever it is set to: those are
stripped where the line is written. It is safe to turn on in a release build, though there is little reason to.

## Consent

**Nothing is sent and nothing is stored until you call `setConsent`.** Until then every event, including the first
open, is held in memory and sent to nobody, and no install id exists. An app that never calls `setConsent` sends
nothing and writes nothing to the device, which is the correct behaviour and not a fault to report.

```kotlin
// Your consent interface, whatever it is, on the answer rather than on the launch.
fun onBannerAnswered(analytics: Boolean, marketing: Boolean) {
    consentStore.save(analytics, marketing)
    Trace.setConsent(analytics = analytics, marketing = marketing)
}
```

- `analytics` is the answer that decides whether anything is sent.
- `marketing` is passed to Trace for the consent record and does not decide whether an event is sent, so
  `analytics = false` discards what was held whatever `marketing` says.

Granting writes the install id, sends the consent record and then everything held, oldest first. Refusing throws
away everything held and writes nothing. If an earlier grant left an install id, refusing also sends the consent
record, which withdraws that grant. With no install id, a refusal in the install's first day is reported with no
identifier, so that Trace can count it (see "Counting each answer once" below). What the
grant already sent stays with Trace until it is erased (see "What to declare to the stores"). Someone who refuses and later agrees is tracked from the moment they agreed.

**Call `setConsent` on every launch, from the answer your app stored.** The SDK does not keep the answer: the
consent record belongs to your app, which has to display it, change it and withdraw it, and two copies of it would
eventually disagree. An app that calls it once after its banner and never again spends every later launch holding
events it should be sending.

## What is stored on the device, and when

Nothing before the person grants consent. Decided on 6 October 2026, before the first release.

| When | What the SDK writes, in `Context.getNoBackupFilesDir()` |
| --- | --- |
| Before an answer | Nothing. The first open and any conversions are held in memory only. |
| On a grant | `install_id`, the install id, if there is not one yet. `first_open_sent`, once the first open has been sent. |
| On a refusal | Nothing. |

### Counting each answer once

Trace works out the share of people who said yes, per platform, from each install's first answer. So every consent
call says whether it is that first answer, and a refusal from an install with no id is reported with nothing that
identifies anyone:

| Consent call | Sent | Fields |
| --- | --- | --- |
| A grant | On every launch | `consent_analytics` true, `consent_marketing`, `anon_user_key`, `timestamp`, `platform` `android`, `first_answer` |
| A refusal after an earlier grant | On every launch, and it withdraws that grant | as a grant, with `consent_analytics` false |
| A refusal with no install id | At most once a process, and only in the install's first 24 hours | `consent_analytics` false, `consent_marketing`, `timestamp`, `platform` `android`, `first_answer` true. No `anon_user_key` and no other identifier. |

`first_answer` is true on the grant that mints the install id and on the refusal above, and false on everything
else. Nothing is written to remember any of it: the install's age comes from the Play Store's own record of when the
app was first installed (`PackageInfo.firstInstallTime`), which the SDK reads and does not send. So the count of
refusals is close, not exact: a refusal is counted again if the app's process restarts in its first day, and not at
all if the person first answers after it.

The SDK does not store the consent answer. Your app does, and passes it to `setConsent` on every launch.

## Sending a conversion

```kotlin
Trace.conversion("purchase", value = 29.99)

Trace.conversion(
    name = "subscription",
    value = 9.99,
    metadata = mapOf("plan" to "plus", "trial" to "false"),
)
```

`name` is your own name for the outcome and is what a conversion rule in the Trace dashboard matches on. The one
name with a meaning of its own is `purchase`, in any case, which is sent as Trace's `PURCHASE` type so that it
reports as revenue. Everything else is a custom conversion.

`value` is the amount, in the currency your site is configured with in Trace. See the note on `currency` under
known limits before sending one.

`metadata` is anything else worth keeping with the conversion. Trace keeps keys of letters, digits and
underscores, up to fifty of them, and drops the rest, so the SDK logs which of yours it will drop rather than
letting them go missing quietly. Do not put anything identifying in it.

A conversion recorded before `setConsent` is held with the rest. A conversion recorded before `initialise` does
nothing and logs a line saying so, rather than throwing.

## The install id, for a privacy screen

```kotlin
val id: String? = Trace.installId
```

This is public because a person's rights depend on it. Someone asking what Trace holds about them, or asking for it
to be deleted, has to be able to find their own identifier first, and an app user has no browser settings to read
it from. So show it on your own privacy screen, with the Trace privacy contact, and that is the only place they can
get it.

It is `null` until the person has granted consent, because no identifier exists before then, which is the truthful
answer rather than minting an identifier in order to display one. Reading it creates nothing and sends nothing. It
is a visitor identity: show it to the person it belongs to and do not log it or send it anywhere else.

## What to declare to the stores

This is what the SDK itself collects, for whoever fills in the Data safety form in the Play Console. **Your own app,
and every other SDK in it, may collect more.** Declare that as well: the answers below are the part this SDK adds,
not the whole of your app's form. They follow Google's definitions as read on 6 October 2026, in
[Provide information for Google Play's Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469).

### What leaves the device

Before a grant, only a refusal in the install's first day, with no identifier: that the person said no, their
marketing answer, the time, and that this is Android (`ConsentGate.kt`, `Transport.kt`). After
`setConsent(analytics = true)`:

| Sent | Where it comes from |
| --- | --- |
| The install id, a random value minted on the grant | `InstallId.kt` |
| What happened: a first open, a purchase or another conversion, and when | `Event.kt`, `Trace.kt` |
| The Play Store install referrer, with the first open only: the campaign text the Play Store recorded for the install | `InstallReferrer.kt`, `Trace.kt` |
| The consent answers, with the install id, that this is Android and whether this is the install's first answer, and the consent state of each event | `Transport.kt`, `ConsentGate.kt` |
| Your app's version, and that this is an Android app from Google Play | `Trace.kt`, `Event.kt` |
| A conversion's name, value, currency and metadata, as your app passes them | `Trace.kt` |
| The SDK's version and the Android version, in the user agent | `Transport.kt` |

The install referrer is read from the Play Store on the device at the first launch and held in memory with the
first open; it leaves the device only with that first open, after a grant. It can carry an ad click id, which Trace
removes before it stores the install.

Like any request, it reaches Trace from the device's IP address. Trace uses that to apply rate limits and the
site's excluded IP list, and does not store it.

The SDK sends no advertising identifier, no Android ID or other hardware identifier, no name, email address or
account id, no location, no contacts, no device model and no list of installed apps. It declares no permissions.

### Answers in the Data safety form

**Does your app collect or share any of the required user data types?** Yes.

**Is all of the user data collected by your app encrypted in transit?** Yes, as long as `apiUrl` is an `https`
address, which the default `https://app.usetrace.io/api-proxy` is (`TraceConfig.kt`). A self hosted `http` address would not
be, and Android blocks one by default for apps targeting Android 9 or later.

**Do you provide a way for users to request that their data is deleted?** Trace deletes everything it holds for
one install when you ask it to: in Trace, the site's settings, Erase a visitor, with the install id as the visitor
id. The person finds their install id on your privacy screen (`Trace.installId`, see above). So answer Yes if you
accept deletion requests and pass them on. A refusal after a grant sends the consent record to Trace, which stops
anything more being sent, but does not by itself delete what was already sent.

| Data type | What the SDK sends | Collected | Shared | Purposes |
| --- | --- | --- | --- | --- |
| Device or other IDs | The install id | Yes | No | Analytics, Advertising or marketing |
| App activity: App interactions | The first open with its install referrer, each conversion with its name and metadata, and a refusal reported with no identifier | Yes | No | Analytics, Advertising or marketing |
| Financial info: Purchase history | A conversion's value and currency, and a conversion named `purchase` | Yes | No | Analytics, Advertising or marketing |

Leave out Purchase history only if your app never passes a value and never records `purchase`.

**Not shared.** Trace processes the data on your behalf, as your service provider, and Google does not count a
transfer to a service provider as sharing. Trace does not sell it or pass it to anyone for their own use.

**Not processed ephemerally.** Trace keeps it, to report on it.

**Required or optional.** Google lets you say optional only if every user, in every region, can choose. The SDK
sends nothing identifying until your app calls `setConsent(analytics = true)`, so if your app asks every user and passes their
answer, answer optional. If your app grants on a user's behalf anywhere, answer required.

**Purposes.** Analytics, because Trace reports how people came to install and convert. Advertising or marketing,
because Google's definition of it includes measuring ad performance, and attributing an install to the campaign that
produced it is that. Trace does not use the data to show ads, target them or send marketing.

Anything your app puts in a conversion's metadata is collected too. Put nothing identifying in it; if you do, it has
to be declared as well.

## Known limits

These are real. They are here rather than discovered.

- **A reinstall counts as a new install.** The install id is held in `Context.getNoBackupFilesDir()`, which Android
  never backs up, transfers or restores, so a reinstall mints a fresh id and reports a fresh install. This is on
  purpose twice over: an id that came back would count a reinstall as the same install, and it would quietly join
  somebody back to a journey history they may reasonably have treated as finished when they removed the app. Over
  counting reinstalls is the better failure.
- **The backup exclusion is proved by a file assertion, not on a device.** The suite asserts the id and the first
  open flag are written under `getNoBackupFilesDir()`. Nothing here proves the platform honours that exclusion; that
  needs a device test driving `bmgr`, which this slice does not have.
- **A referrer the Play Store will not give is a direct install, not an error.** `FEATURE_NOT_SUPPORTED`, a Play
  Services that is absent or out of date, a sideloaded build, or a client that never answers at all: in every case
  the install is still reported, with no referrer, and lands in direct.
- **An app killed before the person answers loses what was held.** Nothing is stored before consent, so the held
  first open and conversions are in memory only. The next launch finds no first open flag and records a first open
  again, with that launch's time, so the install is still reported once the person agrees.
- **A device offline at the moment consent is granted loses what was held, including the first open.** The
  transport tries three times and then gives up, and nothing that was flushed is kept. Keeping it past the answer
  would mean some later launch sends it again, and a duplicated install is harder to see than a missing one. There
  is no retry across launches.
- **`setConsent` has to be called on every launch**, because the SDK does not persist the answer. See Consent
  above.
- **`currency` on a conversion is recorded, not applied.** Trace values conversions in the currency the site is
  configured with, and the ingest route has no per conversion currency field, so what you pass is carried in
  `metadata` under `currency` and nothing converts it. One case is sharper than that: for a mirrored tag event such
  as `ga4:purchase`, a `metadata.currency` that differs from the site's configured currency makes the server drop
  the value rather than convert it. So sending a currency that is not your site's can cost you a conversion value.
  If your prices are in one currency, set that currency on the site in Trace and leave this alone.

## Building this repository

```
./gradlew :trace:assembleRelease
./gradlew :trace:testDebugUnitTest
```

JDK 17, and an Android SDK with the platform for `compileSdk 35`. The unit tests need no device and no network: the
ones that assert what crosses the wire run against an HTTP server on a loopback port, and the Play referrer client
is behind an interface the tests drive themselves.

See `CLAUDE.md` for the rules this repository works under, and `docs/` for the plan the slices follow.
