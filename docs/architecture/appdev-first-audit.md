# AppDev-first: реестр нарушений и план устранения

- Статус: принятый living audit; состояние кода обновляется по мере закрытия пунктов
- Дата исходного среза: 2026-09-11
- Область: прикладной API, metadata, forms, persistence, search, fetch и RLS
- Связанные решения: ADR-0003, ADR-0004, ADR-0005, roadmap v2.3

## 1. Короткое название и правило

Короткое название принципа — **AppDev-first**.

> Прикладной код объявляет предметное намерение и отличия. Повторяемая механика,
> транзакционность, security enforcement, persistence lifecycle и интеграция
> платформенных подсистем принадлежат платформе.

Новый обязательный прикладной артефакт допустим, когда содержит хотя бы одно из:

- предметное правило или workflow;
- специальную security policy, которую нельзя безопасно вывести из metadata;
- нестандартную persistence-семантику;
- действительно отличающийся UI/интеграционный сценарий.

Класс, конфигурация или строковая регистрация, которые только соединяют стандартные
platform components, считаются нарушением AppDev-first.

Correctness и security не упрощаются ради уменьшения числа файлов. Их сложность
переносится за platform API и покрывается тестами.

## 2. Измеренный исходный срез

Текущий прикладной модуль содержит:

| Метрика | Значение |
|---|---:|
| JPA entities в `org.ip.model` | 22 |
| repository-интерфейсы в `org.ip.repository` | 22 |
| наследники `AbstractBaseService`/`AbstractTableSectionService` | 20 |
| repositories с `searchByTerm` | 12 |
| services с собственным search-кодом | 17 |
| объявления `@FieldMetadata` | 64 |
| явные `required = true` | 42 |
| `@NotNull`/`@NotBlank` | 51 |
| `@Lookup` | 21 |
| явные `FieldType.ENTITY_REFERENCE` | 9 |
| ссылки модели на `serviceClass` | 15 |
| упоминания `getDisplayName()` в моделях | 19 |
| `displaySortFields` | 13 |

Числа являются baseline для сравнения, а не самостоятельной целью. Удаление полезного
предметного сервиса ради уменьшения счётчика запрещено.

## 3. Реестр нарушений

Приоритеты:

- `P0` — блокирует принятие соответствующего платформенного контракта;
- `P1` — существенно увеличивает стоимость и риск типового прикладного изменения;
- `P2` — ухудшает ergonomics/диагностику, но имеет рабочий обходной путь.

| ID | Кратко | Приоритет | Статус | Этап закрытия |
|---|---|---:|---|---|
| `ADX-01` | Нет semantic entity archetypes в коде | P0 | `WIP` | B3 |
| `ADX-02` | Save-specific обвязка и domain switch | P0 | `CLOSED в текущем scope` | B3 |
| `ADX-03` | Пустая section persistence wiring | P0 | `CLOSED в текущем scope` | B3 |
| `ADX-04` | Обязательные repository/service и magic lookup | P1 | `OPEN` | C4 |
| `ADX-05` | Повторяющийся/in-memory search | P1 | `OPEN` | C4 |
| `ADX-06` | Дублирование RLS intent | P0 | `DONE: descriptor есть, read-предикат проверяется и для custom-политики` | C2 |
| `ADX-07` | Fetch/session knowledge в UI | P1 | `DONE: граф сценария даёт FetchPlan, форма декларативна` | C3 |
| `ADX-08` | Дублирование JPA/validation/UI metadata | P1 | `OPEN` | C3-C4 |
| `ADX-09` | Несколько источников InstanceName | P1 | `DONE: пилот и каналы отображения на одном источнике` | C3 |
| `ADX-10` | Центральная регистрация global search | P2 | `OPEN` | C4/E3 |
| `ADX-11` | Тяжёлый stringly-typed form API | P2 | `OPEN` | E3 |
| `ADX-12` | Нет единой discoverable точки entity lifecycle | P1 | `WIP: core закрыт` | B4/E3 |
| `ADX-13` | Нет контракта интернированных сущностей | P1 | `DONE: механизм и забор есть, узкий service-шов ожидает C4.7` | C4.6 |

