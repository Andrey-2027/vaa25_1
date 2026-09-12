# Текущий engineering baseline

- Дата проверки: 2026-09-11
- Ветка: `main`
- Baseline commit/tag: не создавался
- Рабочее дерево: содержит пользовательские изменения этапов A0–A4, B3–B4 и C1; baseline commit/tag ещё не создавался

## Статус этапов

| Этап | Статус | Комментарий |
|---|---|---|
| A0 Source of truth | Завершён в объёме A0 | Созданы versioned architecture index, ADR, status и WIP inventory; старый roadmap оставлен архивом. Baseline commit/tag намеренно отложен до общего gate A1–A4 |
| A1 Quality/correctness | Завершён | Исправлены базовые контракты и тесты; полный `verify`, включая production frontend и упаковку JAR, зелёный |
| A2 Reproducible build | Завершён в текущем scope | Manifest, fingerprints, относительный workspace contract и IntelliJ Maven bootstrap проверены на отдельном пустом repository; pre-distribution hardening ведётся отдельными gates |
| A3 Secure configuration | WIP, упрощённый dev-вариант | Локальные DB credentials и абсолютные report paths вынесены во внешний игнорируемый файл; profiles/demo initialization/production validation пока не менялись |
| A4 Schema/PostgreSQL IT | DONE в текущем scope | Переносимый JSON mapping, H2 integration test и fail-fast JDBC selection готовы; PostgreSQL/Flyway вынесены в pre-production gate |
| B Aggregate save/events | B3 DONE в текущем scope: B3.1–B3.11 | Semantic archetypes, role-based numbering, resolved owned-section registry, custom override registry и единый metadata-driven save для пилотов готовы; переходные save-specific классы, section wiring и ручной delete cascade удалены после parity |
| B4 Application lifecycle DX | Core реализован в текущем scope | ADR-0005: `EntityLifecycle<T>`, context contracts, fail-fast registry и callbacks подключены к entity/aggregate save boundaries; контрольные listeners мигрированы, Entity Explorer/scaffolder остаются следующими шагами |
| C RLS enforcement | C1 завершён, C2 готов к старту | Создана карта `DAC-01`–`DAC-21`: CRUD, lookup, sections, search/projection, reports/export, REST, jobs, infrastructure и future integrations; statement guard подтверждён как detection-only |
| D Physical modularity | Не начат | Проект остаётся одним Maven-модулем |

## Quality gate

До изменений A1 последний полный запуск:

```text
Tests run: 973, Failures: 5, Errors: 0, Skipped: 0
BUILD FAILURE
```

Категории пяти failures:

- три устаревших ожидания до case-insensitive `LIKE`;
- один тест старого Vaadin API `getAriaLabel()`;
- один некорректный fallback-сценарий Global Search для уже зарегистрированной entity.

После A1, Maven 3.9.9 из IntelliJ IDEA Community Edition 2025.2.3 и JDK 21.0.11:

```text
Tests run: 978, Failures: 0, Errors: 0, Skipped: 0
Build frontend completed
BUILD SUCCESS
```

Проверенная команда использует один и тот же lifecycle для локального запуска и будущего CI:

```text
mvn verify
```

В текущем окружении команда выполнена встроенным Maven IntelliJ IDEA. Maven Wrapper отложен по решению владельца проекта.

## A2: проверка локальных зависимостей

Соседние исходные проекты описаны в `scripts/local-dependencies.json` относительными путями, точными Maven coordinates и fingerprints build inputs. Скрипт `scripts/bootstrap-local-dependencies.ps1` проверяет IntelliJ Maven 3.9.9, JDK 21, состав исходников и собирает зависимости в явном порядке до запуска application gate.

Проверка 2026-09-11 выполнена против отдельного изначально пустого Maven local repository `.local-maven-repository/a2-isolated-m2-20260911`:

```text
DynamicReports, ReportUI, FilterGrid, crudui, UReport3: BUILD SUCCESS
GitVaa: Tests run: 978, Failures: 0, Errors: 0, Skipped: 0
GitVaa production frontend: completed
GitVaa executable JAR: 192315829 bytes
```

Такой gate исключает случайную зависимость от ранее заполненного `%USERPROFILE%/.m2`. Он пока не заменяет clean-checkout CI: несколько sibling-проектов не имеют зафиксированного Git commit/release, UReport3 содержит локальные изменения, а лицензионное решение по iText 5 остаётся открытым.

