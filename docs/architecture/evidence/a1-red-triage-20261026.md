# A1: триаж красных классов полного verify (2026-10-26)

> Обновление 2026-10-26: блокер миграции прав и `ApplicationBoundaryTest` сняты,
> полный `verify` зелёный — [a1-verify-green-20261026.md](a1-verify-green-20261026.md).

- Дата: 2026-10-26.
- Основание: [A1 плана](../../plans/query-report-separation-plan.md#a1-итоговая-приёмка),
  [M2.2.3](m2-2-3-cutover-20261026.md).
- Срез: после закрытия M2 полный `verify` прогнан на стабильных артефактах; два долга
  M1/M2 сняты, остаток классифицирован. **Приёмка M2 подтверждена, A1 остаётся
  открытой** — чужие красные классы названы блокером общего gate, а не записаны
  зелёной приёмкой.

## 1. Исходный остаток после M2.2.3

Полный прогон M2.2.3 дал **1830 тестов, 33 failures / 14 errors / 21 skipped**.
18 красных классов распались на три группы:

| Группа | Классы | Вердикт |
|---|---|---|
| Долг M1/M2 | `PlatformWritePathLifecycleTest`, `JrxmlExecutionIT` | исправлено в этом срезе |
| Чужая миграция прав | 16 классов `org.ip.security.*`, `org.ip.service.*`, `org.ip.rest.*`, `org.ip.routes.*` | блокер A1, не трогали |
| — | — | — |

## 2. Снятые долги M1/M2

### 2.1. `PlatformWritePathLifecycleTest`

Гейт импортировал `org.ipro` из classpath и видел не только production-классы
`platform-query`, но и тестовые классы из `platform-query-*-tests.jar`
(`CteHibernateExecutorIT`, `CteHibernateFilterIT`, `SubqueryRlsIT`,
`RecorderWinnerCteSpikeTest`). `ImportOption.Predefined.DO_NOT_INCLUDE_TESTS`
отсекает каталоги `test-classes`, но не JAR-фикстуры, поэтому тестовые классы
попадали в перечень путей записи.

Исправление:

- импорт дополнительно фильтрует локации `-tests.jar`;
- production-путь `org.ipro.query.ReportQueryExecutor` объявлен как `EXCEPTION`:
  `SET LOCAL statement_timeout` — сессионная настройка PostgreSQL, строк сущностей
  не пишет.

Результат: **4 теста, 0 failures / 0 errors**.

### 2.2. `JrxmlExecutionIT`

`sqlWithoutJpqlMarkerIsRejected` ожидал подстроку `"sql"` в сообщении, а сообщение
содержит `"SQL"` в фразе «выполнение SQL мимо конвейера guard/RLS запрещено».
Смысл проверки не менялся — тест по-прежнему требует, чтобы отказ называл SQL;
ожидание приведено к фактическому регистру.

Результат: **4 теста, 0 failures / 0 errors**.

Совместный адресный прогон двух классов: **8 тестов, 0 failures / 0 errors**.

## 3. Полный verify после исправлений

```text
mvn -o -B -Dvaadin.skip=true clean verify
```

Результат: **1830 тестов, 31 failure / 14 errors / 21 skipped**, BUILD FAILURE.
Минус два исправленных failure относительно M2.2.3; новых красных классов нет.

Гейты среза M2 в этом же прогоне:

| Гейт | Результат |
|---|---|
| `PlatformPublicSurfaceTest` | 10 / 0 / 0 |
| `PlatformApiBaselineTest` | 4 / 0 / 0 |
| `PlatformPackageDependencyMatrixTest` | 3 / 0 / 0 |
| `ReportFormSeamTest` | 6 / 0 / 0 |
| `PersistenceTypeRegistrationTest` | 6 / 0 / 0 |
| `PlatformPersistenceModuleTest` | 4 / 0 / 0 |
| `WorkspaceManifestTest` | 4 / 0 / 0 |
| `PlatformSubsystemClosureTest` | 2 / 0 / 0 |
| `PlatformStarterCompositionTest` | 5 / 0 / 0 |
| `PlatformVaadinSurfaceTest` | 6 / 0 / 0 |
| `PlatformVaadinModuleTest` | 7 / 0 / 0 |

Примечание: первый прогон после исправлений совпал с параллельной переустановкой
`platform-vaadin` в общий `~/.m2` и дал 307 errors (`NoClassDefFoundError` на
классы, которые в этот момент перезаписывались). После стабилизации артефактов
прогон повторён; в протокол идёт повторный результат. Это же объясняет, почему
`WorkspaceManifestTest` и API/matrix в первом прогоне были красными.

## 4. Остаток: чужая незакоммиченная миграция прав

31 failure / 14 errors приходятся на 16 классов:

| Класс | F | E |
|---|---:|---:|
| `org.ip.security.RlsServiceWriteBoundaryIT` | 7 | 0 |
| `org.ip.security.RlsProtectedTypeActionDecisionTest` | 6 | 0 |
| `org.ipro.rls.RlsDimensionFactsIT` | 5 | 0 |
| `org.ip.service.UngrantedDefinitionsReportTest` | 3 | 0 |
| `org.ip.security.RlsReadGateTest` | 2 | 0 |
| `org.ip.security.RlsAutoConfigurationSmokeTest` | 2 | 4 |
| `org.ip.security.RlsDeniedEventTest` | 1 | 0 |
| `org.ip.security.RlsIntegrationTest` | 1 | 0 |
| `org.ip.security.RlsUiGateTest` | 1 | 0 |
| `org.ip.ApplicationBoundaryTest` | 1 | 0 |
| `org.ip.rest.RestReadRoleMatrixIT` | 1 | 0 |
| `org.ip.security.NamedOperationGrantAdminIT` | 1 | 2 |
| `org.ip.routes.DeepLinkTwoUserAccessIT` | 0 | 1 |
| `org.ip.service.UserAdministrationSelfElevationIT` | 0 | 1 |
| `org.ip.security.OperationEffectiveRightsIT` | 0 | 3 |
| `org.ip.security.C5GrantAdminParityIT` | 0 | 3 |

Общая причина видна в сообщениях:

- ключи сущностей уже пишутся как `ENTITY:org.ip.model.Journal`, а тесты и
  реестр измерений ждут короткий `ENTITY:Journal`;
- `PermissionSubjectLock.acquire` бросает `IllegalArgumentException: Permission
  subject no longer exists` на сохранении и отзыве грантов;
- `RlsReadGateTest` и `RestReadRoleMatrixIT` ждут отказа чтения, а путь его не
  даёт;
- `ApplicationBoundaryTest` фиксирует 37 зависимостей `org.ip.application.*` от
  `EntityManager` — это состояние незакоммиченной работы, а не переноса M2.

Подтверждение владельца изменений — рабочее дерево, а не M2:

```text
M src/main/java/org/ip/application/access/PermissionCatalogService.java
M src/main/java/org/ip/application/access/PermissionCellState.java
M src/main/java/org/ip/application/access/PermissionPatchService.java
M src/main/java/org/ip/application/access/PermissionScopePageService.java
M src/main/java/org/ip/application/access/PermissionSubjectLifecycle.java
M platform-vaadin/src/main/java/org/ipro/form/...            (26 файлов)
```

Ни один из этих классов не импортирует `platform-unidr`,
`platform-report-vaadin`, `platform-query` или `platform-report`; перенос M2 их
не касался.

## 5. Вердикт

- **M2 принят.** Все гейты среза зелёные на полном прогоне; два долга M1/M2 сняты
  адресно и в общем прогоне.
- **A1 не закрыт.** Полный `verify` остаётся красным только на чужой
  незакоммиченной миграции прав. По выходу A1 это блокер общего gate, а не
  зелёная приёмка; исправлять его в срезе M2 нельзя — он принадлежит другой
  работе.
- Осталось для A1: PostgreSQL/браузерная приёмка (manual/visual/table/CTE/register,
  печать списка/карточки, каталог, PDF), random-order gate и закрытие чужого
  блокатора прав. Отдельного random-order профиля в `pom.xml`, модульных POM и
  `scripts/*.ps1` не найдено; запуск не выполнялся.