### ADX-01 — отсутствующая семантика типа сущности (`P0`, B3)

**Сейчас.** `BaseEntity` является общей технической основой, но платформа почти одинаково
воспринимает справочник, документ, системную запись и агрегат. Стандартные `code/name` и
`number/date`, их validation, display и numbering metadata повторяются в моделях.

**Риск.** Поведение восстанавливается ad hoc в сервисах и формах; наличие секций начинает
ошибочно использоваться как признак документа.

**Цель.** Реализовать ADR-0004: resolved `EntityKind`, `StandardCatalogEntity`,
`StandardDocumentEntity`, прямой `BaseEntity` escape hatch и независимые capabilities.

**Закрытие.** Kind выводится без дублирования; стандартные поля наследуются; документ
допускает ноль/одну/несколько секций; секции не определяют kind.

**Прогресс 2026-09-11.** Срез B3.1 завершён: добавлены `EntityKind`,
`StandardCatalogEntity` (`code`, `name`), `StandardDocumentEntity` (`number`, `date`)
и effective-kind resolution `AUTO -> inferred/PLAIN` с fail-fast конфликтом явного
kind и base class. Стандартные нумеруемые поля получили роли и platform defaults;
class-level `@NumberingPolicy` меняет policy унаследованного поля без boilerplate и
field shadowing. Общий numbering/semantic/explorer gate: 54 теста зелёные. Контрольные
пилоты уже используют metadata-driven save path, однако прикладные модели ещё не
переведены на `StandardCatalogEntity`/`StandardDocumentEntity` и схема их стандартных
полей не мигрирована; поэтому `ADX-01` остаётся WIP.

### ADX-02 — save-specific обвязка и domain type switch (`P0`, B3)

**Исходный срез.** Для `ReceivingDocument` и `PrdSpec` были повторены handler, form adapter, command,
result и use case. Центральный
[`ItemFormSaveDispatcher`](../../src/main/java/org/ip/application/form/ItemFormSaveDispatcher.java)
импортировал оба domain class и содержал `if` по типам. Generic fallback выполнял
`save header -> commitTableSections`, то есть сохранял шапку и строки в два шага.

**Риск.** Каждый новый стандартный документ требует набора механических классов либо
получает возможность частичного сохранения.

**Цель.** Один metadata-driven `harvest -> transactional save -> apply`; registry custom
handlers остаётся только escape hatch для особой семантики.

**Закрытие.** Dispatcher не знает domain types; registry выбирает только явный custom
override; стандартные пилоты не имеют зарегистрированного save-specific handler;
attached mutable sections никогда не попадают в two-phase path.

**Прогресс 2026-09-11.** B3.4 добавил независимые от домена
`MetadataDrivenAggregateSaveService` и `MetadataDrivenItemFormSaveAdapter`: B3.5–B3.10
добавили `ItemFormSaveHandlerRegistry` с fail-fast duplicate policy и подключили его к
dispatcher. Dispatcher больше не импортирует domain types: custom override выбирается
из registry, а `ReceivingDocument`/`PrdSpec` не зарегистрированы как overrides и
проходят общий metadata-driven engine. В cleanup-срезе удалены переходные typed
handlers/adapters/commands/results/use cases; registry оставлен только как SPI для
реально нестандартной семантики. `ADX-02` закрыт behavioural и architecture tests.

### ADX-03 — пустые services/repositories секций и ручной cascade (`P0`, B3)

**Исходный срез.** `ReceivingDocumentItemService`, `PrdSpecMtrService` и
`PrdSpecOperService` были constructor-only оболочками над generic section service;
для них существовали отдельные repository-интерфейсы, а удаление строк накладной
вручную выполнялось в `ReceivingDocumentService.delete()`.

**Риск.** Каждая новая секция добавляет два инфраструктурных класса и требует не забыть
delete lifecycle, хотя platform metadata уже знает row class, parent и ownership.

**Цель.** Платформенный default section persistence/service из resolved section
descriptor; custom policy только для нестандартного режима. Owned-section cleanup
принадлежит aggregate lifecycle.

**Закрытие.** Стандартная секция не требует application repository/service; добавление
или удаление секции не требует правки root service.

