# Рабочее место прав: приёмка П1, 13.10.2026

Статус: выполнен. Реализованы каталог и контракты; расчёт прав, paged-значения, save и UI принадлежат следующим этапам.

Документы: [план](C:/JavaProject/TestVaadin25/GitVaa25/docs/plans/permissions-administration-workspace-plan.md), [контракты П1](C:/JavaProject/TestVaadin25/GitVaa25/docs/plans/permissions-administration-p1-contracts.md), [спецификация П0](C:/JavaProject/TestVaadin25/GitVaa25/docs/plans/permissions-administration-p0-specification.md).

## 1. Реализация

Новый прикладной пакет `org.ip.application.access` содержит metadata service и DTO. Публичные контракты платформенных модулей, schema AccessGrant, ключи C5 и правила enforcement не изменялись.

- [PermissionCatalogService:81](C:/JavaProject/TestVaadin25/GitVaa25/src/main/java/org/ip/application/access/PermissionCatalogService.java:81) — immutable metadata catalogue, ADMIN-only, без запросов к БД.
- [PermissionCatalogService:86](C:/JavaProject/TestVaadin25/GitVaa25/src/main/java/org/ip/application/access/PermissionCatalogService.java:86) — отдельная проекция distinct stored keys для диагностики.
- [SubjectRef](C:/JavaProject/TestVaadin25/GitVaa25/src/main/java/org/ip/application/access/SubjectRef.java) — stable type/id identity отдельно от key/title snapshot.
- [PermissionBinding](C:/JavaProject/TestVaadin25/GitVaa25/src/main/java/org/ip/application/access/PermissionBinding.java), [PermissionObjectRow](C:/JavaProject/TestVaadin25/GitVaa25/src/main/java/org/ip/application/access/PermissionObjectRow.java), [PermissionCatalog](C:/JavaProject/TestVaadin25/GitVaa25/src/main/java/org/ip/application/access/PermissionCatalog.java) — metadata hierarchy и shared sources.
- [PermissionCellState](C:/JavaProject/TestVaadin25/GitVaa25/src/main/java/org/ip/application/access/PermissionCellState.java), [PermissionContribution](C:/JavaProject/TestVaadin25/GitVaa25/src/main/java/org/ip/application/access/PermissionContribution.java) — checked provenance transfer contracts.
- [PermissionPatch](C:/JavaProject/TestVaadin25/GitVaa25/src/main/java/org/ip/application/access/PermissionPatch.java) — dirty flags и JPA snapshots, без full-page replacement.
- [PermissionCatalogConfiguration](C:/JavaProject/TestVaadin25/GitVaa25/src/main/java/org/ip/application/access/PermissionCatalogConfiguration.java) и LegacyPermissionDefinition — explicit adapter действующего JPQL preview READ-check.

Каталог использует capability и exact registration, а не key prefixes для определения рабочих операций. READ и CREATE/UPDATE/DELETE одной сущности представлены одной строкой по прежним разным backend keys. Названия CREATE-only/read-only и EXECUTE соответствуют реальному флагу.

Подсистемы сохраняют marker identity и parent path. Значения RLS определяются по FILTERABLE и grantValues registry. Дополнительный WAREHOUSE появился в unit-проверке без добавления бизнес-списка в сервис.

## 2. Проверка

Воспроизведение из корня workspace:

```powershell
./scripts/run-permissions-p1.ps1
```

Переиспользован изолированный harness П0 с параметром Stage=P1 и generic standalone JUnit launcher. Старый запуск П0 по умолчанию сохранён. Приложение компилируется из source snapshot в собственный run-каталог; основной target и sibling libraries не перезаписываются. Dependency artifacts разрешены offline, скопированы в workspace и зафиксированы SHA256.

В проверенном source snapshot 446 main Java files. Компиляция успешна, 19 предупреждений существующих deprecated/unchecked API. JDK 21, JUnit 6.0.3, текущие application dependencies.

**Итог: 24 tests started, 24 successful, 0 failed, 0 skipped.** [Summary](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-p1/test-summary.txt).

