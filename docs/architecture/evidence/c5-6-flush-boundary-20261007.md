# C5.6: финальная граница записи для типов без RLS — 2026-10-07

- План: [C5.4–C5.10](../c5-operation-permissions-plan.md), пакет C5.6.
- Решение: [ADR-0017](../decisions/ADR-0017-c5-single-operation-authority.md) §9 (и §3, §7, отклонённый вариант
  «пересмотр точки авторизации на flush»).
- Предыдущий срез: [c5-6-write-enforcement-20261006.md](c5-6-write-enforcement-20261006.md)
  (§5 называл эту границу остатком).
- Метод: реализация в `platform-rls`, `platform-core` и проводке
  `platform-spring-boot-autoconfigure`, owner-тесты, измерение цены включения полным
  прикладным прогоном, затем стейджинг переключателя.
- Дополнение того же дня: реестр границы переведён с статического поля на принадлежность
  экземплярам контекста, поэтому прикладная проверка вооружённого состояния возвращена (§4,
  §5), а публичная поверхность `platform-rls` переписана в baseline намеренно.

## 1. Что было открыто

`RlsWriteGuardBridge.requireAuthorized` выходил сразу при `!policy.protectedEntity()`. Для
типа с canonical write handle и без измерений RLS (`UnitOfMeasurement`, `User`, `Role`,
`AttributeType`, `AttributeValue`, …) это означало: **финальной проверки нет вообще**. Ловил
только сам canonical write, а прямой `EntityManager`, Spring Data repository в обход
`RlsRepositoryEnforcementAspect` (он тоже выходит по `!protectedEntity()`) и commit-time
dirty checking записывали такую сущность без решения о правах. ADR-0017 §9 требовал ровно
обратного: зафиксированная на preflight авторизация потребляется на flush **для всех** типов
с write-capability, тем же механизмом, что RLS применяет сегодня.

## 2. Что сделано

**Одна граница, две ветви.** Запись и потребление остались в существующем журнале
одноразовых авторизаций `RlsWriteAuthorization` (ключ: тип + id, для transient — тождество
объекта), поэтому обе ветви наследуют и одноразовость, и снятие авторизации на
`afterCompletion` транзакции. Новая ветка добавлена в
[RlsWriteGuardBridge](../../../platform-rls/src/main/java/org/ipro/rls/RlsWriteGuardBridge.java):

```text
requireAuthorized(registry, entity, operation)
-> protectedEntity()            -> потребляется авторизация со снимком проверок строк (как было)
-> isWriteGoverned(type)        -> потребляется авторизация canonical write (новое)
-> иначе                        -> путь владельца (агрегат/внутренний store) не затронут
```

**Реестр — принадлежность экземпляра, а не статика.** `requireAuthorized` получает реестр
параметром; его держат владельцы: [RlsHibernateWriteGuardInstaller](../../../platform-rls/src/main/java/org/ipro/rls/RlsHibernateWriteGuardInstaller.java)
создаёт [RlsWriteEnforcementListener](../../../platform-rls/src/main/java/org/ipro/rls/RlsWriteEnforcementListener.java)
с тем же экземпляром реестра, что и контекст, а фиксирует авторизацию
[RlsPolicyEnforcer](../../../platform-rls/src/main/java/org/ipro/rls/RlsPolicyEnforcer.java).
Статической осталась только журнальная запись транзакции `RlsWriteAuthorization` — она по
природе принадлежит потоку и транзакции, а не контексту. Прежний статический
`RlsWriteGuardBridge.install(registry)` убран: вооружённый контекст больше не решает за все
последующие контексты той же JVM.

**Кто называет тип предметом границы.** Новый факт в
[RlsDimensionRegistry](../../../platform-rls/src/main/java/org/ipro/rls/RlsDimensionRegistry.java):
`registerWriteGovernedType` / `isWriteGoverned`. Регистрацию делает тот же
`C5EntityOperationPermissionRegistrar`, который уже выводит операционные ключи C5 из
`descriptor.capabilities().writes()`: один факт — одно место объявления, тип без canonical
handle (`OWNED_ROW`, `INTERNAL_STORE`) в границу не попадает и владельческий путь записи
сохраняет.

**Кто фиксирует авторизацию.** [CanonicalWriteExecutor](../../../platform-core/src/main/java/org/ipro/data/CanonicalWriteExecutor.java)
после уже принятого решения об операции (`capability` → C5 → RLS) вызывает
`RlsPolicyEnforcer.recordCanonicalWrite/recordCanonicalDelete` — enforcer здесь только точка
доступа к тому же реестру, что у слушателя, а сама ветвь живёт в мосте. Для типа с RLS
запись пропускается: снимок проверок строк там неизвестен, а пустой снимок отклонял бы
штатную запись — это отдельно закреплено тестом. Метод безопасен для любого типа:
незащищённый и неведомый тип он просто пропускает, поэтому вызов стоит без ветвлений в
вызывающем коде.

**Почему запись не встроена в `requireUpdate`.** Вызов вынесен отдельным методом, а не
спрятан внутри `requireUpdate`: тот же `requireUpdate` зовут repository-aspect и
application-сервисы, и встроенная запись дала бы авторизацию границы из чужого места — ровно
тот обход, который решается этим срезом. Запись canonical write фиксирует только canonical
preflight.