**Прогресс 2026-09-11.** B3.2 завершил metadata foundation: resolved descriptor и
startup registry однозначно знают owner, row, parent/line fields и standard
`MUTABLE_REPLACE_ALL` mode; противоречивые и orphan declarations падают при старте.
В B3.3 появился `GenericOwnedSectionService`, который по descriptor выполняет parent
linking, `minRows`/Bean Validation, line numbering, metadata-derived loading,
replace-all и delete lifecycle с защитой от cross-owner row IDs. Application section
services/repositories затем удалены после parity-проверок. Для UI добавлен
descriptor-bound `MetadataTableSectionService`, а root delete теперь вызывает
metadata-driven `deleteAllOwnedSections` внутри общего lifecycle. `ReceivingDocument`
и `PrdSpec` не требуют application section repository/service; `ADX-03` закрыт
тестами save/delete и startup/architecture checks.

### ADX-04 — обязательная пара repository/service и magic service lookup (`P1`, C4)

**Сейчас.** Почти каждой entity соответствуют Spring Data repository и наследник
`AbstractBaseService`. [`ServiceLocator`](../../src/main/java/org/ipro/crud/ServiceLocator.java)
требует `@EntityMetadata.serviceClass` либо bean с именем `<entity>Service`.
Для standard owned sections `TableSectionFactory` уже использует descriptor-bound
platform adapter; `serviceClass` остаётся только явным escape hatch для custom policy.

**Риск.** Модель связана с application service class; переименование ломает runtime;
обычный CRUD требует классов без предметной семантики.

**Цель.** Secured generic entity data service/data manager для standard path. Typed
repository/service создаётся только для предметных операций или оптимизированных queries.
`serviceClass` и magic bean name остаются временной compatibility policy, затем удаляются.

**Зависимость.** Generic data path вводится после fail-closed RLS boundary, чтобы удобный
API не стал новым обходным каналом.

**Закрытие.** Обычная entity работает без application repository/service и без ссылки
модели на infrastructure class; custom service разрешается явным typed override.

### ADX-05 — повторяющийся и иногда in-memory поиск (`P1`, C4)

**Сейчас.** `AbstractBaseService.search(term, pageable)` бросает
`UnsupportedOperationException`. Repositories повторяют JPQL `LOWER(code/name) LIKE`,
services повторяют обработку пустого терма. `ReceivingDocumentService` загружает
`findAll()` и фильтрует документы в памяти.

**Риск.** Новый справочник требует query + service override; поведение пагинации/RLS
может различаться; in-memory fallback не масштабируется.

**Цель.** Metadata/instance-name-driven secured default search с server-side pagination.
Custom query — только осознанная оптимизация или специальная семантика.

**Закрытие.** Стандартные каталоги и документы ищутся без repository query/service
override; in-memory search отсутствует на entity data path.

### ADX-06 — RLS intent повторяется в нескольких механизмах (`P0`, C2)

**Сейчас.** Защищённая entity одновременно объявляет `@RlsDimension`, Hibernate
`@FilterDef/@Filter` с SQL и `RlsDimensionValue.getRlsChecks()` для write/delete. Имена
измерений являются независимыми строками. `BranchDimensionValueSource` и
`JournalDimensionValueSource` почти одинаково реализуют справочник значений измерения.
Примеры: [`Workshop`](../../src/main/java/org/ip/model/Workshop.java) и
[`ReceivingDocument`](../../src/main/java/org/ip/model/ReceivingDocument.java).

**Риск.** Read и write policy могут разойтись; ошибка является security defect, а не
только неудобством разработки.

**Цель.** Один declarative RLS intent/policy descriptor, из которого платформа строит
read enforcement и write/delete checks. Generic dimension value source выводится из
metadata/InstanceName; custom policy остаётся для сложных отношений.

**Закрытие.** Простое измерение объявляется один раз; нет ручного SQL и `getRlsChecks()`
для стандартного случая; сложная policy покрывает read/write parity tests.

