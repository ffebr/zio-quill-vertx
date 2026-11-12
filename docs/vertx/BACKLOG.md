# Бэклог native PostgreSQL backend

Цель: отдельный native Vert.x ZIO backend для PostgreSQL в предлагаемом модуле `quill-vertx-zio`. Сохраняются существующие Quill core/SQL macros, QDSL expressions и shared expected values; JDBC driver/fallback запрещены внутри нового backend. Backend wiring и JDBC-specific integration seams разрешено мигрировать. Решение и границы: [ADR-001.md](ADR-001.md), [PLAN.md](PLAN.md), [COMPATIBILITY.md](COMPATIBILITY.md).

Статусы: DONE — реализация и указанная приёмка подтверждены; READY — можно выдавать; BLOCKED — зависит от указанной задачи; REVIEW — остаются перечисленные coverage, migration или audit checks; EXTERNAL — действие пользователя. Native cross-build завершён: 166 tests / 29 suites прошли на каждой поддерживаемой Scala версии; dependency/runtime/JAR audit и форматирование завершены. DONE у native задач не означает полный parity по другим JDBC БД или app validation.

## Состояние аудита

| ID | Работа | Статус | Результат |
| --- | --- | --- | --- |
| A01 | Инвентаризация публичного JDBC/ZIO API и QDSL тестов | DONE | API и граница миграции в COMPATIBILITY.md |
| A02 | Архитектура native PostgreSQL backend | DONE | Решение зафиксировано в ADR-001.md |
| A03 | Shared и PostgreSQL test coverage | DONE | T02, T12 и T13 учитывают существующие runners и пробелы |
| A04 | Свежий baseline текущей ревизии | DONE | VALIDATION.md; это base aggregate, не native/PostgreSQL validation |

## Задачи

### T01 — PostgreSQL JDBC reference harness [DONE; depends A01–A03]

Владелец: infrastructure. Файлы: только новые PostgreSQL-specific helpers/fixtures; общий build изменяет оркестратор.

- Выбрать и зафиксировать версию PostgreSQL для тестов, подготовить отдельную БД/schema с существующей PostgreSQL схемой и воспроизводимым reset/seed.
- Запускать оригинальные JDBC/ZIO PostgreSQL suites как неизменённый behavioral reference. Native и JDBC сравнения выполняются на раздельно reset schemas с одинаковыми seed.
- Сохранить версии среды, лог, XML, число тестов и исходные падения. Ненулевой test count обязателен; compile-only не завершает задачу.
- Не менять исходные assertions, shared expressions или expected values.
- Original PostgreSQL reference run: 150 tests across 19 suites on both Scala 2.13.16 and 2.12.20, zero failures/ignored/pending. After the Departments planner diagnosis, the original JDBC Departments cases were rerun against the fresh isolated database: 2 passed, exit 0.

### T02 — Shared QDSL corpus и migration inventory [DONE; app-specific usage remains unknown]

Владелец: compatibility tests. Файлы: новые test manifest/mapping и migration inventory; shared тесты не менять.

- Составить manifest PostgreSQL suites, переиспользуемых query definitions и expected values из `quill-test-kit`; связать их с текущими JDBC PostgreSQL runners.
- Составить список backend seams, которые могут измениться: imports, context creation, ZIO environment/layers/config, custom JDBC codecs, raw prepare/probe/session и прямые JDBC обращения.
- Отдельно записать, что исходники приложения недоступны агентам. Не создавать фиктивный «старый app source» из примеров и не требовать byte-for-byte app corpus.
- Приёмка: таблица suite → shared expression/expectation → JDBC runner → план native runner, плюс список пока неизвестных app-specific seams. Ни одна query expression или expectation не изменена.
- Матрица в [TEST-MATRIX.md](TEST-MATRIX.md) фиксирует native runner, общие definitions/expectations, отдельные proxy cases и JDBC/PostgreSQL gaps. App source unavailable seams записаны как unknown.

### T03 — Native architecture contracts [DONE; направление принято в ADR-001]

Владелец: оркестратор с независимым reviewer. Файлы: уточнение ADR и внутренние interface specifications.

