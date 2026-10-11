# C5.8e: административный минимум в первичной выдаче и полевая политика `password` — 2026-10-10

- План: [C5.4–C5.10](../c5-operation-permissions-plan.md), пакет C5.8 («Администратор и первичная
  выдача») и §7 («Отложено с условием возврата»); остаток после
  [C5.8d](c5-8d-self-elevation-20261010.md): «выдача новых определений — точечная операторская
  работа» и «поле `password` остаётся пишущимся generic-записью».
- Решения: [ADR-0017](../decisions/ADR-0017-c5-single-operation-authority.md) §3 (носитель права —
  строка `access_grant`; `canUpdate` разрешает `EXECUTE`), §5 (незарегистрированное определение —
  отказ, не allow), §8 (порядок решения — до нумерации, валидации, хуков и SQL), §12 (смена пароля и
  назначение ролей — отдельные определения); [ADR-0019](../decisions/ADR-0019-owner-named-row-operation.md)
  §1, §2 (порядок именованной операции: решение → чтение строки → мутация владельцем → preflight
  свойств); [ADR-0018](../decisions/ADR-0018-no-field-permissions.md) (полевых прав не появляется:
  спрашивается то же именованное определение, а не флаг на поле).
- Метод: два предметных изменения — значение по умолчанию административного минимума в первичной
  выдаче и шов владельца свойства в preflight canonical write; проверка на реальном JPA/H2
  (приложение) и на изолированном persistence unit (`platform-core`), плюс поверхностные и
  baseline-gates платформы.

## 1. Что было не так

1. **Свежий контур закрывал управление пользователями и для администратора.** C5.8d объявил
   `USER:ASSIGN_ROLES` и `USER:CHANGE_PASSWORD` отдельными определениями, но оставил их выдачи
   точечной операторской работой. Практическое следствие: роль базовой выдачи
   (`app.security-baseline.roles=ADMIN`) получала плоскость сущностей и всё равно не могла ни
   создать учётную запись с паролем, ни назначить роли — то есть администратор после включения
   enforcement не мог выполнить свою работу без ручной выдачи каждого ключа.
2. **Хэш пароля переносился generic-записью.** `UserService` спрашивал `USER:CHANGE_PASSWORD`, но
   это был путь сервиса: вызывающий, положивший хэш прямо в `password` (а не в `rawPassword`), тем
   же `canonical update` менял учётные данные любого, включая себя, имея только
   `ENTITY:org.ip.model.User` (UPDATE). Первый прикладной сценарий, где поле обязано быть защищено
   от записи, — условие возврата из §7 плана — наступил, и закрыть его следовало без введения
   полевых прав (ADR-0018).

## 2. Что сделано

| Место | Было | Стало |
| --- | --- | --- |
| [`InitialGrantIssuance`](../../../src/main/java/org/ip/config/InitialGrantIssuance.java) | административный минимум не выдавался: `ACTION:` — только экран доступа | значение по умолчанию `app.security-baseline.operations:USER:ASSIGN_ROLES,USER:CHANGE_PASSWORD`: условный стартер выдаёт обеим названным ролям те же две строки, что и раньше вручную. Ключ обязан быть зарегистрирован **и объявлять `EXECUTE`**, иначе старт останавливается; существующая строка не переписывается и не дублируется |
| [`CanonicalWritePropertyPolicy`](../../../platform-core/src/main/java/org/ipro/data/CanonicalWritePropertyPolicy.java) | — | шов владельца: тип объявляет, какие его свойства generic-запись не переносит. Preflight получает **заявленный тип**, **предсостояние строки** (`null` при создании) и **цель записи** и требует право владельца, когда его свойство тронуто |
| [`CanonicalWriteExecutor`](../../../platform-core/src/main/java/org/ipro/data/CanonicalWriteExecutor.java) | preflight: capability + решение C5 об операции + RLS | тот же preflight спрашивает и владельца свойства — в `persist` (create/update/save) и в именованной операции. Отказ приходит до нумерации, валидации, lifecycle-хуков, событий и SQL-мутации. `null`-политика означает «у типа таких свойств нет», а не ослабление границ типа |
| [`UserPasswordChangePolicy`](../../../src/main/java/org/ip/application/user/UserPasswordChangePolicy.java) | — | правило владельца для `User`: сравнивает хэш предсостояния с хэшем цели и требует `USER:CHANGE_PASSWORD` при любом отличии, **включая затирание** (есть → нет). Новых определений и ATTR-ключей не появляется |
| [`DataAccessAutoConfiguration`](../../../platform-spring-boot-autoconfigure/src/main/java/org/ipro/data/config/DataAccessAutoConfiguration.java) | — | `ObjectProvider<CanonicalWritePropertyPolicy>` в сборке `CanonicalWriteExecutor`: без владельца правило не применяется, а не подменяется |
| [`platform-core-surface.txt`](../../../src/test/resources/platform-core-surface.txt), [`platform-public-surface.txt`](../../../src/test/resources/platform-public-surface.txt), [`PlatformCoreModuleTest`](../../../src/test/java/org/ipro/PlatformCoreModuleTest.java), [`PlatformCoreSurfaceTest`](../../../src/test/java/org/ipro/PlatformCoreSurfaceTest.java), [baseline `platform-core.api`](../../../src/test/resources/platform-api-baseline/platform-core.api) | — | reviewed-классификации: `APP_SPI` (приложение реализует шов) в обоих реестрах, состав модуля, замер «не вакуумная» (109 типов), публичная подпись `platform-core`; конфигурация autoconfigure — в своём baseline |

