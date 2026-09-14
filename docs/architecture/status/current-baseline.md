# Текущий engineering baseline

- Дата общего baseline: 2026-09-13; адресная проверка C4.3/C4.4: 2026-09-14
- Ветка: `main`
- Baseline commit/tag: не создавался
- Рабочее дерево: содержит пользовательские изменения этапов A0–A4, B3–B4 и C1; baseline commit/tag ещё не создавался

## Статус этапов

| Этап | Статус | Комментарий |
|---|---|---|
| A0 Source of truth | Завершён в объёме A0 | Созданы versioned architecture index, ADR, status и WIP inventory; старый roadmap оставлен архивом. Baseline commit/tag намеренно отложен до общего gate A1–A4 |
| A1 Quality/correctness | Завершён | Исправлены базовые контракты и тесты; полный `verify`, включая production frontend и упаковку JAR, зелёный |
| A2 Reproducible build | Завершён в текущем scope | Manifest, fingerprints, относительный workspace contract и IntelliJ Maven bootstrap проверены на отдельном пустом repository; pre-distribution hardening ведётся отдельными gates |
| A3 Secure configuration | WIP, упрощённый dev-вариант | Локальные DB credentials и абсолютные report paths вынесены во внешний игнорируемый файл; demo initializer ограничен профилями `dev`/`demo`/`test` и opt-in property (`DAC-19`); более широкая production validation остаётся открытой |
| A4 Schema/PostgreSQL IT | DONE в текущем scope | Переносимый JSON mapping, H2 integration test и fail-fast JDBC selection готовы; PostgreSQL/Flyway вынесены в pre-production gate |
| B Aggregate save/events | B3 DONE в текущем scope: B3.1–B3.11 | Semantic archetypes, role-based numbering, resolved owned-section registry, custom override registry и единый metadata-driven save для пилотов готовы; переходные save-specific классы, section wiring и ручной delete cascade удалены после parity |
| B4 Application lifecycle DX | Core реализован в текущем scope | ADR-0005: `EntityLifecycle<T>`, context contracts, fail-fast registry и callbacks подключены к entity/aggregate save boundaries; контрольные listeners мигрированы, Entity Explorer/scaffolder остаются следующими шагами |
| C3 FetchPlan + InstanceName | C3.0–C3.7 реализованы; адресные проверки C3.7 выполняются | Периметр C3.0; `@InstanceName` + резолвер (пилот `ReceivingDocument`, `Nomenclature`); `FetchPlanRegistry` со сценариями `LIST`/`DETAIL`/`LOOKUP`/`ROW` и декларацией `@Lookup(fetch)`; read-path выбирает сценарий внутри сервиса, явные пути расширяют план; `ItemTable` использует один section reload через `ROW`; `DETAIL`/`LIST` независимы, планы детерминированы. C3.7 включает раннюю write-авторизацию и единый lifecycle-managed `InstanceNameProvider`. Широкое распространение `@InstanceName` на остальные сущности остаётся отдельной миграцией |
| C RLS enforcement | C1 завершён; реализация C2 согласованного scope выполнена, широкая проверка ещё не зелёная | `DAC-13`, `DAC-17` и `DAC-19` закрыты; checked duplication закреплён как конечное решение; detection-only production guard принят и вынесен в `A4-PREPROD-RLS-GUARD`; целевой набор C2 (53 теста) и random-order gate (156 тестов) зелёные. Полный random `verify` пока даёт ошибки жизненного цикла Spring test context в `AttributeValueServiceTest`/`AttributeTypeServiceTest`; `VisualQuerySubqueryIT` исключён по решению владельца и разбирается отдельно |
| C4 Data-access facade | C4.0–C4.2 закрыты; C4.3 core реализовано, формальное закрытие pending; C4.4–C4.5 закрыты | Таксономия всех 37 persistence types, классификация service/repository/base слоя, baseline и пилоты зафиксированы в `c4-inventory.md`; решения — в `ADR-0007`. C4.1: единый canonical read executor, descriptor/capability catalog и правило `plan ∪ extras -> deepen once`; `AbstractBaseService`, `LookupService` и global search используют одну границу; row cancel без per-reference reads. C4.1 hardening: capability enforcement fail-closed, LOOKUP для lookup по id, SQL-bound lookup, paging count parity, `deepen once`. C4.2: tri-state `RequiredMode`, вывод `type`/`reference`/server-nullability/UI-required с `FactOrigin`, eager startup-валидация и `MetadataAllowance`; пилотная зачистка дублей сохранила effective values без diff. C4.2 hardening: snapshot-ресурсы в поставке, таблица совместимости Java-типа и `FieldType`, вывод server-required из JPA, дедупликация типов, негативный startup-тест и whitelist warning-кодов; global search получил capability-грань источника. C4.3 core: `EntityDataAccess`, единый `CanonicalWriteExecutor`, type-directed resolver, generic `CanonicalEntityService` и исполняемые запреты; `ValidatedJpaCrudService` ограничен internal-store. C4.3 hardening: intent задаёт точную JPA-операцию; UPDATE требует существующую доступную исходную строку и авторизует исходное и целевое состояния до валидации/hooks; `GridFormView` canonical handle ограничен `CREATE`. Полный порядок pipeline и ранний отказ покрыты тестами. Формальное закрытие C4.3 ожидает write-telemetry, первый production-каталог на canonical write path и form → write → audit acceptance. C4.4: canonical search builder и согласованные overloads на всех 16 стандартных корнях, literal escaping, deterministic ordering и bounded paging. C4.5: модульное участие через `@GlobalSearchable`, canonical secured query без count, удаление central config и Spring wiring; целевые тесты зелёные. C4.6 (в работе): заборы миграции и aggregate-boundary guard, волна A (`Branch`/`Journal`/`Oper`/`Role`), волна B (`GroupNom`/`Nomenclature`/`ReceivingDocument`), волна C (`Workshop`/`UnitOfMeasurement` + снятый дубль `GridFormView`, `BaseService.sum` на canonical-поверхности). ADR-0008/ADX-13: интернированные сущности получили один механизм `NaturalKeyCreateSupport` вместо двух копий retry. C4.7–C4.8 впереди |
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

## C2: fail-closed RLS (core реализован)

Строки `DAC-01`–`DAC-21` в
[`security-channel-matrix.md`](../security-channel-matrix.md) переоценены по коду,
а не по прежнему состоянию C1. Что закрыто:

- RLS intent объявляется один раз (`@RlsDimension` → `RlsPolicyDescriptor`), из него
  выводятся write/delete-проверки, а ожидаемый read-предикат вычисляется и сверяется
  с `@Filter(condition = ...)` при старте (расхождение — fail-fast);
- неизвестное/неполное измерение и незарегистрированный annotated класс дают отказ,
  а не тихое «unprotected»; отсутствие грантов даёт sentinel, а не «без ограничений»;
- субъект без authentication не является authority: `requireAuthenticatedUsername`,
  read-гейт и артефакт-кэш отказывают для пустого субъекта и `system`, untyped
  `runAsSystem/callAsSystem` удалены;
- обязательная граница стоит перед Spring Data repository (native query, `flush()`,
  bulk и мутация только по id запрещены), а flush-time guard требует capability
  на каждый INSERT/UPDATE/DELETE, включая implicit dirty-checking на commit;
