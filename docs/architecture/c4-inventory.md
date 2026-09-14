# C4.0: инвентаризация и таксономия data-access

Статус: `IN PROGRESS` (первый срез C4)
Родительский план: [`c4-data-access-facade-plan.md`, §5 C4.0](c4-data-access-facade-plan.md)
Решение: [`decisions/ADR-0007-canonical-data-access-path.md`](decisions/ADR-0007-canonical-data-access-path.md)

Документ фиксирует проверяемые факты checkout до изменения кода. Все числа получены
командами, приведёнными в §6, и должны перепроверяться тем же способом: это baseline, а не
пересказ.

> **Состояние после C4.** Ниже описан срез до C4.0 и цели среза, поэтому упоминания
> `AbstractBaseService`, `serviceClass` и обязательной пары repository/service
> относятся к исходному состоянию. К C4.7 `AbstractBaseService` и `serviceClass`
> удалены, `BaseService` резолвится по entity type, а canonical handle получили все 16
> стандартных корней. Итоговые числа, artifact budget и разбор каждого оставшегося
> класса — в [`status/current-baseline.md`](status/current-baseline.md) и
> [`decisions/ADR-0007-canonical-data-access-path.md`](decisions/ADR-0007-canonical-data-access-path.md).

## 1. Область подсчёта

- `src/main/java`, production-код. Тестовые сущности и тестовые сервисы не входят, кроме
  явно оговорённых мест.
- «Persistence type» = класс с настоящей аннотацией `@Entity` (39 совпадений `^@Entity`
  включают `@EntityListeners`/`@EntityScan`, реальных 37).
- «Metadata-driven» = `@EntityMetadata` объявлена на классе.
- «Owned row» = объявлена `@TableSectionMetadata` (4 класса) либо фактически существует
  только внутри агрегата (1 дополнительный).

## 2. Таксономия persistence types (37)

Классы экспозиции и их правила — в ADR-0007 §2. `ROOT` = `STANDARD_ROOT`,
`ROW` = `OWNED_ROW`, `INT` = `INTERNAL_STORE`.

### 2.1. `org.ip.model` (22 типа)

| # | Type | Kind | @EntityMetadata | @TableSectionMetadata | Classification | Writes | Причина / примечание |
|---|---|---|---|---|---|---|---|
| 1 | `AttributeType` | directory | да | — | ROOT | CRUD | явный `serviceClass` |
| 2 | `AttributeValue` | directory | да | — | ROOT | create only | `update`/`delete` запрещены typed policy (`AttributeValueService:286,298`) |
| 3 | `Branch` | catalog | да | — | ROOT | CRUD | пилот «явный serviceClass» |
| 4 | `GridFormView` | plain | да | — | ROOT | CRUD + ownership | UI-хранилище видов; `serviceClass` не задан → bean-name convention; update/delete ограничены `checkEditable` |
| 5 | `GroupNom` | catalog | да | — | ROOT | CRUD | |
| 6 | `Journal` | catalog | да | — | ROOT | CRUD | пилот «явный serviceClass» |
| 7 | `NomAttributeValue` | plain | да | да | ROW | — | пересечение metadata ∩ owned row |
| 8 | `NomSklAttribute` | plain | да | — | ROOT | CRUD + typed setters | `serviceClass`; имеет собственную list-форму |
| 9 | `Nomenclature` | catalog | да | — | ROOT | CRUD | |
| 10 | `Oper` | catalog | да | — | ROOT | CRUD | |
| 11 | `PrdSpec` | document | да | — | ROOT | CRUD | |
| 12 | `PrdSpecMtr` | plain | да | да | ROW | — | пересечение metadata ∩ owned row |
| 13 | `PrdSpecOper` | plain | нет | да | ROW | — | |
| 14 | `ReceivingDocument` | document | да | — | ROOT | CRUD | canonical search (C4.4); in-memory поиск удалён |
| 15 | `ReceivingDocumentItem` | plain | нет | да | ROW | — | |
| 16 | `Role` | plain | да | — | ROOT | CRUD | bean-name convention (`roleService`) |
| 17 | `SklNomOpa` | plain | да | — | ROOT | через typed use case | `save`/`create`/`update`/`delete` запрещены (`SklNomOpaService:227,234,241,249`) |
| 18 | `SklNomOpaValue` | plain | нет | **нет** | ROW | — | **таксономический пробел**: строка агрегата `SklNomOpa`, но без `@TableSectionMetadata`; есть собственный `SklNomOpaValueRepository` |
| 19 | `UnitOfMeasurement` | catalog | да | — | ROOT | CRUD | bean-name convention; пилот «bean-name» |
| 20 | `User` | plain | да | — | ROOT | CRUD | bean-name convention |
| 21 | `UserFormSettings` | plain | нет | — | INT | owner subsystem | служебная таблица настроек форм, явно без metadata |
| 22 | `Workshop` | catalog | да | — | ROOT | CRUD | bean-name convention |