Стоимость на горячем пути: одно чтение `@RlsDimension`-политики из уже собранной карты и, для
governed-типа, запись в `ThreadLocal`-журнал. SQL не добавляется — в отличие от решения
«перепроверять C5 на flush», которое ADR-0017 отклоняет отдельным пунктом.

## 3. Цена включения — измерена, а не предположена

Полный прикладной прогон с вооружённой границей (`rls.write-boundary.enforced=true`). Измерение
снято до переноса реестра на экземпляры, но семантика границы тем изменением не затронута
(те же две ветви, тот же журнал), поэтому цена включения та же:

| Прогон | Результат |
| --- | --- |
| с вооружённой границей (`logs/boundary-app-tests.log`) | `Tests run: 1856, Failures: 18, Errors: 123, Skipped: 6` |
| baseline (без границы, `logs/c5-6-app-tests.log`) | `Tests run: 1856, Failures: 32, Errors: 12, Skipped: 6` |

Отказ границы встретился 91 раз, и все — по четырём типам:

| Тип | Отказов | Кто писал напрямую |
| --- | --- | --- |
| `org.ip.model.UnitOfMeasurement` | 67 | `setUp` фикстур тестов (`entityManager.persist`) |
| `org.ip.model.User` | 12 | демо-наполнение `DataInitializer`, фикстуры пользователей |
| `org.ip.model.AttributeType` | 9 | фикстуры справочника атрибутов, легаси-сервис |
| `org.ip.model.Role` | 3 | демо-наполнение `DataInitializer` |

**Этот замер — фронт, а не итог (уточнено 2026-10-08).** Многие фикстуры падали уже в `setUp`
на `UnitOfMeasurement`/`User`/`Role`, поэтому записи следующих типов в тех же тестах не
исполнялись и в таблицу не попали. Повторный замер (19 типов, `UnitOfMeasurement` снят
намеренно) показал следующий слой: `Nomenclature` 68, `GroupNom` 18, `AttributeValue` 8,
`GridFormView` 1 — см. §10. Вывод о порядке работы не меняется: объём берётся из замера, а
следующий слой виден только после перевода предыдущего.

Новых отказов — около 35 классов, среди них `NomenclaturePrice*` (5 классов),
`RestRead*` (6), `FetchPlan*` (3), `AttributeValueRefRlsIT`, `NomenclatureAttributesIT`,
`C5GrantAdminParityIT`, `ReportJpqlPreviewControllerIT`, `NumberingEngineIT`,
`EntityChangeLogH2CompatibilityIT`, `PersistenceRegistrationIT`, `DetachedRenderCostIT`,
`FormCoordinatorScopeGuardTest`, `EventContourWiringIT`, `GenericOwnedSectionServiceIT`,
`MetadataDrivenAggregateSaveServiceIT`, `CanonicalWriteBoundaryIT`, и три профильных
`Demo|Dev|TestProfileWithPropertyIT`.

**Почему это не «просто починить тесты».** Один из отказов — прикладной production-путь:
[AttributeValueService](../../../src/main/java/org/ip/service/AttributeValueService.java)
пишет `AttributeValue` через `attributeValueRepository.save(...)`, то есть у живого
пользователя при включении откажет сохранение атрибутов, а не только тест. Демо-наполнение
[DataInitializer](../../../src/main/java/org/ip/config/DataInitializer.java) закрыть bypass'ом
нельзя: `RlsBypassScope` — закрытый перечень из четырёх причин, а публичный
`RlsContext.runAsSystem` для приложения намеренно бросает исключение. Значит, включение
границы — это шаг миграции мест прямой записи на canonical path (C5.8 плана: «первичная выдача
грантов вместе с включением enforcement»), а не смена одного флага.

## 4. Стейджинг: как включено сейчас

Переключатель назван явно и печатается при старте — молчаливого «флага нет, значит всё
хорошо» нет:

- `rls.write-boundary.enforced` (default `false`) — читается в
  [DataAccessAutoConfiguration](../../../platform-spring-boot-autoconfigure/src/main/java/org/ipro/data/config/DataAccessAutoConfiguration.java)
  и передаётся в регистратор; при `false`
  `C5EntityOperationPermissionRegistrar` печатает WARN со **списком** типов с canonical write
  handle, которые границей ещё не накрыты, при `true` — WARN с числом вооружённых типов;
- `rls.write-boundary.enforced-types` (default пусто) — пошаговое вооружение: типы
  (полные имена классов), для которых граница обязательна уже сейчас (§7). Имя без canonical
  write handle останавливает старт; WARN печатает и вооружённые, и снятые типы;
- свойство записано в `application.properties` и в тестовом `application.properties` с
  объяснением цены включения;
- вооружённое состояние проверяется не сменой общего профиля, а отдельными тестами:
  [CanonicalWriteFlushBoundaryIT](../../../platform-core/src/test/java/org/ipro/data/CanonicalWriteFlushBoundaryIT.java)
  (`@DataJpaTest` с настоящим `RlsHibernateWriteGuardInstaller`) и тестом переключателя в
  `C5EntityOperationPermissionRegistrarTest`.

