# ADR-0007: canonical data-access path и таксономия экспозиции типов

- Статус: принято; C4.0–C4.8 закрыты (C4.8 — hardening и закрытие этапа)
- Дата: 2026-09-13
- Область: platform API/SPI, type exposure taxonomy, read/write pipeline, search defaults,
  effective metadata, telemetry policy, compatibility lifecycle
- Связанные документы: [`JMIX_GitVaa_Roadmap_v2.md`, C4](../../../JMIX_GitVaa_Roadmap_v2.md#c4-узкий-data-access-facade),
  [`c4-data-access-facade-plan.md`](../c4-data-access-facade-plan.md),
  [`c4-inventory.md`](../c4-inventory.md),
  [`appdev-first-audit.md`](../appdev-first-audit.md) (`ADX-04`, `ADX-05`, `ADX-08`,
  платформенная часть `ADX-10`)
- Опирается на: ADR-0002 (lifecycle events), ADR-0003 (default aggregate save),
  ADR-0004 (archetypes/owned sections), ADR-0005 (application behavior boundary),
  ADR-0006 (FetchPlan и InstanceName)

## Контекст

После C3 стандартный read-path защищён RLS и FetchPlan, InstanceName вычисляется единым
провайдером, а write-path имеет раннюю авторизацию. Однако стандартный прикладной путь
всё ещё требует избыточной обвязки, и одна и та же семантика реализована несколько раз:

- `AbstractBaseService`, `LookupService` и `JpaGlobalSearchProvider` содержат независимые
  query boundaries с похожей оркестрацией RLS + FetchPlan + Criteria API;
- в production существуют **две** generic CRUD-базы (`AbstractBaseService`,
  `ValidatedJpaCrudService`), а `ServiceLocator` требует `serviceClass` либо bean-name
  convention;
- 37 persistence types никак не разделены по праву на публичный data handle:
  `ManagedEntityCatalog` (и C3 `ManagedEntityTypes`) содержит весь persistence unit, но
  membership не должен сам по себе выдавать CRUD;
- generic search реализован 11 раз (`searchByTerm`) и частично in-memory
  (`ReceivingDocumentService`, `ReportTemplateService`);
- `BaseService.findAll(Specification, Pageable)` и `BaseService.search(String, Pageable)`
  на standard path бросают `UnsupportedOperationException` — production-time trap;
- `RowDraft.restore` перечитывает каждую entity-ссылку строки отдельным
  `LookupService.findById` (UI-managed N+1).

Инвентаризация и проверяемые числа — в [`c4-inventory.md`](../c4-inventory.md).

## Решение

### 1. Один canonical path, facade — orchestration boundary

Публичной точкой входа для стандартных операций становится **`EntityDataAccess`**
(`org.ipro.data`), выражающий назначение операции, а не persistence-механику:

```java
public interface EntityDataAccess {
    <T> Optional<T> detail(Class<T> type, Object id);
    <T> Page<T> list(Class<T> type, Specification<T> filter, Pageable pageable);
    <T> List<T> lookup(Class<T> type, String term, int limit);
    <T> T create(Class<T> type, T entity);
    <T> T update(Class<T> type, T entity);
    <T> void delete(Class<T> type, Object id);
}
```

Правила:

- facade **не владеет policy**: RLS, FetchPlan, InstanceName, effective metadata и telemetry
  остаются отдельными компонентами, вызываемыми изнутри;
- пользователь API не передаёт `EntityGraph`, Hibernate session, RLS predicate и telemetry
  scope; дополнительные fetch paths динамического вида могут только расширять scenario plan;
- имена intent-методов и их минимальная поверхность могут быть уточнены в C4.1, но разделение
  обязанностей и границы public/SPI/internal фиксируются здесь.

Границы:

| Уровень | Содержимое |
|---|---|
| public API | `EntityDataAccess`, intent-методы, `EntityExposure`/`EntityCapabilities` (read-only descriptor view) |
| public SPI | `EntityDataPolicy` (custom policy по явному типу), `EntitySearchProvider` (optimized/full-text/ranking) |
| internal | descriptor catalog, capability resolver, read/write executor, fetch-plan resolver-обёртка, telemetry decorator |

Custom policy/provider регистрируется **по явному entity type**. Две регистрации для одного
type — startup error. Custom registration не может затенить standard type по порядку
обнаружения bean'ов: выбор детерминирован (по типу), а не «кто позже зарегистрировался».

### 2. Таксономия экспозиции и capabilities

Вводится один классифицированный descriptor поверх существующих
`ManagedEntityCatalog` и `SectionMetadataRegistry`, без нового classpath scan.

```text
PERSISTENCE_TYPE  — любой type persistence unit
STANDARD_ROOT     — самостоятельный metadata-driven root с canonical data handle
OWNED_ROW         — строка declared section, доступная только через aggregate boundary
INTERNAL_STORE    — platform/report/settings/telemetry storage без public entity facade
```

Descriptor хранит JPA/metadata-признаки, `EntityExposure`, набор разрешённых read-сценариев
(`LIST`/`DETAIL`/`LOOKUP`/`ROW`), набор разрешённых write intents (`CREATE`/`UPDATE`/`DELETE`)
и причину custom policy/ограничения.

Правила:

- один type имеет ровно одну `EntityExposure`;
- `ManagedEntityCatalog` membership **не** предоставляет data handle; вычисление FetchPlan
  само по себе не даёт права на data operation;
- `STANDARD_ROOT` допускает `LIST`, `DETAIL`, `LOOKUP` и только явно разрешённые writes;
- `OWNED_ROW` допускает `ROW` внутри aggregate boundary; автономный list/detail/lookup/CRUD
  handle не выдаётся;
- `INTERNAL_STORE` обслуживается владельцем subsystem и не становится business entity;
- невалидная пара `(type, operation/scenario)` отклоняется **до** RLS, provider callback и SQL;
- `SectionMetadataRegistry` остаётся источником факта owned-row ownership.

**Судьба `ManagedEntityTypes` (C3):** остаётся тонким raw sorted metamodel snapshot для
`FetchPlanRegistry`/`InstanceNameResolver`. Он **не** превращается во второй источник типов и
**не** становится capability catalog: descriptor строится над `ManagedEntityCatalog` +
`SectionMetadataRegistry` и является единственным местом, где решается exposure.

**`SklNomOpaValue`** (строка агрегата `SklNomOpa` без `@TableSectionMetadata`) классифицируется
`OWNED_ROW` с явным владельцем `SklNomOpa`; выдача автономного handle для него запрещена
descriptor'ом независимо от наличия `SklNomOpaValueRepository`. Способ объявления владельца
(добавить `@TableSectionMetadata` либо отдельная декларация агрегата) выбирается в C4.1.

### 3. Судьба generic CRUD-баз

| База | Решение | Milestone |
|---|---|---|
| `BaseService<T,ID>` | остаётся compatibility-интерфейсом; deprecated к концу C4 | удаление в D |
| `AbstractBaseService<T,ID>` | **удалён в C4.7**: orchestration перенесена в canonical path, последние два наследника мигрированы волной F C4.6 | C4.7 — выполнено |
| `ValidatedJpaCrudService<T>` | **retain as internal-store adapter** для non-metadata report stores (`JrxmlTemplate`, `ReportTemplate`) | C4.3 |
| `GenericOwnedSectionService` | **retain**: единственный persistence-путь owned row | — |

Правила:

- новый application code не наследует `AbstractBaseService`; architecture test запрещает
  новое наследование;
- typed service использует `EntityDataAccess` через композицию и не повторяет canonical
  CRUD/search;
- `ValidatedJpaCrudService` не может обслуживать `STANDARD_ROOT`: если тип имеет
  `@EntityMetadata`, он обязан идти через canonical path. Architecture test проверяет это
  по факту, а не по документации.

### 4. Единый read pipeline

Каждое стандартное чтение проходит одинаковую последовательность:

1. получить descriptor для JPA-managed type;
2. проверить capability операции и допустимость сценария;
3. применить read gate и активировать RLS;
4. разрешить `scenario plan ∪ additional paths`, валидировать и **углубить union ровно один
   раз**;
5. построить content query и отдельный count query при paging (fetch joins не попадают в
   count);
6. выполнить запрос;
7. завершить telemetry scope согласно operation policy.

Пункт 4 устраняет текущую асимметрию: `AbstractBaseService.buildFetchGraph` углубляет
объединение (`FetchGraphs.deepen`), а `LookupService.entityGraph` строит graph из union без
углубления. Правило «plan ∪ extras → validate → deepen once → graph» вычисляется **в одном
компоненте**; построение scenario-graph вне этого resolver'а запрещено architecture test.

`LookupService` как самостоятельная query boundary удаляется: lookup идёт через тот же
executor. `RowDraft.restore` не выполняет per-reference reads: ссылка восстанавливается из
захваченного состояния без SQL либо одним bounded/batch canonical read.

### 5. Единый write pipeline

```text
write intent
-> ранняя RLS authorization
-> server/structural validation
-> entity lifecycle
-> metadata-declared aggregate processing
-> persistence
-> events / audit / telemetry
```

Facade не обнаруживает произвольные агрегаты: для явно объявленных owned sections он
делегирует `MetadataDrivenAggregateSaveService`, а предметные команды и особая транзакционная
оркестрация остаются typed use case.

**Представление intentional prohibitions.** Запрет — это capability/policy типа, а не
случайный `UnsupportedOperationException` в глубине service. Descriptor несёт
`denied(CREATE|UPDATE|DELETE)` с причиной; generic fallback такой тип не открывает.
Baseline запретов (`AttributeValue.update/delete`, `SklNomOpa.save/create/update/delete`,
`GridFormView` ownership, любой write owned row в обход aggregate boundary) переносится в
descriptor в C4.3 и покрывается отдельными тестами.

### 6. Effective metadata: server и UI semantics

`boolean required() default false` в `@FieldMetadata` заменяется tri-state:

```java
enum RequiredMode { AUTO, REQUIRED, OPTIONAL }
```

Приоритет вывода UI defaults:

```text
explicit UI override -> Bean Validation -> JPA metadata -> Java type -> platform fallback
```

Правила:

- `serverRequired` выводится из Bean Validation + JPA и является контрактом записи;
- `uiRequired` = явный override либо `serverRequired`;
- UI optional при server-required — startup error;
- UI required при server-optional — допустимое явное ограничение (warning), server contract
  молча не меняется;
- несовместимые Java/JPA/reference/field types — startup error;
- resolved descriptor хранит effective value и origin для диагностики;
- `@FieldMetadata` остаётся признаком участия поля в автоматически строящейся форме:
  inference не означает auto-exposure технических/audit-полей.

**Реализация (C4.2).** `RequiredMode` заменил `boolean required`; 52 объявления мигрированы
механически. Effective-факты считает `FieldMetadataInfo` и хранит `FactOrigin` для каждого:
`required` — `EXPLICIT` (явный режим) либо `BEAN_VALIDATION`/`JPA_MAPPING`/`PLATFORM_DEFAULT`,
`type` — `EXPLICIT`/`JAVA_TYPE`/`JPA_MAPPING`, `reference` — `EXPLICIT`/`JPA_MAPPING`.
Противоречиями считаются: объявленный тип против Java-типа/JPA-ассоциации, `@Lookup.entity`
против типа ссылки, `OPTIONAL` против server-required. Примитивы не становятся обязательными:
`nullable = false` на `boolean` не создаёт требования заполнить поле, потому что «пустого»
состояния у примитива нет. Избыточность (явное совпадает с выводом) — `INFO`, а не молчание.

Диагностика адресная (`entity`, `field`, `source`) и классифицированная (`ERROR`/`WARNING`/`INFO`);
проверку выполняет `MetadataConsistencyValidator`, а останавливает старт
`MetadataConsistencyStartupCheck` — по всем managed-типам сразу, а не при первом открытии
формы. Осознанные расхождения объявляются `MetadataAllowance`: исключение называет причину,
понижает диагностику до `INFO`, а при исчезновении условия становится `STALE_ALLOWANCE`
(`ERROR`). Так «разрешено» не превращается в вечное молчание.

**Измеренный результат.** Снимок effective-фактов до/после подтвердил, что перевод пилотов на
вывод **не изменил ни одного значения**: diff пуст, а декларации удалены (см.
`src/test/resources/metadata/effective-metadata-{before,after}.txt` и тест
`EffectiveMetadataSnapshotTest`). Изменилось только происхождение фактов: `required` —
21 значение из Bean Validation, 26 явных, 22 платформенных; `type` — 35 из Java-типа,
24 из JPA, 10 явных; `reference` — 18 из JPA-ассоциации, 8 явных. То есть в текущей модели все
обязательные поля были объявлены и явно, и контрактом записи: вывод не менял поведение, а
убирал дублирование — важный факт, а не «инференс сработал». Оставшиеся вне пилотов
избыточные объявления (24 `required`, 8 `type`, 8 `lookup.entity`) проверка перечисляет как
`INFO`: это готовый список для следующего среза, а не неизвестность.

### 7. Search semantics

Default search выполняется в БД, с paging/limit, RLS и подходящим FetchPlan.

Приоритет search fields:

1. явный override search context (проверяется, а не пропускается);
2. type-level `@SearchFields` — единое предметное исключение для всех search contexts;
3. пути `@InstanceName` — единый источник имени C3;
4. строковые `selectColumns` effective metadata — в них уже выражены отображаемые
   `code`/`name`/`number`, поэтому отдельная карта «по entity kind» не вводится;
5. пустой набор — пустая выдача; startup error для entity, явно участвующей в global
   search без корректных полей, остаётся за `GlobalSearchCatalog` (C4.5).

Контракт:

- case-insensitive contains;
- **literal escaping** пользовательского ввода: `%`, `_`, `\` трактуются буквально (в
  текущем `LookupService` они работают как wildcard — это осознанное изменение semantics);
- **blank term** не является фильтром: результат ограничен page/limit и детерминированным
  sort, а не «вся таблица»;
- детерминированный fallback sort (display sort fields, затем id);
- paging/limit обязателен; `findAll().stream().filter(...)` на standard path запрещён;
- custom provider явно объявляет full-text/joins/ranking и не получает обход canonical
  enforcement/graph resolution.

`BaseService.search(String, Pageable)` перестаёт быть runtime trap: в переходном слое
делегирует default engine; в canonical API standard search либо реализован, либо невалидная
searchable-конфигурация отклонена при старте. До миграции строится per-entity compatibility
matrix (см. [`c4-inventory.md` §7](../c4-inventory.md)) и каждое отличие явно принимается.

### 8. Telemetry policy для LOOKUP

LOOKUP — горячий путь:

- успешный lookup **не** создаёт durable event на каждое нажатие;
- разрешены агрегированные counters/timers и bounded sampling;
- slow/error/deny создают отдельное событие;
- security deny продолжает аудитироваться на security boundary;
- включённая telemetry не добавляет SQL-запись к каждому lookup query;
- в telemetry не попадают search term, entity values, RLS grants и пользовательские
  предикаты.

### 9. Compatibility и removal milestones

| Срез | Что должно быть сделано |
|---|---|
| C4.1 | canonical read executor; `AbstractBaseService` reads делегируют; `LookupService` использует общий resolver; `RowDraft` без N+1 |
| C4.2 | tri-state `required`, effective metadata и snapshot parity на пилотах |
| C4.3 | canonical write path; `ValidatedJpaCrudService` ограничен internal-store; intentional prohibitions в descriptor |
| C4.4 | default server-side search; per-entity parity; первые `searchByTerm` удалены |
| C4.5 | modular global search; `GlobalSearchApplicationConfig` удалён после parity — реализовано |
| C4.6 | application CRUD services/repositories мигрированы; `serviceClass` и magic bean-name удалены |
| C4.7 | compatibility orchestration удалена/сведена к thin delegate; architecture gates |
| D | `BaseService`/`AbstractBaseService` удалены, если consumers мигрированы полностью |

Дополнительно: `ManagedEntityCatalog` membership не выдаёт data handle; `ValidatedJpaCrudService`
не обслуживает metadata-driven root; scenario graph нельзя построить вне canonical resolver;
новые production-классы не наследуют compatibility base service.

### 10. Пилоты

Пилоты и их назначение зафиксированы в [`c4-inventory.md` §9](../c4-inventory.md):
isolated fixture (artifact budget), `Branch`/`Journal` (явный `serviceClass` и композиция),
`UnitOfMeasurement` (bean-name), `Nomenclature` (global search/InstanceName), `PrdSpec`
(owned sections + typed query).

## Последствия

**Положительные:** один standard path вместо трёх; exposure решается до SQL; search
становится server-side и предсказуемым; UI перестаёт управлять fetch paths; intentional
запреты становятся явными capabilities; compatibility path не остаётся равноправным API.

**Отрицательные и принятые:** двойная авторизация write сохраняется до подтверждённой
необходимости кеширования; literal escaping меняет local search (принимается per-entity
matrix); tri-state миграция `required` может изменить формы — snapshot parity делает каждое
изменение явным; `ValidatedJpaCrudService` остаётся, но в узкой проверяемой роли.

## Прогресс реализации

### C4.1 — unified read kernel (2026-09-13)

Реализовано в объёме ADR §1 (границы), §2 (таксономия) и §4 (единый read pipeline):

- публичный read-only descriptor API: `EntityExposure`, `DataOperation`,
  `EntityCapabilities`, `EntityDescriptor`; классификация — `EntityDescriptorCatalog`;
- `ScenarioFetchGraphResolver` + `FetchPlanRegistry.pathsWith` для §4 п.4;
- `CanonicalReadExecutor` как единственная content/count/sort boundary с RLS gate и
  проверкой экспозиции до SQL;
- `readTelemetry` — noop seam (§8), durable-event policy для LOOKUP остаётся за C4.4/C4.5;
- `AbstractBaseService`, `LookupService`, `GenericOwnedSectionService` (ROW) и
  `GlobalSearchService` (gate) используют эту границу;
- §4 п.10: `RowDraft.restore` не выполняет per-reference reads;
- `SklNomOpaValue` классифицируется `OWNED_ROW` явным `EntityExposureOverride`
  (`org.ip.config.EntityClassificationConfig`).

Полный набор и random-order gate зелёные (1164/0/0) — см.
[`../status/current-baseline.md`](../status/current-baseline.md#c41-unified-read-kernel).

### C4.1 hardening по итогам ревью (2026-09-13)

Ревью среза нашло расхождение с §2 и одну поведенческую регрессию; они закрыты без
нового пользовательского функционала.

**§2, таксономия и capabilities.** Capability перестала быть декларацией:
`requireScenario` проверяет `capabilities().allows(scenario)` до RLS, provider callback и
SQL. `INTERNAL_STORE` не читается через canonical path, а тип вне каталога получает новую
экспозицию `UNCLASSIFIED` с пустыми capabilities вместо прежнего permissive
`STANDARD_ROOT`. Предметные запреты выражения явной policy (`EntityCapabilityOverride`,
заменяет выведенные наборы): `AttributeValue` — create only, `SklNomOpa` — без generic
writes, `GridFormView` — writes с ownership в причине. Тип может остаться `INTERNAL_STORE`
и всё же получить canonical чтение — но только явным объявлением владельца с причиной
(сейчас так объявлен `UreportTemplate`: `LIST`/`DETAIL` на время, пока его сервис
наследует `AbstractBaseService`; lookup не выдан). Это правило и есть «владелец
обслуживает свой storage»: descriptor описывает canonical handle, а не внутренний путь
подсистемы.

**§4, единый read pipeline.** `DetailRead` несёт сценарий: карточка — `DETAIL`, значение
выбора — `LOOKUP`. Пока сценарий был константой исполнителя, lookup по id терял
объявленные `@Lookup(fetch)` зависимости (например, единицу измерения выбранной
номенклатуры) — это регрессия против C3, закрыта тестом. Blank lookup ограничивается в
SQL (`setMaxResults`), а не обрезкой полной выгрузки; paging count использует
`countDistinct`, когда Specification пометила запрос distinct (parity с Spring Data,
переносимо между диалектами). Правило `plan ∪ extras → validate → deepen once`
выполняется буквально: `FetchPlan` хранит `rawPaths()`, каждый динамический путь
проверяется через `ColumnPath` до объединения, а само объединение углубляется ровно один
раз. `readLookup` активирует RLS-фильтры безусловно, а `GlobalSearchService` проверяет
capability источника до provider-callback — оба остатка второго ревью закрыты.

Полный набор и random-order gate зелёные (1214/0/0) — см.
[`../status/current-baseline.md`](../status/current-baseline.md#c41-hardening-по-итогам-ревью).

### C4.2 — effective metadata (2026-09-13)

Реализовано в объёме §6: разделение raw-аннотаций и effective-фактов с origin'ами,
tri-state `required`, вывод типа из Java-типа/JPA, цели выбора из типа ссылки, server-required
из Bean Validation и JPA, startup-проверка с классифицированными диагностиками и объявленными
исключениями. Снимок до/после показал нулевой diff по значениям и явное перераспределение
источников; тесты `EffectiveMetadataSnapshotTest`, `EffectiveFieldFactsTest`,
`MetadataConsistencyValidatorTest` фиксируют правила и распределение.

Остаётся открытым и не относится к этому срезу: `FALLBACK_TYPE` (Java-тип вне известных) —
предупреждение, а не отказ; удаление оставшихся вне пилотов дублирующих объявлений (список
выдан проверкой); вывод {@code type = DECIMAL/INTEGER} из Java-типа там, где это тоже дублирование.

### C4.3 — canonical write path (реализация 2026-09-13; hardening 2026-09-14)

Реализовано в объёме §1 (границы), §2 (write capabilities) и §5 (write pipeline):

- **`EntityDataAccess`** — публичный facade с intent-методами `detail`/`list`/`lookup`/
  `create`/`update`/`save`/`delete`; реализация `CanonicalEntityDataAccess` не принимает
  решений о policy, а проецирует их на единые executor'ы;
- **`CanonicalWriteExecutor`** — единый write pipeline: `capability типа → ранний RLS →
  нормализация версии → нумерация → bean-валидация → lifecycle before-hooks и before-events
  → persistence → after-events`. Persistence идёт через `EntityManager`, поэтому canonical
  CRUD не требует ни Spring Data repository, ни application service, ни `serviceClass`,
  ни bean-name convention;
- **`EntityDataPolicy`** (SPI) и **`EntityDataAccessResolver`** — type-directed выбор:
  explicit typed custom policy, иначе canonical generic path, иначе отказ. Две policy для
  одного типа — startup error; policy на не-JPA тип — startup error;
- **`CanonicalEntityService<T>`** — default `BaseService` для `STANDARD_ROOT` без своего
  сервиса (не третья CRUD-база: write-оркестрации в классе нет); `ServiceLocator`
  возвращает его только там, где canonical handle разрешён, иначе сохраняет прежнюю
  диагностику;
- **§2, intentional prohibitions** стали исполняемыми: `AttributeValue` — только `CREATE`,
  `SklNomOpa` — без generic writes, `UreportTemplate` (read-мост владельца) — без writes.
  Отказ приходит до RLS, SQL и пользовательского кода;
- **intent определяет JPA-операцию**, а не наличие `id`: `create` с существующим `id` и
  `update` без `id` отклоняются, заявленный `type` сверяется с точным persistence-классом
  объекта. `update` сначала проверяет наличие и доступность исходной строки, затем
  авторизует и исходное сохранённое состояние, и запрошенное целевое состояние до
  нормализации/валидации/хуков. Это не даёт `merge` превратить отсутствующую строку в
  insert и не позволяет сменой RLS-классифицирующего поля обойти проверку;
- **ownership `GridFormView` не обходится facade**: canonical handle типа ограничен
  `CREATE`, а `update`/`delete` остаются в типизированном `GridFormViewService.checkEditable`,
  потому что canonical pipeline эту проверку не исполняет;
- **§3** закрыт по факту, а не по документации: `ValidatedJpaCrudService` в конструкторе
  отклоняет metadata-driven root (`@EntityMetadata`) с указанием canonical path —
  internal-store adapter больше не может молча обслуживать standard root.

DoD проверен изолированной fixture (test-only entity + поля, без repository/service/
`serviceClass`/bean-name): list/detail/create/update/delete и Bean Validation проходят
через canonical path в отдельном persistence unit, где нет ни одной сущности приложения
(`CanonicalWritePathIT`). Остальные обещания закреплены `CanonicalWriteBoundaryIT`,
`ServiceLocatorCanonicalFallbackTest` и `ValidatedJpaCrudServiceGuardTest`.

Предыдущий полный набор и random-order gate (1202/0/0, до текущего hardening) — см.
[`../status/current-baseline.md`](../status/current-baseline.md#c43-canonical-write-path).

DoD проверен не только изолированной fixture: `CanonicalWritePathIT` отдельно
подтверждает, что оба гаранита порядка исполняются всем pipeline — успешная запись
проходит `RLS → numbering → validation → events` в этом порядке, а RLS-отказ
останавливает операцию **до** validator'а, lifecycle hooks и событий.

Реализованное ядро среза прошло адресную проверку после hardening; формальное закрытие
C4.3 остаётся pending по DoD: write-telemetry, первый production-каталог на canonical
write path и acceptance-цепочка form → write → audit. Эти пункты нужны, чтобы подтвердить
интеграцию не только на изолированной fixture.

Осознанно не входит в срез и остаётся открытым (историческая запись среза C4.3; всё
перечисленное закрыто позже — write-telemetry в C4.8, остальное в C4.4–C4.7): поглощение
`AbstractBaseService`
(§3, milestone C4.7) — типизированные сервисы пока сохраняют собственный write-путь,
а canonical executor обслуживает generic/`STANDARD_ROOT` без своего сервиса; снятие
read-моста `UreportTemplate` переводом сервиса на internal-store adapter; server-side
search (C4.4); **write-telemetry** — seam вокруг canonical write не заведён (план C4.3
п.4 переносит его отдельным шагом); **перевод первого реального справочника** на
canonical path (план C4.3 п.7) — логично делать вместе с C4.7, а не смешивать с
широкой миграцией. Поэтому production canonical fallback пока не несёт нагрузки:
статус среза опирается на fixture и boundary-тесты, а не на реальный справочник.

### C4.4 — server-side search (реализация 2026-09-13; hardening 2026-09-14)

Реализовано в объёме §7:

- **`SearchContext`** (`LIST`/`LOOKUP`/`GLOBAL`) и **`SearchRead`** — один поисковый
  запрос без копирования query builder: контекст задаёт FetchPlan-сценарий, telemetry-
  намерение, строгость проверки явных полей и признак ranking;
- **`CanonicalReadExecutor.readSearch`** — единственный search builder: capability
  сценария → read gate → RLS → fetch-граф → SQL. `readLookup` стал тонкой проекцией
  этого же builder'а (сценарий `LOOKUP`, мягкая проверка полей из UI), а не вторым
  запросом;
- **`SearchFieldResolver`** — единая лестница полей: явные поля → `@InstanceName` →
  строковые `selectColumns` effective metadata; неизвестное явное поле в list/global
  search отклоняется, а не пропускается молча;
- **`SearchTerms`** — literal escaping (`%`, `_`, `\`) и регистронезависимая нормализация;
- **порядок детерминирован**: `exact → prefix → substring → id` (для `LOOKUP` — по id);
- **blank term не является фильтром**: выдача bounded (page/limit) и упорядочена по id,
  а не «вся таблица»; paging считает `totalElements` отдельным count-запросом;
- **`BaseService.search(String, Pageable)`** перестал быть runtime trap — delegates to
  canonical engine; `CanonicalEntityService.search` реализован, а не бросает;
- **все 16 стандартных корней используют один default engine**; repository
  `searchByTerm`/`findWithFilter` удалены. Для `PrdSpec` предметные поля заданы через
  `@SearchFields`; для `SklNomOpa`,
  `UnitOfMeasurement` и `GridFormView` прежняя предметная search-семантика задана явными
  полями, но оба overload (`search(String)` и `search(String, Pageable)`) вызывают тот же
  canonical builder. Остальные корни используют metadata/InstanceName resolver;
  `User` больше не фильтрует `findAll()` в памяти, а `NomSklAttribute` без строковых полей
  возвращает bounded page для blank term и пустую выдачу для непустого.
- **global telemetry intent отделён от LIST**: `SearchContext.GLOBAL` отображается на
  `DataOperation.GLOBAL_SEARCH`, при этом сохраняет FetchPlan `LIST`; подключение
  `GlobalSearchService` к общему search builder выполнено в C4.5.

DoD проверен без repository/service: isolated fixture ищется через canonical engine
(`CanonicalWritePathIT`), literal escaping и paging закреплены поведенчески;
`SearchTermsTest`/`SearchFieldResolverTest` — на уровне семантики; принятые изменения
зафиксированы в `CharacterizationStandardPathIT` и в per-entity матрице
(`c4-inventory.md` §7.2).

Предыдущий полный набор и random-order gate (1227/0/0, до текущего hardening) — см.
[`../status/current-baseline.md`](../status/current-baseline.md#c44-server-side-search).

Последующая адресная проверка overload parity и новых граничных случаев: 48 тестов,
0 failures/errors/skipped (2026-09-14); детали в [`current baseline`](../status/current-baseline.md#c44-server-side-search).
Полный `mvn verify` после этих изменений не запускался.

### C4.5 — modular global search (2026-09-14)

Реализована модульная декларация `@GlobalSearchable(order)` и startup-каталог, который
допускает только managed `STANDARD_ROOT` с `LIST` capability. Порядок групп задан
декларацией, а managed entity без аннотации не появляется в выдаче. Поисковые поля
поступают из общего `SearchFieldResolver`; `@SearchFields` сохраняет единый набор
`PrdSpec(codeSpec, draft)` для list/global search. Центральные `GlobalSearchConfig` и
`GlobalSearchApplicationConfig` удалены.

`GlobalSearchProvider` не владеет EntityManager или запросом: custom SPI отвечает за
подпись/классификацию результата и timeout. `CanonicalReadExecutor.readSearchWindow`
выполняет bounded GLOBAL query без count внутри обычного capability → read gate → RLS →
fetch graph → SQL pipeline. Дополнительные fetch paths каталога гарантируют, что
классификатор и `@InstanceName` читают только инициализированные пути. `PrdSpec` сохраняет
подпись `codeSpec — draft` без обращения к lazy `nomenclature`.

Поведенческие проверки включают fixed source order, поля/подписи, отказ невалидной
регистрации, пропуск timeout источника, Spring wiring и один SQL-запрос без count с
ранжированием `exact → prefix → substring → id`; результат запуска приведён в
[`current-baseline.md`](../status/current-baseline.md#c45-modular-global-search).

### C4.6 волны E–F и C4.7 — compatibility-механизмы удалены (2026-09-14)

Волна E перевела доменные сервисы (`AttributeType`, `PrdSpec`, `User`, `AttributeValue`,
`NomSklAttribute`, `SklNomOpa`, `GroupNom`, `ReceivingDocument`) на делегирование canonical
handle, а их правила — в `EntityLifecycle`; интернирование значений вынесено в один
`NaturalKeyCreateSupport` (ADR-0008, `ADX-13`).

Волна F закрыла два последних наследника compatibility base:

- **`GridFormView`** — ownership-правило переехало в `GridFormViewLifecycle`, поэтому
  canonical handle типа больше не сужается до `CREATE`. Раньше запрет жил только в сервисе,
  и facade приходилось закрывать, чтобы его не обойти; теперь правило исполняет тот же
  write pipeline, что и остальные lifecycle-запреты, а `update`/`delete` остаются доступны
  законному автору вида. `beforeUpdate` решает по сохранённому состоянию, а не по payload —
  иначе чужой личный вид можно было бы изменить, передав его с `shared = true`;
- **`UreportTemplate`** — владелец обслуживает свой storage (non-metadata `INTERNAL_STORE`)
  через `ValidatedJpaCrudService`; read-мост владельца (`EntityCapabilityOverride` с
  `LIST`/`DETAIL`) снят вместе с причиной его существования.

C4.7 удалил саму базу и остатки compatibility-резолва:

- `AbstractBaseService` удалён: наследников не осталось, а второй generic CRUD-базе рядом
  с canonical path нечего обслуживать. Забор `CompatibilityMigrationArchitectureTest`
  проверяет это по факту (`Class.forName` + ArchUnit) и ведёт reviewed-список реализаций
  `BaseService` в обе стороны;
- `@EntityMetadata.serviceClass` и ветка его чтения удалены: атрибут не задавался ни одной
  production-моделью;
- резолв `BaseService` стал type-directed: `ServiceLocator` индексирует бины по первому
  аргументу `BaseService` после создания синглтонов (`SmartInitializingSingleton`) и
  отказывает на дубликате — выбор не зависит от имён и порядка регистрации.

Полный набор `mvn verify` — 1272 теста, 0 failures/errors; random-order gate — 1271 тест,
0 failures/errors (seed `3330014842700`); детали и artifact budget — в
[`../status/current-baseline.md`](../status/current-baseline.md).

### C4.8 — hardening и закрытие этапа (закрыт)

- **§5 write-telemetry заведён, исход двухфазный.** `WriteTelemetry` — typed collaborator с
  noop по умолчанию (аналог `ReadTelemetry`); scope открывается до capability-проверки,
  поэтому ранний deny фиксируется как `denied`, а ошибка исполнения — как `failed`.
  Успех сообщается поэтапно: `pipelineCompleted` после flush внутри операции и затем
  `committed` либо `rolledBack` по исходу транзакции (`TransactionSynchronization`), так как
  Spring коммитит уже после возврата метода и откат на коммите выглядел бы как успех.
  Вложенный canonical write в одной бизнес-операции переиспользует внешний scope, а не
  открывает второй. Никакой payload, значений, паролей или поисковых строк в seam не попадает.
- **§5 отказ — это policy, а не любой `IllegalStateException`.** Введён
  `CanonicalWriteDeniedException` с `DenialKind` (`CAPABILITY`, `AGGREGATE_BOUNDARY`,
  `ACCESS`); security-отказ (`RlsAccessDeniedException`) относится к отказу доступа, а
  валидация, нумерация и lifecycle — к ошибкам исполнения.
- **§8 read-telemetry унифицирован.** Единый `measured(...)` фиксирует и успех, и отказ на
  всех публичных overloads; `LOOKUP` остаётся hot path без durable event.
- **§2/§7 auth-граница search — fail-closed.** Глобальный поиск — защищённая операция:
  снятая в C4.5 проверка `requireAuthenticatedUsername()` восстановлена (явный typed
  RLS-bypass проходит как системная операция). Без `RlsCurrentUser` бин не создаётся, поэтому
  «забытая» policy не превращает поиск в анонимный; незащищённый режим доступен только
  явной фабрике для срезов/тестов.
- **§7 to-many: запрет вместо эмуляции.** Поля поиска через `PluralAttribute` отклоняются
  резолвером (strict) или пропускаются (lookup), а сортировка по to-many-пути отклоняется до
  SQL: порядок корня по элементу коллекции не определён, а `SELECT DISTINCT` с `ORDER BY`
  по join'нутой коллекции невалиден в PostgreSQL. `distinct` остаётся для spec-driven filter
  (parity content/count) и как страховка в поиске для полей, пришедших минуя резолвер.
- **§1/§4 UI persistence boundary.** `FormResolver` больше не владеет `EntityManager`;
  grouping вынесен в data-адаптер вне UI-пакетов, а arch-тест зафиксировал пустой список
  исключений.

Проверка (JDK 21, offline): `mvn verify` — 1316 тестов, 0 failures/errors/skipped;
random-order gate — 1316 тестов, 0 failures/errors (seed `20260915`). Детали и разбор ревью
среза — в
[`../status/current-baseline.md`](../status/current-baseline.md#c48-hardening-и-закрытие-этапа-закрыт).

## Открытые вопросы

- **Решено (C4.6 волна F):** `GridFormView` остаётся `STANDARD_ROOT`. Тип — полноценная
  metadata-driven сущность с собственным предметным доступом к видам реестра, а
  row-level правило выражается lifecycle handler'ом, а не сужением capabilities.
- **Решено (C4.6 волна F):** `UreportTemplate` переведён на `ValidatedJpaCrudService` как
  internal-store adapter — ту же базу, что соседи по подсистеме (`ReportTemplate`,
  `JrxmlTemplate`). Отдельный adapter не понадобился: форма одна, и `ADR-0007 §3` уже
  считал эту базу целевой для non-metadata storage.
- **Решено (C4.7):** typed application service не обязан проходить capability-проверку.
  Descriptor описывает canonical/generic handle, а типизированный use case — владелец типа,
  который не повторяет canonical CRUD, а держит домен (канонизация значений, нормализация
  пароля). Попытка распространить capability и на него заставила бы выдавать типу
  write-права, которые затем никто не обязан проверять на уровне строк; row-level запреты
  для этого и выражаются lifecycle handler'ом.
- Точная форма публичной intent-проекции (§1) — остаётся открытым: read-намерения уже
  проходят через executor, публичный `EntityDataAccess` и write-намерения — C4.3;
  architecture-тест, запрещающий строить scenario-граф вне resolver'а — C4.7.
- Organizational gate, не технический: платформа для одного приложения или reusable
  framework для многих. От ответа зависит, идёт ли команда сразу в C5/D или сначала
  проверяет, окупился ли C4 на практике; решение владельца, в коде не выражается.
