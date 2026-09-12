# GitVaa — roadmap развития платформенного ядра

> Статус: рабочий источник приоритетов.  
> Версия: 2.3, 2026-09-11.  
> Основание: обсуждение JMIX vs GitVaa, последующая сверка с текущим checkout и архитектурный аудит проекта.  
> Принцип: JMIX используется как ориентир платформенных контрактов и developer experience, но не как цель функционального копирования.

---

## 0. Как использовать этот документ

Этот файл описывает только:

- принятые архитектурные решения;
- фактический статус текущей реализации;
- будущие этапы и их порядок;
- измеримые условия завершения этапов.

История обсуждения, альтернативные мнения и уже закрытые вопросы не дублируются в roadmap. Для них используются отдельные discussion-документы и ADR.

Статусы:

| Статус | Значение |
|---|---|
| `BASELINE` | Уже является устойчивой частью проекта |
| `DONE` | Подэтап завершён и проверен |
| `WIP` | Присутствует в текущем рабочем дереве, но ещё не считается завершённым |
| `NEXT` | Следующий обязательный этап |
| `LATER` | Выполняется после прохождения предыдущих gates |
| `CONDITIONAL` | Только при подтверждённом бизнес-требовании |

Правило перехода:

> Новый функциональный эпик не начинается, пока обязательный gate предыдущего этапа не выполнен. Уже начатый WIP разрешено довести только до согласованного, тестируемого состояния.

---

## 1. Стратегическая позиция

### 1.1. Цель GitVaa

Основная цель — собственное платформенное ядро для enterprise-приложений:

- list/detail/selection формы из метаданных;
- семантические archetypes сущностей и короткий golden path для стандартных
  справочников и документов;
- варианты форм и контекст открытия;
- табличные секции и типизированные агрегаты;
- metadata-driven атомарное сохранение стандартных агрегатов без обязательных
  save-specific классов в прикладном коде;
- серверный RLS и resource permissions;
- отчёты, поиск, аудит, нумерация и события;
- расширяемые SPI и диагностируемая конфигурация;
- возможность физически отделить платформу от прикладной модели.

ERP-функции производства, склада и планирования являются прикладным полигоном платформы и развиваются после стабилизации соответствующих платформенных контрактов.

### 1.2. Что перенимаем у JMIX

- явные сценарные fetch plans;
- единое представление сущности (`InstanceName`);
- контролируемую data-access boundary;
- атомарную границу сохранения агрегата;
- разделение UI lifecycle, persistence lifecycle и after-commit реакций;
- декларативные действия;
- resource permissions на entity/attribute/action;
- deep-linking;
- сильную диагностику и developer tooling.

### 1.3. Что не копируем

- XML как обязательный источник истины форм;
- полный `DataContext`/identity map для всех экранов;
- in-memory grouping backend вместо существующей server-side lazy-группировки;
- generic REST, автоматически раскрывающий все JPA-сущности;
- unrestricted Groovy runtime;
- BPM, Dynamic Model, multitenancy и SSO без подтверждённой потребности;
- функциональный паритет с экосистемой JMIX как самостоятельную метрику.

### 1.4. Что сохраняем как преимущества GitVaa

- code-first customization;
- `BaseEntity` как техническая основа и opinionated
  `StandardCatalogEntity`/`StandardDocumentEntity` как необязательный golden path;
- strict form variants и `sectionFilter`;
- metadata-driven default `harvest -> transactional save -> apply` для стандартных
  агрегатов и typed override только для нестандартных сценариев;
- серверное дерево групп `DISTINCT + COUNT + lazy children`;
- dimension-based RLS;
- projection/query layer;
- собственный Report Studio при условии дальнейшей консолидации.

### 1.5. Постулат AppDev-first

Короткое имя принципа — **AppDev-first**: прикладной код объявляет предметное
намерение и отличия, а повторяемая platform mechanics остаётся внутри платформы.

> Архитектурное изменение не должно перекладывать платформенную сложность на
> прикладного программиста. Если стандартный сценарий после изменения требует
> новых обязательных классов, ручной регистрации или повторяющейся конфигурации,
> изменение не принимается без предметной либо security-причины, безопасного
> декларативного default и диагностируемого пути расширения.

Следствия постулата:

- стандартная сущность и стандартный документ описываются моделью и метаданными;
  отдельные handler/adapter/command/result/use-case классы для них не обязательны;
- стандартный справочник и документ наследуют общую структуру и defaults; прямое
  наследование от `BaseEntity` остаётся поддерживаемым escape hatch;
- семантический тип сущности не выводится из наличия секций: документ может не иметь
  строк, а секции могут принадлежать нестандартному агрегату;
- convention/default покрывает массовый сценарий, SPI и typed use case являются
  escape hatch для действительно нестандартного поведения;
- correctness, транзакционность и security не ослабляются ради краткости: сложность
  их enforcement принадлежит платформе, а прикладной код объявляет только intent;
- новый обязательный прикладной артефакт должен содержать предметную семантику, а
  не повторять механическую связку UI, service и persistence;
- startup diagnostics обязаны объяснять отсутствующую конфигурацию и способ её
  исправления; тихий fallback запрещён;
- внутренняя сложность платформы допустима только за стабильной API/SPI-границей и
  при наличии behavioural/architecture tests.

### 1.6. Developer-experience gate для всех этапов

| Этап | Проверка с точки зрения прикладного программиста |
|---|---|
| A | Локальная разработка запускается одной документированной командой и примером конфигурации; production hardening не усложняет обычный dev-loop |
| B | Семантический descriptor различает стандартные сущности; документ с нулём, одной или несколькими owned mutable sections не требует save-specific классов; custom handler нужен только для особой семантики |
| C | RLS и fetch defaults применяются централизованно; разработчик не добавляет обязательные predicates вручную в каждый запрос |
| D | Модульность поставляется starter/autoconfiguration и не превращается в ручную регистрацию набора infrastructure beans |
| E | Стандартные CRUD actions/deep links/diagnostics доступны по default; прикладной код описывает отличия |
| F | ERP и integrations добавляют предметные contracts, не заставляя приложение копировать внутреннюю механику платформы |

