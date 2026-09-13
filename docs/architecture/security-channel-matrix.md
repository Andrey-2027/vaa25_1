# Карта каналов доступа к данным

- Этап: `C1 — инвентаризация каналов`; строки переоценены после `C2 — fail-closed RLS`
- Состояние: инвентарь C1 сохранён; статусы `DAC-*` пересмотрены по коду и текущему worktree
- Даты: инвентарь — 2026-09-11; переоценка — 2026-09-12; проверка текущих gates — 2026-09-13
- Базовое проверенное дерево: коммиты `8cd77b6` (platform RLS enforcement) и `ec7bafa`; текущие изменения ещё не committed

Этот документ отвечает на три вопроса для любого пути к данным:

1. кто владеет решением о доступе;
2. где оно принудительно применяется до выполнения запроса или изменения;
3. допустим ли привилегированный обход и как он ограничен.

`RlsStatementGuard` в колонке «применение» означает только обнаружение. Он не является
enforcement-механизмом и не делает канал безопасным.

## Защищённая модель на момент переоценки

| Entity | Измерения | Вид |
|---|---|---|
| `Journal` | `JOURNAL` | FILTERABLE |
| `Branch` | `BRANCH` | FILTERABLE |
| `Workshop` | `BRANCH` | FILTERABLE (`nullsNotApplicable`) |
| `PrdSpec` | `JOURNAL` | FILTERABLE (`valuePaths = journal.id`) |
| `ReceivingDocument` | `JOURNAL`, `BRANCH`, `ENTITY:ReceivingDocument` | FILTERABLE + CHECK_ONLY, все `custom` |

Все FILTERABLE-сущности объявляют `@FilterDef(..., applyToLoadByKey = true)`, поэтому
построчный фильтр действует и на загрузку по ключу, а не только на списки.

Строки `PrdSpecMtr`, `PrdSpecOper` и `ReceivingDocumentItem` не объявляют собственную
RLS policy: доступ наследуется от aggregate root, и это уже не предположение
реализации, а явная metadata policy `SectionRlsPolicy.INHERIT_ROOT`
(`GenericOwnedSectionService.requireDescriptor`).

## Обозначения

- **Покрыт** — enforcement есть в коде пути, и он не зависит от дисциплины конкретного
  вызывающего сервиса; у статуса указана enforcement-точка и тест.
- **Частично** — защита есть, но у канала остался незакрытый участок или неполное
  тестовое подтверждение.
- **Разрыв** — защищённые данные могут попасть в запрос без обязательного enforcement.
- **Зарезервирован** — канала ещё нет; до появления должен быть выбран безопасный
  контракт, а не разрешён прямой доступ по умолчанию.

## Security matrix

