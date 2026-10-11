# C5.7: чтение сущности и плитка подсистемы — один ответ C5 — 2026-10-09

- План: [C5.4–C5.10](../c5-operation-permissions-plan.md), пакет C5.7 («Применение: чтение и UI»),
  пункт «Чтение сущности — один раз через C5; дублирующая CHECK_ONLY-проверка снимается».
- Решения: [ADR-0017](../decisions/ADR-0017-c5-single-operation-authority.md) §3 (носитель права —
  строка `access_grant`; короткий `ENTITY:<Имя>` — ключ операций типа), §5 (реестр определений).
- Предшественники: [c5-8-named-operation-issuance-20261008.md](c5-8-named-operation-issuance-20261008.md),
  [c5-8b-operation-effective-rights-20261009.md](c5-8b-operation-effective-rights-20261009.md) —
  оба назвали столбец «Чтение» короткого `ENTITY`-ключа последним местом, где флаг короткого ключа
  что-то решал, и снятие — шагом C5.7.
- Метод: снятие проверки в `platform-rls` и проводка UI в приложении; целевые прогоны, намеренная
  перезапись baseline `platform-rls`.

## 1. Что было не так

Один факт решался дважды, и оба раза — по флагу `canRead` ключа **операций** типа:

- [`RlsReadGate`](../../../platform-rls/src/main/java/org/ipro/rls/RlsReadGate.java) проходил по
  CHECK_ONLY-измерениям класса и требовал `AccessService.hasAnyAccess(dimension, user)`; для типа
  вида `ReceivingDocument` это и был короткий ключ `ENTITY:ReceivingDocument`;
- [`SubsystemHomeView.isEntityVisible`](../../../src/main/java/org/ip/views/workspace/SubsystemHomeView.java)
  скрывал плитку ровно тем же запросом.

Пока это жило, короткий ключ нёс две роли сразу: решение C5 об операциях (`canUpdate`/`canDelete`) и
гейт видимости вида. Поэтому админ-экран показывал у него столбец «Чтение», который описывал не
операцию и дублировал ответ, который C5 даёт **отдельным** read-ключом `ENTITY:<FQN>`. Срез C5.8b
оставил это единственным исключением сознательно: снять флаг, который ещё читается, значило бы
спрятать плитку у субъекта, только что получившего запись.

## 2. Что сделано

| Место | Было | Стало |
| --- | --- | --- |
| [`RlsReadGate`](../../../platform-rls/src/main/java/org/ipro/rls/RlsReadGate.java) | цикл по CHECK_ONLY-измерениям → `AccessService.hasAnyAccess` (флаг короткого ключа) | `C5PermissionEvaluator#isEntityReadPermitted` — read-ключ `ENTITY:<FQN>`; спрашивается один раз и только когда у политики класса есть CHECK_ONLY-измерение |
| [`RlsAutoConfiguration.rlsReadGate`](../../../platform-rls/src/main/java/org/ipro/rls/config/RlsAutoConfiguration.java) | инжекция `AccessService` | инжекция `C5PermissionEvaluator` |
| [`SubsystemHomeView`](../../../src/main/java/org/ip/views/workspace/SubsystemHomeView.java) | `hasAnyAccess` по короткому ключу | `RlsReadGate#canRead` — тот же ответ, которым ответит открытие списка |
| [`AccessGrantAdminService`](../../../src/main/java/org/ip/service/AccessGrantAdminService.java) | короткий `ENTITY`-ключ шёл по пути CHECK_ONLY: три флага, `canRead` нормализовался из записи | ключ распознаётся как операционное измерение **по определению реестра**, а не по префиксу; выдача пишет ровно объявленные операции, «Чтение» не записывается; эффективные права собирает `C5PermissionDecisionService.decideForSubject` — тот же engine, что у enforcement |
| [`AdminView`](../../../src/main/java/org/ip/views/admin/AdminView.java) | чекбокс «Чтение» и подсказка «запись без чтения недоступна» для всех CHECK_ONLY | чекбокс показывается только там, где определение выражает чтение; подсказка названа соответственно |

