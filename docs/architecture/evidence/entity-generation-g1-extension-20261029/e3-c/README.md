# E3.C — actual приёмка владельца

Дата: 2026-10-30. E3.C принят; вместе с E3.R/A/B закрывает функциональный gate E3
в согласованном scope одного обязательного immutable owner существующего CATALOG
Long-ID типа. E4, E5/browser и migration gate M остаются открытыми.

## Проверенный маршрут

1. Новый OwnerAcceptanceExportTest пропускает owner draft и document envelope v1/v2
   через настоящий JSON codec → validator → EntityJavaGenerator. Экспортирует raw
   input/context/reviewed plan/manifest/Java. Ручной source для acceptance не используется.
2. OwnedCatalog имеет counterparty owner и scalar description; target — existing
   ReferenceTarget из отдельного `g1-reference-target:1.0-E2` JAR. Дополнительный
   `g1-owner-support:1.0-E3` JAR содержит OrdinaryOwnerReference: существующий control
   root с обычной ссылкой owner, без capability. Оба JAR — зависимости, не generated
   entries. Каждый manifest содержит ровно один Java source.
3. Для owner выполнен actual C2 с независимым OwnerScenarioTest. Для document v1/v2
   выполнены отдельные actual C2 с GenerationScenarioTest. C фиксирует required suite
   и effective org.ipro dependency hashes, включая support JAR.
4. Три новых consumer созданы из support template без business source. До D каждый
   получил собственный Maven runtime tree: 19 dependency locations у owner, 17 у
   каждого document. D получил paths из этого resolution и сам пересчитал hashes.
5. D2 опубликовал exact source bytes: APPLIED → NOOP для каждого case. Receipt
   связан с manifest/planId/C summary; успешный journal отсутствует.
6. Из этих D-applied projects выполнен новый Maven/H2 runtime run. После него source,
   receipt bindings и все dependency hashes сверены повторно.

## Результаты

| Проверка | Результат |
| --- | --- |
| Core/JSON/runtime bridge/platform forms/P8/Explorer | 39 suites, 255 tests; failures/errors/skips=0 |
| Source contract checks | 33/33 |
| D selftests текущего snapshot | 75/75; failures/skips=0 |
| OwnedCatalog actual C2 | 8 runtime tests, success |
| OwnedCatalog D2 | APPLIED → NOOP; exact bytes/receipt, no journal |
| OwnedCatalog applied runtime | 8 tests, BUILD SUCCESS |
| Document envelope v1 actual C2/D2/runtime | 5 tests → APPLIED/NOOP → 5 tests, success |
| Document envelope v2 owner=null actual C2/D2/runtime | 5 tests → APPLIED/NOOP → 5 tests, success |
| Owner: generic suite под прежним manifest | REJECTED/C_TESTS_INCOMPLETE до CREATE |
| Owner: изменённая копия target JAR, facts/plan прежние | REJECTED/DEPENDENCIES_CHANGED до CREATE и на NOOP |
| Перестановка C summaries document v1/v2 | Оба REJECTED/C_SUMMARY_MISMATCH при одинаковых Java bytes |
| Source/receipt/dependency/snapshot verification | 103 checks, все passed |

255 — один итоговый regression reactor. Повторные C/applied/runtime прогоны и
selftests приведены отдельно и не прибавлены к нему как новые уникальные tests.
75 D selftests включают существующий N-file foundation; это не приёмка E4 generation.
Legacy F1/scalar goldens, JSON fixtures и старые binary constructors проверены в
основном reactor; actual Matrix C1/D1 заново в E3.C не запускался.

## Actual owner runtime

- Runtime descriptor распознаёт explicit CatalogOwner(counterparty); тип цели,
  LAZY/no cascade/optional=false/JoinColumn nullable=false/NotNull, list/select
  code/name/counterparty/description и orders 30/40 проверены независимо от source.
- Создание под A, повторное чтение, scalar update с сохранением code, owner-filter
  A/B в LIST и LOOKUP. Ссылочные поля читаются после закрытия persistence context.
- Required-null и missing target отклоняются. Попытка A→B получает
  OWNER_IMMUTABLE и оставляет persisted A. Другой повторно загруженный detached
  экземпляр A с тем же id/version разрешён; его изменённое name не обновляет target.
