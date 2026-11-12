#!/usr/bin/env bash
set -euo pipefail

# Local disposable PostgreSQL harness for the Vert.x QDSL integration tests.
# Credentials are for this local test instance only, not application credentials.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SETUP_DIR="${QUILL_VERTX_SETUP_DIR:-/workspace/.setup/quill}"
DATA_DIR="${SETUP_DIR}/postgres-data"
CONTAINER="${QUILL_VERTX_PG_CONTAINER:-quill-vertx-postgres}"
IMAGE="postgres:16.11-bookworm@sha256:a2420e9555e2224583fe84d0bb3f0b967e69354ae3a0be55a9c14e251388c4eb"
HOST="${QUILL_VERTX_PG_HOST:-127.0.0.1}"
PORT="${QUILL_VERTX_PG_PORT:-55432}"
DB="${QUILL_VERTX_PG_DATABASE:-quill_vertx_test}"
PG_ROLE="${QUILL_VERTX_PG_USER:-quill_test}"
PASSWORD="${QUILL_VERTX_PG_PASSWORD:-quill_test_only}"
REFERENCE_DB="quill_test"
REFERENCE_ROLE="postgres"
REFERENCE_PASSWORD="postgres"
SCHEMA="${ROOT_DIR}/quill-sql-test/src/test/sql/postgres-schema.sql"
MANAGED_LABEL="io.quill.vertx.postgres-harness"

for identifier in "$DB" "$PG_ROLE"; do
  if [[ ! "$identifier" =~ ^[A-Za-z_][A-Za-z0-9_]{0,62}$ ]]; then
    echo "Database and role names must match [A-Za-z_][A-Za-z0-9_]{0,62}" >&2
    exit 2
  fi
done

docker_cli() {
  env -u DOCKER_HOST -u DOCKER_CONTEXT -u DOCKER_TLS -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH \
    docker --host=unix:///var/run/docker.sock "$@"
}

container_exists() {
  docker_cli container inspect "$CONTAINER" >/dev/null 2>&1
}