**Вооружённая проверка вернулась в приложение.** Пока реестр границы был статическим,
прикладной `@SpringBootTest` с вооружённым свойством создавал второй контекст в той же JVM,
и после него статический реестр оставался вооружённым для чужих контекстов — на пробном
прогоне это дало ровно один лишний отказ (`RlsAutoConfigurationSmokeTest` записал `Role`
напрямую и получил отказ границы из чужого контекста), и прикладная проверка была удалена.
Теперь реестр принадлежит экземплярам контекста, поэтому
[CanonicalWriteFlushBoundaryIT](../../../src/test/java/org/ip/security/CanonicalWriteFlushBoundaryIT.java)
в приложении снова вооружён (`@SpringBootTest(properties = "rls.write-boundary.enforced=true")`),
а состояние не выходит за границы своего контекста. Уровень модуля остаётся как более узкая
проверка настоящего Hibernate flush.

Миграционный чек-лист для включения (по измерению §3): места прямой записи
`UnitOfMeasurement`/`User`/`Role`/`AttributeType`, демо-`DataInitializer`,
`AttributeValueService` и остальные легаси-сервисы с `repository.save(...)`, затем сами
фикстуры тестов. `User`, `Role` и `AttributeType` из этого списка закрыты (§7), поэтому
`enforced=true` ждёт `UnitOfMeasurement`, `AttributeValueService` и фикстур. Только после этого
`enforced=true` становится значением по умолчанию, а свойство — удаляется.

## 5. Проверки

| Прогон | Результат |
| --- | --- |
| [RlsWriteGuardBridgeTest](../../../platform-rls/src/test/java/org/ipro/rls/RlsWriteGuardBridgeTest.java) (owner) | 7 тестов, 0 failures: отказ без авторизации, одноразовость, различение `WRITE`/`DELETE`, сохранение владельческого пути, «авторизация canonical write не подменяет строковую проверку», инертность при `null`-реестре |
| [CanonicalWriteFlushBoundaryIT](../../../platform-core/src/test/java/org/ipro/data/CanonicalWriteFlushBoundaryIT.java) (настоящий flush, уровень модуля) | 4 теста, 0 failures: `create` canonical path проходит и потребляет авторизацию, прямой `persist` отклонён (`RlsAccessDeniedException`), `update` через canonical path проходит, а второе изменение того же объекта в той же транзакции отклонено на flush, `delete` через canonical path проходит |
| [CanonicalWriteFlushBoundaryIT](../../../src/test/java/org/ip/security/CanonicalWriteFlushBoundaryIT.java) (новый, приложение, вооружён) | 5 тестов, 0 failures: canonical `create`/`update`/`delete` `UnitOfMeasurement` проходят границу, прямой `persist` отклонён («canonical write preflight»), dirty change в новой транзакции отклонён на flush, прямой `remove` отклонён на commit-time flush (`TransactionSystemException` → `RlsAccessDeniedException`) |
| прикладной класс + `RlsAutoConfigurationSmokeTest` в одной JVM | 5 + 17, 0 failures — после вооружённого класса соседний контекст пишет `Role` без чужого отказа, то есть утечки статического состояния больше нет |
| `C5EntityOperationPermissionRegistrarTest` (расширен) | 4 теста, 0 failures: снятый переключатель оставляет тип вне границы, выдача операционного ключа от него не зависит, вооружённый — набирает только типы с write-capability |
| [DemoSeedingWriteBoundaryIT](../../../src/test/java/org/ip/config/DemoSeedingWriteBoundaryIT.java) (новый, пошаговое вооружение) | 5 тестов, 0 failures: демо-данные на месте при границе, вооружённой только для `Role`/`User`, неназванный тип снят, прямой `persist` роли и пользователя отклонён (`canonical write preflight`), canonical path субъекта seeding открыт и после старта |
| `Test|Dev|DemoProfileWithPropertyIT` (реально запускают `DataInitializer`) | 1 + 1 + 1, 0 failures; отдельный прогон `TestProfileWithPropertyIT` с `-Drls.write-boundary.enforced=true` (WARN: 20 типов) — тоже 0 failures, то есть мигрированное место переживает и глобальное вооружение |
| `C5EntityOperationPermissionRegistrarTest` после добавления пошагового режима | 6 тестов, 0 failures: прежние четыре + «вооружён только названный тип» и «имя без canonical write handle — отказ старта» |
| [PilotFixtureWriteBoundaryIT](../../../src/test/java/org/ip/config/PilotFixtureWriteBoundaryIT.java) (новый, пошаговое вооружение) | 3 теста, 0 failures: пилотная фикстура проходит вооружённую для `Role`/`User` границу (ADMIN, входной пользователь с ролью), неназванный тип снят, canonical path субъекта открыт и после наполнения |
| [AttributeTypeWriteBoundaryIT](../../../src/test/java/org/ip/application/catalog/AttributeTypeWriteBoundaryIT.java) (новый, пошаговое вооружение) | 4 теста, 0 failures: вооружён ровно `AttributeType`; canonical `create`/`update` проходят; форма типа атрибута сохраняется через настоящий `ItemFormSaveDispatcher`; прямой `persist` отклонён (`canonical write preflight`) |
| мигрированные фикстуры, прогон с `-Drls.write-boundary.enforced-types=org.ip.model.AttributeType` | `NomenclatureAttributesIT` 9/0 + `AttributeValueRefRlsIT` 2/0: полные контексты больше не пишут `AttributeType` напрямую, граница для них не мешает |
| slice-контексты, тот же вооружённый флаг | `AttributeTypeLifecycleTest` 4/0, `JpaAuditingSliceContractTest` 2/0, `AttributeValueServiceTest` 24/0, `SklNomOpaServiceTest` 16/0: `@DataJpaTest` границу не поднимает (WARN регистратора в логах срезов отсутствует), поэтому прямые записи там — иной контекст, а не обход |
| production-список, прогон с `-Drls.write-boundary.enforced-types=org.ip.model.AttributeType,org.ip.model.Role,org.ip.model.User` | WARN подтверждает «вооружено 3 типа, снято 17»; `AccountingObjectPilotIT` 18/0 + `NomenclatureAttributesIT` 9/0 + `AttributeValueRefRlsIT` 2/0 |
| прикладной RLS-набор после батча | 103 теста (`AccountingObjectPilotIT` 18, `RlsAutoConfigurationSmokeTest` 17, `RlsIntegrationTest` 23, `RlsServiceWriteBoundaryIT` 13, `CanonicalWriteBoundaryIT` 9, `RlsRepositoryEnforcementIT` 8, `RlsUiGateTest` 4, `RlsReadGateTest` 5, `RlsDeniedEventTest` 1, `PlatformRlsModuleTest` 5), 0 failures |
| четыре вооружённых класса в одной JVM | `AttributeTypeWriteBoundaryIT` 4/0, `DemoSeedingWriteBoundaryIT` 5/0, `PilotFixtureWriteBoundaryIT` 3/0, `CanonicalWriteFlushBoundaryIT` 5/0 — состояние вооружения по-прежнему не переживает свой контекст |
| полный прикладной набор после батча (`logs/c5-6-batch-full-app.log`) | `Tests run: 1887, Failures: 20, Errors: 3, Skipped: 6` против `1856 / 21 / 3 / 6` на прогоне до батча: ни один класс не покраснел от этих правок, два прежних чужих отказа ушли (`WorkspaceManifestTest`, `ReportExecutionIT`), три чужих добавились (`ReportFormSeamTest`, `CteHibernateFilterIT`, `SubqueryRlsIT` — работа по регистрам/CTE вне этого батча). Все классы батча зелёные: `AttributeTypeWriteBoundaryIT` 4, `PilotFixtureWriteBoundaryIT` 3, `DemoSeedingWriteBoundaryIT` 5, `CanonicalWriteFlushBoundaryIT` 5, `NomenclatureAttributesIT` 9, `AttributeValueRefRlsIT` 2, `AccountingObjectPilotIT` 18 |
| overlay `test-usage` (осознанное расширение) | В `platform-core-surface.txt` добавлен файл `PilotFixtureWriteBoundaryIT` к `MetadataDrivenAggregateSaveService`, в `platform-vaadin-surface.txt` — `AttributeTypeWriteBoundaryIT` к `FormResolver`. Оба — реальные швы теста (сборка инициализатора из настоящих бинов и UI-канал формы); после правки в обоих overlay остаётся ровно один незарегистрированный файл — чужой `AccountingObjectPilotIT` |
| `platform-spring-boot-autoconfigure` целиком | 38 тестов (36 + 2 новых), 0 failures |
| `platform-rls` целиком | 66 тестов (59 + 7 новых), 0 failures |
| `platform-core` целиком | 159 тестов (155 + 4 новых), 0 failures |
| `platform-spring-boot-autoconfigure` целиком | 36 тестов (35 + 1 новый), 0 failures |
| `WorkspaceManifestTest` | 4 теста, 0 failures — манифест `scripts/local-dependencies.json` перезаписан (`-Dplatform.manifest.write=true`); сменились ровно два отпечатка (`platform-rls`, `platform-core`), остальные и `fileCount` не тронуты |
| `PlatformPackageDependencyMatrixTest` | 3 теста, 0 failures — рёбра пакетов не изменились: перенос реестра не создал новых зависимостей |
| `PlatformApiBaselineTest` (artifact `platform-rls`) | baseline перезаписан намеренно: `RlsWriteGuardBridge.install` → `recordCanonicalWrite/Delete(registry, entity)`, `RlsWriteEnforcementListener` безпустой ctor → `(RlsDimensionRegistry)`, в `RlsDimensionRegistry` появились `registerWriteGovernedType`/`isWriteGoverned`, в `RlsPolicyEnforcer` — `recordCanonicalWrite/Delete`. Единственное оставшееся расхождение — `platform-vaadin` (не этот срез) |
| полный прикладной набор со стейджингом | см. §6 |