### 2.2. Платформенные пакеты (15 типов) — все `INTERNAL_STORE`

| # | Type | Пакет | Владелец |
|---|---|---|---|
| 23 | `JrxmlTemplate` | `org.ipro.jr.dom` | report runtime (JR) |
| 24 | `NumberingCounter` | `org.ipro.numbering` | numbering |
| 25 | `NumberingRule` | `org.ipro.numbering` | numbering |
| 26 | `ReportBand` | `org.ipro.reportstudio.dom` | report studio |
| 27 | `ReportField` | `org.ipro.reportstudio.dom` | report studio |
| 28 | `ReportOrder` | `org.ipro.reportstudio.dom` | report studio |
| 29 | `ReportParam` | `org.ipro.reportstudio.dom` | report studio |
| 30 | `ReportTemplate` | `org.ipro.reportstudio.dom` | report studio |
| 31 | `AccessGrant` | `org.ipro.rls` | RLS admin |
| 32 | `SettingValue` | `org.ipro.settings` | settings |
| 33 | `EntityChangeLogEntity` | `org.ipro.telemetry.model` | telemetry |
| 34 | `OperationLogEntity` | `org.ipro.telemetry.model` | telemetry |
| 35 | `PerfStatsEntity` | `org.ipro.telemetry.model` | telemetry |
| 36 | `TraceSettingsEntity` | `org.ipro.telemetry.model` | telemetry |
| 37 | `UreportTemplate` | `org.ipro.ureport.dom` | uReport |

Итог по классам: **16 `STANDARD_ROOT`, 5 `OWNED_ROW`, 16 `INTERNAL_STORE`** = 37.

### 2.3. Находки таксономии

1. **`SklNomOpaValue` не объявлен owned row, но ведёт себя как строка агрегата.**
   `@TableSectionMetadata` отсутствует, поэтому `ServiceLocator` и `SectionMetadataRegistry`
   его строкой секции не считают, а   `SklNomOpaValueRepository` существует. Если у типа
   появится `@EntityMetadata` (или generic fallback начнёт ориентироваться на JPA-metamodel),
   он получит автономный handle без обязательного предиката владельца. Это ровно тот дефект,
   от которого защищается §4.2 плана. **Закрыто в C4.1:** тип классифицируется `OWNED_ROW`
   явным `EntityExposureOverride` в `org.ip.config.EntityClassificationConfig`; автономного
   list/detail/lookup у строки нет (ADR-0007 §2, `CanonicalReadBoundaryIT`).
2. **`UserFormSettings` — `INTERNAL_STORE`, обслуживается `FormSettingsService`**, у него нет
   metadata и нет `BaseService`-контура; он не должен попадать в generic path.
3. **`GridFormView` — единственный metadata-driven `STANDARD_ROOT` без `serviceClass`**,
   обслуживается по bean-name convention и имеет собственное ownership-правило. Кандидат на
   `INTERNAL_STORE` с точки зрения «canonical data handle», но он реально показывается
   пользователю как список, поэтому в C4.0 классифицирован `STANDARD_ROOT` с **custom
   policy**; C4.6 принимает окончательное решение.
4. **Два пересечения `@EntityMetadata ∩ @TableSectionMetadata`** (`NomAttributeValue`,
   `PrdSpecMtr`) уже сейчас корректны: `ServiceLocator` отказывает им до поиска бина
   (`ServiceLocatorSectionRowTest`), а metadata-заголовки используются только для
   explorer. C4.1 должен сохранить это поведение и покрыть его taxonomy-тестом.

## 3. Классификация service / repository / generic base слоя

### 3.1. Generic CRUD-базы

