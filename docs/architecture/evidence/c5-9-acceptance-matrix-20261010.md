# C5.9 — матрица приёмки П01–П14: пары allow/deny и ссылки

Дата: 2026-10-10. Быстрый проход: инвентарь существующих проверок и ссылок, без нового кода.
Источник строк — §3 [плана C5](../c5-operation-permissions-plan.md). Gate строки:

> Каждая строка матрицы имеет невакуумную пару allow/deny и ссылку на проверку.

Полный, непустой ответ строки — **два** наблюдаемых исхода на одном и том же предмете:
разрешённое действие проходит, а запрещённое не проходит **и не оставляет побочного результата**
(номер, `Saving`, hook, SQL, запись гранта, изменение строки).

## 1. Матрица

| ID | Сценарий | allow | deny | Статус |
| --- | --- | --- | --- | --- |
| П01 | Точечный грант есть и отсутствует | `C5PermissionDecisionServiceTest.canUpdateAllowsCreateAndUpdateAndExecuteButNotDelete`, `canDeleteAllowsDeleteOnly`, `entityDecisionChoosesTheReadAndOperationKeySpellings` | те же тесты (DELETE/UPDATE/CREATE соответственно), `readGrantIsNeverMistakenForAWritePermission`, `writeFlagsAloneDoNotGrantRead` | закрыта |
| П02 | Только `'*'` или value-scoped строка | `canUpdateAllowsCreateAndUpdateAndExecuteButNotDelete`, `C5PermissionDecisionServiceJpaTest.updateAndDeleteRowsDecideIndependentlyAndRevokeFailsClosed`, `C5GrantAdminParityIT.saveAndRevokeThroughAdminIsImmediatelyVisibleToServer` | `wildcardGrantDoesNotAuthorizeAnOperation`, `C5PermissionDecisionServiceJpaTest.wildcardAndValueScopedRowsDoNotAuthorizeAnOperation`, `C5ReadDecisionServiceTest.wildcardGrantDoesNotAuthorizeC5Read`, `valueScopedRlsRowDoesNotBecomeGlobalC5Read` | закрыта |
| П03 | Тип без RLS (`Nomenclature`) | `OperationEffectiveRightsIT.theShortEntityKeyIsAnOperationKeyInEveryColumn` (точный грант → `canUpdate` и `decideEntity(UPDATE)` = true), `RlsUiGateTest.entityWithoutRlsIsFullyAllowed` | тот же `OperationEffectiveRightsIT` (`'*'` → `canUpdate` и `decideEntity(UPDATE)` = false; `DELETE` без права = false), `CanonicalWriteFlushBoundaryIT` (app) `directPersistOfAnUnprotectedTypeIsDeniedAtFlush` | закрыта |
| П04 | Тип с RLS (`ReceivingDocument`) | `RlsUiGateTest.aliceWithPerValueUpdateGrantCanCreatePrdSpec`, `RlsProtectedTypeActionDecisionTest.permittedUserSeesEditableCardAndAvailableActions`, `everyRightIsReadSeparatelyOnProtectedType` | `RlsUiGateTest.checkOnlyGateWithoutEntityGrantBlocksDocumentUpdate` (права `JOURNAL`+`BRANCH` есть, `ENTITY`-гранта нет → отказ называет `ENTITY:ReceivingDocument`), `RlsProtectedTypeActionDecisionTest.rowRightsWithoutOperationRightOpenNothingInUiOrOnServer`, `operationDenialBlocksActionAndNamesTheOperation`, `C5GrantAdminParityIT.wildcardGrantDoesNotAuthorizeC5ReadButKeepsLegacyRlsSemantics` | закрыта |
| П05 | Отсутствующий провайдер, аноним, неизвестное определение | `C5PermissionDecisionServiceTest.requirePassesForAPermittedOperation`, `C5ReadDecisionServiceTest.exactGlobalUserReadGrantAllowsRead` | `CanonicalWritePathIT.missingDecisionProviderIsAConfigurationErrorNotAnAllow` («no provider is configured»), `unauthenticatedSubjectIsDeniedBeforeAnyGrantRead`, `subjectDecisionDeniesABlankSubjectBeforeAnyGrantIsRead`, `unknownDefinitionIsAConfigurationErrorEvenForAnUnprivilegedSubject`, `operationTheDefinitionDoesNotDeclareIsAConfigurationError`, `C5OperationPermissionRegistryTest.unknownKeyIsAConfigurationErrorNotADenial` | закрыта |
| П06 | Ошибка хранилища грантов | `C5ReadDecisionServiceTest.exactGlobalUserReadGrantAllowsRead` | `C5PermissionDecisionServiceTest.storageFailurePropagatesInsteadOfTurningIntoAnAllow`, `C5ReadDecisionServiceTest.storageFailurePropagatesInsteadOfAllowOrEmptySuccess` | закрыта |
| П07 | Запрещённая операция в агрегате | `MetadataDrivenAggregateSaveServiceIT.savesHeaderAndOnlyAttachedSections`, `savesReceivingDocumentThroughTheSameMetadataDrivenContract`, `MetadataDrivenAggregateEventIT.publishesChangedOnlyAfterSuccessfulAggregateCommit` | `MetadataDrivenAggregatePreflightTest.aggregateOperationIsAuthorizedBeforeAggregateEventsAndHooks` (`never` на `openAggregateOperation`, `publishAggregateSaving`, `beforeAggregateSave`, `replaceAll`, `findService` — нет событий, hooks, SQL секций и корня), `updateIntentIsAuthorizedAsUpdateNotCreate` | закрыта с оговоркой (см. §2.1) |
| П08 | Замена набора регистра без определения владельца | `RegisterKeyChangePermissionsTest.uiDecisionRequiresReadDataUpdateAndNamedAction`, `RegisterKeyChangesTest.auditRecordsOldAndNewKeyAndUserAfterSuccessfulChange` | `RegisterKeyChangePermissionsTest.readDenialClosesTheUiAction`, `updateDenialClosesTheUiAction`, `aNewRowCannotOfferKeyChange`, `capabilityOffRejectsBeforePermissions`; `RegisterKeyChangesTest.aDeniedNewStateRollsBackAndPublishesNoAudit`, `permitIsLimitedToTheRequestedRow`, `sameKeyOfAnotherTypeDoesNotGiveItCapability` | закрыта |
| П09 | Изменение managed-сущности и коллекции на commit | `CanonicalWriteFlushBoundaryIT` (app) `canonicalCreateAndUpdateOfAnUnprotectedTypePassTheBoundary`, `canonicalDeleteOfAnUnprotectedTypePassesTheBoundary`; platform-core версия — `canonicalCreateConsumesTheFixedAuthorizationAtFlush`, `updateThroughTheCanonicalPathIsAllowedAndASecondDirtyChangeIsNot`, `canonicalDeleteConsumesItsOwnAuthorizationAtFlush` | `directPersistOfAnUnprotectedTypeIsDeniedAtFlush`, `dirtyChangeOutsideTheCanonicalPathIsDeniedAtFlush`, `directRemoveOfAnUnprotectedTypeIsDeniedAtFlush`; platform-core — `directPersistWithoutCanonicalWriteIsDeniedAtFlush` | закрыта с оговоркой (коллекция — §2.2) |
| П10 | Чтение сущности | `RlsReadGateTest.c5EntityReadAloneUnblocksReads`, `C5ReadDecisionServiceTest.exactGlobalUserReadGrantAllowsRead` | `RlsReadGateTest.aGrantOnTheOperationKeyDoesNotOpenReads`, `lookupServiceHonorsReadGate`, `C5ReadDecisionServiceTest.noGrantAndRevokeFailClosed`, `updateOrDeleteFlagsAloneDoNotGrantC5Read` | закрыта с оговоркой (§2.3) |
| П11 | Действие: allow/deny, программный вызов, снятие права после отрисовки | `RlsProtectedTypeActionDecisionTest.permittedUserSeesEditableCardAndAvailableActions`, `ActionContextProviderTest.capabilitiesComeFromDescriptorPolicyNotFromUiTable` | `ActionContextProviderTest.operationDenialBlocksActionAndNamesTheOperation`, `rowAllowanceDoesNotSubstituteForOperationRight`, `operationDenialIsNamedBeforeRowDenial`, `selectedRowIsReportedAsAccessDeniedNotAsNoSelection`; снятие после отрисовки — `RlsProtectedTypeActionDecisionTest.revokedOperationRightBlocksActionRecomputedAfterRender`; программный вызов — `C5PermissionDecisionServiceTest.requireThrowsAnAccessDeniedExceptionForADeniedOperation`, `MetadataDrivenAggregatePreflightTest.*` | закрыта |
| П12 | Админ: эффективное решение и источники | `C5GrantAdminParityIT.saveAndRevokeThroughAdminIsImmediatelyVisibleToServer`, `roleGrantIsEffectiveOnlyWhileRoleIsAssigned`, `shortEntityKeyIsIssuedAsAnOperationKeyOnly` | `C5GrantAdminParityIT.c5SaveRejectsWriteFlagsAndValueScopedInputAndKeepsHistoricalValueRows`, `valueScopedOnlyRowDoesNotBecomeGlobalC5Read`, `updateOnlyGlobalRowDoesNotGrantReadAndIsNotOfferedAsC5Right`, `wildcardGrantDoesNotAuthorizeC5ReadButKeepsLegacyRlsSemantics`; раздельность операций и измерений — `OperationEffectiveRightsIT.theShortEntityKeyIsAnOperationKeyInEveryColumn`; незамеченных типов нет — `UngrantedDefinitionsReportTest.everyRegisteredDefinitionWithoutADecidingRowIsReported` / `aDecidingRowAnywhereClearsTheDefinition` | закрыта |
| П13 | Пользователь меняет свои роли или чужой грант | `UserAdministrationSelfElevationIT.changingAnotherUsersRolesNeedsTheAssignmentOperation`, `changingAPasswordNeedsItsOwnOperation`, `anUnchangedOwnRoleSetIsAnUpdateNotAnAssignment`, `UserAdministrationFlowIT.createsUserThroughExistingFormAndReturnsItInCanonicalList` | `UserAdministrationSelfElevationIT.nobodyGivesThemselvesTheAdminRoleEvenHoldingBothOperations`, `creatingAUserAsksForBothOperations` | закрыта |
| П14 | Старт: конфликт определений, CHECK_ONLY на типе с canonical CRUD | `C5EntityOperationPermissionRegistrarTest.registersExactlyTheDeclaredWriteOperationsUnderTheGateKey`, `keepGateKeyCollidesWithAnExistingCheckOnlyDimensionWithoutFailing`, `MetadataConsistencyStartupCheckTest.cleanModelDoesNotStopStartup`, `EventContourStartupCheckTest.staysQuietWhenTheWholeContourIsPresent`, `C5OperationPermissionRegistryTest.declaredOperationsAreAvailableWithoutThrowingToDisplayAndIssuancePaths` | `C5OperationPermissionRegistryTest.sameKeyWithDifferentOperationsIsAConfigurationError`, `C5EntityOperationPermissionRegistrarTest.namingATypeWithoutCanonicalWriteIsAStartupError`, `MetadataConsistencyStartupCheckTest.conflictStopsStartupAndReportsEveryErrorWithEntityFieldAndSource`, `EventContourStartupCheckTest.failsLoudlyWhenHandlersExistButTheContourIsMissing`, `RlsCustomDimensionParityTest.checkOnlyDimensionWithReadConditionFailsAtRebuild` | закрыта с оговоркой (§2.4) |