## 6. Состояние дерева после стейджинга

Финальный полный прикладной прогон со снятым переключателем (`logs/boundary-final-app-tests.log`):
`Tests run: 1856, Failures: 21, Errors: 3, Skipped: 6` — то есть **меньше** отказов, чем в
базовом прогоне до среза (32 / 12): ни один отказ не приходит из этой границы, пока она снята.
Срез не добавил красного ни в одном прикладном классе.

Прежний перечень чужих отказов остался (§4.2 предыдущего evidence), плюс два новых на момент
прогона, и оба — не от этой границы:

- `WorkspaceManifestTest` — отпечаток `platform-vaadin` ушёл во время прогона: параллельно
  с этой работой дерево правится (файлы `platform-vaadin/src/main` менялись в 13:06–13:24).
  После переноса реестра манифест перезаписан ещё раз (отпечатки `platform-rls` и
  `platform-core`) и перепроверён обычным прогоном (4/4, зелёный) — то есть в нём
  зафиксирован и чужой ваядин-дрейф, это названное следствие, а не побочный эффект;
  при разборе чужой работы виноват будет эта перезапись. Тот же чужой дрейф остаётся
  единственной причиной красного `PlatformApiBaselineTest` (baseline `platform-vaadin`
  не переписывался: это чужая незавершённая правка `FormRoute`);