Обход `RlsContext#isBypassed()` и типы без CHECK_ONLY-политики не тронуты: фоновая задача и тип без
гейта читаются как раньше. Измерение, которое не является ни C5 read, ни определением операции
(не-сущностные CHECK_ONLY вроде `SETTINGS:*`), сохраняет прежние три флага — полное снятие вида
CHECK_ONLY планом отложено отдельным условием возврата.

Ни один объявленный короткий `ENTITY`-ключ не остаётся в прежнем пути: `ReceivingDocument`,
`AccountingObjectInstallation`, `AccountingObjectCorrection` и `NomenclatureAccountingObject` несут
`@EntityMetadata` (STANDARD_ROOT с canonical write handle), поэтому регистратор операций объявляет
для них определения, и `C5GrantAdminParityIT` проверяет это на `ENTITY:ReceivingDocument` —
единственном типе, где прежний гейт плитки был в пилоте.

## 3. Проверки

| Проверка | Результат |
| --- | --- |
| [`RlsReadGateTest`](../../../src/test/java/org/ip/security/RlsReadGateTest.java) — доступ к виду целиком открывает только C5 read-ключ; строка на коротком ключе чтение **не** открывает и отказ приходит до построчной оценки (`findAll`, `LookupService`); тип без CHECK_ONLY-политики не затронут | 4/4 |
| [`C5GrantAdminParityIT`](../../../src/test/java/org/ip/security/C5GrantAdminParityIT.java) — выдача на коротком ключе записывает `canUpdate` и не записывает `canRead`; read-ключ и ключ операций — разные строки | 7/7 |
| [`OperationEffectiveRightsIT`](../../../src/test/java/org/ip/security/OperationEffectiveRightsIT.java) — столбцы диалога совпадают с решением сервера, включая столбец «Чтение» короткого `ENTITY`-ключа (`'*'` правом не показан) | 5/5 |
| [`RlsAutoConfigurationSmokeTest`](../../../src/test/java/org/ip/security/RlsAutoConfigurationSmokeTest.java) — `collectEffective` покрывает все настраиваемые измерения; у короткого ключа `canRead` = решение C5, а не флаг строки | 17/17 |
| [`NamedOperationGrantAdminIT`](../../../src/test/java/org/ip/security/NamedOperationGrantAdminIT.java) — соседняя ветка выдачи операций не задета | 6/6 |
| [`RestReadRoleMatrixIT`](../../../src/test/java/org/ip/rest/RestReadRoleMatrixIT.java) — REST-чтение строится на тех же C5 read-ключах | 8/8 |
| `platform-rls`, весь модуль (`mvn -o test`) | 75/75 |
| `PlatformApiBaselineTest` — после намеренной перезаписи `platform-rls.api`: относительно состояния до среза поверхность изменилась одной строкой (ctor `RlsReadGate`), а git-дифф файла несёт и другие незакоммиченные срезы дерева | 3/3 |

Прогоны: `mvn -o -Dmaven.repo.local=.local-maven-repository -Dtest="RlsReadGateTest,C5GrantAdminParityIT,OperationEffectiveRightsIT,RlsAutoConfigurationSmokeTest,NamedOperationGrantAdminIT" test`
(39 тестов, 0 отказов), `mvn -o -Dmaven.repo.local=... -Dtest="RestReadRoleMatrixIT,PlatformApiBaselineTest" test`
(11 тестов, 0 отказов), `mvn -o test` в `platform-rls`. JDK 21.

## 4. Остаток

- Остальные пункты C5.7 этим срезом не закрыты: `RlsUiGate` остаётся владельцем построчного
  ответа, а общий backend операций для `ActionContextProvider`/E1.2b сделан следующим срезом
  того же дня — [c5-7b-e12b-action-operation-gate-20261009.md](c5-7b-e12b-action-operation-gate-20261009.md)
  (композиция «операция — C5, строка — RLS» ровно в одном месте); собственные определения
  административных операций — отдельная работа.
- Кликами UI не проверялся: проверены сервис, которым экран выдаёт, и проводка решений;
  отрисовка чекбоксов и подсказки на живой странице не воспроизводилась.
- Полная матрица П01–П14, PostgreSQL и параллельные сессии — за C5.9.