Один и тот же тест даёт обе стороны только там, где он проверяет и проход, и отказ
(например `canUpdateAllowsCreateAndUpdateAndExecuteButNotDelete`). Пустых ссылок в таблице нет:
каждая пара — наблюдаемый allow и наблюдаемый deny, а не «тест существует».

## 2. Оговорки: что именно не утверждается

### 2.1 П07 — «нет номера» на агрегатном пути

`MetadataDrivenAggregatePreflightTest` доказывает отказ **до** событий, hooks, секций и SQL корня
(`findService` не вызывается), и из этого следует, что до нумерации дело не доходит. Явного
`verify(..., never())` по номеру на агрегатном пути нет; номер явно закрыт на canonical-пути —
`CanonicalWritePathIT.c5DenialStopsBeforeRlsNumberingValidationHooksAndEvents`. Это уточнение
формулировки, а не дыра в механизме.

### 2.2 П09 — коллекция и настоящие flush-callbacks

Коллекция `User.roles` и поведение реальных flush-callbacks проверяются отдельным шагом на
PostgreSQL — так записано в §4 плана. На H2 закрыта managed-сущность (append/update/delete и
второе грязное изменение вне канонического пути).

### 2.3 П10 — «одна проверка»

«Отказ без гранта сохранён» закрыт. Число обращений к решающему сервису на один путь чтения ни
один тест не считает: «одна проверка» утверждается устройством гейта (`RlsReadGate` — решение по
типу/виду, не цикл по грантам), а не `times(1)`. Если нужен счётчик — это отдельная правка теста.

