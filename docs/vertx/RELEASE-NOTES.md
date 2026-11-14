Native Vert.x SQL execution for Quill and ZIO, published from ffebr/zio-quill-vertx.

- PostgreSQL, MySQL/MariaDB and DB2 native clients; JDBC execution/fallback is absent.
- Shared scoped pools, transactions, batch/returning, prepared execution and bounded streams.
- PostgreSQL arrays/JSON and an explicitly corrected, version-pinned native DB2 client.
- Scala 2.12.20 and 2.13.16, gated by live PostgreSQL/MySQL/MariaDB/DB2 CI jobs.
- Connection/context/layer wiring and custom JDBC codecs require migration; preserve QDSL query bodies and recompile.
- Typed optional and mapped PostgreSQL binds, decimal/array preparation, cursor binds, and distinct JSON/JSONB parameters.
- Registry consumer CI builds the dual-backend Conduit application and runs its original conformance suite.
- This is an alpha for pilot integration. Closed-application compatibility is not yet validated.

Maven registry: https://maven.pkg.github.com/ffebr/zio-quill-vertx

Coordinates: `io.github.ffebr:quill-vertx-zio_2.12:<version>` or
`io.github.ffebr:quill-vertx-zio_2.13:<version>`.

The matching Quill runtime dependencies and corrected Java DB2 client are
published to the same fork registry. See `docs/vertx/RELEASING.md` for
authentication and usage. This release does not publish to Maven Central,
Sonatype, npm, or an upstream repository.