**Сделано в C2.** `@RlsDimension` стал единственным объявлением intent:
`RlsPolicyDescriptor` выводит из него write/delete-проверки, а
generic dimension value source собирается из metadata (`grantValues = true` +
`RlsDimensionValueCatalog`), поэтому дублирующие `BranchDimensionValueSource` и
`JournalDimensionValueSource` из приложения удалены. Read-предикат по-прежнему
объявлен в `@Filter(condition = ...)`, но больше не является непроверенным
дублированием: `RlsDimensionRegistry` вычисляет ожидаемое условие и сверяет его с
фактическим при старте (расхождение — fail-fast). Для сложной политики, где предикат
вывести нечем (`custom = true`), он объявляется явно (`readCondition`) и сверяется тем
же механизмом — то есть ADX-06 закрыт и для `ReceivingDocument`, причём без
обязанности прикладника писать «ещё один тест»: расхождение ловит старт приложения.
Что остаётся человеческим решением, а не машинной гарантией: эквивалентность
произвольного SQL `getRlsChecks()` — это свойство конкретной политики, для
`ReceivingDocument` подтверждённое parity-тестом на одних строках и грантах. Решение
C2 зафиксировано: checked duplication с fail-fast сверкой — конечная форма; генерация
фильтров из descriptor не входит в scope (`C2.4`). Переоценка каналов и gate — в
[`security-channel-matrix.md`](security-channel-matrix.md).

### ADX-07 — Hibernate session/fetch knowledge в форме (`P1`, C3)

**Сейчас.** [`PrdSpecMtrFormCustomization`](../../src/main/java/org/ip/views/forms/PrdSpecMtrFormCustomization.java)
задаёт `LOOKUP_FETCH_DEPTH`, вручную вычисляет association paths и перечитывает выбранные
lookup entities, чтобы избежать `LazyInitializationException` после закрытия сессии.

**Риск.** Прикладной UI знает внутреннее устройство persistence context; аналогичный код
будет копироваться во всех dependent lookups.

**Цель.** Lookup возвращает представление, пригодное для объявленного lookup/row
сценария. Зависимости instance name и value-change сценария входят в FetchPlan либо
запрашиваются декларативным typed API.

**Закрытие.** Custom form не содержит EntityGraph hints, depth constants, reload ради
lazy proxy или обработки `LazyInitializationException`.

**Прогресс 2026-09-13 (закрыто).** Срез C3.3–C3.5: появился `FetchPlanRegistry` с ключом
`(entityClass, scenario)` для `LIST`/`DETAIL`/`LOOKUP`/`ROW` и декларация зависимостей
выбора `@Lookup(fetch = ...)`. `PrdSpecMtr` объявил `unitOfMeasurement` (от номенклатуры)
и `nomenclature` + `nomenclature.unitOfMeasurement` (от спецификации компонента), а
`PrdSpecMtrFormCustomization` читает обычные геттеры. Из кастомизации удалены
`LOOKUP_FETCH_DEPTH`, `FetchGraphs.associationPaths`, перечитывание по ID и знание о
сессии; `FetchGraphs.associationPaths` как отдельный механизм удалён. Закрытие
зафиксировано ArchUnit-правилом `ApplicationFormFetchBoundaryTest`: кастомизация формы не
может зависеть от `jakarta.persistence`/`org.hibernate`, `FetchGraphs` или `LookupService`.
Приёмка — `FetchPlanLookupHydrationIT` (объявленные зависимости приходят загруженными из
`LookupService.search`) и `FetchPlanRegistryTest` (невалидный объявленный путь — отказ
старта). Дополнительно сценарии `LIST`/`DETAIL`/`ROW` подключены к существующей
read-границе (`AbstractBaseService`, `GenericOwnedSectionService`, `ItemTable`), поэтому
перечисление ссылочных полей убрано и из неё; приёмка — `FetchPlanReadBoundaryIT`.

### ADX-08 — дублирование JPA, Bean Validation и UI metadata (`P1`, C3/C4)

**Сейчас.** Обязательность повторяется в `@NotNull/@NotBlank`, `@Column(nullable=false)`
и `@FieldMetadata(required=true)`. Entity reference одновременно известна из Java type,
`@ManyToOne`, `FieldType.ENTITY_REFERENCE` и `@Lookup(entity=...)`. Порядок формы и grid
также задаётся независимо. `MetadataResolver` включает в формы только поля с
`@FieldMetadata`.

