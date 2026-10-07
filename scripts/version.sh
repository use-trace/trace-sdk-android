#!/usr/bin/env bash
# Prints the SDK's version, from trace/build.gradle.kts, the one place it is set. Fails when it is not a plain
# x.y.z or when the README's dependency line names a different one. Run from anywhere; used by version.yml and
# release.yml.
set -euo pipefail
cd "$(dirname "$0")/.."

version=$(sed -n 's/^version = "\(.*\)"$/\1/p' trace/build.gradle.kts)
if ! [[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "trace/build.gradle.kts has no version = \"x.y.z\" line (found \"$version\")." >&2
  exit 1
fi
readme=$(sed -n 's/.*io\.usetrace:trace-sdk-android:\([^"]*\)".*/\1/p' README.md)
if [ "$readme" != "$version" ]; then
  echo "README.md declares io.usetrace:trace-sdk-android:$readme but trace/build.gradle.kts says $version. Change both." >&2
  exit 1
fi
echo "$version"