По решению владельца проекта A2 закрыт в объёме, достаточном для текущей стадии разработки. CI, публикация внутренних артефактов и лицензионный gate остаются обязательными перед распространением, но не блокируют A3.

## A3: локальная конфигурация

Основной `application.properties` импортирует внешний `config/application-local.properties`. Реальный файл содержит настройки текущего рабочего места, игнорируется Git и не упаковывается в JAR. Для передачи проекта используется `config/application-local.example.properties`: новый разработчик копирует его, заполняет пароль и запускает приложение обычным способом.

После изменения конфигурации повторно выполнен полный `verify` Maven 3.9.9 из IntelliJ IDEA:

```text
Tests run: 978, Failures: 0, Errors: 0, Skipped: 0
Build frontend completed
BUILD SUCCESS
```

## A4: переносимый JSON-контракт и H2 compatibility для `jsonb`

Поле `EntityChangeLogEntity.payload` описано через Hibernate `SqlTypes.JSON`. Конкретный тип колонки выбирает dialect: PostgreSQL использует `jsonb`, H2 — собственный `json`. JDBC writer определяет продукт БД при создании и использует `CAST(? AS jsonb)` для PostgreSQL либо `? FORMAT JSON` для H2; неизвестная СУБД отклоняется fail-fast. Это убирает PostgreSQL-specific DDL из entity и сохраняет исходный JSON без двойного кодирования.

Добавлен integration-test, который проверяет создание таблицы, insert и точное чтение payload. После изменения полный gate:

```text
Tests run: 979, Failures: 0, Errors: 0, Skipped: 0
Build frontend completed
BUILD SUCCESS
```

H2 integration-test не проверяет validation/operators/indexing PostgreSQL JSONB. Это отдельный pre-production PostgreSQL/Testcontainers gate; он не блокирует B и не требует Flyway на текущей стадии.

## B1: стабилизация aggregate save WIP

Добавлены integration-сценарии для `PrdSpec`: rollback при сбое сохранения материалов и операций, отсутствие изменения строк при `ABSENT`, очистка подключённой пустой секции и запрет aggregate save/delete без соответствующих прав `JOURNAL`. В совокупности B1-набор (`PrdSpec`, `ReceivingDocument`, form adapters и section contracts) прошёл:

```text
Tests run: 46, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Эти проверки выполнены на H2. PostgreSQL-подтверждение lifecycle/RLS остаётся отдельным pre-production gate.

## B2: семантика lifecycle events

`EntityEventPublisher` разделяет синхронные veto-capable `Saving`/`Deleting`,
внутритранзакционный `Saved` и after-commit `Changed`/`Deleted`. Для двух последних
событий отсутствие активной транзакции является ошибкой (fail-closed), поэтому
нет ложной немедленной доставки «как будто commit состоялся». Aggregate scope
подавляет дубли root `Saved/Changed` из generic service; владельцем этих событий
остаётся aggregate coordinator — metadata-driven service либо явный custom use case
для нестандартного workflow.

Добавлены проверки:

- ordering: `Saving -> Saved -> (commit) -> Changed`;
- listener veto до persistence;
- rollback без `Changed`;
- успешный commit с доставкой `Changed`;
- отсутствие after-commit fallback без транзакции;
- event contracts не зависят от Vaadin, application domain или field-audit telemetry.

Целевой B2-набор прошёл 30 тестов; полный `mvn verify` после изменений:

```text
Tests run: 989, Failures: 0, Errors: 0, Skipped: 0
Build frontend completed
BUILD SUCCESS
```

Дополнительно устранён blocker production frontend: `@Theme` больше не задаёт одновременно имя custom theme и `Lumo.class`; источником темы остаётся каталог `themes/default`.

## B3: semantic foundation и cleanup завершены в текущем scope

ADR-0003 и roadmap закрепляют developer-experience postulate: стандартный документ
с metadata-declared owned sections не должен требовать отдельных
handler/adapter/command/result/use-case классов. B3 должен заменить текущий domain
type switch и двухфазный fallback единым metadata-driven атомарным save; registry
остаётся SPI для нестандартных overrides. Typed adapters/use cases контрольных
пилотов удалены после behavioural parity и больше не являются шаблоном приложения.

ADR-0004 дополнительно принят как входной контракт B3: `BaseEntity` остаётся
технической основой, `StandardCatalogEntity` и `StandardDocumentEntity` образуют
необязательный opinionated golden path, semantic kind резолвится без дублирования,
а owned sections остаются явно объявляемой независимой capability. Реализация этих
классов и первой части descriptor добавлена: `EntityKind.AUTO` выводится в
`CATALOG`/`DOCUMENT` по mapped superclass либо в `PLAIN`, конфликт explicit kind с
base class отклоняется fail-fast. Прикладные entities и schema не мигрировали.

B3.1 также завершил стандартную нумерацию: `code`/`number` несут роли
`CATALOG_CODE`/`DOCUMENT_NUMBER`, а class-level `@NumberingPolicy` переопределяет
policy унаследованного поля без его повторного объявления. Реестр нумерации теперь
разрешает mapped-superclass fields, а save-hook, admin UI и metadata explorer
используют одну effective `NumberingDefinition`.

Проверка первого среза встроенным Maven IntelliJ IDEA и JDK 21:

```text
Numbering* + semantic metadata + explorer regression gate
Tests run: 54, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Срез B3.1 завершён.

