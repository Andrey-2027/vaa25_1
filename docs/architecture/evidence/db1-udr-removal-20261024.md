# DB1 UDR: таблицы движка удалены из DBVaa25 (2026-10-24)

- Дата: 2026-10-24.
- Основание: движок UDR удалён в
  [R3.2](r3-2-udr-engine-removal-20261024.md); владелец признал все UDR-отчёты
  тестовыми ([R2](r2-udr-inventory-20261022.md), решение 2026-10-23). Отдельным
  решением 2026-10-24 подтверждено: рабочая БД одна — `DBVaa25`, и она
  тестовая, удаление разрешено.
- Срез: инвентарь → бэкап с проверкой восстановления → удаление пяти таблиц →
  post-checks → компиляция приложения. **DB1 UDR закрыт.**
- Runbook: [`db1-udr-data-inventory.md`](../db1-udr-data-inventory.md).

## 1. Инвентарь до удаления

[`scripts/db1-udr-inventory.sql`](../../../scripts/db1-udr-inventory.sql),
read-only. Вывод: `outputs/db1-udr-inventory-DBVaa25-final-20261024.txt`
(gitignored).

| `report_template` | `report_band` | `report_field` | `report_param` | `report_order` | UDR-гранты |
|---|---|---|---|---|---|
| 6 | 18 | 27 | 2 | 2 | 0 |

Схема, PK/UNIQUE/FK, шапки шаблонов и отсутствие внешних ссылок — те же, что в
[подготовке](db1-udr-preparation-20261024.md) и R2: 6 `DRAFT`-шаблонов
(id 1, 2, 3, 4, 6, 7), других таблиц, ссылающихся на UDR, нет.

## 2. Бэкап

```text
outputs/db1-udr-backup-20261024-210326/   (gitignored, вне коммита)
  DBVaa25.udr.sql            20 511 B, 5 CREATE TABLE, 5 COPY
  DBVaa25.udr-counts.txt     band 18, field 27, order 2, param 2, template 6
  DBVaa25.udr-grants.csv     только заголовок (UDR-грантов 0)
  SHA256SUMS.txt
```

Восстановление проверено автоматически скриптом бэкапа: одноразовая БД, restore
дампа, сверка counts по каждой таблице — совпало, проверочная БД удалена.
Хэши трёх артефактов пересчитаны независимо (`sha256sum` после `tr -d '\r'`):
3/3 OK.

## 3. Удаление

```bash
psql -h localhost -U postgres -d DBVaa25 -X -v ON_ERROR_STOP=1 \
     -v confirm=DROP-UDR-TABLES -v confirm_db=DBVaa25 \
     -f scripts/db1-udr-removal.sql
```

Exit 0. Вывод сохранён: `outputs/db1-udr-removal-DBVaa25-20261024.txt`
(gitignored). Ход: counts до удаления (6/18/27/2/2), UDR-гранты 0, одна
транзакция, `DROP TABLE` ×5 в порядке дочерних, `COMMIT`.

## 4. Post-checks

| Проверка | Результат |
|---|---|
| `to_regclass` всех пяти таблиц | `NULL` |
| sequence `report_%` | 0 |
| relation `report_%` (таблицы, индексы, sequence) в `public` | нет ни одной |
| `information_schema.tables` по пяти именам | 0 |
| `access_grant` | не трогался, UDR-строк было 0 |
| `DO`-блок скрипта | «все пять таблиц удалены в БД DBVaa25» |

## 5. Компиляция

```text
mvn -B -q -DskipTests compile   exit 0
```

Maven — из IntelliJ IDEA (отдельной установки `mvn` на PATH нет). Компиляция
основного дерева проходит: после R3.2 ни код приложения, ни `platform-*` не
ссылаются на `report_template*`, поэтому удаление таблиц на сборку не влияет.

## 6. Что не делалось

- Runtime-проверка приложения (старт, каталог отчётов, UniDR/JR) после удаления
  не выполнялась — срез ограничен удалением и компиляцией; runbook §5 описывает,
  что смотреть при следующем запуске.
- Других БД нет: `DBVaa25` — единственная рабочая, остальные локальные БД
  (v1/v2/v3, navigation pilot) таблиц не содержали и этим срезом не менялись.
- Тестовый прогон не выполнялся: цель среза — компиляция, а полный `test-compile`
  упирается в незакоммиченный дрейф параллельной работы по Explorer (см. R3.2).

## 7. Закрытие

- Все пункты DoD runbook §7 выполнены: инвентарь и бэкап рабочей БД сняты,
  удаление выполнено и записано здесь.
- **DB1 UDR закрыт.** R3.2 закрыт полностью: код движка удалён ранее, физические
  таблицы — этим срезом.
- Откат возможен только из дампа `DBVaa25.udr.sql` (runbook §6); каталог бэкапа
  лежит вне git и должен сохраняться, пока он нужен.
