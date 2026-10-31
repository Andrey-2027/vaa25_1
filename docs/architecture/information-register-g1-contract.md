# Регистр сведений G1: контракт первого среза (R0)

Дата: **2026-10-31**, Europe/Moscow. Пакет: **G1.R0** из
[плана подготовки](information-register-g1-preparation-plan.md#g1r0--зафиксировать-контракт-первого-среза).
[плана подготовки](../plans/information-register-g1-preparation-plan.md#g1r0--зафиксировать-контракт-первого-среза).
Статус: **контракт зафиксирован; production-код, schema resources и `RecipeCatalog` не изменены;
draft и генерация регистра не подключены.**

Документ нормативен для G1.R1–R7. Он описывает то, что будет реализовано, и не объявляет
реализованным ничего из этого. JSON-примеры в
[`contracts/register`](contracts/register) — **спецификации**, а не доказательство
поддержанного импорта: текущий `DraftJsonCodec` принимает только envelope v1/v2
([`DraftJsonCodec.envelope`](../../entity-design-json/src/main/java/org/ipro/design/json/DraftJsonCodec.java)),
поэтому v3 сегодня отвергается. Импорт и семантические проверки появляются в R2.

Основание профиля: §5 [плана подготовки](../plans/information-register-g1-preparation-plan.md#5-контракт-первого-рецепта-решение-до-реализации-reg-d2).
Ограничения взяты из существующих проверок: `RegisterMetadataValidator`,
`RegisterDescriptor`, `RegisterKeyWrites`, `RegisterKeyWritePermissions`,
`RegisterKeyWriteGuardInstaller`, `RegisterStartupValidator`, `DraftValidator`,
`JavaSourceBuilder`.

## 1. Границы первого среза

| Входит | Не входит |
| --- | --- |
| Рецепт `independent-register-v1@1` (самостоятельный регистр) | `recorded-register-v1@1`, регистраторы, строки, принадлежащие документу |
| Режимы `NONE` и `DAY` | `MONTH`, `YEAR`, `EXACT`-периодичность как выбор рецепта |
| Минимум одно измерение и один ресурс | Регистр без измерений или без ресурсов |
| Скалярные поля и ссылочные измерения | Секции, owner, произвольные дополнительные реквизиты |
| Именованные `set`/`delete` + действующий canonical CRUD | Named-only профиль, `CHANGE_KEY`, перенос строки |
| Одна новая таблица на регистр | Изменение существующих таблиц, второй persistence unit |

`recorded-register-v1@1` остаётся `HANDOFF_ONLY` и в этом срезе не получает ни envelope,
ни фактов, ни генератора. Регистр, подчинённый документам, — отдельный следующий рецепт.

## 2. Режимы

| Свойство | `NONE` | `DAY` |
| --- | --- | --- |
| `@RegisterMetadata.period` | `""` (значение по умолчанию) | `"period"` |
| `@RegisterMetadata.periodicity` | не задаётся (остаётся `EXACT`) | `RegisterPeriodicity.DAY` |
| База | `org.ipro.crud.BaseEntity` | `PeriodicRegisterEntryBase` |
| Поле периода | отсутствует | наследуемое `LocalDateTime period` |
| Колонка периода | отсутствует | `period_date`, `NOT NULL`, `updatable = false` |
| Нормализация | нет | `RegisterPeriodListener` из базы: начало дня |
| Business key | `dimensions` в объявленном порядке | `dimensions` в объявленном порядке, затем `period_date` |
| `asOf` / slice | нет | есть (`getLast`, срез на дату) |

`NONE` — это `period = ""`, **а не новый элемент** `RegisterPeriodicity`. Перечисление
остаётся `EXACT, DAY, MONTH, YEAR`; новый режим не появляется ни в enum, ни в
`RegisterMetadata`.

Для `DAY` период — `LocalDateTime` с нормализацией начала дня (не `LocalDate` документа).
Один и тот же день — один ключ: `RegisterPeriodicity.DAY.normalize` и
`RegisterKeyWriteGuardInstaller` сравнивают нормализованный период, поэтому правка времени
внутри дня не считается сменой ключа.

## 3. Роли и инварианты

Роль поля задаётся **UUID-ссылкой** в `register.dimensions` / `register.resources`, а не
именем и не копией типа: переименование поля не меняет роль.

- **I1.** Каждое пользовательское поле имеет ровно одну роль — `DIMENSION` или `RESOURCE`.
  Поле, не входящее ни в один список, и UUID, входящий в оба списка, — ошибки.
- **I2.** Порядок `dimensions` определяет порядок ключевых колонок, порядок колонок в
  `UNIQUE` и порядок группировки среза. Порядок `resources` ключа не задаёт.
- **I3.** Технические поля `BaseEntity` (`id`, `version`, `createdAt`, `modifiedAt`,
  `createdBy`, `modifiedBy`) зарезервированы и не входят в business key; их нельзя объявить
  измерением или ресурсом.
- **I4.** В `DAY` имя `period` зарезервировано под builtin-период: пользовательское поле с
  таким именем отклоняется, а роль `DIMENSION`/`RESOURCE` для периода не задаётся.
  В `NONE` периода нет вовсе — ни поля, ни `asOf`, ни среза.
- **I5.** Каждое измерение обязательно: `serverRequired = true`, колонка `NOT NULL`,
  `updatable = false`. Ресурс может быть необязательным; `serverRequired = false` означает
  nullable-колонку.
- **I6.** `UNIQUE` — ровно по ключевым колонкам в порядке измерений (для `DAY` — с
  `period_date` последней). Дополнительных колонок, уникальности по ресурсам и частичных
  индексов нет.
- **I7.** Изменение ключа запрещено: `keyChangeAllowed = false`, ключевые колонки
  `updatable = false`. Именованные `set`/`delete` ключ не меняют: другой ключ — другая строка.
- **I8.** Секций нет (`sections = []`), owner нет, регистратора нет, generic-путь записи
  не отключается (см. §7).

## 4. Типы полей

| Тип | Измерение | Ресурс | Ограничения | Java | Колонка |
| --- | --- | --- | --- | --- | --- |
| `STRING` | да | да | `length` обязателен; измерение 1..200, ресурс 1..4000 | `String` | `varchar(length)` |
| `INTEGER` | да | да | нет | `Integer` | `integer` |
| `LONG` | да | да | нет | `Long` | `bigint` |
| `DECIMAL` | нет | да | `precision` 1..19, `scale` 0..precision | `BigDecimal` | `numeric(precision, scale)` |
| `BOOLEAN` | нет | да | нет | `Boolean` | `boolean` |
| `REFERENCE` | да | нет | `reference.typeId` обязателен, цель — §5 | тип цели | `<column>_id` |
| `DATE`, `DATETIME` | нет | нет | — | — | вне первого среза |

Почему именно так:

- Измерения ограничены точно сравнимыми типами. `DECIMAL` в ключе сравнивается по величине
  при разном scale (`numeric(19,4)` возвращает `100.0000` для записанного `100`) — это
  допустимо для ресурса (`RegisterKeyWrites.sameValue`), но делает ключ и `UNIQUE`
  зависимыми от СУБД.
- Лимит 200 для строкового измерения держит btree-ключ в переносимом размере (PostgreSQL
  ограничивает строку индекса ~2704 байтами, UTF-8 символ — до 4 байт); 4000 для ресурса
  покрывает и Oracle `VARCHAR2(4000)`, и MSSQL.
- `DATE`/`DATETIME` не объявлены: в первом срезе нет ни одного сценария приёмки на них
  (A2.1 и R6 используют строку, число и ссылку), а собственная временная семантика
  регистра уже выражена периодом. Добавление — явный bump профиля, а не «естественное»
  расширение.

Ограничения — правила рецепта, а не переизобретение `RegisterMetadataValidator`:
валидатор платформы проверяет `LocalDateTime` периода, `NOT NULL`/`updatable` ключа и
точный `UNIQUE`; ограничения длины/точности и набор типов по роли проверяет `DraftValidator`
(новые коды — §9), а физические типы — генератор.

## 5. Ссылочные измерения

- Ссылка допустима **только для измерения**; ресурс-ссылка в первом срезе отклоняется.
- Цель считается допустимой ровно тогда, когда `ReferenceTargetDescriptor.supported()`:
  `exposure = STANDARD_ROOT`, `identityType = java.lang.Long`, `representation = YES`,
  `selection = YES`, плюс корректное Java-имя (`sourceTypeName`). Это существующий
  reference-контракт G1; рецепт его не ослабляет и не расширяет.
- Неизвестные факты цели (`Eligibility.UNKNOWN`, отсутствие записи в facts) — не
  «поддержано по умолчанию», а `PROJECT_FACT_UNKNOWN`.
- Цель-регистр (`kind = INFORMATION_REGISTER`) в первом срезе запрещена явно: ссылка
  регистр → регистр создаёт циклы и второй контракт среза, которого нет в приёмке.
  Отказ — `REFERENCE_UNAVAILABLE` с причиной `registerTarget`.
- Цель — только существующий тип: ссылка на тип из того же черновика отклоняется.
- Физически: `@ManyToOne(fetch = LAZY, optional = false)`, явный `@JoinColumn(nullable = false)`,
  без cascade и без orphan removal. `NOT NULL` ссылки следует из I5.

## 6. Имена и хранение

- `tableName` по умолчанию — `snake(className)`; `columnName` — `snake(name)`, для
  `REFERENCE` — `snake(name) + "_id"` (совпадает с `DraftSuggestions.column`).
- Профиль имени — существующий переносимый: `[a-z][a-z0-9_]{0,62}`, без зарезервированных
  SQL-слов из `DraftValidator`, без кавычек, регистра и не-ASCII.
- Колонка периода фиксирована: `period_date` (контракт `PeriodicRegisterEntryBase`).
- Технические колонки — эффективные имена `BaseEntity`: `id`, `version`, `created_at`,
  `modified_at`, `created_by`, `modified_by`. Черновик их не описывает, `UNIQUE` их не
  содержит.
- Имя `UNIQUE` — `uk_<table>_key`; имя FK — `fk_<table>_<column>`.
- Совпадение имени таблицы или колонки — `DUPLICATE_STORAGE_NAME`; нарушение профиля —
  `INVALID_STORAGE_NAME`; занятая существующая таблица — `DUPLICATE_STORAGE_NAME` по
  `entity.tableName`.

## 7. Возможности чтения и записи

Первый write profile фиксирован одним значением `NAMED_AND_CANONICAL_CRUD`. Другие значения
в envelope не поддержаны (`REGISTER_WRITE_PROFILE_UNSUPPORTED`); сменить профиль можно
только новой версией контракта, а не настройкой валидатора или скрытием кнопок.

**Чтение.** `LIST`, `DETAIL`, `LOOKUP` — существующий recipe reads. Прикладное чтение идёт
через canonical `EntityDataAccess` и `RegisterReaderFactory`. Для `DAY` доступны
`getLast`/срез; для `NONE` — нет, и отсутствие периода не изображается пустым срезом.

**Именованные операции.** `RegisterKeyWrites.set/delete` (ADR-0023), `keyWriteAllowed = true`:

| Операция | Требуемые права | Семантика |
| --- | --- | --- |
| `set` без `expectedVersion` | `REGISTER:<FQN>:SET_KEY` + ENTITY `READ`, `CREATE`, `UPDATE` | upsert: нет строки — создать; повтор тех же ресурсов — `UNCHANGED` |
| `set` с `expectedVersion` | те же | проверяемая запись: `ABSENT` вместо создания, `VERSION_CONFLICT` вместо перезаписи |
| `delete` | `REGISTER:<FQN>:DELETE_KEY` + ENTITY `READ`, `DELETE` | версия обязательна; отсутствие — `ABSENT` |

**Canonical CRUD.** `RegisterDescriptor.genericWritesAllowed()` возвращает `true` для
самостоятельного регистра, поэтому стандартные формы сохраняют `CREATE`/`UPDATE`/`DELETE`.
Это не открывается флагом `keyWriteAllowed` и не отключается им: запрет generic-записи
потребовал бы отдельного серверного guard'а, различающего command scope и generic intent,
и отдельной security-приёмки — такого расширения в первом срезе нет.

**Смена ключа.** `keyChangeAllowed = false`: команда `CHANGE_KEY` не регистрируется,
`RegisterKeyChangePermissions` её не выдаёт, а `RegisterKeyWriteGuardInstaller` на
`PreUpdate` отвергает любое изменение нормализованного ключа, включая managed flush и generic
UPDATE (`REGISTER_KEY_CHANGE_REQUIRED`). Ключевые колонки `updatable = false`; это проверяет
и `RegisterMetadataValidator`, и startup-проверка отображения.

**Границы прав.** Ни одна операция не выдаёт гранты: определения C5 регистрируются, но
`EXECUTE`/entity-гранты назначаются отдельно. RLS применяется по значениям строки, включая
ссылочные измерения. Отсутствие гранта — отказ, а не «тихая» запись.

## 8. Envelope v3

Новый envelope — `schemaVersion = 3`, отдельная schema resource `entity-draft-v3.schema.json`
(R2). v1 и v2 сохраняют прежний смысл байт-в-байт; регистровый черновик в них не появляется.

```json
{
  "schemaVersion": 3,
  "recipe": { "id": "independent-register-v1", "version": 1 },
  "draftId": "00000000-0000-4000-8000-000000000101",
  "entity": { "className": "RegionRate", "packageName": { "mode": "EXPLICIT", "text": "com.example.registerconsumer" },
              "label": "Региональные ставки", "titles": { "list": {...}, "item": {...}, "selection": {...} },
              "tableName": { "mode": "AUTO", "text": "" }, "subsystemId": null, "icon": "MONEY" },
  "fields": [ { "id": "...0111", "name": "regionCode", "type": "STRING", "...": "как в v1" } ],
  "sections": [],
  "register": {
    "period": "NONE",
    "writeProfile": "NAMED_AND_CANONICAL_CRUD",
    "dimensions": ["...0111"],
    "resources": ["...0112"]
  }
}
```

Правила envelope:

- `register` обязателен, `owner` отсутствует и запрещён (`additionalProperties: false`).
- `sections` обязателен и пуст; непустой список — `REGISTER_SECTION_UNSUPPORTED` (R2), а не
  молчаливое игнорирование.
- Поле `fields[]` сохраняет форму v1 (`name`, `label`, `type`, `columnName`, `serverRequired`,
  `uiRequired`, `constraints`, `reference`, `applications`): это позволяет R2 переиспользовать
  `FieldDraft`, не заводя второй DTO поля.
- `applications` у регистра фиксированы: `form = list = selection = true`, `table = false`.
  Регистр не имеет строк-секций, поэтому флаг `table` смысла не несёт и не может быть
  включён (`INVALID_APPLICATION`).
- `period` — `"NONE"` или `"DAY"`; `writeProfile` — единственное значение выше.
- Порядок `fields[]` — порядок объявления Java-полей; порядок ролей задают массивы UUID.

Примеры: [`register-draft-none.json`](contracts/register/register-draft-none.json) (`RegionRate`),
[`register-draft-day.json`](contracts/register/register-draft-day.json) (`ItemRate`).
Имена совпадают с планируемыми fixtures A2.1, чтобы R6 мог переиспользовать ожидания.

## 9. Матрица случаев

Машиночитаемая форма — [`register-draft-cases.json`](contracts/register/register-draft-cases.json):
`from` (базовый fixture), `edits` (подмножество JSON Patch: `add`/`remove`/`replace`),
`expect`. Это спецификация для R2: R2 превращает каждый случай в тест `DraftValidator`.
Проверено, что каждый положительный случай валиден по правилам этого документа, а каждый
отрицательный нарушает ровно одно правило (см. §14).

### Положительные

| ID | Случай | Ожидание |
| --- | --- | --- |
| `P01` | `NONE`, одно строковое измерение, decimal-ресурс | valid |
| `P02` | `DAY`, ссылочное измерение, decimal-ресурс | valid |
| `P03` | `NONE`, ссылочное измерение вместо строкового | valid |
| `P04` | два измерения: порядок задаёт порядок ключа | valid |
| `P05` | явные `tableName`/`columnName` по профилю | valid |
| `P06` | необязательный ресурс (`serverRequired = false`) | valid |
| `P07` | `INTEGER`/`LONG`/`BOOLEAN` ресурсы | valid |
| `P08` | границы: строка измерения 200, `scale = precision = 19` | valid |

### Отрицательные

| ID | Случай | Код | propertyKey |
| --- | --- | --- | --- |
| `N01` | измерение добавлено и в ресурсы | `REGISTER_ROLE_DUPLICATE` | `register.resources` |
| `N02` | UUID дважды в `dimensions` | `REGISTER_ROLE_DUPLICATE` | `register.dimensions` |
| `N03` | поле без роли | `REGISTER_ROLE_MISSING` | `register.roles` |
| `N04` | UUID отсутствует в `fields` | `REGISTER_ROLE_UNKNOWN` | `register.resources` |
| `N05` | пустой `dimensions` | `REGISTER_ROLE_MISSING` | `register.dimensions` |
| `N06` | пустой `resources` | `REGISTER_ROLE_MISSING` | `register.resources` |
| `N07` | непустые `sections` | `REGISTER_SECTION_UNSUPPORTED` | `sections` |
| `N08` | `period = MONTH` | `REGISTER_PERIOD_UNSUPPORTED` | `register.period` |
| `N09` | `writeProfile = NAMED_ONLY` | `REGISTER_WRITE_PROFILE_UNSUPPORTED` | `register.writeProfile` |
| `N10` | `DECIMAL` в измерении | `REGISTER_FIELD_ROLE_UNSUPPORTED` | `register.roles` |
| `N11` | `REFERENCE` в ресурсе | `REGISTER_FIELD_ROLE_UNSUPPORTED` | `register.roles` |
| `N12` | пользовательское поле `period` в `DAY` | `RESERVED_FIELD` | `name` |
| `N13` | цель ссылки не поддержана | `REFERENCE_UNAVAILABLE` | `reference.typeId` |
| `N14` | факты цели неизвестны | `PROJECT_FACT_UNKNOWN` | `reference.typeId` |
| `N15` | недопустимое имя колонки | `INVALID_STORAGE_NAME` | `columnName` |
| `N16` | повтор имени колонки | `DUPLICATE_STORAGE_NAME` | `columnName` |
| `N17` | `scale > precision` | `INVALID_CONSTRAINT` | `constraints.scale` |
| `N18` | нет `length` у строки | `VALUE_REQUIRED` | `constraints.length` |
| `N19` | `length` у `DECIMAL` | `INAPPLICABLE_CONSTRAINT` | `constraints.length` |
| `N20` | повтор UUID поля | `DUPLICATE_ID` | `id` |
| `N21` | таблица уже существует | `DUPLICATE_STORAGE_NAME` | `tableName` |
| `N22` | цель ссылки — регистр | `REFERENCE_UNAVAILABLE` | `reference.typeId` |
| `N23` | `applications.table = true` | `INVALID_APPLICATION` | `applications.table` |
| `N24` | необязательное измерение | `REGISTER_DIMENSION_REQUIRED` | `serverRequired` |
| `N25` | `DATE` в ресурсе | `REGISTER_FIELD_ROLE_UNSUPPORTED` | `register.roles` |
| `N26` | строка измерения длиной 201 | `LIMIT_EXCEEDED` | `constraints.length` |

Новые коды `DraftProblem` вводятся R2 (в коде их пока нет): `REGISTER_ROLE_MISSING`,
`REGISTER_ROLE_DUPLICATE`, `REGISTER_ROLE_UNKNOWN`, `REGISTER_SECTION_UNSUPPORTED`,
`REGISTER_PERIOD_UNSUPPORTED`, `REGISTER_WRITE_PROFILE_UNSUPPORTED`,
`REGISTER_FIELD_ROLE_UNSUPPORTED`, `REGISTER_DIMENSION_REQUIRED`. Остальные коды
существуют и переиспользуются без изменения смысла.

### Структурные отказы (не `DraftProblem`)

До семантики их отвергает строгий JSON envelope R2:

| Случай | Отказ |
| --- | --- |
| `schemaVersion = 3` с рецептом catalog/document | envelope: неподдержанный рецепт v3 |
| `schemaVersion = 1/2` с блоком `register` | envelope v1/v2: неизвестное свойство |
| `schemaVersion = 3` c `owner` | envelope v3: неизвестное свойство |
| `register.period` не из `NONE`/`DAY` | envelope: `enum` (в R2 дополнительно семантика) |

## 10. Матрица версий

| Область | Первый срез | Сохранено без изменения |
| --- | --- | --- |
| Recipe | `independent-register-v1@1` (сейчас `HANDOFF_ONLY`) | `recorded-register-v1@1` остаётся `HANDOFF_ONLY` |
| Draft envelope | `schemaVersion = 3`, `entity-draft-v3` | v1/v2 и catalog/document профили |
| Generator | `register-java-v1` | `catalog-java-v4`, `document-java-v4` и legacy `catalog-java-v1` |
| Fingerprint | `plan-fingerprint-v3` только для регистра | F1/F2 байт-в-байт |
| Manifest | `g1-generation-manifest-v2`, ровно 1 entry | M2 без изменений |
| C summary | `g1-c-summary-v2` | C2 без изменений |
| D state | `g1-apply-receipt-v2` / `g1-apply-journal-v2`, `.g1-apply-v2` | D2 без изменений |
| Migration | отдельный scope R7 | ORM `create-drop`/`update` гейт не закрывают |

Почему новый fingerprint профиль, а не F2: F2 сериализует `EntityDraft` и context, но в его
`draft(...)` нет слота под роли/период/write profile — регистровый черновик дал бы тот же
`planId` при изменении ключа. Дописывать поля в F2 нельзя: это изменило бы байты уже
принятых catalog/document планов. F3 добавляет ветку регистра, не трогая F1/F2; C и D
принимают профиль из плана, а не угадывают по имени файла.

## 11. Минимальный manifest и migration scope

**Generated files — ровно один:** `<package path>/<ClassName>.java` (для `RegionRate` —
`com/example/registerconsumer/RegionRate.java`). Никаких repository/config/scanner,
operation adapter, application service и второго объявления того же класса не генерируется;
именованные операции уже предоставляет платформа. ZIP-доставка не нужна: manifest из одного
entry передаётся C/D так же, как catalог из одного файла, но с профилем `register-java-v1`.

**Migration scope (R7, отдельный gate):**

- новая таблица на регистр, существующие таблицы не меняются;
- колонки: `id bigint identity` (PK), `version bigint NOT NULL`, `created_at`, `modified_at`,
  `created_by`, `modified_by`, ключевые колонки в порядке измерений (для `DAY` —
  `period_date` последней), ресурсы;
- `NOT NULL` на ключевых колонках; ресурс — по `serverRequired`;
- точный `UNIQUE uk_<table>_key` по ключевым колонкам в объявленном порядке;
- FK `fk_<table>_<column>` на таблицу цели для каждого ссылочного измерения, `NOT NULL`;
- отдельных индексов сверх `UNIQUE` и FK в первом срезе нет;
- проверка: применение на чистой тестовой БД, затем startup и поведение; физические
  колонки/`NOT NULL`/`UNIQUE`/FK проверяются по схеме, а не по ORM-логу.

## 12. Изменяемые API

| Точка | Изменение | Пакет |
| --- | --- | --- |
| `RuntimeDesignSnapshot` | новый `RegisterFacts` и nullable register-раздел `TypeFacts`; прежние constructors сохранены | R1 |
| `EntityDesignRuntimeProjection`, `EntityDesignRuntimeAutoConfiguration` | optional bridge из validated `RegisterRegistry`; «регистров нет» и «фактов нет» различаются | R1 |
| `EntityDraft` | nullable `RegisterDraft`; шестипараметрический constructor делегирует `null` | R2 |
| `RecipeCatalog` | builtin `period` для `DAY`, `systemFieldNames` дополняются регистровыми резервами, рецепт переходит в `DRAFT_READY` только в R3 | R2/R3 |
| `DraftValidator` | правила ролей, периода, типов по роли, storage и write profile; восемь новых кодов §9 | R2 |
| `CheckedStructure` | проверенная register-проекция (роли, период, ключ, ссылки) | R2 |
| `FieldApplicationMapping` | регистровый projection binding (публикация полей без секций) | R2 |
| `DraftJsonCodec` + `entity-draft-v3.schema.json` | envelope v3, строгие `register`/`sections`; v1/v2 не меняются | R2 |
| `RecipeCatalog.Recipe` / `Phase` | `independent-register-v1@1` → `DRAFT_READY`, затем генерация в R3 | R2/R3 |
| `EntityJavaGenerator` | dispatch `register-java-v1`; unsupported профиль — явный отказ | R3 |
| `PlanFingerprint` / `GenerationManifest` | `plan-fingerprint-v3` с регистровыми фактами; M2 не меняется | R4 |
| `EntityDraftEditorView` (P8) | выбор регистра, роли/период, preview/download; для `NONE` без `asOf`/slice | R5 |
| `scripts/test-entity-generation-g1.ps1` (C) | register suite и проверка одного entry | R4/R6 |
| `GenerationApplyAdapter` (D) | whitelist профиля `register-java-v1`; ledger `.g1-apply-v2` не меняется | R4 |
| `examples/register-consumer` | приёмка runtime (A2.1) и actual generated (A2.2/R6) | A2.1/R6 |
| `platform-register` | изменений нет: движок уже поддерживает профиль; `RegisterMetadataValidator` не ослабляется | — |

Публичные DTO расширяются с сохранением прежних constructors/accessors. `register` не
попадает в `FieldDraft` и не превращается в 13-й «тип поля»: роль — отдельная структура
черновика.

## 13. Что подтверждено и что не подтверждено

Проверено R0 (см. §14): спецификации непротиворечивы, каждый положительный кейс валиден,
каждый отрицательный нарушает ровно одно заявленное правило, имена/роли/ключи выводятся
однозначно.

**Не проверено и не заявлено R0:**

- импорт v3 и любые семантические проверки в `DraftValidator` — их нет до R2;
- проекция `RegisterFacts`, генерация Java, C/D, миграция и UI — R1/R3/R4/R5/R7;
- runtime-приёмка A2.1 и внешняя generated-приёмка A2.2;
- поведение на PostgreSQL и MSSQL (в R0 нет ни одной БД);
- `MONTH`/`YEAR`/`EXACT`-рецепты, `recorded-register-v1`, named-only профиль и `CHANGE_KEY`.

## 14. Проверки R0 и evidence

Проверка спецификаций: [`checks.mjs`](../../outputs/register-g1/r0/20261031-r0-contract/checks.mjs)
применяет `edits` каждого случая к базовому fixture и проверяет результат по правилам §2–§9
(роли, типы по роли, ограничения, имена, резервы, ссылки, applications, `UNIQUE`-порядок),
затем сверяет набор кодов с `expect`. Это проверка **документа**, а не codec.

Результат, лог и summary:
[`outputs/register-g1/r0/20261031-r0-contract`](../../outputs/register-g1/r0/20261031-r0-contract/summary.json).