- `RlsUnauthenticatedAccessIT` — 3 отказа о C5-read (`ENTITY:org.ip.model.ReceivingDocument`),
  то есть read-путь, который этот срез не трогал: класс проходил в обоих предыдущих прогонах
  дня, а прикладной код инициализации пилота менялся в 12:52, между запусками.

Повторный полный прикладной прогон после батча §7 (`logs/c5-6-batch-full-app.log`)
дал `1887 / 20 / 3 / 6`. Из прежних чужих отказов ушли `WorkspaceManifestTest` и
`ReportExecutionIT`; добавились `ReportFormSeamTest`, `CteHibernateFilterIT`, `SubqueryRlsIT`
(диалог выборки регистра и CTE/подзапросы отчёта — работа соседних потоков, не этого батча),
остальное — тот же список плюс незарегистрированный чужой файл `AccountingObjectPilotIT` в
overlay-проверках поверхностей. Ни один класс, тронутый батчем, не красный.

Замечание по воспроизводимости: приложение берёт платформенные модули из локального
репозитория Maven (`~/.m2/repository` — путь по умолчанию), поэтому прикладной прогон видит
изменения платформы только после `mvn -o -f platform-build/pom.xml -pl ../platform-rls,../platform-core,../platform-spring-boot-autoconfigure install`.
Каталог `.local-maven-repository` в корне — не тот источник, из которого читает обычный
`mvn -o test`; проверка артефактов должна идти по `~/.m2`.

## 7. Шаги миграции: демо-наполнение, пилотная фикстура, AttributeType

Миграция мест прямой записи идёт по типам, поэтому граница получила пошаговое вооружение:
свойство `rls.write-boundary.enforced-types` называет типы (полные имена классов через
запятую), для которых flush уже требует зафиксированную авторизацию; `enforced=true`
остаётся состоянием окончания миграции («все типы»). Имя без canonical write handle
останавливает старт: список — признак завершённой миграции, а не способ разрешить запись.
Старт всегда печатает WARN с тремя числами: вооружённые типы, снятые типы и сам список.

Первым снят тип `Role`/`User` в [DataInitializer](../../../src/main/java/org/ip/config/DataInitializer.java) —
то место из §3, где отказов было 3 (Role) и часть из 12 (User). Прежняя запись шла
`roleRepository.save(...)`/`userRepository.save(...)` мимо preflight.

**Почему снято именно так, а не bypass'ом.** Обход для приложения закрыт: `RlsBypassScope` —
закрытый перечень системных причин, а `RlsContext.runAsSystem` бросает исключение. Поэтому
наполнение объявляет собственного субъекта (`demo-data-initializer`), получает ровно права
на два своих типа (read-ключ + ключ операций, как это уже делает пилотный инициализатор) и
пишет через `EntityDataAccess` — тем же путём, которым писал бы человек. Права выдаются в той
же транзакции и до первой записи: seeding либо есть вместе с правами, либо нет вовсе.

**Что это доказывает.** Отдельный контекст
[DemoSeedingWriteBoundaryIT](../../../src/test/java/org/ip/config/DemoSeedingWriteBoundaryIT.java)
(5 тестов, 0 отказов) вооружает границу **только** для `Role` и `User` и проверяет четыре
вещи: демо-данные на старте на месте (3 роли, 3 пользователя), неназванный тип
(`UnitOfMeasurement`) остаётся снятым, прямой `persist` роли и пользователя отклонён на flush,
а canonical path для субъекта seeding остаётся открыт и после старта. Профильные
`Test|Dev|DemoProfileWithPropertyIT`, которые действительно запускают `DataInitializer`,
проходят и с глобально вооружённой границей (`-Drls.write-boundary.enforced=true`, WARN
называет 20 типов): место больше не отказывает на commit.

**Второй шаг: пилотная фикстура.** [AccountingObjectPilotInitializer](../../../src/main/java/org/ip/config/AccountingObjectPilotInitializer.java)
писала роль ADMIN и входного пользователя `accounting-pilot` через repository — это было
последнее место прямой записи `Role`/`User` в production-коде. Теперь фикстура объявляет
собственный названный субъект (`PILOT_SUBJECT = "accounting-pilot"`), выдаёт ему read-ключ и
ключ операций на два своих типа до первой записи и пишет через `EntityDataAccess`. Тело
наполнения выделено (`seedFixture()`): production-запуск по-прежнему защищён требованием
отдельной базы `accounting_object_pilot_*`, и тест этот предохранитель не ослабляет, а вызывает
то же тело с тем же субъектом. [PilotFixtureWriteBoundaryIT](../../../src/test/java/org/ip/config/PilotFixtureWriteBoundaryIT.java)
(3/3) вооружает границу только для `Role`/`User` и проверяет, что фикстура проходит её целиком
(ADMIN и входной пользователь с ролью), что неназванный тип снят и что canonical path субъекта
остаётся открыт и после наполнения.

