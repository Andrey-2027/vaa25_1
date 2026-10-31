# E5.H — согласованная сборка и общий автоматический прогон

Дата: 2026-10-31. **E5.H выполнен: 15/15 actual routes, 1204 итоговых checks passed.**
E5.B (браузер) и M (миграции) не запускались и остаются открытыми.

## Артефакты одного baseline

Рабочий run: `outputs/entity-generation-g1-extension/e5-h/e5h-20261031`.
JDK 21, IntelliJ Maven 3.9.9, PowerShell 7. Все Maven builds и consumers имеют
раздельные build directories внутри run. Установки выполнялись последовательно
в новый `run/repository`, без изменения общего `~/.m2/repository`.
Единственный mirror — `file:///C:/Users/strim/.m2/repository`; сетевой repository
не использовался. Отсутствующие checksum-файлы локального mirror дают Maven
warnings; реальные consumed bytes дополнительно фиксируются SHA-256.

Собраны и установлены 18 текущих модулей этого репозитория:
`platform-bom`, `platform-contracts`, `platform-events`, `platform-persistence`,
`platform-metadata`, `platform-numbering`, `platform-settings`, `platform-telemetry`,
`platform-rls`, `platform-core`, `platform-register`, `platform-navigation`,
`platform-spring-boot-autoconfigure`, `platform-vaadin`, `platform-backend-starter`,
`entity-design-core`, `entity-design-json`, `entity-design-runtime`.
Также заново собраны support JAR `g1-reference-target:1.0-E2` и
`g1-owner-support:1.0-E3`. Первый build использует `maven.test.skip=true` только
для установки общего baseline; проверенные тесты перечисляются отдельно ниже.

Внешние peer JAR (crudui/filtergrid и их identity/crud API leaf artifacts),
библиотеки фреймворков и не затронутые report/query-артефакты взяты из локального
cache. Это зафиксированные зависимости, а не заявление о пересборке всех соседних
репозиториев или всего приложения. Реальные runtime dependency trees/classpaths
сохранены у consumers. Наличие platform-register в backend closure не означает
приёмку register generation/CTE или миграций.

Свежие JAR не переустанавливались между C и D. До итоговой приёмки сверяются hashes
всех собранных JAR/POM, bytecode producer/backend/component builds, C dependencies,
D verifiedDependencies и effective consumer dependency locations. D сам пересчитывает
bytes по текущим paths до CREATE/NOOP; одной внешней сверкой отчёта это не заменено.
Generated section form consumers используют установленный platform-vaadin JAR
этого же baseline через `UseInstalledPlatform`, без отдельной подмены реализации.

## Проверки

| Категория | Результат |
|---|---|
| Backend/design/JSON/runtime/metadata/canonical/security/autoconfigure | 83 suites, 481 tests; failures/errors/skips=0 |
| P8/forms и затронутые production boundaries | 33 suites, 161 tests; failures/errors/skips=0 |
| API baseline checks, 13 явно выбранных platform artifacts | 4 tests; failures/errors/skips=0 |
| C harness selfchecks | 12/12 passed |
| D legacy/extension/three-file selftests | 87/87 passed, 0 failed/skipped |
| Actual builder/P8 → C → D → applied runtime | 15/15 routes, 100 C tests + 103 applied-runtime tests; failures/errors/skips=0 |
| Actual downloaded P8 bytes → standard forms + H2 | 7 consumers, 28 tests; failures/errors/skips=0 |
| Actual negative probes | 5/5 ожидаемых отказов |
| Итоговая сверка hashes/bindings/reports/producer bytecode | 1204/1204 passed |
| Независимая проверка сохранённого архива | 783/783 passed |
| Source contract checks | 33/33 passed (source-only; module tests отдельно выше) |

