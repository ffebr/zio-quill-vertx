# Native SQL validation checkpoint

The multi-SQL change shares execution, scoped connections, transactions and
streaming between native PostgreSQL, MySQL/MariaDB and DB2 contexts.
Each context retains its own dialect, codecs and cancellation behavior.

## Preserved baseline

- All 29 pre-existing PostgreSQL test files match the snapshot taken before
  extraction byte for byte, including after module formatting.
- The original shared and JDBC test trees are unchanged.
- Original MySQL JDBC/ZIO reference: 26 passed, 4 originally ignored, 5 suites.
  This uses a separate `quill_test` database initialized with the original schema;
  it is reference evidence rather than native-client validation.

## Failures found and corrected

| Failure | Correction | Regression coverage |
| --- | --- | --- |
| MySQL returned a closed pooled connection after interruption | Await the internal socket close event, which runs the SQL pool removal handler, before releasing the lease | Pending-query interruption and replacement of the physical session, pool size one |
| MariaDB 11.4 rejected single-table DELETE aliases | Generate the explicit DELETE target-alias syntax accepted by MySQL and MariaDB | Original native CRUD plus portable SQL regression |
| DB2 rejected the unchanged password containing `!` | Repack the pinned native client with CP500 matching its declared CCSID 500 | The DB2 suites authenticate with the original harness password |
| DB2 rejected tuple projection aliases `_1`, `_2` | Quote generated aliases beginning with underscore | Original shared People differences query and assertion |
| DB2 treated numeric conversions as boolean expressions | Lower numeric conversions before boolean normalization and preserve a scalar SELECT; explicitly declare DB2 string input before the numeric cast | Scalar, projected and action conversions in portable tests; live DB2 CRUD scalar query |
| DB2 cancellation setup did not create a lock wait | Commit the fixture row first, then hold an UPDATE lock; currently-committed reads can skip an uncommitted INSERT | The original pending-state and prompt-interruption assertions remain |
| MySQL diagnostics misread hash comments and arithmetic double minus | Apply MySQL comment and escaped-string rules | Portable diagnostics regressions; bound execution is independent |

## DB2 dependency correction

`quill-vertx-db2-client` is an explicit Java artifact built from the official
Vert.x 4.5.36 binary. The build verifies upstream SHA-256
`0e42c8d46602908fb2269ebdcc9ffd5cd1496cc39666cbde24ba66460261760e`.
Archive comparison confirmed every original entry is retained with identical
bytes except for the manifest and `CCSIDConstants.class`. The automatic module
name is preserved. Apache license text and an attribution/change notice are
added. The original dependency is provided at build time; the native module
consumes the corrected artifact. The Java artifact has no Scala dependencies.

An independent native Java program reproduced the authentication failure.
A disposable account confirmed that an alphanumeric password works, adding `!`
fails, and the CP500 correction makes the same password work. Prompted Db2 CLP
authentication over TCP succeeded throughout. No server password or
authentication configuration was changed. The temporary account was removed.
See the [artifact README](../../quill-vertx-db2-client/README.md).

## Current full-module matrix

SQL Server has been removed at the user's request, including its context,
client, pool constructors, three live suites, three portable tests and local
harnesses. The previous 242-test checkpoint included those three portable
cases. All remaining tests and assertions retain their previous behavior.

JDK: Temurin 17.0.17. Vert.x: 4.5.36. ZIO: 2.1.20.

| Tests | Scala 2.13.16 | Scala 2.12.20 |
| --- | ---: | ---: |
| PostgreSQL 16.11, existing native corpus | 166 | 166 |
| MySQL 8.4.7, native + original shared corpus | 21 | 21 |
| MariaDB 11.4.5, native + original shared corpus | 21 | 21 |
| DB2 LUW 11.5.8.0, native + original shared corpus | 21 | 21 |
| Portable SQL dialect/diagnostic tests | 10 | 10 |
| **Total passed / suites** | **239 / 41** | **239 / 41** |

Both runs completed with zero failed, aborted, canceled, ignored or pending
tests. The module's scalafmt check passed.  The native runtime contains exactly one corrected
DB2 artifact and no original DB2 jar, JDBC module, JDBC driver, MSSQL client or Oracle client.

The tests cover shared People queries and scalar/nullable/binary encoding,
CRUD, mapped types, UUID/decimal/time, exact generated keys and batch order,
transactions including typed failure/defect/interruption, bounded cursor reads
and early/failing consumers, prepared reuse, diagnostics and connection reuse.
MySQL/MariaDB also reuse original generated-returning cases. DB2 includes
whole-record FINAL TABLE and identity-only inserts. These selected cases do
not establish exhaustive SQL feature or closed-application compatibility.

Retained workspace logs under `/workspace/.setup/quill/`:

- `drop-sqlserver-validation.log`: full `quill-vertx-zio/test` passed on both
  Scala versions (239 tests each); the subsequent format check found one
  whitespace-only issue in the edited portable suite.
- `drop-sqlserver-artifacts.log`: formatting correction/check, local package/POM
  and runtime dependency inspection after SQL Server removal.
- `multi-sql-final-scala213.log`, `multi-sql-final-scala212.log`: the previous
  successful 242-test checkpoint, before scope reduction.
- `multi-sql-original-mysql-reference.log`: original JDBC reference result.
- `multi-sql-artifact-validation.log`, `multi-sql-final-artifact-boundary.log`:
  local package/POM creation and runtime dependency inspection.
- `multi-sql-sqlserver-network-check.log`: fresh official download redirect
  followed by HTTP 403.

Earlier failing logs remain in the workspace. They recorded the failures above
and a Scala 2.12 quoted-interpolation parser error, corrected with ordinary
string concatenation. No test expectations were changed to accept failures.

Reproduce individual live profiles using:

```bash
./tools/vertx/test.sh
./tools/vertx/test-sql.sh mysql
./tools/vertx/test-sql.sh mariadb
./tools/vertx/test-sql.sh db2
```

Each script starts its labelled local harness and exports connection settings.
The combined full module uses those same settings. Repeat after `++2.12.20`
and module clean for the second Scala version:

```text
quill-vertx-zio/test
```

`test-sql.sh all` now starts only PostgreSQL, MySQL, MariaDB and DB2 and runs
the full module suite, including all remaining portable tests.

## Current scope

SQL Server was removed from the native context API, dependency graph, pool
constructors, integration runners, portable diagnostics and local harnesses.
The previous HTTP 403 download failure is historical and no longer a gate;
no domain addition is needed for the remaining supported backends. The original
Quill JDBC SQL Server backend and its tests remain outside this native change.

Oracle is excluded: Vert.x 4.5.36 uses JDBC internally for that backend. The
native runtime must retain its JDBC-free dependency boundary. Closed-app
acceptance also remains external because the application is unavailable.

No packages, Git changes or environment configuration were published.
