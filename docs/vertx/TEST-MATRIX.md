# PostgreSQL native coverage matrix

This matrix tracks source coverage in `quill-vertx-zio`. Shared specs are
reused as query-definition and expected-value traits; Vert.x specs provide
native setup and assertions for those definitions. Separately authored native
probes are identified as such. A passing native run is not proof of coverage
for every PostgreSQL JDBC-specific suite or of the closed application.

## Shared SQL corpus exercised through Vert.x

| Shared source | Native spec | Coverage relationship |
| --- | --- | --- |
| [`PeopleSpec.scala`](../../quill-test-kit/src/test/scala/zio/quill/context/sql/base/PeopleSpec.scala) | [`PeopleZioVertxSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/PeopleZioVertxSpec.scala) and [`PeopleZioVertxFilteredUpdatesSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/shared/write/PeopleZioVertxFilteredUpdatesSpec.scala) | Shared query definitions, fixtures and expected values cover People examples 1–12: reads/joins/composition/lifts/pagination and both filtered updates. The streaming case is a separate native query using the shared `peopleEntries` fixture. |
| [`PeopleReturningSpec.scala`](../../quill-test-kit/src/test/scala/zio/quill/context/sql/base/PeopleReturningSpec.scala) | [`PeopleZioVertxReturningSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/shared/write/PeopleZioVertxReturningSpec.scala) | Shared returning definitions and fixtures cover scalar/whole-record insert returning, returning-many insert, update returning, delete returning, and correlated returning query cases. |
| [`PeopleAggregationSpec.scala`](../../quill-test-kit/src/test/scala/zio/quill/context/sql/base/PeopleAggregationSpec.scala) | [`PeopleAggregationZioVertxSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/shared/read/PeopleAggregationZioVertxSpec.scala) | All six shared aggregation definitions and expected results. |
| [`BatchValuesSpec.scala`](../../quill-test-kit/src/test/scala/zio/quill/context/sql/base/BatchValuesSpec.scala) | [`BatchValuesZioVertxSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/shared/write/BatchValuesZioVertxSpec.scala) | Shared normal, returning and mixed batch insert definitions and expected rows. |
| [`BatchUpdateValuesSpec.scala`](../../quill-test-kit/src/test/scala/zio/quill/context/sql/base/BatchUpdateValuesSpec.scala) | [`BatchUpdateValuesZioVertxSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/shared/write/BatchUpdateValuesZioVertxSpec.scala) | Shared batch update definitions, including embedded/optional values, returning, and append cases. |
| [`CaseClassQuerySpec.scala`](../../quill-test-kit/src/test/scala/zio/quill/context/sql/base/CaseClassQuerySpec.scala) | [`CaseClassQueryZioVertxSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/shared/read/CaseClassQueryZioVertxSpec.scala) | Shared case-class projections, joins, inline records and union definitions/expectations. |
| [`DepartmentsSpec.scala`](../../quill-test-kit/src/test/scala/zio/quill/context/sql/base/DepartmentsSpec.scala) | [`DepartmentsZioVertxSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/shared/read/DepartmentsZioVertxSpec.scala) | Shared nested department query definitions and expected results. The native fixture drops and recreates the original `Department`, `Employee` and `Task` relations before seeding; it does not change the query or expected lists. These queries have no `ORDER BY`, so PostgreSQL does not guarantee list order. Accumulated column statistics changed the plan and reversed two results; `TRUNCATE` did not clear those statistics. The original JDBC spec reproduced the same two mismatches on the same database with matching SQL/EXPLAIN evidence. The original JDBC Departments runner passed both cases after canonical fixture restoration (`vertx-departments-jdbc-fresh.log`). |
| [`DistinctSpec.scala`](../../quill-test-kit/src/test/scala/zio/quill/context/sql/DistinctSpec.scala) | [`DistinctZioVertxSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/shared/read/DistinctZioVertxSpec.scala) | Shared distinct/distinct-on definitions, fixtures and expected values. |
| [`OnConflictSpec.scala`](../../quill-test-kit/src/test/scala/zio/quill/context/sql/base/OnConflictSpec.scala) | [`OnConflictZioVertxSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/shared/write/OnConflictZioVertxSpec.scala) | Shared PostgreSQL conflict definitions and expected results. |
| [`OptionQuerySpec.scala`](../../quill-test-kit/src/test/scala/zio/quill/context/sql/OptionQuerySpec.scala) | [`OptionQueryZioVertxSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/shared/read/OptionQueryZioVertxSpec.scala) | Shared option projection, fallback and optional join definitions/expectations. |
| [`ProductSpec.scala`](../../quill-test-kit/src/test/scala/zio/quill/context/sql/ProductSpec.scala) | [`ProductZioVertxSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/shared/read/ProductZioVertxSpec.scala) | Shared product fixture and query definitions, including insert-returning/cast cases. |
| [`EncodingSpec.scala`](../../quill-test-kit/src/test/scala/zio/quill/context/sql/EncodingSpec.scala) | [`NativeEncodingSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/shared/encoding/NativeEncodingSpec.scala) | Shared encoding definitions and expectations, with additional native temporal and mapped-codec assertions. |
| [`ArrayEncodingBaseSpec.scala`](../../quill-test-kit/src/test/scala/zio/quill/context/sql/encoding/ArrayEncodingBaseSpec.scala) | [`NativeArrayEncodingSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/shared/encoding/NativeArrayEncodingSpec.scala) | Shared base array fixture/types and deep comparison helper; Vert.x suite exercises broad scalar-array round trips and adds mapped, UUID, timestamp, predicate and null-array checks. |
| [`ArrayOpsSpec.scala`](../../quill-test-kit/src/test/scala/zio/quill/context/sql/base/ArrayOpsSpec.scala) | [`NativeArrayOpsSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/shared/encoding/NativeArrayOpsSpec.scala) | Shared array operation definitions and expected values for covered cases. |
| [`OptionalNestedSpec.scala`](../../quill-core/src/test/scala/zio/quill/context/encoding/OptionalNestedSpec.scala) | [`NativeOptionalNestedSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/shared/encoding/NativeOptionalNestedSpec.scala) | Shared optional nested definitions/expectations are exercised with native setup, including null and non-null nested records. |