### 2.4 П14 — «CHECK_ONLY на типе с canonical CRUD»

Буквальное прочтение строки («такой тип обязан уронить старт») **противоречит принятому решению**:
ADR-0017 §3 намеренно использует существующий `ENTITY:<SimpleName>`-ключ вида CHECK_ONLY как ключ
операций, и `C5EntityOperationPermissionRegistrarTest.keepGateKeyCollidesWithAnExistingCheckOnlyDimensionWithoutFailing`
фиксирует, что совпадение ключей старт **не** останавливает. Явными отказами старта являются:
конфликт операций под одним ключом, имя типа без canonical write, конфликт метаданных и
CHECK_ONLY-измерение с условием чтения. Строку матрицы предлагается переформулировать как
«конфликт определений; совпадение CHECK_ONLY-ключа с ключом операций — допустимо по ADR-0017 §3»,
иначе она навсегда останется «непройденной по букве».

## 3. Итог

- Все 14 строк имеют невакуумную пару allow/deny и ссылки; новых тестов прогон не потребовал.
- Три уточнения формулировок (П07, П10, П14) и один отложенный объём (П09, PostgreSQL) названы выше.
- Прогон §4: **246 проверок на H2, 0 отказов, 0 пропущенных** — все ссылки §1 зелёные.
  PostgreSQL-шаг и вакуумные уточнения §2 остаются непроверенными и названы таковыми.