Рассинхронизация уже видна у `ReceivingDocument.journal`: DB допускает `null`, `@NotNull`
закомментирован, но UI metadata оставляет `required=true`.

**Риск.** Несогласованная UI/server/DB validation и избыточное описание каждого поля.

**Цель.** Приоритет резолюции:

```text
явный UI override
  -> Bean Validation
  -> JPA metadata
  -> Java type
  -> platform fallback
```

`@FieldMetadata` описывает отличия, а не повторяет уже известный факт.

**Закрытие.** Startup validation обнаруживает противоречия; standard persistent field
получает type/required/reference defaults без дублирования; explicit metadata остаётся
override.

### ADX-09 — несколько источников instance name (`P1`, C3)

**Сейчас.** Модели одновременно используют `HasDisplayName/getDisplayName`, `toString`
и `displaySortFields`; глобальный поиск отдельно задаёт display fields. Форматы могут
различаться.

**Риск.** Одна entity по-разному выглядит в lookup, grid, search, audit и заголовке;
sort semantics снова настраивается вручную.

**Цель.** Единый `InstanceNameResolver` с объявленными fetch dependencies и безопасным
fallback; consumers не реализуют собственную параллельную резолюцию.

**Закрытие.** Пилотные entities имеют один источник instance name; lookup/search/audit
дают согласованное представление без lazy loading.

**Прогресс 2026-09-13.** Срез C3.2: введён `InstanceNameResolver` с публичной
декларацией `@InstanceName` (явные paths либо вывод из metadata `displaySortFields`) и
startup-валидацией. Пилотные `ReceivingDocument` (явные paths `number`+`date`, ранее —
`toString()`) и `Nomenclature` (metadata-derived `code`+`name`) переведены на единый
источник; lookup (`FieldRenderer`), глобальный поиск (`JpaGlobalSearchProvider`), аудит
(`EntitySnapshot`) и RLS-каталог (`RlsDimensionValueCatalog`) используют его для
мигрированных сущностей, а немигрированные сохраняют прежнее представление. На момент
этого среза ещё предстояли приёмка C3.6 (измерения, detached-render и query/graph gates)
и распространение на остальные сущности. Приёмка C3.6 закрыта в следующем срезе;
широкая миграция остальных сущностей остаётся отдельной работой.

**Прогресс 2026-09-13 (закрыто).** Срез C3.3–C3.5: параллельные резолюции имён у
потребителей сняты — единая лестница `definition → HasDisplayName → toString` живёт в
`InstanceNameBridge.displayName`, и через неё теперь идут lookup (`EntityField` — подписи
саггеста и текст поля), grid/list (`FieldRenderer`, `ListForm` группировка и
`ComboBoxFilter`), контекстные фильтры (`ContextFilterPanel`), фильтры вида
(`GridViewEditorDialog`), отчёты (`ReportQueryExecutor`, `ReportQueryEditor`) и
RLS-каталог. Важное следствие: каналы, которые раньше кастовали значение к
`HasDisplayName`, на мигрированной сущности без этого интерфейса (`ReceivingDocument`)
падали или показывали `toString()` — теперь они дают единое имя. Углубление fetch-графов
(`FetchGraphs.deepen`) тоже читает состав имени из единого источника, поэтому grid
загружает ровно то, что нужно имени, а не `selectColumns`. Startup-валидация глобального
поиска принимает `@InstanceName` как самодостаточное представление вместо требования
`HasDisplayName`. Немигрированные сущности сохраняют прежнее поведение — это осознанный
compatibility-путь, а не второй рекомендуемый источник.

**Текущий статус после C3.6–C3.7 (закрыто).** Приёмочные измерения подтверждены тестами;
bridge выбирает подходящую регистрацию и для отображения, и для анализа имени/fetch-путей
по типу сущности. Поздний частичный контекст больше не затеняет resolver, знающий пилотный
тип. Распространение `@InstanceName` на остальные сущности остаётся отдельной миграцией.

### ADX-10 — центральная ручная регистрация глобального поиска (`P2`, C4/E3)

**Baseline до C4.5.** Центральная `GlobalSearchApplicationConfig` требовала для каждой
entity снова перечислить search и display fields.

