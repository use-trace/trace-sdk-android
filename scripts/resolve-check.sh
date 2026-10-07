#!/usr/bin/env bash
# Builds a throwaway Android app that depends on io.usetrace:trace-sdk-android:<version> the way a customer's app does,
# so a release is proved to resolve, with its dependencies, and to link. Used by release.yml.
#
#   scripts/resolve-check.sh <version>          from Maven Central, retrying while Central syncs (up to an hour)
#   scripts/resolve-check.sh <version> --local  from Maven Local, once (the dry run, after publishToMavenLocal)
set -euo pipefail
version=$1
mode=${2:-central}
root=$(cd "$(dirname "$0")/.." && pwd)
agp=$(sed -n 's/^agp = "\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")
dir=$(mktemp -d)

if [ "$mode" = --local ]; then
  repos='mavenLocal { content { includeGroup("io.usetrace") } }
        mavenCentral()'
  tries=1
else
  # No Maven Local here, so only Central can answer for io.usetrace.
  repos='mavenCentral()'
  tries=${RESOLVE_TRIES:-30}
fi

cat > "$dir/settings.gradle.kts" <<KTS
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        $repos
    }
}
rootProject.name = "resolve-check"
KTS
cat > "$dir/build.gradle.kts" <<KTS
plugins {
    id("com.android.application") version "$agp"
}
android {
    namespace = "io.usetrace.resolvecheck"
    compileSdk = 35
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("io.usetrace:trace-sdk-android:$version")
}
KTS
mkdir -p "$dir/src/main/java/io/usetrace/resolvecheck"
echo '<manifest xmlns:android="http://schemas.android.com/apk/res/android" />' > "$dir/src/main/AndroidManifest.xml"
# Naming the SDK's entry point makes the build fail unless its classes are in the AAR that resolved.
echo 'package io.usetrace.resolvecheck; class Check { Object trace = io.usetrace.sdk.Trace.class; }' \
  > "$dir/src/main/java/io/usetrace/resolvecheck/Check.java"

for try in $(seq 1 "$tries"); do
  # --refresh-dependencies, or Gradle remembers a version Central did not have yet for 24 hours.
  if "$root/gradlew" -p "$dir" assembleDebug --no-daemon --refresh-dependencies --quiet > "$dir/out.log" 2>&1; then
    echo "io.usetrace:trace-sdk-android:$version resolved and built into an app ($mode, try $try)."
    exit 0
  fi
  if [ "$try" -lt "$tries" ]; then
    echo "Not resolvable yet (try $try of $tries); Central can take a while to sync. Trying again in two minutes."
    sleep 120
  fi
done
cat "$dir/out.log" >&2
echo "::error::io.usetrace:trace-sdk-android:$version did not resolve and build ($mode) after $tries tries."
exit 1
