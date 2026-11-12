# Протокол автономной работы Luna

1. Оркестратор выдаёт одну карточку BACKLOG с зависимостями, разрешёнными файлами и внутренним контрактом. Coding исполнители — Luna. Checkout уже изолирован; worktree без запроса пользователя не создавать. Один владелец на файл; общие build изменения интегрирует оркестратор.
2. Актуальная граница — [ADR-001.md](ADR-001.md): QDSL expressions, модели и ожидаемое PostgreSQL поведение сохраняются. Разрешены новые backend imports, context construction, native pool/config/ZLayer/environment, native codecs и замена прямых JDBC APIs. Полная совместимость старых JDBC signatures больше не требуется.
3. Переиспользовать Quill core/SQL DSL/macros. Не переписывать query expressions ради native runner и не добавлять fake JDBC Connection/PreparedStatement/ResultSet adapters. Неизвестная возможность QDSL — повод исследовать и сообщить blocker, а не урезать контракт.
4. Исходные assertions, query fixtures и expected values не ослаблять. Не добавлять ignore/pending/filters для зелёного результата. В native tests разрешено менять wiring/effect runner и регистрацию native codecs; query bodies и результаты остаются теми же. Context-bound quotations перекомпилируются с новым контекстом.
5. Native module не использует JDBC driver, quill-jdbc transport или Vert.x JDBC client; fallback запрещён. Исходный JDBC служит reference. Механическое выделение общих data-only типов (JsonValue/JsonbValue) с сохранением FQCN/поведения координирует оркестратор; дублирование классов запрещено.
6. SQL исполняется через native Vert.x PostgreSQL client. Event loop не блокируется ожиданием Future/ZIO. Не сохранять synchronous JDBC SQL APIs через скрытое worker dispatch: их миграция на native prepare/raw API разрешена и должна быть документирована.
7. Async bridge проверять на synchronous throw, callback failure/success, interruption до/после acquire, late acquisition после отмены и exactly-once completion. Ресурсы позднего callback освобождаются; отмена fiber сама по себе не доказывает отмену SQL на сервере. Транзакция владеет одной native сессией.
8. Один sbt slot на общий checkout; билды не запускаются конкурентно и не перетирают отчёты. Сначала inspect git status и текущие тесты. Итог содержит команды, versions, exit codes, test counts, failed/skipped/pending и свежие log/XML. Compile-only и нулевой test count не являются функциональной проверкой.
9. Зависимости/сервисы проверять по конкретным prerequisites; не обходить TLS/checksum и не раскрывать секреты. Среду, network policy, публикацию и внешний push без отдельной команды не менять. Недоступные проверки отмечать BLOCKED/UNRUN.
10. Исполнитель возвращает REVIEW: diff, реализованные контракты, проверенные команды и ограничения. Сам себя не принимает. Оркестратор независимо проверяет diff/результат; другой Luna reviewer проверяет ключевые lifecycle/error/compatibility случаи.

## Приёмка

Один corpus QDSL expressions исполняется на JDBC reference и native backend с одинаковыми expected values; backend wiring, native codec registration и effect runner явно различаются. Полные файлы приложения не обязаны быть byte-for-byte одинаковыми: разрешена согласованная интеграционная миграция, но запросы не переписываются.

В отчёте отдельно: исходный baseline, PostgreSQL reference, native parity, resource/cancellation tests, dependency isolation, прочие JDBC regressions и пользовательский black-box workload. Библиотечные тесты не доказывают работоспособность недоступного приложения. Native implementation и app migration не объявляются завершёнными по одному обновлённому плану.
