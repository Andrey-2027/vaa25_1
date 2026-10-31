# Архивные JSON-шаблоны UDR (R2/S8)

- Источник: локальная PostgreSQL-БД `DBVaa25`, таблицы `report_template`,
  `report_band`, `report_field`, `report_param`, `report_order`.
- Дата выгрузки: 2026-10-22. Все шаблоны — `DRAFT` 2026-08, автор `admin`.
- Формат: [`ipro-report-template/3`](../../udr-template-json-format.md).
- Команда выгрузки (повторяемая):

```text
psql "$URL" -U "$USER" -At -v id=<old_id> \
  -f scripts/r2-udr-export-json.sql > <file>.json
```

| Старый id | Файл | Имя | Решение (предложение) |
|---|---|---|---|
| 1 | `01-form-pervaya.json` | Form первая | архив |
| 2 | `02-434434.json` | 434434 | архив |
| 3 | `03-435435.json` | 435435 | архив |
| 4 | `04-specs-by-journal.json` | СпецификацииПоЖурналу | пересобрать в UniDR |
| 6 | `06-test1.json` | Тест1 | архив |
| 7 | `07-pro.json` | Проь | архив |

Решения и правила переноса — в [`r2-udr-id-map.csv`](../r2-udr-id-map.csv) и
[evidence](../r2-udr-inventory-20261022.md). Файлы не редактируются вручную:
они фиксируют то, что реально лежало в БД (включая самоссылки `parentKey == key`
у шаблонов 2 и 4).

Проверка: `UdrTemplateArchiveTest` читает этот каталог и падает, если формат,
имена или связи бэндов перестали соответствовать схеме.