| ID | Канал и точки входа | Владелец решения | Применение | Privileged bypass | Статус после C2 |
|---|---|---|---|---|---|
| `DAC-01` | Standard CRUD read: `AbstractBaseService.findById/findAll/page/spec/sum` | Platform data boundary | `RlsPolicyEnforcer.prepareRead` в каждом read-методе + `applyToLoadByKey` + sentinel `-1` вместо пустого списка | Не нужен | **Покрыт.** Fail-closed по субъекту и по построчному фильтру. Тесты: `RlsIntegrationTest.aliceSeesOnlyHerJournal`, `userWithNoGrantsSeesNothing`, `adminWildcardSeesEverythingAndFilterStaysDisabled`, `prdSpecInheritsAccessFromItsJournalNotItsOwnId`, `RlsReadGateTest` |
| `DAC-02` | Standard create/update/delete и aggregate root save | Platform data boundary + entity policy | `RlsPolicyEnforcer.requireUpdate/requireDelete` из descriptor + flush-time guard `RlsWriteEnforcementListener` (PRE_INSERT/PRE_UPDATE/PRE_DELETE) с одноразовыми capability `RlsWriteAuthorization`; implicit dirty-checking на commit тоже требует grant | Только типизированная system operation | **Покрыт.** Read и write policy сведены к одному descriptor; для стандартного случая SQL сверяется с ним при старте. Тесты: `RlsIntegrationTest.canUpdateAndCanDeleteReflectGrantFlagsPerJournal`, `entityLevelGrantGatesWriteIndependentlyOfJournalAndBranch`, `RlsRepositoryEnforcementIT.commitTimeDirtyCheckingRequiresExplicitWriteAuthorization`, `RlsDeniedEventTest` |
| `DAC-03` | Предметные `search(...)` и custom repository queries | Platform boundary перед repository | `RlsRepositoryEnforcementAspect`: для protected entity read требует гейт до `proceed`, write/delete — построчные проверки; native query, `flush()`, bulk (`@Modifying`, `*InBatch`) и мутация только по id запрещены | Не допускается | **Покрыт по каналу repository.** Сервис может забыть явный вызов — отказ всё равно произойдёт. Тесты: `RlsRepositoryEnforcementIT` (включая deny-ветки: native query, `flush`, bulk, мутация только по id) |
| `DAC-04` | Lookup/selection: `LookupService.search/findAll/findById` | Platform lookup boundary | Каждый метод вызывает `RlsPolicyEnforcer.prepareRead` (браузерный гейт + фильтры). Публичный доступ к raw repository удалён (в `src/main` нет `getRepository(`) | Не нужен | **Покрыт.** Тест: `RlsReadGateTest.lookupServiceHonorsReadGate` |
| `DAC-05` | Обычные list/item/selection forms | Server-side boundary; UI только отображает решение | Формы делегируют `BaseService`/`LookupService`; `RlsUiGate` возвращает `AccessDecision` с причиной для UI и явно описан как UX поверх серверного write-guard | Не допускается из UI | **Покрыт.** Архитектурный запрет (`UiPersistenceBoundaryTest`) действует для обоих UI-пакетов — `org.ip.views..` и `org.ip.groupgrid..`; невакуумность правила проверена мутацией (временный `EntityManager` в `org.ip.groupgrid` правило ломает). Найденный здесь дефект канала тоже закрыт: строка owned-секции больше не является самостоятельным справочником — у неё нет узла подсистемы и автономного сервиса, а `ServiceLocator` отказывает ей с настоящей причиной. Тесты: `SectionParentColumnIsolationTest.everySectionRowIsNotAnAutonomousCatalog`, `ServiceLocatorSectionRowTest` |
| `DAC-06` | Owned table sections: `GenericOwnedSectionService`, aggregate save/delete | Aggregate policy owner | Явная `SectionRlsPolicy.INHERIT_ROOT`; read через фильтры root; row-repository запрещён аспектом (`use the aggregate section service`); write выполняется по проверенному root | Только системная операция над всем aggregate | **Покрыт.** Тесты: `GenericOwnedSectionServiceIT`, `PrdSpecMetadataAggregateIT`, `ReceivingDocumentRulesIT`, `NomenclatureAttributesIT`, `RlsRepositoryEnforcementIT.ownedSectionRowRepositoryIsDenied` (отрицательный тест на row-repository секции) |
| `DAC-07` | Global search: `GlobalSearchService` + `JpaGlobalSearchProvider` | Platform search boundary | До провайдера: `ensureRlsEnabled`, затем `prepareRead`/`canRead` для source; source без доступа пропускается до вызова провайдера | Не нужен | **Покрыт.** Тесты: `GlobalSearchServiceTest.sourceWithoutReadAccessIsSkippedBeforeProviderInvocation`, `shortQueryDoesNotTouchRlsOrProviders` |
| `DAC-08` | Projection/grid/grouping/custom UI query | Platform query boundary | Прямой persistence в UI запрещён архитектурно (`UiPersistenceBoundaryTest`, оба UI-пакета); прикладной projection-запрос идёт через сервисы/`LookupService` | Не допускается | **Покрыт для production-кода.** Спайк `org.ip.groupgrid` (`GroupGridDemoView`, `GroupingGrid`, `GroupingPanelView`, `NomenclatureLazyGroupsView`) перенесён в test-исходники: он был единственным потребителем своих панелей, а две из них держали raw `EntityManager`. Production-классpath чист, правило расширено на `org.ip.groupgrid..` и проверено мутацией. Остаток на C4: полноценный secured projection path для будущих grouping-сценариев |
| `DAC-09` | Report JPQL/HQL: Report Studio, JRXML, UReport через общий runner | Reports data boundary | `ReportQueryGuard` анализирует roots/joins/subqueries, `ReportQueryExecutor` включает фильтры | Отдельный system-report contract, по умолчанию запрещён | **Покрыт.** Тесты: `ReportQueryGuardTest`, `SubqueryRlsIT`, `VisualQueryPackageRlsIT`, `CteHibernateFilterIT`, `ReportQueryExecutionIT`, `VisualQueryGuardIT`, `JrxmlExecutionIT` |
| `DAC-10` | Entity-параметры отчёта | Reports parameter boundary | `EntityParamRefresher`: read gate, вопрос про detached entity/id, включение фильтров, перечитывание | Не нужен | **Покрыт.** Тест: `ReportParamResolutionIT` |
| `DAC-11` | Асинхронный запуск отчёта | Reports orchestration | Только `ReportTaskExecutor` через `ReportExecutionService.executeAsync`: bounded pool, снимок аутентификации, request attributes, очистка ThreadLocal в `finally` и отказ без аутентифицированного субъекта | Не допускается неявный `system` | **Покрыт.** `JrxmlRunDialog` переведён с raw `Thread` на исполнителя (свой поток остался только у инфраструктурных демонов платформы). Тесты: `ReportTaskExecutorTest` (снимок не течёт при мутации субъекта, отказ до постановки задачи, очистка при успехе и ошибке), `ReportExecutionIT`, `JrxmlExecutionIT`, плюс arch-правило `UiThreadingBoundaryTest` против `new Thread` в `org.ip..` (проверено мутацией) |
| `DAC-12` | Export и run-cache отчёта | Reports artifact boundary | `ReportArtifactCache.get(key, requester)` отдаёт артефакт только владельцу, владелец обязан быть аутентифицирован; `ReportExecutionService.cached(...)` передаёт текущего пользователя | System export только отдельным use case | **Покрыт.** Тесты: `ReportArtifactCacheTest.putAndGet` (отказ чужому владельцу), `keyChangesWithUser`, `ReportExecutionIT.cachedArtifactReused` |
| `DAC-13` | Прямой repository/`EntityManager` из application service и динамическое имя REF-сущности | Platform boundary вокруг persistence | `ApplicationBoundaryTest` запрещает JPA API в application services; `ManagedEntityCatalog` разрешает REF-цель только среди сущностей persistence unit, а `AttributeValueService` читает её через RLS-aware `LookupService` | Только зарегистрированный typed bypass | **Закрыт для application service.** REF API принимает только `AttributeType + id`; вызвать его с классом другой сущности нельзя. Сырые JPA-входы остаются в платформенном слое и защищены его boundary |
| `DAC-14` | Проверка ссылочной целостности перед delete | `ReferenceCheckService` | `withReferenceIntegrityCheck` → typed scope; SQL обхода ограничен `SELECT COUNT`, актор обязателен, результат аудируется | Разрешён только этому use case | **Допустимый типизированный обход.** Тесты: `RlsStatementGuardTest.referenceIntegrityBypassRejectsRowMaterialization`, `untypedRunAsSystemIsRejected`, `RlsIntegrationTest.withRlsDisabledSeesEverythingAndRestoresFilterAfter` |
| `DAC-15` | RLS administration: grants, users/roles, dimension values | Security administration boundary | Серверная проверка `@PreAuthorize("hasRole('ADMIN')")` на `AccessGrantAdminService`; значения измерений — через `RlsDimensionValueCatalog` + `withDimensionAdministration` (typed reason, audit, SQL только по своей таблице) | Разрешён security-admin use case | **Частично.** Серверная граница появилась, но resource permissions как таковые остаются C5, а UI-проверка в `AdminView` — по-прежнему только UX |
| `DAC-16` | Auth/RLS bootstrap: users, roles, grants | Security kernel | Явный kernel scope; bootstrap-семантика измерений закреплена тестами | Внутренний kernel scope | **Особый канал, fail-closed.** Тесты: `bootstrapAllowsFirstValueCreationWhenDimensionHasNoGrants`, `bootstrapTurnsOffOnceDimensionHasAnyGrant`, `bootstrapDoesNotApplyToCheckOnlyDimensions` |
| `DAC-17` | Scheduled/background jobs | Job-specific service boundary | `ApplicationBackgroundBoundaryTest` запрещает прямые и meta-annotated `@Scheduled`/`@Async`, включая class-level `@Async`, и executor/thread primitives в `org.ip..`; бизнес-задач нет | Только зарегистрированный system job с reason/scope | **Закрыт превентивным правилом.** При появлении первой фоновой бизнес-задачи правило остаётся deny-by-default: сначала typed executor с субъектом, scope и audit |
| `DAC-18` | Native SQL/JDBC и operational telemetry | Владельцы подсистем telemetry/diagnostics | Инвентарь подтверждён: `JdbcTemplate` встречается только в schema compatibility (`ReportSchemaCompatibility`) и self-test аудита полей; business-таблиц нет | Startup/self-test/retention scopes | **Вне entity RLS.** Любой новый JDBC к business tables обязан быть отдельно зарегистрирован или запрещён; resource permissions — C5 |
| `DAC-19` | Startup/bootstrap/schema compatibility | Infrastructure owner | `DataInitializer` регистрируется только при `dev`/`demo`/`test` без `prod` и `app.demo-data.enabled=true` | Startup-only scope | **Закрыт для demo initializer.** Шесть независимо обнаруживаемых profile/property integration tests проверяют отсутствие/наличие бина и идемпотентность |
| `DAC-20` | REST JPQL preview: `POST /api/report-jpql/preview` | Integration/report boundary | `hasAnyAccess("REPORTS:JPQL_PREVIEW")` → 403, затем общий `JpqlRunService` (guard → RLS → limits) под учёткой вызвавшего | Named service account с минимальным report scope | **Покрыт.** Тест: `ReportJpqlPreviewControllerIT` |
| `DAC-21` | Будущие import, messaging, external integrations и новые REST adapters | Будущий integration boundary | Контракт не менялся: вход только через secured application API | Только named service account с минимальным scope | **Зарезервирован.** Новый adapter не допускается к repository/EM |

