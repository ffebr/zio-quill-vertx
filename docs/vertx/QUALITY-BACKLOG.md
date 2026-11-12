# Бэклог качества native Vert.x backend

Цель этапа: упростить существующую реализацию и привести её к локальным
соглашениям Quill. Публичные API, QDSL, SQL, типы ошибок, порядок результатов,
владение ресурсами и interruption semantics сохраняются. Новые features и
изменение тестовых ожиданий в этот этап не входят.

## Ориентиры

- `quill-jdbc/src/main/scala/zio/quill/context/jdbc/JdbcContextVerbExecute.scala`:
  небольшие методы исполнения и общие helpers для повторяющихся операций.
- `quill-jdbc-zio/src/main/scala/zio/quill/context/qzio/ZioJdbcUnderlyingContext.scala`:
  явные effect types, connection scope и компактные context verbs.
- `quill-core/src/main/scala/zio/quill/context/ContextVerbTranslate.scala`:
  публичные macro overloads и отделённая реализация диагностики.
- JDBC encoder/decoder traits и `quill-engine` AST transformers:
  явные typed codecs и pattern matching; не вводить универсальные frameworks.
- Репозиторный scalafmt; Scala 2.12.20 и 2.13.16, JDK 17.

## Задачи и владение

| ID | Задача | Владелец / файлы | Статус | Приёмка |
| --- | --- | --- | --- | --- |
| Q00 | Снимок исходного состояния и границы | Root | DONE | Снимок `src`, README и docs в `/workspace/.setup/quill/quality-before-20261112`; исходный checkpoint 166 native tests на каждой Scala версии |
| Q01 | Убрать повторение context execution, batch preparation и мёртвые helpers | Luna context: только `PostgresZioVertxContext.scala` | DONE | Малые private helpers; macro-facing signatures и resource/cancellation boundaries прежние; без новой иерархии traits |
| Q02 | Проверить и упростить чтение async bridge/ownership | Luna runtime: только `VertxRuntime.scala`, `VertxPool.scala` | DONE | Сначала обоснование состояний; безопасное устранение дублирования; exactly-once и late cleanup сохранены, CAS не заменяется непроверенной схемой |
| Q03 | Упростить native SQL diagnostics | Luna translation: только `VertxTranslateContext.scala`, при необходимости один private helper | DONE | Macro API прежний; меньше вложенности/повторных вычислений; strings, nested comments, dollar quotes, E-strings и options сохраняются; без фиктивных unsupported overrides ради наследования |
| Q04 | Согласовать codecs/JSON helpers с Quill | Luna codecs: только `VertxEncoders.scala`, `VertxDecoders.scala`, `VertxJsonExtensions.scala` | DONE | Единая проверка bind index; типизированные простые helpers; null/temporal/precision semantics прежние; публичные constructors и implicits не меняются |
| Q05 | Проверить стиль AST dialect и общий дизайн | Luna reviewer: read-only | DONE | Сравнение с оригинальными transformers; конкретные замечания, обоснованно оставить необходимые явные ветви |
| Q06 | Независимое ревью и окончательная проверка | Root + Luna reviewer | DONE | Review diff относительно снимка; неизменность исходных тестов/API; полный native cross-run, format и dependency audit; фактические результаты ниже |

## Правила исполнения

Один владелец на файл. Общий checkout; агенты не запускают sbt и не меняют
чужие файлы. Root владеет build slot, интегрирует и проверяет результат.
Модель исполнителей — Luna, как согласовано с пользователем.

Не сокращать код ценой понятности. Новая абстракция допускается только если
упрощает несколько реальных мест использования. Не переносить большие блоки
между файлами только ради количества строк. Не менять shared/core/JDBC source,
build, схему fixture или исходные/native тестовые ожидания. Обнаруженный
функциональный дефект сообщать отдельно, не маскировать как style change.
Пакеты, Git и версии среды не публикуются.

## Итог этапа

Завершено: Q00–Q06 приняты. Четыре Luna-исполнителя подготовили изменения;
отдельный Luna-reviewer проверил diff и решения по AST/transaction ownership.
Root интегрировал результат и выполнил чистый cross-run: по 166 passed /
29 suites на Scala 2.13.16 и 2.12.20, без падений и пропусков. Все 29 native
test files побайтно неизменны; публичные Scala signatures сохранены,
runtime classpaths не содержат JDBC. Форматирование и локальная сборка прошли.

Подробности и решения — в [QUALITY-VALIDATION.md](QUALITY-VALIDATION.md).
Исторический функциональный checkpoint остаётся в [VALIDATION.md](VALIDATION.md);
его логи не перезаписывались. Публикации не выполнялись.
