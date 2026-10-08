# Changelog

Each release is also a GitHub release, with its pull request's description as the notes.

## 0.2.0, not yet released

- On a consent gated site (UK and EU, or no region set) the Play Store install referrer is read only after the
  person says yes, just before the first open is sent. Before an answer the first open waits in memory without it.
  The Play Store keeps the referrer for 90 days, so a later yes still gets it.
- On a US or Other site it is still read at the first launch, unless the answer the app passes at launch is a no.
  A refusal never reads it, in either region.
- On the first launch that has not reported the install, the SDK asks Trace whether the site is consent gated
  (`GET /v1/snippet-config`), as the website tag and the iOS SDK do, and keeps the answer only when it is "not gated"
  (`site_not_consent_gated`, an empty file). No answer reads as gated.
- Decided by Dom on 8 October 2026 after legal advice (PECR regulation 6, ePrivacy article 5(3)).

## 0.1.0, 6 October 2026

- First release.