Обе части — один срез: первая открывает администратору работу без ручной выдачи, вторая не даёт
этой же выдаче (и любому другому держателю изменения строки) распоряжаться паролем в обход
названной операции.

## 3. Проверки

| Проверка | Метод | Результат |
| --- | --- | --- |
| [`CanonicalWritePathIT`](../../../platform-core/src/test/java/org/ipro/data/CanonicalWritePathIT.java) — владелец шва: preflight получает предсостояние и цель; отказ владельца не доходит до валидации, хуков, событий и SQL; создание передаёт `null`; разрешённая запись доходит до строки; именованная операция спрашивает то же правило (предсостояние снято с управления) | JPA, изолированный persistence unit, H2 | 41/41 (4 новых) |
| `platform-core` целиком (`mvn -o -f platform-core/pom.xml test`) | owner-набор модуля | 180/180 |
| `platform-spring-boot-autoconfigure` (`DataAccessAutoConfigurationTest`, `C5EntityOperationPermissionRegistrarTest`, `AutoconfigureModuleCompositionTest`) | unit/контекст | 38/38 |
| [`InitialGrantIssuanceWiringTest`](../../../src/test/java/org/ip/config/InitialGrantIssuanceWiringTest.java) — оба определения приходят **свойством по умолчанию**, а не вызовом | `@SpringBootTest`, H2 | 1/1 |
| [`InitialGrantIssuanceIT`](../../../src/test/java/org/ip/config/InitialGrantIssuanceIT.java) — роль с generic-записью по `User`, но без названных операций: создание учётной записи отказывает `ACTION:USER:CHANGE_PASSWORD`, назначение ролей — `ACTION:USER:ASSIGN_ROLES`; после выдачи оба проходят, самоповышение остаётся закрытым | `@SpringBootTest`, H2 | 7/7 |
| [`UserPasswordWriteBoundaryIT`](../../../src/test/java/org/ip/security/UserPasswordWriteBoundaryIT.java) — чужой канал: держатель изменения строки `User` без права на пароль не может ни сменить хэш, ни создать учётную запись, ни затереть хэш; запись без изменения хэша проходит; то же право пускает ту же запись | `@SpringBootTest`, H2 | 4/4 |
| [`UserAdministrationFlowIT`](../../../src/test/java/org/ip/service/UserAdministrationFlowIT.java), [`UserAdministrationSelfElevationIT`](../../../src/test/java/org/ip/service/UserAdministrationSelfElevationIT.java), [`UserServiceTest`](../../../src/test/java/org/ip/service/UserServiceTest.java), [`UserItemFormAccessTest`](../../../src/test/java/org/ip/views/forms/UserItemFormAccessTest.java) | `@SpringBootTest` + unit/Vaadin | 3/3, 6/6, 13/13, 4/4 |
| [`RlsAutoConfigurationSmokeTest`](../../../src/test/java/org/ip/security/RlsAutoConfigurationSmokeTest.java), [`C5GrantAdminParityIT`](../../../src/test/java/org/ip/security/C5GrantAdminParityIT.java), [`OperationEffectiveRightsIT`](../../../src/test/java/org/ip/security/OperationEffectiveRightsIT.java), [`NamedOperationGrantAdminIT`](../../../src/test/java/org/ip/security/NamedOperationGrantAdminIT.java), [`RlsProtectedTypeActionDecisionTest`](../../../src/test/java/org/ip/security/RlsProtectedTypeActionDecisionTest.java), [`RlsIntegrationTest`](../../../src/test/java/org/ip/security/RlsIntegrationTest.java), [`UngrantedDefinitionsReportTest`](../../../src/test/java/org/ip/service/UngrantedDefinitionsReportTest.java) — экран выдачи, решение, отчёт и flush-граница после смены правила | `@SpringBootTest`, H2 | 17/17, 7/7, 5/5, 6/6, 10/10, 23/23, 3/3 |
| [`DemoSeedingWriteBoundaryIT`](../../../src/test/java/org/ip/config/DemoSeedingWriteBoundaryIT.java), [`PilotFixtureWriteBoundaryIT`](../../../src/test/java/org/ip/config/PilotFixtureWriteBoundaryIT.java), [`AccountingObjectPilotIT`](../../../src/test/java/org/ip/application/register/AccountingObjectPilotIT.java), [`RestReadPipelinePilotIT`](../../../src/test/java/org/ip/rest/RestReadPipelinePilotIT.java), [`RestReadRoleMatrixIT`](../../../src/test/java/org/ip/rest/RestReadRoleMatrixIT.java), `RestReadHttp*IT`, [`ReportJpqlPreviewControllerIT`](../../../src/test/java/org/ip/web/ReportJpqlPreviewControllerIT.java), [`ApplicationNavigationSecurityTest`](../../../src/test/java/org/ip/navigation/ApplicationNavigationSecurityTest.java) — прочие места, где создаётся `User` (фикстуры заявляют операцию явно, а не ослабляют правило) | `@SpringBootTest`, JPA/H2 | 5/5, 3/3, 28/28, 13/13, 8/8, 5/5+3/3+6/6+4/4, 5/5, 3/3 |
| Поверхностные gates: [`PlatformApiBaselineTest`](../../../src/test/java/org/ipro/PlatformApiBaselineTest.java), [`PlatformCoreModuleTest`](../../../src/test/java/org/ipro/PlatformCoreModuleTest.java), [`PlatformVaadinSurfaceTest`](../../../src/test/java/org/ipro/PlatformVaadinSurfaceTest.java) | unit | 4/4, 6/6, 6/6 |
| [`PlatformCoreSurfaceTest`](../../../src/test/java/org/ipro/PlatformCoreSurfaceTest.java), [`PlatformPublicSurfaceTest`](../../../src/test/java/org/ipro/PlatformPublicSurfaceTest.java) | unit | **красные по чужим причинам**: см. §4 |

