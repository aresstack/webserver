#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export GRADLE_USER_HOME="$ROOT_DIR/.chatgpt/gradle-home"

if [[ ! -d "$GRADLE_USER_HOME" ]]; then
    echo "Missing offline Gradle cache: $GRADLE_USER_HOME" >&2
    exit 1
fi

if [[ ! -f "$ROOT_DIR/runtime/caddy/bin/caddy" ]]; then
    echo "Missing cached Linux Caddy test binary. Extract the complete offline cache ZIP." >&2
    exit 1
fi

cd "$ROOT_DIR"
if [[ $# -eq 0 ]]; then
    set -- clean build
fi

# Use the bundled Caddy test dependency and prevent network downloads.
bash "$ROOT_DIR/gradlew" --no-daemon --offline -x downloadCaddy "$@"