## Реестр существующих обходов

| Обход | Назначение | Допустимый результат наружу | Состояние |
|---|---|---|---|
| `withReferenceIntegrityCheck` в `ReferenceCheckService` | Увидеть все ссылки и не удалить объект из-за скрытых строк | Только veto/список типов блокирующих ссылок | Типизирован (`REFERENCE_INTEGRITY_CHECK`), актор обязателен, аудируется, SQL ограничен `SELECT COUNT` |
| `withDimensionAdministration` в `RlsDimensionValueCatalog` | Построить полную матрицу грантов | Идентификатор, код и имя dimension value | Типизирован (`RLS_ADMINISTRATION`), разрешённые dimensions закрыты списком, SQL — только своя таблица, аудируется |
| `RlsContext.runAsSystem/callAsSystem` (без scope) | — | — | **Удалён:** shims бросают `UnsupportedOperationException` |
| Bootstrap/self-test/retention | Инициализация, проверка схемы, очистка telemetry | Только инфраструктурный результат | Demo seed ограничен профилем/property; schema self-test и telemetry retention остаются инфраструктурными операциями |

Отсутствие authentication не является разрешённым обходом: `requireAuthenticatedUsername`
отказывает для пустого субъекта и для `system`, а read-гейт возвращает false. Fallback
`CurrentUser -> "system"` больше не даёт authority.