Каждый этап и PR оценивается одновременно по correctness/security и по количеству
новых обязательных действий, классов и конфигурации в типовом прикладном сценарии.
Полный реестр нарушений `ADX-01`–`ADX-11`, их доказательства, artifact budgets и
карта устранения ведутся в `docs/architecture/appdev-first-audit.md`.

---

## 2. Фактическая исходная точка

### 2.1. Устойчивый baseline (`BASELINE`)

| Область | Состояние |
|---|---|
| Формы | `FormRegistry`, `FormResolver`, list/detail/selection, strict variants, layouts, context/seed filters |
| Табличные секции | `ItemSectionHost`, `TableSectionFactory`, `ItemTable`, несколько persistence patterns |
| Гриды | server-side filter/grouping, saved views, projection/HQL режимы |
| RLS | dimension registry, read/write/UI gates, Hibernate filters, statement detection |
| Метаданные | entity/field/table-section metadata, `ColumnPath`, `FetchGraphs` |
| Отчёты | Report Studio, Jasper/DynamicReports/UReport integrations, query guards |
| Cross-cutting | numbering, telemetry, field audit, global search |
| Архитектурные правила | запрет зависимости `org.ipro` от `org.ip`, правила чистоты form assembly |

### 2.2. Текущий незавершённый срез (`WIP`)

На текущем checkout B1–B3.11 реализованы и проверены: lifecycle event contracts,
metadata-driven aggregate save, registry custom handlers как SPI и предметные
listeners для `ReceivingDocument`/`PrdSpec`. Переходные save-specific
adapters/use cases и application section wiring удалены после behavioural parity.

Незавершёнными направлениями остаются RLS enforcement, PostgreSQL pre-production
gate и физическая модульность. Они не блокируют закрытие B3 в текущем dev-scope,
но требуются для соответствующих production/distribution gates.

### 2.3. Известные блокеры

1. PostgreSQL `jsonb` semantics (operators, indexing и реальная schema validation) ещё не покрыты; это отдельный pre-production gate.
2. Основная схема изменяется через `ddl-auto=update`, миграций нет.
3. Сборка зависит от локальных SNAPSHOT/fork-артефактов.
4. В основном профиле находятся технические credentials и demo users.
5. `RlsStatementGuard` обнаруживает возможный обход, но не является enforcement boundary.
6. Generic save больше не допускает двухфазный путь для metadata-declared mutable sections;
   PostgreSQL lifecycle/RLS confirmation остаётся pre-production gate.
7. Платформа и приложение физически находятся в одном Maven-модуле.
8. Локальный bootstrap fork/SNAPSHOT-зависимостей реализован, но CI-проверка чистого checkout ещё не добавлена.

### 2.4. AppDev-first debt register

Living audit `docs/architecture/appdev-first-audit.md` является обязательным входом
для этапов B–E. Текущие группы долга:

- `B3`: semantic entity model migration remains (`ADX-01`); domain switch/two-phase
  save и пустая section wiring (`ADX-02`–`ADX-03`) закрыты в текущем scope;
- `C2`: дублирование RLS intent между annotations, filter SQL и write checks (`ADX-06`);
- `C3-C4`: repository/service/search ceremony, fetch leakage, metadata duplication,
  InstanceName и global-search defaults (`ADX-04`, `ADX-05`, `ADX-07`–`ADX-10`);
- `D`: доказательство defaults через starter/reference application без скрытой
  зависимости от component scan монолита;
- `E3`: form authoring, typed diagnostics и окончательное закрытие центральных
  registrations (`ADX-10`, `ADX-11`).

Этап не считается завершённым, если относящийся к нему `ADX-*` закрыт только cleanup-ом
без behavioural/architecture test и измеримого уменьшения обязательного application wiring.

---

## 3. Обязательные архитектурные контракты

### 3.1. Семантические типы сущностей и capabilities

`BaseEntity` задаёт только техническую идентичность (`id`, `version`, equality) и не
является полной предметной моделью всех объектов платформы. Резолвнутый entity
descriptor различает как минимум:

```text
PLAIN
CATALOG
DOCUMENT
INFORMATION_REGISTER
```

Для массового сценария платформа предоставляет необязательные opinionated base classes:

```text
StandardCatalogEntity extends BaseEntity
  code + name

StandardDocumentEntity extends BaseEntity
  number + date
```

Правила:

- наследование от стандартного класса является golden path, а не обязательным условием
  semantic kind;
- kind выводится из стандартного базового класса автоматически и не дублируется в
  прикладной metadata;
- нестандартная сущность наследуется прямо от `BaseEntity` и явно описывает intent;
- стандартные поля содержат общую JPA/Bean Validation/UI metadata, но конкретная
  numbering policy и область уникальности задаются для конкретной сущности;
- документ может иметь ноль, одну или несколько секций;
- наличие секций не превращает справочник или нестандартный агрегат в документ;
- owned sections объявляются головной сущностью явно; обратное сканирование всех
  `@ManyToOne`-ссылок запрещено, потому что ссылка не означает ownership;
- секция является независимой capability со своим persistence mode;
- `INFORMATION_REGISTER` резервирует отдельную модель, но dimensions/resources/period/
  recorder semantics реализуются только на соответствующем ERP-этапе;
- выбор поведения выполняется через descriptor и registry/policies, а не через domain
  type switch.

Подробное решение, ограничения миграции и developer-experience contract зафиксированы
в ADR-0004.