- Зафиксировать отдельный `quill-vertx-zio`, использование Quill core/SQL macros, native pool/context/layers, Future-to-ZIO bridge, session/ownership, binding/row extraction и error model.
- Разделить неизменяемый контракт QDSL/наблюдаемых PostgreSQL results и разрешённую migration boundary: imports, context/wiring, layers/environment, native codecs и JDBC escape hatches.
- Native API не реализует фиктивные JDBC interfaces. Не извлекать credentials из произвольного `DataSource`; native config/pool задаётся явно.
- Указать поддерживаемые Scala/JDK/Vert.x/PostgreSQL версии на основании текущего build и официальных API. Приёмка: frozen interfaces и проверяемая architecture note; отсутствие обещаний JDBC source compatibility.

### T04 — Build/module isolation [DONE]

Владелец: оркестратор; согласованные build edits интегрирует оркестратор.

- Добавить новый отдельный backend module с проектным именем `quill-vertx-zio`, использующий `quill-core`, `quill-sql`, ZIO и совместимую линию Vert.x.
- Не подключать `quill-jdbc`, pgjdbc или Vert.x JDBC client к native module. Оригинальный `quill-jdbc-zio` и все JDBC DB modules остаются reference/regression targets.
- JSON wrappers `zio.quill.JsonValue`/`JsonbValue` сейчас находятся в `quill-jdbc-zio`, хотя являются data-only QDSL types. Если native corpus требует их без JDBC dependency, root координирует выделение в лёгкий shared module с теми же FQCN и неизменным поведением; обе backend closures используют ровно одну копию. Альтернативный packaging design должен исключить зависимость native module от всего JDBC backend и FQCN duplication.
- Зафиксировать cross-build и Vert.x BOM по поддерживаемым версиям репозитория. Build/source edits shared types выполняет только root; JDBC behavior reference и assertions сохраняются.
- Приёмка: module compile по выбранным версиям, dependency/runtime closure audit, native module без JDBC execution dependencies; JSON data types доступны без зависимости на старый JDBC runtime и не задвоены.

### T05 — Native runtime and resource ownership [DONE; broader fault coverage remains in T13]

Владелец: Luna runtime; файлы нового native module.

- Реализовать exactly-once Vert.x Future bridge, synchronous throw handling, typed error mapping без потери cause, и раздельные failure/defect/interruption каналы.
- Определить ownership ZIO scope для pool/connection, acquire/close races, late acquisition после interruption, borrowed vs owned resources.
- SQL и Future ожидание не блокируют Vert.x event loop; никакие синхронные JDBC APIs не вводятся.
- Приёмка: deterministic bridge/resource failure tests, acquire/cancel race tests, native select и доказанный pool close.

### T06 — Native context, layers and configuration [DONE]

Владелец: Luna API/runtime.

- Предоставить native PostgreSQL context/layer/config и ZIO environment, переиспользуя Quill context verbs/translation abstractions, а не JDBC transport types.
- Описать migration examples для старых imports, context constructors, `DataSource`/`Connection` environments и app pool wiring к Vert.x pool/layer. Native connection/config задаются явно.
- Сохранять ZIO/ZStream usage там, где применимо; допустимые environment/error type изменения явно документировать.
- Приёмка: context-bound shared QDSL expressions компилируются после повторной macro expansion с native context; query bodies не переписаны.

### T07 — Native codecs and row/parameter adapter [DONE; app-specific JDBC codec migration remains external]

Владелец: Luna codecs; тесты в native module.

- Scalars/null/options/mapped logical types, decimals/overflow, bytea/UUID, arrays/null elements, dates/times and PostgreSQL JSON.
- Определить native encoder/decoder contract. Инвентаризировать JDBC custom callbacks и дать явные migration examples; не эмулировать `PreparedStatement`, JDBC `ResultSet`, `PGobject` или `createArrayOf` как transport contract.
- Обеспечить `JsonValue`/`JsonbValue` для обоих backend test closures через shared data-only packaging из T04, сохраняя package/FQCN и value semantics. Не добавлять duplicate definitions в native module.
- Typed null encoders cover `bytea` and SQL date/time/timestamp. `java.util.Date` uses epoch milliseconds so `java.sql.Date`/`Time` subclasses and Timestamp millisecond truncation preserve JDBC behavior.
- Сравнить round-trip behavior с JDBC reference по тестовым таблицам/fixtures и негативным кейсам; не менять consumer QDSL или expected values.
- Приёмка: tabular encode/decode parity на выбранных Scala версиях, документированные пределы precision/timezone.

