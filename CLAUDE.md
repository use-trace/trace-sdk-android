# Trace Android SDK

The Android SDK for Trace (usetrace.io). The server side it talks to lives in the `use-trace/trace` monorepo, in
`apps/api/src/tim/`, and the design this SDK implements is `docs/plans/APP_TRACKING.md` there.

**Read `.claude/rules/app-tracking.md` in the `trace` repository before writing code here.** It holds the decisions
this SDK has to obey, and it is kept up to date as the server side changes. What follows is the subset that binds
this repository, not a replacement for it.

## Pull requests are reviewed by the shared review loop

Every pull request is reviewed by use-trace/trace's review loop after CI passes: a reviewer reads the change against this
file and the rules, and a fixer can push fixes. The ruleset requires `review-gate`, so nothing merges until the loop has
approved it. Add `hold` to keep a pull request back.

## Working rules

- British English in code comments, documentation and commit messages. No em dashes or en dashes anywhere. Use
  commas, colons or full stops.
- Direct, plain copy. No marketing language.
- A bug fix ships with a failing test first.
- Never push to `main`. Branch, commit, open a pull request. A green pull request merges itself
  (`.github/workflows/auto-merge.yml`); the `hold` label keeps it back.

## The boundary, which is not negotiable

The SDK does four things: persist an install scoped anonymous key, send `FIRST_OPEN` once with the install referrer,
send conversions, and hold events until the consent state is known.

Hashed identifiers are out until a reviewed slice brings them back: Trace decided on 11 September 2026 not to match
on a hashed email or customer id (`docs/CONSENT_REMEDIATION.md` item 11 in the `trace` repository), a hashed email is
still personal data under UK and EU GDPR, and the customer privacy notice promises pseudonymised identifiers rather
than names or emails.

It does **not** do screen views, session tracking, automatically collected events, funnels or crash reporting.

## Things the server cannot do for us

These are server side facts, learned the hard way while building the ingest path. Each one is a silent failure if
the SDK gets it wrong, which is why they are here rather than in a comment somewhere.

- **Send the install id with the consent call, not only with events.** On a consent gated site the first open is
  buffered and replayed after consent, and on replay the key comes from the consent service. An install id missing
  from that call means a server minted key is recorded as though it were the app's own.
- **Send a non-empty user agent.** The server's bot guard treats an empty one as a bot and silently ignores the
  event, with a 200. Any ordinary client is fine: okhttp, Dalvik and CFNetwork all pass.
- **Send `source_type: "app"` and a `platform`.** The server infers an app from a `FIRST_OPEN` as a backstop, but do
  not rely on the backstop.
- **A 2xx is not proof of delivery.** The dashboard answers a POST with a web page and a 200, which lost every
  event sent to the old default address on 7 October 2026. A send is delivered only when the body is the API's own
  answer: `"accepted": true` from `/v1/event` (202), a boolean `cookie_set` from `/v1/consent` (201). A 2xx without
  it is a wrong address: not delivered, not retried, and logged once per launch even with logging off. A 401 or 403
  (a wrong or revoked api key) is the same kind of wrong configuration. A first open sent with a wrong configuration
  is not marked sent, so the next launch sends it again and the install is reported once the configuration is fixed. Any other failure still marks it sent: the server may have taken it.
- **An install with no install id is refused, with a 400.** That is deliberate: the server will not invent an
  identity from a device signature, because that is fingerprinting. Always send the key.
- **Expose the install id to the host app.** A person's access and erasure rights depend on them being able to find
  their own identifier, and the web route for that is browser settings, which an app user does not have. The
  customer's app has to be able to show it.

## Privacy, which is the product

- No advertising identifiers. No IDFA, no GAID, no device fingerprint, ever, however convenient.
- A visitor identity must never appear in a log line or an error report.
- Hold events until consent is known. Do not send and apologise later.
- **Nothing is written to the device before consent** (decided 6 October 2026, before the first release). Before an
  answer the first open and every conversion are held in memory only and no install id exists. A grant writes
  `install_id` and, once the first open has been sent, `first_open_sent`. A refusal writes nothing and sends no
  identifier: with no install id it is reported anonymously, with `first_answer` true, at most once a process in the
  install's first day, so the server can count it (README, "Counting each answer once"). The consent
  answer is not stored at all: the host app keeps it and passes it on every launch. A process killed before an
  answer loses what was held, and the next launch records a first open again; that cost was accepted.

## Repository status

The Gradle project exists and builds: one library module, `trace`. CI runs the house copy rules, a build job, a
unit test job (every Robolectric test runs at minSdk 21 and at compileSdk 35), `lint`, `privacy` and `api`. The
SDK is written: `Trace` and `TraceConfig` are its whole public surface, and every other class it declares is
`internal` (`BuildConfig` is public because the Android Gradle plugin generates it so). The plan in `docs/` records
which task built each file.

`.github/workflows/newest-toolchain.yml` builds and tests every Monday on the newest stable Android Gradle plugin,
Kotlin, Gradle, compileSdk and LTS JDK, applied to a throwaway checkout. A failure opens one `incident` issue titled
"Newest toolchain run is failing" and alerts, through a copy of the monorepo's shared recorder (a public repository
cannot call a workflow in a private one); the next pass closes it. It means the pinned versions are about to stop
working for a customer on the newest tools, not that `main` is broken. `main` is on Android Gradle plugin 9 with its built in Kotlin
turned off (`android.builtInKotlin=false` and `android.newDsl=false` in `gradle.properties`), because the binary
compatibility validator behind `api` only attaches to the Kotlin Android plugin. Plugin 10 removes those switches:
moving to it means moving `api` to the Kotlin plugin's own ABI validation and deleting the Kotlin Android plugin.

`privacy` is `scripts/check-privacy.sh`: the rules above, as a check. The README's "What to declare to the stores"
is what customers put in the Play Data safety form, so sending a new field changes it in the same pull request;
`every field that leaves the device is one the store declarations name` in `TransportTest` fails until it does. `api` compares the public API with
`trace/api/trace.api`; after a deliberate change to anything public, run `./gradlew :trace:apiDump` and commit the
file with the change.

## Releasing

The version is `version` in `trace/build.gradle.kts` and nowhere else, apart from the README's dependency line, which
`scripts/version.sh` holds to it. A version on Maven Central can never be removed or replaced and customers pin it,
so a release needs a person: a pull request that changes the version waits in the `version` job's "Waiting for
release-approved" step until it carries `release-approved` (`.github/workflows/version.yml`).

`.github/workflows/release.yml` does the rest on the push to `main`. When the version has no GitHub release and the
merged pull request carries `release-approved`, it tags `v<version>`, publishes `io.usetrace:trace-sdk-android` to
Maven Central through the Central Portal (`com.vanniktech.maven.publish`: the release AAR with sources and javadoc
jars, signed, POM from the `POM_` lines in `gradle.properties`), creates the GitHub release with the AAR and the pull
request's title and body as its notes, then builds a throwaway app against the version from Central
(`scripts/resolve-check.sh`), retrying for up to an hour while Central syncs. Without the four secrets
(`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`, `SIGNING_KEY_PASSWORD`) or a licence, or when the
Central Portal refuses the token or the key does not unlock or is on no public keyserver, it stops red before tagging. A merged pull request without the label publishes nothing and says so. On every pull request the
same workflow is a dry run: the real token and key checked when they are set, the build signed with a throwaway
ed25519 key, published to Maven Local, each signature checked and resolved from there.

A bad release is fixed by a new patch version, never by moving a tag.