**Риск.** Новая searchable entity требует правки общего файла; участие легко забыть;
конфигурация дублирует instance-name/field metadata.

**Цель.** Модульные contributors и metadata-driven defaults. Явное включение/исключение
остаётся декларацией intent; поля по умолчанию выводятся из search/instance metadata.

**Закрытие (C4.5, 2026-09-14).** `@GlobalSearchable(order)` включает сущность рядом с
моделью; общий config удалён. Каталог валидирует exposure/capabilities, search fields,
provider collisions/timeouts и наличие подписи при старте.

### ADX-11 — тяжёлый и stringly-typed form customization API (`P2`, E3)

**Сейчас.** Простая регистрация custom form требует отдельного `*FormConfig`. Некоторые
factories получают зависимости через `FormContext.applicationContext().getBean(...)`.
Поля, варианты и параметры задаются строками; `PrdSpecFormConfig` тихо игнорирует
`IllegalStateException`, если ожидаемое поле отсутствует или изменило тип.

**Риск.** Ошибки рефакторинга обнаруживаются только при открытии формы либо молча меняют
поведение; тестирование требует Spring service locator.

**Цель.** Self-describing/annotated factories, typed infrastructure accessors и
проверяемые keys/descriptors. Java customization остаётся полноценным escape hatch, но
не занимается регистрационной механикой и persistence hydration.

**Закрытие.** Простая custom form не требует wrapper config; infrastructure beans не
извлекаются из raw `ApplicationContext`; неизвестное поле/variant/parameter даёт
startup diagnostic, а не silent fallback.

### ADX-12 — нет единой discoverable точки entity lifecycle (`P1`, B4)

**Сейчас.** Authoritative правила перед записью могут находиться в
`AbstractBaseService.validateBusinessRules`, `EntitySavingEvent` listener,
`AggregateSavingEvent` listener или custom save handler. Spring events требуют ручной
проверки типа из-за стирания generic-параметра. По entity class нельзя однозначно найти
её полный набор lifecycle callbacks и их порядок.

**Риск.** Прикладной разработчик нового справочника или агрегата не имеет очевидного
аналога `ПередЗаписью`; правило легко привязать только к одному UI/save path либо
случайно продублировать в нескольких местах.

**Цель.** Реализовать ADR-0005: optional типизированный `<Entity>Lifecycle`, не более
одного authoritative handler на entity type, отдельный callback для root + attached
sections, automatic Spring discovery и resolved отображение в Entity Explorer.

**Прогресс 2026-09-11.** Введены `EntityLifecycle<T>`, context contracts и fail-fast
`EntityLifecycleRegistry`; entity/aggregate callbacks (`beforeSave`, `beforeUpdate`,
in-transaction `onSave`) подключены к стандартным
transaction boundaries. `ReceivingDocumentRulesListener` и
`PrdSpecCrossValidationListener` мигрированы в `ReceivingDocumentLifecycle` и
`PrdSpecLifecycle`; registry и Spring/H2 parity tests зелёные.

**Оставшееся закрытие.** Generic form, direct service и custom use case должны иметь
единый lifecycle contract в подтверждённых channel tests; Entity Explorer должен
показывать resolved handler, а documentation/scaffolder — направлять `ПередЗаписью` в
`beforeSave`. До этих шагов `ADX-12` остаётся `WIP`.

### ADX-13 — нет контракта интернированных сущностей (`P1`, C4.6)

**Сейчас.** Тип, у которого нет пользовательского редактирования, а идентичность —
натуральный ключ (набор значений атрибутов КСУ, значение словаря атрибутов), не имеет
своей формы в платформе. Такой сервис вынужден наследовать широкий generic-контракт
записи и гасить неприменимые методы исключением, а разрешение гонки на создании
копируется из сервиса в сервис вместе с `REQUIRES_NEW`-шаблоном и классификатором
уникального нарушения. Платформа при этом не знает, что идентичность типа закреплена
схемой, и не может отличить его от обычного справочника.

**Риск.** Третий такой тип (составной ключ производственного плана уже назван предметно)
получает копию retry-логики вместо механизма, а «неизменяемый» тип остаётся объявленным
только соглашением в коде: без уникального индекса гонка двух создателей даёт дубль.

