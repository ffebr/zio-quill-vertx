# PostgreSQL optional updates discovered by the Conduit application

The Conduit dual-backend application exposed an incompatibility in
`0.1.0-alpha.1`: a lifted `None` in an ordinary Quill `getOrElse` update can
produce `CASE WHEN $1 IS NOT NULL THEN $2 ELSE column END`. PostgreSQL cannot
infer the first parameter's type through the native prepare protocol and
returns SQLSTATE `42P18`. The JDBC executor supplies parameter types and accepts
the same QDSL.

The native PostgreSQL dialect now lowers the normalized null-fallback expression
to `COALESCE($1, column)`. PostgreSQL infers the parameter type from the column.
Application QDSL and the original API assertions stay unchanged. The rewrite
only accepts stable operands (lifts, identifiers, constants and their properties)
and preserves ordinary CASE expressions and potentially volatile SQL expressions.

Regression coverage lives in `VertxDialectSpec`: real PostgreSQL projections and
updates with `Some`/`None`, nullable `orElse`, an ordinary conditional, and a
mirror-only check that volatile expressions retain CASE evaluation. This change
does not add a blocking operation or a JDBC fallback.

The existing published alpha remains immutable. Consumers needing this fix must
use the corrected source revision until another version is released. The
Conduit application pins both executors to that same source revision.

The PostgreSQL-focused native suite passed on Scala 2.13.16: 178 tests in 30
suites, with no failed, canceled, ignored or pending tests. This includes both
new regressions. Other database/Scala matrix entries were not rerun for this
PostgreSQL-only change; no new package release is implied by these results.
