#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_dir"
if [[ "${GITHUB_ACTIONS:-}" != true || "${GITHUB_REPOSITORY:-}" != ffebr/zio-quill-vertx ]]; then
  echo 'Publication is restricted to GitHub Actions in ffebr/zio-quill-vertx' >&2
  exit 1
fi
case "${GITHUB_EVENT_NAME:-}" in
  push)
    [[ "${GITHUB_REF_TYPE:-}" == tag && "${1:-}" == "${GITHUB_REF_NAME:-}" ]] || {
      echo 'Publish a push only from its own tag' >&2; exit 1;
    }
    tag="$GITHUB_REF_NAME"
    ;;
  workflow_dispatch) tag="${1:-}" ;;
  *) echo 'Publish only from a release tag push or an explicit workflow dispatch' >&2; exit 1 ;;
esac
[[ "$tag" == vertx-v* ]] || { echo 'Expected a vertx-v<version> tag' >&2; exit 1; }
release_version="${tag#vertx-v}"
if [[ ! "$release_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?$ || "$release_version" == *SNAPSHOT ]]; then
  echo 'Expected an immutable semantic release version' >&2
  exit 1
fi
[[ "$(git rev-parse HEAD)" == "$(git rev-parse "refs/tags/$tag^{commit}")" ]] || {
  echo 'Checkout does not match the release tag' >&2; exit 1;
}
modules=(quill-engine quill-core quill-sql quill-zio quill-vertx-db2-client quill-vertx-zio)
prepare=()
upload=()
for scala_version in 2.13.16 2.12.20; do
  prepare+=("++$scala_version")
  upload+=("++$scala_version")
  for module in "${modules[@]}"; do
    # The corrected Java client has identical coordinates on both Scala versions.
    if [[ "$module" == quill-vertx-db2-client && "$scala_version" == 2.12.20 ]]; then continue; fi
    prepare+=("$module/packageBin" "$module/packageSrc" "$module/makePom")
    upload+=("$module/publish")
  done
done
sbt -Dmodules=base -Dquill.macro.log=false "-Dquill.vertx.release.version=$release_version" "${prepare[@]}"
python3 tools/vertx/verify-release.py "$release_version"
sbt -Dmodules=base -Dquill.macro.log=false "-Dquill.vertx.release.version=$release_version" "${upload[@]}"