- bypass типизирован, ограничен списком reason'ов, обязательным актором, audit и
  ограничением самого SQL обхода; источник значений измерения выводится из metadata
  (`grantValues`), дублирующие application-классы удалены;
- архитектурный запрет repository/`EntityManager` в `org.ip.views..` и запрет
  row-repository для owned-секций.

Gate C2 зафиксирован в матрице: целевой список тестов каналов, архитектурные правила
и полный `verify`. Историческое подтверждение (`target/full-verify-c2d.log`; предыдущее —
`target/full-verify-c2b.log`, `1030, 0, 0`; базовое — `target/full-verify3.log`,
`1010, 0, 0` на коммитах `8cd77b6` + `ec7bafa`):

```text
Tests run: 1040, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Повторная проверка после текущих исправлений (2026-09-13, Maven IntelliJ IDEA, JDK 21):
целевой набор C2 — `53/0/0`, random-order C2 gate — `156/0/0`, seed `904469758400`.
Расширенный random `verify`, исключая отдельно отложенный `VisualQuerySubqueryIT`,
выполнил 1079 тестов, но завершился с ошибками создания JPA auditing bean: в первом
прогоне — 23 ошибки в `AttributeValueServiceTest`, при `spring.test.context.cache.maxSize=128`
— 3 ошибки в `AttributeTypeServiceTest`; все с причиной `GenericWebApplicationContext`
уже закрыт. Один прогон с лимитом 256 завершился зелёным (seed `2828632607900`), но
повтор с тем же лимитом в Surefire завершился 22 такими ошибками в
`ReportQueryGuardTest`, `VisualQueryGuardIT`, `VisualQueryPackageDeepPathIT` и
`ReportExecutionIT` (seed `3078094256100`). Изолированный `AttributeTypeServiceTest`
проходит (`3/3`), как и связанные парные прогоны. Значит, увеличение кэша не является
стабильным исправлением. C2-specific gate подтверждён, но широкий random `verify`
остаётся незелёным/неповторяемым; проблему жизненного цикла тестовых контекстов нельзя
считать исправленной или скрывать исключением этих классов.

Пробелы подтверждения первой ревизии закрыты поведенческими тестами:
`RlsRepositoryEnforcementIT` (+6 запретов: native query, `flush`, bulk, `@Modifying`,
мутация только по id, row-repository секции), `RlsUnauthenticatedAccessIT` (сервис,
lookup, прямая граница repository, субъект `system` — все отказом) и
`RlsStatementGuardTest.staleSessionMarkOnReusedThreadIsClearedAtRequestBoundary`
(граница запроса снова вооружает канарейку).

Закрыты два канала из первой ревизии. `DAC-11`: `JrxmlRunDialog` переведён с raw
`Thread` на управляемый исполнитель (`ReportTaskExecutor` через
`ReportExecutionService.executeAsync`), снимок субъекта и очистка ThreadLocal — теперь
гарантия платформы; архитектурное правило запрещает `new Thread` в `org.ip..`.
`DAC-08`: спайк `org.ip.groupgrid` (две панели с raw `EntityManager`) перенесён в
test-исходники, а правило `UiPersistenceBoundaryTest` расширено на этот пакет.
Невакуумность обоих правил проверена мутацией.

Закрыт последний семантический пробел ADX-06 — read/write parity сложной политики.
У `custom`-измерения read-предикат вывести нечем (подзапросы и конъюнкция путей),
поэтому он объявляется явно: `@RlsDimension(value = "BRANCH", custom = true,
readCondition = ...)` с условием, взятым из одной константы с `@Filter`. Реестр сверяет
объявленное с фактическим фильтром при старте и отказывает при пропущенном
`readCondition` или расхождении. Контракт симметричен: объявленный предикат без фильтра
(CHECK_ONLY) и объявленный предикат на стандартном измерении (его предикат выводится из
`valuePaths`, поэтому объявление было бы молча проигнорировано) — тоже отказ старта. Гарантия закреплена тестами: негативные фикстуры
`rlsparity.*` в `RlsCustomDimensionParityTest` показывают, что проверка не вакуумна,
а `RlsIntegrationTest.receivingDocumentReadVisibilityMatchesWriteGuardPerRow` сверяет
видимый набор строк с набором, проходящим write-guard, на одних и тех же грантах.
Что проверка НЕ доказывает: эквивалентность произвольного SQL и `getRlsChecks()` —
это остаётся свойством конкретной политики, подтверждаемым поведенческим тестом.

В согласованном scope закрыты `DAC-13`, `DAC-17` и `DAC-19`; checked duplication —
конечное решение C2. Production guard остаётся detection-only по решению и вынесен в
`A4-PREPROD-RLS-GUARD`; `DAC-15` resource permissions остаётся в C5. Для завершения
проверки C2 остаётся получить зелёный широкий `verify` на текущем дереве: C2-specific
random gate проходит, но общий random `verify` пока нестабилен из-за уже закрытого
Spring-контекста в двух независимых `DataJpaTest` классах. `VisualQuerySubqueryIT`
сознательно исключён по отдельному решению владельца.

Найденный при проверке пробелов дефект канала закрыт: автономный справочник строки
секции (`NomAttributeValueService` → `NomAttributeValueRepository`) падал на закрытом
row-repository. Строка owned-секции больше не является самостоятельным справочником —
узел подсистемы и `serviceClass` убраны, мёртвые service/repository удалены, а
`ServiceLocator` отказывает такой строке с настоящей причиной. Инвариант закреплён
тестами (`SectionParentColumnIsolationTest.everySectionRowIsNotAnAutonomousCatalog`,
`ServiceLocatorSectionRowTest`).

## C3: FetchPlan + InstanceName (инкремент C3.0 — периметр)

Решение принято в ADR-0006: FetchPlan и InstanceName — два специализированных
компонента, `ManagedEntityCatalog` — единственный источник набора управляемых
сущностей, публичные контракты отделяются от internal registry с первого среза,
явный override имеет приоритет над metadata-derived default, invalid fetch path —
startup error. Реализация начата с расчистки периметра (C3.0).

Сделано в текущем scope:

- **C3.0.1.** Удалён доказанный повторный RLS write/delete enforcement:
  `AbstractBaseService.save/create/update/delete` больше не вызывают
  `checkRlsWrite`/`checkRlsDelete`; проверка и выдача one-shot capability остаются
  за `RlsRepositoryEnforcementAspect`, а flush-time guard — за
  `RlsWriteEnforcementListener`. Удалён мёртвый код (`checkRls`, `emitRlsDenied`,
  `RlsPermissionCheck`, поле `AccessService`), обновлены устаревшие ссылки в
  комментариях и упрощены два юнит-теста, инжектившие `accessService` только из-за
  удалённого метода.
- **C3.0.2.** Добавлена отдельная автоконфигурация
  `org.ipro.fetch.config.FetchPlanInstanceNameAutoConfiguration` (зарегистрирована в
  `AutoConfiguration.imports`); `MetadataAutoConfiguration` не изменялась.
- **C3.0.3.** `ManagedEntityCatalog` получил read-only `managedEntityClasses()` —
  представление уже построенного metamodel-снимка; общий internal-источник набора
  сущностей `org.ipro.fetch.ManagedEntityTypes` для будущих registry C3. Новый
  classpath scan не добавлен, что закреплено правилом ArchUnit «metadata core не
  зависит от `org.ipro.fetch`».

Проверка выполнена встроенным Maven IntelliJ IDEA 2025.2.3 (JDK 21.0.11,
offline, `-Dmaven.repo.local=.local-maven-repository/distribution-m2`; `mvn` в PATH
отсутствует, поэтому использован `mvn` встроенного Maven с `JAVA_HOME` на JDK 21):

```text
Целевой RLS набор (RlsServiceWriteBoundaryIT + C2 gate классы)
Tests run: 60, Failures: 0, Errors: 0, Skipped: 0

