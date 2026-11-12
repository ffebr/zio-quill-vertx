# Результаты проверки native PostgreSQL backend

Дата: 2026-11-12. Исходная ревизия: `fc0800f45b73fa5e4638e669f5bca5a09fe57c21`.
Проверялся рабочий checkout с новым `quill-vertx-zio`; публикации не выполнялись.

## Среда и результаты

Temurin JDK 17.0.17, sbt 1.11.4, ZIO 2.1.20, Vert.x 4.5.36,
zio-json 0.7.44, SCRAM client 3.2. PostgreSQL 16.11 использует официальный
образ, закреплённый digest в `tools/vertx/postgres.sh`. Native и JDBC reference
работают с разными БД; исходная схема загружается из репозитория.

| Проверка | Выполнено | Failed | Ignored / pending | Suites |
| --- | ---: | ---: | --- | ---: |
| Native PostgreSQL, Scala 2.13.16 | 166 passed | 0 | 0 / 0 | 29 |
| Native PostgreSQL, Scala 2.12.20 | 166 passed | 0 | 0 / 0 | 29 |
| Исходный PostgreSQL JDBC, Scala 2.13.16 | 150 passed | 0 | 0 / 0 | 19 |
| Исходный PostgreSQL JDBC, Scala 2.12.20 | 150 passed | 0 | 0 / 0 | 19 |
| Исходный base aggregate, Scala 2.13.16 | 2784 passed | 0 | 2 / 1 | 106 |
| Исходные JDBC ZIO PostgreSQL/H2/SQLite, Scala 2.13.16 | 100 passed | 0 | 8 / 0 | 18 |

Все группы завершены без canceled/aborted. Пропуски и pending относятся только
к исходным тестам, которые не менялись. Native suite не содержит пропусков.
Более ранний отдельный PostgreSQL JDBC reference: 49 passed, 8 suites, без
failed/ignored/pending. Его результаты входят в более широкий регрессионный прогон.

Итоговый cross-build завершился с exit code 0. Оба native JAR собраны локально
через `packageBin`; это не публикация. `quill-vertx-zio/scalafmtCheckAll`,
`git diff --check` и `bash -n` для обоих shell helpers прошли.

## Что проверяют native тесты

Источник каждого перенесённого корпуса и пределы покрытия перечислены в
[TEST-MATRIX.md](TEST-MATRIX.md). Перенесённые shared quotations и expected
values не исправлялись под новый backend. Дополнительные native fixtures
проверяют самостоятельные транспортные и ресурсные контракты.

- Запросы/joins/options/aggregation/distinct, динамические quotations,
  mapped types, native scalar/array/time/UUID/bytea и JSON/JSONB codecs.
- Insert/update/delete, affected rows, returning/generated/many, batch и
  on-conflict; native prepare, async probe и синхронная SQL диагностика.
- Nested transaction reuse, commit и rollback при typed failure, defect и
  interruption; cursor streaming, early take и ошибка потребителя.
- Отмена `pg_sleep` через `context.run`: observer подтверждает активный запрос
  на сервере до interruption и его исчезновение после; пул max=1 используется снова.
- Deferred unique violation на COMMIT: подтверждены оба insert до ошибки,
  native SQLSTATE `23505`, отсутствие записей и повторное использование пула.
- Реальное завершение PostgreSQL backend через observer-соединение:
  подтверждён `pg_terminate_backend=true`, ошибка транзакции, rollback и новый запрос.
- Неверный пароль даёт native SQLSTATE `28P01`; отдельный пул после ошибки закрывается.
- Единственный Vert.x event loop продолжает обрабатывать timer callbacks, пока
  PostgreSQL подтверждённо выполняет `pg_sleep`; после cancellation запрос исчезает
  и native пул повторно используется.
- Typed null для bytea и SQL Date/Time/Timestamp; SQL-подтипы, переданные как
  `java.util.Date`, используют прежнюю миллисекундную семантику JDBC.
- Ошибка mapped decoder остаётся typed failure; соединение возвращается в пул.
- Детерминированные async bridge/acquire races, late acquisition cleanup и
  interruption внутри долгоживущего внешнего scope.
- Числовые casts строк, включая отдельное scalar выражение с конкатенацией;
  correlated UPDATE/DELETE RETURNING и защита от захвата имён вложенных aliases.
- Исходный PostgreSQL complex query и обе pretty-printing проверки; параметры
  диагностики и placeholder-like текст в строках/comments/dollar quotes.

## Изоляция и целостность

На обеих Scala версиях проверены `Compile/dependencyClasspath`,
`Runtime/fullClasspath` и `dependencyTree`: отсутствуют `quill-jdbc`, PostgreSQL JDBC driver
(`org.postgresql:postgresql`) и `vertx-jdbc-client`. Локальные JAR содержат
`PostgresZioVertxContext`, не содержат классов JDBC драйвера/Vert.x JDBC client.
Native production code не содержит JDBC execution/fallback.