assert_managed_container() {
  local image label mount_source
  image="$(docker_cli inspect --format '{{.Config.Image}}' "$CONTAINER")"
  label="$(docker_cli inspect --format "{{index .Config.Labels \"${MANAGED_LABEL}\"}}" "$CONTAINER")"
  mount_source="$(docker_cli inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Source}}{{end}}{{end}}' "$CONTAINER")"
  if [[ "$image" != "$IMAGE" || "$label" != true || "$mount_source" != "$DATA_DIR" ]]; then
    echo "Refusing to use existing container '$CONTAINER': expected the managed image, label, and data mount" >&2
    return 1
  fi
}

wait_ready() {
  local attempt
  for attempt in $(seq 1 60); do
    if docker_cli exec "$CONTAINER" pg_isready -U "$PG_ROLE" -d postgres >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  docker_cli logs "$CONTAINER" >&2 || true
  echo "PostgreSQL did not become ready within 60 seconds" >&2
  return 1
}

psql_admin() {
  docker_cli exec -e PGPASSWORD="$PASSWORD" "$CONTAINER" \
    psql --username="$PG_ROLE" --dbname=postgres --no-psqlrc --set=ON_ERROR_STOP=1 "$@"
}

psql_reference() {
  docker_cli exec -e PGPASSWORD="$REFERENCE_PASSWORD" "$CONTAINER" \
    psql --username="$REFERENCE_ROLE" --dbname="$REFERENCE_DB" --no-psqlrc --set=ON_ERROR_STOP=1 "$@"
}

ensure_reference_role() {
  psql_admin --command="DO \$\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${REFERENCE_ROLE}') THEN CREATE ROLE ${REFERENCE_ROLE} LOGIN SUPERUSER PASSWORD '${REFERENCE_PASSWORD}'; END IF; END \$\$;"
  psql_admin --command="ALTER ROLE ${REFERENCE_ROLE} WITH LOGIN SUPERUSER PASSWORD '${REFERENCE_PASSWORD}';"
}

create_reference_database() {
  [[ -r "$SCHEMA" ]] || { echo "Schema file not found: $SCHEMA" >&2; return 1; }
  psql_admin --command="CREATE DATABASE ${REFERENCE_DB} OWNER ${REFERENCE_ROLE};"
  docker_cli exec -i -e PGPASSWORD="$REFERENCE_PASSWORD" "$CONTAINER" \
    psql --username="$REFERENCE_ROLE" --dbname="$REFERENCE_DB" --no-psqlrc --set=ON_ERROR_STOP=1 < "$SCHEMA"
}

reference_init() {
  ensure_reference_role
  local database_exists
  database_exists="$(psql_admin --tuples-only --no-align --command="SELECT 1 FROM pg_database WHERE datname = '${REFERENCE_DB}';")"
  if [[ "$database_exists" != 1 ]]; then
    create_reference_database
  fi
  psql_reference --command="SELECT current_database() AS database, current_user AS user, version() AS server, count(*) AS tables FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';"
}

reference_reset() {
  ensure_reference_role
  psql_admin --command="SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '${REFERENCE_DB}' AND pid <> pg_backend_pid();"
  psql_admin --command="DROP DATABASE IF EXISTS ${REFERENCE_DB};"
  create_reference_database
  psql_reference --command="SELECT current_database() AS database, current_user AS user, version() AS server, count(*) AS tables FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';"
}

reset_database() {
  [[ -r "$SCHEMA" ]] || { echo "Schema file not found: $SCHEMA" >&2; return 1; }
  psql_admin --command="SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$DB' AND pid <> pg_backend_pid();"
  psql_admin --command="DROP DATABASE IF EXISTS \"$DB\";"
  psql_admin --command="CREATE DATABASE \"$DB\" OWNER \"$PG_ROLE\";"
  docker_cli exec -i -e PGPASSWORD="$PASSWORD" "$CONTAINER" \
    psql --username="$PG_ROLE" --dbname="$DB" --no-psqlrc --set=ON_ERROR_STOP=1 < "$SCHEMA"
  docker_cli exec -e PGPASSWORD="$PASSWORD" "$CONTAINER" \
    psql --username="$PG_ROLE" --dbname="$DB" --no-psqlrc --set=ON_ERROR_STOP=1 \
    --command="SELECT current_database() AS database, version() AS server, count(*) AS tables FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';"
}

up() {
  mkdir -p "$DATA_DIR"
  if ! container_exists; then
    [[ -r "$SCHEMA" ]] || { echo "Schema file not found: $SCHEMA" >&2; return 1; }
    docker_cli run --detach --name "$CONTAINER" \
      --label "${MANAGED_LABEL}=true" \
      --publish "${HOST}:${PORT}:5432" \
      --mount "type=bind,src=${DATA_DIR},dst=/var/lib/postgresql/data" \
      --mount "type=bind,src=${SCHEMA},dst=/docker-entrypoint-initdb.d/10-quill-schema.sql,readonly" \
      --env POSTGRES_USER="$PG_ROLE" \
      --env POSTGRES_PASSWORD="$PASSWORD" \
      --env POSTGRES_DB="$DB" \
      "$IMAGE" >/dev/null
  else
    assert_managed_container
    local state
    state="$(docker_cli inspect --format '{{.State.Status}}' "$CONTAINER")"
    if [[ "$state" != running ]]; then docker_cli start "$CONTAINER" >/dev/null; fi
  fi
  wait_ready
  docker_cli exec -e PGPASSWORD="$PASSWORD" "$CONTAINER" \
    psql --username="$PG_ROLE" --dbname="$DB" --no-psqlrc --set=ON_ERROR_STOP=1 \
    --command="SELECT current_database() AS database, version() AS server, count(*) AS tables FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';"
}

case "${1:-up}" in
  up)
    up
    ;;
  reset)
    container_exists || { echo "Container is missing; run $0 up first" >&2; exit 1; }
    assert_managed_container
    wait_ready
    reset_database
    ;;
  status)
    container_exists || { echo "Container is missing; run $0 up first" >&2; exit 1; }
    assert_managed_container
    wait_ready
    docker_cli exec -e PGPASSWORD="$PASSWORD" "$CONTAINER" \
      psql --username="$PG_ROLE" --dbname="$DB" --no-psqlrc --set=ON_ERROR_STOP=1 \
      --command="SELECT current_database() AS database, current_user AS user, version() AS server, count(*) AS tables FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';"
    ;;
  reference-init)
    container_exists || { echo "Container is missing; run $0 up first" >&2; exit 1; }
    assert_managed_container
    wait_ready
    reference_init
    ;;
  reference-reset)
    container_exists || { echo "Container is missing; run $0 up first" >&2; exit 1; }
    assert_managed_container
    wait_ready
    reference_reset
    ;;
  reference-status)
    container_exists || { echo "Container is missing; run $0 up first" >&2; exit 1; }
    assert_managed_container
    wait_ready
    psql_reference --command="SELECT current_database() AS database, current_user AS user, version() AS server, count(*) AS tables FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';"
    ;;
  connection)
    printf 'PGHOST=%s\nPGPORT=%s\nPGDATABASE=%s\nPGUSER=%s\nPGPASSWORD=%s\n' "$HOST" "$PORT" "$DB" "$PG_ROLE" "$PASSWORD"
    ;;
  reference-connection)
    printf 'POSTGRES_HOST=%s\nPOSTGRES_PORT=%s\nPOSTGRES_PASSWORD=%s\n' "$HOST" "$PORT" "$REFERENCE_PASSWORD"
    ;;
  logs)
    container_exists || { echo "Container is missing; run $0 up first" >&2; exit 1; }
    assert_managed_container
    docker_cli logs "$CONTAINER"
    ;;
  down)
    if container_exists; then
      assert_managed_container
      docker_cli stop "$CONTAINER"
    fi
    ;;
  *)
    echo "Usage: $0 {up|reset|status|connection|logs|down|reference-init|reference-reset|reference-status|reference-connection}" >&2
    exit 2
    ;;
esac
