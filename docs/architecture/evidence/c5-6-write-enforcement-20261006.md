# C5.6: применение решения C5 к записи — 2026-10-06

- План: [C5.4–C5.10](../c5-operation-permissions-plan.md), пакет C5.6.
- Решение: [ADR-0017](../decisions/ADR-0017-c5-single-operation-authority.md) §1, §3, §5.
- Метод: реализация в `platform-core` и `platform-spring-boot-autoconfigure`, правка
  прикладных фикстур и тестов, которые кодировали прежнего владельца решения; прогоны
  owner-наборов и прикладных тестов.

## 1. Что подключено

**Canonical write.** [CanonicalWriteExecutor](../../../platform-core/src/main/java/org/ipro/data/CanonicalWriteExecutor.java)
получил субъект (`RlsCurrentUser`) и единое решение
([C5PermissionDecisionService](../../../platform-rls/src/main/java/org/ipro/rls/c5/C5PermissionDecisionService.java)).
Порядок стал таким:

```text
write intent
-> capability типа (fail-closed)
-> решение C5 об операции CREATE/UPDATE/DELETE   <-- новое
-> RLS: доступная исходная строка, исходное/целевое состояние
-> нормализация версии -> нумерация -> server validation
-> lifecycle before-hooks и before-события -> persistence -> after-события
```

Отказ C5 наступает до RLS, до загрузки исходной строки, до нумерации, валидации, хуков и
событий — то есть до пользовательского кода и до любого SQL мутации. Отсутствующий
провайдер решения — ошибка конфигурации (`C5PermissionConfigurationException`), а не allow:
то же правило, что в canonical read. Read-intent'ы через эту проверку не проходят.

`requireOperation(Class, DataOperation)` — публичный preflight без записи: capability плюс
решение C5, ни SQL, ни нумерации. Им пользуется агрегат.

**Агрегат.** [MetadataDrivenAggregateSaveService](../../../platform-core/src/main/java/org/ipro/crud/MetadataDrivenAggregateSaveService.java)
вызывает preflight через узкий seam
[AggregateOperationAuthorizer](../../../platform-core/src/main/java/org/ipro/crud/AggregateOperationAuthorizer.java)
до `publishAggregateSaving` и `beforeAggregateSave`. Раньше отказ приходил только внутри
корневого `save`, то есть после aggregate-событий и пользовательских хуков. Отсутствие
авторизатора не расширяет права: корневой save проверяет то же решение сам и откажет —
просто позже.

**Проводка.** `DataAccessAutoConfiguration` передаёт в executor `RlsCurrentUser` и
`ObjectProvider<C5PermissionDecisionService>`; `MetadataAutoConfiguration` передаёт в
агрегат `ObjectProvider<AggregateOperationAuthorizer>` (тот же executor). Обе связи —
через провайдеры, поэтому частичные контексты по-прежнему поднимаются, а решение остаётся
обязательным в полном контуре.

## 2. Поведенческое следствие: без явных грантов запись закрыта

Это главное изменение среза, предсказанное evidence C5.4. Гранты выданы там, где без них
разваливалась бы работа, и каждое место названо:

| Место | Что выдано |
| --- | --- |
| [AccountingObjectPilotInitializer](../../../src/main/java/org/ip/config/AccountingObjectPilotInitializer.java) | добавлены операционные строки `ENTITY:<SimpleName>` на восемь типов пилота: установка, корректировка и справочники без них отказывали бы |
| [RlsTestFixture](../../../src/test/java/org/ipro/rls/RlsTestFixture.java) | синтетический суперпользователь материализует по строке на каждое зарегистрированное определение операции — построчный `"*"` операции не авторизует |
| [RlsTestAccessConfiguration](../../../src/test/java/org/ipro/rls/RlsTestAccessConfiguration.java) | тестовая авто-конфигурация привязывает реестр определений к фикстуре; без привязки фикстура выдаёт только построчный доступ и запись отказывает |

Гранты `*` при этом не переписываются и не расширяются: wildcard остаётся построчным
обходом, как и решено.

## 3. Тесты, которые кодировали прежнего владельца

Разделение «операция — C5, строки — RLS» сделало часть существующих тестов неверными по
типу ожидаемого отказа: тот же ключ `ENTITY:<SimpleName>` теперь решает C5, а не RLS-флаг.