### 3.2. Присутствие табличной секции

Состояния операции:

| Состояние | Валидация | Persistence | Результат |
|---|---:|---:|---|
| `ATTACHED + rows` | Да | `replaceAll(rows)` | Строки сохраняются |
| `ATTACHED + empty` | Да | `replaceAll(empty)` | Секция очищается |
| `ABSENT` | Нет | Не вызывается | Существующие строки не меняются |

Фактически подключённые `ItemTable` являются источником истины для экземпляра формы. Скрытая секция не должна создаваться, загружаться, валидироваться или сохраняться.

### 3.3. Атомарное сохранение агрегата

Для стандартного metadata-driven агрегата с owned mutable sections:

```text
UI harvest
  -> platform aggregate request from metadata + attached sections
  -> platform aggregate save boundary owns one transaction
       -> structural validation
       -> aggregate saving policies/listeners
       -> authoritative row/entity validation
       -> save header
       -> save ATTACHED sections
       -> collect persisted result
       -> schedule after-commit event
  -> apply persisted result to the same form
  -> commitSnapshot
```

Правила:

- стандартная сущность без секций использует generic entity save;
- стандартный агрегат, объявленный entity/table-section metadata, использует один
  metadata-driven atomic save без обязательных handler/adapter/command/result/use-case
  классов в прикладном коде;
- custom handler/use case требуется только для явно нестандартной persistence или
  business workflow semantics и имеет приоритет над metadata-driven default;
- отсутствие custom handler при неподдерживаемом режиме секции — ошибка конфигурации
  до первого persistence-вызова;
- generic two-phase путь `save header -> commitTableSections` запрещён;
- write-through и immutable/create-once секции не приводятся насильно к replace-all;
- Vaadin-форма не передаётся в транзакционный application/persistence слой;
- внутренние erased descriptors допустимы внутри платформы, но
  `Map<Class<?>, List<?>>` не становится предметным API прикладного программиста;
- специальный business use case остаётся типизированным и содержит только реальную
  предметную семантику.

### 3.4. Lifecycle events

Разделяются три типа семантики:

| Контур | Назначение | Может отменить операцию |
|---|---|---:|
| UI lifecycle | UX-валидация, dirty/close, отображение ошибок | Да, только UI intent |
| Saving lifecycle | Нормализация и server-side veto до persistence | Да |
| Changed lifecycle | Реакция на уже сохранённое изменение after commit | Нет, основной commit уже состоялся |

Уточнённый порядок aggregate save:

1. Проверить команду, section presence и структурные инварианты.
2. Открыть единый operation/event context.
3. Опубликовать `AggregateSavingEvent`; listener может нормализовать данные или выполнить veto.
4. Проверить итоговое состояние Bean Validation и section validators.
5. Сохранить шапку и подключённые секции.
6. Опубликовать внутри транзакции только события, явно определённые как pre-commit.
7. Доставить `EntityChangedEvent` только после успешного commit.

Гарантии:

- application events не зависят от Vaadin;
- у одной бизнес-операции один владелец root Saved/Changed events;
- rollback не порождает after-commit событие;
- mutation path без транзакции не получает неявную семантику «как будто commit состоялся»;
- внешние side effects выполняются через after-commit/outbox, а не из before-save listener;
- сложные бизнес-сценарии остаются явными use cases, а не скрываются в event bus.

### 3.5. FetchPlan и InstanceName

`FetchPlan` принадлежит сценарию данных, а не UI-классу:

```text
FetchPlan: prd-spec.detail.materials
Form variant: materials-only -> выбирает этот план
```

Минимальные типы планов:

- `list`;
- `detail`;
- `lookup`;
- `row`;
- отдельно `projection/report`, если этот канал допускает entity graph.

`InstanceNameResolver` использует приоритет:

```text
@InstanceName
  -> HasDisplayName
  -> metadata display fields
  -> safe fallback
```

Зависимости сложного instance name объявляются явно и входят в план. Пути валидируются fail-fast, рекурсия ограничивается и защищается от циклов.

### 3.6. RLS enforcement

`RlsStatementGuard` остаётся механизмом обнаружения и телеметрии. Он не считается границей безопасности.

Enforcement должен происходить до выполнения запроса:

```text
request/query intent
  -> identify protected entity/dimensions
  -> resolve effective subject/grants
  -> build mandatory predicate or deny
  -> execute
  -> statement guard verifies expected protection
```

Обязательные каналы:

- entity list/detail;
- lookup/selection;
- table sections;
- global search;
- projection grid;
- JPQL/HQL/SQL reports;
- export;
- background job;
- будущий REST/import.

Для каждого канала должно быть явно выбрано одно:

- автоматический mandatory predicate;
- typed secured service;
- запрет обычным пользователям;
- отдельный privileged system context с аудитом.

UI gate отражает решение пользователю, но не заменяет server-side enforcement.

### 3.7. Физическая модульность

Целевая минимальная структура:

```text
gitvaa-platform-api
  metadata/event/action/data contracts без зависимости от org.ip

gitvaa-platform-spring
  autoconfiguration, persistence adapters, RLS, numbering, telemetry

gitvaa-platform-vaadin
  forms, field factories, coordinators, UI bindings

gitvaa-platform-reports
  optional report/query add-on

gitvaa-application
  org.ip domain, repositories, services, use cases, views
```

Это целевая граница, а не требование создать все модули одним PR. Первый extraction slice должен подтвердить направление на малом наборе контрактов.

---

## 4. Этап A — Engineering Baseline (`WIP`)

Этап A не реализует новую платформенную функцию. Он создаёт воспроизводимую и безопасную точку, от которой можно менять ядро.