- Явный CatalogOwnerContext принимает create с A, отклоняет подменённый B и
  восстанавливает thread context после success/failure.
- Удаление используемого A отклоняется; удаление child сохраняет A/B, их code/name.
- Owner не выдаёт root create/read/update или target lookup grants. Обычная ссылка
  owner на control root меняется A→B и очищается; child delete не удаляет targets.

Генерация и runtime owner документов не включены. Document v1/v2 имеют одинаковый
Java source и разные planId. Document v2 с non-null owner сохраняется JSON codec
как invalid input; validator выдаёт OWNER_UNSUPPORTED_RECIPE на owner без fieldId,
checked/plan не выдаётся. Fixtures и diagnostics сохранены ниже.

Проверки используют существующие platform JAR; platform SNAPSHOT не
переустанавливались. Установлен только новый support artifact 1.0-E3. Для drift
менялась копия reference-target JAR в outputs, общий cache не подменялся. Browser,
deployment и schema migration не выполнялись. Aggregate guards проверены ранее
в E3.R/B; секции actual generated owner подключаются в E4.

## Evidence

- [Машинный итог](summary.json), [103 checks](verification.json), [D source hashes](d-source-hashes.json).
- [255 tests](test-results.json), [log](tests.log), [XML](regression-reports/).
- [OwnedCatalog input/C/D/runtime](owned-catalog/).
- [Document v1](document-v1/), [Document v2](document-v2/).
- [Invalid document owner](invalid-document-owner.json), [diagnostics](invalid-document-diagnostics.json).
- [Required-suite refusal](owner-wrong-suite.json), [JAR drift CREATE](owner-drift-create.json), [NOOP](owner-drift-noop.json).
- [Cross-envelope C refusal v1](document-v1-cross-summary.json), [v2](document-v2-cross-summary.json).
- [D selftests](d-selftest-summary.json), [contract checks](contract-results.json), [fingerprints](source-fingerprints.json).
- [Support fixture sources/specs](support-fixtures/).

Рабочий каталог: `outputs/entity-generation-g1-extension/e3c/work/`.
Regression/export: `outputs/entity-generation-g1-extension/e1-ab/e3c-acceptance-r1/`.
Actual C: `work/c/owned-catalog-r4`, `work/c/document-v1-r2`, `work/c/document-v2-r2`.
Applied projects: `work/applied/{owned-catalog,document-v1,document-v2}`.
Diagnostic runs не заменяют перечисленные итоговые результаты.

## Воспроизведение

JDK 21, PowerShell 7, существующий reviewed platform Maven cache:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Axiom/AxiomJDK-21'
$maven = 'C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.3/plugins/maven/lib/maven3/bin/mvn.cmd'
& $maven -o -Dmaven.repo.local=C:/Users/strim/.m2/repository -f scripts/entity-generation-g1/owner-support/pom.xml install
pwsh -File scripts/test-entity-generation-g1-document.ps1 -RunId <new-run> -IncludeOwnerForms -LocalRepository C:/Users/strim/.m2/repository
```

Fixture target `g1-reference-target:1.0-E2` должен уже существовать, либо быть собран
отдельно из reference-target/pom.xml. Новый export лежит в owner-acceptance-input.
Для каждого case передать actual SourceFile, RelativePath/ExpectedSha256 из
manifest, ManifestFile и соответствующий ScenarioSpec в test-entity-generation-g1.ps1.
Маркер owner suite выбирает g1-owners и OwnerScenarioTest; documents используют
общий default profile/test.

Новый applied consumer создать из consumer template; Spec и owner sidecar положить
в test harness, generated source не копировать. Получить собственный runtime tree
Maven generate-test-resources (у owner с -Pg1-owners). Вызвать
apply-entity-generation-g1.ps1 -Apply с ProjectRoot/CSummary/ReviewedPlan и
DependencyTree `<consumer>/target/consumer-runtime-dependency-tree.json`; повторить
для NOOP. Затем выполнить Maven test из applied project: у owner
`-Pg1-owners -Dtest=OwnerScenarioTest`, у documents `-Dtest=GenerationScenarioTest`.
Каждый повтор требует fresh output/project и matching C/current dependency set.