B3.2 усилил `TableSectionMetadataInfo` до resolved owned-section descriptor и добавил
startup `SectionMetadataRegistry`. Registry нашёл три текущие секции и проверяет
root/row symmetry, orphan/duplicate ownership, JPA identity/association contracts,
parent/line field types и service compatibility. Standard persistence mode явно
зафиксирован как `MUTABLE_REPLACE_ALL`.

```text
Section descriptor/registry + composition regression gate
Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

Section/form/current typed-save compatibility gate
Tests run: 45, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Срез B3.2 завершён.

B3.3 добавил `GenericOwnedSectionService` как platform-level persistence contract для
`MUTABLE_REPLACE_ALL` owned sections. Service работает только с resolved descriptor:
сам связывает новые строки с root, проверяет тип/owner, `minRows` и Bean Validation,
назначает 1-based line numbers, загружает строки с metadata-derived fetch graph,
выполняет insert/update/delete в `replaceAll`, а также отдельный `deleteAll`. ID строки
из другой секции/другого root не принимается; detached Hibernate proxy сравнивается по
идентичности без принудительной инициализации. На срезе B3.3 root numbering,
lifecycle events и проверка права на aggregate root намеренно оставались границей
следующего aggregate coordinator; B3.4 эту границу реализует поверх section service.
Прикладные typed services/repositories контрольных секций удалены после behavioural
parity; generic descriptor-bound service является default path.

Проверка встроенным Maven IntelliJ IDEA и JDK 21:

```text
GenericOwnedSectionServiceIT
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Срез B3.3 завершён в текущем scope.

B3.4 добавил `MetadataDrivenAggregateSaveService` — независимую от Vaadin
транзакционную границу aggregate save. Он получает только attached section inputs,
публикует veto-capable `AggregateSavingEvent`, выполняет authoritative section
validation, сохраняет root через обычный `BaseService` (с numbering, Bean Validation,
business hooks и RLS), затем приводит каждую attached section через
`GenericOwnedSectionService.replaceAll` и возвращает persisted rows. `ABSENT` не
передаётся и не изменяется, `ATTACHED + empty` очищается явно; исключение в поздней
секции откатывает header и ранее записанные секции. `MetadataDrivenItemFormSaveAdapter`
снимает эти inputs из фактически подключённых `ItemForm`-таблиц и применяет результат
обратно без повторного reload. В B3.5–B3.10 dispatcher получил registry custom overrides;
typed handlers контрольных пилотов выведены из Spring-контекста, а стандартный путь
использует metadata-driven adapter.

Проверка встроенным Maven IntelliJ IDEA и JDK 21:

```text
MetadataDrivenAggregateSaveServiceIT + GenericOwnedSectionServiceIT
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0

ItemFormSaveDispatcherTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0

BUILD SUCCESS
```

Срез B3.5–B3.11 завершён в текущем scope. `ItemFormSaveHandlerRegistry` подключён как
optional SPI: duplicate declared handlers падают при построении registry, dynamic
ambiguity — до persistence. `ItemFormSaveDispatcher` больше не содержит domain type
switch и сначала разрешает custom override, затем вызывает metadata-driven adapter.
Оба контрольных документа проходят общий default engine; старые
adapters/commands/results/use cases и constructor-only section services/repositories
удалены после parity. Root delete использует generic metadata-driven cleanup, а
предметные listeners сохранены.

Проверка встроенным Maven IntelliJ IDEA и JDK 21:

