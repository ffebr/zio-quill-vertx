#!/usr/bin/env bash
set -euo pipefail
case "${QUILL_VERTX_SCALA_VERSION:-}" in
  2.12.20|2.13.16) ;;
  *) echo 'Set QUILL_VERTX_SCALA_VERSION to 2.12.20 or 2.13.16' >&2; exit 2 ;;
esac
exec "${QUILL_VERTX_REAL_SBT:-sbt}" "-Dquill.scala.version=${QUILL_VERTX_SCALA_VERSION}" "++${QUILL_VERTX_SCALA_VERSION}" "$@"
