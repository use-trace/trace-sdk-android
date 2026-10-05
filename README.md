# Trace Android SDK

Install attribution and conversions for Android apps, for [Trace](https://usetrace.io).

The SDK is deliberately small. It does five things and nothing else:

1. Persists an install scoped anonymous key.
2. Sends `FIRST_OPEN` once, with the Play Store install referrer.
3. Sends conversions.
4. `identify(hash)`, for a customer passing their own hashed account identifier.
5. Holds events until the consent state is known, then flushes or discards them.

It does not do screen views, session tracking, automatically collected events, funnels or crash reporting. Every
capability it gains has to be maintained across Android releases forever, so adding one is a product decision.

Status: empty. The API side shipped first, in the `trace` repository. See `CLAUDE.md` before writing any code here.