**Цель.** Реализовать ADR-0008: интернирование как capability типа плюс поведенческий
маркер `InternedEntity`, один общий механизм `NaturalKeyCreateSupport`, канонизация ключа
остаётся в типе, а идентичность обязана быть объявлена уникальным индексом схемы.

**Прогресс 2026-09-14.** Введены `InternedEntity` (маркер, не `@MappedSuperclass`) и
`NaturalKeyCreateSupport`; `AttributeValueService` и `SklNomOpaService` переведены на него
и больше не содержат своей копии retry. Забор `InternedEntityContractTest` требует от
интернированного типа `@Table(uniqueConstraints)`, а `NaturalKeyCreateSupportTest`
проверяет ретрай, отсутствие ретрая на посторонних ошибках, отдельную транзакцию каждой
попытки и диагностику исчерпания с названным ключом. Профиль записи остаётся
capability типа и у живых случаев различается (`AttributeValue` — `CREATE`,
`SklNomOpa` — без generic-записи вообще).

**Оставшееся закрытие.** Узкая service-форма (`InternedEntityService`: только чтения и
один `getOrCreate`, без `update`/`delete` в интерфейсе) вводится вместе с удалением
`AbstractBaseService` в C4.7, чтобы не заводить нового наследника compatibility-базы;
до этого момента сервисы всё ещё наследуют её и гасят неприменимые методы.

## 4. Что не является нарушением

AppDev-first не означает запрет прикладных классов. Допустимы и желательны:

- typed use case проведения документа, резервирования, расчёта или интеграции;
- listener/policy с реальным бизнес-правилом и server-side veto;
- custom form с отличающейся компоновкой или предметным поведением;
- custom RLS policy для сложного отношения, если она является единым источником read/write intent;
- специализированный repository query с измеренной необходимостью;
- явная декларация owned sections на root entity.

Критерий простой: после удаления platform wiring в классе должна остаться предметная
семантика. Если класс становится пустым — это кандидат на platform default.

## 5. Этапы устранения

### Волна 0 — правило и измеримость (`DONE в документации`)

- дать принципу имя AppDev-first;
- вести этот реестр по стабильным `ADX-*` identifiers;
- для каждого PR указывать изменение artifact/configuration budget;
- не закрывать пункт только удалением файлов: требуются behavioural и architecture tests.

### Волна 1 — semantic model и aggregate save (`B3`, следующая)

Закрывает `ADX-01`, `ADX-02`, `ADX-03`:

1. Реализовать resolved semantic entity/section descriptors и standard base classes.
2. Добавить generic platform section persistence из descriptor, включая delete lifecycle.
3. Реализовать default metadata-driven atomic aggregate save.
4. Завершить registry custom handlers и запретить неоднозначность.
5. Перевести `ReceivingDocument` и `PrdSpec`, сохранив только реальную предметную семантику.
6. Удалить domain switch, two-phase aggregate fallback и пустую section wiring после parity.

Пункт 6 выполнен в cleanup-срезе B3: стандартные pilot adapters/use cases,
section services/repositories и ручной root delete cascade удалены; предметные
lifecycle rules сохранены и в B4 перенесены из listeners в typed handlers.

### Волна 1.1 — application lifecycle DX (`B4`)

Закрывает `ADX-12` без возврата обязательной save-specific обвязки:

1. Ввести типизированный `EntityLifecycle<T>` и context contracts без Vaadin.
2. Добавить fail-fast registry с кардинальностью `0..1` handler на entity type.
3. Подключить entity и aggregate callbacks к существующим transaction boundaries.
4. Мигрировать предметные veto-listeners после behavioural parity.
5. Показать resolved lifecycle и источник handler в Entity Explorer.
6. Зафиксировать feature-package/naming convention и шаблон `<Entity>Lifecycle`.

### Волна 2 — security boundary (`C1-C2`)

Закрывает `ADX-06` и создаёт безопасную основу для generic data API:

1. Построить channel/security matrix.
2. Ввести единый RLS policy descriptor и fail-closed enforcement.
3. Вывести стандартные dimension value sources из metadata/InstanceName.
4. Мигрировать простые `Branch`, `Journal`, `Workshop`, затем сложные документы.
5. Подтвердить read/write/delete parity и privileged bypass audit.