| Подэтап | Статус | Проверяемый результат |
|---|---|---|
| A0 | `DONE` | Roadmap/ADR/status версионируются, WIP инвентаризирован; baseline tag ждёт фиксации текущего dev-scope |
| A1 | `DONE` | 978 тестов зелёные, production frontend и JAR собираются через `mvn verify` |
| A2 | `DONE` | Локальный dependency bootstrap и isolated-repository gate приняты как достаточный scope текущей стадии разработки |
| A3 | `WIP` | Локальная конфигурация вынесена из JAR/Git; production profiles и hardening отложены до появления deployment target |
| A4 | `DONE в текущем scope` | Переносимый JSON mapping, H2 integration test и fail-fast выбор JDBC-синтаксиса готовы; PostgreSQL/Flyway вынесены в pre-production gate |

### A0. Source of truth и фиксация состояния

1. Вернуть архитектурные Markdown-документы под контроль Git.
2. Разделить ADR, roadmap, status и discussion.
3. Инвентаризировать текущий WIP и определить его владельца/назначение.
4. Зафиксировать baseline tag/commit после прохождения A1–A4.
5. Не смешивать cleanup с массовым форматированием или изменением кодировок.

### A1. Зелёный quality gate и базовая корректность

1. Исправить или осознанно обновить пять текущих падающих тестов.
2. Исправить `BaseEntity.equals/hashCode` и добавить cross-entity/proxy tests.
3. Исправить противоречие `EntityMetadataInfo.getAllAnnotatedFields()` для hidden fields.
4. Включить обязательный `mvn verify` как единый локальный/CI gate.
5. Разделить warnings, expected security diagnostics и реальные ошибки теста.

### A2. Воспроизводимая сборка и зависимости

Текущий прогресс (2026-09-11):

1. Maven Wrapper отложен по решению владельца; канонический локальный toolchain — Maven 3.9.9 из IntelliJ IDEA и JDK 21.
2. Абсолютные пути устранены из build contract: соседние проекты описаны относительно checkout GitVaa.
3. Добавлены machine-readable manifest, source fingerprints и fail-fast bootstrap в dependency order.
4. Успешно проверена сборка всех локальных артефактов и GitVaa против отдельного изначально пустого Maven repository.
5. Версии локальных зависимостей централизованы в properties приложения; происхождение и текущее состояние fork/source-copy зафиксированы.

Отложенные pre-distribution gates, не блокирующие текущую разработку:

1. Оформить FilterGrid, ReportFlowUI и crudui как нормальные versioned Git/release-артефакты либо публиковать их во внутренний Maven repository.
2. Зафиксировать локальные изменения UReport3 отдельным commit/release.
3. Закрыть лицензионное решение по iText 5/UReport до distributable build.
4. Добавить CI на чистом checkout с получением воспроизводимых внутренних артефактов.

### A3. Конфигурация и эксплуатационная безопасность

Текущий упрощённый вариант (2026-09-11):

- общие настройки остаются в classpath `application.properties`;
- подключения и локальные абсолютные пути читаются из внешнего `config/application-local.properties`;
- реальный локальный файл игнорируется Git и не включается в JAR;
- в репозитории хранится `config/application-local.example.properties`;
- отдельные `dev/test/prod` profiles и production fail-fast вводятся позже, когда появится реальная схема развёртывания.

1. Разделить `dev`, `test`, `prod` profiles.
2. Удалить пароль БД из общего файла конфигурации.
3. Создавать demo users только в dev/test.
4. Явно настроить Open-In-View.
5. Отключить тяжёлую Hibernate statistics в prod по умолчанию.
6. Добавить startup validation обязательных production settings.

### A4. Переносимый JSON-контракт и H2 integration test

Текущий прогресс (2026-09-11): этап закрыт в текущем dev-scope. Поле `EntityChangeLogEntity.payload` описано через Hibernate `SqlTypes.JSON`, поэтому тип колонки выбирает dialect: `jsonb` для PostgreSQL и `json` для H2. Низкоуровневый JDBC writer один раз определяет продукт БД и использует соответствующую привязку параметра: `CAST(? AS jsonb)` для PostgreSQL и `? FORMAT JSON` для H2; неизвестная СУБД отклоняется fail-fast. Integration-test проверяет H2 DDL, реальный путь `EventSink` и точное сохранение payload без двойного JSON-кодирования. Это portability contract, а не эмуляция PostgreSQL JSONB semantics.

### Отдельный pre-production gate для схемы PostgreSQL (не блокирует B)

1. Ввести Flyway baseline для PostgreSQL.
2. Переключить production schema mode с `update` на `validate`.
3. Добавить PostgreSQL/Testcontainers profile для persistence/RLS/audit IT.
4. H2 оставить только для тестов, не зависящих от PostgreSQL semantics, либо поддерживать отдельные совместимые migrations.
5. Проверить создание `entity_change_log`, `jsonb`-полей, operators и индексов реальной СУБД.

Миграции и PostgreSQL integration suite не блокируют B на текущей стадии разработки, но являются обязательным gate перед deployment и любым изменением production-схемы.

### Definition of Done этапа A

- clean checkout собирается одной документированной командой;
- типовой локальный dev-loop не требует production-only инфраструктуры и ручной
  сборки конфигурации из нескольких неочевидных источников;
- `mvn verify` зелёный;
- CI повторяет результат;
- production secrets отсутствуют в репозитории;
- demo initialization не работает в prod;
- production schema управляется миграциями и проверяется через `validate`;
- PostgreSQL integration suite проверяет audit/RLS paths (pre-production gate);
- текущий архитектурный baseline зафиксирован и документы версионируются.

---

## 5. Этап B — завершение aggregate save и events (`NEXT после A`)

### B1. Стабилизировать текущий WIP

