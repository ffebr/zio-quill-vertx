#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_dir"
./tools/vertx/postgres.sh up
export POSTGRES_HOST="${POSTGRES_HOST:-${QUILL_VERTX_PG_HOST:-127.0.0.1}}"
export POSTGRES_PORT="${POSTGRES_PORT:-${QUILL_VERTX_PG_PORT:-55432}}"
export POSTGRES_DB="${POSTGRES_DB:-${QUILL_VERTX_PG_DATABASE:-quill_vertx_test}}"
export POSTGRES_USER="${POSTGRES_USER:-${QUILL_VERTX_PG_USER:-quill_test}}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-${QUILL_VERTX_PG_PASSWORD:-quill_test_only}}"
export CI=true
if [[ -n "${QUILL_VERTX_SBT:-}" ]]; then
  sbt_command="$QUILL_VERTX_SBT"
elif command -v sbt >/dev/null 2>&1; then
  sbt_command="$(command -v sbt)"
elif [[ -x /workspace/.setup/quill/bin/sbt ]]; then
  sbt_command=/workspace/.setup/quill/bin/sbt
else
  echo 'sbt is required; set QUILL_VERTX_SBT to its executable path.' >&2
  exit 1
fi
postgres_tests='quill-vertx-zio/testOnly zio.quill.vertx.PeopleZioVertxSpec zio.quill.vertx.QueryParitySpec zio.quill.vertx.Vertx* zio.quill.vertx.shared.* zio.quill.context.vertx.*'
sbt_tasks=("$postgres_tests")
if [[ "${QUILL_VERTX_CLEAN:-0}" == 1 ]]; then
  sbt_tasks=('quill-vertx-zio/clean' "$postgres_tests")
fi
exec "$sbt_command" "${sbt_tasks[@]}" "$@"
