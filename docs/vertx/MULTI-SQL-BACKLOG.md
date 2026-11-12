# Native Vert.x SQL: обобщение backend

Цель: общее ZIO/Vert.x исполнение и отдельные реальные SQL backend для
PostgreSQL, MySQL/MariaDB и DB2. Публичный PostgreSQL context,
его QDSL и существующие 166 native tests сохраняются. Общий интерфейс Pool
сам по себе не считается доказательством совместимости.

## Граница реализации

Native wire clients Vert.x 4.5.36 используются для PG/MySQL/DB2. SQL Server исключён по запросу пользователя.
Oracle client этой версии использует `ojdbc11`, `OracleDataSource` и
`executeBlocking`; он исключён действующим требованием пользователя убрать
JDBC driver/execution/fallback. H2/SQLite не получают фиктивный native backend.
Собственный Quill DB2 dialect реализуется отдельно: в исходном Quill его нет.

| ID | Работа | Владелец / файлы | Статус |
| --- | --- | --- | --- |
| M00 | Аудит API, native protocols и JDBC closure | Luna audit; официальный source/POM cache | DONE |
| M01 | Общее ZioVertxContext ядро, сохранение PG public context | Luna core; новый `context/vertx/ZioVertxContext.scala`, существующий `PostgresZioVertxContext.scala` | DONE |
| M02 | Общие scalar codecs и vendor overrides; PG arrays/JSON отдельно | Luna codecs; encoder/decoder traits и новые vendor codec traits | DONE |
| M03 | Native transport abort для socket clients, без PG cast в общем коде | Luna audit; новый `VertxConnectionAbort.scala` | DONE: MySQL/MariaDB/DB2 live |
| M04 | MySQL/MariaDB contexts/dialects/pool builders | Root | DONE |
| M05 | DB2 dialect/context, FINAL/OLD TABLE returning | Luna DB2; новые DB2 файлы | DONE: 21 live tests / Scala version |
| M06 | Реальные disposable DB harnesses | Luna infrastructure; новые scripts tools/vertx/{mysql,mariadb,db2}.sh | DONE for PG/MySQL/MariaDB/DB2 |
| M07 | Новые общие/driver-specific integration runners, shared corpus | Luna tests; только новые test files | DONE |
| M08 | Portable diagnostics для ?, $n; macro API прежний | Root; VertxTranslateContext.scala | DONE: 10 portable tests / Scala version |
| M09 | Сборка, независимое ревью, matrix и отчёт | Root + Luna reviewer | DONE: full module 239 tests / Scala version |
| M10 | DB2 upstream CCSID 500/CP1047 correction, reproducible native client artifact | Luna dependency patch + root; `project/VertxDB2Patch.scala`, `quill-vertx-db2-client` | DONE: pinned binary, single class correction, POM/runtime audit |
| M11 | Исходный shared EncodingSpec для scalar/options/binary | Luna tests; `MultiSqlSharedEncodingSpec.scala` | DONE live MySQL/MariaDB/DB2 |
| M12 | Исключить SQL Server из native backend по запросу пользователя | Root | DONE: removed; remaining full module passes on both Scala versions |

## Согласованные interfaces

`abstract class context.vertx.ZioVertxContext[D <: SqlIdiom, +N <: NamingStrategy](val naming: N)`
сохраняет ZIO[Pool, Throwable, A], transaction FiberRef, scoped acquire, prepare,
bounded cursor streaming, query/action/batch verbs. В ядре нет PG client import,
конкретного dialect и vendor codec traits. Конкретные contexts задают idiom,
codecs, cancellation и специфическое получение returning.

Protected hooks ядра:

- `cancel(connection: SqlConnection): ZIO[Any, Throwable, Unit]` — abstract;
  PG использует cancelRequest, MySQL и DB2 native clients — transport abort.
- `executeReturning[T](sql, prepare, extractor, returningBehavior, connection)`
  — default prepare/execute/extract first RowSet.
- `executeBatchReturning[T](sql, prepares, extractor, returningBehavior, connection)`
  — default executeBatch/extract all RowSets; MySQL может выполнять каждый
  returning insert отдельно для точных generated keys.
- `executePrepared`, `extractFirstRowSet` — protected final для vendor hooks.

Postgres context остаётся `PostgresZioVertxContext[N](naming)` с прежними
VertxJsonExtensions/array codecs. Общие scalar traits — `VertxBaseEncoders` и
`VertxBaseDecoders`; существующие имена VertxEncoders/VertxDecoders сохраняют
PG arrays. Новые vendor mixins: `VertxMysqlCodecs` и `VertxDB2Codecs`, без неработающих PostgreSQL array/JSONB implicits.

MySQL/DB2 используют ?, PostgreSQL — $n через structural liftingPlaceholder.
MySQL generated keys берутся из native LAST_INSERTED_ID, не вычисляются как
firstId+n. SQL generation делегируется оригинальному MySQLDialect; native
adapter декларирует single-value returning, чтобы macro не объединял такие
insert в multi-VALUES с неполной metadata.
DB2 использует FINAL TABLE для insert/update и OLD TABLE для delete returning.

## Приёмка и исполнение

Один владелец на файл, один sbt slot у root; агенты sbt не запускают.
Исходные shared/JDBC/PG-native test expressions и expectations не меняются.
Рабочий снимок сохранён в `/workspace/.setup/quill/multi-sql-before-20261112`.

Тесты: CRUD/options/mapped types/bytea-or-binary/UUID/time, batch/generated
keys/returning по возможностям СУБД, commit/rollback/defect/interruption,
stream early take/empty/failure/pool max=1 reuse. Существующий PG suite
проверяется на обеих Scala версиях. Новые backend принимаются только по
фактически выполненным nonempty live DB tests. UNRUN/blocked DB не выдаются
за passed, compile-only не означает полную поддержку.

Отмена через закрытие socket — transport abort, не подтверждённый сервером
statement cancel. Она использует изолированный version-pinned Vert.x internal
SPI; закрытая физическая сессия должна удаляться из пула. Гарантии PG cancel
не ослабляются. Server-side cancellation отдельно отражается в matrix.

Публикации пакетов, Git или среды не выполняются.

Итог текущего этапа: 239 тестов / 41 suite на каждой Scala 2.12.20 и 2.13.16,
без ignored/canceled/pending. PG: 166, MySQL: 21, MariaDB: 21, DB2: 21,
portable SQL: 10. Предыдущие 29 файлов тестов PostgreSQL побайтно сохранены.
Команды, найденные ошибки и ограничения — в
[MULTI-SQL-VALIDATION.md](MULTI-SQL-VALIDATION.md).

M12 закрывает прежний сетевой blocker: SQL Server удалён из области
native backend по явному решению пользователя. Его context/dialect/codecs,
pool builders, client dependency, runners и harnesses удалены. Требование
добавить домен отменено. Исходный JDBC SQL Server код Quill сохранён.
Полная совместимость закрытого приложения остаётся внешним приёмочным gate.