## Переоценка после C2 (2026-09-12)

Метод: чтение кода enforcement-точек и реестров, инвентарь тестовых классов, сверка с
ранее зафиксированным логом полного gate. Пробелы подтверждения из первой ревизии
закрыты поведенческими тестами (см. Gate C2): `target/rls-gap-tests.log` —
21/21 по трём классам; актуальный полный `verify` `target/full-verify-c2f.log` —
`1049, 0 failures, 0 errors` (предыдущие подтверждения — `1040`/`1035`/`1030`/`1010`).

Шесть критических выводов C1 в текущем состоянии:

1. **«Защита call-site based, repository — параллельный вход» — закрыто.** Обязательная
   граница стоит на Spring Data repository для protected entity и на flush-time guard,
   а не в вызывающем сервисе.
2. **«Guard — detection-only» — остаётся.** Guard по-прежнему не блокирует запрос в
   production; deny есть только в платформенных путях и при misuse обхода.
3. **«Результат зависит от того, кто первым использовал session» — закрыто для
   платформенных каналов:** активация идемпотентна, пересчитывается при смене версии
   грантов, а аспект сам открывает границу перед protected repository.
4. **«Raw background threads» — закрыто:** единственный исполнитель —
   `ReportTaskExecutor`, приложение своих потоков не создаёт (архитектурное правило).
5. **«UI-проверка ADMIN не защищает сервис» — закрыто:** проверка перенесена на
   `AccessGrantAdminService`.