Состояние: пункты 1–5 выполнены в согласованном scope. Для стандартных измерений
read-предикат сверяется с descriptor при старте; для `custom`-измерений
`ReceivingDocument` ожидаемый предикат объявляется через `readCondition` и также
сверяется при старте. Checked duplication зафиксирован как конечное решение C2;
генерация фильтров из descriptor не входит в scope, поэтому `ADX-06` закрыт.

### Волна 3 — fetch, metadata и data defaults (`C3-C4`)

Закрывает `ADX-04`, `ADX-05`, `ADX-07`, `ADX-08`, `ADX-09` и платформенную часть
`ADX-10`.

Детальная последовательность оставшейся части волны зафиксирована в
[`c4-data-access-facade-plan.md`](c4-data-access-facade-plan.md):

1. Ввести scenario FetchPlans и единый InstanceNameResolver.
2. Сделать lookup hydrated согласно plan/declared dependencies.
3. Реализовать secured generic data facade/default CRUD service.
4. Реализовать metadata-driven server-side search и убрать in-memory fallbacks.
5. Добавить резолюцию UI metadata из Bean Validation/JPA/Java type и conflict diagnostics.
6. Убрать обязательность `serviceClass`/magic bean names для standard path.
7. Подключить modular global-search contributors и defaults.

### Волна 4 — поставляемый platform default (`D`)

Не закрывает отдельный запах сам по себе, а доказывает, что исправления не зависят от
случайного component scan монолита:

1. Вынести descriptors/contracts и Spring/Vaadin adapters.
2. Подключать defaults через starter/autoconfiguration.
3. Добавить reference application и TestKit с artifact budgets из раздела 6.
4. Запретить ссылку platform production code на `org.ip`, включая строки и scan roots.

### Волна 5 — form authoring и tooling (`E3`)

Закрывает `ADX-10`, `ADX-11` и предотвращает регрессии:

1. Сократить регистрацию form/search customizations до self-describing contributors.
2. Ввести typed descriptors/keys там, где строка представляет структурный факт.
3. Запретить silent catch/fallback для конфигурационных ошибок.
4. Показывать effective entity kind, defaults, overrides, plans, RLS и handlers в
   Entity Explorer.
5. Добавить scaffolding только для предметных расширений; генератор не создаёт пустые
   repository/service/handler/adapter классы.

## 6. Artifact budgets и gates

### Стандартный справочник

Прикладной минимум:

```text
один entity class extends StandardCatalogEntity
+ предметные поля/правила
```

Budget: `0` обязательных application repository, service, search query, display-name
method и central registration. Явные overrides не считаются нарушением, если содержат
отличающуюся семантику.

### Стандартный документ

Прикладной минимум:

```text
root entity extends StandardDocumentEntity
+ 0..N row entities для реально существующих секций
+ 0..1 <Entity>Lifecycle при наличии предметных lifecycle-правил
```

Budget: `0` save-specific handler/adapter/command/result/use-case, section
repository/service и правок центрального dispatcher. Одна секция проверяет минимальный
сценарий; две — универсальность; отсутствие секций — document semantics без aggregate rows.

### Стандартная RLS-защита

Budget: одна декларация intent/policy на сущность или reusable policy. Ручное повторение
dimension name в filter SQL, write checks и value-source class запрещено для standard case.

### Кастомная форма

Budget относится не к числу строк UI, а к platform wiring: `0` ручных EntityGraph hints,
session reloads, raw `ApplicationContext` lookups и silent configuration fallbacks.

## 7. Definition of Done всего AppDev-first remediation

- все `ADX-*` имеют статус `DONE` с ссылкой на behavioural/architecture tests;
- reference application реализует стандартный catalog и document artifact budgets;
- добавление стандартной entity не требует правки центрального application switch/config;
- security/fetch/transaction defaults применяются автоматически и fail-closed;
- custom extension содержит предметное отличие и диагностируется при конфликте;
- Entity Explorer показывает effective configuration и источник каждого override;
- CI считает запрещённые зависимости/регистрации и не позволяет вернуть boilerplate
  в golden path.