## 4. Прогон

Окружение: JDK 21 (AxiomJDK-21), Apache Maven 3.9.9, офлайн (`-o`), H2, профиль `test`.
Maven 3.9.9 скачан во временный каталог **вне репозитория** — в PATH есть только wrapper 3.5.2,
а `maven-compiler-plugin:3.14.1` требует ≥ 3.6.3.

| Набор | Классы и число тестов | Итог |
| --- | --- | --- |
| `platform-rls` (П01, П02, П05, П06, П10, П14) | `C5PermissionDecisionServiceTest` 24, `C5ReadDecisionServiceTest` 11, `C5OperationPermissionRegistryTest` 10, `C5PermissionDecisionServiceJpaTest` 5, `C5ReadPermissionRegistryTest` 2 | 52 / 0 отказов |
| `platform-core` (П05, П07, П09, П14) | `CanonicalWritePathIT` 41, `MetadataDrivenAggregatePreflightTest` 2, `CanonicalWriteFlushBoundaryIT` 4, `MetadataConsistencyStartupCheckTest` 3, `EventContourStartupCheckTest` 4, `GenericIdentityAggregateIT` 5 (соседний, не строка матрицы) | 59 / 0 |
| `platform-vaadin` (П11) | `ActionPolicyDecisionTableTest` 16, `ActionContextProviderTest` 15, `ActionRegistryResolutionTest` 12, `ActionSuppressionTest` 5 | 48 / 0 |
| `platform-spring-boot-autoconfigure` (П14) | `C5EntityOperationPermissionRegistrarTest` 6, `DataAccessAutoConfigurationTest` 3, `C5EntityReadPermissionRegistrarTest` 1 | 10 / 0 |
| приложение (П03, П04, П07, П08, П10, П11, П12, П13, П14) | `RegisterKeyChangesTest` 9, `MetadataDrivenAggregateSaveServiceIT` 7, `RegisterKeyChangePermissionsTest` 7, `C5GrantAdminParityIT` 7, `UserAdministrationSelfElevationIT` 6, `RlsCustomDimensionParityTest` 6, `CanonicalWriteFlushBoundaryIT` 5, `OperationEffectiveRightsIT` 5, `RlsReadGateTest` 4, `RlsUiGateTest` 4, `UserAdministrationFlowIT` 3, `UngrantedDefinitionsReportTest` 3, `RlsProtectedTypeActionDecisionTest` 10, `MetadataDrivenAggregateEventIT` 1 | 77 / 0 |
| **Всего** | 5 модулей | **246 / 0 отказов / 0 пропущенных** |

