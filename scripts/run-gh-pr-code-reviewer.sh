#!/usr/bin/env bash
# Usage: scripts/run-gh-pr-code-reviewer.sh <github-pull-request-url> [--max-diff-chars N]
# Set GITHUB_TOKEN for private repositories or to lift GitHub's unauthenticated rate limit.
# -Djitllm.EnableTimingForTornadoVMInit=true turns on the engine's own log of weight loading and
# TornadoVM initialization.
set -euo pipefail
if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <github-pull-request-url> [--max-diff-chars N]" >&2
    exit 2
fi
ARGFILE="$("$(dirname "$0")/tornado-args.sh")"
CMD=(java
    "@$ARGFILE"
    --add-modules jdk.incubator.vector
    -Djitllm.EnableTimingForTornadoVMInit=true
    -jar demos/gh-pr-code-reviewer/target/quarkus-app/quarkus-run.jar
    "$@")
echo "${CMD[*]}"
"${CMD[@]}"
