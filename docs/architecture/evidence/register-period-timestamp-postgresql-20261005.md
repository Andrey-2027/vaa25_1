# Переход периода регистра на TIMESTAMP: PostgreSQL, 2026-10-05

Результат: SQL перехода выполнен, транзакция завершилась `COMMIT`.

## Цель и входные данные

- Сервер: локальный PostgreSQL 17.10, `localhost:5432`.
- База приложения: `DBVaa25`, схема `public`, таблица `nomenclature_price`.
- Подключение взято из локальной конфигурации приложения; секреты в evidence не записываются.
- Выполненный файл: [information-register-period-timestamp-postgresql.sql](../../../scripts/information-register-period-timestamp-postgresql.sql).
- SHA-256 файла: `d81bb72541d1e4ee8e9ae219c8bd699d0ac37b8b64e6a9e6f32ad3f546dce5a0`.

До перехода `period_date` имела тип `date`, `NOT NULL`. В таблице было **0 строк**.
Индексы: первичный ключ по `id`, уникальный ключ по `(nomenclature_id, period_date)`.

## Выполнение

Использован установленный `psql` 17 с `-X`, без интерактивного запроса пароля,
`ON_ERROR_STOP=1` и явной базой приложения. Для этой сессии установлены
`search_path=public`, `lock_timeout=5s`, `statement_timeout=60s`.
Файл выполнялся через `-f`; содержимое SQL не изменялось.

```text
BEGIN
ALTER TABLE
COMMIT
```

Миграция переводит DATE в `timestamp without time zone` явным CAST; прежняя дата
соответствует началу дня. Проверка сохранности реальных значений здесь ограничена
пустой таблицей: существующих записей для сравнения не было.

## Проверка после COMMIT

| Проверка | Результат |
| --- | --- |
| База / схема | `DBVaa25` / `public` |
| Тип `period_date` | `timestamp without time zone` |
| `datetime_precision` | `6` — микросекунды |
| `is_nullable` | `NO` |
| Число строк | `0` |
| NULL-периоды / периоды вне начала дня | `0` / `0` |
| `nomenclature_price_pkey` | unique, valid, ready; колонки `(id)` |
| `uk_nomenclature_price_key` | unique, valid, ready; колонки `(nomenclature_id, period_date)` |
| Ограничения PRIMARY KEY / UNIQUE | `convalidated=true` |
| FK `nomenclature_id → nomenclature(id)` | сохранён, `convalidated=true` |

Это проверка миграции и физической схемы. Приложение не запускалось; ORM/read/CTE
сценарии на этой базе и PostgreSQL-конкуренция не выполнялись. Предыдущая проверка
исходников на H2 сохранена в [отдельном протоколе](register-temporal-tests-20261005.md).
Пользовательские сохранённые отчёты этим SQL не меняются.

## Обратный переход

Возврат к DATE потребует отдельного решения и проверки данных. После появления
времени внутри дня CAST к DATE потеряет время и может объединить уникальные ключи.
Этот обратный переход не выполнялся.