Текущий прогресс (2026-09-11): B1 закрыт в текущем dev-scope. `SectionPayload` фиксирует non-null presence/rows, defensive copy списка строк, отсутствие null-элементов и запрет `ABSENT + rows`; integration tests подтверждают atomic save `PrdSpec`, rollback шапки при сбое материалов и операций, неизменность `ABSENT`, очистку `ATTACHED + empty` и RLS write/delete. Финальное подтверждение PostgreSQL остаётся pre-production gate.

- завершить `SectionPayload` API и tests;
- завершить atomic save `PrdSpec` во всех вариантах;
- подтвердить rollback header + attached sections;
- подтвердить, что `ABSENT` section не загружается и не изменяется;
- подтвердить, что `ATTACHED + empty` очищает строки;
- проверить RLS write/delete на новых путях.

### B2. Зафиксировать event semantics

Статус: **DONE в текущем scope** (2026-09-11).

- привести publisher, use cases, комментарии и тесты к контракту раздела 3.4;
- исключить дубли root events между service и aggregate use case;
- запретить ложный after-commit fallback без транзакции;
- отделить field audit от business/integration events;
- добавить тесты listener ordering, veto, rollback и after-commit delivery.

Выполнено: after-commit `Changed/Deleted` теперь требуют активную транзакцию и
никогда не доставляются немедленно вне неё; aggregate scope оставляет root
`Saved/Changed` за aggregate coordinator (metadata-driven service либо явный custom
use case); добавлены unit- и Spring integration-проверки
порядка, veto, rollback и успешного commit. Field audit остаётся отдельным
telemetry-контуром и не входит в event contracts. Финальная проверка PostgreSQL
остаётся pre-production gate.

### B3. Metadata-driven atomic save и registry для overrides

Статус: **DONE в текущем scope** (B3.1–B3.11 завершены 2026-09-11; PostgreSQL
подтверждение остаётся отдельным pre-production gate).

Текущий прогресс: срез `B3.1` завершён. Добавлены `EntityKind`,
`StandardCatalogEntity` (`code`, `name`), `StandardDocumentEntity` (`number`, `date`)
и effective-kind resolution с fail-fast конфликтом explicit kind/base class.
Стандартные `code` и `number` имеют роли `CATALOG_CODE`/`DOCUMENT_NUMBER` и
готовую автоматическую нумерацию; конкретная сущность может одной class-level
`@NumberingPolicy` изменить scope/period/prefix/pattern/manual mode, не переобъявляя
унаследованное поле. Реестр, save-hook, admin UI и metadata explorer используют одну
resolved `NumberingDefinition`; неправильные роли/dateField отклоняются fail-fast.
В B3.2 существующий `TableSectionMetadataInfo` усилен до immutable resolved
owned-section descriptor с owner/row types, parent/line fields, persistence mode и
стабильным ключом. `SectionMetadataRegistry` при старте проверяет обе стороны
`@TableSections`/`@TableSectionMetadata`, отсутствие orphan/duplicate ownership,
JPA/identity contracts, parent association, line-number type и service compatibility.
Для текущего standard path явно зафиксирован `MUTABLE_REPLACE_ALL`; новые режимы не
угадываются. Прикладные сущности и схема не мигрировали. В B3.3 добавлен `GenericOwnedSectionService`: он
принимает resolved descriptor, автоматически связывает строки с root, валидирует
Bean Validation и `minRows`, назначает line numbers, выполняет metadata-derived
fetch, `replaceAll` (insert/update/delete) и `deleteAll`, а также отклоняет строки
чужого owner. B3.1 regression gate: 54 теста; B3.2 startup/composition gate:
16 тестов, расширенный section/save regression gate: 45 тестов; B3.3 generic section
persistence gate: 3 integration-теста.

В B3.4 реализован core metadata-driven aggregate save. `MetadataDrivenAggregateSaveService`
открывает единый transaction/event scope, проверяет только переданные attached-секции,
публикует `AggregateSavingEvent`, выполняет authoritative section validation, сохраняет
шапку через обычный `BaseService` (с numbering и RLS), вызывает generic section
`replaceAll`, собирает persisted rows только для attached-секций и публикует Saved/Changed.
`MetadataDrivenItemFormSaveAdapter` является единственным platform harvest/apply bridge
для `ItemForm`; fallback dispatcher уже может его использовать. Отсутствующая секция не
загружается и не изменяется, пустая attached-секция очищается явно, а сбой поздней
секции откатывает шапку и ранее сохранённые строки. B3.4 gate: 2 aggregate integration-
теста и 4 dispatcher regression-теста.

В B3.5–B3.10 добавлен `ItemFormSaveHandlerRegistry` как optional SPI для явных
нестандартных overrides. Дубликаты объявленного типа отклоняются при построении
registry, а dynamic handlers с несколькими совпадениями — до начала persistence;
порядок Spring beans не является приоритетом. `ItemFormSaveDispatcher` больше не
импортирует domain types и сначала ищет override в registry, затем использует
metadata-driven adapter. `ReceivingDocument` и `PrdSpec` исключены из Spring registry
и проходят один default aggregate lifecycle. Pilot gate дополнен behavioural parity
проверками delete, RLS, listener ordering и telemetry; переходные typed handlers,
adapters и use cases после B3.11 удалены.

Цель B3 — убрать domain type switch и generic two-phase save, не превращая
архитектурную безопасность в обязательный набор классов для каждого документа.
B3 закрывает semantic/default-save часть `ADX-01`/`ADX-02`; cleanup-срез B3.11
закрывает `ADX-03` — section wiring и root delete cascade стали metadata-driven.

До реализации save engine фиксируется минимальный semantic entity descriptor из
ADR-0004. B3 не реализует проведение документов или регистры, но перестаёт считать
все наследники `BaseEntity` семантически одинаковыми. Стандартные каталоги/документы
получают opinionated golden path, а owned sections остаются независимой capability.

