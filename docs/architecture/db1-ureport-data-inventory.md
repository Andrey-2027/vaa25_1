# DB1: инвентаризация и вывод данных UReport

- Дата: 2026-10-18.
- Вход: U1.1–U1.4 выполнены (runtime UReport удалён), см.
  [`ureport-removal-plan.md`](ureport-removal-plan.md) и
  [`evidence/u1-ureport-removal-20261018.md`](evidence/u1-ureport-removal-20261018.md).
- Статус DB1: **выполнен на локальном PostgreSQL 2026-10-18** — таблица удалена в пяти БД,
  UReport-грантов не было, каталог файлов заархивирован, конфиг очищен. Полный протокол —
  [`evidence/db1-ureport-data-removal-20261018.md`](evidence/db1-ureport-data-removal-20261018.md).
  Runtime-проверки приложения не выполнены (текущее дерево не компилируется из-за чужого
  среза) — см. §7.
- Решения приняты 2026-10-18
  ([ADR-0027](decisions/ADR-0027-report-composition-without-ureport.md)):
  D2 — браузерный дизайнер не нужен; D5 — **удалить все шаблоны UReport**;
  D9 — старый `/ureport/*` не обслуживается и не перенаправляется.
- Что передаётся в DB1 (раздел 8 плана): таблица `ureport_template`, файловое
  хранилище XML, строки `access_grant`, аудиторский инвентарь «шаблон → файл»
  (решение по каждой строке уже одно — удалить), перечень исторических упоминаний.

Документ — исполняемый runbook: сначала только чтение и резервные копии, затем
удаление отдельным подтверждённым шагом. Ни одна команда ниже не должна
выполняться на рабочей БД без записи результата в evidence.

## 0. Что известно о схеме (проверено по коду до U1.2)

Таблица `ureport_template` (сущность `org.ipro.ureport.dom.UreportTemplate` на
`HEAD~`, удалена в U1.2):

| Колонка | Тип/смысл |
|---|---|
| `id` | `bigint identity`, PK |
| `version` | `bigint`, optimistic lock |
| `created_at`, `modified_at` | `timestamp` |
| `created_by`, `modified_by` | `varchar` |
| `name` | `varchar(250) not null`, unique `uk_ureport_template_name` |
| `file_name` | `varchar(255) not null`, unique `uk_ureport_template_file` |
| `description` | `varchar(1000)` |
| `enabled` | `boolean not null` |
| `target_entity_class` | `varchar(255)`, canonical имя сущности для кнопки «Печать» |

Важное для DB1:

- `spring.jpa.hibernate.ddl-auto=update` **не удаляет** таблицу после снятия
  сущности: после U1.2 она осталась в БД как orphan и не пересоздаётся.
  Обратное тоже верно: старый билд при откате снова увидит свою таблицу.
- XML-файлы лежали вне БД: `ureport.fileStoreDir=${UREPORT_FILESTORE_DIR:ureport-files}`,
  имена — `file_name` из таблицы (`*.ureport.xml`), путь мог быть UNC.
- Гранты — только таблица `access_grant` (других таблиц прав в `platform-rls`
  нет). Ключ измерения — строка: `ENTITY:<FQN>`, `ACTION:<...>`, `REST:<...>`,
  `JOURNAL`, `SETTINGS:<...>`, `*`. Для UReport ожидается
  `ENTITY:org.ipro.ureport.dom.UreportTemplate`; отдельных UReport-`ACTION`-ключей
  в коде не было, но это проверяется запросом, а не предположением.
- `REPORTS:JPQL_PREVIEW` и прочие `REPORTS:*`/`SETTINGS:*`/`JOURNAL` — **не
  UReport**, удалять их нельзя.

## 1. Предусловия и стоп-условия

Перед стартом:

- [x] D2 подтверждён 2026-10-18: браузерный дизайнер не нужен, макеты ведутся в
      Jaspersoft Studio / UniDR ([ADR-0027](decisions/ADR-0027-report-composition-without-ureport.md)).
- [x] D5 подтверждён 2026-10-18: удаляются **все** шаблоны; построчная замена не
      готовится, инвентарь (раздел 3) всё равно снимается для сверки и evidence.
- [ ] Есть окно обслуживания и согласие владельца данных.
- [ ] Известны: версия приложения (commit), профиль, БД, путь файлового
      хранилища, ответственный за восстановление.
- [ ] Резервные копии сняты и проверены восстановлением на отдельной БД
      (раздел 4).

Немедленно остановиться, если:

- `select count(*) from ureport_template` не совпадает с числом файлов и разница
  не объяснена;