6. **«Политика секций должна стать явной metadata policy» — закрыто:**
   `SectionRlsPolicy.INHERIT_ROOT` проверяется при обращении к descriptor.
7. **«Read/write parity не сверяется для `custom`-измерений» — закрыто для read-предиката:**
   у сложной политики вывести предикат нечем, поэтому он объявляется явно
   (`@RlsDimension(readCondition = ...)`) и сверяется реестром с фактическим `@Filter`
   при старте, как у стандартных измерений. Отказом являются все четыре отклонения:
   пропущенный `readCondition`, разъехавшийся с фильтром, объявленный у CHECK_ONLY
   (фильтра нет) и объявленный у стандартного измерения (предикат выводится из
   `valuePaths`, объявленное было бы молча проигнорировано); семантика read↔write дополнительно сверяется на одних
   и тех же строках и грантах (`RlsIntegrationTest.receivingDocumentReadVisibilityMatchesWriteGuardPerRow`).
   Границы гарантии: проверяется равенство объявленного предиката исполняемому и
   совпадение видимого набора строк с набором, проходящим write-guard, — но не
   эквивалентность произвольного SQL самому `getRlsChecks()`.

### Первоначальный список разрывов (исторический)

| # | Что осталось | Тип | Куда |
|---|---|---|---|
| 1 | Guard в production остаётся detection-only | Отдельный pre-production hardening (`A4-PREPROD-RLS-GUARD`) | **Решение:** немедленный deny не включать; до production deny нужны измеренные метрики/алерты, inventory запросов и узкий allow-list служебных путей |
| 2 | Предикаты read остаются в `@Filter(condition = ...)` на сущности | Форма ADX-06 | Решение: «checked duplication» как end state либо генерация фильтров из descriptor |
| 3 | `DAC-17`, `DAC-19` и часть `DAC-15`/`DAC-13` не закрыты | Открытые пункты | Остаются в C2/C4/C5 по своим строкам |

### Актуальные решения C2

- `DAC-13` закрыт: application services не используют JPA API напрямую; REF lookup
  принимает только `AttributeType + id`, разрешает целевую сущность через managed
  catalog и читает её через RLS-aware `LookupService`.
- `DAC-17` закрыт превентивной архитектурной границей: прямые и meta-annotated
  `@Scheduled`/`@Async`, class-level `@Async` и фоновые executor/thread primitives
  запрещены в application namespace до появления typed job executor.
- `DAC-19` закрыт для demo initializer: регистрация требует `dev`/`demo`/`test`,
  отсутствия `prod` и `app.demo-data.enabled=true`; profile/property матрица проверяется
  независимо обнаруживаемыми integration tests.
- Read-condition checked duplication зафиксирован как конечное решение C2. Генерация
  фильтров из descriptor не входит в scope; parity проверяется при старте.
- Production guard остаётся detection-only. Немедленный deny не включается; переход
  рассматривается только отдельным pre-production gate `A4-PREPROD-RLS-GUARD`.

## Обязательный test grid для C2

Каждый защищённый тип проверяется через применимые каналы `DAC-01`–`DAC-13` и `DAC-20`.
Статус — по инвентарю тестовых классов на момент переоценки.

