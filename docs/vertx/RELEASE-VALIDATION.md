# Fork release preparation

Target: `ffebr/zio-quill-vertx`, Maven group `io.github.ffebr`, version
`0.1.0-alpha.1`, tag `vertx-v0.1.0-alpha.1`.

Publication is confirmed: the fork's
[Native Vert.x workflow](https://github.com/ffebr/zio-quill-vertx/actions/runs/43287367791)
completed successfully on 2026-11-12. All eight database/Scala matrix jobs,
the package upload step and the prerelease creation step succeeded. The
registry lists all 11 artifacts; the native Scala 2.12 and 2.13 package pages
both show version `0.1.0-alpha.1`.

Release commit: `ee69343301240cdfc13a4d8a869997d199dc3d33`.
The immutable tag points at that commit in `ffebr/zio-quill-vertx`.

Completed locally:

- The native module's full supported matrix passed 239 tests on each Scala
  version before release wiring; original PostgreSQL fixtures remain unchanged.
- Both Scala variants of the five Scala runtime modules and the Java DB2
  client were built and staged into a workspace-local Ivy repository.
- `tools/vertx/verify-release.py` validated all 11 coordinate sets, POM internal
  dependency closure, binary/source jars, and the DB2 native correction.
- A separate application resolved those binary dependencies and compiled QDSL,
  run/prepare/translate, shared JsonValue, and all three contexts on Scala
  2.12.20 and 2.13.16. It loaded native PostgreSQL/MySQL/DB2 drivers through
  ServiceLoader without an MSSQL client.
- The actual `ci-test.sh postgres 2.12.20` entrypoint passed 176 tests with the
  local real PostgreSQL harness. The GitHub matrix also requires MySQL,
  MariaDB and DB2 on both Scala versions before publication.
- actionlint 1.7.7 accepts all workflow files; shell syntax checks pass.
- The sbt publish guard rejected an invocation outside the designated Actions
  context. All inherited workflow jobs are restricted to `zio/zio-quill`;
  the new workflow is restricted to `ffebr/zio-quill-vertx`.

Workspace evidence under `/workspace/.setup/quill/`:
`fork-release-local-stage.log`, `fork-release-consumer.log`,
`fork-release-ci-entrypoint.log`, `fork-release-guard-negative.log`.

The source and tag were pushed only to the user's fork. GitHub API access from
the local environment was unavailable; the workflow's success, publication
steps, prerelease and package versions were confirmed through public GitHub
pages. No environment network configuration was changed.
