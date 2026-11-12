#!/usr/bin/env bash
set -euo pipefail

# Local disposable MariaDB harness for native Vert.x SQL integration tests.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SETUP_DIR="${QUILL_VERTX_SETUP_DIR:-/workspace/.setup/quill}"
DATA_DIR="${SETUP_DIR}/mariadb-data"
CONTAINER="${QUILL_VERTX_MARIADB_CONTAINER:-quill-vertx-mariadb}"
IMAGE="mariadb:11.4.5@sha256:ec1794c8cd442f795b838f60379d7851acf8fef04cc39a8ecf74e7b1d6a3b6d7"
LABEL="io.quill.vertx.mariadb-harness"
HOST="127.0.0.1"
PORT="53307"
CONTAINER_PORT="3306"
DATA_PATH="/var/lib/mysql"
DB="quill_vertx_test"
PASSWORD="${QUILL_VERTX_MARIADB_PASSWORD:-quill_test_only}"
SCHEMA="${ROOT_DIR}/quill-sql-test/src/test/sql/mysql-schema.sql"
source "$(dirname "${BASH_SOURCE[0]}")/sql-common.sh"

maria_root() {
  sql_docker exec -e MYSQL_PWD="$PASSWORD" "$CONTAINER" mariadb --protocol=socket --user=root --batch --skip-column-names "$@"
}

maria_db() {
  sql_docker exec -e MYSQL_PWD="$PASSWORD" "$CONTAINER" mariadb --protocol=socket --user=root --database="$DB" --batch --skip-column-names "$@"
}

wait_ready() {
  local attempt
  for attempt in $(seq 1 90); do
    if maria_root --execute='SELECT 1' >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  sql_docker logs "$CONTAINER" >&2 || true
  echo 'MariaDB did not become ready within 90 seconds' >&2
  return 1
}

db_summary() {
  maria_db --execute='SELECT DATABASE(), VERSION(), COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE();'
}

reset_database() {
  [[ -r "$SCHEMA" ]] || { echo "Schema file not found: $SCHEMA" >&2; return 1; }
  maria_root --execute="DROP DATABASE IF EXISTS \`${DB}\`; CREATE DATABASE \`${DB}\`;"
  sql_docker exec -i -e MYSQL_PWD="$PASSWORD" "$CONTAINER" mariadb --protocol=socket --user=root --database="$DB" < "$SCHEMA"
  db_summary
}

up() {
  local created=0
  mkdir -p "$DATA_DIR"
  [[ -r "$SCHEMA" ]] || { echo "Schema file not found: $SCHEMA" >&2; return 1; }
  if ! sql_container_exists; then
    created=1
    sql_docker run --detach --name "$CONTAINER" \
      --label "${LABEL}=true" \
      --publish "${HOST}:${PORT}:${CONTAINER_PORT}" \
      --mount "type=bind,src=${DATA_DIR},dst=${DATA_PATH}" \
      --env MARIADB_ROOT_PASSWORD="$PASSWORD" \
      --env MARIADB_DATABASE="$DB" \
      "$IMAGE" >/dev/null
  else
    sql_ensure_running
  fi
  wait_ready
  if [[ "$created" == 1 ]]; then
    sql_docker exec -i -e MYSQL_PWD="$PASSWORD" "$CONTAINER" mariadb --protocol=socket --user=root --database="$DB" < "$SCHEMA"
  fi
  db_summary
}

case "${1:-up}" in
  up) up ;;
  status) sql_ensure_running; wait_ready; db_summary ;;
  reset) sql_ensure_running; wait_ready; reset_database ;;
  connection) printf 'MARIADB_HOST=%s\nMARIADB_PORT=%s\nMARIADB_DATABASE=%s\nMARIADB_USER=root\nMARIADB_PASSWORD=%s\n' "$HOST" "$PORT" "$DB" "$PASSWORD" ;;
  logs) sql_container_exists || { echo "Container is missing; run $0 up first" >&2; exit 1; }; sql_assert_managed; sql_docker logs "$CONTAINER" ;;
  down) sql_down ;;
  *) echo "Usage: $0 {up|status|reset|connection|logs|down}" >&2; exit 2 ;;
esac