C3.0 контекст/границы + затронутые юнит-тесты
(RlsAutoConfigurationSmokeTest, FetchPlanInstanceNameAutoConfigurationTest,
 ManagedEntityCatalogTest, PlatformArchitectureTest, GroupNomServiceVersionTest,
 AttributeType/AttributeValue/SklNomOpa/UreportTemplate service tests)
Tests run: 34 + 72, Failures: 0, Errors: 0, Skipped: 0

Aggregate/section набор
Tests run: 35, Failures: 0, Errors: 0, Skipped: 0

Random-order gate целевого набора (12 классов, seed по умолчанию)
Tests run: 87, Failures: 0, Errors: 0, Skipped: 0

Полный тестовый набор текущего дерева (default order, фаза test, после C3.1–C3.2)
Tests run: 1107, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Полный набор выполнен в фазе `test` (без production frontend и упаковки JAR) и в
default order. Random-order широкий `verify` не запускался: его нестабильность
(порядко-зависимый жизненный цикл Spring test context, см. раздел C2) остаётся
pre-existing дефектом и не маскируется. На момент среза C3.0 `ADX-07` и `ADX-09`
оставались `OPEN`; их фактическое закрытие — в разделе C3.3–C3.5 ниже.

> Обновление (C3.7): эта порядко-зависимость диагностирована и закрыта. Причина —
> JVM-глобальный AspectJ-аспект аудита, который в slice-контекстах настраивал
> `AuditingEntityListener` из чужого контекста; разбор и лечение — в разделе
> «C3.7 hardening» ниже.

### C3.1–C3.2: контракт InstanceName и резолвер с пилотом

Сделано в текущем scope:

- введён публичный контракт `@InstanceName` (C3.1): явные attribute paths либо вывод
  состава из существующей metadata (`@EntityMetadata.displaySortFields`, документирован
  как SQL-эквивалент `getDisplayName()`), с разделителем и startup-валидацией;
- реализован `InstanceNameResolver` (C3.2) в отдельной границе C3: при построении
  бина резолвер валидирует объявленные paths и падает при неизвестном пути, пустом
  `displaySortFields` или отсутствии `@EntityMetadata`. Резолвер не обращается к
  repository и не инициирует lazy load: неинициализированный прокси представляется
  ссылкой `Class#id` (идентификатор читается через `HibernateProxy` lazy initializer);
- приоритет резолюции: объявленный `@InstanceName` → `HasDisplayName` (только для
  немигрированных и инициализированных сущностей) → безопасный fallback. Миграция
  opt-in: без декларации представление сущности не меняется;
- пилот: `ReceivingDocument` (явные paths `number`+`date`, раньше зависел от `toString()`)
  и `Nomenclature` (metadata-derived `code`+`name`);
- потребители lookup/search/audit/admin переведены на единый источник для мигрированных
  сущностей: `FieldRenderer.entityReference`, `EntitySnapshot.displayNameOf`,
  `RlsDimensionValueCatalog`, `JpaGlobalSearchProvider` (резолвер передаётся через
  `GlobalSearchProviderRegistry`/`GlobalSearchAutoConfiguration` как optional).

Проверка C3.1–C3.2 (встроенный Maven IntelliJ IDEA, offline):

```text
InstanceName unit + pilot + C3 config
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0

Search regression набор
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0

Random-order gate (12 классов, seed по умолчанию)
Tests run: 52, Failures: 0, Errors: 0, Skipped: 0
```

Не завершено в C3.2: FetchPlan registry и сценарные планы (C3.3), интеграция в
read-границу (C3.4), миграция `PrdSpecMtrFormCustomization` (C3.5) и измерения (C3.6).
Поэтому на момент этого среза `ADX-09` оставался `WIP`, а `ADX-07` — `OPEN`.

### C3.3–C3.5: FetchPlan registry, сценарный LOOKUP и закрытие ADX-07/ADX-09

Сделано в текущем scope:

- **C3.1 (расширение).** `InstanceNameResolver` отдаёт не только имя, но и состав
  (`instanceNamePaths`) и зависимости имени (`instanceNameFetchPaths`), а
  `InstanceNameBridge` — единую лестницу `definition → HasDisplayName → toString`
  (`displayName`), которой обязаны пользоваться каналы отображения;
- **C3.3.** Введён `FetchPlanRegistry` с ключом `(entityClass, scenario)` для `LIST`,
  `DETAIL`, `LOOKUP` и `ROW`. Базовый набор путей выводится из эффективной metadata
  (grid/form-поля, колонки выбора, поля строки секции), к нему добавляются зависимости
  имени (`LOOKUP`, `DETAIL`) и объявленные зависимости выбора (`LOOKUP`), после чего
  граф углубляется через единое имя ссылочных целей (`FetchGraphs.deepen`). Планы
  кэшируются и не смешиваются; порядок путей детерминирован; причина каждого пути
  доступна через `FetchPlan.reasonFor`;
- **декларация зависимости выбора.** `@Lookup(fetch = {...})` объявляет attribute paths
  цели, которые нужны месту использования сразу после выбора значения. Пути
  валидируются при построении registry — неизвестный путь завершает старт приложения;
- **C3.4.** Сценарий `LOOKUP` подключён к существующей read-границе:
  `LookupService.search(..., fetchPaths)` применяет граф к Criteria-запросу и к
  `findAll`-ветке (RLS read-гейт не менялся), `FieldFactory` берёт пути из registry,
  а `SelectionFormAssembler` объединяет план сценария с путями выбранных колонок
  (optional-сеттер, чтобы ручная сборка в тестах сохранила прежнее поведение);
- **C3.5 / ADX-07.** `PrdSpecMtr` объявляет зависимости (`unitOfMeasurement` от
  номенклатуры; `nomenclature` и `nomenclature.unitOfMeasurement` от спецификации
  компонента), а `PrdSpecMtrFormCustomization` читает обычные геттеры. Удалены
  `LOOKUP_FETCH_DEPTH`, `FetchGraphs.associationPaths`, перечитывание по ID и знание о
  сессии; `associationPaths` удалён и как сам механизм. Закрытие закреплено
  `ApplicationFormFetchBoundaryTest`;
- **ADX-09.** Потребители имени переведены на `InstanceNameBridge.displayName`:
  `EntityField` (подписи саггеста и текст поля), `ListForm` (группировка и
  `ComboBoxFilter`), `ContextFilterPanel`, `GridViewEditorDialog`, `ReportQueryExecutor`,
  `ReportQueryEditor`, RLS-каталог и fallback-биндинг `FieldFactory`. Каналы, ранее
  кастовавшие значение к `HasDisplayName`, на мигрированной сущности без этого
  интерфейса (`ReceivingDocument`) падали или показывали `toString()` — теперь дают
  единое имя. Углубление fetch-графов читает состав имени из единого источника, поэтому
  grid грузит ровно нужное имени; startup-валидация глобального поиска принимает
  `@InstanceName` вместо обязательного `HasDisplayName`.

