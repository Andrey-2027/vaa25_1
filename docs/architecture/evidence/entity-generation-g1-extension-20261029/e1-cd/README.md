# E1.C/D — документный C2/D2 без UI

**Приёмка:** E1 принят отдельным повторным прогоном 2026-10-29;
[итоговое evidence](../e1-acceptance/README.md).

Дата: 2026-10-29. Пакет E1.C/D из
[плана продолжения](../../../../plans/entity-generation-g1-extension-plan.md) и
[контрактов E0](../../entity-generation-g1-extension-contracts.md). UI, Vaadin, browser,
PostgreSQL и миграции не использовались.

## Что реализовано

- C выдаёт `g1-c-summary-v2`: manifest, scenario/spec hash, отсортированные
  `dependencies` с SHA-256 effective `org.ipro` JAR, `supportSources`, `compile`,
  `runtime` и `tests.suites`. C2 без manifest или без required suite не может быть
  `success`; legacy `g1-c-summary-v1` сохраняется отдельно для `PlanReceipt`-входа.
- C проверяет `g1-generation-manifest-v2` до compile: ровно одна `CREATE` entry для E1,
  `plan-fingerprint-v2`, path/hash/bytes исходника и пересчитанный
  `g1-file-set-sha256-v2`.
- Независимые specs `empty-document` и `scalar-document` приведены к actual draft-ам A;
  `control-document` остаётся synthetic fixture для C selftest.
- D2 принимает только `document-java-v1`, matching C2/manifest, current dependency
  locations и ведёт отдельный ledger `<project>/.g1-apply-v2/`. D1 catalog не изменён.
- D2 receipt/journal содержат обязательные поля E0.4; NOOP требует те же C bytes и
  verified dependencies; relocation completed проекта допускает NOOP.
- D2 различает успешную публикацию и durable commit: receipt является границей
  `APPLIED`; после неё destructive rollback запрещён и возвращается
  `RECOVERY_REQUIRED`. Target повторно проверяется по bytes, реальному пути и
  filesystem identity перед receipt и перед успешным ответом.
- Identity берётся из `BasicFileAttributes.fileKey()`; если он недоступен, D2 пишет
  собственный xattr/ADS-токен и не считает совпадение bytes/timestamps доказательством
  владения. При неподтверждённой identity rollback ничего не удаляет.
- C отвергает любой skipped test и требуют именно
  `com.example.generated.harness.GenerationScenarioTest`; D2 делает ту же проверку
  по C2. Legacy `g1-c-summary-v1` сохранён отдельной веткой и проверен матрицей.
- Тонкий `--apply` CLI и `-Apply` режим launcher-а материализуют actual plan A без
  изменения production API A/C.

## Прогоны C

| Сценарий | planId | manifestSha256 | C summary SHA-256 | tests |
| --- | --- | --- | --- | --- |
| EmptyDocument | `g1-edb926b5688ff32b8fba23c3d0519d513f697f2f34e97c02c59157b89774e1e6` | `67d5453af0291c0f11b8943327884e40641d4afa3b1bb84adfd88cf49167351a` | `7b4414bd115dcd9c01d6bdff1841f358ef371be7dd5fc8ddb371759be33d574c` | 5/5, 0 failures/errors/skips |
| ScalarDocument | `g1-2fdd307057f01a33265214c7a1be5f2f0b2d7c9cd609fb9147c0677823d52fa5` | `6d6435a97283b6047ffff211ee7905197aca7e4a72e3529fecdac592a8f1f4e1` | `2591ea312b68df86009e4a08175b6006fa7e6e8e78fca22371a65d98f5a15176` | 5/5, 0 failures/errors/skips |

Оба прогона — `mode=generated-acceptance`, `status=success`, `runtime.startup=success`,
по 17 platform JAR без changes/missing. Source: actual `plan.files()` экспорт A
(`outputs/entity-generation-g1-extension/e1-ab/e1-c-actual-20261029/input/...`).
Summary-файлы повторного прогона: `actual-*-document-reviewfix`; C selftest с
`allSkipped` — `harness-selftest-20261029-200753`.

- [C EmptyDocument summary](c-empty-document-summary.json)
- [C ScalarDocument summary](c-scalar-document-summary.json)
- [C selftest summary](c-selftest-summary.json) — 6/6 проверок, включая `allSkipped`, `mode=selftest`.
- [C all-skipped summary](c-all-skipped-summary.json) — `failure/tests-skipped`, 5 skipped, startup=failure.

## Прогоны D

| Прогон | Результат |
| --- | --- |
| EmptyDocument, чистый project root | `APPLIED/APPLIED`, receipt v2 |
| EmptyDocument повторно | `NOOP/NOOP`, receipt не переписан |
| ScalarDocument, чистый project root | `APPLIED/APPLIED`, receipt v2 |
| ScalarDocument повторно | `NOOP/NOOP` |
| EmptyDocument, foreign target заранее | `CONFLICT/CONFLICT_UNOWNED`, bytes сохранены |
| D self-test | 61/61, включая relocation, C2 change, dependency drift, receipt failure, pending journal, foreign replacement, post-receipt rollback и late target |