### T08 — Query/action/returning/batch execution [DONE]

Владелец: Luna execution; native executor и новые runners.

- Поддержать query/single/cardinality, affected rows, returning/returningGenerated/returningMany, batch grouping/order/counts.
- Использовать native prepared binding. Если native placeholder протокол отличается, преобразование делается на backend layer; QDSL/quotation expressions остаются неизменными. Учесть PostgreSQL strings, comments, dollar quotes и operators.
- Приёмка: те же shared expressions и expected values на реальной БД; проверить invalid SQL, constraint, empty batch и partial failure без изменения golden assertions.

### T09 — Transaction semantics [REVIEW]

Владелец: Luna transactions.

- Native transaction owner и fiber-local/session propagation; nested reuse по наблюдаемой reference semantics без добавления неявных savepoints.
- Проверить commit/rollback на typed failure, defect, interruption; stream в transaction, child fibers, commit/rollback failure и connection loss.
- Определить поведение pending requests при interruption; native transaction всегда использует одну native session.
- Приёмка: same side effects as JDBC reference, no committed writes after rollback, resource returned/disposed on every exit.

### T10 — Streaming and fetch behavior [REVIEW]

Владелец: Luna streaming; native stream executor/tests.

- Native row stream с bounded-memory/backpressure, fetch policy, ownership cursor/connection вне и внутри transaction.
- Проверить early take, empty result, consumer failure, interruption до acquire/во время query/между fetch, late callbacks и cleanup failure.
- Приёмка: pull/resource instrumentation, повторное использование pool max=1, server-visible cancellation/cleanup и прежние streaming expectations. Не подменять stream полной materialization.

### T11 — Native prepare/probe/translate [DONE; client-specific seam inventory remains in T15]

Владелец: Luna API/execution.

- Реализовать native/Quill prepare abstraction, probe/translate/prepareParams только для необходимых сценариев. Это не возвращает JDBC `PreparedStatement`/`Connection`/`ResultSet` API.
- App-specific JDBC raw prepare/session/vendor usage неизвестен без клиентского usage inventory и остаётся в T15. Поддерживаемые native bind/raw SQL APIs документированы; синхронные JDBC методы не эмулируются.
- Native translation options и placeholder lexer покрыты `VertxTranslateSpec` (включая `String`/`List[String]` option macros и lexical quoting/comment cases); проверить их в clean cross-build. Ordinary SQL expressions не переписываются.
- Приёмка native API: новые native tests на поддерживаемые prepare/probe/translate сценарии. Полный app-specific список migrated/unsupported JDBC escape hatches остаётся вне этой завершённой native реализации.

### T12 — PostgreSQL QDSL parity matrix [REVIEW]

Владелец: Luna conformance; production code не менять при разборе расхождений.

- Использовать manifest T02 и текущие query/expected fixtures: aggregation/departments/options/distinct/batch/onConflict/query-result/encoding/arrays, ComplexQuery and PrettyPrinting exact source cases, плюс JSON/returning/prepare/transaction/stream.
- Для native specs менять только backend wiring/effect runner/codec registration. Точные query expression bodies и expected values остаются общими с JDBC suites; quotations перекомпилируются в новом context.
- JDBC/native запускаются на отдельно reset schemas с одинаковыми seed; сравниваются результаты и side effects, включая rollback/timezone. Расхождения исследуются, expectations не переписываются.
- Приёмка: manifest suite coverage, fresh reports, no new skipped tests, diff/hash audit подтверждает неизменность исходных query fixtures и assertions.

### T13 — Failure, cancellation and event-loop tests [REVIEW]

Владелец: независимый tests/review agent.

- Covered: deferred SQLSTATE 23505 commit failure and rollback, terminated backend transaction, decoder failure, wrong-password auth SQLSTATE 28P01, `pg_sleep` cancellation after observing it in `pg_stat_activity`, event-loop heartbeat progress and one-connection pool reuse.
- Remaining: broader disconnect/timeout, cancel during acquire/fetch/commit races, scope closure and event-loop stress/affinity cases.
- Отделить deterministic mocked bridge races от real server assertions. Проверить event-loop heartbeat/affinity без хрупких benchmark thresholds.
- Приёмка: завершённые/canceled запросы не остаются на сервере по заявленной cancellation semantics, нет утечек/double completion, event loop не блокируется.