Проверка C3.3–C3.5 (встроенный Maven IntelliJ IDEA 2025.2.3, JDK 21.0.11, offline):

```text
FetchPlan registry unit + C3 config + InstanceName unit
Tests run: 23, Failures: 0, Errors: 0, Skipped: 0

FetchPlan LOOKUP hydration IT (ADX-07 приёмка)
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0

ApplicationFormFetchBoundaryTest + PlatformArchitectureTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0

Затронутые наборы (form/search/views/crud/metadata)
Tests run: 310, Failures: 0, Errors: 0, Skipped: 0

Random-order gate тех же наборов (seed по умолчанию)
Tests run: 310, Failures: 0, Errors: 0, Skipped: 0

Полный тестовый набор текущего дерева (default order, фаза test)
Tests run: 1118, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Честные оговорки: полный прогон — фаза `test` (без production frontend и упаковки JAR);
широкий random-order `verify` не запускался, так как описанная выше нестабильность
порядко-зависимого жизненного цикла Spring test context остаётся pre-existing дефектом и
не маскируется (диагностирована и закрыта в C3.7, см. ниже). Измерения C3.6 (query count,
размер графа до/после миграции) не сняты.
Перегрузка графа `LOOKUP` объединением объявленных зависимостей зафиксирована как
сознательный размен в ADR-0006.

### C3.4 (завершение): LIST/DETAIL/ROW подключены к read-границе

Сделано в текущем scope:

- `AbstractBaseService` больше не перечисляет ссылочные поля сам: список загружается по
  плану `LIST`, форма элемента (`findById`) — по независимому плану `DETAIL`. Локальный расчёт
  `FetchGraphs.entityReferencePaths(getGridFields())` из границы удалён;
- `GenericOwnedSectionService.findByParent(parent, descriptor)` использует план `ROW`;
  read-сервис объединяет его с дополнительными путями активного вида — UI не собирает
  базовый граф сам;
- явные пути остаются только как override (C3.3, правило 3): вариант
  `findAll(spec, pageable, fetchPaths)` для динамического состава колонок
  `ListForm`/`SelectionForm` и вариант `findByParent(parent, descriptor, fetchPaths)`;
- подключение сделано без инверсии зависимостей: `AbstractBaseService` получает registry
  optional-полем, `GenericOwnedSectionService` — optional-сеттером (его бин создаёт
  metadata-конфигурация, которая обязана подниматься без границы C3). Без компонентов C3 (ручная сборка сервиса)
  `AbstractBaseService` граф не строит — и не подменяет его второй реализацией расчёта путей.

Проверка (встроенный Maven IntelliJ IDEA 2025.2.3, JDK 21.0.11, offline):

```text
FetchPlan read-boundary IT (LIST/DETAIL/ROW применены: Hibernate.isInitialized)
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0

Random-order gate затронутых наборов (fetch/crud/form/metadata/views/security/application)
Tests run: 303, Failures: 0, Errors: 0, Skipped: 0

Полный тестовый набор текущего дерева (default order, фаза test)
Tests run: 1122, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Приёмка поведенческая: без применённого плана ссылки пришли бы неинициализированными
прокси, поэтому `Hibernate.isInitialized` — наблюдаемый признак того, что граф взят из
плана.

### C3.6: измерения и инвариант сценариев

Сделано в текущем scope:

- измерения сняты в тесте, а не в текстовом отчёте. «До» реконструируется тем же кодом,
  который стоял в `AbstractBaseService` (metadata-производный набор ссылок + углубление
  через состав имени), и сравнивается с планом на одних и тех же данных
  (`FetchPlanMeasurementIT`);
- проверены четыре утверждения: (1) план `LIST` не теряет ни одного пути прежнего графа;
  (2) количество подготовленных запросов у плана не больше, чем у прежнего графа (в
  измерении на 3 документах — равно); (3) detached-рендер строки (журнал и оба цеха)
  добавляет **ноль** запросов — то есть lazy load действительно не происходит;
  (4) план `DETAIL` не грузит зависимости чужого сценария: у `PrdSpec` ссылка
  `nomenclature` загружена, а `nomenclature.unitOfMeasurement` (зависимость сценария
  `LOOKUP`) — нет, как и коллекция `materials`, не входящая ни в один сценарий;
- статистика Hibernate включена в тестовой конфигурации; тесты утверждают
  `isStatisticsEnabled()`, так что отключение фичи уронит измерение, а не сделает его
  вакуумным;
- `DETAIL` и `LIST` независимы: ссылка, нужная только таблице, не утяжеляет форму;
  при обновлении списка он загружается по сценарию `LIST`. Решение зафиксировано в
  ADR-0006 (п. 10).

Проверка (встроенный Maven IntelliJ IDEA 2025.2.3, JDK 21.0.11, offline):

```text
FetchPlanMeasurementIT (query count, граф, detached render, чужой сценарий)
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0

FetchPlanRegistryTest (включая отказ старта на нарушенном инварианте)
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0

Random-order gate затронутых наборов
Tests run: 332, Failures: 0, Errors: 0, Skipped: 0

Полный тестовый набор текущего дерева (default order, фаза test)
Tests run: 1127, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Ограничение измерения: равенство по количеству запросов на трёх документах — это проверка
отсутствия регрессии, а не демонстрация выигрыша. План добавляет зависимости имени и
объявленные зависимости выбора, поэтому в отдельных сценариях он грузит больше прежнего
графа; это осознанный размен (ADR-0006, п. 9), а не измеренное улучшение. Опциональный
lifecycle-diff spike не запускался: практическая необходимость его результатов не
подтверждена.

### C3.7 hardening (блоки 1–6)

Этап выделен по результатам ревью C3: утверждение «незакрытых пунктов нет» было слишком
сильным. Сделано в текущем scope (без нового пользовательского функционала):

- **Ранняя write-авторизация.** `create/update/delete/save` вызывают решение о правах
  первым действием (`AbstractBaseService.authorizeWrite`), до нормализации,
  bean-валидации, `validateBusinessRules`, lifecycle hooks и before-событий. Для
  `delete` это закрывает реальную регрессию C3.0.1: удаление owned sections уходило в БД
  до проверки права, и отменял его только rollback. Гейт вызывает тот же
  `RlsPolicyEnforcer`, что и repository-aspect, поэтому раннее решение не может
  разойтись с границей; aspect и flush-listener остаются последним рубежом для прямых
  вызовов repository и commit-time dirty checking.
- **Единое имя.** `InstanceNameProvider` — публичный контракт с одним методом;
  статический `InstanceNameBridge` больше не содержит своей лестницы имени и делегирует
  провайдеру. Это устранило дефект, а не дублирование: UI-канал через bridge вызывал
  `HasDisplayName` на Hibernate-прокси напрямую и разворачивал его (SQL внутри сессии,
  `LazyInitializationException` вне её), тогда как резолвер этот случай уже обрабатывал.
  Пустое объявленное имя теперь даёт безопасную ссылку `Type#id`, а не молчаливый откат
  на legacy-ветку; последняя ступень лестницы — `Type#id`, а не `toString()`.
- **Жизненный цикл регистрации.** Установка вынесена в `InstanceNameBridgeInstaller`;
  регистрации контекстов сосуществуют, а не перезаписывают друг друга. Это оказалось не
  теорией: первый полный прогон после блока 2 упал на `InstanceNamePilotIT` именно
  из-за обнуления глобального состояния закрывающимся тест-контекстом.
