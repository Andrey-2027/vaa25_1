# П3 — серверная пагинация и selected membership

Дата: 14.10.2026.

## Результат

Добавлен PermissionScopePageService: ADMIN-only scalar projection, совместные фильтры
и selected EXISTS до pagination, одинаковое условие count/fetch, стабильная сортировка,
нормализованные личные draft exceptions и page state overlay. Лимиты page/draft — 500.
Privileged source определяется зарегистрированным grantValueType, не hardcoded именем.

## Проверки и замеры

- `scripts/run-permissions-p3.ps1`: **107/107**, 148.620 s JUnit; включая П0/П1/П-U/П2
  и повторяемые inherited scenarios в классе П3. Число не означает 107 новых тестов.
- Maven offline platform-rls test: **78/78**, BUILD SUCCESS.
- Реальный Spring/Hibernate/H2 тест: **4 200** значений, search P3-04199 вне первой
  страницы; selected по ролям; снятие личного права не снимает роль; личный draft
  добавляет ещё не открытую строку; global grant сохраняет all-values при снятии exact.
- LIKE '%' интерпретируется буквально. По уточнению lazy-контракта вышедший за границы
  offset возвращает пустой диапазон и актуальный total, без подмены offset.
  Не-ADMIN отказ на paged API.
- **50 000** значений: scalar page **50**, **150 ячеек**, **6 SQL**, **446 ms**.
  Лог outputs/permissions-p3/test-run.log: `P3 H2 50000: page=50, SQL=6, elapsedMs=446`.
  P2 budget также остаётся 3 SQL.
- `scripts/run-permissions-p3-postgresql.ps1`: 50 000 временных значений, assertions
  filtered selected count=1 и global-with-exact-draft-removal count=50 000; EXPLAIN
  ANALYZE selected OFFSET 49 000 LIMIT 50: **118.284 ms execution**, sort external merge
  2392 kB, 50 возвращённых строк. Временные таблицы ON COMMIT DROP, ROLLBACK.

## Найденные и исправленные проблемы

SQL three-valued logic: NOT(... valueId IN draftIds) при global NULL отбрасывал источник.
Добавлен explicit IS NOT NULL в исключение exact draft rows. Draft состояния теперь
участвуют в P2 matching до расчёта effective, включая read prerequisite.

## Границы evidence

PostgreSQL проверен equivalent SQL на временных таблицах, не полный Spring/JPA runtime
на PostgreSQL. Измерения единичные, не p95. Текущий scalar source adapter поддерживает
id/code/name; нет загрузки arbitrary metadata fields. Подключение FilterGrid UI — П7,
общий draft store и save — П4, полная DB/UI интеграция и индексы по production-like
планам — П9. Предметные данные DBVaa25 не изменены.

Контракт: docs/plans/permissions-administration-p3-contracts.md.