### T14 — Release and dependency audit [REVIEW; other database branches remain UNRUN]

Владелец: оркестратор + независимый reviewer.

- Final dependency audit on both Scala versions confirms no `quill-jdbc`, pgjdbc or Vert.x JDBC client in the native closure; native execution uses Vert.x pool, with no fallback.
- Проверить QDSL/expected fixtures не менялись и исходный JDBC reference/tests остались нетронутыми. Original PostgreSQL JDBC suites passed 150 tests / 19 suites on both supported Scala versions; fresh isolated original JDBC Departments cases passed 2/2. Other H2/SQLite/MySQL/SQL Server/Oracle branches remain unrun or only covered by prior recorded regressions; do not describe them as part of this PostgreSQL validation.
- Отчёт по COMPATIBILITY: команды/versions/counts/logs и migration inventory для wiring/JDBC seams. Недоступные БД указываются UNRUN и не выдаются за passed.
- Приёмка: все обязательные QDSL/native tests зелёные, dependency isolation подтверждён, никакого ослабления тестов или изменения query expressions.

### T15 — Application migration validation [EXTERNAL; depends T14]

Владелец: пользователь; агентам исходники black box недоступны.

- По migration inventory изменить только разрешённую integration boundary: imports/context/pool/layers/config и используемые JDBC codecs/escape hatches. QDSL query expressions остаются без изменений.
- Перекомпилировать исходники с новым backend и выполнить workload. Записать список необходимых integration changes и результаты без передачи закрытого кода/секретов.
- Приёмка: пользователь подтверждает отсутствие QDSL rewrites и совпадение требуемых данных/side effects. Без результата статус конкретного приложения `NOT VALIDATED`; библиотечные тесты не подтверждают app-specific migration.

## Completed validation and remaining review

- Final isolated native run in `/workspace/.setup/quill/vertx-final-isolated-validation.log`: Scala 2.13.16 and 2.12.20 each completed 29 suites with 166 passed, 0 aborted/failed/canceled/ignored/pending; exit 0. The full suites each include the Departments cases; two additional Departments runs each passed on Scala 2.12 after fixture isolation.
- `packageBin`, compile/runtime classpath/tree and formatting checks passed on both Scala versions across `/workspace/.setup/quill/vertx-final-validation.log` and `vertx-final-isolated-validation.log`; no package was published.
- Final dependency audit found no `quill-jdbc`, `org.postgresql` JDBC driver or Vert.x JDBC client in either native Scala closure.
- PostgreSQL JDBC reference in `/workspace/.setup/quill/vertx-full-jdbc-reference.log`: 150 passed across 19 suites on both Scala versions, zero failures/ignored/pending. Fresh isolated original JDBC Departments run in `/workspace/.setup/quill/vertx-departments-jdbc-fresh.log`: 2 passed, exit 0. Other recorded original regressions: base 2,784 passed, 2 ignored, 1 pending; PostgreSQL/H2/SQLite JDBC ZIO 100 passed across 18 suites, 8 ignored, zero failures.
- `VertxTransportSpec` verifies auth SQLSTATE 28P01, server-observed cancellation of pending `pg_sleep`, event-loop heartbeat progress, and pool reuse. `VertxCodecNullSpec` checks four additional typed-null encoder cases; `java.util.Date` preserves JDBC millisecond behavior.
- T01/T02 reference and repository-source inventory are accepted. Client-specific seam usage, broader fault tests, other JDBC database branches and application migration/workload validation remain outstanding.

## Параллельность

До native runtime можно параллелить T01 и T02; T03 фиксирует interfaces до T04/T05. После T06 codecs и execution развиваются по зависимостям; transaction предшествует streaming. T12/T13 независимы от product implementation и проверяются отдельным владельцем. В общем checkout один владелец на файл и один sbt slot; оригинальный JDBC reference и shared expected fixtures не редактируются.

## Качество кода

Отдельный этап Q00–Q06 завершён: [QUALITY-BACKLOG.md](QUALITY-BACKLOG.md),
[QUALITY-VALIDATION.md](QUALITY-VALIDATION.md). Его приёмка не закрывает
остающиеся функциональные и app-specific проверки выше.