- **Авторитет регистрации — по объявлению типа, а не по очереди установки.** Мост
  выбирал активную регистрацию как последнюю установленную, поэтому авторитетным
  становился любой контекст, поднявшийся позже, — включая частичный, чей набор metadata
  не содержит объявлений. Наблюдалось как потеря единого имени: UI и аудит получали
  fallback-ссылку `ReceivingDocument` вместо `РН-ПИЛОТ от 2026-09-13`, хотя живой
  контекст объявление знал (`InstanceNamePilotIT` в полном прогоне при зелёных тестах
  того же класса на прямом резолвере). Теперь для значения выбирается самая поздняя
  регистрация, которая знает его тип (`InstanceNameResolver.canResolve`), и только если
  таких нет — самая поздняя вообще. Тот же выбор по типу используется для `declaredName`,
  `instanceNamePaths` и `instanceNameFetchPaths`, чтобы поздний частичный контекст не
  затенял анализ имени или графа. Тип объекта берётся через единый прокси-безопасный
  `InstanceNameResolver.persistentClass`.
- **Аудит в slice-контекстах — устранена порядко-зависимость набора.** Корневая причина
  была не в C3: `@EntityListeners(AuditingEntityListener.class)` стоит на `BaseEntity`
  безусловно, а `AuditingEntityListener` скомпилирован AspectJ и настраивается
  JVM-глобальным синглтоном `AnnotationBeanConfigurerAspect`. Контексты с
  `@EnableJpaAuditing` регистрируют этот аспект бином и вешают `dependsOn` на EMF, а
  slice-контексты тестов (`@DataJpaTest`) — нет: `AuditConfig` вырезается исключающим
  фильтром слайса. В результате listener slice-контекста получал `ObjectFactory` чужого
  контекста: пока тот жив, аудит писался «по доверенности», когда закрывался — запись
  падала с `IllegalStateException: … has been closed already`. Лечение на стороне
  платформы: `AuditConfig` подключается к точке входа явно (`@Import` на `Application`),
  поэтому его инфраструктура есть в каждом контексте, который маппит `BaseEntity`,
  включая slice-контексты. Контракт закреплён поведенчески
  (`JpaAuditingSliceContractTest`): в slice-контексте есть свои `jpaAuditingHandler`,
  аспект и `AuditorAware`, а сохранение заполняет `createdAt/createdBy/modifiedAt`.
- **Инкрементальная компиляция тестов.** Отдельный дефект сборки, объяснявший
  массовые падения прогонов: два тест-файла лежали так, что их `.class` не появлялся по
  ожидаемому пути записи компилятора, и maven-compiler-plugin на каждом билде сносил
  записанные 336 классов тестов (405→69) и пересобирал их. Тесты успевали подняться,
  пока `target/test-classes` был полупустым: `Unable to find a @SpringBootConfiguration`
  (32×), `Unable to read meta-data for CrudAutoConfiguration` (4×), `ApplicationContext
  failure threshold exceeded`. Файлы перенесены, сборка снова инкрементальная.
- **Backoff auto-configuration.** Резолвер требует наличия `ManagedEntityTypes` (а не
  только `MetadataResolver`), поэтому частичный контекст без каталога сущностей получает
  backoff вместо `UnsatisfiedDependencyException` при создании бина.
- **Scenario-aware read API.** `AbstractBaseService.findAll()` и его pageable overload
  применяют `LIST`; `LookupService.findAll/search/findById` применяют `LOOKUP`.
  Дополнительные пути динамических колонок объединяются с базовым планом. Для форм выбора
  добавлен `findAllByScenario(LOOKUP, ...)`, поэтому сценарий не зависит от overload.
- **Одна загрузка секции.** `GenericOwnedSectionService` всегда объединяет `ROW` с
  дополнительными путями. `ItemTable` больше не перечитывает каждую ссылку отдельно;
  при смене вида одна загрузка строк обновляет только те ссылки, идентификаторы которых
  не менялись в несохранённой UI-модели.
- **Независимые планы.** Проверка `DETAIL ⊇ LIST` снята. Форма и список загружаются для
  своих потребителей; после сохранения список перечитывается через `LIST`.
- **Детерминированные планы.** Managed types, lookup-декларации и итоговые пути
  упорядочиваются стабильно; при совпадающих зависимостях одинаковая причина выигрывает
  независимо от порядка входного набора классов.

Проверка (Maven из IntelliJ IDEA, JDK 21.0.11, offline):

```text
RlsServiceWriteBoundaryIT (включая не-вакуумный контроль жизненного цикла)
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0

Аудит-граница + InstanceName + auto-configuration (alphabetical order)
JpaAuditingSliceContractTest, AttributeTypeServiceTest,
FetchPlanInstanceNameAutoConfigurationTest, InstanceNamePilotIT, InstanceNameResolverTest
Tests run: 31, Failures: 0, Errors: 0, Skipped: 0

Полный тестовый набор (default order, фаза test)
Tests run: 1147, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

Random-order gate, seed 11214075245200 — то самое зерно, на котором до исправления
аудита набор заканчивался 4 ошибками (AttributeTypeServiceTest 3/3,
VisualQueryPackageDeepPathIT 1/2)
Tests run: 1147, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

Random-order gate, seed 20260913
Tests run: 1147, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Гейт раннего RLS проверялся обратным экспериментом, а не только прямым прогоном: при
снятом вызове гейта в `delete` запрещённое удаление доходит до `publishDeleting` и тест
падает (NeverWantedButInvoked) — то есть проверка имеет силу.

Ограничений в C3.7 больше нет. Измерения C3.6 проверяют отсутствие регрессии, а не
выигрыш по запросам. За пределами C3 остаётся распространение `@InstanceName` на остальные
сущности.

## C4.0: inventory, ADR и characterization

Первый срез C4 закрывает классификацию и фиксирует baseline до изменения кода. Ни один
production-класс ещё не мигрирован — это осознанно: C4.1 начинается только после того, как
таксономия и решения проверяемы.

- [`../c4-inventory.md`](../c4-inventory.md): все **37** persistence types получают ровно
  одну classification — **16 `STANDARD_ROOT`**, **5 `OWNED_ROW`**, **16 `INTERNAL_STORE`**.
  Отдельно зафиксированы **18** metadata-driven типов и **4** объявленных
  `@TableSectionMetadata`-строки, включая два пересечения (`NomAttributeValue`,
  `PrdSpecMtr`).
- Classification service/repository/base слоя: **17** subclasses `AbstractBaseService`,
  **2** `ValidatedJpaCrudService`, **18** application repositories, **11** `searchByTerm`,
  **11** `serviceClass` declarations, **15** вызовов `LookupService`.
- Таксономическая находка, которую план предсказывал: `SklNomOpaValue` — строка агрегата
  `SklNomOpa` без `@TableSectionMetadata`, но с собственным repository. C4.1 обязан
  классифицировать её `OWNED_ROW` до появления generic fallback, иначе JPA-metamodel
  membership выдаст ей автономный handle.
- [`../decisions/ADR-0007-canonical-data-access-path.md`](../decisions/ADR-0007-canonical-data-access-path.md):
  canonical path `EntityDataAccess`, taxonomy/capability descriptor, судьба
  `BaseService`/`AbstractBaseService` (**absorb**), `ValidatedJpaCrudService` (**retain as
  internal-store**), tri-state `required`, literal-escaping search, telemetry policy для
  LOOKUP и compatibility milestones.
- Characterization-тесты фиксируют поведение, которое C4 меняет:
  `CharacterizationStandardPathIT` (7 тестов, пилот `Branch`) — wildcard-семантика `%`/`_`,
  bounded blank term у lookup, молчаливый пропуск неизвестного search field, runtime trap
  `search(String, Pageable)`, неограниченный blank legacy-search; `RowDraftTest`
  (`restoreIssuesOneLookupPerEntityReference`) — по одному read'у на каждую ссылку строки,
  то есть UI-managed N+1, который C4.1 обязан убрать.

Проверка (Maven из IntelliJ IDEA, JDK 21, offline):

```text
CharacterizationStandardPathIT, RowDraftTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Открытые решения C4.1, актуальные после hardening-среза: остаётся ли `GridFormView`
`STANDARD_ROOT` с custom policy или переходит в `INTERNAL_STORE` (решается в C4.6).
Окончательная форма descriptor/capability API зафиксирована разделом ниже; способ
объявления `SklNomOpaValue` owned row выбран (`EntityExposureOverride`).

