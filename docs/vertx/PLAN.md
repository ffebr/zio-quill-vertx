# PostgreSQL: Quill ZIO JDBC → Vert.x ZIO

## Зафиксированная область

- Целевая СУБД — PostgreSQL. Приложение и его исходники недоступны для аудита; версии Quill, Scala, JDK, PostgreSQL и текущие codecs/wiring приложения ещё предстоит выяснить.
- Пользователь разрешил миграцию интеграционной границы: imports, создание контекста, ZIO layers/environment, настройку native pool, JDBC-specific codecs и raw prepare могут потребовать изменений.
- QDSL сохраняется. Выражения `quote`/`query`/`lift`, операторы, actions, returning и их семантика не переписываются под новый backend. Контекстно-зависимые quotations перекомпилируются с новым контекстом; нельзя вручную переписывать выражения или ожидания.
- Новый backend — native Vert.x PostgreSQL в отдельном модуле с предлагаемым именем `quill-vertx-zio`. Он использует существующие Quill core/SQL macros. В новом backend запрещены JDBC-драйвер и JDBC execution/fallback. Существующие JDBC tests/expectations остаются эталоном; root может координировать механическое выделение общего data-only типа/build dependency, если native QDSL corpus этого требует.
- Совместимость означает сохранение QDSL-выражений и наблюдаемых результатов/side effects на PostgreSQL, при явно мигрируемом backend wiring. Это не обещание drop-in совместимости JDBC типов, старых imports, `DataSource`/`Connection` environment, `PreparedStatement` или vendor `unwrap`.
- Существующие тесты и expected values не ослабляем и не переписываем. Новые Vert.x specs используют те же query expressions и assertions с новым backend wiring.
- Native implementation находится в `quill-vertx-zio`: контекст, pool/runtime, codecs, transactions, streams, prepare/probe и shared corpus runners реализованы. Итоговые 166 tests / 29 suites прошли на Scala 2.13.16 и 2.12.20 без пропусков и падений; compile/runtime dependency audit завершён. Закрытое app validation и более широкая fault matrix остаются отдельными областями приёмки.

Исходная ревизия аудита: `fc0800f45b73fa5e4638e669f5bca5a09fe57c21`.

## Контракт и миграционная граница

Quill core/SQL macros продолжают строить те же запросы и actions. Новый контекст обеспечивает native parameter binding, row decoding, ZIO effects, pool/session ownership, transaction и streaming. Для context-bound quotations требуется перекомпиляция в новом контексте; само выражение QDSL остаётся прежним.

Меняться могут backend-specific imports, context constructors, ZIO environments/layers, database configuration, encoder/decoder APIs и JDBC escape hatches. Их точная карта — [COMPATIBILITY.md](COMPATIBILITY.md). Каждое отличие должно быть перечислено с migration guidance и проверкой эквивалентного поведения. Прямые JDBC callbacks нельзя silently emulate: клиентский код, использующий `PreparedStatement`, `Connection`, JDBC `ResultSet`, `DataSource` или vendor APIs, переводится на документированный native seam. До получения клиентского usage inventory поддерживаемый набор таких seams неизвестен.

Приёмка идёт по двум независимым уровням:

1. Репозиторный контракт: тот же набор QDSL-выражений и shared expected values исполняется через PostgreSQL native backend; результаты, записи, transaction effects и streaming semantics сравниваются с JDBC reference.
2. Реальное приложение: пользователь подтверждает перечень изменённых wiring/JDBC seams, перекомпилирует приложение без переписывания QDSL, затем запускает workload. Без этого остаётся непроверенным app-specific migration scope.

## Архитектурные решения

Native Vert.x PostgreSQL runtime находится в отдельном `quill-vertx-zio`; модуль использует Quill core/SQL, ZIO и одну зафиксированную совместимую линию Vert.x. Он не зависит от `quill-jdbc`, JDBC-драйвера или Vert.x JDBC client. Оригинальный `quill-jdbc-zio` остаётся отдельным JDBC reference.

