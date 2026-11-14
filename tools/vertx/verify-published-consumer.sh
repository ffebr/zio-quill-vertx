#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <published-version> <app-commit-sha> <app-checkout>" >&2
}

if [[ $# != 3 ]]; then usage; exit 2; fi
version="$1"
app_commit="$2"
app_dir="$(cd "$3" && pwd)"
case "$version" in
  *SNAPSHOT) echo 'Snapshots are not accepted by the published-consumer check' >&2; exit 2 ;;
esac
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?$ ]] || {
  echo 'Expected an immutable semantic release version' >&2; exit 2;
}
[[ "$app_commit" =~ ^[0-9a-f]{40}$ ]] || { echo 'Expected an immutable 40-character app commit' >&2; exit 2; }
[[ "$(git -C "$app_dir" rev-parse HEAD)" == "$app_commit" ]] || {
  echo 'Checked-out application does not match app_commit' >&2; exit 1;
}
[[ "${GITHUB_ACTIONS:-}" == true && "${GITHUB_REPOSITORY:-}" == ffebr/zio-quill-vertx ]] || {
  echo 'Published-consumer test is restricted to Actions in ffebr/zio-quill-vertx' >&2; exit 1;
}
[[ -n "${GITHUB_PACKAGES_USER:-}" && -n "${GITHUB_PACKAGES_TOKEN:-}" ]] || {
  echo 'GitHub Packages read credentials are required' >&2; exit 1;
}

app_submission="$app_dir/submissions/scala-zio-vertx-quill-ffebr"
reports="${RUNNER_TEMP:-/tmp}/vertx-published-consumer-${version}"
mkdir -p "$reports"
[[ -f "$app_submission/build.sbt" && -f "$app_dir/test/run.sh" ]] || {
  echo 'Application checkout is missing the expected challenge files' >&2; exit 1;
}
driver_repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
producer_tag="vertx-v$version"
producer_sha="$(git -C "$driver_repo_dir" rev-parse --verify "refs/tags/$producer_tag^{commit}")"
quill_source_sha="$(sed -nE 's/.*zio-quill-vertx\.git#([0-9a-f]{40}).*/\1/p' "$app_submission/build.sbt" | head -n 1)"
[[ "$quill_source_sha" =~ ^[0-9a-f]{40}$ ]] || {
  echo 'Could not read the pinned Quill source SHA from the application build.sbt' >&2; exit 1;
}
[[ "$quill_source_sha" == "$producer_sha" ]] || {
  echo "App Quill source pin $quill_source_sha does not match release tag $producer_tag at $producer_sha" >&2
  exit 1
}
cat >"$reports/MANIFEST.txt" <<EOF
published_version=$version
producer_repository=ffebr/zio-quill-vertx
producer_tag=$producer_tag
producer_sha=$producer_sha
app_commit=$app_commit
EOF

if [[ -z "${PGHOST:-}" || -z "${PGPORT:-}" || -z "${PGUSER:-}" || -z "${PGPASSWORD:-}" ]]; then
  echo 'PGHOST, PGPORT, PGUSER and PGPASSWORD must point at the PostgreSQL 18 service' >&2
  exit 1
fi

echo "Building published native Quill $version and source-pinned JDBC in app $app_commit"
cd "$app_submission"
copy_test_reports() {
  local variant report_dir
  for variant in jdbc native; do
    report_dir="$app_submission/$variant/target/test-reports"
    if [[ -d "$report_dir" ]]; then
      mkdir -p "$reports/test-reports/$variant"
      find "$report_dir" -maxdepth 1 -type f -name '*.xml' -exec cp -- '{}' "$reports/test-reports/$variant/" \;
    fi
  done
}
if ./dev/sbt.sh "-Dconduit.quill.published.version=$version" \
  jdbc/scalafmtCheck native/scalafmtCheck \
  jdbc/Test/scalafmtCheck native/Test/scalafmtCheck scalafmtSbtCheck \
  jdbc/test native/test jdbc/stage native/stage 2>&1 | tee "$reports/build.log"; then
  copy_test_reports
else
  build_status=$?
  copy_test_reports
  exit "$build_status"
fi
python3 dev/check-runtime.py

