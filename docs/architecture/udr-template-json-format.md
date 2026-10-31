# Формат JSON-обмена шаблона UDR (заморожен для R2/S8)

- Дата фиксации: 2026-10-22.
- Основание: R2/S8 — «после удаления кода должны остаться описание формата и
  проверяемые примеры/инструмент чтения»
  ([план](../plans/query-report-separation-plan.md), §R2 п. 7).
- Источник формата: `org.ipro.reportstudio.transfer.ReportTemplateExchange`
  (`FORMAT = ipro-report-template`, `SCHEMA_VERSION = 3`). Код удаляется в R3,
  документ остаётся.
- Примеры: [`evidence/r2-udr-json-samples/`](evidence/r2-udr-json-samples) —
  шесть шаблонов из локальной `DBVaa25`, выгруженных из БД в этой схеме.
- Читатель: [`org.ip.archive.UdrTemplateArchive`](../../src/main/java/org/ip/archive/UdrTemplateArchive.java)
  — не зависит от отчётного DOM и переживает R3.

## 1. Конверт

```json
{
  "format": "ipro-report-template",
  "schemaVersion": 3,
  "template": { ... }
}
```

| Поле | Тип | Обязательно | Смысл |
|---|---|---|---|
| `format` | строка | да | Всегда `ipro-report-template`. Другое значение — не наш архив |
| `schemaVersion` | целое | да | Единственная поддерживаемая версия — `3`. Формат заморожен: новая версия не вводится, потому что писателя больше не будет |
| `template` | объект | да | Один шаблон; массив шаблонов форматом не предусмотрен |

Локальные технические поля (`id`, `version`, `state`, `createdAt/By`,
`modifiedAt/By`, `template_id`) в формат не входят. Перенос между БД не должен
зависеть от последовательностей; связи бэндов заданы локальными ключами.

## 2. `template`

| Поле | Тип | Смысл |
|---|---|---|
| `name` | строка | Имя шаблона, не пустое |
| `description` | строка/null | Описание |
| `targetEntityClass` | строка/null | FQCN типа для печати из списка/карточки |
| `jpql` | строка | Авторский запрос; для `VISUAL` — последний скомпилированный текст |
| `querySource` | `MANUAL` \| `VISUAL` | Источник запроса |
| `maxRows` | целое | Лимит строк запуска |
| `timeoutMs` | целое | Таймаут выполнения |
| `advanced` | boolean | Расширенный режим редактора |
| `pageSize` | `A4`/`A5`/`A3`/`LETTER`/`LEGAL`/null | Размер страницы |
| `pageOrientation` | `PORTRAIT`/`LANDSCAPE`/null | Ориентация |
| `baseFontSize` | целое/null | Базовый кегль |
| `gridEnabled` | boolean/null | Сетка |
| `stripeRows` | boolean/null | Полосы строк |
| `visualFilterJson` | строка/null | JSON визуального фильтра (непрозрачен для архива) |
| `visualQueryJson` | строка/null | JSON визуального пакета (непрозрачен для архива) |
| `params` | массив | Параметры, см. §3 |
| `bands` | массив | Бэнды, см. §4 |
| `orders` | массив | Сортировки, см. §5 |

`visualFilterJson`/`visualQueryJson` хранятся как строки, а не вложенные
объекты: их схема принадлежит query-слою и архивируется отдельно, а не
переписывается этим форматом.

## 3. Параметр (`params[]`)

| Поле | Тип | Смысл |
|---|---|---|
| `name` | строка | Имя `:param`, не пустое |
| `caption` | строка/null | Подпись |
| `kind` | `SCALAR`/`ENTITY`/`ENTITY_LIST`/`PERIOD` | Вид |
| `entityClass` | строка/null | Обязателен для `ENTITY`/`ENTITY_LIST` |
| `valueSource` | `FORM`/`DEFAULT`/`CONTEXT`/`COMPUTED` | Источник значения |
| `required` | boolean | Обязателен ли |
| `showOnForm` | boolean | Показывать на форме запуска |
| `defaultValue` | строка/null | JSON-представление константы |
| `computed` | `NONE`/`NOW`/`CURRENT_USER`/`RLS_ORG` | Для `COMPUTED` |
| `position` | целое | Порядок |