**Третий шаг: AttributeType.** Прямых записей `AttributeType` в production-коде не было вовсе:
тип читают `AttributeTypeLifecycle` (`findById`) и `AttributeValueService` (`findByIdForUpdate`),
а пишет его generic item-форма через metadata-driven адаптер, то есть canonical path. Поэтому шаг
состоял не в переводе вызова, а в закрытии обхода и в переводе **тестовых фикстур полного
контекста**: `NomenclatureAttributesIT` (helpers `stringType`/`numberType`/`refType` и снятие
`active`) и `AttributeValueRefRlsIT` (создание и удаление типа) писали repository напрямую и при
вооружённой границе отказали бы. Slice-тесты (`@DataJpaTest`: `AttributeTypeLifecycleTest`,
`JpaAuditingSliceContractTest`, `AttributeValueServiceTest`, `SklNomOpaServiceTest`) границу не
устанавливают вообще — регистратор границы в их контексте не поднимается (проверено: WARN о
состоянии границы печатается в полных контекстах и отсутствует в срезах), поэтому их прямые
записи — не вход обхода, а другой контекст, и трогать их не нужно.
[AttributeTypeWriteBoundaryIT](../../../src/test/java/org/ip/application/catalog/AttributeTypeWriteBoundaryIT.java)
(4/4) вооружает только `AttributeType` и держит границу на пользовательском входе: форма типа
атрибута сохраняется через настоящий `ItemFormSaveDispatcher`, canonical `create`/`update`
проходят, прямой `persist` отклонён на flush («canonical write preflight»).

**Переключение production-списка.** После этих шагов у `Role`, `User` и `AttributeType` прямых
записей в production-коде не осталось (проверено по `src/main`: запись идёт через
`EntityDataAccess`/`CanonicalEntityService`, `UserDetailsServiceImpl` только читает), поэтому они
названы в `rls.write-boundary.enforced-types` в main-properties:
`org.ip.model.AttributeType,org.ip.model.Role,org.ip.model.User`. Вооружение не меняет поведение
легальных путей — они и так проходят тот же preflight, — но закрывает обход. Список проверен
прогоном с ровно этим значением через системное свойство (WARN: вооружено 3 типа, снято 17):
`AccountingObjectPilotIT` 18/0, `NomenclatureAttributesIT` 9/0, `AttributeValueRefRlsIT` 2/0 — и
отдельным вооружённым прогоном этих же фикстур (`-Drls.write-boundary.enforced-types=org.ip.model.AttributeType`,
11/0), который доказывает, что перевод фикстур на canonical path состоялся, а не заявлен.

**Четвёртый шаг: фикстуры `Role`/`User` полных контекстов.** Источник писал «именно они
удерживают `enforced=true` от переключения» — они переведены. Семь классов писали роль и
пользователя repository-{@code save} внутри `RlsTestFixture.callAsSuperuser(...)`:
`RestReadHttpTestSupport` (четыре входных пользователя HTTP-фикстуры),
`RestReadHttpSecurityIT` (две роли + назначение ролей),
`RestReadPipelinePilotIT` (`createRole`/`assignRoles`), `RestReadRoleMatrixIT`
(`roleWithUser`/`setUserRoles`), `RlsAutoConfigurationSmokeTest`, `C5GrantAdminParityIT`
(роль, пользователь и снятие ролей) и `ReportJpqlPreviewControllerIT` (посев и уборка
пользователей). Записи идут `EntityDataAccess.create/update/delete`, а не repository.

**Находка шага: read-ключ нужен не только чтению.** Первый вооружённый прогон дал семь отказов
`C5AccessDeniedException: нет права на операцию: READ ENTITY:org.ip.model.User` — canonical
`update`/`delete` читает исходную строку, а синтетический суперпользователь `RlsTestFixture`
материализовал только операционные ключи. Поэтому фикстура получила названный вариант
`callAsSuperuserWithRead`/`runAsSuperuserWithRead` (read-ключ выдаётся явно и по типам), а не
ослабление проверки: правило «read и операция — разные строки» сохранено, просто теперь оно
видно и в фикстуре. Запись (`create`) read-ключа не требует — это видно по тому, какие шаги
отказали, а какие нет.

**Вооружённые прогоны после шага** (`-Drls.write-boundary.enforced-types=org.ip.model.AttributeType,org.ip.model.Role,org.ip.model.User`,
ровно production-список): мигрированные фикстуры 59/0; соседние полные контексты, которые пишут
те же типы косвенно (HTTP-контракт/пилот/изоляция, `DeepLinkTwoUserAccessIT`,
`RlsServiceWriteBoundaryIT`, `NumberingEngineIT`, `Canonical*`, `MetadataDrivenAggregateSaveServiceIT`,
`GenericOwnedSectionServiceIT`) — 85/0; вся пакета среза и мигрированные фикстуры вместе — 109/0.
Slice-контексты (`@DataJpaTest`) границу по-прежнему не устанавливают и в счёт не входят.

**Что осталось в списке.** Остальные 17 типов не тронуты, и один из них упирается в решение, а не
в код: `AttributeValueService` пишет `AttributeValue` напрямую, но тип объявлен CREATE-only
(`EntityClassificationConfig.attributeValueIsCreateOnly`), а переименование — намеренная typed
операция. Значит, одного перевода вызовов на canonical path там недостаточно: нужен
благословлённый путь для именованной операции владельца (C5.7/C5.8 плана). Дальше —
`UnitOfMeasurement` (67 отказов измерения §3) и остальные фикстуры полных контекстов, в том числе
прямые записи `Role`/`User` в `RestReadHttpSecurityIT`, `RestReadPipelinePilotIT`,
`RestReadRoleMatrixIT`, `C5GrantAdminParityIT`, `RlsAutoConfigurationSmokeTest`: именно они
удерживают `enforced=true` от переключения.