```text
MetadataDrivenAggregateSaveServiceIT + GenericOwnedSectionServiceIT
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0

ItemFormSaveDispatcherTest + ItemFormSaveHandlerRegistryTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0

BUILD SUCCESS
```

Cleanup B3.11 закрыт отдельным parity-срезом: удалены переходные save-specific классы,
application section services/repositories и ручной delete cascade; добавлены
descriptor-bound section adapter и generic root delete lifecycle.

Проверка встроенным Maven IntelliJ IDEA и JDK 21:

```text
B3 cleanup/parity gate
Tests run: 48, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## B4: единая прикладная точка lifecycle-поведения — решение принято

ADR-0005 фиксирует и реализует public application API `EntityLifecycle<T>`. Для сущности
допускается не более одного optional lifecycle handler с предсказуемым именем
`<Entity>Lifecycle`; он становится каноническим местом для `beforeSave`, `beforeUpdate`,
`beforeAggregateSave`, `beforeDelete` и in-transaction `onSave`. Сложные правила могут
быть вынесены в узкие
rule-классы, но их вызов и порядок остаются обозримыми из lifecycle handler.

Spring lifecycle events остаются внутренним transport/extension mechanism и средством
для decoupled reactions, но перестают быть рекомендуемым прикладным API authoritative
правил конкретной сущности. Стандартная manager-функциональность остаётся в platform
services; обязательные `ObjectModule`, `ManagerModule` и ручной `EntityConfig` не
вводятся. Для новых объектов принимается feature-package convention. Контрольные правила
перенесены в `ReceivingDocumentLifecycle` и `PrdSpecLifecycle`; Entity Explorer,
scaffolder и отдельный guide остаются следующими шагами.

Core B4 реализован: registry подключён к entity/aggregate transaction boundaries,
а контрольные rules мигрированы в typed lifecycle handlers и подтверждены parity tests.

## C1: карта каналов доступа

В `docs/architecture/security-channel-matrix.md` зафиксированы владельцы, фактический
enforcement и privileged bypass policy для 21 канала, включая существующий REST
JPQL-preview endpoint. Главные блокеры C2:

- protected custom `search(...)` может выполнить repository query без явного RLS;
- прямой repository/`EntityManager` остаётся параллельным входом в обход platform API;
- statement guard обнаруживает нарушение, но не блокирует запрос;
- report workers вручную переносят security/request context;
- admin и system bypass не имеют типизированного scope/reason/audit;
- отсутствие authentication сейчас сводится к имени `system`, а должно fail closed.

C1 не изменяет runtime. Первым C2 vertical slice выбраны `Journal` и `PrdSpec` через
CRUD, lookup, custom search, global search и report projection.

После инвентаризации и корректировки order-dependent ожидания в `RlsReadGateTest`
повторно выполнен полный gate Maven 3.9.9 из IntelliJ IDEA и JDK 21.0.11:

```text
Tests run: 992, Failures: 0, Errors: 0, Skipped: 0
Build frontend completed
BUILD SUCCESS
```

В логе по-прежнему видны сообщения `RlsStatementGuard` о потенциально незащищённых
SELECT. Это ожидаемый результат текущего detection-only режима и подтверждение
найденного C1 разрыва, а не успешное fail-closed enforcement.

Living audit `docs/architecture/appdev-first-audit.md` фиксирует 11 групп нарушений
`ADX-01`–`ADX-11`, измеренный application boilerplate baseline, artifact budgets и
обязательную карту закрытия по B3, C2-C4, D и E3. `ADX-01` находится в `WIP`,
`ADX-02` и `ADX-03` закрыты в текущем scope, остальные кодовые `ADX-*` открыты;
документальная волна 0 завершена.

## Неошибочные и блокирующие диагностики

| Диагностика | Категория | Действие |
|---|---|---|
| H2 не реализует PostgreSQL `jsonb` semantics | Ограничение тестового double | Dialect-aware mapping проверяет DDL/JDBC payload contract на H2; validation/operators/indexing проверить PostgreSQL/Testcontainers перед production |
| `RLS: возможная тихая утечка` | Security finding, не ожидаемый шум | C: fail-closed enforcement и channel tests |
| Mockito self-attach warning | Toolchain warning | Учесть в A2/JDK test configuration |
| Deprecated/unchecked compiler warnings | Технический долг | Исправлять адресно, не скрывать общей настройкой |

Warnings не подавляются только ради зелёного лога. Security/schema diagnostics остаются видимыми, пока не закрыты соответствующим этапом.
