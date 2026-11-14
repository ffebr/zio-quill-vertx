# Fork-only Vert.x releases

Repository: `ffebr/zio-quill-vertx`. Maven group: `io.github.ffebr`.
Registry: `https://maven.pkg.github.com/ffebr/zio-quill-vertx`.

The latest published version is `0.1.0-alpha.2`, tagged `vertx-v0.1.0-alpha.2`.
Publication completed on 2026-11-14 after all eight database/Scala jobs passed in
the fork's [Native Vert.x workflow](https://github.com/ffebr/zio-quill-vertx/actions/runs/43613000269).
It includes the general PostgreSQL parameter-binding fix. The original
`0.1.0-alpha.1` remains immutable and has known lifted-NULL failures.

- [Prerelease](https://github.com/ffebr/zio-quill-vertx/releases/tag/vertx-v0.1.0-alpha.2)
- [Scala 2.13 package](https://github.com/ffebr/zio-quill-vertx/packages/3386899)
- [Scala 2.12 package](https://github.com/ffebr/zio-quill-vertx/packages/3386904)

## CI/CD

`.github/workflows/vertx-release.yml` tests every supported backend on both
Scala versions. Tag publication waits for the entire matrix to pass. Each
backend uses the same real disposable database and corpus as local validation.
The publish job prepares binaries, source jars and POMs for both Scala versions,
checks the complete runtime dependency closure, then publishes through the
workflow's repository-scoped `GITHUB_TOKEN` with `packages: write`. It creates
a prerelease in this fork after successful publication.

Only these modules are published: `quill-engine`, `quill-core`, `quill-sql`,
`quill-zio`, `quill-vertx-zio`, and `quill-vertx-db2-client`. The first five
have Scala 2.12 and 2.13 variants; the Java DB2 artifact is published once.
All internal runtime dependencies use the same fork group and version. The
provided original DB2 client is only a build input to the corrected full jar.

The build fixes the destination to this fork's GitHub Maven registry. Its
publish guard requires the exact repository, GitHub Actions, the allowed
module set, an explicit immutable release version, and a workflow token.
Inherited workflows are restricted to their original `zio/zio-quill`
repository, including the old Sonatype/npm release and upstream notification.
If regenerating inherited workflows, preserve those ownership guards.

To request a release, push an immutable `vertx-v<semantic-version>` tag pointing
at the intended commit in this fork. Never move an already published tag or
reuse a published version: use a new alpha number for corrections.
The manual workflow entry accepts an existing tag, checks out that tag for
both testing and publishing, and applies the same test gate. This also allows
starting the first release after enabling Actions on a newly created fork.

## Published application verification

The [alpha.2 registry-consumer run](https://github.com/ffebr/zio-quill-vertx/actions/runs/43614128553)
passed against app commit `6b23740cf39dc6baab19f9c7f80e07572ecbd7c7`:
197 original conformance steps per executor, identical golden transcripts,
14 JDBC / 16 native unit tests, and exact-version runtime/package audits.
The app's [own CI](https://github.com/ffebr/vertx-jdbc-challenge/actions/runs/43612440268)
also passed and delivered both nonroot images to its fork's GHCR registry.

For a coordinated release, push the driver commit, update the application's
`main` source pin to that commit, and wait for application CI before tagging the
driver commit. The consumer intentionally rejects a mismatched source pin.

After a successful tag release, `vertx-published-consumer.yml` resolves the
Conduit app's `main` once and checks out that immutable commit. Its JDBC executor
uses the same source revision as the release tag; native resolves the published
GitHub Packages artifacts. The check audits all six native Quill runtime jars,
builds and tests both executors, runs the original API suite and golden
transcripts, and verifies package manifests and checksums.

The workflow uses this driver's repository token with `packages: read`, so the
application repository does not need cross-repository package credentials.
Manual dispatch accepts an immutable version and app commit and enforces the
same source-pin and runtime-closure checks.

## Consume a published version

```scala
resolvers += "ffebr Quill Vert.x" at
  "https://maven.pkg.github.com/ffebr/zio-quill-vertx"

libraryDependencies += "io.github.ffebr" %% "quill-vertx-zio" % "0.1.0-alpha.2"

credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  sys.env("GITHUB_PACKAGES_USER"),
  sys.env("GITHUB_PACKAGES_TOKEN")
)
```

GitHub's Maven registry requires authentication for downloads, including
public packages. Configure credentials outside source control; a classic PAT
with `read:packages` can read packages the account may access. For another
repository's Actions, grant that repository package access before using its
`GITHUB_TOKEN`. Do not place token values in issues, commits or chat.

Replace the application's previous Quill dependencies with this fork's
coordinates so the same `zio.quill` classes are not supplied by two artifact
groups. ZIO and Vert.x external dependencies retain their original coordinates.
See the [module README](../../quill-vertx-zio/README.md) for context/pool wiring.