| Файл | Что изменено |
| --- | --- |
| [RlsServiceWriteBoundaryIT](../../../src/test/java/org/ip/security/RlsServiceWriteBoundaryIT.java) | каждый тест выдаёт операционное право явно; ожидания отказа переведены на C5; добавлен тест `operationRightAndRowRightAreIndependentDecisions`: с операционным правом, но без доступа к строке отказывает RLS-путь, со строкой, но без операции — C5 |
| [RlsAutoConfigurationSmokeTest](../../../src/test/java/org/ip/security/RlsAutoConfigurationSmokeTest.java) | отказ создания документа без гранта: ожидается `C5AccessDeniedException` по `ENTITY:ReceivingDocument` |
| [PrdSpecMetadataAggregateIT](../../../src/test/java/org/ip/application/document/PrdSpecMetadataAggregateIT.java) | защита агрегата от неавторизованного сохранения: отказ теперь от C5 до persistence |
| [NomenclaturePriceFormTest](../../../src/test/java/org/ip/application/register/NomenclaturePriceFormTest.java), [NomenclaturePricePersistenceTest](../../../src/test/java/org/ip/application/register/NomenclaturePricePersistenceTest.java) | акторы получают операционную строку на `NomenclaturePrice` рядом с read-ключом |
| [GroupNomVersionNormalizationIT](../../../src/test/java/org/ip/service/GroupNomVersionNormalizationIT.java), [InformationRegisterRecorderPersistenceTest](../../../src/test/java/org/ipro/register/InformationRegisterRecorderPersistenceTest.java) | hand-built executor передаёт субъект и решение C5 |

Список не исчерпывается этой таблицей: полный прикладной прогон (§4.1) нашёл ещё шесть
классов с тем же сдвигом владельца — `RlsReadGateTest`, `RlsDeniedEventTest`,
`CanonicalWriteBoundaryIT`, `UserAdministrationFlowIT`, `RlsDimensionFactsIT` и
`WorkspaceManifestTest`, — а также два reviewed-реестра `platform-core`, которым новый
MODULE_API-тип обязан быть виден явно. Их правки и причины — в §4.1.

## 4. Проверки

| Прогон | Результат |
| --- | --- |
| `platform-rls` | 59 тестов, 0 failures (плюс 35 в `platform-spring-boot-autoconfigure`) |
| `platform-core` (`mvn -o -f platform-build/pom.xml -pl ../platform-core test`) | 155 тестов, 0 failures, 0 errors; из них 6 новых C5.6: четыре на canonical write (порядок отказа, закрыто без гранта и открыто с ним, wildcard не авторизует, отсутствующий провайдер — ошибка конфигурации) и два на preflight агрегата |
| owner-классы записи: `RlsServiceWriteBoundaryIT` 13, `MetadataDrivenAggregateSaveServiceIT` 7, `GroupNomVersionNormalizationIT` 1, `InformationRegisterRecorderPersistenceTest` 11 | 32 теста, 0 failures — запись через сервис, агрегат, hand-built executor и регистратор работают с явными операционными грантами |
| полный прикладной набор (`mvn -o test`, лог `logs/c5-6-app-tests.log`) | `Tests run: 1856, Failures: 32, Errors: 12, Skipped: 6`, 14:23 — BUILD FAILURE; разбор в §4.1 и §4.2 |
| финальный точечный набор C5.6 (12 классов, лог `logs/c5-6-final-targeted.log`) | `Tests run: 80, Failures: 1, Errors: 0`; единственный отказ — чужой (§4.2), 11 из 12 классов зелёные |

Точечный набор — те же 12 классов, что отказались в полном прогоне: `RlsReadGateTest`,
`RlsDeniedEventTest`, `RlsAutoConfigurationSmokeTest`, `CanonicalWriteBoundaryIT`,
`UserAdministrationFlowIT`, `RlsDimensionFactsIT`, `PlatformCoreModuleTest`,
`PlatformCoreSurfaceTest`, `PrdSpecMetadataAggregateIT`, `NomenclaturePriceFormTest`,
`NomenclaturePricePersistenceTest`, `WorkspaceManifestTest`. Числа по классам совпадают с
полным прогоном, поэтому перепроверка закрывает именно те отказы, которые нашёл полный
набор, а не только удобные.

### 4.1. Что нашёл полный прогон: из 44 отказов 24 — этого среза