- в `access_grant` есть UReport-строки, которых нет в согласованном списке;
- хранилище указывает на сетевой путь, к которому нет доступа на чтение/запись;
- обнаружены живые интеграции, читающие `/ureport/*` как API (D9 допускает 404 для
  людей, но скрытый обмен данными — нет); зафиксировать в evidence перед удалением.

## 2. Снять окружение (без секретов в чате/логах)

Фиксируется в evidence: commit, профиль, хост БД (без пароля), схема, путь
хранилища, дата/время, кто выполнял.

```bash
git rev-parse HEAD
# URL и пользователь БД берутся из локального конфига профиля, значения
# в документ и логи не попадают:
psql "$PGURL" -c 'select current_database(), current_schema(), version();'
printf '%s\n' "${UREPORT_FILESTORE_DIR:-ureport-files}"   # путь, не содержимое
```

Если `UREPORT_FILESTORE_DIR` уже не задан (U1.2 удалил код), путь берётся из
старого локального конфига/эксплуатационной документации окружения; пустой
ответ — это не «файлов нет», а «путь неизвестен» → стоп.

## 3. Read-only инвентаризация

Все запросы — только чтение. Выполнять под ролью с правом `SELECT`.

### 3.1. Существует ли таблица и что в ней

```sql
-- PostgreSQL: null = таблицы нет (значит, окружение уже чистили — выяснить когда)
select to_regclass('public.ureport_template') as table_name;

select count(*) as rows_total,
       count(*) filter (where enabled) as rows_enabled,
       count(*) filter (where not enabled) as rows_disabled,
       count(*) filter (where target_entity_class is not null) as with_print_binding,
       min(created_at) as first_created,
       max(modified_at) as last_modified
from ureport_template;

select id, name, file_name, enabled, target_entity_class,
       created_at, modified_at, created_by, modified_by
from ureport_template
order by id;
```

### 3.2. Сводка по привязкам к печати

```sql
select coalesce(target_entity_class, '<без привязки>') as target,
       count(*) as templates
from ureport_template
group by 1
order by 2 desc, 1;
```

Эти строки — кандидаты на замену JR/UniDR в кнопке «Печать» соответствующей
формы; без них печать из карточки/списка не должна «потерять» сценарий.

### 3.3. Файлы против таблицы (в обе стороны)

```bash
DIR="${UREPORT_FILESTORE_DIR:-ureport-files}"
# Только имена и размеры; содержимое не выгружается.
find "$DIR" -maxdepth 1 -type f -name '*.ureport.xml' -printf '%f\t%s\n' | sort > /tmp/ureport-files.tsv
wc -l /tmp/ureport-files.tsv
# Windows/PowerShell: Get-ChildItem "$DIR" -File | Select Name,Length | Sort Name
```

Если в каталоге встречаются файлы без суффикса `.ureport.xml`, считать надо все
файлы каталога: инвентарь обязан объяснить каждый, а не только «похожие на
шаблон».

```sql
\copy (select file_name from ureport_template order by 1) to '/tmp/ureport-db-files.txt' with (format text)
```

```bash
cut -f1 /tmp/ureport-files.tsv | sort > /tmp/ureport-fs-files.txt
comm -23 /tmp/ureport-db-files.txt /tmp/ureport-fs-files.txt   # есть в БД, нет файла
comm -13 /tmp/ureport-db-files.txt /tmp/ureport-fs-files.txt   # файл есть, записи нет
```

Обе разницы записываются в evidence. «Файл без записи» — кандидат в архив, но не
доказательство мусора: имя могло быть переименовано вручную.

### 3.4. Гранты, относящиеся к UReport

Сначала — обнаружение, а не догадка:

```sql
select dimension, count(*) as grants,
       count(*) filter (where can_read) as can_read,
       count(*) filter (where can_update) as can_update,
       count(*) filter (where can_delete) as can_delete
from access_grant
where dimension ilike '%ureport%'
group by 1
order by 2 desc, 1;

select id, subject_type, subject_key, dimension, dimension_value_id,
       can_read, can_update, can_delete, created_at, modified_at
from access_grant
where dimension ilike '%ureport%'
order by dimension, subject_type, subject_key;
```

Контроль полноты — перечень всех измерений, где вообще есть гранты (чтобы
UReport-ключ не оказался замаскирован):

```sql
select dimension, count(*) from access_grant group by 1 order by 1;
```

Согласованный список удаления — **точные** значения `dimension` из первого
запроса. Удаление по `ilike` в рабочем шаге запрещено: между инвентаризацией и
удалением множество не должно меняться.