## 8. Остаток и названный долг

- **Граница снята не для всех.** `enforced=false` остаётся признаком незавершённой миграции,
  но три типа (`AttributeType`, `Role`, `User`) вооружены по имени в main-properties: для них
  прямой `EntityManager` отклоняется уже сейчас, а снятые типы — состояние переключения, а не
  признак готовности. Замер 2026-10-08 вооружал 19 из 20 типов (§10) и показал следующий слой
  остатка: `Nomenclature`, `GroupNom`, `AttributeValue`, `GridFormView` плюс снятый
  `UnitOfMeasurement`. Снятие остатка — вместе с миграцией §4.
- **Фикстуры тестов больше не держат переключение.** Полные контексты переведены на canonical
  path: `NomenclatureAttributesIT`/`AttributeValueRefRlsIT` вместе с `AttributeType`, затем семь
  классов с `Role`/`User` (HTTP-фикстура и её наследники, `RestReadPipelinePilotIT`,
  `RestReadRoleMatrixIT`, `RlsAutoConfigurationSmokeTest`, `C5GrantAdminParityIT`,
  `ReportJpqlPreviewControllerIT`). Фикстура без прав сначала получает их — названным субъектом
  `RlsTestFixture` (ops-ключи и, где нужно, read-ключи), а не ослаблением проверки. Остаток —
  slice-контексты: граница там не поднята вообще, поэтому их прямые записи вне предмета, а не
  в списке должников.
- **Owned rows и строки регистра вне границы.** У них нет canonical write handle, их пишет
  владелец (агрегат, регистратор) — граница не расширена на них намеренно: иначе она
  запретила бы штатные пути владельцев, не имея собственного решения о правах.
- **Приложение не может открыть bypass.** `RlsBypassScope` закрыт, поэтому демо-наполнение и
  системные инициализаторы при включении обязаны идти либо canonical path, либо получить
  платформенный scope отдельным решением, а не обход в прикладном коде.
- **Свойство временное.** Оно существует ровно на время миграции (в обеих формах — `enforced`
  и `enforced-types`); после неё удаляется, а `enforced=true` становится единственным поведением.
- **Пошаговое вооружение — тоже временный инструмент.** Список `enforced-types` нужен, чтобы
  типы мигрировали по одному и каждый шаг был проверяем. Как только список опустеет, а
  `enforced=true` станет обычным, оба свойства уходят.

## 9. Прогоны 2026-10-08

- **Платформенный reactor — 17/17, 1024 теста, 0 падений**
  (`mvn -f platform-build/pom.xml install`, JDK 21, offline): contracts 4, events 34,
  persistence 7, telemetry 16, RLS 68, core 159, autoconfigure 38, vaadin 476, rest 111,
  rest-web 109, оба starter'а по 1; install записал 15 JAR'ов.
  Первый заход останавливался на `platform-vaadin`: `VaadinModuleCompositionTest`
  `moduleCarriesExactlyTheReviewedTypes` видел ровно одну разницу — модуль несёт
  `org.ipro.form.coordinator.AccessDenialPresentation`, которого нет в reviewed-списке.
  Список обновлён (тип внесён, бюджет 130 → 131), и это не подгонка: тип существует в дереве
  и без записи расширял бы публичную поверхность молча. Свежесть `.m2` проверена сверкой
  md5: `platform-vaadin`/`platform-core`/`platform-rls` target-JAR ≡ `.m2`-JAR.
  После install baseline `platform-vaadin` сошёлся сам: `PlatformApiBaselineTest` 3/3.
  App-сторона reviewed-списка `platform-vaadin` тоже приведена к факту: снята запись
  `INTERNAL org.ipro.form.PreciseLocalDateTimeField` — такого файла в дереве нет (остался
  только stale surefire-отчёт), — и ожидание размера списка приведено к 131 (130 типов
  плюс `AccessDenialPresentation`).
- **Пачка этого среза — 67 тестов, 0 падений** в одном контексте прогона:
  `PilotFixtureWriteBoundaryIT` 3, `AttributeTypeWriteBoundaryIT` 4,
  `DemoSeedingWriteBoundaryIT` 5, `CanonicalWriteFlushBoundaryIT` 5, `NomenclatureAttributesIT` 9,
  `AttributeValueRefRlsIT` 2, `AttributeTypeLifecycleTest` 4 (slice), `AccountingObjectPilotIT` 18,
  `RlsAutoConfigurationSmokeTest` 17. Прогон выполнен уже на обновлённых `.m2`-артефактах
  платформы, то есть проверяет не только приложение, но и только что собранную границу.
  После полного install пачка перепрогнана на свежем `platform-vaadin` — те же 67/0,
  `BUILD SUCCESS`: форма типа атрибута пишет через границу и на новой сборке адаптера.
- **Записи пачки в реестрах поверхностей точны.** В падениях заморозок «новая ссылка»
  (`PilotFixtureWriteBoundaryIT` → `MetadataDrivenAggregateSaveService`,
  `AttributeTypeWriteBoundaryIT` → `FormResolver`) не появляются: обе внесены в `test-usage`
  вместе с переводом фикстур.
