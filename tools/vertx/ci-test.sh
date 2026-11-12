#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_dir"
case "${1:-}" in
  postgres|mysql|mariadb|db2|all) profile="$1" ;;
  *) echo 'Usage: ci-test.sh {postgres|mysql|mariadb|db2|all} {2.12.20|2.13.16}' >&2; exit 2 ;;
esac
export QUILL_VERTX_SCALA_VERSION="${2:-}"
case "$QUILL_VERTX_SCALA_VERSION" in
  2.12.20|2.13.16) ;;
  *) echo 'Supported Scala versions: 2.12.20, 2.13.16' >&2; exit 2 ;;
esac
export QUILL_VERTX_SBT="$repo_dir/tools/vertx/ci-sbt.sh"
exec ./tools/vertx/test-sql.sh "$profile"