| Проверка | Состояние |
|---|---|
| нет grant | Покрыто (`RlsIntegrationTest.userWithNoGrantsSeesNothing`, `RlsReadGateTest.noEntityGrantMakesReadsEmpty...`) |
| grant на одну строку | Покрыто (`RlsIntegrationTest.aliceSeesOnlyHerJournal`) |
| wildcard grant | Покрыто (`adminWildcardSeesEverythingAndFilterStaysDisabled`) |
| CHECK_ONLY deny/allow | Покрыто (`noEntityGrantMakesReadsEmpty...`, `entityGrantUnblocksReads`, `checkOnlyDimensionNeverGetsHibernateFilterEnabled`) |
| read/write parity сложной (custom) политики | Покрыто: fail-fast на пропущенный и на разъехавшийся с `@Filter` `readCondition` (`RlsCustomDimensionParityTest` на фикстурах `rlsparity.*`), совпадение видимых строк с write-guard на одних грантах (`RlsIntegrationTest.receivingDocumentReadVisibilityMatchesWriteGuardPerRow`) |
| read/update/delete независимо | Покрыто (`canUpdateAndCanDeleteReflectGrantFlagsPerJournal`) |
| join, subquery, projection, count, pagination | Покрыто для отчётного канала (`SubqueryRlsIT`, `VisualQueryPackageRlsIT`, `CteHibernateFilterIT`) |
| нет authentication / неизвестный subject | Покрыто (`RlsUnauthenticatedAccessIT`: сервис, lookup, прямая граница repository и субъект `system` — все отказом) |
| динамическая REF-цель | Покрыто (`ManagedEntityCatalogTest`, `AttributeValueRefRlsIT`: только managed entity, RLS-aware lookup и отказ для недоступной строки) |
| application JPA boundary / фоновые примитивы | Покрыто (`ApplicationBoundaryTest`, `ApplicationBackgroundBoundaryTest`; включая meta-annotations и class-level `@Async`) |
| demo initializer profiles/property | Покрыто (`*Profile*IT`: production/unlisted profile и disabled property — бин отсутствует; разрешённые профили — бин есть и повторный запуск идемпотентен) |
| worker thread и переиспользование thread/session | Покрыто (`RlsStatementGuardTest.staleSessionMarkOnReusedThreadIsClearedAtRequestBoundary`: stale-метка молчит, граница `RlsGuardRequestFilter` снова вооружает канарейку) |
| cross-user доступ к report artifact | Покрыто (`ReportArtifactCacheTest.putAndGet`, `keyChangesWithUser`) |
| HTTP Basic REST preview без grant и с grant | Покрыто (`ReportJpqlPreviewControllerIT`) |
| разрешённый bypass с проверкой scope и audit | Покрыто (`RlsStatementGuardTest`, `RlsDeniedEventTest`) |

Новый канал считается поддержанным только после добавления строки в эту матрицу и
негативного channel test. Неизвестный канал для protected entity должен завершаться
отказом до SQL в платформенных API. Для произвольных SQL/JDBC вызовов production guard
остаётся detection-only до отдельного pre-production gate; deny не включается немедленно.

## Gate C2

Gate фиксируется как: целевой набор тестов каналов + архитектурные правила +
полный `verify`. C2 принимается с осознанным production guard detection-only; включение
deny перенесено в самостоятельный pre-production gate `A4-PREPROD-RLS-GUARD` и не
является незакрытым условием C2.

Целевые классы (в IntelliJ, JDK 21):

```text
mvn -Dtest='Rls*Test,Rls*IT,SubqueryRlsIT,VisualQueryPackageRlsIT,CteHibernateFilterIT,ReportQueryGuardTest,ReportParamResolutionIT,ReportJpqlPreviewControllerIT,JrxmlExecutionIT,ReportExecutionIT,ReportArtifactCacheTest,UiPersistenceBoundaryTest,PlatformArchitectureTest,EntityEventArchitectureTest' test
```

Историческое подтверждение полного gate на предыдущем срезе, обычный порядок:

```text
Tests run: 1049, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- Лог: `target/full-verify-c2f.log`. Перед прогоном и после него состав классов
  совпадал (`863` main / `370` test): без такого контроля прогон может быть испорчен
  параллельной сборкой в тот же `target` (прецедент — прогон с частичным
  `target/test-classes` и каскадом `NoClassDefFound`).
- Состав относительно `full-verify-c2d` (`1040`) объясним по классам: `+6` —
  `RlsCustomDimensionParityTest` (этот срез: пропущенный, разъехавшийся, лишний на
  стандартном измерении и лишний на CHECK_ONLY `readCondition`, плюс два положительных
  случая), `+1` — parity-тест в `RlsIntegrationTest` (тот же срез), `+2` —
  `EntityUpdateContextTest` (чужая незакоммиченная работа в этом же дереве, не этап C2).
- Предыдущие подтверждения: `target/full-verify-c2e.log` — `1048, 0, 0`;
  `target/full-verify-c2d.log` — `1040, 0, 0`;
  `target/full-verify-c2b.log` — `1030, 0, 0`; `target/full-verify-c2.log` — `1035, 0, 0`;
  базовое — `target/full-verify3.log`, `1010, 0, 0` на коммитах `8cd77b6` + `ec7bafa`.
- В прогоне присутствовали `RlsIntegrationTest`, `RlsRepositoryEnforcementIT`,
  `RlsStatementGuardTest`, `RlsUnauthenticatedAccessIT`, `RlsUiGateTest`, `RlsReadGateTest`,
  `RlsDeniedEventTest`, `SubqueryRlsIT`, `VisualQueryPackageRlsIT` и архитектурные тесты.
- Тестовые свойства включают `rls.guard.strict=true`, поэтому нарушения guard
  собирались, а не только логировались.
- Состав относительно `full-verify-c2b` (`1030`) объясним по классам: `+7` — этот срез
  (`ReportTaskExecutorTest` +5, `UiThreadingBoundaryTest` +2); `−3`/`+3` —
  `GenericOwnedSectionServiceIT` и `MetadataDrivenAggregateSaveServiceIT`, где
  параллельно с этим срезом появились тесты compositional guard (сам guard — чужая
  незакоммиченная работа в этом же дереве, не этап C2).
- Исторический random-order сбой: `ReportJpqlPreviewControllerIT` отдавал 403 вместо
  200/400 (`target/full-verify-random2.log`). Повторный C2 random-order gate на текущем
  дереве прошёл: `156` тестов, `0` failures/errors, seed `904469758400`; 403 не
  воспроизвёлся.
- Проверка после текущих исправлений (2026-09-13, Maven IntelliJ IDEA, JDK 21): целевой
  набор — `53` теста, `0` failures/errors. Расширенный random `verify` исключал отдельно
  отложенный `VisualQuerySubqueryIT`: `1079` тестов, `0` failures, `3` errors в
  `AttributeTypeServiceTest` при уже закрытом `GenericWebApplicationContext` (BUILD
  FAILURE). Первый прогон на стандартном размере контекстного кэша дал тот же симптом в
  `AttributeValueServiceTest` (23 ошибки); `spring.test.context.cache.maxSize=128`
  сократил их до трёх. Один прогон с лимитом 256 прошёл полностью (seed
  `2828632607900`), но повтор с тем же лимитом завершился 22 ошибками в
  `ReportQueryGuardTest`, `VisualQueryGuardIT`, `VisualQueryPackageDeepPathIT` и
  `ReportExecutionIT` (seed `3078094256100`), с тем же симптомом закрытого контекста.
  Изолированный `AttributeTypeServiceTest` прошёл `3/3`, парные прогоны с
  `NumberingEngineIT` также прошли. Увеличение кэша не считается исправлением: полный
  project gate пока нестабилен; отказ C2-функциональных проверок не обнаружен, но причина
  нестабильности контекста остаётся предметом отдельного разбора.

## Отдельный pre-production gate: A4-PREPROD-RLS-GUARD

Production guard остаётся detection-only до выполнения и подтверждения отдельного
pre-production gate. Немедленное включение deny не является частью C2. До переключения
в deny mode нужно подтвердить полноту telemetry/alerting на production-like нагрузке;
классифицировать нарушения и известные служебные запросы; зафиксировать узкий,
проверяемый allow-list; проверить его на PostgreSQL; выполнить staged rollout с
наблюдением и rollback-процедурой. До этого нарушения остаются видимыми, но не блокируют
production запросы.

## Порядок реализации C2

1. Единый `RlsPolicyDescriptor` и fail-closed subject resolution — **сделано**
   (`RlsPolicyDescriptor`, `@RlsDimension`, `requireAuthenticatedUsername`, удалённые
   untyped bypass).
2. Обязательный enforcement перед repository/query execution — **сделано для канала
   repository** (`RlsRepositoryEnforcementAspect` + flush-time write guard); guard
   оставлен независимой проверкой корректности и пока только детектирует.
3. Первый вертикальный срез `Journal` и `PrdSpec` — **сделано** (CRUD, lookup, custom
   search, global search, report projection).
4. `Branch`/`Workshop`, многомерный `ReceivingDocument`, owned rows и CHECK_ONLY —
   **сделано**, включая машинную сверку read-предиката `custom`-политики
   (`readCondition` + parity-тест).
5. Типизировать реальные bypass use case, добавить audit и server-side permissions —
   **сделано для RLS**; resource permissions остаются C5.
6. Архитектурный тест против repository/`EntityManager` в UI и protected application
   query вне boundary — **сделано для обоих UI-пакетов:** правило покрывает
   `org.ip.views..` и `org.ip.groupgrid..`, плюс отдельное правило запрещает `new Thread`
   в приложении (`UiThreadingBoundaryTest`); невакуумность обоих проверена мутацией.