Повторные actual-прогоны после review: EmptyDocument и ScalarDocument снова
`APPLIED/APPLIED` → `NOOP/NOOP` на новых project roots
(`actual-*-document-reviewfix`). Сборка и H2 runtime выполнены из этих D-applied
project-ов после копирования consumer build-support: по 5/5 тестов, `BUILD SUCCESS`.
Эффективные `org.ipro` JAR из build tree сверены с `dependencies` C2: 17/17, без
расхождений (`dependency-verification.json`). Legacy «Матрица» повторно пройдена
полным P8→C1→D1 (`kind=g1-c-summary-v1`): APPLIED→NOOP→CONFLICT_MODIFIED и
applied-project runtime 5+3, `outputs/entity-generation-g1/matrix-20261029/run-20261029-200426/`.

- [D self-test summary](d-selftest-summary.json)
- [D EmptyDocument APPLIED](d-empty-applied.json)
- [D EmptyDocument NOOP](d-empty-noop.json)
- [D ScalarDocument APPLIED](d-scalar-applied.json)
- [D ScalarDocument NOOP](d-scalar-noop.json)
- [D conflict result](d-conflict.json)
- [D v2 receipt](d-empty-receipt.json)
- [Dependency provenance](dependency-verification.json) — build tree против C2, 17/17

Пути actual evidence:
`outputs/entity-generation-g1-extension/e1-c/actual-*`,
`outputs/entity-generation-g1-extension/e1-d/actual-*` и
`outputs/entity-generation-g1-extension/e1-d/apply-selftest-20261029-reviewfix/`.
Applied-проекты содержат только `.g1-apply-v2`, lock, receipt и один generated Java;
journal после успеха отсутствует. Build/runtime evidence —
`outputs/entity-generation-g1-extension/e1-d/build-*-reviewfix/`; dependency comparison —
`outputs/entity-generation-g1-extension/e1-d/dependency-verification.json`.

## Команды

```powershell
# A: actual document plans + manifest (нужен PowerShell 7: у launcher-а #requires 7.0)
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File scripts/test-entity-generation-g1-document.ps1 -RunId e1-c-actual-20261029

# C: generated-acceptance (по одному вызову на сценарий)
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/test-entity-generation-g1.ps1 `
  -SourceFile <export>/com/example/generated/EmptyDocument.java `
  -RelativePath com/example/generated/EmptyDocument.java `
  -ExpectedSha256 a28039602c6cae7ea4ea75cd6b1d6f9be9e6fbc2e42909e2ab028574d409ad34 `
  -SpecFile scripts/entity-generation-g1/scenarios/empty-document/ScenarioSpec.java `
  -Scenario empty-document -ManifestFile <export>/manifest.json `
  -OutputDirectory outputs/entity-generation-g1-extension/e1-c `
  -RunId actual-empty-document-20261029

# D: actual apply / repeat NOOP
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/apply-entity-generation-g1.ps1 -Apply `
  -OutputDirectory outputs/entity-generation-g1-extension/e1-d `
  -RunId actual-empty-document-20261029 `
  -ProjectRoot outputs/entity-generation-g1-extension/e1-d/actual-empty-document-20261029/project `
  -SourceRoot src/main/java `
  -CSummary outputs/entity-generation-g1-extension/e1-c/actual-empty-document-20261029/summary.json `
  -ReviewedPlan outputs/entity-generation-g1-extension/e1-ab/e1-c-actual-20261029/input/empty-document/reviewed-plan.json `
  -DependencyTree outputs/entity-generation-g1-extension/e1-c/actual-empty-document-20261029/consumer-runtime-dependency-tree.json `
  -SkipCoreBuild
```

## Границы и что не закрыто

- E1.C/D — только **один** CREATE-файл. Multi-file, sections, references, owner, ZIP и
  N-file rollback относятся к E2–E4.C0/D0.
- Browser/UI приёмка E1.B и E5.B не входит в этот пакет.
- Receipt failure, relocation и dependency drift проверены D self-test на synthetic
  dependency JAR. Actual прогоны подтверждают реальные platform hashes, APPLIED/NOOP и
  conflict preservation; отдельный actual dependency-drift требует копии repository,
  которую нельзя портить в общем `.m2`.
- D2 не использует `attrs:creation:modified:size` как identity. Если `fileKey`
  отсутствует, пишется собственный xattr/ADS-токен; при невозможности доказать identity
  target сохраняется, а результат — `RECOVERY_REQUIRED`.
- SQL/миграции, deployment и migration gate M не запускались.