| База | Где | Потребители | Решение ADR-0007 |
|---|---|---|---|
| `AbstractBaseService<T,ID>` | `org.ipro.crud` | 17 subclasses (16 в `org.ip.service` + `UreportTemplateService`) | **absorb** orchestration в canonical path; класс становится тонким deprecated delegate |
| `ValidatedJpaCrudService<T>` | `org.ipro.crud.jpa` | `JrxmlTemplateService`, `ReportTemplateService` | **retain as internal-store adapter**; не может обслуживать `STANDARD_ROOT` |
| `GenericOwnedSectionService` | `org.ipro.crud` | `MetadataDrivenAggregateSaveService`, `ItemTable` read | **retain** как единственный persistence-путь owned row |

### 3.2. `org.ip.service` (20 классов) — классификация и судьба

| Класс | Тип | Класс работы | Решение |
|---|---|---|---|
| `AttributeTypeService` | ROOT | typed domain validation (словарь только для REF; запрет смены `valueType` при наличии значений) | retain как domain port + canonical delegation (решение C4.6) |
| `AttributeValueService` | ROOT | typed domain (find-or-create, rename, race) | retain как domain port + canonical facade через композицию |
| `BranchService` | ROOT | pure CRUD/search boilerplate | migrate → удалить (пилот C4.6) |
| `GridFormViewService` | ROOT | typed UI store (visibility/ownership) | retain как custom policy provider: `STANDARD_ROOT` + custom policy, canonical write — только `CREATE` (решение C4.6) |
| `GroupNomService` | ROOT | pure CRUD/search boilerplate | migrate → удалить |
| `JournalService` | ROOT | pure CRUD/search boilerplate | migrate → удалить (пилот C4.6) |
| `NomSklAttributeService` | ROOT | typed (bind/unbind/setBindings) | retain как domain port |
| `NomenclatureService` | ROOT | pure CRUD/search boilerplate | migrate → удалить |
| `OperService` | ROOT | pure CRUD/search boilerplate | migrate → удалить |
| `PrdSpecService` | ROOT | typed (`findByJournal`) | retain как domain port + композиция facade |
| `ReceivingDocumentService` | ROOT | typed override (fetch graph) | migrate → удалить; search уже canonical с C4.4 |
| `RoleService` | ROOT | pure CRUD/search boilerplate | migrate → удалить |
| `SklNomOpaService` | ROOT | typed immutable aggregate (findOrCreate/items) | retain как domain port с explicit capability policy |
| `UnitOfMeasurementService` | ROOT | pure CRUD/search boilerplate | migrate → удалить (пилот bean-name) |
| `UserService` | ROOT | typed (password) | retain domain part; CRUD → canonical |
| `WorkshopService` | ROOT | pure CRUD/search boilerplate | migrate → удалить |
| `AccessGrantAdminService` | INT | admin use case (не `BaseService`) | retain |
| `FormSettingsService` / `FormSettingsStoreAdapter` | INT | settings store | retain |
| `GridViewStoreAdapter` | ROOT | UI adapter | retain до C4.6 |
| `UserFormSettingsRepository` | INT | storage | retain |

### 3.3. Repository-слой

- 26 интерфейсов `JpaRepository` в `src/main`: 18 в `org.ip.repository` (application) и 8
  платформенных (`JrxmlTemplateRepository`, `NumberingCounterRepository`,
  `NumberingRuleRepository`, `ReportTemplateRepository`, `AccessGrantRepository`,
  `SettingValueRepository`, `OperationLogRepository`, `UreportTemplateRepository`).
- **Baseline C4.0:** 11 application repositories содержали `searchByTerm` (AttributeType, AttributeValue,
  Branch, GroupNom, Journal, Nomenclature, Oper, PrdSpec, SklNomOpa, UnitOfMeasurement,
  Workshop) — это 11 из 17 application entity. В C4.4 методы удалены: стандартные типы
  используют canonical search с выводимыми или явно закреплёнными полями.
- `SklNomOpaValueRepository` и `UserFormSettingsRepository` не имеют `searchByTerm`; первый
  обслуживает owned row, второй — `INTERNAL_STORE`.
- Application repositories без `searchByTerm`: `GridFormViewRepository`,
  `NomSklAttributeRepository`, `ReceivingDocumentRepository`, `RoleRepository`,
  `UserRepository`.

### 3.4. Superclass assignment (по факту)

