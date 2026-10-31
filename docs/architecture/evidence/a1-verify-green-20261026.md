# A1: блокер миграции прав снят, полный verify зелёный (2026-10-26)

- Дата: 2026-10-26.
- Основание: [A1 плана](../../plans/query-report-separation-plan.md#a1-итоговая-приёмка),
  [триаж красных классов](a1-red-triage-20261026.md), [закрытие M2.2.3](m2-2-3-cutover-20261026.md).
- Срез: закрыт последний общий блокер A1 — незакоммиченная миграция прав
  (`ENTITY:<short>` → `ENTITY:<FQN>`, lifecycle/lock субъекта) и архитектурная
  граница приложения. Полный `verify` выполнен на текущем дереве.

## 1. Что было красным

После M2.2.3 полный прогон давал **1830 тестов, 31 failure / 14 errors /
21 skipped**. Красные классы распадались на три смысловые группы:

1. **Единый ключ C5.** Тесты, фикстуры и отчёты ещё ждали короткий
   `ENTITY:<SimpleName>` и отдельное read-определение. После P-U у сущности один
   ключ `ENTITY:<FQN>` и один набор операций; чтение и запись — независимые флаги
   одной глобальной строки.
2. **Предпосылка чтения.** `UPDATE`/`DELETE` существующей сущности требуют
   эффективного `ENTITY READ` (ADR-0017). Фикстуры, выдававшие только write-флаг,
   получали отказ и на сервере, и в отчёте.
3. **Lifecycle/lock субъекта.** `PermissionSubjectLock.acquire` требует живую
   строку субъекта внутри транзакции. Тесты с синтетическими субъектами
   мокировали lock без ответа на `acquire`, из-за чего lifecycle падал с
   `NullPointerException` при сохранении пользователя/роли.

Отдельно `ApplicationBoundaryTest` фиксировал 37 зависимостей
`org.ip.application.*` от `EntityManager`: JPA-доступ жил в прикладном слое в
обход правила DAC-13.

## 2. Исправления

### 2.1. Ключ и решающая строка

- Тесты и комментарии переведены на единый `ENTITY:<FQN>`; короткий ключ больше
  не является ни ключом операции, ни алиасом.
- `UngrantedDefinitionsReportTest` использует общий
  `C5ReadPermissionRegistry(operationDefinitions)` и полный набор операций
  сущности (`READ/CREATE/UPDATE/DELETE`), как production-регистратор.
  Wildcard и value-scoped строки по-прежнему не считаются решающими.
- Фикстура `OperationEffectiveRightsIT` выдаёт `READ` вместе с `UPDATE`, потому
  что этого требует ADR-0017; проверка независимости флагов сохранена.

### 2.2. Lock и lifecycle

- В тестах синтетических субъектов `PermissionSubjectLock` отвечает стабильным
  `SubjectRef`; жизненный цикл реальных `User`/`Role` в `C5GrantAdminParityIT`
  больше не падает на `acquire`.
- Создание субъекта и первичная выдача гранта выполняются в одной транзакции;
  `PermissionSubjectLifecycle` и patch-save используют один и тот же row lock.

### 2.3. Архитектурная граница `ApplicationBoundaryTest`

JPA-доступ вынесен из `org.ip.application` в `org.ip.persistence`:

| Класс | Новый владелец |
|---|---|
| `PermissionSubjectLock` | `org.ip.persistence.access` |
| `PermissionSubjectLifecycle` | `org.ip.persistence.access` |
| `PermissionScopePageService` | `org.ip.persistence.access` |
| `PermissionPatchStore` (новый) | `org.ip.persistence.access` |
| `StoredGrantKeyReader` (новый) | `org.ip.persistence.access` |
| `PersistedUserState` | `org.ip.persistence.user` |

`PermissionCatalogService` и `PermissionPatchService` остались прикладными и
зависят от persistence-адаптеров; `PermissionDraftStore.flags` открыт для
использования новым владельцем критериев. Правило `ApplicationBoundaryTest` не
ослаблялось: **3/0**, зависимостей `org.ip.application.*` от `EntityManager`
больше нет.

Замеры `d1-surface-measurements.md` обновлены намеренно
(`-Dplatform.measurements.write=true`): `app-source-files=235`
(233 + два persistence-адаптера), остальные значения — из текущего дерева и
реестра, включая `platform-tree-files=0` после M2.2.3.

### 2.4. Смысловые падения прав

В этом же срезе закрыты read gate, dimension facts, UI gate, protected actions,
REST-матрица, self-elevation и отчёт о неназначенных определениях: тесты
приведены к единому ключу, read-предпосылке и реальному решающему движку C5,
а не к RLS-свёртке с wildcard/value-scoped строками.

## 3. Результаты проверок

Полный прогон на текущем дереве:

```text
mvn -o -B -Dvaadin.skip=true clean verify
```

```text
Tests run: 1830, Failures: 0, Errors: 0, Skipped: 21
BUILD SUCCESS
Total time: 09:38 min
```

Адресные прогоны среза:

| Набор | Результат |
|---|---|
| `ApplicationBoundaryTest` + `PermissionCatalogServiceTest` + scope/workspace IT + security IT + routes | **85 / 0 / 0** |
| `OperationEffectiveRightsIT` + `C5GrantAdminParityIT` + `UngrantedDefinitionsReportTest` | **15 / 0 / 0** |
| `PlatformPublicSurfaceTest` после обновления замеров | **10 / 0 / 0** |

Гейты среза A1/M2 в полном прогоне:

| Гейт | Результат |
|---|---|
| `ApplicationBoundaryTest` | 3 / 0 / 0 |
| `PlatformPublicSurfaceTest` | 10 / 0 / 0 |
| `PlatformApiBaselineTest` | 4 / 0 / 0 |
| `PlatformPackageDependencyMatrixTest` | 3 / 0 / 0 |
| `WorkspaceManifestTest` | 4 / 0 / 0 |
| `ReportFormSeamTest` | 6 / 0 / 0 |
| `PersistenceTypeRegistrationTest` | 6 / 0 / 0 |
| `PlatformStarterCompositionTest` | 5 / 0 / 0 |
| `PlatformVaadinModuleTest` | 7 / 0 / 0 |

## 4. Вердикт

- Блокер миграции прав снят; общий автоматический gate A1 зелёный.
- `ApplicationBoundaryTest` закрыт переносом JPA-владельцев, а не исключением
  из правила.
- **A1 verify закрыт.** Ручная PostgreSQL/браузерная приёмка из §A1 плана
  (manual/visual/table/CTE/register, печать списка/карточки, PDF, каталог) и
  отдельный random-order gate в этом срезе не выполнялись: отдельного профиля
  random-order в проекте нет, а ручные сценарии требуют поднятого PostgreSQL и
  браузера. Это остаётся отдельным входом, а не зелёной приёмкой.