Команды (каждая — с проверкой кода возврата, без фильтрации, скрывающей статус):

```
mvn -o -Dmaven.repo.local=../.local-maven-repository test -Dtest='C5*Test'                      # platform-rls
mvn -o -Dmaven.repo.local=../.local-maven-repository test -Dtest='CanonicalWriteFlushBoundaryIT,CanonicalWritePathIT,MetadataDrivenAggregatePreflightTest,MetadataConsistencyStartupCheckTest,EventContourStartupCheckTest,GenericIdentityAggregateIT'   # platform-core
mvn -o -Dmaven.repo.local=../.local-maven-repository test -Dtest='ActionContextProviderTest,ActionRegistryResolutionTest,ActionPolicyDecisionTableTest,ActionSuppressionTest'   # platform-vaadin
mvn -o -Dmaven.repo.local=/c/Users/strim/.m2/repository test -Dtest='C5EntityOperationPermissionRegistrarTest,C5EntityReadPermissionRegistrarTest,DataAccessAutoConfigurationTest'   # platform-spring-boot-autoconfigure
mvn -o -Dmaven.repo.local=/c/Users/strim/.m2/repository test-compile                             # приложение
rm -f target/test-classes/org/ip/views/unidr/smoke/*.class                                       # см. ограничения ниже
mvn -o -Dmaven.repo.local=/c/Users/strim/.m2/repository surefire:test -Dtest='RlsUiGateTest,RlsReadGateTest,RegisterKeyChangePermissionsTest,RegisterKeyChangesTest,RlsCustomDimensionParityTest,UngrantedDefinitionsReportTest,CanonicalWriteFlushBoundaryIT,MetadataDrivenAggregateSaveServiceIT,MetadataDrivenAggregateEventIT'
mvn -o -Dmaven.repo.local=/c/Users/strim/.m2/repository surefire:test -Dtest='OperationEffectiveRightsIT,C5GrantAdminParityIT,RlsProtectedTypeActionDecisionTest,UserAdministrationSelfElevationIT,UserAdministrationFlowIT'
```

### 4.1 Ограничения прогона

- **PostgreSQL-шаг П09 не выполнен.** Матричных пунктов (реальные flush-callbacks, коллекция
  `User.roles`, откат агрегата при позднем отказе, отзыв между транзакциями) тестовый артефакт не
  имеет. В `src/test` есть только регистровые PG-наборы `RegisterWriteConcurrencyPostgresIT` и
  `RecorderSetWriterPostgresIT`: они требуют отдельной одноразовой базы `g3_register_test_*` и
  переменных `IPRO_REGISTER_G3_JDBC_URL`, `IPRO_REGISTER_G3_DB_USER`, `IPRO_REGISTER_G3_DB_PASSWORD`,
  и ни одного пункта П09 не покрывают. Локальный PostgreSQL на 5432 слушает, но учётные данные не
  выданы, а база мной не создавалась — шаг остаётся открытым, а не «пропущенным тестом».
- **Приложение запускалось с обходом чужих незавершённых конфигураций.** Перед `surefire:test`
  удалены только скомпилированные `target/test-classes/org/ip/views/unidr/smoke/*.class` (тот же
  обход, что в [c5-8c](c5-8c-ungranted-report-issuance-20261010.md), §3); исходники не тронуты.
  Пока чужие smoke-конфигурации не починены, `mvn test` в приложении целиком по-прежнему не поднимет
  общий контекст.
- **Репозитории разрешения зависимостей разные.** Модули платформы берут артефакты из репозиторного
  `.local-maven-repository`, приложение — из `~/.m2/repository`: `org.ipro:platform-navigation`
  установлен только там. Это конфигурация окружения, не результат среза.
- **Непроверяемое осталось непроверяемым.** Счётчик обращений к решающему сервису (П10) и явный
  `never()` по номеру на агрегатном пути (П07) не появились от прогона: они не asserted ни в одном
  из зелёных тестов §2.3/§2.1.