## 4. Бэнд (`bands[]`)

| Поле | Тип | Смысл |
|---|---|---|
| `key` | строка | Локальный ключ в пределах шаблона, уникальный |
| `parentKey` | строка/null | Ключ родительского бэнда |
| `kind` | `REPORT_HEADER`/`PAGE_HEADER`/`GROUP_HEADER`/`DETAIL`/`GROUP_FOOTER`/`PAGE_FOOTER`/`REPORT_FOOTER`/`NO_DATA` | Вид |
| `position` | целое | Порядок в шаблоне |
| `groupField` | строка/null | Поле группировки у `GROUP_*` |
| `startNewPage` | boolean/null | Группа с новой страницы |
| `titleWidth` | целое/null | Ширина подписи группы |
| `headerLayout` | `VALUE`/`TITLE_AND_VALUE`/`EMPTY`/null | Раскладка заголовка группы |
| `fields` | массив | Поля бэнда, см. ниже |

Поле (`fields[]`):

| Поле | Тип | Смысл |
|---|---|---|
| `kind` | `COLUMN`/`TEXT`/`ROW_NUMBER`/`EXPRESSION`/`FORMULA` | Вид |
| `queryField` | строка/null | Путь поля запроса; обязателен для `COLUMN` |
| `text` | строка/null | Текст/шаблон `{alias}` для `TEXT`/`EXPRESSION` |
| `caption` | строка/null | Заголовок колонки |
| `width` | целое/null | Ширина, px |
| `format` | строка/null | Формат значения |
| `border` | boolean/null | Граница |
| `visible` | boolean | Видимость |
| `aggregation` | `NONE`/`SUM`/`COUNT`/`COUNT_ROWS`/`AVG`/`MIN`/`MAX` | Агрегат (в footer-бэндах) |
| `alignment` | `LEFT`/`CENTER`/`RIGHT` | Выравнивание |
| `position` | целое | Порядок |

Инварианты: `key` уникален; `parentKey` либо отсутствует, либо указывает на
существующий `key`; `parentKey == key` встречается в старых данных (корневые
групповые заголовки писали `parent_id = id`) — архив сохраняет это как есть, а
читатель считает такие бэнды корневыми и отдельно показывает их число.

## 5. Сортировка (`orders[]`)

`columnName` (строка), `direction` (`ASC`/`DESC`), `position` (целое).

## 6. Версия и совместимость

Версия `3` — последняя. Политика после R3:

- файлы с `schemaVersion != 3` читатель не открывает и называет версию;
  «догадаться» о предыдущей схеме нельзя — писателя, который её создавал, уже нет;
- неизвестное значение перечисления (`kind`, `aggregation`, `valueSource`,
  `pageSize` и т.д.) читатель не принимает: архив должен быть проверяемым, а не
  «примерно понятным»; добавочные поля, которых не было в схеме, не мешают
  чтению — формат заморожен, но не обязан падать на будущих пометках;
- `format` и `schemaVersion` — часть имени файла в примерах и в будущих архивах,
  чтобы файл нельзя было перепутать с JSON UniDR (там `fileVersion` и
  `definition`).

## 7. Чем архив не является

- Это не импорт: `UdrTemplateArchive` ничего не сохраняет и не исполняет.
- Это не нейтральная модель: поля и бэнды — модель UDR. Пересборка в UniDR
  выполняется вручную и проверяется на тех же данных; автоматический конвертер
  не пишется (решение S8, [evidence](evidence/r2-udr-inventory-20261022.md)).
- Это не замена резервной копии БД: примеры фиксируют форму и содержимое
  шаблонов, а `report_template` и связанные таблицы архивируются отдельно перед
  физическим удалением (R3/DB1-подобная процедура).