Путь по умолчанию:

```text
ItemForm
  -> ItemFormSaveDispatcher
       -> custom handler найден: typed override
       -> custom handler отсутствует:
            entity без attached sections -> generic entity save
            standard owned aggregate     -> metadata-driven atomic save
            unsupported section mode     -> configuration error before persistence
```

Работы:

1. **B3.1 — DONE.** Ввести `EntityKind`/resolved descriptor и стандартные mapped superclasses
   `StandardCatalogEntity` (`code`, `name`) и `StandardDocumentEntity` (`number`,
   `date`) с автоматическим kind inference; сохранить `BaseEntity` как escape hatch.
   Добавить роли и class-level policies стандартной нумерации, включая поддержку
   inherited mapped-superclass fields во всём numbering path.
2. **B3.2 — DONE.** Оставить `@TableSections` явной декларацией ownership на головной сущности и
   добавить startup validation согласованности root/row metadata; не обнаруживать
   секции обратным сканированием JPA-ссылок.
3. **B3.3 — DONE в текущем scope.** Реализовать generic platform persistence/service
   для standard owned section из resolved descriptor, включая validation, parent/line
   linking, replace-all и delete lifecycle; numbering головной сущности остаётся в
   существующем generic save-hook. Constructor-only application section
   service/repository не являются default path и удалены после behavioural parity.
4. **B3.4 — DONE в текущем scope.** Реализовать независимый от Vaadin
   `MetadataDrivenAggregateSaveService` и единый `MetadataDrivenItemFormSaveAdapter`;
   request строится только из attached sections, а атомарная граница включает header,
   generic section persistence и lifecycle events. Dispatcher использует этот adapter
   как default path; custom handlers остаются только как explicit SPI для уникальной
   предметной семантики.
5. **B3.5 — DONE.** Ввести registry специализированных `ItemFormSaveHandler<T>` как SPI
   переопределения, а не обязательную регистрацию каждой entity.
6. **B3.6 — DONE.** Разрешать не более одного custom handler для класса; неоднозначность
   является fail-fast configuration error, порядок Spring beans не задаёт скрытый приоритет.
7. **B3.7 — DONE.** Собирать request только из фактически attached sections; `ABSENT` не загружать
   и не изменять, `ATTACHED + empty` трактовать как явную очистку.
8. **B3.8 — DONE.** Выполнять header save, authoritative section validation, `replaceAll` стандартных
   owned sections, сбор persisted result и lifecycle events в одной транзакции.
9. **B3.9 — DONE.** Сохранять через platform services/policies, а не напрямую через repositories,
   чтобы не обходить RLS, validation, numbering и event contracts.
10. **B3.10 — DONE.** Перевести `ReceivingDocument` и `PrdSpec` на default engine как два контрольных
   агрегата. Оставлять custom use case/handler только там, где после миграции остаётся
   уникальная предметная семантика.
11. **B3.11 — DONE.** После behavioural parity удалить переходные
    adapters/commands/results/use cases, constructor-only section services/repositories
    и ручной root delete cascade. Добавить descriptor-bound section adapter и generic
    delete lifecycle; сохранить custom handler registry и предметные listeners.

Cleanup/parity gate B3.11: 48 targeted unit/integration tests, 0 failures и 0 errors;
проверены aggregate save/delete, RLS, listener ordering, telemetry, hidden sections,
registry ambiguity и startup metadata validation.

Новые режимы секций (`write-through`, `immutable`, `create-once`) не угадываются.
При появлении подтверждённого сценария для них вводится явный metadata contract
либо custom handler; стандартный `replaceAll` fallback для них запрещён.

### Definition of Done этапа B

- `BaseEntity` остаётся техническим base class; стандартные catalog/document base
  classes несут общую структуру, а semantic kind выводится без дублирования metadata;
- секции объявляются владельцем явно и не обнаруживаются по произвольным обратным
  JPA-ссылкам;
- стандартная owned section не требует application repository/service, а её delete
  lifecycle не требует override root service;
- dispatcher не импортирует конкретные domain entity classes;
- тестовый документ без секций проходит standard entity path;
- минимальный тестовый стандартный документ с одной owned table section сохраняется
  атомарно без save-specific handler/adapter/command/result/use-case классов;
- тестовый документ с двумя секциями подтверждает универсальность независимой
  обработки presence, rollback и persisted result каждой секции;
- форма с attached sections не может уйти в generic two-phase save;
- registry выбирает единственный custom override и отклоняет неоднозначность;
- `ReceivingDocument` и `PrdSpec` проходят одинаковый default lifecycle contract;
- любой оставшийся custom handler содержит документ-специфичную семантику, а не
  механическое связывание формы и CRUD services;
- транзакционный aggregate service не зависит от Vaadin;
- rollback и after-commit подтверждены integration tests; финальное подтверждение PostgreSQL остаётся pre-production gate;
- архитектурный тест запрещает Vaadin-зависимости в event contracts.

---

## 6. Этап C — Data Boundary и RLS Enforcement (`ACTIVE`)

### C1. Инвентаризация каналов

Создать security matrix для entity CRUD, lookup, section, search, projection, reports, export, jobs и будущих integrations. Для каждого канала зафиксировать owner, enforcement mechanism и privileged bypass policy.

Исходная карта текущего checkout зафиксирована в
[`docs/architecture/security-channel-matrix.md`](docs/architecture/security-channel-matrix.md).
Она содержит стабильные идентификаторы `DAC-01`–`DAC-21`, реестр существующих
bypass, критические разрывы и обязательный channel-test grid. C1 завершён как
инвентаризация; устранение найденных разрывов начинается в C2.

### C2. Fail-closed RLS