Проверки, которые надо записать:

- [ ] Число UReport-грантов совпадает с числом строк в выгрузке.
- [ ] Ни одна строка `REPORTS:*`, `SETTINGS:*`, `JOURNAL`, `*` не попала в
      список.
- [ ] Субъекты из выгрузки не потеряют доступ к другим объектам: у каждого
      проверено, что удаляются только UReport-строки.

### 3.5. Размер и состояние таблицы (для плана окна)

```sql
select pg_size_pretty(pg_total_relation_size('ureport_template')) as total_size,
       pg_size_pretty(pg_relation_size('ureport_template')) as heap_size;
```

## 4. Резервные копии и проверка восстановления

Порядок: сначала копии, потом любые изменения.

```bash
STAMP=$(date +%Y%m%d-%H%M)
mkdir -p "db1-backup-$STAMP"

# Схема + данные + гранты (структура и данные одной командой):
pg_dump "$PGURL" -t public.ureport_template -f "db1-backup-$STAMP/ureport_template.sql"

# UReport-гранты — CSV и SQL, чтобы восстановление не зависело от формата:
psql "$PGURL" -c "\copy (select * from access_grant where dimension ilike '%ureport%' order by id) to 'db1-backup-$STAMP/ureport_grants.csv' with (format csv, header true)"

# Файлы — архив с манифестом хэшей:
tar -czf "db1-backup-$STAMP/ureport-files.tgz" -C "$(dirname "$DIR")" "$(basename "$DIR")"
( cd "$(dirname "$DIR")" && find "$(basename "$DIR")" -type f -print0 | sort -z | xargs -0 sha256sum ) \
  > "db1-backup-$STAMP/ureport-files.sha256"

sha256sum "db1-backup-$STAMP"/* > "db1-backup-$STAMP/SHA256SUMS"
```

Проверка восстановления (обязательна до удаления):

```bash
createdb db1_restore_check
psql db1_restore_check -f "db1-backup-$STAMP/ureport_template.sql"
psql db1_restore_check -c 'select count(*) from ureport_template;'
# число должно совпасть с инвентарём; затем:
dropdb db1_restore_check
```

Для файлов: распаковать архив во временный каталог, сверить
`sha256sum -c ureport-files.sha256`, число файлов и имена с `/tmp/ureport-files.tsv`.

Место хранения копий: вне сервера приложения, с ограниченным доступом; в
evidence — путь, размер, хэш `SHA256SUMS`, дата.

## 5. Аудиторский инвентарь D5 (заполняется до удаления)

Решение по каждому шаблону уже принято: **удалить** (D5, 2026-10-18,
[ADR-0027](decisions/ADR-0027-report-composition-without-ureport.md)). Поэтому таблица
ниже — не выбор судьбы шаблона, а его учёт: что именно было и где лежало. Замена на
JR/UniDR в рамках U1/DB1 не готовится.

| id | name | file_name | enabled | target_entity_class | используется (печать/ссылки) | файл найден | в бэкапе | примечание |
|---|---|---|---|---|---|---|---|---|
| | | | | | | да / нет | да / нет | |

Правила заполнения:

- «используется» подтверждается не словами, а проверкой: кнопка «Печать» на
  форме `target_entity_class`, ссылки в документации, обращения к `/ureport/*`
  в логах доступа. Это фиксирует **потерянный сценарий**, но не меняет решение:
  печать, если она понадобится, делается новым JR/UniDR-отчётом.
- «файл найден» — строка таблицы сверена с каталогом (раздел 3.3), а не угадана по
  имени; «в бэкапе» — файл входит в `ureport-files.sha256` и проверен при
  восстановлении (раздел 4).
- Шаблон без файла или файл без шаблона — стоп-условие, а не повод удалить «как есть».

Итог инвентаря переносится в evidence вместе с выгрузкой из 3.1 и списком
потерянных сценариев печати (если они есть).

## 6. План вывода (после бэкапов и сверки инвентаря)

Порядок выбран так, чтобы откат был возможен на каждом шаге: сначала права
(легко восстановить CSV), затем таблица (есть dump), затем файлы (архив).

1. **Гранты.** В одной транзакции удалить ровно согласованный список:

   ```sql
   begin;
   delete from access_grant
    where id in (/* id из выгрузки 3.4, вставленные явно */);
   -- ожидаемое число строк:
   -- commit;  -- только после сверки rowcount
   ```

   Сразу после — приложение или SQL-проверка: `REPORTS:*`, `JOURNAL`,
   `SETTINGS:*` не изменились; число оставшихся грантов = прежнее минус
   удалённое.