| Набор | Проверки |
| --- | --- |
| PermissionCatalogServiceTest — 10 | Новый корень без ручного списка; разные read/write keys; CREATE-only/read-only; дополнительный WAREHOUSE; отсутствие source; регистрация вместо угадывания prefix; OWNED_ROW/INTERNAL_STORE; разные markers с одинаковыми titles; late ACTION registration; несогласованные capability; legacy/unknown/wildcard diagnostic paths. |
| PermissionContractsTest — 4 | Stable type/id после переименования; USER/ROLE id collision; точный persisted key; запрет C5 value/wildcard/ATTR/write-through-read; immutable patches; duplicate physical coordinates; provenance другого flag/value. |
| PermissionCatalogP1IT — 4 новых | Real Spring ADMIN boundary; coverage registries; отсутствие SQL/grant issuance при catalog(); реальные sources и legacy mappings, API scope links; read-only key diagnostics и сохранность исторических записей. |
| Унаследованные П0 — 6 | Standard ListForm embedding/selection; JpaFilterGrid text + additional specification для data/count; component checkbox + «Р»; exact two-role C5 sources; shared JOURNAL/BRANCH; inventory. |

Все DB-записи тестов используют изолированную H2 и rollback/create-drop. Локальная PostgreSQL не использовалась П1 для записи или выдачи прав. Проверки ADMIN-denial подтверждают отказ до SQL; metadata catalogue возвращается с prepareStatementCount=0.

## 3. Фактический результат каталога

Снимок: [catalog-p1.tsv](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-p1/catalog-p1.tsv). Каждая строка TSV — один рабочий column binding, а не отдельный объект.

| Элемент | Количество/поведение |
| --- | --- |
| Entity rows | 21 canonical roots |
| ACTION rows | 7, имена владельцев и UPDATE→EXECUTE |
| API rows | 4, подсистема исходного типа и его shared scope links |
| Settings rows | 3 действующих SettingsGroup dimensions, UPDATE-check |
| JPQL preview | 1 legacy row, READ-check через hasAnyAccess |
| Все визуальные rows | 36 |
| Рабочие columns этих rows | 75 |
| Shared sources | 2: JOURNAL и BRANCH, по три RLS flags |
| Editable represented keys | 58: 56 строковых definitions + 2 scopes |
| Diagnostic-only key | SETTINGS:Documents |
| Coverage | Все 59 registered dimensions; все 52 C5 definitions |

SETTINGS:Documents зарегистрирован на маркере, но не имеет SettingsGroup. Он получил CHECK_WITHOUT_OWNER; фиктивная рабочая галка не добавлялась. Остальные current definitions покрыты рабочими metadata bindings.

SklNomOpa остаётся read-only, AttributeValue — CREATE-only. Внутренние persistence types и owned rows не стали самостоятельными CRUD-пунктами. UNKNOWN/ATTR/`*` stored keys диагностируются и не преобразуются при чтении.

Дополнительные файлы: [source manifest](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-p1/source-manifest.json), [artifact manifest](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-p1/artifact-manifest.json), [object inventory](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-p1/objects.tsv), [definitions](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-p1/definitions.tsv). После прогона уточнён только комментарий distinct-key query; поведение проверенного кода не менялось.

## 4. Ограничения и вход следующих этапов

CellState/Contribution/Patch на П1 задают checked contracts. Они не означают готовность RLS provenance или patch-save. Наличие колонки означает поддерживаемую для назначения возможность, а не текущее разрешение субъекта.

Scope catalogue поддерживает новый зарегистрированный source, но контролируемый privileged paging ещё имеет прежний allowlist JOURNAL/BRANCH; его обобщение — П3. П1 не расширяет RLS bypass.

storedKeyDiagnostics читает только distinct keys. Отсутствующие пользователи/значения и устаревшие flags, lifecycle, конкуренция и pilot minimum остаются в следующих этапах. Четыре historical BRANCH-ссылки и ADMIN minimum из П0 не исправлялись П1.

Browser/UI, большой справочник и PostgreSQL write-concurrency этим прогоном не проверялись. Связка с AdminView и стандартными формами — П5–П9. Следующий этап — П2: batch-получение эффективных прав и полного происхождения каждой галки.