Новый API открыто принимает native pool/config/layer, а не пытается извлечь credentials и настройки из произвольного JDBC `DataSource`. Native context и pool владеют одной сессией на транзакцию. Вызовы драйвера выполняются async; event loop не блокируется. Новый API не предоставляет фиктивные JDBC `Connection`, `PreparedStatement` или `ResultSet` и не обещает `unwrap`.

Решение пользователя и архитектурное направление зафиксированы в [ADR-001.md](ADR-001.md); T03 и native runtime/context implementation приняты. Если конкретные QDSL выражения невозможно выполнить без изменения самих выражений или shared expectations, это blocker. Отличия только в wiring/codec/prepare API допустимы, если они явно инвентаризированы и есть migration guidance.

## Последовательность

1. Снять baseline и инвентаризировать имеющиеся QDSL tests, native dependencies, shared encoders/decoders и JDBC escape hatches. Зафиксировать версии тестового окружения; неизвестные app-specific версии пометить как неизвестные.
2. Зафиксировать `quill-vertx-zio` API, native pool/layer/config, error model, ownership и mapping QDSL bind markers. Сохранить исходные JDBC behavior reference и tests; root координирует необходимые mechanical shared-type/build extraction, не меняя QDSL или expectations.
3. Сделать vertical slice с существующим QDSL insert/select выражением и теми же ожидаемыми данными, выполнить native запрос в PostgreSQL и закрыть ресурсы.
4. Реализовано в `quill-vertx-zio`: codecs, query/action/returning/batch, transactions, streaming, native prepare/probe и context/layers. Поддерживаемые shared выражения исполняются с native wiring; текущий перечень точных shared definitions и отдельных proxy cases указан в [TEST-MATRIX.md](TEST-MATRIX.md).
5. Итоговый native cross-run: 166 passed / 29 suites на каждой из Scala 2.13.16 и 2.12.20, exit 0; локальные JAR и formatting check готовы, JDBC отсутствует в Compile/Runtime closure. Native tests покрывают общий SQL/encoding corpus, ComplexQuery/PrettyPrinting, casts и correlated returning, transactions/streams/cancellation, commit failure, backend termination, auth SQLSTATE 28P01, event-loop heartbeat и typed null/date codecs. Полный исходный PostgreSQL JDBC suite: по 150 passed / 19 suites на обеих Scala версиях; base: 2784 passed; PostgreSQL/H2/SQLite JDBC ZIO: 100 passed. Исходный JDBC Departments runner воспроизвёл planner/order проблему на той же БД и прошёл после canonical fixture reset; запросы и ожидания сохранены. Подробные команды, история и пределы — в [VALIDATION.md](VALIDATION.md). Другие JDBC DB ветви, широкие failure cases и закрытое приложение остаются непроверенными.
6. Передать пользователю конкретный migration inventory для wiring и JDBC-only usages. Пользователь запускает закрытый workload; app-specific проверка не подменяется библиотечными тестами.

План работ, владельцы, зависимости и критерии — [BACKLOG.md](BACKLOG.md). Инструкции исполнителям — [AGENT_PROTOCOL.md](AGENT_PROTOCOL.md).

## Границы автономии

Исполнители работают только по карточкам [BACKLOG.md](BACKLOG.md); оркестратор интегрирует изменения и независимо проверяет результат. Исходный JDBC остаётся reference. Не ослаблять его тесты, не менять QDSL выражения или expected values, не публиковать пакеты и не менять пользовательскую среду без отдельной команды.

## Текущее состояние

Библиотечная реализация и указанный корпус проверены: 166 native tests на обеих поддерживаемых Scala версиях, dependency/runtime/JAR audit и форматирование завершены. Публикации не было. Полная совместимость закрытого приложения, другие DB ветви и исчерпывающая fault matrix не заявляются. Результаты — в [VALIDATION.md](VALIDATION.md), покрытие — в [TEST-MATRIX.md](TEST-MATRIX.md).