## C4.1: unified read kernel

Срез заменяет три независимые query-boundary одной. Ни один production-класс ещё не
мигрирован по data-handle: это C4.3+.

Добавлено (пакет `org.ipro.data`, auto-configuration `DataAccessAutoConfiguration`):

- `EntityDescriptorCatalog` — классифицированный descriptor поверх `ManagedEntityCatalog`
  и `SectionMetadataRegistry`, без нового scan. Baseline: **16 `STANDARD_ROOT`**,
  **5 `OWNED_ROW`**, **16 `INTERNAL_STORE`**; `SklNomOpaValue` классифицируется явным
  `EntityExposureOverride` из `org.ip.config.EntityClassificationConfig` (находка
  `c4-inventory.md` §2.3).
- `ScenarioFetchGraphResolver` — единственная точка правила
  `scenario plan ∪ extras -> deepen once -> graph`; `FetchPlanRegistry.pathsWith`
  вычисляет углублённое объединение. Прежняя асимметрия
  (`LookupService.entityGraph` union без deepen) устранена.
- `CanonicalReadExecutor` — content/count/sort, отдельный count query без fetch-графа,
  обязательный RLS read gate и проверка экспозиции типа **до** RLS/SQL. Owned row не
  получает автономный list/detail/lookup: отказ с реальной причиной.
- `ReadTelemetry` — optional seam с noop по умолчанию (ADR-0007 §8); durable-event policy
  для LOOKUP остаётся за C4.4/C4.5.
- Immutable read requests `PageRead`/`ListRead`/`DetailRead`/`LookupRead`.

Мигрировано без изменения public-поведения:

- `AbstractBaseService`: `findById`, `findAll()`, `findAll(Pageable)`,
  `findAll(Specification, Pageable)`, `findAllByScenario`/`findAllWithFetchGraph` и `sum`
  идут через executor, когда он есть в контексте; при ручной сборке (unit-тесты) остаётся
  прежний путь.
- `LookupService`: собственная Criteria/fetch orchestration удалена, поиск идёт через ту
  же границу.
- `GenericOwnedSectionService`: граф строки `ROW` строится тем же resolver'ом.
- `GlobalSearchService`: read gate идёт через executor; provider query консолидирован в
  `CanonicalReadExecutor` в C4.5, SPI оставлен только для отображения/классификации.
- `RowDraft.restore` больше не перечитывает каждую entity-ссылку: UI-managed N+1 убран,
  отмена возвращает захваченное состояние (включая несохранённую ссылку без id).

Проверка (Maven из IntelliJ IDEA, JDK 21, offline):

```text
Полный тестовый набор (default order, фаза test)
Tests run: 1164, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

Random-order gate, seed 20260913
Tests run: 1164, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Осознанные ограничения среза: реальная telemetry-реализация не включается (только
noop-seam); architecture-тест, запрещающий строить scenario-граф вне resolver'а, входит
в C4.7; per-entity search matrix — C4.4.
Для ownership есть fallback-путь без единого resolver'а в metadata-only контекстах, где
C3/C4-границы отсутствуют по построению.

### C4.1 hardening по итогам ревью

Ревью C4.1 нашло расхождение кода с ADR §2/§4 и одну поведенческую регрессию. Срез
закрывает их, не добавляя пользовательского функционала:

- **capabilities стали enforcement-границей.** `CanonicalReadExecutor.requireScenario`
  проверяет `descriptor.capabilities().allows(scenario)` до RLS и SQL. `INTERNAL_STORE`
  больше не читается через canonical path «потому что не owned row».
- **Тип вне каталога — отказ, а не permissive root.** Новая экспозиция
  `EntityExposure.UNCLASSIFIED`: пустые capabilities и явная причина вместо полного
  read/write handle. Прежний fallback `descriptorOf` выдавал неизвестному классу
  `STANDARD_ROOT`, то есть решение уезжало глубже в RLS/JPA.
- **Явная capability policy типа.** Добавлена `EntityCapabilityOverride` (наборы reads и
  writes заменяют выведенные из экспозиции) и объявлены предметные ограничения из
  инвентаря: `AttributeValue` — только create, `SklNomOpa` — без generic writes,
  `GridFormView` — writes с ownership-ограничением в причине. Владелец подсистемы
  отчётов отдаёт `UreportTemplate` явный read-мост `LIST`/`DETAIL` до перевода сервиса на
  internal-store adapter (C4.3); lookup не выдан намеренно.
- **lookup по id снова идёт сценарием `LOOKUP`.** `DetailRead` несёт сценарий
  (`DETAIL` для карточки, `LOOKUP` для значения выбора):
  `LookupService.findById` терял объявленные `@Lookup(fetch)` зависимости, которых в
  DETAIL нет. Регрессия закрыта тестом `lookupByIdKeepsTheSelectionDependenciesThatTheFormPlanOmits`.
- **lookup ограничивается в SQL.** Blank term и пустой список полей ставят
  `setMaxResults` на запрос вместо чтения таблицы с обрезкой в памяти; `limit == 0` —
  пустой результат без запроса.
- **paging count parity.** Если Specification помечает запрос distinct (join по to-many),
  count тоже считается `countDistinct` — как в Spring Data. На H2 тест зелёный и без этого
  (H2 сворачивает `select distinct count(...)`), на PostgreSQL прежняя запись дубликаты не
  убирает, поэтому явный `countDistinct` — переносимое поведение, а тест фиксирует контракт.
- **`deepen once` без повторного углубления.** `FetchPlan` хранит `rawPaths()` (план до
  углубления), `pathsWith` углубляет объединение ровно один раз. Ранее план приходил уже
  углублённым и углублялся повторно: результат не менялся, но правило не соответствовало
  объявленному.
- **Телеметрия считает страницу страницей** (`getNumberOfElements`), а не одним
  результатом; `AbstractBaseService.findById(null)` возвращает пустой результат, как
  `LookupService.findById`, вместо NPE из canonical-запроса.
- **Остатки второго ревью C4.1 закрыты.** `readLookup` активирует
  `rlsFilterActivator.ensureRlsEnabled` безусловно, как list/detail/sum — в
  fallback-конфигурации без `RlsPolicyEnforcer` lookup больше не остаётся единственным
  чтением без FILTERABLE-предикатов. `GlobalSearchService` проверяет capability источника
  до provider-callback. `pathsWith` валидирует каждый динамический путь через `ColumnPath`
  **до** объединения — правило читается буквально как `union → validate → deepen once`,
  а неизвестный путь отклоняется сразу, а не падает на построении `EntityGraph`.

Проверка (Maven из IntelliJ IDEA, JDK 21, offline):

```text
Адресный набор (CanonicalReadBoundaryIT, FetchPlanMeasurementIT, FetchPlanRegistryTest)
Tests run: 27, Failures: 0, Errors: 0, Skipped: 0