Thus the older claim that shared corpus coverage is wholly absent is stale:
Vert.x specs now exercise 15 shared SQL/encoding suites, in addition to
`PeopleZioVertxSpec`. This does not imply every JDBC PostgreSQL suite has an
equivalent native runner.

## Native-only tests and remaining gaps

| Source / suite | Coverage and limits |
| --- | --- |
| [`VertxPostgresNativeSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/VertxPostgresNativeSpec.scala), [`QueryParitySpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/QueryParitySpec.scala), [`VertxPrepareSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/VertxPrepareSpec.scala) | Native CRUD, transaction failure/defect/interruption and nested reuse, stream cleanup/cancellation, pool reuse, SQL preparation, returning/batch probes, and native async probe. Cases written with separate native fixtures are proxy/contract coverage, not additional shared corpus cases. |
| [`VertxRuntimeSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/context/vertx/VertxRuntimeSpec.scala), [`VertxCodecNullSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/VertxCodecNullSpec.scala), [`VertxJsonSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/VertxJsonSpec.scala), [`VertxTemporalSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/VertxTemporalSpec.scala), [`VertxDialectSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/context/vertx/VertxDialectSpec.scala) | Future bridge, codec/null, JSON/JSONB and temporal values, and dialect-specific tests. These add native behavior coverage without changing shared QDSL expectations. |
| [`VertxFailureSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/VertxFailureSpec.scala) | Native PostgreSQL failures cover deferred SQLSTATE 23505 at commit with both writes rolled back, terminated backend transaction observed through an observer, throwing mapped decoder as typed query failure, and reuse of a one-connection pool after failure. |
| [`VertxTransportSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/VertxTransportSpec.scala) | Wrong-password authentication preserves PostgreSQL `PgException` SQLSTATE `28P01`. A pending `pg_sleep(30)` is observed in `pg_stat_activity`; the Vert.x event-loop heartbeat advances while the query fiber waits, then cancellation stops the server query and the pool remains reusable. |
| [`VertxCodecNullSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/VertxCodecNullSpec.scala) | Added real SQL checks for four typed null encoders, including `bytea` and SQL date/time/timestamp values. `java.util.Date` encoding uses epoch milliseconds, preserving JDBC-compatible millisecond precision for `java.sql.Date`, `Time` and `Timestamp` subclasses. |
| [`ComplexQuerySpec.scala`](../../quill-jdbc-test-postgres/src/test/scala/zio/quill/context/jdbc/postgres/ComplexQuerySpec.scala) and [`PrettyPrintingSpec.scala`](../../quill-jdbc-test-postgres/src/test/scala/zio/quill/context/jdbc/postgres/PrettyPrintingSpec.scala) | [`PostgresSpecificZioVertxSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/vertx/shared/postgres/PostgresSpecificZioVertxSpec.scala) now runs the complex join/nesting/infix query and both pretty-printing assertions against PostgreSQL with the original query/expected SQL. These targeted cases pass. |
| [`JdbcContextSpec.scala`](../../quill-jdbc-test-postgres/src/test/scala/zio/quill/context/jdbc/postgres/JdbcContextSpec.scala) | JDBC context suite has no direct native counterpart. Compare individual pool, transaction, stream and cancellation contracts against Vert.x-specific assertions rather than counting it as suite parity. |
| Synchronous `probe` / JDBC prepare APIs | Synchronous probe is unsupported by the native context; Vert.x provides native asynchronous probe/prepare paths. JDBC-specific invalid-decoder and raw statement behavior has not been established as full suite parity. |
| Translation and dialect lowering | [`VertxTranslateSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/context/vertx/VertxTranslateSpec.scala) covers option macro translation for `String` and `List[String]`, lift diagnostics and lexical placeholder substitution across quoted strings, comments, nested comments and dollar quotes. [`VertxDialectSpec.scala`](../../quill-vertx-zio/src/test/scala/zio/quill/context/vertx/VertxDialectSpec.scala) verifies scalar concatenation remains `SELECT` after action-only lowering; correlated update/delete capture and `returningMany` also pass. |
| Closed application | Its source and workload are unavailable in this repository. No library suite establishes successful app compilation or workload behavior after wiring/JDBC seam migration. |

## Original-tree integrity audit

Before accepting the migration, check that the original shared, PostgreSQL JDBC,
and JDBC ZIO test trees contain no edits. These commands are an audit recipe:

```bash
git diff --exit-code HEAD -- \
  quill-test-kit/src/test \
  quill-jdbc-test-postgres/src/test \
  quill-jdbc-zio/src/test
git status --short -- \
  quill-test-kit/src/test \
  quill-jdbc-test-postgres/src/test \
  quill-jdbc-zio/src/test
```

The first command checks tracked changes against `HEAD`; inspect the second for
any output, including untracked files in those trees.

## Validation state

The final isolated native run in
`/workspace/.setup/quill/vertx-final-isolated-validation.log` passed 166 tests
across 29 suites on both Scala 2.13.16 and 2.12.20, with zero aborted, failed,
canceled, ignored or pending tests; exit code 0. Two additional Departments runs each passed on Scala 2.12 after the fixture
began restoring the three original relations before seeding; the full suites
on both Scala versions also include these cases. `packageBin`, compile, runtime
classpath/tree and formatting checks completed for both versions across
`vertx-final-validation.log` and `vertx-final-isolated-validation.log`; no
package was published. Dependency classpaths and trees contain no
`quill-jdbc`, PostgreSQL JDBC driver (`org.postgresql`) or Vert.x JDBC client.

The earlier PostgreSQL JDBC run in
`/workspace/.setup/quill/vertx-full-jdbc-reference.log` reported 150 passed
across 19 suites on each Scala version. The original JDBC Departments runner
also passed both cases against the fresh isolated schema after the plan/order
diagnosis (`vertx-departments-jdbc-fresh.log`, exit 0). The matching native and original JDBC
queries have no `ORDER BY`, so no result order is guaranteed by PostgreSQL;
query bodies and expected lists remain unchanged. Other recorded original
regressions: base 2,784 passed, 2 ignored, 1 pending; PostgreSQL/H2/SQLite JDBC
ZIO 100 passed across 18 suites, 8 ignored, zero failures. Other JDBC database
branches, broader connection-loss/timeout/fault conditions, and the
closed-application workload remain unrun. See [PROGRESS.md](PROGRESS.md) for
integration status.