Команды: `mvn -o -f platform-core/pom.xml test`, `mvn -o -f platform-spring-boot-autoconfigure/pom.xml
test`, затем в приложении `mvn -o test-compile` и
`mvn -o surefire:test -Dtest='...'` (наборы из таблицы). Итого прикладных: **117/117** (набор A) и
**101/107** (набор B; шесть отказов — чужие, названы ниже).

**Ограничение прогона.** То же, что в C5.8c/C5.8d: общий контекст приложения не поднимается с
чужими **скомпилированными** смок-конфигурациями `org.ip.views.unidr.smoke.*` на тестовом
classpath (`BeanDefinitionOverrideException` по бину `security`), поэтому
`target/test-classes/org/ip/views/unidr/smoke/*.class` удалены перед `surefire:test` (исходники не
тронуты). В общем checkout идёт параллельная сборка: она пересобирает эти классы и дерево
`target/test-classes` во время прогона, поэтому часть наборов пришлось перезапускать — отказы
выглядели как `ClassNotFoundException`/`FileNotFoundException` по чужим классам
(`RlsTestAccessConfiguration`, `RlsTestFixture`) и к коду этого среза не относятся. Ни один файл
среза при этом не изменён.

## 4. Остаток и названные пределы

- **Поле `password` закрыто как канал записи, но не как «полевое право».** Новых определений,
  ATTR-ключей и флагов на поля не появилось (ADR-0018): спрашивается то же
  `ACTION:USER:CHANGE_PASSWORD`, и только тогда, когда запись несёт изменение хэша. Полная
  attribute read/write политика (скрытие и защита произвольных полей) остаётся отложенной в §7
  плана: вернулся и закрыт **её первый сценарий**, а не общий механизм.
- **Административный минимум — имя свойства, а не константа в коде.** Состав минимума задаётся
  `app.security-baseline.operations`; расширять его сверх двух операций — отдельное решение
  оператора (та же дисциплина, что у списка ролей).
- **UI не кликался**: отказ владельца и открытость полей покрыты серверными и компонентными
  тестами, но не наблюдением в браузере; это тот же класс непроверенного, что в C5.7b/C5.8c/C5.8d.
- **Чужие красные gates** (не файлы этого среза): `PlatformCoreSurfaceTest`
  (`test-usage overlay` для `org.ipro.data.CanonicalReadExecutor` — untracked
  `org/ip/unidr/**`), `PlatformPublicSurfaceTest` (`wildcard`-импорты и fully-qualified ссылки в
  `org/ip/unidr/**`, legacy-ссылка `org.ipro.reportstudio.data.EntityRef`, замер `app-source-files`
  236 против 200 в `d1-surface-measurements.md`, 11 неотревьюенных типов
  `org.ipro.navigation.*`/`org.ipro.reportstudio.*`). `CanonicalWritePropertyPolicy` из этих списков
  убран: ему назначена роль в обоих реестрах.
- **PostgreSQL-шаг не выполнен**: правило свойства проверено на H2; живой PostgreSQL (flush-хуки,
  коллекция `User.roles`) — шаг C5.9, как и прежде. Полная матрица П01–П14 — там же.
