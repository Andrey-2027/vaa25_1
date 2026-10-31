# DB1: вывод данных UReport — evidence (2026-10-18)

- Дата: 2026-10-18, 22:06–22:10 (локальное время машины).
- Ветка `main`, HEAD `9b3463d`; дерево содержит незакоммиченные чужие срезы — они не
  участвовали в DB1 (менялись только БД, файловое хранилище и локальный конфиг).
- Основание: [ADR-0027](../decisions/ADR-0027-report-composition-without-ureport.md)
  (D2/D5/D9), [runbook DB1](../db1-ureport-data-inventory.md).
- Окружение: PostgreSQL 17.10 на `localhost:5432`, пользователь из локального
  `config/application-local.properties` (значения в документ не переносятся).
  Основная БД — `DBVaa25`; дополнительно проверены четыре тестовые БД, где таблица
  осталась пустым orphan'ом от старых сборок.

## 1. Инвентарь до удаления (read-only)

`DBVaa25`, `ureport_template`: **2 строки, обе `enabled`**.

| id | name | file_name | enabled | target_entity_class |
|---|---|---|---|---|
| 1 | Вариант №1 | Вариант №1.ureport.xml | true | — (нет привязки к печати) |
| 2 | UReport | UReport.ureport.xml | true | `org.ip.model.UnitOfMeasurement` |

Размер таблицы — 64 kB. Строки созданы 2026-08-24 пользователем `admin`.

Файловое хранилище (`ureport.fileStoreDir` из локального конфига):
`C:/JavaProject/TestVaadin25/UReportFiles` — **6 файлов**:

| файл | в таблице |
|---|---|
| `Вариант №1.ureport.xml` | да (id 1) |
| `UReport.ureport.xml` | да (id 2) |
| `SpecJournal.ureport.xml` | нет |
| `Проба1.ureport.xml` | нет |
| `Проба1.ureport.xml.bak` | нет |
| `ПробаJpql.ureport.xml` | нет |

**Расхождение объяснено:** 4 файла — остатки шаблонов, чьи строки были удалены раньше
(приложение удаляло запись, но не файл). Это не «живые» шаблоны: строки в БД нет, кнопка
«Печать» на них не ссылается. По D5 («удалить все») они выводятся вместе с остальными,
но файл `Проба1.ureport.xml.bak` сохранён только в архиве, а не восстановлен.

Гранты: `access_grant` в `DBVaa25` — 14 строк, **UReport-строк 0**
(`dimension ilike '%ureport%'`). `REPORTS:*` в этой БД отсутствуют как класс (не
удалялись); `ENTITY:*`, `ACTION:*`, `BRANCH`, `*` не тронуты.

По остальным БД:

| БД | `ureport_template` | UReport-гранты |
|---|---|---|
| `DBVaa25` | 2 строки | 0 |
| `accounting_object_pilot_local_v1` | 0 | 0 |
| `accounting_object_pilot_local_v2` | 0 | 0 |
| `accounting_object_pilot_local_v3` | 0 | 0 |
| `navigation_pilot_6dd935094b1448d98f627a56dcd85d1f` | 0 | 0 |
| остальные `g3_register_test_*`, `accounting_object_pilot_local` | таблицы нет | — |

## 2. Резервные копии и проверка восстановления

Каталог: `C:/JavaProject/TestVaadin25/db1-backup-20261018-2206` (вне репозитория).

- `ureport_template.sql` — `pg_dump -t public.ureport_template` по `DBVaa25` (схема + 2 строки);
- `<db>.ureport_template.sql` — то же для четырёх пустых БД (нужны только для откатa схемы);
- `ureport_grants.csv` — выгрузка UReport-грантов (только заголовок: строк нет);
- `ureport-files.tgz` — архив `UReportFiles` (6 файлов);
- `ureport-files.sha256` — хэши каждого файла;
- `application-local.properties.bak` — копия конфига до удаления строки `ureport.fileStoreDir`;
- `SHA256SUMS` — хэши всех артефактов бэкапа.

Проверка восстановления (обязательна до удаления):

```text
createdb db1_restore_check
psql -f ureport_template.sql          -> restore ok
select count(*) from ureport_template -> 2   (совпало с инвентарём)
dropdb db1_restore_check              -> ok
```