Счётчики разных категорий не суммируются как уникальные бизнес-сценарии.
Повторные диагностические/исправляющие прогоны не прибавлены к accepted totals.
Нулевые skips относятся к положительным приёмочным suites. В C selftest есть
специальный отрицательный `allSkipped`: его пропущенные тесты обязаны привести к
отказу C; это одна из 12 успешно проверенных защит, а не successful acceptance.

Матрица actual: legacy Matrix; EmptyDocument и ScalarDocument; ReferenceCatalog
и ReferenceDocument; OwnedCatalog; document envelopes v1/v2 с owner=null;
catalog/document с 0/1/2 sections; OwnedCatalogSections2 с row reference.
В каждом route: exact builder/P8 bytes → независимая spec → C → clean project
D APPLIED → whole-set NOOP → новый Maven/H2 runtime из D-applied sources →
conflict copy с изменением последнего generated файла и сохранением чужих bytes.
JSON envelope v1/v2 остаются отдельными plan bindings даже при одинаковом Java.
Один и тот же принятый C используется для APPLIED/NOOP/conflict своего route.
Matrix сохраняет legacy F1/C1/D1; остальные 14 routes используют F2/M2/C2/D2.

Семь section inputs заново произведены настоящим JSON codec/validator/builder,
затем переданы в обычный P8 import/preview/download. Каждый downloaded entry и
manifest сверены с этим планом и теми же C inputs. ZIP/single Java сохранены.
Legacy Matrix экспортирует новый P8 download и captured F1 plan в отдельный
`design.matrix.output`; прежнее evidence и старый Matrix input не перезаписывались.

Actual negative probes для combined owner + two-section комплекта:

- Missing/changed последняя row → C `manifest-source-missing` / `manifest-mismatch`,
  Maven не запускался.
- Изменённая копия dependency JAR → D `REJECTED/DEPENDENCIES_CHANGED` до CREATE
  и на NOOP; исходный JAR и все существующие targets сохранены.
- Подменённый required suite при прежнем manifest → D
  `REJECTED/C_TESTS_INCOMPLETE`, generated targets не созданы.

## Синхронизация manifests и API

В `scripts/local-dependencies.json` обновлены только четыре устаревших fingerprints:
platform-contracts, platform-core, entity-design-runtime и
platform-spring-boot-autoconfigure. Алгоритм прежний ordinal
`gitvaa-source-tree-sha256-v2`; входы, hashes и количества файлов сохранены до/после.
Остальные пересобранные модули уже соответствовали manifest.

Прикладной reviewed состав core дополнен семью уже реализованными owner-типами.
Роли: APP_API — CatalogOwnerContext и CatalogOwnerViolationException; MODULE_API —
CatalogOwnerDescriptor/Resolution/Resolver; INTERNAL — CatalogOwnerGuard/Token.
Context и стабильные причины отказа явно экспортируются для внешних consumers;
не требуется перевод handwritten host-моделей на владельца. Core budgets —
45 APP_API, 6 APP_SPI, 58 MODULE_API, 18 INTERNAL, всего 127 top-level типов.
Vaadin budget учитывает существующий INTERNAL CatalogOwnerForms: 140 типов.

Обновлены ровно четыре API snapshots:

1. platform-contracts: декларация `@CatalogOwner`.
2. platform-core: owner context/diagnostics и межмодульные owner facts.
3. platform-spring-boot-autoconfigure: существующий E3 bean method получил
   ObjectProvider<EntityManager>, необходимый aggregate owner guard. Это изменение
   сигнатуры конфигурационного метода уже было в production source; E5.H синхронизирует
   baseline, а не вводит его заново и не заявляет сохранение старой сигнатуры этого метода.
4. platform-vaadin: owner facts в EntitySummary; прежний constructor сохранён,
   новый overload и accessor отражены в snapshot.

До записи snapshots сохранены исходные файлы и failed comparison. После записи
выполнено обычное сравнение без `platform.api.baseline.write`. Artifact filter
поддерживает явный список и запрещает молча пропустить неизвестный ID.

## Найденные проблемы проверок и границы

