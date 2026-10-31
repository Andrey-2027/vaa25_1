# DB1 UDR: инвентаризация, бэкап и удаление таблиц движка

- Дата подготовки: 2026-10-24.
- Вход: движок UDR удалён из кода
  ([R3.2](evidence/r3-2-udr-engine-removal-20261024.md)); владелец признал все
  UDR-отчёты тестовыми и ненужными ([R2](evidence/r2-udr-inventory-20261022.md)).
- Статус: **выполнено 2026-10-24 на `DBVaa25`** — единственной рабочей БД,
  признанной тестовой решением владельца. Снят инвентарь, снят и проверен
  восстановлением бэкап, пять таблиц удалены, post-checks чистые, компиляция —
  exit 0. Протоколы: [подготовка](evidence/db1-udr-preparation-20261024.md),
  [удаление](evidence/db1-udr-removal-20261024.md).

Документ — исполняемый runbook: сначала только чтение и резервные копии, затем
удаление отдельным подтверждённым шагом. Ни одна команда ниже не должна
выполняться на рабочей БД без записи результата в evidence.

## 0. Что удаляется и что остаётся

Пять таблиц старого движка (сущности `org.ipro.reportstudio.dom` удалены в R3.2):

| Таблица | Родитель | Что хранит |
|---|---|---|
| `report_template` | — | шапка отчёта: имя, состояние, JPQL/JSON, привязка печати, лимиты, оформление |
| `report_band` | `report_template`, самоссылка `parent_id` | бэнды и вложенные группы |
| `report_field` | `report_band` | поля/колонки бэнда |
| `report_param` | `report_template` | объявления параметров |
| `report_order` | `report_template` | сортировки |

Проверено по локальной БД (PostgreSQL 17.10):

- PK `id` — `bigint identity by default` во всех пяти; отдельных sequence нет —
  identity-последовательности принадлежат таблицам и уходят вместе с ними.
- FK: `report_band.template_id → report_template.id`,
  `report_band.parent_id → report_band.id`, `report_field.band_id → report_band.id`,
  `report_param.template_id → report_template.id`,
  `report_order.template_id → report_template.id`; все — `NO ACTION`.
- Индексы: только PK и `uk_report_template_name`, `uk_report_param_template_name`;
  индексов по FK нет, отдельных объектов удалять не нужно.
- Enum-поля — `varchar` + CHECK, PostgreSQL-типов и отдельных объектов нет.
- Других таблиц, ссылающихся на UDR, нет.
- Грантов UDR в локальных БД нет; `access_grant` этим срезом не трогается.

`spring.jpa.hibernate.ddl-auto=update` таблицы после снятия сущностей **не**
удаляет: они остаются orphan'ами и не пересоздаются. Старый билд при откате снова
увидит их; данные — только из дампа.

Порядок удаления — дочерние до родителя:
`report_field → report_band → report_param → report_order → report_template`.
`cascade` намеренно не используется, чтобы не задеть посторонние объекты.

## 1. Предусловия и стоп-условия

Перед стартом (прогон `DBVaa25` 2026-10-24 — все пункты выполнены):

- [x] Решение владельца «UDR не нужен» всё ещё действует (R2, 2026-10-23);
      2026-10-24 подтверждено, что `DBVaa25` — единственная БД и она тестовая.
- [x] Есть окно обслуживания и ответственный за восстановление.
- [x] Известны: версия приложения (commit), профиль, БД, сервер PostgreSQL.
- [x] Инвентарь каждой БД снят и сохранён (§2).
- [x] Бэкап снят и **проверен восстановлением** на отдельной БД (§3).
- [x] Приложение остановлено или не пишет в эти таблицы (после R3.2 писать
      некому; старые собранные версии — повод остановить их).

Немедленно остановиться, если:

- инвентарь показывает таблицы, которых нет в списке пяти;
- есть view/триггер/внешний потребитель, ссылающийся на `report_*`;
- в `access_grant` есть строки `ENTITY:*ReportTemplate*` или иные UDR-ключи —
  решение по ним принимается отдельно, а не удаляется этим скриптом;
- восстановление бэкапа не совпало по counts;
- `confirm_db` не совпадает с именем БД (скрипт отказал) — не «исправлять» токен
  вслепую, а перепроверить, та ли это БД.

## 2. Инвентарь (read-only)

```bash
psql "$PGURL" -X -A -F '|' -P footer=off -v ON_ERROR_STOP=1 \
     -f scripts/db1-udr-inventory.sql
```

Скрипт [`scripts/db1-udr-inventory.sql`](../../scripts/db1-udr-inventory.sql)
показывает:

1. строки и размер пяти таблиц;
2. полный список колонок (сверка со схемой из §0);
3. PK/UNIQUE/FK и identity-колонки;
4. индексы;
5. шапки шаблонов (`id`, имя, состояние, привязка печати, автор);
6. число UDR-грантов (0 или список для отдельного решения).

Скрипт работает и в БД без таблицы `access_grant`, и в БД без UDR-таблиц:
отсутствие таблиц — это факт инвентаря, а не ошибка.

Для каждой БД сохранить вывод рядом с решением: дата, БД, исполнитель, commit.
Отдельно зафиксировать, что строки `report_template` — тестовые (R2) или
получили новое решение владельца.