| База | Типы |
|---|---|
| `AbstractBaseService` | AttributeType, AttributeValue, Branch, GridFormView, GroupNom, Journal, NomSklAttribute, Nomenclature, Oper, PrdSpec, ReceivingDocument, Role, SklNomOpa, UnitOfMeasurement, User, Workshop, UreportTemplate |
| `ValidatedJpaCrudService` | JrxmlTemplate, ReportTemplate |
| без generic base (owner-specific repository/service) | AccessGrant, SettingValue, NumberingCounter, NumberingRule, ReportBand/Field/Order/Param, только через `ReportTemplate`, telemetry 4, owned rows (через aggregate boundary), SklNomOpaValue, UserFormSettings |

## 4. Consumer map

| Точка входа | Потребители (production) |
|---|---|
| `ServiceLocator` | `FormResolver`, `FormCoordinator`, `ItemFormWrapperView`, `MetadataDrivenAggregateSaveService`, `ItemFormSaveDispatcher`, `SelectionFormAssembler`, `MetadataAutoConfiguration` |
| `LookupService` | 15 вызовов в `src/main`: `FieldFactory`, `ListForm` (3), `RowDraft`, `SelectionContextFilters`/`ContextFilterPanel`, `GridViewEditorDialog` (2), `AttributeValueService` (2), `ReportParamForm`, `NomAttributeValueItemForm`, `PrdSpecByJournalView`, `ReportQueryEditor` |
| `AbstractBaseService.findAll(Specification, Pageable)` + `findAllWithFetchGraph` | 42 совпадения в `src/main` (объявления + вызовы в 16 subclasses и form/grid) |
| `BaseService.search(String[,Pageable])` | 20 реализаций/переопределений в `src/main`; `AbstractBaseService:295` и `ValidatedJpaCrudService:221,227` бросают `UnsupportedOperationException` |
| `BaseService.findAll(Specification, Pageable)` default | `BaseService:26` бросает `UnsupportedOperationException`; `ValidatedJpaCrudService` не переопределяет |
| `@GlobalSearchable` + `GlobalSearchCatalog` | явное entity-level участие и стабильный порядок; центральная source-конфигурация удалена в C4.5 |
| `RowDraft.restore` → `LookupService.findById` | `ItemTable`/`ItemForm` cancel path: по одному read на каждую entity-ссылку строки |

## 5. Intentional write prohibitions (baseline)

Фиксируются как capability/policy, а не как runtime trap:

| Type | Запрещено | Где | Причина |
|---|---|---|---|
| `AttributeValue` | `update`, `delete` | `AttributeValueService:286,298` | значение бессмертно; переименование — отдельная операция |
| `SklNomOpa` | `save`, `create`, `update`, `delete` | `SklNomOpaService:227,234,241,249` | набор immutable, создаётся канонизацией `findOrCreate` |
| `GridFormView` (shared/чужой вид) | `update`, `delete` | `GridFormViewService:52,58` | ownership |
| `GenericOwnedSectionService` | любые write в обход aggregate boundary | `:405,409` | owned row не имеет автономного handle |

### 5.1. Остаточная избыточность объявлений (C4.2, `INFO`-список)

После перевода пилотов на вывод сквозная проверка метаданных (`MetadataConsistencyValidator`)
перечисляет избыточные объявления остальных сущностей: **24** `required`, **8** `type`,
**8** `lookup.entity`. Это готовый список для механической зачистки, а не поиск по коду
(полный вывод — в логе старта, код `REDUNDANT_REQUIRED`/`REDUNDANT_TYPE`/
`REDUNDANT_LOOKUP_TARGET`). Единственное разрешённое расхождение —
`ReceivingDocument.journal` (UI требует, сервер допускает NULL; объявлено в
`org.ip.config.MetadataDiagnosticsConfig`).

Со среза C4.1 hardening запреты выше объявлены явной policy в
`org.ip.config.EntityClassificationConfig` (`EntityCapabilityOverride`) и проверяются
`CanonicalReadExecutor` для чтения. С C4.3 `writes` читает `CanonicalWriteExecutor`:
generic write к `AttributeValue`/`SklNomOpa`/`UreportTemplate` отклоняется до RLS, SQL и
пользовательского кода (`CanonicalWriteBoundaryIT`).

