#!/usr/bin/env bash

# Shared safety helpers for the disposable native SQL integration databases.
# The engine scripts set CONTAINER, IMAGE, LABEL, DATA_DIR, PORT, and DATA_PATH.

sql_docker() {
  env -u DOCKER_HOST -u DOCKER_CONTEXT -u DOCKER_TLS -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH \
    docker --host=unix:///var/run/docker.sock "$@"
}

sql_container_exists() {
  sql_docker container inspect "$CONTAINER" >/dev/null 2>&1
}

sql_assert_managed() {
  local image label mount_source published_port
  image="$(sql_docker inspect --format '{{.Config.Image}}' "$CONTAINER")"
  label="$(sql_docker inspect --format "{{index .Config.Labels \"${LABEL}\"}}" "$CONTAINER")"
  mount_source="$(sql_docker inspect --format "{{range .Mounts}}{{if eq .Destination \"${DATA_PATH}\"}}{{.Source}}{{end}}{{end}}" "$CONTAINER")"
  published_port="$(sql_docker inspect --format "{{range (index .HostConfig.PortBindings \"${CONTAINER_PORT}/tcp\")}}{{.HostIp}}:{{.HostPort}}{{end}}" "$CONTAINER")"
  if [[ "$image" != "$IMAGE" || "$label" != true || "$mount_source" != "$DATA_DIR" || "$published_port" != "127.0.0.1:${PORT}" ]]; then
    echo "Refusing to use existing container '$CONTAINER': image, ownership label, data mount, or localhost port does not match this harness" >&2
    return 1
  fi
}

sql_ensure_running() {
  if sql_container_exists; then
    sql_assert_managed
    local state
    state="$(sql_docker inspect --format '{{.State.Status}}' "$CONTAINER")"
    if [[ "$state" != running ]]; then sql_docker start "$CONTAINER" >/dev/null; fi
  else
    echo "Container '$CONTAINER' is missing; run $0 up first" >&2
    return 1
  fi
}

sql_down() {
  if sql_container_exists; then
    sql_assert_managed
    sql_docker stop "$CONTAINER"
  fi
}