Data-only `JsonValue`/`JsonbValue` перенесены из `quill-jdbc-zio` в `quill-zio`:
содержимое файла, FQCN и value semantics сохранены. Это позволяет обоим
backend использовать ровно одну копию типов без native зависимости на JDBC.

Следующий аудит дал пустой diff и пустой status:

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

## Воспроизведение и логи

Для свежего native cross-build с JDK 17 и sbt:

```bash
QUILL_VERTX_CLEAN=1 ./tools/vertx/test.sh \
  'quill-vertx-zio/packageBin' \
  'show quill-vertx-zio/Compile/dependencyClasspath' \
  'show quill-vertx-zio/Runtime/fullClasspath' \
  'quill-vertx-zio/dependencyTree' \
  '++2.12.20' \
  'quill-vertx-zio/test' \
  'quill-vertx-zio/packageBin' \
  'show quill-vertx-zio/Compile/dependencyClasspath' \
  'show quill-vertx-zio/Runtime/fullClasspath' \
  'quill-vertx-zio/dependencyTree' \
  'quill-vertx-zio/scalafmtCheckAll'
```

По умолчанию helper начинает со Scala, настроенной в sbt; для приведённой
матрицы первая версия — 2.13.16. В облачной среде используется
`/workspace/.setup/quill/bin/sbt`. `postgres.sh up` сохраняет существующую
тестовую БД; `reset` явно пересоздаёт только disposable native БД.

Логи текущей рабочей среды:

- `/workspace/.setup/quill/vertx-final-isolated-validation.log` — итоговые
  166 + 166 tests, дополнительные два последовательных запуска Departments
  на Scala 2.12 (2 + 2 passed), её dependency/runtime audit, package и format check;
  exit 0.
- `/workspace/.setup/quill/vertx-final-validation.log` — clean compile,
  166 passed на Scala 2.13, её package/dependency/runtime audit; последующий
  Scala 2.12 прогон выявил два ordering failures, разобранных ниже.
- `/workspace/.setup/quill/vertx-full-jdbc-reference.log` — полный исходный
  PostgreSQL JDBC suite: по 150 passed на обеих Scala версиях; exit 0.
- `/workspace/.setup/quill/vertx-original-regressions.log` — свежие исходные
  base и PostgreSQL/H2/SQLite JDBC ZIO regressions.
- `/workspace/.setup/quill/jdbc-postgres-reference.log` — отдельный ZIO reference.
- `/workspace/.setup/quill/vertx-departments-jdbc-diagnosis-fixed.log` —
  воспроизведение двух ordering failures исходным JDBC runner на той же native БД.
- `/workspace/.setup/quill/vertx-departments-plan.txt` — план Hash Right Anti Join.
- `/workspace/.setup/quill/vertx-departments-jdbc-fresh.log` — исходный JDBC
  Departments runner после восстановления canonical schema: 2 passed; exit 0.

Ранние падения не скрыты: clean build устранил stale macro SQL; новые
регрессионные тесты обнаружили потерю строковых casts при копировании idiom,
неверный alias correlated RETURNING и отсутствие SELECT после слишком раннего
lowering scalar выражения. Исправления внесены в native dialect; тестовые
ожидания и исходные shared выражения сохранены. Отдельно исправлена неоднозначная
Java-перегрузка `Tuple.of` в новом Scala 2.12 тесте.

### Порядок строк в Departments

Два shared примера сравнивают списки без `ORDER BY`. После накопления PostgreSQL
column statistics план сменился на Hash Right Anti Join и вернул
`Research, Quality` вместо `Quality, Research`. Исходный неизменённый JDBC
runner на той же БД воспроизвёл оба падения; прямое SQL исполнение подтвердило
тот же порядок. Это существующее предположение корпуса о состоянии fixture,
а не отличие результатов JDBC и Vert.x.

`TRUNCATE` не удаляет column statistics и не устранил проблему. Только новый
native `DepartmentsZioVertxSpec.beforeAll` теперь пересоздаёт три отношения
Department/Employee/Task по точному DDL исходного `postgres-schema.sql`,
без CASCADE, затем вставляет прежние fixtures. Общие QDSL expressions,
expected lists и исходные JDBC тесты не менялись; сортировка результатов
не добавлялась. После этого прошли повторные native запуски, полный cross-run
и исходный JDBC runner на свежей схеме. Порядок произвольного SQL без
`ORDER BY` по-прежнему не гарантируется ни одним из backend.

## Пределы результата

Это проверка реализации и перечисленного библиотечного корпуса. Закрытое
приложение не компилировалось и не запускалось; его версия PostgreSQL и набор
custom codecs неизвестны. Полная app-specific совместимость не подтверждена.
Миграция wiring/codec/raw JDBC APIs разрешена пользователем и описана в
[README](../../quill-vertx-zio/README.md) и [COMPATIBILITY.md](COMPATIBILITY.md).

Не выполнялись полные upstream Vert.x tests, перенос всех JDBC-specific
interfaces/tests на native API, Scala 2.12 JDBC ZIO regressions,
MySQL/SQL Server/Oracle regressions и исчерпывающая fault matrix с network
partition. Они не включены в число passed.