audit_native_quill_closure() {
  local lib_dir="$1"
  local artifact
  local -a expected_scala_artifacts=(
    "quill-engine_2.13-$version.jar"
    "quill-core_2.13-$version.jar"
    "quill-sql_2.13-$version.jar"
    "quill-zio_2.13-$version.jar"
    "quill-vertx-zio_2.13-$version.jar"
  )
  local -a scala_jars=("$lib_dir"/quill-*_2.13-*.jar)
  local -a db2_jars=("$lib_dir"/quill-vertx-db2-client-*.jar)

  for artifact in "${expected_scala_artifacts[@]}"; do
    [[ -s "$lib_dir/$artifact" ]] || {
      echo "Native runtime is missing published Quill artifact $artifact" >&2; return 1;
    }
  done
  [[ "${#scala_jars[@]}" == 5 ]] || {
    echo "Expected exactly five Scala Quill runtime jars, found ${#scala_jars[@]}" >&2; return 1;
  }
  [[ "${#db2_jars[@]}" == 1 && "${db2_jars[0]}" == "$lib_dir/quill-vertx-db2-client-$version.jar" ]] || {
    echo "Expected one quill-vertx-db2-client-$version.jar in the native runtime" >&2; return 1;
  }
  for artifact in "${scala_jars[@]}"; do
    case "$(basename "$artifact")" in
      "quill-engine_2.13-$version.jar"|"quill-core_2.13-$version.jar"|"quill-sql_2.13-$version.jar"|"quill-zio_2.13-$version.jar"|"quill-vertx-zio_2.13-$version.jar") ;;
      *) echo "Unexpected or source-shadowing Quill runtime jar: $(basename "$artifact")" >&2; return 1 ;;
    esac
  done
  echo "Native Quill runtime closure verified at $lib_dir: version $version"
}

audit_native_quill_closure "$app_submission/native/target/stage/lib"

tmp_dir="$(mktemp -d "${RUNNER_TEMP:-/tmp}/vertx-published-consumer.XXXXXX")"
cleanup() { rm -rf "$tmp_dir"; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for variant in jdbc native; do
  # Reuse the original test runner against the staged classpath without letting
  # its build.sh replace the published-mode stage with a source build.
  runner="$tmp_dir/runner-$variant"
  mkdir -p "$runner"
  cp "$app_submission/$variant/README.md" "$runner/README.md"
  cat >"$runner/build.sh" <<'BUILD'
#!/usr/bin/env bash
set -euo pipefail
BUILD
  cat >"$runner/start.sh" <<'START'
#!/usr/bin/env bash
set -euo pipefail
: "${APP_VARIANT_DIR:?APP_VARIANT_DIR is required}"
exec java -Xmx2g -Duser.timezone=UTC -cp "$APP_VARIANT_DIR/target/stage/lib/*" conduit.Main
START
  chmod 755 "$runner/build.sh" "$runner/start.sh"
  APP_VARIANT_DIR="$app_submission/$variant" bash "$app_dir/test/run.sh" "$runner" \
    2>&1 | tee "$reports/original-$variant.log"
  PORT=3000 bash "$app_submission/dev/transcript.sh" "$variant" "$reports/$variant.jsonl" \
    2>&1 | tee "$reports/transcript-$variant.log"
  cmp "$app_dir/bench/cmd/conformance/golden-dev.jsonl" "$reports/$variant.jsonl"

  archive="$tmp_dir/conduit-$variant.tar.gz"
  bash "$app_submission/ci/package.sh" "$variant" "$archive"
  extracted="$tmp_dir/package-$variant"
  mkdir -p "$extracted"
  tar -xzf "$archive" -C "$extracted"
  package_dir="$extracted/conduit-$variant"
  (cd "$package_dir" && sha256sum --check SHA256SUMS)
  grep -Fx "source_commit=$app_commit" "$package_dir/MANIFEST.txt"
  grep -Fx "quill_source_commit=$producer_sha" "$package_dir/MANIFEST.txt"
  [[ -x "$package_dir/bin/start.sh" && -s "$package_dir/README.md" ]]
  if [[ "$variant" == native ]]; then
    audit_native_quill_closure "$package_dir/lib"
  fi
  while IFS= read -r path; do
    case "$path" in
      "conduit-$variant/"|"conduit-$variant/README.md"|"conduit-$variant/MANIFEST.txt"|"conduit-$variant/SHA256SUMS"|"conduit-$variant/bin/"|"conduit-$variant/bin/start.sh"|"conduit-$variant/lib/"|"conduit-$variant/lib/"*.jar) ;;
      *) echo "Unexpected packaged path: $path" >&2; exit 1 ;;
    esac
  done < <(tar -tzf "$archive")
done

echo "Published consumer passed: quill-vertx-zio $version in app $app_commit"
