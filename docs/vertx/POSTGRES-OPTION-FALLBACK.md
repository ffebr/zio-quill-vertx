# PostgreSQL lifted parameters

The Conduit application exposed a bug in `0.1.0-alpha.1`: a lifted `None`
can produce `CASE WHEN $1 IS NOT NULL THEN $2 ELSE column END`. PostgreSQL
cannot infer the first parameter's type during native preparation and returns
SQLSTATE `42P18`. The same problem affects ordinary `isEmpty` and `forall`
queries. Rewriting a particular update to `COALESCE` does not solve it.

The native encoders now preserve Vert.x typed-null metadata, including through
optional and mapped encoders. The binding layer uses it to annotate parameters
used in null predicates before preparing the statement. Other scalar uses keep
target-column inference, including PostgreSQL enum comparisons. The ordinary
Quill CASE expressions and their evaluation semantics remain unchanged.

PostgreSQL has two additional preparation constraints:

- Vert.x 4.5.36's fallback class lookup omits decimal types. The binding layer
  annotates decimal parameters as `numeric`.
- A projected array parameter can otherwise become `text` before values are
  bound. Supported arrays receive their PostgreSQL array type.

Streaming prepares a cursor before passing its tuple and uses the same binding
layer, so NULL predicates are typed during preparation as well. JSON and JSONB parameters carry
separate descriptors and bind as JSON text cast to the correct database type.
SQL NULL remains distinct from JSON's `null`, including for scalar JSON strings.
The binding layer uses the same SQL marker scanner as diagnostics; markers
inside quoted strings, comments and dollar-quoted bodies are preserved.

The public one-argument `VertxEncoder` constructor remains available. A custom
encoder used with optional values should describe the native representation it
appends, for example:

```scala
VertxEncoder[CustomerCode] { (index, value, tuple, _) =>
  tuple.addString(value.value)
}.withNullValue(io.vertx.sqlclient.data.NullValue.String)
```

Use the descriptor of the encoded representation, rather than the domain class.
Mapped encoders inherit the underlying descriptor. Custom encoders without a
descriptor retain their previous untyped-null behavior.

Live regression coverage includes Some/None queries and updates, mapped and
custom encoders, decimal and array bindings, cursor streams, JSON/JSONB,
batches, and reusable prepared queries. The unchanged shared Quill query
corpus remains part of the native suite. No JDBC fallback or blocking database
operation is introduced.

The old alpha remains immutable. The release workflow gates publication on
PostgreSQL, MySQL, MariaDB and DB2 tests with Scala 2.12 and 2.13. The separate
published-consumer workflow then builds the Conduit application against the
registry artifacts and runs its original conformance tests.