- **Заморозка ссылок пачки.** `PlatformCoreSurfaceTest` 6/6 и `PlatformVaadinSurfaceTest`
  5/6 после записи `test-usage` для `AccountingObjectPilotIT.java` (новый файл называет
  `MetadataDrivenAggregateSaveService` и `FormResolver`) — эта запись механическая: правило
  требует назвать новую ссылку в реестре, а не оставить её незамеченной.
- **Остаток срез-гейтов назван поимённо и чужого кода в них нет.**
  `PlatformVaadinSurfaceTest.applicationMainCodeDoesNotReachIntoInternalModuleTypes`:
  `org/ip/views/register/AccountingObjectRegisterView.java` конструирует
  `MODULE_API` `FormBindingRegistry`, потому что его требует сигнатура APP_API-метода
  `FieldFactory.createField(FieldMetadataInfo, FormBindingRegistry)`. Это не тестовая подгонка,
  а решение о роли: либо `FormBindingRegistry` поднимается до APP_API (и тянет за собой
  INTERNAL `FormBinding`, который стоит в его же сигнатуре, — то есть правило 7), либо ссылка
  регистрируется долгом в D1-реестре. Выбор владельца типа, не этой пачки.
  `PlatformPublicSurfaceTest` (4 падения) — долг направления регистра объектов учёта: wildcard
  и fully-qualified импорты платформенных типов в `AccountingObject*`-файлах, семь типов без
  роли в D1-реестре (`AggregateSaveContribution`, `MetadataDrivenAggregateSaveService`,
  `EventSource`, `RlsFilterActivator`, `RlsPolicyEnforcer`, `RlsReadableIdsResolver`,
  `C5PermissionKeys`) и устаревший замер `app-source-files` в
  `docs/architecture/d1-surface-measurements.md` (191 против 171). Ни одного падения не осталось
  про типы и записи этой пачки.

## 10. Замер остатка — 2026-10-08

**Что вооружено.** Замер вооружил 19 из 20 типов с canonical write handle — весь список, кроме
`UnitOfMeasurement`: его 67 отказов известны из §3 и только мешали бы читать следующий слой.
Состояние подтверждено WARN при старте: «вооружена … для 19 типов … снята для 1 типов:
[org.ip.model.UnitOfMeasurement]». Прогон: `logs/boundary-armed-remainder-20261008.log`,
JDK 21, offline, 9 минут. В main-properties по-прежнему названы три типа: вооружение 19 —
свойство прогона, а не новое состояние по умолчанию.

| Прогон | Результат |
| --- | --- |
| вооружённые 19 типов | `Tests run: 1887, Failures: 25, Errors: 98, Skipped: 6` |
| из них границе принадлежат | **95 тестов в 21 классе** |
| не принадлежат | 28: surface gates, CTE/register-долг, deep-link baseline, `RlsUnauthenticatedAccessIT` (§9) |

**Принадлежность 28 проверена, а не предположена.** Два класса, которые могли бы быть нашими,
прогнаны со снятой границей и падают ровно так же: `RlsUnauthenticatedAccessIT` 3/5,
`CanonicalReadBoundaryIT` 1/12 (`Tests run: 17, Failures: 4`,
`logs/baseline-unarmed-2classes-20261008.log`). Это след чужой работы — классификация типов
разошлась с ожиданием теста (29 против 23) из-за агрегатов учёта и долга D1-реестра (§9), а не
отказов границы.

По типам (число упавших тестов):

| Тип | Тестов | Где | Природа |
| --- | --- | --- | --- |
| `org.ip.model.Nomenclature` | 68 | 14 классов: `NomenclaturePrice*` (4), `FetchPlan*` (3), `RestRead*` (2), `PrdSpecMetadataAggregateIT`, `ReceivingDocumentRulesIT`, `GenericOwnedSectionServiceIT`, `MetadataDrivenAggregateSaveServiceIT`, `DetachedRenderCostIT` | только фикстуры: в `src/main` прямых записей типа нет |
| `org.ip.model.GroupNom` | 18 | HTTP-фикстура и её наследники (`RestReadHttpContractIT`/`PilotIT`/`SecurityIT`/`SessionIsolationIT`) | только фикстуры |
| `org.ip.model.AttributeValue` | 8 | `NomenclatureAttributesIT`, `AttributeValueRefRlsIT` | прикладной путь [`AttributeValueService`](../../../src/main/java/org/ip/service/AttributeValueService.java) (пять прямых `save`) |
| `org.ip.model.GridFormView` | 1 | `CanonicalWriteBoundaryIT` | собственная проверка, писавшая `saveAndFlush` мимо canonical path; в `src/main` есть `GridFormViewService.save` |

**Почему типов больше, чем в §3.** Прежний замер («все отказы — по четырём типам») оказался
**фронтом, а не итогом**: фикстуры падали уже в `setUp` на `UnitOfMeasurement`/`User`/`Role`,
поэтому записи `Nomenclature`/`GroupNom` в тех же тестах не исполнялись. Снятие
`UnitOfMeasurement` — после того как три типа из четырёх переведены — открыло следующий слой.
Тем же замером закрыт вопрос §3: `AttributeValue` **является** блокирующим типом (пояснение в §3
было верным, таблица — неполной).

**Что это значит для порядка работы.** Следующий слой виден только после перевода предыдущего,
поэтому объём берётся из замера, а не из числа имён в WARN. Отсюда разделение: механизм
закрыт в C5.6, включение вынесено в C5.6b со своим Gate и точкой остановки
([план](../c5-operation-permissions-plan.md) §C5.6b).
