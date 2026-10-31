# DB1 UDR: удаление подготовлено, рабочие БД не тронуты (2026-10-24)

- Дата: 2026-10-24.
- Основание: движок UDR удалён в
  [R3.2](r3-2-udr-engine-removal-20261024.md); владелец признал все UDR-отчёты
  тестовыми ([R2](r2-udr-inventory-20261022.md), решение 2026-10-23).
- Срез: подготовка физического удаления — инвентарь, бэкап с проверкой
  восстановления, скрипт удаления с guard'ами. **Удаление на рабочих БД не
  выполнялось**; локальная `DBVaa25` не изменялась.
- Runbook: [`db1-udr-data-inventory.md`](../db1-udr-data-inventory.md).

## 1. Инвентарь (read-only)

Снят на локальном PostgreSQL 17.10 скриптом
[`scripts/db1-udr-inventory.sql`](../../../scripts/db1-udr-inventory.sql).

| БД | `report_template` | band | field | param | order | UDR-гранты |
|---|---|---|---|---|---|---|
| `DBVaa25` | 6 (48 kB) | 18 | 27 | 2 | 2 | 0 |
| `accounting_object_pilot_local_v1` | 0 | 0 | 0 | 0 | 0 | 0 |
| `accounting_object_pilot_local_v2` | 0 | 0 | 0 | 0 | 0 | 0 |
| `accounting_object_pilot_local_v3` | 0 | 0 | 0 | 0 | 0 | 0 |
| `navigation_pilot_6dd935094b1448d98f627a56dcd85d1f` | 0 | 0 | 0 | 0 | 0 | 0 |
| `accounting_object_pilot_local` | таблиц нет | — | — | — | — | — |

`DBVaa25`, шапки шаблонов — те же 6 строк, что в R2:

| id | имя | состояние | привязка печати | автор |
|---|---|---|---|---|
| 1 | Form первая | DRAFT | `UnitOfMeasurement` | admin |
| 2 | 434434 | DRAFT | `UnitOfMeasurement` | admin |
| 3 | 435435 | DRAFT | `UnitOfMeasurement` | admin |
| 4 | СпецификацииПоЖурналу | DRAFT | — | admin |
| 6 | Тест1 | DRAFT | — | admin |
| 7 | Проь | DRAFT | — | admin |

Схема совпала с выведенной из удалённых сущностей: identity `id`, FK только
между пятью таблицами, отдельных индексов по FK нет, enum-поля — `varchar` с
CHECK. Других таблиц, ссылающихся на UDR, в `DBVaa25` нет; `access_grant` не
содержит UDR-строк (0 во всех проверенных БД).

## 2. Бэкап и проверка восстановления

Скрипт [`scripts/db1-udr-backup.ps1`](../../../scripts/db1-udr-backup.ps1)
прогнан на `DBVaa25`:

```text
outputs/db1-udr-backup-20261024-173650/   (gitignored, вне коммита)
  DBVaa25.udr.sql            20 511 B, 5 CREATE TABLE, 5 COPY
  DBVaa25.udr-counts.txt     template 6, band 18, field 27, param 2, order 2
  DBVaa25.udr-grants.csv     только заголовок (UDR-грантов 0)
  SHA256SUMS.txt             хэши трёх артефактов
```

Проверка восстановления выполнена автоматически: одноразовая БД
`db1_udr_restore_check_00a33638b0be`, restore дампа, сверка counts по каждой из
пяти таблиц — совпало, проверочная БД удалена (`pg_database` → 0 совпадений).
Хэши артефактов пересчитаны независимо: 3/3 OK.

## 3. Скрипт удаления и его guard'ы

[`scripts/db1-udr-removal.sql`](../../../scripts/db1-udr-removal.sql) требует два
токена: намерение и имя БД.

| Прогон | Результат |
|---|---|
| без `-v confirm` | отказ до DDL, exit 3, `report_template` = 6 |
| `confirm=DROP-UDR-TABLES`, `confirm_db=other_db` | отказ до DDL, exit 3, `report_template` = 6 |
| одноразовая копия `DBVaa25` из дампа | restore 6 строк → удаление пяти таблиц → `to_regclass` = NULL, sequence `report_%` = 0, проверочная БД удалена |

Полный прогон делался только на копии `db1_udr_removal_dryrun_1792852711`,
которая затем удалена. Рабочая `DBVaa25` осталась с 6 строками и всеми пятью
таблицами.

## 4. Что не делалось

- Инвентарь и бэкап **рабочих** БД: в этом чекауте их нет, вход — оператор.
  Скрипт бэкапа по умолчанию отказывается от нелокального хоста и требует
  `-AllowRemote`; адрес и креды читаются из локального конфига, в вывод не
  попадают.
- Удаление на локальной `DBVaa25` сознательно не выполнялось: срез — подготовка,
  а данные нужны как эталон инвентаря до решения владельца о запуске DB1.
- Runtime-проверки приложения после удаления не выполнялись: удаления не было.
  Runbook §5 описывает, что проверить после него.

## 5. Артефакты

```text
scripts/db1-udr-inventory.sql      read-only инвентарь (таблицы, схема, FK, гранты)
scripts/db1-udr-backup.ps1         дамп + counts + CSV грантов + restore check + SHA256
scripts/db1-udr-removal.sql        guard по токену и имени БД, транзакционное удаление
docs/architecture/db1-udr-data-inventory.md   runbook и DoD
```

DoD DB1 UDR: подготовка закрыта; снятие инвентаря/бэкапа и удаление на рабочих
БД остаются открытыми пунктами runbook §7.