Первый широкий component run обнаружил устаревшие reviewed owner lists/budgets.
ArchUnit также включал test-classes из нестандартного evidence build directory:
стандартный DoNotIncludeTests ориентировался на default Maven layout. Его
дополнительный import filter теперь исключает `/test-classes/` в любом build root;
production правила о зависимости core от приложения/Vaadin сохранены и проходят.
Уточнена проверка exported standard bases: mapped-superclass assertions относятся
к StandardCatalogEntity/StandardDocumentEntity, а не к новым owner command API.
Production generator/canonical/owner/aggregate механизмы в E5.H не менялись.

**Явно вне scope:**
`PlatformCoreSurfaceTest.applicationTestReferencesToInternalOrModuleTypesAreFrozenPerFile`
сейчас обнаруживает отдельный register/CTE overlay drift:
`TwoDimensionRegisterCteSpikeTest → SectionMetadataRegistry`. Этот метод исключён
из целевого E5.H filter по §13/E5.H.2 плана. Остальные шесть production/API surface
проверок выполняются. Это названное исключение области, не скрытый JUnit skip
и не исправление register/CTE в рамках G1. Failed исходный отчёт сохранён.

## Evidence и воспроизведение

- [Машинный итог](summary.json), [финальная сверка](verification.json),
  [проверка сохранённого архива](archive-verification.json).
- [Artifact hashes](build-complete.json), [repository inventory](repository-inventory.json),
  [source fingerprints до/после](source-fingerprints.json),
  [финальные source inventories](source-fingerprints-final.json).
- [Backend reports/log](backend/), [component reports/log](ui/), [API reports/before/after](api/).
- [Actual inputs](inputs/), [скачанные P8 bytes](delivery/),
  [C/D/applied runtime по каждому route](actual/), [actual results](actual-results.json).
- [Generated forms](forms/), [negative probes](negatives/), [negative results](negative-results.json).
- [C selfchecks](c/), [D selftests/proofs](d/), [source contract checks](contracts/).
- [Исходные manifests](local-dependencies.before.json), [обновлённые](local-dependencies.after.json),
  [source snapshots](sources/), [source hashes](source-hashes.json),
  [предыдущие диагностические прогоны](prior/).

Из корня репозитория с новым уникальным RunId:

```powershell
$id = 'e5h-new-run'
foreach ($stage in @('Build','Backend','Ui','Api','Selftests','Actual','Forms','Negatives','Verify')) {
    & scripts/test-entity-generation-g1-integration.ps1 -RunId $id -Stage $stage
    if (-not $?) { throw "E5.H stage failed: $stage" }
}
& scripts/save-entity-generation-g1-integration-evidence.ps1 -RunId $id `
    -EvidenceDirectory docs/architecture/evidence/entity-generation-g1-extension-20261029/e5-h-new
& scripts/verify-entity-generation-g1-integration-evidence.ps1 `
    -EvidenceDirectory docs/architecture/evidence/entity-generation-g1-extension-20261029/e5-h-new
```

Среда должна разрешать Java/Maven subprocesses, hard links и junctions. В текущем
прогоне три последних actual routes дополнительно выполнялись отдельным вызовом
`-ActualCases sections-document-1,sections-document-2,sections-owned-catalog-2`:
потребители и output roots различны, install/JAR baseline общий и неизменяемый.
Основной orchestrator затем собирает все 15 completed results; готовый route
повторно не считается новой проверкой. Не запускать один case конкурентно:
существующий незавершённый project требует явного разбора, а не перезаписи.

Archive хранит source bytes и отчёты, не Maven cache/скомпилированные JAR.
Абсолютные paths в C/D/receipt оставлены provenance исходного run; при архивировании
их не переписывали. `verify-entity-generation-g1-integration-evidence.ps1` проверяет
копии без обращения к живым applied projects. Для нового выполнения нужны новые
изолированные consumers. Браузерная приёмка, deployment и миграции не заявляются.