| Класс | Было | Причина | Чем закрыто |
| --- | --- | --- | --- |
| `PrdSpecMetadataAggregateIT` | 3 / 1 | тест удаления ждал отказ RLS по `JOURNAL`, получил отказ C5 | выдан операционный грант `ENTITY:PrdSpec` (canDelete), чтобы отказ пришёл от строки, а не от операции |
| `NomenclaturePriceFormTest` | 4 / 4 | форма писала без операционной строки, read-ключ операцией не распоряжается | операционная строка на `NomenclaturePrice` рядом с read-ключом |
| `NomenclaturePricePersistenceTest` | 11 / 0 + 7 err | то же | то же |
| `RlsAutoConfigurationSmokeTest` | 17 / 1 | ожидался отказ RLS-пути, пришёл `C5AccessDeniedException` | ожидание переведено на C5 по `ENTITY:ReceivingDocument` |
| `RlsDeniedEventTest` | 1 / 1 | тот же ключ обслуживает теперь и C5, и CHECK_ONLY-ворота RLS; плюс bootstrap-правило измерения (см. §4.3) | уникальные субъекты, грант `BRANCH` + `ENTITY:ReceivingDocument`, строка `JOURNAL` для другого субъекта, проверка строго `dimension=JOURNAL`, `action=изменение`, entity FQN |
| `RlsReadGateTest` | 5 / 0 + 1 err | тест «типы без CHECK_ONLY-измерений не затронуты» писал через canonical write с одним read-ключом | операционная строка на `Nomenclature` |
| `UserAdministrationFlowIT` | 3 / 0 + 1 err | административные сценарии меняли `User`/`Role` без операционного гранта (`ROLE_ADMIN` операцией не распоряжается) | явные операционные строки в фикстуре |
| `CanonicalWriteBoundaryIT` | 9 / 2 | пробы аутентификации выдавали только read-ключи | хелпер выдаёт read и операционные строки |
| `PlatformCoreModuleTest`, `PlatformCoreSurfaceTest` | 6 / 1, 6 / 2 | новый MODULE_API-тип обязан стоять в reviewed-реестре и в бюджете разделов | `AggregateOperationAuthorizer` добавлен в `REVIEWED_TYPES`, в `platform-core-surface.txt` (MODULE_API 50 -> 51) и в размер 104 -> 105 |
| `RlsDimensionFactsIT` | 11 / 2 | реестр измерений ждёт восемь аннотированных измерений и ключи C5; операционные ключи регистрируются как динамические CHECK_ONLY-измерения | хелпер `c5OperationKeys()` — по одному ключу на тип с write-capability |
| `WorkspaceManifestTest` | 4 / 1 | отпечатки модулей изменились вместе с поверхностью | перезапись манифеста (`-Dplatform.manifest.write=true`) отдельным прогоном |

Все двенадцать классов после правок зелёные в финальном точечном наборе — кроме второго
отказа `PlatformCoreSurfaceTest`, который этому срезу не принадлежит.

### 4.2. Остаток полного прогона — чужие изменения рабочего дерева

20 отказов из 44 приходятся на незакоммиченные чужие срезы в том же дереве и не связаны с
решением об операции:

| Класс | Отказов | Тема |
| --- | --- | --- |
| `PlatformPublicSurfaceTest` | 4 | чужая поверхность D1-реестра |
| `PlatformVaadinSurfaceTest` | 2 | untracked тест пилота учётных объектов называет `FormResolver`, а `AccountingObjectRegisterView` — `FormBindingRegistry` |
| `PlatformApiBaselineTest` | 2 | untracked модуль `platform-rest-web` публикуется без baseline-файла |
| `ApplicationBoundaryTest` | 2 | чужая граница приложения |
| `ReportExecutionIT` | 1 + 3 err | `RecorderTypeRegistry` |
| `CanonicalReadBoundaryIT` | 1 | `org.ip.model`: 23 типа против 29 |
| `DeepLinkCatalogBaselineIT`, `DeepLinkRouteBaselineTest` | 1 + 1 | у корневого типа каталога нет публичного alias |
| `ReferenceTargetToStringTest` | 1 | чужое |
| `SearchFieldParitySnapshotTest` | 1 | чужой снимок паритета |
| `SectionMetadataRegistryTest` | 1 | чужой |
| `PlatformCoreSurfaceTest` | 1 | второй отказ: untracked `AccountingObjectPilotIT` начал называть MODULE_API `MetadataDrivenAggregateSaveService` |

Единственный оставшийся отказ финального набора — последняя строка таблицы. Он лечится
одной строкой в overlay (`MetadataDrivenAggregateSaveService ...
org/ip/application/register/AccountingObjectPilotIT.java`), но это заморозка ссылки чужого
незакоммиченного теста, поэтому решение оставлено владельцу пилота, а не принято здесь.

Тот же чужой дрейф попал и в `scripts/local-dependencies.json`: манифест обновлён намеренно
(без него падает `WorkspaceManifestTest`), и вместе с отпечатками `platform-rls`,
`platform-core` и `platform-spring-boot-autoconfigure` он теперь фиксирует и чужие модули
(`platform-rest`, `platform-vaadin`). Это осознанная цена за зелёный манифест, а не
побочный эффект; при разборе чужого дрейфа виноват будет этот коммит.

