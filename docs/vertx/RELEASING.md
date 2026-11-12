# Fork-only Vert.x releases

Repository: `ffebr/zio-quill-vertx`. Maven group: `io.github.ffebr`.
Registry: `https://maven.pkg.github.com/ffebr/zio-quill-vertx`.

The first planned version is `0.1.0-alpha.1`, tagged `vertx-v0.1.0-alpha.1`.
A planned version is not evidence that packages have been published; verify
the Native Vert.x workflow and the fork's Packages page before consuming it.

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

## Consume a published version

```scala
resolvers += "ffebr Quill Vert.x" at
  "https://maven.pkg.github.com/ffebr/zio-quill-vertx"

libraryDependencies += "io.github.ffebr" %% "quill-vertx-zio" % "0.1.0-alpha.1"

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