Полный тестовый набор (default order, фаза test)
Tests run: 1171, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

Random-order gate, seed 20260913
Tests run: 1171, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
``` Оставшееся осознанно открытым: write-enforcement capabilities (`writes` читает C4.3,
когда появится canonical write path) и миграция `UreportTemplateService` с
`AbstractBaseService` на internal-store adapter.

### C4.2: effective metadata

Срез убирает единственность явного атрибута как источника истины: `required`, `type` и
`reference` выводятся из Bean Validation, JPA и Java-типа, а объявление остаётся там, где
вывод невозможен или должен быть переопределён.

- `RequiredMode` (`AUTO`/`REQUIRED`/`OPTIONAL`) заменил `boolean required`; мигрировано
  52 объявления. Примитивы не становятся обязательными: `nullable = false` на `boolean`
  не создаёт требования заполнить поле.
- `FieldMetadataInfo` хранит effective-факты и `FactOrigin` каждого: `required` —
  `EXPLICIT`/`BEAN_VALIDATION`/`JPA_MAPPING`/`PLATFORM_DEFAULT`; `type` —
  `EXPLICIT`/`JAVA_TYPE`/`JPA_MAPPING`; `reference` — `EXPLICIT`/`JPA_MAPPING`.
- Конфликты (объявленный тип против Java/JPA, `@Lookup.entity` против типа ссылки,
  `OPTIONAL` против server-required) — `ERROR`; избыточное объявление — `INFO`;
  UI-строгость без server-контракта — `WARNING`.
- `MetadataConsistencyValidator` обходит все managed-типы, `MetadataConsistencyStartupCheck`
  останавливает старт на `ERROR`, сообщая все ошибки сразу с сущностью/полем/источником.
- `MetadataAllowance` объявляет осознанное расхождение с причиной; исчезновение условия
  даёт `STALE_ALLOWANCE` (`ERROR`). Единственное известное расхождение —
  `ReceivingDocument.journal` (UI требует, сервер допускает NULL) — объявлено в
  `org.ip.config.MetadataDiagnosticsConfig`.
- Пилоты (Branch, Journal, UnitOfMeasurement, Nomenclature, PrdSpec и их строки) переведены
  на вывод: удалены дублирующие `required`, `type = ENTITY_REFERENCE` и `lookup.entity`.
- Snapshot parity: `src/test/resources/metadata/effective-metadata-{before,after}.txt` —
  diff пуст (ни одно effective-значение не изменилось), а перераспределение источников
  видно в `effective-metadata-origins.txt`.
- Hardening по итогам ревью: `*.txt`-исключение в `.gitignore` вернуло snapshot-ресурсы в
  поставку (до него чистый checkout терял их); объявленный `type` проверяется на
  совместимость с Java-типом (таблица `String → TEXT/TEXT_AREA/EMAIL/PASSWORD`), а не
  строгим равенством — иначе `EMAIL`/`PASSWORD`/`TEXT_AREA` останавливали старт;
  `jpaDeclaresNonNull` учитывает `@ManyToOne`/`@OneToOne`/`@Basic(optional=false)`;
  snapshot собирает типы через `LinkedHashSet` (строки больше не дублируются);
  `MetadataAllowance` понижает только warning-коды из `ALLOWABLE_CODES`, а не любую
  диагностику; `MetadataConsistencyStartupCheck` покрыт негативным тестом;
  `GlobalSearchService` проверяет capability источника.

Проверка (Maven из IntelliJ IDEA, JDK 21, offline):

```text
Адресный набор (EffectiveMetadataSnapshotTest, EffectiveFieldFactsTest,
MetadataConsistencyValidatorTest, MetadataConsistencyStartupCheckTest,
FetchPlanRegistryTest)
Tests run: 30, Failures: 0, Errors: 0, Skipped: 0

Полный тестовый набор (default order, фаза test)
Tests run: 1185, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

Random-order gate, seed 20260913
Tests run: 1185, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

На реальной модели проверка не нашла ни одной ошибки и ни одного предупреждения: 1
разрешённое расхождение (`ReceivingDocument.journal`) и 40 избыточных объявлений вне
пилотов (24 `required`, 8 `type`, 8 `lookup.entity`) — перечислены как `INFO`, то есть
следующий срез может удалять их по готовому списку, а не по поиску.

### C4.3: canonical write path

Срез замыкает write-обещание ADR-0007 §1/§2/§5: write intent становится отдельной
boundary, а `STANDARD_ROOT` проходит CRUD без Spring Data repository и application service.

- `EntityDataAccess` — публичный facade (`detail`/`list`/`lookup`/`create`/`update`/`save`/
  `delete`); `CanonicalEntityDataAccess` не принимает решений о policy, а проецирует их на
  единые executor'ы.
- `CanonicalWriteExecutor` — единый порядок: capability типа (fail-closed) → ранний RLS →
  нормализация версии → нумерация → bean-валидация → lifecycle before-hooks и before-events
  → persistence через `EntityManager` → after-events и `onSave`. Запрещённая операция не
  доходит ни до валидации, ни до хуков, ни до пользовательского расширителя.
- `EntityDataPolicy` (SPI) + `EntityDataAccessResolver` — type-directed выбор: explicit typed
  custom policy, иначе canonical generic path, иначе отказ с реальной причиной. Две policy
  для одного типа и policy на не-JPA тип — startup error.
- `CanonicalEntityService<T>` (`BaseService`) — default handle для `STANDARD_ROOT` без своего
  сервиса; `ServiceLocator` отдаёт его только там, где canonical path разрешён, иначе
  сохраняет прежнюю диагностику.
- Предметные запреты стали исполняемыми: `AttributeValue` — только `CREATE`, `SklNomOpa` —
  без generic writes, `UreportTemplate` (read-мост владельца) — без writes.
- `ValidatedJpaCrudService` отклоняет metadata-driven root в конструкторе: internal-store
  adapter больше не может молча обслуживать standard root (ADR-0007 §3).
- Hardening по итогам ревью: intent определяет JPA-операцию и сверяется с точным
  persistence-классом (`create` с существующим `id` больше не делает `merge`, `update` без
  `id` отклоняется). UPDATE сначала требует существующую и доступную исходную строку,
  затем авторизует и сохранённое исходное, и поданное целевое состояние до нормализации,
  валидации и hooks; это не допускает неявный insert через `merge` и обход RLS сменой
  классифицирующего поля. Canonical handle `GridFormView` ограничен `CREATE` — ownership-правило
  (`checkEditable`) не исполняется canonical pipeline и не обходится facade;
  `CanonicalWritePathIT` проверяет `update` после `flush`/`clear` и подтверждает полный
  порядок pipeline (`RLS → numbering → validation → events`), а также что RLS-отказ
  останавливает операцию до validator'а, хуков и событий.