`GridFormView` разведён точнее: ownership-проверка (`checkEditable`) живёт только в
типизированном сервисе и canonical pipeline её не исполняет, поэтому canonical handle
типа ограничен `CREATE` — прямой `EntityDataAccess.update/delete(GridFormView.class, …)`
отклоняется, а не обходит правило (`gridFormViewOwnershipRuleIsNotBypassedByCanonicalFacade`).

Там же объявлен обратный случай: `UreportTemplate` остаётся `INTERNAL_STORE`, но получает
явный read-мост `LIST`/`DETAIL` от владельца подсистемы отчётов, потому что его сервис пока
наследует `AbstractBaseService` (writes при этом не выдаются) — исключение названо причиной,
а не выведено из metamodel.

## 6. Baseline (воспроизводимые команды)

```bash
grep -rEn "^@Entity([[:space:](]|$)" src/main/java --include=*.java | sed 's/:.*//' | sort -u | wc -l
# -> 37

grep -rn "^@EntityMetadata" src/main/java --include=*.java | sed 's/:.*//' | sort -u | wc -l
# -> 18

grep -rn "^@TableSectionMetadata" src/main/java --include=*.java | sed 's/:.*//' | sort -u
# -> 4: NomAttributeValue, PrdSpecMtr, PrdSpecOper, ReceivingDocumentItem

grep -rln "extends AbstractBaseService" src/main/java | wc -l          # -> 17
grep -rln "ValidatedJpaCrudService" src/main/java | wc -l             # -> 3 (база + 2 consumer)
grep -rln "extends JpaRepository" src/main/java | wc -l               # -> 26
grep -rln "searchByTerm" src/main/java/org/ip/repository | wc -l      # -> 11
grep -rn "serviceClass" src/main/java/org/ip/model/*.java | wc -l     # -> 11 declarations
grep -rnE "lookupService\.(search|findById|findAll)" src/main/java | wc -l  # -> 15
```

| Метрика | Значение |
|---|---|
| Persistence types | 37 |
| Metadata-driven types | 18 |
| Declared owned rows | 4 |
| Structural owned rows (incl. undeclared) | 5 |
| Standard roots | 16 |
| Internal stores | 16 |
| `AbstractBaseService` subclasses | 17 |
| `ValidatedJpaCrudService` subclasses | 2 |
| Application repositories | 18 |
| `searchByTerm` repositories | 11 |
| `serviceClass` declarations | 11 |
| Generic runtime traps (`UnsupportedOperationException` на standard path) | `BaseService:26`, `AbstractBaseService:296`, `ValidatedJpaCrudService:221,227` |

## 7. Search compatibility matrix (метод C4.0)

Матрица строится **до** C4.4 как данные, а не как рассуждение. Для каждого searchable
`STANDARD_ROOT` фиксируются на одном dataset:

