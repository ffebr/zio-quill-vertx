# Development checkpoint: native PostgreSQL backend

The later multi-SQL checkpoint is in
[MULTI-SQL-VALIDATION.md](MULTI-SQL-VALIDATION.md): 239 tests pass on each Scala
version across PostgreSQL, MySQL, MariaDB, DB2 and portable dialect checks.
SQL Server has since been removed from the native backend at the user's request.
The post-removal full module passes on Scala 2.12 and 2.13; no network change
is required for that excluded backend.
The PostgreSQL and quality checkpoints below retain their historical scope.

`quill-vertx-zio` implements PostgreSQL execution through the native Vert.x SQL
client with ZIO. The QDSL compatibility target is unchanged; context/pool/layers,
JDBC codec callbacks and raw JDBC integration seams may migrate as agreed.
No environment versions or packages have been published.

## Implemented and verified

- Separate module with native Tuple/Row codecs, PostgreSQL positional binding,
  query/action/batch/returning execution and JSON/JSONB support.
- Scoped native pool/connection ownership, transaction nesting/rollback,
  bounded cursor streaming, SQL cancellation and late acquisition cleanup.
- Native reusable prepared operations and async SQL probe; synchronous
  diagnostic translation with pretty printing and safe lift rendering.
- Native dialect regressions for scalar/filtered-action numeric casts and
  correlated UPDATE/DELETE RETURNING aliases, including nested binder capture.
- Pinned PostgreSQL 16.11 harness with isolated native/reference databases.
- Shared SQL/encoding corpus runners plus native resource/failure tests;
  original shared, PostgreSQL JDBC and JDBC ZIO test sources are unchanged.

Final native cross-build: **166 passed in 29 suites on Scala 2.13.16 and
166 passed in 29 suites on Scala 2.12.20**, JDK 17; no failures, ignored,
canceled, aborted or pending tests. Both local module JARs were built.
Dependency audits contain no JDBC backend, PostgreSQL JDBC driver or Vert.x
JDBC client. Module formatting and shell/diff checks pass.

Original regressions: **2784 base tests passed** (2 existing ignored, 1 pending)
and **100 PostgreSQL/H2/SQLite JDBC ZIO tests passed** (8 existing ignored).
The full original PostgreSQL JDBC suite also passed **150 tests in 19 suites
on each Scala version**, without skips. The separate ZIO PostgreSQL reference
checkpoint passed 49 tests.

The failure suite confirms deferred COMMIT failure with SQLSTATE 23505, actual
server-side backend termination and throwing decoder behavior, followed by
reuse of a single-connection pool. Authentication SQLSTATE 28P01, heartbeat
on a single Vert.x event loop during server-observed pg_sleep, typed null binds
and util.Date subtype millisecond behavior also pass. Cancellation tests observe the query on
PostgreSQL before interruption and confirm it disappears afterward.

The Departments fixture restores the exact original table schema between runs:
its unsorted shared assertions otherwise also fail on JDBC after PostgreSQL
changes join plans with accumulated statistics. Shared queries and expected
lists remain unchanged; no general ordering guarantee is added.

## Acceptance boundary

The library implementation and recorded native matrix pass. Full closed-app
compatibility remains unvalidated: application sources and workload are not
available. Other database regressions, all JDBC-specific cases and the wider
fault matrix are not claimed as completed. The backlog retains these coverage
gaps and the external application gate; passing library tests do not remove it.

See [VALIDATION.md](VALIDATION.md) for versions, commands, counts and retained
failure history; [TEST-MATRIX.md](TEST-MATRIX.md) for coverage;
[BACKLOG.md](BACKLOG.md) for remaining acceptance items; and the
[module README](../../quill-vertx-zio/README.md) for migration examples.

## Code quality pass

The work in [QUALITY-BACKLOG.md](QUALITY-BACKLOG.md) is complete. Shared
execution helpers, simpler transaction finalization, shared Future/codec
conversion and leaner diagnostic rendering preserve the public Scala API.
The independent Luna review found no actionable regression. Both Scala versions
again pass 166 tests across 29 suites; all native test files are byte-for-byte
unchanged. See [QUALITY-VALIDATION.md](QUALITY-VALIDATION.md) for this run.
