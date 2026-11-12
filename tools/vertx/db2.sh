#!/usr/bin/env bash
set -euo pipefail

# Local disposable Db2 Community harness. This image requires privileged mode,
# accepts IBM's local Community license, and needs at least 4 GiB of memory.
SETUP_DIR="${QUILL_VERTX_SETUP_DIR:-/workspace/.setup/quill}"
DATA_DIR="${SETUP_DIR}/db2-data"
CONTAINER="${QUILL_VERTX_DB2_CONTAINER:-quill-vertx-db2}"
IMAGE="ibmcom/db2:11.5.8.0@sha256:220c15de7eba67e97d6a3db0f08ae171d246acdfb18d7fd109ce43652f97676e"
LABEL="io.quill.vertx.db2-harness"
HOST="127.0.0.1"
PORT="50500"
CONTAINER_PORT="50000"
DATA_PATH="/database"
DB="QTEST"
PASSWORD="${QUILL_VERTX_DB2_PASSWORD:-Quill_Test_only1!}"
source "$(dirname "${BASH_SOURCE[0]}")/sql-common.sh"

db_summary() {
  sql_docker exec "$CONTAINER" bash -lc "su - db2inst1 -c 'db2 connect to ${DB}; db2 -x \"SELECT CURRENT SERVER, CURRENT USER, COUNT(*) FROM SYSCAT.TABLES WHERE TABSCHEMA NOT LIKE '\''SYS%'\''\"; db2 connect reset'"
}

wait_ready() {
  local attempt ready_attempts="${QUILL_VERTX_DB2_READY_ATTEMPTS:-180}"
  for attempt in $(seq 1 "$ready_attempts"); do
    if sql_docker exec "$CONTAINER" bash -lc "su - db2inst1 -c 'db2 connect to ${DB}'" >/dev/null 2>&1; then
      sql_docker exec "$CONTAINER" bash -lc "su - db2inst1 -c 'db2 connect reset'" >/dev/null 2>&1 || true
      return 0
    fi
    sleep 1
  done
  sql_docker logs "$CONTAINER" >&2 || true
  echo "Db2 did not become ready within $ready_attempts attempts" >&2
  return 1
}

create_database() {
  sql_docker exec "$CONTAINER" bash -lc "su - db2inst1 -c 'db2 create database ${DB} using codeset UTF-8 territory US pagesize 8192'"
}

reset_database() {
  sql_docker exec "$CONTAINER" bash -lc "su - db2inst1 -c 'db2 force applications all; db2 terminate; db2 drop database ${DB}'"
  create_database
  wait_ready
  db_summary
}

up() {
  mkdir -p "$DATA_DIR"
  if ! sql_container_exists; then
    sql_docker run --detach --name "$CONTAINER" \
      --label "${LABEL}=true" \
      --publish "${HOST}:${PORT}:${CONTAINER_PORT}" \
      --mount "type=bind,src=${DATA_DIR},dst=${DATA_PATH}" \
      --privileged \
      --memory=6g \
      --cpus=3 \
      --env LICENSE=accept \
      --env DB2INSTANCE=db2inst1 \
      --env DB2INST1_PASSWORD="$PASSWORD" \
      --env DBNAME="$DB" \
      "$IMAGE" >/dev/null
  else
    sql_ensure_running
  fi
  wait_ready
  db_summary
}

case "${1:-up}" in
  up) up ;;
  status) sql_ensure_running; wait_ready; db_summary ;;
  reset) sql_ensure_running; wait_ready; reset_database ;;
  connection) printf 'DB2_HOST=%s\nDB2_PORT=%s\nDB2_DATABASE=%s\nDB2_USER=db2inst1\nDB2_PASSWORD=%s\n' "$HOST" "$PORT" "$DB" "$PASSWORD" ;;
  logs) sql_container_exists || { echo "Container is missing; run $0 up first" >&2; exit 1; }; sql_assert_managed; sql_docker logs "$CONTAINER" ;;
  down) sql_down ;;
  *) echo "Usage: $0 {up|status|reset|connection|logs|down}" >&2; exit 2 ;;
esac