- список search fields (repository `searchByTerm` против metadata defaults);
- blank term (возвращает всё / пусто);
- case-insensitive поведение;
- литералы `%`, `_`, `\` (сейчас `LookupService` трактует их как SQL wildcard — это
  осознанное изменение semantics в C4.4);
- порядок результата (сейчас без явного `ORDER BY` — не детерминирован);
- limit/paging.

Текущее состояние фиксируется characterization-тестом
`org.ipro.data.CharacterizationStandardPathIT` (пилот `Branch`); расхождения old/new
принимаются построчно в C4.4.

### 7.1. «Old» сторона матрицы: текущие repository search fields

| Type | `searchByTerm` поля | Возможное расхождение с canonical default |
|---|---|---|
| `AttributeType` | `code`, `name` | — |
| `AttributeValue` | `code`, `name` | — |
| `Branch` | `code`, `name` | — |
| `GroupNom` | `code`, `name` | — |
| `Journal` | `code`, `name` | — |
| `Nomenclature` | `code`, `name` | — |
| `Oper` | `code`, `name` | — |
| `PrdSpec` | `codeSpec`, `draft` | semantic defaults дадут InstanceName-поля `codeSpec`; `draft` — repository-specific |
| `SklNomOpa` | `displayName` | расширение до `LIST`/InstanceName-полей меняет выдачу |
| `UnitOfMeasurement` | `shortCode` | canonical `code`/`name` расширит выдачу |
| `Workshop` | `code`, `name` | — |

Ни один `searchByTerm` не задаёт `ORDER BY`: текущий порядок результата не детерминирован.
Все запросы используют `LIKE LOWER(CONCAT('%', :term, '%'))`, то есть пользовательские
`%`/`_` уже сейчас работают как wildcard (та же семантика, что у `LookupService`).

### 7.2. «New» сторона: canonical search engine (C4.4)

Сервисы больше не реализуют собственные search Criteria/repository queries: поля выводит
`SearchFieldResolver` по одной лестнице — явные поля → `@SearchFields` → пути `@InstanceName` (единый источник C3) → строковые
`selectColumns` effective metadata. Для всех мигрированных типов это `code`/`name`
(у `Branch`, `Journal`, `Oper`, `Workshop`, `GroupNom`, `AttributeType`, `AttributeValue`,
`Nomenclature`) — то есть тот же набор, что у прежнего `searchByTerm`, но без repository query.
Чтобы сохранить предметную поверхность без второго query builder, `@SearchFields` задаёт
общие для list/global search поля `PrdSpec` (`codeSpec`, `draft`), а explicit field sets —
`SklNomOpa` (`displayName`), `UnitOfMeasurement`
(`shortCode`) и `GridFormView` (`name`, `formKey`). `User` выводит `username` из metadata.
У `NomSklAttribute` нет строковых search paths: непустой терм даёт пустую выдачу, blank term
возвращает только bounded page.

Принятые изменения semantics (зафиксированы поведенчески, см. `CharacterizationStandardPathIT`):

| Аспект | Было | Стало (C4.4) |
|---|---|---|
| `%`, `_`, `\` в терме | SQL-wildcard | литеральные символы (literal escaping) — для lookup и search |
| blank term в list search | `findAll()` без ограничения | не фильтр: первые page/limit записей, порядок по id |
| blank term в lookup | первые N | первые N (сохранено) |
| порядок | без `ORDER BY` | `exact → prefix → substring`, затем `id` |
| limit/paging | repository, 100 без count | `search(String)` — первые 100; `search(String, Pageable)` — серверный paging с count |
| неизвестное явное поле | молча пропускается | отклоняется (`IllegalArgumentException`); lookup остаётся tolerant |

На default engine переведены стандартные корни: `AttributeType`, `AttributeValue`, `Branch`,
`GridFormView`, `GroupNom`, `Journal`, `NomSklAttribute`, `Nomenclature`, `Oper`, `PrdSpec`,
`ReceivingDocument`, `Role`, `SklNomOpa`, `UnitOfMeasurement`, `User`, `Workshop`. Все
repository `searchByTerm` и `findWithFilter` на стандартном пути удалены. Четыре прежних
typed query сохраняют поля поиска и используют тот же Criteria builder; их application
service/domain migration остаётся в C4.6. В C4.5 global providers переведены на
`SearchContext.GLOBAL` внутри canonical read boundary; подробности — в `current-baseline.md`.

## 8. Effective metadata snapshot (метод C4.2)

До изменения declarations сохраняется snapshot effective `required`/`type`/`reference` по
всем UI-полям с указанием origin каждого факта. Snapshot строится из
`MetadataResolver`/`FieldMetadataInfo` и сравнивается побайтово после C4.2; любое
необъяснённое изменение `required/type/reference` ломает тест. Пилот C4.2 — одна
directory-сущность с полным набором `@FieldMetadata` и одна document-сущность с
`ENTITY_REFERENCE`.

## 9. Пилоты (выбор C4.0)

| Пилот | Назначение | Почему |
|---|---|---|
| isolated fixture (test-only entity) | доказательство artifact budget: entity + поля без repository/service/config | проверяет главный DoD C4.3 в чистом виде; реализован в C4.3 как `org.ipro.data.fixture.C4FixtureEntity` в отдельном persistence unit (`CanonicalWritePathIT`) |
| `Branch` | явный `serviceClass`, zero domain methods | самый дешёвый реальный CRUD-pilot (C4.3/C4.6) |
| `Journal` | явный `serviceClass`, используется typed `PrdSpecService.findByJournal` | проверяет композицию facade и typed use case |
| `UnitOfMeasurement` | bean-name convention, без `serviceClass` | проверяет удаление magic lookup |
| `Nomenclature` | global search + InstanceName + собственный `serviceClass` | C4.5: модульное участие; вместе с `PrdSpec` и `ReceivingDocument` проходит canonical global read |
| `PrdSpec` | document с owned sections (`PrdSpecMtr`/`PrdSpecOper`) | aggregate boundary + typed query |

## 10. Что C4.0 сознательно не решает

- не меняет код закрытия тайн: сам фасад, executor и descriptor появляются в C4.1–C4.3;
- не мигрирует сущности на `@InstanceName` (C3 закрыл механизм, C4 не расширяет его);
- не удаляет `serviceClass`, magic bean-name и repositories — это C4.6–C4.7;
- не вводит `INTERNAL_STORE` exposure gate в код — он появится на первом write/read
  срезе C4.1–C4.3 и будет проверен architecture-тестом.

## 11. C4.6/C4.7: решения и волны миграции

Решения, зафиксированные до первого изменения кода:

1. C4.5 фиксируется отдельным чекпоинт-коммитом до старта C4.6.
2. `GridFormView` остаётся `STANDARD_ROOT` с custom policy: ownership живёт в
   `GridFormViewService`, canonical write-handle типа — только `CREATE`.
3. `serviceClass` убирается по волнам вместе с сущностью; сам атрибут
   `@EntityMetadata.serviceClass()` и ветка в `ServiceLocator` удаляются одним отдельным
   шагом в C4.7, с полным `verify` до и после.
4. `AttributeType` остаётся domain port'ом: правило словаря и `valueType` живёт в
   `AttributeTypeService`, стандартные read/write делегируются canonical.
5. `AbstractBaseService` удаляется полностью: остаток перебазируется на canonical
   composition seam, `org.ipro.crud.AbstractBaseService` исчезает.
6. `CanonicalReadExecutor.readSum` выводится на canonical-поверхность: без этого UI-грид
   `WorkshopListView` не отвязать от compatibility base.
7. Интернированные сущности (ADR-0008, ADX-13) получают один механизм
   `NaturalKeyCreateSupport` и поведенческий маркер `InternedEntity`; узкий service-шов для
   них строится на canonical-шве и вводится вместе с удалением `AbstractBaseService`,
   чтобы не завести нового наследника compatibility-базы накануне её удаления. Волна E
   поэтому переводит `AttributeValueService`/`SklNomOpaService` только на делегирование
   CRUD: `save`/`create`/`update`/`delete` остаются в базе до C4.7.

Волны — по нарастанию риска:

| Волна | Сущности | Суть |
|---|---|---|
| A | `Branch`, `Journal`, `Oper`, `Role` | класс и `serviceClass` удаляются целиком; `RoleRepository` остаётся (пишет `DataInitializer`, читает `AccessGrantAdminService`) |
| B | `GroupNom`, `Nomenclature`, `ReceivingDocument` | снимается дублирующий `findAll(spec, pageable)`, затем класс; `Nomenclature` и `ReceivingDocument` — aggregate roots с owned-секциями |
| C | `Workshop`, `UnitOfMeasurement`, `GridFormView` | `sum` на canonical-поверхность + перевод `WorkshopListView`; `shortCode` → `@SearchFields`; у `GridFormView` снимается только дубль-override |
| E | `AttributeValueService`, `NomSklAttributeService`, `SklNomOpaService`, `PrdSpecService`, `UserService`, `AttributeTypeService` | домен остаётся, стандартные read/write делегируются canonical |
| F | `GridFormViewService`, `UreportTemplateService` | не удаляются, но перестают наследовать compatibility base |

Заборы C4.6 (`CompatibilityMigrationArchitectureTest`): allowlist наследников
compatibility base (17 → 0), allowlist «модель → application service» (11 → 0), UI без
зависимостей от `org.ip.repository`, UI без собственного `EntityManager` (1 → 0). Каждый
список проверяется в обе стороны, поэтому его можно только уменьшать.

Пятый гейт C4.6 (ADR-0007 §5): агрегат с declared owned-секциями нельзя сохранить прямым
`EntityDataAccess.create/update` — такой intent не несёт графа секций и молча сохранил бы
только шапку. Отказ происходит до RLS, валидации, хуков и событий; aggregate boundary
(`MetadataDrivenAggregateSaveService`) остаётся единственным путём и вызывает `save()`
(`aggregateRootWithOwnedSectionsRejectsDirectWriteIntent`).
