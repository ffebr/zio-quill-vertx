#!/usr/bin/env bash
set -euo pipefail

# Local disposable MySQL harness for native Vert.x SQL integration tests.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SETUP_DIR="${QUILL_VERTX_SETUP_DIR:-/workspace/.setup/quill}"
DATA_DIR="${SETUP_DIR}/mysql-data"
CONTAINER="${QUILL_VERTX_MYSQL_CONTAINER:-quill-vertx-mysql}"
IMAGE="mysql:8.4.7@sha256:6c6706ec58d3ac9e925d5f0435c6a923117f68232551b9c8271a294ea72ee331"
LABEL="io.quill.vertx.mysql-harness"
HOST="127.0.0.1"
PORT="53306"
CONTAINER_PORT="3306"
DATA_PATH="/var/lib/mysql"
DB="quill_vertx_test"
PASSWORD="${QUILL_VERTX_MYSQL_PASSWORD:-quill_test_only}"
SCHEMA="${ROOT_DIR}/quill-sql-test/src/test/sql/mysql-schema.sql"
source "$(dirname "${BASH_SOURCE[0]}")/sql-common.sh"

mysql_root() {
  sql_docker exec -e MYSQL_PWD="$PASSWORD" "$CONTAINER" mysql --protocol=socket --user=root --batch --skip-column-names "$@"
}

mysql_db() {
  sql_docker exec -e MYSQL_PWD="$PASSWORD" "$CONTAINER" mysql --protocol=socket --user=root --database="$DB" --batch --skip-column-names "$@"
}

wait_ready() {
  local attempt
  for attempt in $(seq 1 90); do
    if mysql_root --execute='SELECT 1' >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  sql_docker logs "$CONTAINER" >&2 || true
  echo 'MySQL did not become ready within 90 seconds' >&2
  return 1
}

db_summary() {
  mysql_db --execute='SELECT DATABASE(), VERSION(), COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE();'
}

reset_database() {
  [[ -r "$SCHEMA" ]] || { echo "Schema file not found: $SCHEMA" >&2; return 1; }
  mysql_root --execute="DROP DATABASE IF EXISTS \`${DB}\`; CREATE DATABASE \`${DB}\`;"
  sql_docker exec -i -e MYSQL_PWD="$PASSWORD" "$CONTAINER" mysql --protocol=socket --user=root --database="$DB" < "$SCHEMA"
  db_summary
}

# The original JDBC suites use this separate database by their existing config.
reference_init() {
  if [[ "$(mysql_root --execute="SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'quill_test'")" == 0 ]]; then
    mysql_root --execute='CREATE DATABASE quill_test'
    sql_docker exec -i -e MYSQL_PWD="$PASSWORD" "$CONTAINER" mysql --protocol=socket --user=root --database=quill_test < "$SCHEMA"
  fi
  mysql_root --database=quill_test --execute='SELECT DATABASE(), VERSION(), COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE();'
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
      --env MYSQL_ROOT_PASSWORD="$PASSWORD" \
      --env MYSQL_DATABASE="$DB" \
      "$IMAGE" >/dev/null
  else
    sql_ensure_running
  fi
  wait_ready
  if [[ "$created" == 1 ]]; then
    sql_docker exec -i -e MYSQL_PWD="$PASSWORD" "$CONTAINER" mysql --protocol=socket --user=root --database="$DB" < "$SCHEMA"
  fi
  db_summary
}

case "${1:-up}" in
  up) up ;;
  status) sql_ensure_running; wait_ready; db_summary ;;
  reset) sql_ensure_running; wait_ready; reset_database ;;
  reference-init) sql_ensure_running; wait_ready; reference_init ;;
  connection) printf 'MYSQL_HOST=%s\nMYSQL_PORT=%s\nMYSQL_DATABASE=%s\nMYSQL_USER=root\nMYSQL_PASSWORD=%s\n' "$HOST" "$PORT" "$DB" "$PASSWORD" ;;
  logs) sql_container_exists || { echo "Container is missing; run $0 up first" >&2; exit 1; }; sql_assert_managed; sql_docker logs "$CONTAINER" ;;
  down) sql_down ;;
  *) echo "Usage: $0 {up|status|reset|reference-init|connection|logs|down}" >&2; exit 2 ;;
esac
