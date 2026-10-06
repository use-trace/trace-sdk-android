#!/usr/bin/env bash
# The privacy rules in CLAUDE.md and the README, as a check rather than a promise. Run from the repository root.
#
# Each rule is a pattern that must not appear in code. Comment lines are skipped, so a KDoc saying "no
# AdvertisingIdClient" is not a violation. A rule that has to change is changed here, in a reviewed pull request.
set -uo pipefail

failed=0
main=trace/src/main

# forbid <reason> <extended regex> <pathspec>...
forbid() {
  local reason=$1 pattern=$2
  shift 2
  local hits
  hits=$(git grep -nIE -e "$pattern" -- "$@" | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|\*|/\*)')
  if [ -n "$hits" ]; then
    echo "::error::$reason"
    echo "$hits"
    failed=1
  fi
}

forbid "No advertising identifier: no GAID, no AdvertisingIdClient, no play-services-ads-identifier." \
  'AdvertisingIdClient|ads-identifier|play-services-ads' \
  "$main" '*.gradle.kts' 'gradle/libs.versions.toml'

forbid "No device identifier and no fingerprint: the install id is a random value with nothing of the device in it." \
  'ANDROID_ID|Build\.SERIAL|getSerial[[:space:]]*\(|getImei|getMeid|getDeviceId|getSubscriberId|getSimSerialNumber|getLine1Number|getMacAddress|getHardwareAddress|Build\.FINGERPRINT' \
  "$main"

# Decided 11 September 2026. Hashing is how an email becomes a hashed identifier, so the means to do it are out too.
forbid "No identify, and no hashed email or customer id." \
  'fun[[:space:]]+identify|hashedEmail|emailHash|MessageDigest' \
  "$main"

# Every line goes through TraceLog, which redacts anything identity shaped at the sink. A log call anywhere else
# skips the redaction, so a visitor identity could reach logcat.
forbid "Log only through TraceLog, which redacts identities. No android.util.Log, println or printStackTrace elsewhere." \
  'android\.util\.Log|(^|[^A-Za-z_.])(println|print)[[:space:]]*\(|System\.(out|err)|printStackTrace' \
  "$main" ":!$main/kotlin/io/usetrace/sdk/TraceLog.kt"

# The README: "The library declares no permissions and no components of its own".
forbid "The library declares no permissions and no components." \
  '<uses-permission|<permission|<activity|<service|<receiver|<provider' \
  "$main/AndroidManifest.xml"

# The README: one transitive dependency, the install referrer client, and nothing else. The Kotlin standard library
# comes with the language, and org.jetbrains:annotations with the standard library.
allowed='^(com\.android\.installreferrer:installreferrer|org\.jetbrains\.kotlin:kotlin-stdlib(-jdk7|-jdk8)?|org\.jetbrains:annotations)$'
modules=$(./gradlew -q --no-daemon :trace:dependencies --configuration releaseRuntimeClasspath \
  | grep -oE '[A-Za-z0-9._-]+:[A-Za-z0-9._-]+:[^ ]+' | cut -d: -f1,2 | sort -u)
if ! grep -qx 'com.android.installreferrer:installreferrer' <<<"$modules"; then
  # Nothing parsed means the check proved nothing, which is not the same as passing.
  echo "::error::Could not read the release runtime classpath. Gradle said:"
  echo "$modules"
  failed=1
elif extra=$(grep -vE "$allowed" <<<"$modules"); then
  echo "::error::The release runtime classpath holds more than the install referrer client and the Kotlin standard library."
  echo "$extra"
  failed=1
fi

if [ "$failed" -ne 0 ]; then
  echo "A privacy rule from CLAUDE.md or the README is broken. See above." >&2
  exit 1
fi
echo "Privacy rules hold."