- закрыть `ADX-06`: простой RLS intent объявляется один раз, а read/write/delete
  enforcement выводится из единого policy descriptor;
- защищённый запрос не исполняется без разрешённого security context;
- неизвестное/неполное измерение означает deny, а не warning;
- mandatory predicates выводятся и применяются платформой централизованно:
  прикладной программист не дублирует RLS-условия в каждом repository/query;
- standard dimension value source выводится из metadata/InstanceName; отдельный
  application class требуется только для сложного или внешнего источника;
- privileged bypass является типизированным, минимальным и аудитируемым;
- statement guard подтверждает enforcement и падает в security tests при нарушении;
- прямой repository/EntityManager access из UI запрещён архитектурно.

### C3. FetchPlan + InstanceName pilot

Пилот:

- `ReceivingDocument`;
- `PrdSpec`;
- `PrdSpecMtr`;
- `PrdSpecOper`;
- lookup-сущности, необходимые их instance names.

Проверки:

- стандартные list/detail/lookup/row планы выводятся из метаданных; явный plan
  требуется только для отличающегося сценария;
- invalid path обнаруживается при регистрации;
- detached render не вызывает lazy loading;
- lookup/value-change сценарий не требует от custom form ручного reload,
  `EntityGraph` hints или знания о Hibernate session (`ADX-07`);
- hidden section не вызывает row query;
- планы разных сценариев не смешиваются;
- пилотные сущности используют один источник instance name для lookup/search/audit,
  без параллельных `toString`/display-field policies (`ADX-09`);
- измеряются query count и объём графа.

### C4. Узкий data-access facade

Фасад унифицирует только:

- load by id;
- paged/list load;
- lookup load;
- fetch-plan resolution;
- обязательный read enforcement;
- telemetry context.

Для standard entity фасад также предоставляет metadata-driven CRUD и server-side
search defaults. Он закрывает `ADX-04`, `ADX-05`, `ADX-08` и платформенную часть
`ADX-10`:

- application repository/service не обязательны, если в них нет предметной семантики;
- `serviceClass` и bean name `<entity>Service` не являются golden-path contract;
- required/type/reference defaults выводятся из Bean Validation/JPA/Java type, явная
  UI metadata описывает только override;
- противоречия UI/server/DB metadata диагностируются на старте;
- стандартный search не требует repository query/service override и не загружает
  `findAll()` для фильтрации в памяти;
- global-search contributors являются модульными, а search/display fields по умолчанию
  выводятся из effective metadata и InstanceName.

Он не сохраняет произвольные агрегаты и не заменяет typed application use cases. Security/fetch/telemetry оформляются как отдельные policies/decorators, чтобы не создать новый god object.

Прикладной API фасада выражает intent (`list`, `detail`, `lookup`, plan key), а не
требует вручную собирать security predicates, EntityGraph hints и telemetry context.

### C5. Resource permissions

После fail-closed row enforcement добавить:

- entity CRUD permissions;
- attribute read/write permissions;
- action permissions;
- эффективное решение и `blockReason` для UI;
- административную диагностику эффективных прав.

Для стандартных CRUD-операций действуют безопасные platform defaults; приложение
декларирует только отличия и предметные permissions.

### Definition of Done этапа C

- каждый data channel присутствует в security matrix;
- protected entity нельзя прочитать через неподдержанный канал молча;
- report/projection/export tests подтверждают отсутствие обхода;
- формы пилотных сущностей работают на явных планах;
- standard catalog работает без application repository/service/search override;
- custom form не содержит session reload/EntityGraph wiring для lookup values;
- простая RLS policy не повторяется в annotation, SQL filter и write-check method;
- metadata conflicts обнаруживаются startup validation;
- UI не зависит напрямую от repository;
- типовой secured query не содержит прикладного boilerplate для RLS/fetch/telemetry;
- security bypasses перечислены, типизированы и аудитируются.

---

## 7. Этап D — физическая платформизация (`LATER, обязательный`)

### D1. Карта зависимостей и публичного API

- классифицировать `org.ipro` классы как API, SPI или internal;
- устранить строковые зависимости платформы на `org.ip`;
- зафиксировать допустимые направления зависимостей;
- определить compatibility policy.

### D2. Первый extraction slice

Сначала физически выделить небольшой стабильный слой contracts/API и подключить его обратно к приложению. Не переносить сразу формы, отчёты и persistence целиком.

Extraction не должен увеличивать число обязательных registrations/configuration в
application: существующие defaults подключаются через starter/autoconfiguration.

Кандидаты первого slice:

- metadata value types/annotations;
- event contracts;
- form/action extension interfaces;
- нейтральные identifiers/results.

### D3. Spring и Vaadin adapters

После подтверждения API:

- выделить Spring/autoconfiguration module;
- выделить Vaadin form module;
- вынести report subsystem в optional add-on;
- оставить прикладные entities/use cases/views только в application module.

Прикладное приложение подключает starter, а не собирает вручную инфраструктурный
граф из platform beans.

### D4. Reference application и TestKit

- минимальное приложение без ERP-домена;
- test entity с list/detail/selection;
- standard catalog sample в пределах AppDev-first artifact budget: entity + предметные
  поля, без application repository/service/search/display wiring;
- RLS sample;
- aggregate sample;
- sample стандартного документа без save-specific обвязки;
- samples документа без секций, с одной и с двумя owned sections;
- совместимость starter/autoconfiguration;
- upgrade/compatibility tests.

### Definition of Done этапа D

- platform artifacts собираются независимо от `org.ip`;
- reference application подключает опубликованные platform artifacts как зависимости;
- production platform code не содержит ссылок на application packages, включая строковые scan/pointcut contracts;
- публичные SPI перечислены и протестированы;
- reference application подтверждает минимальный setup без ручной регистрации
  стандартных platform services;