## 3. Бэкап и проверка восстановления

```powershell
# Локальная БД из config/application-local.properties:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/db1-udr-backup.ps1

# Несколько БД (локальный сервер):
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/db1-udr-backup.ps1 `
    -Databases DBVaa25,accounting_object_pilot_local_v1,accounting_object_pilot_local_v2,accounting_object_pilot_local_v3

# Рабочая БД — только явным решением оператора:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/db1-udr-backup.ps1 `
    -AllowRemote -Databases DBVaa25 -OutputRoot D:\db1-udr-backup-20261024
```

[`scripts/db1-udr-backup.ps1`](../../scripts/db1-udr-backup.ps1):

- по умолчанию работает только с `localhost`/`127.0.0.1`; удалённый хост требует
  `-AllowRemote`;
- читает host/port/user/password из `config/application-local.properties`
  (значения не печатает);
- пишет `pg_dump --no-owner --no-privileges` по пяти таблицам;
- пишет counts по каждой таблице и CSV UDR-грантов;
- если `-SkipRestoreCheck` не задан, создаёт одноразовую БД
  `db1_udr_restore_check_<hex>`, восстанавливает дамп, сверяет counts по каждой
  таблице и удаляет проверочную БД; при расхождении оставляет её для диагностики
  и завершается ошибкой;
- пишет `SHA256SUMS.txt` с хэшами всех артефактов.

Проверка после запуска:

```text
каталог содержит <db>.udr.sql, <db>.udr-counts.txt, <db>.udr-grants.csv, SHA256SUMS.txt
в дампе 5 CREATE TABLE и 5 COPY
sha256sum -c SHA256SUMS.txt  -> все OK
restore_check=ok (<scratch db>) в манифесте
```

Empty-БД (таблицы есть, строк 0) тоже дампится: нужен откат схемы. Если таблиц
нет вовсе, создаётся `<db>.SKIPPED.txt`.

## 4. Удаление

Только после §2–3, по одной БД за раз:

```bash
psql "$PGURL" -X -v ON_ERROR_STOP=1 \
     -v confirm=DROP-UDR-TABLES -v confirm_db=DBVaa25 \
     -f scripts/db1-udr-removal.sql
```

[`scripts/db1-udr-removal.sql`](../../scripts/db1-udr-removal.sql) отказывается
работать, если:

- не задан `confirm=DROP-UDR-TABLES`;
- `confirm_db` не совпадает с `current_database()`;
- в БД нет `report_template` (не та БД или уже удалено).

Скрипт печатает counts до удаления и UDR-гранты, затем в одной транзакции
удаляет пять таблиц в порядке дочерних, после чего проверяет:

- `to_regclass` всех пяти — `NULL`;
- не осталось sequence `report_%`;
- `DO`-блок падает, если хоть одна таблица осталась.

Транзакция и `ON_ERROR_STOP` означают: при любой ошибке удаление откатывается
целиком. Пустые БД (v1/v2/v3, navigation pilot) проходят тот же путь — для них
это удаление пустой схемы.

## 5. Post-checks

| Проверка | Ожидание |
|---|---|
| `scripts/db1-udr-inventory.sql` после удаления | нет строк по таблицам, `udr_grants` тот же, что до |
| `to_regclass('public.report_template')` и остальных четырёх | `NULL` |
| sequence `report_%` | 0 |
| `access_grant` | не изменился; UDR-строки не удалялись этим срезом |
| каталог отчётов и печать | UDR-строк нет (R3.1); UniDR/JR работают |
| приложение стартует | схема без UDR-таблиц не нужна после R3.2 |
| старый билд с `ddl-auto=update` | создаст пустые таблицы сам; данными их наполнит только дамп |

## 6. Откат

```bash
# схема + данные одной БД
psql "$PGURL" -X -v ON_ERROR_STOP=1 -f <backup>/DBVaa25.udr.sql
```

Дамп содержит `CREATE TABLE`, identity, PK/UNIQUE/FK и `COPY` данных; после
восстановления сверить counts с `<db>.udr-counts.txt`. Гранты восстанавливать не
нужно — они не удалялись. Если удаление выполнялось в нескольких БД, откат
делается по каждой отдельно.

## 7. DoD DB1 UDR

- [x] Решение владельца «UDR не нужен» зафиксировано (R2, 2026-10-23).
- [x] Скрипт инвентаря готов и прогнан на трёх формах БД (с данными, пустые
      таблицы, таблиц нет).
- [x] Скрипт бэкапа готов; на локальной `DBVaa25` снят дамп, восстановление
      сверено по counts, временная БД удалена.
- [x] Скрипт удаления готов; guard-отказы проверены, полный прогон выполнен на
      одноразовой копии, рабочая БД не тронута.
- [x] Инвентарь рабочей БД `DBVaa25` снят и сохранён (2026-10-24).
- [x] Бэкап `DBVaa25` снят и проверен восстановлением (counts совпали, хэши 3/3).
- [x] Удаление на `DBVaa25` выполнено, post-checks чистые, записано в
      [evidence](evidence/db1-udr-removal-20261024.md).
- [x] Компиляция приложения после удаления — `mvn -DskipTests compile`, exit 0.
