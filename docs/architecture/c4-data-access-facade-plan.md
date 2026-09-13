# C4: узкий data-access facade — план работ

Статус: `IN PROGRESS` — C4.0–C4.2 закрыты. Ядро C4.3 реализовано и hardening пройден, но формальное закрытие остаётся pending: write telemetry, первый production-каталог на canonical write path и полный form → write → audit acceptance. C4.4 server-side search закрыт для стандартного list/lookup пути: один builder, согласованные overloads, per-type поля через canonical search, literal escaping и bounded paging. Контекст `GLOBAL_SEARCH` определён, подключение существующего provider service к нему остаётся в C4.5.
Родительский этап: [`JMIX_GitVaa_Roadmap_v2.md`, C4](../../JMIX_GitVaa_Roadmap_v2.md#c4-узкий-data-access-facade)
Связанные нарушения: `ADX-04`, `ADX-05`, `ADX-08`, платформенная часть `ADX-10`.
Использует результаты: C2 fail-closed RLS boundary; C3 FetchPlan и InstanceName.

## 1. Исходное состояние

После C3 платформа умеет:

- применять `LIST`, `DETAIL`, `LOOKUP` и `ROW` FetchPlan в существующих read-path;
- вычислять единый InstanceName и его fetch-зависимости;
- выполнять раннюю write-авторизацию и обязательный read enforcement;
- сохранять metadata-declared owned sections через aggregate boundary;
- запрещать прямой persistence из application UI и application services.

Однако стандартный прикладной путь всё ещё требует избыточной обвязки. На момент
планирования production persistence unit содержит 37 JPA entity types. Из них 18 классов
в `org.ip.model` имеют фактическую `@EntityMetadata`, четыре класса являются owned rows,
причём два owned row одновременно имеют `@EntityMetadata`. В `org.ip.service` находятся
16 наследников `AbstractBaseService`, а во всём production-коде — 17 с учётом
`UreportTemplateService`. В `org.ip.repository` находятся 18 repository-интерфейсов и
11 повторяющихся `searchByTerm` declarations.

Кроме `AbstractBaseService`, в production уже существует вторая generic CRUD-база —
`ValidatedJpaCrudService`; её используют `JrxmlTemplateService` и
`ReportTemplateService`. Она предоставляет validation, reference check, lifecycle и
events, но не включает обязательные RLS/fetch/search policies стандартного entity path.

Основные источники сложности:

- `ServiceLocator` требует `@EntityMetadata.serviceClass` либо bean name
  `<entity>Service`;
- `AbstractBaseService`, `LookupService` и `JpaGlobalSearchProvider` содержат отдельные
  query paths с близкой оркестрацией RLS, fetch и Criteria API;
- `ValidatedJpaCrudService` образует ещё одну generic CRUD-лестницу, судьба которой не
  может остаться за пределами C4 inventory;
- `ManagedEntityCatalog` и C3 `ManagedEntityTypes` намеренно содержат весь persistence
  unit, но JPA-managed type сам по себе не означает право на публичный data handle;
- `RowDraft.restore` перечитывает каждую сохранённую ссылку owned row отдельным
  `LookupService.findById` при отмене редактирования;
- стандартный search повторяется в repositories/services, а часть реализаций использует
  `findAll()` с фильтрацией в памяти;
- `BaseService.search(String, Pageable)` и
  `BaseService.findAll(Specification, Pageable)` допускают production-time
  `UnsupportedOperationException` на generic path;
- required/type/reference facts повторяются между Java type, JPA, Bean Validation и UI
  metadata;
- global search требует центральной ручной регистрации application entities.

C4 не добавляет ещё один слой поверх этих механизмов. Он сводит стандартные операции к
одной реализации и превращает прежние API в миграционные адаптеры, после чего удаляет
заменённую обвязку.

## 2. Цель и измеримый результат

C4 должен сделать стандартный entity data path безопасным по умолчанию и не требующим
прикладного инфраструктурного кода.

Artifact budget стандартного справочника после C4:

```text
entity + предметные поля/constraints

application repository:            0 обязательных
application CRUD service:          0 обязательных
repository search query:           0 обязательных
display-name method/config:        0 обязательных
central search registration:       0 обязательных
ручные RLS/fetch/telemetry calls:   0 обязательных
```

Typed repository, service, query или provider допустимы только при наличии предметной
семантики, специальной модели выдачи либо доказанной оптимизации. Их существование не
должно быть условием работы стандартных list/detail/selection/item forms.

## 3. Границы этапа

### 3.1. В C4 входят

- единый canonical data-access path для стандартных entity reads и CRUD;
- явная таксономия persistence types и capabilities допустимых операций;
- intent-oriented операции `list`, `detail`, `lookup`, `create`, `update`, `delete`;
- использование FetchPlan C3, обязательного RLS enforcement и telemetry context;
- generic fallback для сущности без application repository/service;
- effective field metadata и startup diagnostics конфликтов;
- metadata-driven server-side search;
- модульное участие entities в global search;
- миграция application forms и простых services/repositories;
- устранение per-reference reload в `ItemTable`/`RowDraft` restore path;
- явный compatibility lifecycle прежнего `BaseService`/`AbstractBaseService`;
- явное решение для `ValidatedJpaCrudService` и его consumers;
- архитектурные, поведенческие и cost gates.

### 3.2. В C4 не входят

- resource/attribute/action permissions — это C5;
- физическое разделение Maven-модулей, starter и reference application — это D;
- form authoring/scaffolding и общий typed customization API — это E3;
- собственный query DSL вместо существующих Criteria/Specification механизмов;
- полнотекстовый движок, fuzzy search и relevance ranking;
- автоматическое удаление repositories с предметными запросами;
- замена typed application use cases универсальным CRUD;
- обнаружение и сохранение произвольных object graphs как агрегатов;
- универсальный projection/grouping engine без production-потребителя;
- изменение PostgreSQL/schema-migration gate.

Для `DAC-08` сохраняется запрет raw persistence из UI. Secured projection path
проектируется отдельным срезом C4.x только вместе с первым реальным production
grouping/projection consumer; отсутствие speculative API не блокирует основной C4.

## 4. Целевая архитектура

### 4.1. Один canonical path

Рабочее имя публичной точки входа — `EntityDataAccess`; окончательное имя и минимальная
public surface фиксируются ADR в C4.0.

```text
ListForm / ItemForm / EntityField / Global Search / typed use case
                              |
                              v
                      EntityDataAccess
               list / detail / lookup / write
                              |
          +-------------------+-------------------+
          v                   v                   v
       RLS policy         FetchPlan          Telemetry policy
          |                   |                   |
          +-------------------+-------------------+
                              v
                     JPA query/write engine
                              ^
                Effective metadata + InstanceName
```

Facade является orchestration boundary, но не policy owner. Он не вычисляет RLS,
FetchPlan, instance name или telemetry policy самостоятельно.

Правило миграции:

```text
старый вызов -> AbstractBaseService -> EntityDataAccess
новый вызов ------------------------> EntityDataAccess
```

`AbstractBaseService` не остаётся второй реализацией. Во время миграции он является
тонким compatibility-адаптером; новый application code не должен от него наследоваться.
Typed service использует facade через композицию. До начала D прежний путь должен быть
схлопнут: либо класс удалён, либо оставлен только deprecated adapter без собственной
query/write orchestration.

### 4.2. Таксономия типов и capabilities

`ManagedEntityCatalog` остаётся сырой границей metamodel и источником всех JPA-managed
types. Он не является каталогом публично доступных business entities и не должен сам по
себе разрешать generic CRUD.

C4 вводит один классифицированный descriptor поверх существующих каталогов, без нового
classpath scan. Минимальная таксономия:

```text
PERSISTENCE_TYPE  — любой type текущего persistence unit
STANDARD_ROOT     — самостоятельный metadata-driven root с canonical data handle
OWNED_ROW         — строка declared section, доступная только через aggregate boundary
INTERNAL_STORE    — platform/report/settings/telemetry storage без public entity facade
```

Descriptor хранит как минимум:

- JPA-managed и metadata-driven признаки;
- resolved entity kind;
- root/owned-row/internal-store classification;
- разрешённые read/write operations;
- допустимые FetchScenario;
- причину custom policy или ограничения capability.

Один type не может одновременно быть `STANDARD_ROOT`, `OWNED_ROW` и `INTERNAL_STORE`.
`SectionMetadataRegistry` остаётся источником факта owned-row ownership, а не
дублируется новым сканером. Судьба C3 `ManagedEntityTypes` фиксируется C4.0: он либо
остаётся raw sorted metamodel snapshot для registries, либо делегирует новому descriptor
catalog, но не становится вторым независимым источником типов.

Минимальные правила:

- `STANDARD_ROOT` допускает `LIST`, `DETAIL`, `LOOKUP` и только явно разрешённые writes;
- `OWNED_ROW` допускает `ROW` внутри aggregate boundary, но не получает автономный
  list/detail/lookup/CRUD handle;
- `INTERNAL_STORE` обслуживается владельцем subsystem и не становится business entity
  только потому, что присутствует в JPA metamodel;
- intentional immutable/controlled entities не получают generic update/delete в обход
  typed policy;
- вычисление FetchPlan само по себе не предоставляет право на data operation.

Невалидная пара `(type, operation/scenario)` отклоняется до RLS, provider callback и SQL.

### 4.3. Intent-oriented API

API выражает назначение операции, а не persistence-механику:

- `detail(type, id)` использует `DETAIL`;
- `list(type, filter, pageable)` использует `LIST`;
- `lookup(type, term, limit)` использует `LOOKUP`;
- дополнительные пути динамического вида могут только расширять scenario plan;
- `create`, `update` и `delete` являются разными write intents и требуют соответствующей
  capability;
- unbounded `findAll()` не является golden path интерактивного UI.

Пользователь API не передаёт `EntityGraph`, Hibernate session, RLS predicate или
telemetry scope. Существующий `Specification` допустим как вход Spring/JPA adapter в C4;
создание собственного filter DSL не входит в этап.

### 4.4. Единый read pipeline

Каждое стандартное чтение проходит одинаковую последовательность:

1. получить классифицированный descriptor для JPA-managed type;
2. проверить capability операции и допустимость FetchScenario;
3. применить обязательный read gate и активировать RLS;
4. разрешить `scenario plan ∪ additional paths`, валидировать и углубить union ровно
   один раз;
5. построить content query и отдельный count query при paging;
6. выполнить запрос;
7. завершить telemetry scope согласно operation policy.

RLS применяется до provider/query callback. Fetch joins не попадают в count query.
Owned row не получает автономные list/detail/lookup/write операции: его data boundary
остаётся у aggregate root и `GenericOwnedSectionService`.

Разрешение итогового набора fetch paths выполняется в одном компоненте. Потребитель не
может отдельно объединить paths и вызвать `FetchGraphs.fromPaths`, минуя validation и
`deepen`. C4 устраняет текущую асимметрию: `AbstractBaseService` углубляет объединение
scenario/additional paths, а `LookupService` строит graph из union без углубления
additional paths.

### 4.5. Единый write pipeline

Стандартные `create`, `update` и `delete` переиспользуют существующие механизмы:

```text
write intent
-> ранняя RLS authorization
-> server/structural validation
-> entity lifecycle
-> metadata-declared aggregate processing
-> persistence
-> events / audit / telemetry
```

Facade не обнаруживает произвольные агрегаты. Он может делегировать установленной
metadata-driven boundary для явно объявленных owned sections, но предметная команда,
несколько независимых roots и специальная транзакционная оркестрация остаются typed use
case.

Custom data policy/provider регистрируется по явному entity type, только если стандартное
поведение недостаточно. Две custom registrations для одного type являются startup error.

Intentional prohibitions также являются частью capability/policy, а не случайным
`UnsupportedOperationException` глубоко в service. Generic fallback не может открыть
update/delete для immutable `SklNomOpa` или controlled `AttributeValue`. Такие domain
ограничения сохраняются как typed contract и проверяются отдельно от generic runtime
traps.

`ValidatedJpaCrudService` не копируется в новый executor. В C4.0 выбирается одно из двух
решений: общая validation/lifecycle/event механика поглощается canonical write pipeline,
либо класс остаётся узким internal storage adapter для non-metadata report stores. Он не
может обслуживать `STANDARD_ROOT` параллельно canonical path.

### 4.6. Effective metadata: server и UI semantics

Нужно различать исходные факты, серверный контракт и представление формы:

```text
serverRequired <- Bean Validation + JPA + domain rules
uiRequired     <- explicit UI override либо serverRequired
diagnostics    <- сравнение всех источников
```

Общий приоритет вывода UI defaults:

```text
explicit UI override
-> Bean Validation
-> JPA metadata
-> Java type
-> platform fallback
```

Текущий `boolean required() default false` заменяется семантикой, различающей как
минимум `AUTO`, `REQUIRED`, `OPTIONAL`. Точное имя enum/API фиксируется C4.0.

Правила:

- UI metadata не становится скрытой domain constraint для REST/import/use cases;
- write pipeline использует server constraints и может раньше БД проверить JPA
  nullability;
- UI optional при server-required является startup error;
- UI required при server-optional является допустимым явным ограничением либо warning,
  но не меняет server contract молча;
- несовместимые Java/JPA/reference/field types являются startup error;
- resolved descriptor хранит effective value и источник решения для диагностики.

`@FieldMetadata` в C4 остаётся признаком участия поля в автоматически строящейся форме.
Все persistent fields могут быть известны metadata core, но не включаются в UI
автоматически: технические, audit и внутренние поля не должны появляться в форме из-за
рефакторинга defaults.

### 4.7. Search semantics

Default search выполняется в БД, с paging/limit, RLS и подходящим FetchPlan.

Приоритет search fields:

1. явный override конкретного search context;
2. прямые строковые пути InstanceName;
3. семантические defaults entity kind (`code`, `name`, `number` при наличии);
4. startup error для entity, явно участвующей в search, если корректных полей нет.

Для lookup конкретного reference field явные `@Lookup.searchFields` расширяют или
переопределяют entity default по принятому в C4.0 правилу. Они не становятся неявной
глобальной search policy.

Default engine обеспечивает:

- case-insensitive contains/prefix contract;
- escaping `%`, `_` и `\`;
- server-side pagination или строгий maximum limit;
- детерминированный fallback sort;
- отсутствие `findAll().stream().filter(...)`;
- одинаковую policy-resolution для list search, lookup и global search;
- явный custom provider для full-text, joins или специального ranking.

`BaseService.search(String, Pageable)` перестаёт быть runtime trap. В переходном слое
он делегирует default engine; в canonical API standard search всегда реализован либо
невалидная searchable-конфигурация отклонена при старте.

Переход на literal escaping является осознанным изменением local search, где прежние
repository queries трактовали `%` и `_` как wildcard. До миграции строится per-entity
compatibility matrix: old/new fields, blank term, case, special characters, order,
limit/paging и выдача на одном наборе данных. Каждое отличие либо устраняется, либо явно
принимается и фиксируется тестом.

### 4.8. Global-search participation

Стандартное участие в global search требует явного intent, но не повторения fields.
Предпочтительная модель:

- entity-level intent входит в effective metadata;
- стандартный contributor строит source из managed entities и defaults;
- модульный `GlobalSearchContributor`/provider описывает только отличие;
- центральный `GlobalSearchApplicationConfig` является migration adapter и удаляется к
  завершению C4.

Точное Java-представление entity-level intent (`@EntityMetadata` property либо отдельная
узкая декларация) выбирается C4.0 с условием: оно не должно дублироваться одновременно в
entity и contributor.

Startup validation обнаруживает duplicate entity, unmanaged type, неизвестное поле,
неподдерживаемый field type, несколько providers и отсутствие search/display defaults.

Standard `JpaGlobalSearchProvider` не является самостоятельным security-relevant read
path. Он выполняется внутри canonical RLS/fetch/telemetry boundary либо делегирует общему
secured query executor. Provider может владеть специфичными ranking, timeout и
classification, но не повторно решать enforcement и graph resolution. То же правило
обязательно для custom providers.

### 4.9. Telemetry policy

Data operation context содержит только технические сведения:

- operation (`list`, `detail`, `lookup`, `create`, `update`, `delete`);
- entity type;
- FetchScenario;
- outcome (`success`, `empty`, `denied`, `error`);
- paging/limit в безопасной форме;
- duration через существующий telemetry mechanism.

Search term, entity values, RLS grants и пользовательские field predicates в telemetry
не записываются.

`LOOKUP` является горячим путём:

- обычный успешный lookup не создаёт durable event на каждое нажатие клавиши;
- разрешены агрегированные counters/timers и bounded sampling;
- slow/error/deny могут создавать отдельное событие;
- security deny продолжает аудитироваться security boundary;
- включённая telemetry не добавляет SQL-запись к каждому lookup query.

## 5. Последовательность работ

### C4.0. Inventory, characterization и ADR

1. Классифицировать все production implementations `BaseService`, обе generic CRUD-базы
   (`AbstractBaseService`, `ValidatedJpaCrudService`), их subclasses и application
   repositories:
   - чистый CRUD/search boilerplate;
   - typed domain operation;
   - optimized/custom query;
   - infrastructure/admin storage;
   - aggregate-specific operation.
2. Классифицировать все 37 persistence types как `STANDARD_ROOT`, `OWNED_ROW` либо
   `INTERNAL_STORE`; отдельно зафиксировать 18 metadata-driven types, четыре owned rows
   и два пересечения этих множеств.
3. Найти все consumers `BaseService`, `ServiceLocator`, `LookupService`,
   `ValidatedJpaCrudService`, прямых repositories, `RowDraft.restore` и global-search
   config/provider.
4. Зафиксировать baseline количества классов, registrations, 11 `searchByTerm`, generic
   runtime traps и per-reference restore queries с однозначно описанной областью
   подсчёта.
5. Построить до изменений:
   - per-entity search compatibility matrix: fields, blank term, case, `%`/`_`/`\`,
     order, limit/paging и результаты на одном dataset;
   - snapshot effective `required/type/reference` для всех UI fields;
   - перечень intentional create/update/delete prohibitions.
6. Добавить characterization tests для текущих list/detail/lookup/search/write и row
   restore paths.
7. Принять ADR, который определяет:
   - canonical facade API и границы public/SPI/internal;
   - судьбу `BaseService`/`AbstractBaseService`;
   - судьбу `ValidatedJpaCrudService` и двух его consumers;
   - taxonomy/capability descriptor и судьбу C3 `ManagedEntityTypes`;
   - standard fallback и custom override precedence;
   - representation intentional write prohibitions;
   - server/UI metadata semantics;
   - контракт `search`, blank term и literal wildcard semantics;
   - telemetry policy для LOOKUP;
   - compatibility/removal milestones.
8. Выбрать пилоты:
   - isolated standard catalog fixture без repository/service;
   - simple entity с явным `serviceClass`;
   - simple entity, обслуживаемая magic bean name;
   - один typed service с предметным методом для проверки композиции.

Кандидаты реальных пилотов после call-site audit: `Branch`/`Journal` для явного
`serviceClass`, `UnitOfMeasurement` для bean-name convention и `PrdSpecService` как
пример сохранения typed `findByJournal` поверх canonical standard reads.

Критерий завершения: каждый persistence type имеет ровно одну classification и набор
capabilities; для каждого service/repository/generic base есть решение `remove`,
`absorb into canonical path`, `retain as internal store`, `retain as domain port`,
`retain as optimized provider` или `migrate`; неизвестных consumers не осталось.

### C4.1. Unified read kernel

1. Реализовать classification/capability descriptor C4.0 поверх существующих
   `ManagedEntityCatalog` и `SectionMetadataRegistry`, без нового classpath scan.
2. Ввести internal immutable read request с entity type, intent/scenario, filter,
   pageable/limit и additional fetch paths.
3. Извлечь proven content/count/sort алгоритм из
   `AbstractBaseService.findAllWithFetchGraph` в один executor для `STANDARD_ROOT`,
   сохранив поведение parity-тестами до оптимизации.
4. Подключить RLS и FetchPlan как отдельных collaborators.
5. Централизовать `scenario plan ∪ extras -> validate -> deepen once -> graph`; запретить
   построение scenario EntityGraph вне этого resolver/executor.
6. Проверить paging с joins/distinct и отсутствие fetch nodes в count query.
7. Подключить telemetry decorator с noop/optional behaviour.
8. Перевести стандартные reads `AbstractBaseService` на executor без изменения public
   поведения.
9. Перевести `LookupService` на тот же executor; удалить собственную дублирующую
   Criteria/fetch orchestration.
10. Устранить per-reference reload в `RowDraft.restore`: предпочтительно восстановить
    захваченную исходную ссылку без SQL, иначе использовать один bounded/batch canonical
    read вместо N `findById`.
11. Выполнять standard и custom global-search providers только внутри canonical
    RLS/fetch/telemetry boundary; provider-specific ranking/classification/timeout
    остаются обязанностью search subsystem.
12. Перевести compatibility
    `BaseService.findAll(Specification, Pageable)` на canonical executor и исключить
    generic `UnsupportedOperationException` для `STANDARD_ROOT`.

Критерий завершения: `AbstractBaseService`, `LookupService` и standard global search не
строят независимые security/fetch query boundaries; все overloads сохраняют scenario
contract и mandatory RLS; row cancel не выполняет per-reference reads.

### C4.2. Effective metadata и conflict diagnostics

1. Разделить raw annotations и resolved/effective descriptors.
2. Перед изменением сохранить проверяемый snapshot effective
   `required/type/reference` и количество значений каждого origin.
3. Ввести tri-state required override и выполнить механическую миграцию declarations.
4. Выводить default field type из Java type.
5. Выводить reference target из Java/JPA association metadata.
6. Выводить server nullability из Bean Validation и JPA.
7. Выводить UI required из server contract с явным UI override.
8. Хранить origin каждого effective fact.
9. Выполнять eager startup validation для всех применимых managed entities/fields, а не
   только при первом открытии формы.
10. Классифицировать diagnostics как error/warning/info и сделать сообщения
    entity/field/source-aware.
11. Исправить либо явно разрешить известную рассинхронизацию
    `ReceivingDocument.journal`.
12. На пилотах удалить дублирующие `required`, `ENTITY_REFERENCE` и lookup target, не
    меняя layout/visibility/order.
13. Сравнить snapshot до/после: каждое изменение effective значения должно быть
    ожидаемым и явно перечисленным; непредусмотренный diff ломает тест.

Критерий завершения: форма пилотной сущности сохраняет поведение при меньшей декларации;
количество реально изменившихся `required/type/reference` известно и объяснено, а
намеренно внесённый UI/server/JPA conflict останавливает startup с точной причиной.

### C4.3. Standard CRUD и generic fallback

1. Выделить существующую write orchestration из `AbstractBaseService` в canonical
   executor либо делегировать уже существующим policy owners без копирования.
2. Явно поглотить общую механику `ValidatedJpaCrudService` либо ограничить этот класс
   ролью internal-store adapter согласно ADR C4.0; не создавать третью реализацию.
3. Реализовать default data handle/service только для `STANDARD_ROOT` с разрешёнными
   operation capabilities.
4. Подключить create/update/delete, server validation, lifecycle, metadata aggregate
   boundary, events и audit. Write-telemetry (operation scope вокруг canonical write)
   остаётся не реализованной в этом срезе: у executor'а пока нет write-telemetry
   collaborator'а, а `ReadTelemetry` покрывает только чтение.
5. Реализовать type-directed resolver для form infrastructure:
   - canonical generic path по умолчанию;
   - явный typed custom policy/provider при необходимости;
   - отказ для `OWNED_ROW` и `INTERNAL_STORE`;
   - отказ для write intent без capability;
   - duplicate custom registration — startup error.
6. Перевести isolated test entity на form/CRUD без application repository/service уже
   поверх effective metadata C4.2.
7. Перевести первый реальный простой справочник, не удаляя ещё общий compatibility API.
8. Проверить, что intentional prohibitions `AttributeValue`/`SklNomOpa` не обходятся
   generic fallback.

Критерий завершения: стандартная entity проходит list/detail/create/update/delete и
RLS tests без Spring Data repository, application service, `serviceClass` и magic bean
name; owned/internal/immutable types не получают лишних capabilities.

Статус среза (2026-09-14): ядро пп. 1–6 и 8 реализовано; формальное закрытие C4.3 отложено до выполнения оставшихся пунктов:

- **п.4 write-telemetry** — seam не заведён (переносится отдельным шагом);
- **п.7 «перевести первый реальный простой справочник»** — типизированные сервисы реальных
  справочников сохранены, production canonical fallback пока не несёт нагрузку. Перевод
  реального справочника логично делать вместе с поглощением `AbstractBaseService` (§3,
  C4.7), иначе он смешивается с широкой миграцией;
- **полноценный form/vertical acceptance** — canonical path проверен на изолированной
  fixture и на boundary full-context тестах; сквозной form → canonical write → audit
  сценарий на реальной сущности ещё не написан.

### C4.4. Metadata-driven server-side search

1. Утвердить per-entity compatibility matrix C4.0 и явно принять либо устранить каждое
   изменение fields/blank/case/wildcard/order/limit semantics.
2. Зафиксировать default search-field resolution, blank-term semantics и literal
   escaping пользовательского ввода.
3. Реализовать общий Criteria search с RLS, paging/limit, escaping и deterministic sort.
4. Различить list, lookup и global-search request context без копирования query builder.
5. Сделать standard `BaseService.search` compatibility-делегатом вместо
   `UnsupportedOperationException`.
6. Не смешивать generic runtime traps с intentional domain prohibitions: последние
   остаются typed capability/policy и покрываются отдельными тестами.
7. Перевести пилоты с repository `searchByTerm` на default engine.
8. Удалить in-memory `findAll()` search с standard entity path.
9. Оставить явный provider/typed query для special joins, full-text или ranking.

Критерий завершения: новый стандартный каталог ищется без repository query/service
override; old/new выдача сопоставлена per entity, а принятые изменения blank term,
special characters, order и paging зафиксированы поведенчески.

Статус среза (2026-09-14): пп. 1–8 закрыты. `LIST` и `LOOKUP` выполняются общим builder'ом; оба `BaseService.search` overload используют одну семантику и поля. Все стандартные корни переведены на него: обычные поля выводятся из metadata/`@InstanceName`, а для `PrdSpec`, `SklNomOpa`, `UnitOfMeasurement`, `GridFormView` сохранены прежние поля через explicit field set. У `User` убран unbounded in-memory search; `NomSklAttribute` использует стандартный контракт сущности без строковых search fields. Все repository `searchByTerm` и `findWithFilter` у стандартных корней удалены. `SearchContext.GLOBAL` имеет отдельный telemetry intent `GLOBAL_SEARCH`; подключение `GlobalSearchService` и его providers к builder'у остаётся в C4.5. П.9 сохранён как extension point для действительно специализированных query/provider реализаций; текущие standard roots такой реализации не требуют.

### C4.5. Modular global search

1. Ввести entity-level participation intent без повторения search/display fields.
2. Реализовать standard contributor только для eligible `STANDARD_ROOT` поверх
   capability catalog, effective metadata и InstanceName; raw присутствия type в
   `ManagedEntityCatalog` недостаточно.
3. Поддержать modular contributors/custom providers как SPI с явным entity type.
4. Определить deterministic source order независимо от Spring bean discovery order.
5. Добавить startup diagnostics конфликтов и невалидных defaults.
6. Мигрировать существующие `Nomenclature`, `PrdSpec`, `ReceivingDocument`.
7. Выполнять standard/custom providers внутри canonical secured query boundary, оставив
   provider только ranking/classification/timeout semantics.
8. Удалить центральный `GlobalSearchApplicationConfig` после behavioural parity.

Критерий завершения: новый модуль добавляет searchable entity без изменения общего
application config, а custom provider объявляет только нестандартную семантику.

### C4.6. Application migration

Миграция выполняется по одной вертикали за раз:

1. form resolver/coordinator получает canonical data access;
2. стандартные read/write/search операции уходят из application service;
3. `serviceClass` удаляется из entity;
4. magic bean-name lookup перестаёт использоваться;
5. пустой service удаляется;
6. repository удаляется, только если не имеет оставшихся domain queries/consumers;
7. typed domain methods переносятся в обычный use case/service с композицией facade;
8. standard InstanceName добавляется оставшимся мигрируемым каталогам, чтобы search и
   display не возвращались к параллельным policies;
9. `ValidatedJpaCrudService` и его report consumers приводятся к решению ADR C4.0, а не
   остаются незадокументированной второй generic CRUD-базой;
10. `ItemTable`/`RowDraft` cancel/restore path не выполняет UI-managed N+1 reload.

Очередность:

- простые catalogs без domain methods;
- catalogs с одной специализированной query;
- standard documents с typed query/use case;
- сложные services только после явной декомпозиции обязанностей;
- infrastructure/admin/report stores не мигрируются механически как business entities.

Критерий завершения: все `BaseService` implementations и application services
классифицированы и мигрированы согласно решению C4.0; оставшийся typed service не
повторяет canonical CRUD/search, а internal storage adapter не доступен как standard
entity facade.

### C4.7. Cleanup и основной acceptance

1. Удалить `serviceClass` из golden-path metadata и production declarations.
2. Удалить `<entity>Service` convention из form/data resolution.
3. Удалить заменённые query builders, adapters и repository search methods.
4. Сделать `AbstractBaseService` тонким deprecated adapter без собственной orchestration
   либо удалить его, если consumers мигрированы полностью.
5. Поглотить `ValidatedJpaCrudService` либо ограничить его internal-store область
   architecture rule согласно ADR.
6. Запретить новым production-классам наследовать compatibility base service.
7. Обновить architecture tests:
   - каждый JPA type имеет ровно одну exposure classification;
   - `ManagedEntityCatalog` membership не предоставляет data handle автоматически;
   - UI не зависит от repository/`EntityManager`;
   - standard entity не требует service/repository;
   - application model не ссылается на service class;
   - standard search не фильтрует `findAll()` в памяти;
   - owned row не получает автономный data handle;
   - internal store не выдаётся как standard root;
   - intentional immutable operations не открываются generic fallback;
   - scenario graph нельзя строить вне canonical plan/graph resolver;
   - custom provider registration уникальна и типизирована.
8. Сравнить итоговые artifact counts с C4.0 baseline и объяснить каждый оставшийся
   application repository/service.

Критерий завершения: основной функциональный DoD C4 выполнен, compatibility path не
является равноправным API, а удалённый boilerplate не оставлен рядом с facade.

### C4.8. Hardening и закрытие этапа

C4.8 планируется заранее и не является неопределённым резервом. Проверяются конкретные
классы регрессий:

- каждый public overload проходит одинаковые RLS/fetch/telemetry boundaries;
- каждый persistence type имеет единственную classification, а invalid
  `(type, operation/scenario)` отклоняется до provider/SQL;
- custom provider не может затенить standard type по порядку регистрации;
- generic fallback и typed use case выбираются детерминированно;
- Spring partial/slice contexts не меняют resolution для известных типов;
- list paging/count корректны при association sort/filter и dynamic fetch paths;
- `scenario plan ∪ additional paths` валидируется и углубляется единообразно для list и
  lookup;
- detached render после list/detail/lookup не вызывает скрытых запросов;
- write deny происходит до validation, lifecycle hooks, before-events и SQL;
- lookup telemetry не создаёт durable event/SQL на каждое нажатие;
- cancel/restore owned row не выполняет отдельный read каждой ссылки;
- blank/special-character search не превращается в unbounded read;
- per-entity old/new search matrix не содержит необъяснённых изменений;
- effective metadata snapshot не содержит необъяснённых изменений
  `required/type/reference`;
- global search не выполняет provider для denied source;
- random/reverse-order regression runs не обнаруживают shared static/context state;
- удаление compatibility code подтверждено architecture tests и source search;
- roadmap, ADR, audit и baseline синхронизированы с фактическим checkout.

Этап закрывается только после исправления найденных hardening-дефектов и повторной
приёмки, а не после формального выполнения миграционного списка C4.6.

## 6. Промежуточные измеримые результаты

| После среза | Проверяемый результат |
|---|---|
| C4.1 | Все persistence types классифицированы; standard list/detail/lookup и global-search provider проходят одну secured query boundary; row cancel не создаёт N reads |
| C4.2 | Effective metadata snapshot стабилен; форма пилота сохраняет поведение при меньшей декларации; все изменения `required/type/reference` перечислены |
| C4.3 | Zero-boilerplate standard catalog проходит form/read/write/RLS сценарии; owned/internal/immutable types не получают лишних capabilities |
| C4.4 | Standard search работает server-side без repository override и generic runtime traps; old/new выдача сопоставлена per entity |
| C4.5 | Searchable entity подключается модульно без центрального config; provider-specific ranking остаётся внутри общей security/fetch/telemetry boundary |
| C4.7 | Compatibility orchestration удалена или сведена к thin delegate; artifact recount объясняет каждый оставшийся service/repository |
| C4.8 | Hardening/regression gates зелёные, необъяснённых metadata/search/query-cost изменений нет, документы синхронизированы |

## 7. Обязательные тестовые вертикали

### 7.1. Type taxonomy и capability matrix

Все 37 JPA types принадлежат ровно одной exposure classification. Проверяются
`STANDARD_ROOT`, все четыре `OWNED_ROW`, representative `INTERNAL_STORE`, invalid
operation/scenario, отсутствие data handle только на основании membership в
`ManagedEntityCatalog` и deterministic resolution в full/partial contexts.

### 7.2. Standard catalog без обвязки

Test entity содержит только entity declaration, standard base class, предметные поля и
constraints. Проверяются:

- list/detail/lookup;
- create/update/delete;
- server-side search;
- InstanceName/display;
- generated form metadata;
- отсутствие repository/service/config beans.

### 7.3. RLS-protected standard entity

Проверяются allow/deny для list/detail/lookup/create/update/delete, отсутствие entity SQL
при раннем deny и сохранение фильтра при paging/count.

### 7.4. Entity с typed use case и intentional restrictions

Typed service содержит предметный метод, использует facade через композицию и не
повторяет CRUD/search. Form standard path не зависит от существования этого service.
Отдельно immutable/controlled entity доказывает, что generic fallback не открывает
запрещённые create/update/delete.

### 7.5. Custom optimized search/provider

Проверяются явная регистрация, precedence, duplicate conflict, обязательные RLS/fetch
boundaries и отсутствие silent fallback к standard/in-memory search. Ranking/timeout
могут отличаться, но provider не получает обход canonical enforcement/graph resolution.

### 7.6. Metadata conflicts и snapshot parity

Отдельные startup tests покрывают required/nullability, field/reference type, unknown
path, duplicate provider и unsupported search field. Каждый тест проверяет не только
тип исключения, но и entity, field и конфликтующие sources в сообщении. Snapshot
до/после подтверждает, что каждое изменение effective `required/type/reference`
намеренно и объяснено.

### 7.7. Telemetry cost/privacy

Проверяются отсутствие raw term/entity values, отсутствие durable event/telemetry SQL на
каждый успешный lookup, наличие slow/error/deny diagnostics и неизменность entity query
count при включении instrumentation.

### 7.8. Row restore и fetch-plan merge

Cancel/restore owned row не выполняет отдельный read каждой ссылки. Для list и lookup
проверяется единое правило `scenario plan ∪ extras -> validate -> deepen once -> graph`,
включая additional reference, чей InstanceName зависит от nested association.

## 8. Definition of Done C4

C4 завершён, когда одновременно выполнены все условия:

- существует один canonical standard data path, а не facade поверх трёх реализаций;
- все 37 persistence types имеют единственную exposure classification и explicit
  operation/scenario capabilities;
- membership в `ManagedEntityCatalog` сам по себе не предоставляет public data handle;
- standard catalog работает без application repository/service/search override;
- isolated fixture подтверждает полный artifact budget;
- forms не используют `serviceClass` или `<entity>Service` convention;
- application model не ссылается на infrastructure service class;
- `AbstractBaseService`, если ещё существует, является только deprecated delegate и не
  допускается в новом application code;
- `ValidatedJpaCrudService` поглощён canonical write path либо ограничен проверяемой
  internal-store областью и не обслуживает `STANDARD_ROOT`;
- typed services содержат предметные операции и используют canonical path через
  композицию;
- list/detail/lookup применяют обязательный RLS и правильный C3 FetchPlan;
- create/update/delete сохраняют раннюю authorization, validation, lifecycle, aggregate
  boundary, events и audit;
- owned section row не получает автономный CRUD;
- internal store не получает business facade, а intentional immutable/controlled
  operations не открываются generic fallback;
- `scenario plan ∪ additional paths` валидируется и углубляется ровно в одном месте;
- row cancel/restore не выполняет per-reference reload;
- `BaseService.findAll(Specification, Pageable)` не бросает
  `UnsupportedOperationException` на standard path;
- `BaseService.search` не бросает `UnsupportedOperationException` на standard path;
- standard search выполняется server-side с paging/limit и без in-memory `findAll`;
- per-entity old/new search fields, special-character semantics, order и результат
  сопоставлены; каждое изменение явно принято;
- global-search source добавляется модульно без центрального application config;
- standard/custom global-search providers работают внутри canonical
  RLS/fetch/telemetry boundary;
- search/display defaults выводятся из effective metadata и InstanceName;
- required/type/reference defaults не дублируют однозначные Java/JPA/Bean Validation
  facts;
- UI/server/JPA conflicts обнаруживаются при старте;
- effective metadata snapshot не содержит необъяснённых изменений
  `required/type/reference`;
- UI metadata не становится скрытым server constraint;
- telemetry не пишет чувствительные значения и не создаёт durable событие на каждый
  lookup;
- custom policies/providers уникальны, типизированы и выбираются детерминированно;
- UI и application services не получают raw persistence API;
- количество удалённой обвязки измерено, а каждый оставшийся repository/service имеет
  зафиксированную причину;
- C4.8 hardening и согласованный regression gate зелёные;
- `ADX-04`, `ADX-05`, `ADX-08` и платформенная часть `ADX-10` обновлены по фактическому
  результату;
- roadmap, ADR, audit и baseline синхронизированы.

## 9. Риски и способы контроля

| Риск | Контроль |
|---|---|
| Facade становится четвёртым data path | Старые API только делегируют canonical executor; заменённая orchestration удаляется в том же срезе |
| Facade превращается в god object | RLS, FetchPlan, metadata, search, write и telemetry остаются отдельными policies/executors |
| Два golden path сохраняются навсегда | ADR задаёт removal milestone; architecture test запрещает новое наследование `AbstractBaseService` |
| Raw JPA catalog принимается за публичный каталог | Отдельная type classification/capability matrix; membership не выдаёт data handle |
| Owned/internal type получает generic CRUD | Descriptor gate до RLS/provider/SQL; все persistence types классифицированы тестом |
| Generic CRUD поглощает domain use cases | Явная классификация C4.0; typed services используют композицию и сохраняют предметные методы |
| Generic fallback обходит intentional запрет write | Operation capabilities и typed policy для immutable/controlled entities |
| `ValidatedJpaCrudService` остаётся третьей реализацией | Явное решение absorb/internal-store в ADR и architecture restriction для metadata roots |
| Metadata defaults показывают технические поля | `@FieldMetadata` остаётся UI participation gate; inference не означает auto-exposure |
| UI override меняет server contract | Раздельные `serverRequired`/`uiRequired`; конфликт диагностируется, write опирается на server constraints |
| Tri-state миграция тихо меняет формы | Effective metadata snapshot и счётный diff до/после; неожиданный diff ломает тест |
| Search незаметно читает всю таблицу | Paging/limit обязателен; blank-term contract; architecture test против in-memory `findAll` |
| Default search выбирает лишние строки/поля | Явный приоритет fields; не искать автоматически по всем String properties |
| Literal escaping меняет привычную выдачу | Per-entity old/new compatibility matrix и явное принятие каждого semantic diff |
| Global search снова становится центральным registry | Entity intent + modular contributors; центральный config удаляется после parity |
| LOOKUP создаёт telemetry storm | Counters/sampling для success; durable events только slow/error/deny; cost test |
| Custom provider ослабляет RLS/fetch | Provider вызывается внутри общей boundary, после enforcement и plan resolution |
| `plan ∪ extras` углубляется по-разному | Один plan/graph resolver и architecture ban на внешнее построение scenario graph |
| Отмена строки создаёт UI-managed N+1 | Явный `RowDraft` cost gate: zero per-reference reads либо один bounded batch |
| Массовая миграция скрывает регрессию | Вертикальные срезы по одной entity/category, behavioural parity перед удалением старого кода |
| C4 закрывается до стабилизации | Обязательный ограниченный C4.8 с заранее перечисленными gates |

## 10. Порядок вертикальных поставок

Рекомендуемая последовательность, оставляющая checkout рабочим после каждого шага:

1. полный persistence/service/search inventory, capability taxonomy и ADR;
2. canonical read executor с извлечением proven content/count/sort behaviour;
3. единый plan/extra-path resolver, lookup migration и устранение `RowDraft` N+1;
4. effective metadata, tri-state и snapshot parity на пилотах;
5. isolated zero-boilerplate standard catalog поверх готовых metadata defaults;
6. первый реальный CRUD pilot и проверка intentional write restrictions;
7. default server-side search, per-entity parity и удаление первых `searchByTerm`;
8. modular global search внутри canonical secured boundary и удаление central config;
9. миграция простых services/repositories и решение `ValidatedJpaCrudService`;
10. декомпозиция typed services без потери предметных методов;
11. cleanup/architecture gates и artifact recount;
12. C4.8 hardening, regression и синхронизация документов.

Не допускается сначала распространить новый API на все entities, а затем проверять его
границы. Каждый срез должен заменять один существующий путь и удалять его дублирующую
реализацию в том же изменении.

## 11. Связь со следующими этапами

C4 подготавливает C5, предоставляя единую точку применения будущих entity/attribute/action
permissions, но не реализует их раньше времени.

C4 также является обязательным входом в D: физически выделять можно только canonical
API без `serviceClass`, magic bean names и зависимости от component scan application
монолита. Reference application D должна повторно доказать C4 artifact budget уже вне
текущего ERP-домена.