2. **Таблица.** Убедиться, что dump восстановим (раздел 4), затем:

   ```sql
   drop table if exists ureport_template;
   ```

   Уникальные индексы `uk_ureport_template_*` уходят вместе с таблицей; отдельных
   `DROP INDEX` не требуется.

3. **Файлы.** Не удалять, а переместить в архив:

   ```bash
   mv "$DIR" "${DIR}.archived-$(date +%Y%m%d)"
   ```

   Каталог `ureport-files` после этого не должен создаваться при старте: код
   UReport удалён, свойства `ureport.*`/`UREPORT_FILESTORE_DIR` больше не
   читаются.

4. **Конфигурация окружения.** Удалить из локальных/серверных конфигов остатки
   `ureport.fileStoreDir`, `ureport.disableFileProvider`,
   `ureport.disableHttpSessionReportCache`, `ureport.debug` и переменную
   `UREPORT_FILESTORE_DIR`. В example-конфиге их уже нет — проверять только
   реальные окружения.

5. **Уборка.** `vacuum (analyze) access_grant;` и `vacuum` на БД после `drop`
   (autovacuum справится, но для evidence полезно зафиксировать).

6. **Evidence.** Записать: дату, commit, кто выполнял, counts до/после, хэши
   бэкапов, путь архива файлов, итоговый инвентарь D5, ссылку на этот runbook.

## 7. Проверка после удаления

- [ ] Приложение стартует с тем же профилем; в логе нет ошибок про
      `ureport_template`/`ureport.*`.
- [ ] `select to_regclass('public.ureport_template')` → `null`.
- [ ] `select count(*) from access_grant where dimension ilike '%ureport%'` → `0`.
- [ ] `REPORTS:JPQL_PREVIEW` и остальные `REPORTS:*` на месте (SQL до/после).
- [ ] Каталог отчётов: UDR/JR/UniDR открываются, запускаются, печатаются;
      печать из списка и карточки не потеряла сценарии из `target_entity_class`.
- [ ] `GET /ureport/index.html` и `/ureport/designer` не отдают дизайнер
      (ожидаемо 404/302 — код фиксируется, а не трактуется как успех).
- [ ] Файловый каталог на старом пути отсутствует; архив доступен и сверен по
      `sha256`.
- [ ] Нет обращений к старому URL в логах доступа за период наблюдения.

## 8. Откат

| Шаг | Откат |
|---|---|
| Удаление грантов | `\copy access_grant from 'ureport_grants.csv' csv header` (id сохраняются) |
| `drop table` | `psql -f ureport_template.sql` (схема+данные), затем сверить count |
| Перемещение файлов | `mv "${DIR}.archived-<дата>" "$DIR"` |
| Откат версии приложения | старый билд с `ddl-auto=update` сам создаст пустую таблицу, если её нет; данные — из dump |

Откат проверяется на отдельной БД/копии, а не на рабочей.

## 9. Что нельзя удалять в DB1

- `REPORTS:*`, `SETTINGS:*`, `JOURNAL`, `*` и любые гранты без `ureport` в
  ключе — это права других подсистем.
- `report_template` (UDR), `jrxml_template` и `jrxml-files` — живые движки.
- Исторические документы (`UnionReport1.md`, `docs/plans/*.log`, старые
  evidence/`.sha256`) — по плану U1 они не переписываются.
- Внешний checkout `../UReportFork2/ureport3` — вне GitVaa, удаляется отдельным
  решением владельца.

## 10. Definition of Done для DB1

- [x] D2/D5 подтверждены (2026-10-18), решение по шаблонам одно — удалить.
- [x] Инвентарь (таблица, файлы, гранты) снят, сверен с файлами и приложен к evidence:
      2 строки, 6 файлов, расхождение объяснено (4 файла — остатки удалённых записей).
- [x] Резервные копии сняты, восстановление проверено (таблица — 2 строки, файлы — 6/6).
- [x] Гранты (UReport-строк 0), таблица (5 БД) и файлы (каталог заархивирован) выведены.
- [x] Конфигурация очищена от `ureport.*`/`UREPORT_FILESTORE_DIR`.
- [ ] Проверки раздела 7 на запущенном приложении — **заблокированы чужим срезом**
      (`test-compile` не проходит); после его завершения повторить старт, старый URL,
      каталог/печать.
- [x] Evidence содержит counts до/после, хэши и инвентарь; потерянный сценарий печати
      один — `org.ip.model.UnitOfMeasurement` (шаблон «UReport»), замена по D5 не готовилась.