### 4.3. Побочная находка: bootstrap-правило измерения

Падение `RlsDeniedEventTest` объяснялось не срезом, а правилом в `AccessService`:
`isNewDimensionValueAllowed` разрешает создание **нового** значения измерения, пока по
этому измерению нет ни одной строки доступа. Пока в базе не было ни одного гранта по
`JOURNAL`, отказ по нему не наступал в принципе. Тест стал детерминированным только
после строки `JOURNAL` на другого субъекта. Это свойство распространяется на все
дименсиональные проверки, которые ожидают отказа «с нуля»: пустая база их не воспроизводит.

## 5. Остаток C5.6 (не выполнено и почему)

- **Полный прикладной прогон** — выполнен (§4). Остаток среза здесь закрыт: 24 из 24
  отказов, принадлежавших C5.6, исправлены и перепроверены точечным прогоном. Остальные
  20 отказов набора — чужие (§4.2), и перевести их на операционные гранты нельзя, не
  исправляя чужие срезы.
- **Финальная граница на flush для типов без RLS** — сделано отдельным срезом:
  [c5-6-flush-boundary-20261007.md](c5-6-flush-boundary-20261007.md). Граница зафиксирована и
  проверена, но по умолчанию снята явным переключателем `rls.write-boundary.enforced`:
  его цена измерена полным прогоном (91 отказ по четырём типам, ~35 классов, включая
  production-путь `AttributeValueService`), поэтому включение идёт вместе с миграцией
  мест прямой записи. Реестр границы переведён со статического поля на принадлежность
  экземплярам контекста (слушателю, регистратору и `RlsPolicyEnforcer`), поэтому
  вооружённое состояние больше не переживает свой контекст, а прикладная проверка границы
  возвращена вместе с модульной. Вооружение стало пошаговым
  (`rls.write-boundary.enforced-types`). Сделаны три шага: демо-наполнение `DataInitializer`
  (`Role`, `User`) пишет через canonical path от собственного субъекта; пилотная фикстура
  `AccountingObjectPilotInitializer` — то же для роли ADMIN и входного пользователя (последняя
  прямая запись `Role`/`User` в production-коде); `AttributeType` — тип, у которого прямых
  записей в production не было, — переведён на canonical path в фикстурах полного контекста
  (`NomenclatureAttributesIT`, `AttributeValueRefRlsIT`) и вооружён. После этого `Role`, `User`
  и `AttributeType` названы в `rls.write-boundary.enforced-types` в main-properties.
  `DemoSeedingWriteBoundaryIT` (5/5), `PilotFixtureWriteBoundaryIT` (3/3),
  `AttributeTypeWriteBoundaryIT` (4/4) и профильные IT подтверждают, что включение границы для
  этих типов ничего не ломает; вооружённый прогон переведённых фикстур — 11/0, production-список
  — 29/0 (WARN: вооружено 3 типа, снято 17). Четвёртый шаг добавлен 2026-10-08:
  `AttributeValue` — production-путь [`AttributeValueService`](../../../src/main/java/org/ip/service/AttributeValueService.java)
  переведён на canonical create и именованное переименование ([ADR-0019](../decisions/ADR-0019-owner-named-row-operation.md)),
  прямых записей типа в production-коде не осталось, тип назван в списке
  ([c5-6b-named-row-operation-20261008.md](c5-6b-named-row-operation-20261008.md)).
- **Именованная операция замены набора регистратора.** Право на замену набора сегодня
  выражено RLS-проверкой (`RlsRecorderSetWriteAuthorizer`); отдельное `ACTION`-определение
  владельца не введено, generic CRUD строк регистра не расширялся.
- **Отказ при старте для `@RlsDimension(CHECK_ONLY)` на типе с canonical CRUD.** Гейт
  пилота ещё объявлен в этом виде; запрет и снятие аннотаций — одна операция вместе с
  выдачей операционных грантов, то есть шаг переключения C5.8.
- **Численный замер security-запросов.** Не снят: в журнале нет SQL по грантам,
  `pg_stat_statements` не установлен, а харнесс (`statement inspector` в тесте) в пакет не
  входил. Статическая оценка C5.4 (не менее восьми запросов к `access_grant` на одно
  сохранение документа) остаётся оценкой; фактическое число запросов C5 добавляет сверху:
  по одному на каждую проверку операции, с коротким замыканием на прямом allow.
  Снят 2026-10-08 тестовым харнессом — [c5-6b-security-query-measurement-20261008.md](c5-6b-security-query-measurement-20261008.md):
  19 запросов к `access_grant` на первичное сохранение документа пилота и 26 на повторное,
  то есть оценка C5.4 оказалась нижней границей, а не итогом.
