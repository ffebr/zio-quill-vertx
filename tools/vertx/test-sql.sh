#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_dir"
profile="${1:-all}"
if [[ $# -gt 0 ]]; then shift; fi

start_database() {
  local script="$1" name value connection_values
  "./tools/vertx/$script" up
  connection_values="$("./tools/vertx/$script" connection)"
  while IFS='=' read -r name value; do
    [[ -z "$name" ]] && continue
    if [[ ! "$name" =~ ^[A-Z][A-Z0-9_]*$ ]]; then
      echo "Invalid connection variable name from $script" >&2
      return 1
    fi
    export "$name=$value"
  done <<< "$connection_values"
  if [[ "$script" == postgres.sh ]]; then
    export POSTGRES_HOST="$PGHOST" POSTGRES_PORT="$PGPORT" POSTGRES_DB="$PGDATABASE"
    export POSTGRES_USER="$PGUSER" POSTGRES_PASSWORD="$PGPASSWORD"
  fi
}

case "$profile" in
  postgres) exec ./tools/vertx/test.sh "$@" ;;
  mysql) start_database mysql.sh; task='quill-vertx-zio/testOnly *MysqlZioVertx*' ;;
  mariadb) start_database mariadb.sh; task='quill-vertx-zio/testOnly *MariaDBZioVertx*' ;;
  db2) start_database db2.sh; task='quill-vertx-zio/testOnly *DB2ZioVertx*' ;;
  all)
    start_database postgres.sh
    start_database mysql.sh
    start_database mariadb.sh
    start_database db2.sh
    task='quill-vertx-zio/test'
    ;;
  *) echo 'Usage: test-sql.sh {postgres|mysql|mariadb|db2|all} [sbt tasks...]' >&2; exit 2 ;;
esac

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

export CI=true
tasks=("$task")
if [[ "${QUILL_VERTX_CLEAN:-0}" == 1 ]]; then tasks=('quill-vertx-zio/clean' "$task"); fi
exec "$sbt_command" "${tasks[@]}" "$@"