- reports подключаются опционально;
- версия платформы может изменяться независимо от версии приложения.

---

## 8. Этап E — UI platform experience (`LATER`)

### E1. Декларативные действия

Эволюционно расширить `ListCommand` до общего action contract:

```text
id, title, icon, variant,
visibleWhen, enabledWhen, blockReason,
execute(ActionContext)
```

Первыми перевести create/edit/remove/refresh/save/close/copy/report/export для пилотных сущностей. Server-side permission остаётся обязательным независимо от состояния кнопки.

Стандартный CRUD-набор создаётся платформой автоматически; прикладной код объявляет
новые действия либо переопределяет только отличающиеся свойства/условия.

### E2. Deep-linking

- стабильный route по entity/id/variant;
- not found/forbidden handling;
- RLS при прямом URL;
- dirty-close contract;
- совместимость dialog/workspace/navigation.

### E3. Диагностика и tooling

- Entity Explorer показывает формы, варианты, plans, actions, RLS dimensions и handlers;
- Entity Explorer показывает effective entity kind, inherited defaults, source каждого
  override и открытые `ADX-*` diagnostics;
- startup validation собирает ошибки конфигурации;
- шаблон добавления новой сущности;
- шаблон стандартного документа не генерирует пустые handler/adapter/use-case классы;
- form/search customizations являются self-describing contributors и не требуют
  wrapper-регистратора либо правки центрального application config;
- structural field/variant/parameter errors не подавляются silent fallback;
- custom form не получает infrastructure через raw `ApplicationContext`;
- machine-readable registry для будущего designer/tooling.

---

## 9. Этап F — интеграции и ERP (`CONDITIONAL`)

### Интеграции

Начинать с конкретных versioned DTO contracts:

- OAuth2/OIDC;
- idempotency;
- correlation id;
- transactional outbox;
- FileStorage;
- notifications/email;
- persistent jobs;
- индексирование поверх существующего Global Search.

Не открывать generic entity REST до завершения этапа C.

### ERP

Следующие platform-driving сценарии:

- документ и его проведение;
- движения и регистры;
- остатки и резервирование;
- складские измерения RLS;
- потребности производства;
- ресурсы, календари и мощности;
- traceability и costing.

Каждый ERP-сценарий сначала формулирует недостающий платформенный контракт, затем реализует предметную функцию. ERP-класс не добавляется в platform module.

Создание стандартного ERP-документа опирается на metadata-driven aggregate save.
Прикладные классы добавляются для проведения, движений, расчётов и иных предметных
правил, но не для повторения стандартной транзакционной механики формы.

---

## 10. Reports policy

Ближайшая цель — не функциональный паритет с JMIX Reports, а консолидация:

1. определить основной authoring/execution path;
2. зафиксировать роль UDR, UReport и JRXML;
3. закрыть лицензирование и воспроизводимость зависимостей;
4. обеспечить RLS/resource permissions всех dataset channels;
5. добавить историю запусков, retention и аудит;
6. только затем добавлять cross-tab, multi-dataset, spreadsheet editing и REST run.

Основной dataset path должен применять security/telemetry defaults централизованно;
создание типового отчёта не требует прикладного копирования этих policies.

Новая крупная возможность отчётов не должна опережать этапы A–C.

---

## 11. Порядок PR

```text
A-0  Versioned docs + ADR/status split
A-1  Green test baseline + BaseEntity/metadata correctness
A-2  Reproducible internal dependencies + source identity + license decision + CI
A-3  dev/test/prod profiles + secrets/demo initialization
A-4  Portable JSON mapping + H2 contract (DONE в текущем scope)
A-4-PREPROD  Flyway baseline + PostgreSQL integration profile

B-0  Stabilize SectionPayload + current events WIP
B-1  Stabilize PrdSpec atomic save and rollback tests
B-2  Event lifecycle semantics and after-commit tests (DONE в текущем scope)
B-3  Semantic entity archetypes + metadata-driven atomic aggregate save + custom handler registry

C-0  Security channel matrix
C-1  Fail-closed RLS enforcement + bypass policy
C-2  InstanceName + FetchPlan pilot
C-3  Narrow data-access facade
C-4  Resource permissions

D-0  API/SPI/internal dependency map
D-1  Platform API extraction slice
D-2  Spring/Vaadin module extraction
D-3  Optional reports add-on
D-4  Reference application + compatibility TestKit

E-*  Actions, deep links, diagnostics
F-*  Integration and ERP capabilities by demand
```

Правила мержа:

- один PR закрывает один проверяемый риск;
- сначала behavioural/architecture test, затем переключение пути;
- каждый PR указывает AppDev-first impact: какие обязательные классы, настройки или действия
  он добавляет/удаляет в стандартном прикладном сценарии;
- обязательный прикладной boilerplate не добавляется без предметной/security-причины
  и безопасного platform default;
- legacy fallback имеет owner, telemetry и дату удаления;
- новый privileged bypass требует security test и ADR;
- массовые перемещения пакетов не смешиваются с изменением поведения;
- schema changes не мержатся без migration;
- следующий этап не объявляется начатым, пока DoD предыдущего не проверен.

---

## 12. Ближайшее решение

Текущий приоритет:

1. выполнить этап A и получить воспроизводимый зелёный baseline;
2. завершить этап B metadata-driven atomic save, не закрепляя переходную
   save-specific обвязку как обязанность прикладного программиста;
3. до новых внешних API и ERP-документов реализовать этап C;
4. после стабилизации контрактов выполнить физическое выделение этапа D.

RLS enforcement и физическая модульность не являются подпунктами Engineering Baseline:

- этап A делает их безопасно выполнимыми;
- этап C создаёт обязательную data/security boundary;
- этап D превращает логическую платформу в физически поставляемые модули.
