# Trace Android SDK

The Android SDK for Trace (usetrace.io). The server side it talks to lives in the `use-trace/trace` monorepo, in
`apps/api/src/tim/`, and the design this SDK implements is `docs/plans/APP_TRACKING.md` there.

**Read `.claude/rules/app-tracking.md` in the `trace` repository before writing code here.** It holds the decisions
this SDK has to obey, and it is kept up to date as the server side changes. What follows is the subset that binds
this repository, not a replacement for it.

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
- **An install with no install id is refused, with a 400.** That is deliberate: the server will not invent an
  identity from a device signature, because that is fingerprinting. Always send the key.
- **Expose the install id to the host app.** A person's access and erasure rights depend on them being able to find
  their own identifier, and the web route for that is browser settings, which an app user does not have. The
  customer's app has to be able to show it.

## Privacy, which is the product

- No advertising identifiers. No IDFA, no GAID, no device fingerprint, ever, however convenient.
- A visitor identity must never appear in a log line or an error report.
- Hold events until consent is known. Do not send and apologise later.

## Repository status

The Gradle project exists and builds: one library module, `trace`. CI runs the house copy rules, a build job, a
unit test job (every Robolectric test runs at minSdk 21 and at compileSdk 35), `lint`, `privacy` and `api`. The
SDK is written: `Trace` and `TraceConfig` are its whole public surface, and every other class it declares is
`internal` (`BuildConfig` is public because the Android Gradle plugin generates it so). The plan in `docs/` records
which task built each file.

`privacy` is `scripts/check-privacy.sh`: the rules above, as a check. `api` compares the public API with
`trace/api/trace.api`; after a deliberate change to anything public, run `./gradlew :trace:apiDump` and commit the
file with the change.