Файлы: архив распакован во временный каталог, `sha256sum -c ureport-files.sha256` —
**6/6 OK**, число файлов совпало.

Хэши (`SHA256SUMS`, сокращённо):

```text
420cdeb7… ureport_template.sql
8a81b308… ureport_grants.csv
635b6d26… ureport-files.tgz
b1e87b1d… ureport-files.sha256
00a4bf71… DBVaa25.ureport_template.sql
23ca4547… navigation_pilot_…ureport_template.sql
```

## 3. Выполненные действия

1. **Гранты.** Удаление не потребовалось: UReport-строк 0 (проверено до и после).
   `REPORTS:*`, `ENTITY:*`, `ACTION:*`, `BRANCH`, `*` не затрагивались.
2. **Таблица.** `drop table if exists public.ureport_template` в транзакции в пяти БД:
   `DBVaa25`, `accounting_object_pilot_local_v1/v2/v3`,
   `navigation_pilot_6dd935094b1448d98f627a56dcd85d1f`. Все пять: `to_regclass(...) is null` → `t`.
3. **Файлы.** Каталог перемещён:
   `UReportFiles` → `UReportFiles.archived-20261018-2207` (рядом, на том же томе).
   Старый путь отсутствует; архив содержит 6 файлов с проверенными хэшами.
4. **Конфигурация.** Из `config/application-local.properties` удалена строка
   `ureport.fileStoreDir=...` (файл в `.gitignore`); копия — в бэкапе.
   В `src/main/resources` и `config/application-local.example.properties` упоминаний
   `ureport.*` не осталось (проверено grep).

## 4. Post-checks

| Проверка | Результат |
|---|---|
| `to_regclass('public.ureport_template')` = null во всех пяти БД | да |
| `access_grant where dimension ilike '%ureport%'` = 0 во всех БД | да |
| `REPORTS:*` не потеряны | в `DBVaa25` их не было и до удаления; гранты (14) не менялись |
| Старый путь файлов отсутствует | да |
| Архив доступен и сверен по `sha256` | 6/6 OK |
| `ureport.*` в конфигурации | не осталось |
| Приложение стартует с тем же профилем | **не проверено** — см. §5 |
| `/ureport/index.html` и `/ureport/designer` не отдают дизайнер | **не проверено** — см. §5 |
| Каталог/печать на запущенном приложении | **не проверено** — см. §5 |

## 5. Что не выполнялось и почему

- **Runtime-проверки** (старт приложения, старый URL, каталог, печать, профиль
  `accounting-pilot`) не выполнены: текущее дерево не компилируется из-за чужого
  незакоммиченного среза (`EntityExplorerPilotPanel` вызывает отсутствующий
  `EntitySummary.structure()`). Это не связано с DB1; после завершения среза проверки
  нужно повторить по §5.5 плана U1.
- **PostgreSQL IT** не запускались (нет изменений в коде; к тому же `test-compile`
  блокирован тем же срезом).

## 6. Откат

```bash
# таблица DBVaa25
psql "$PGURL" -f db1-backup-20261018-2206/ureport_template.sql
# пустые таблицы остальных БД
psql "$PGURL_<db>" -f db1-backup-20261018-2206/<db>.ureport_template.sql
# файлы
mv C:/JavaProject/TestVaadin25/UReportFiles.archived-20261018-2207 C:/JavaProject/TestVaadin25/UReportFiles
# конфиг
cp db1-backup-20261018-2206/application-local.properties.bak config/application-local.properties
```

Гранты восстанавливать не нужно — они не удалялись. Старый билд с `ddl-auto=update`
создаст пустую таблицу сам, но данные — только из dump.

## 7. DoD DB1

- [x] D2/D5 подтверждены, решение по шаблонам одно — удалить.
- [x] Инвентарь снят, расхождение «2 строки / 6 файлов» объяснено и записано.
- [x] Резервные копии сняты, восстановление таблицы и файлов проверено.
- [x] Гранты, таблица и файлы выведены; порядок и результаты записаны.
- [x] Конфигурация очищена от `ureport.*`.
- [x] Post-checks по данным пройдены.
- [ ] Runtime-проверки приложения — заблокированы чужим срезом, вынесены в §5.