Исторический полный прогон до последующего hardening (Maven, JDK 21, offline):

```text
Адресный набор (CanonicalWritePathIT, CanonicalWriteBoundaryIT,
CanonicalReadBoundaryIT, ServiceLocatorCanonicalFallbackTest,
ValidatedJpaCrudServiceGuardTest)
Tests run: 32, Failures: 0, Errors: 0, Skipped: 0

Полный тестовый набор (default order, фаза test)
Tests run: 1214, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

Random-order gate, seed 20260913
Tests run: 1214, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`CanonicalWritePathIT` использует изолированный persistence unit: test-only entity
(`org.ipro.data.fixture.C4FixtureEntity`) с `@EntityMetadata` и полями, но без repository,
service, `serviceClass` и bean-name — то есть успех не объясняется инфраструктурой
приложения. Формальное закрытие C4.3 остаётся pending: **write-telemetry** (план C4.3
п.4), **первый реальный справочник** на canonical path (план C4.3 п.7) и form → write →
audit acceptance. До выполнения этих пунктов production canonical fallback не несёт
нагрузки, поэтому статус опирается на fixture и boundary-тесты. Отдельно остаются
поглощение `AbstractBaseService` (C4.7) и снятие read-моста `UreportTemplate`.

### C4.4: server-side search

Срез делает search единым механизмом: один query builder с явным контекстом, а не
независимые repository-запросы и in-memory фильтры.

- `SearchContext` (`LIST`/`LOOKUP`/`GLOBAL`) и `SearchRead` — контекст задаёт
  FetchPlan-сценарий, telemetry-намерение, строгость проверки явных полей и ranking.
  `GLOBAL` сохраняет FetchPlan `LIST`, но использует отдельную telemetry operation
  `GLOBAL_SEARCH`; глобальная интеграция описана ниже в C4.5.
- `CanonicalReadExecutor.readSearch` — единственный builder для LIST/LOOKUP/GLOBAL:
  capability сценария → read gate → RLS → fetch-граф → SQL. `readLookup` стал тонкой
  проекцией того же builder'а; `readSearchWindow` даёт bounded top-N для global search
  без count query и применяет per-source timeout.
- `SearchFieldResolver` — единая лестница полей: явные поля → type-level `@SearchFields`
  → `@InstanceName` → строковые
  `selectColumns` effective metadata. Неизвестное явное поле в list/global search
  отклоняется (`SearchFieldResolverTest`), а не пропускается молча.
- `SearchTerms` — literal escaping: `%`/`_`/`\` трактуются буквально (принятое изменение;
  раньше это были SQL-wildcard). Порядок детерминирован: `exact → prefix → substring → id`.
- Blank term не является фильтром: выдача bounded (page/limit) и упорядочена по id, а не
  `findAll()`; paging считает `totalElements` отдельным count-запросом.
- `BaseService.search(String, Pageable)` больше не runtime trap, а compatibility-делегат;
  `CanonicalEntityService.search` реализован.
- Все 16 стандартных корней используют canonical builder; repository `searchByTerm` и
  `findWithFilter` на стандартном пути удалены. `PrdSpec` задаёт общие поля
  `codeSpec,draft` через `@SearchFields`; `SklNomOpa`, `UnitOfMeasurement` и
  `GridFormView` сохраняют поля через explicit field sets,
  но оба overload используют один builder. `User` больше не фильтрует `findAll()` в
  памяти; `NomSklAttribute` без строковых полей возвращает bounded page для blank term и
  пустую выдачу для непустого. Typed application services остаются в C4.6, их search
  query уже canonical.

Исторический полный прогон до последующего hardening (Maven, JDK 21, offline):

```text
Адресный набор до hardening C4.3/C4.4 (исторический снимок)
Tests run: 29, Failures: 0, Errors: 0, Skipped: 0

Полный тестовый набор и random-order gate до hardening C4.3/C4.4:
1227 tests, 0 failures, 0 errors, 0 skipped (исторический снимок)
```

Повторная адресная проверка после устранения замечаний C4.3/C4.4 (2026-09-14,
JDK 21, offline):

```text
SearchTermsTest, SearchFieldResolverTest, SearchOverloadParityTest,
CanonicalWritePathIT, CanonicalWriteBoundaryIT, ServiceLocatorCanonicalFallbackTest,
ValidatedJpaCrudServiceGuardTest, CharacterizationStandardPathIT
Tests run: 48, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Полный `mvn verify` после этих изменений не запускался.

Тесты literal escaping проверены откатом: без экранирования падают и `SearchTermsTest`,
и поведенческий `CanonicalWritePathIT.searchTreatsWildcardsLiterally`. Для typed search
сервисы сохраняют предметные методы, но запросы уже идут через canonical builder с
explicit fields или общими type-level defaults.

### C4.5: modular global search

Глобальный поиск переведён на общий secured query path без центральной карты источников:

- `@GlobalSearchable(order)` объявляет участие и стабильный порядок; каталог включает
  только managed `STANDARD_ROOT` с разрешённым `LIST`, а необъявленные managed types
  автоматически не публикуются;
- поля выводятся через `SearchFieldResolver`; `@SearchFields` сохраняет семантику
  `PrdSpec` (`codeSpec`, `draft`) одинаковой для list/global search, не включая
  `nomenclature.name` из отображаемой metadata;
- `GlobalSearchProvider` стал mapper/classifier/timeout SPI без EntityManager и SQL.
  `GlobalSearchCatalog` проверяет конфликты и невалидную конфигурацию при старте;
- `CanonicalReadExecutor.readSearchWindow` выполняет bounded GLOBAL query с лимитом,
  timeout и RLS/read-capability/fetch-plan policy, без дополнительного count-запроса;
- `Nomenclature`, `PrdSpec`, `ReceivingDocument` объявляют участие рядом с сущностями;
  custom `PrdSpecGlobalSearchProvider` сохраняет подпись `codeSpec — draft`; центральные
  `GlobalSearchConfig` и `GlobalSearchApplicationConfig` удалены;
- тесты закрепляют fixed source order, поля и подписи, пропуск timeout источника, wiring
  Spring-контекста и один bounded SQL-запрос без count с ранжированием
  `exact → prefix → substring → id`.

Проверка C4.5 (JDK 21, offline): целевой набор — 55 тестов, 0 failures/errors/skipped;
общий random-order `mvn verify` — 1233 теста, 0 failures/errors/skipped, BUILD SUCCESS
(seed `3330014842700`; `VisualQuerySubqueryIT` исключён проектным gate).

## Неошибочные и блокирующие диагностики

| Диагностика | Категория | Действие |
|---|---|---|
| H2 не реализует PostgreSQL `jsonb` semantics | Ограничение тестового double | Dialect-aware mapping проверяет DDL/JDBC payload contract на H2; validation/operators/indexing проверить PostgreSQL/Testcontainers перед production |
| `RLS: возможная тихая утечка` | Security finding, не ожидаемый шум | C: fail-closed enforcement и channel tests |
| Mockito self-attach warning | Toolchain warning | Учесть в A2/JDK test configuration |
| Deprecated/unchecked compiler warnings | Технический долг | Исправлять адресно, не скрывать общей настройкой |

Warnings не подавляются только ради зелёного лога. Security/schema diagnostics остаются видимыми, пока не закрыты соответствующим этапом.
