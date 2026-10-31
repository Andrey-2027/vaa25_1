# E4.C0 — N-file C2 без UI

Дата: 2026-10-29. Пакет C из
[плана §10](../../../../plans/entity-generation-g1-extension-plan.md) и
[контрактов E0.4](../../../entity-generation-g1-extension-contracts.md). UI, Vaadin,
browser, PostgreSQL, D и миграции не использовались.

## Результат

C2 расширен с одного source до полного manifest-комплекта без нового transport-формата:

- `-SourceDirectory` + `g1-generation-manifest-v2`; каждый entry материализуется в
  чистый isolated consumer и сверяется по path/UTF-8 bytes/SHA-256.
- Missing, extra, duplicate и изменённый source отклоняются до Maven.
- `g1-c-summary-v2` содержит `sources` (ровно entries, порядок сохранён),
  `sourceSetSha256 = manifestSha256`; `supportSources` не содержит generated paths.
- Для N>1 выполняется `MultiFileScenarioTest`; он проверяет `@TableSections`,
  `@TableSectionMetadata` linkage, parent/lineNumber, опубликованные row fields и
  aggregate save/reopen обеих секций. Одиночный вход по-прежнему требует
  `GenerationScenarioTest`.
- `success` больше не может опираться на один suite: проверяются все required suites,
  а N-file summary не подтверждает один root без rows.

Контрольный комплект: `MultiFileRoot` + `MultiFileRow` + `MultiFileNote`. Это synthetic
fixture C; actual A→C для E4 будет выполнен после E4.A. D и его N-file применение
относятся к E4.D0.

## Прогон

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/test-entity-generation-g1.ps1 -SelfTest `
  -OutputDirectory outputs/entity-generation-g1-extension/e4-c0 -KeepIsolation
```

Run: `outputs/entity-generation-g1-extension/e4-c0/harness-selftest-20261029-204003`.

| Проверка | Результат |
| --- | --- |
| 12 selftest checks | 12/12 passed |
| single catalog/document | success, 5/5 |
| N-file positive | success, 3 sources, `MultiFileScenarioTest` 2/2 |
| dependencies | 17 platform JAR, без changed/missing |
| missing source | rejected `manifest-source-missing`, Maven не запускался |
| extra source | rejected `manifest-source-unexpected`, Maven не запускался |
| duplicate entry | rejected `manifest-duplicate`, Maven не запускался |
| changed row | rejected `manifest-mismatch`, Maven не запускался |
| broken row | compile failure, Maven exit ≠ 0 |
| all skipped | `tests-skipped`, startup=failure |

## Артефакты

- [C selftest summary](c-selftest-summary.json)
- [N-file C summary](c-multifile-summary.json)
- [single-file C summary](c-single-summary.json)
- [missing-source rejection](c-multifile-missing-summary.json)
- `manifest.json`, `sources`, `maven.log`, `reports/`, dependency tree и platform JAR
  hashes находятся в run-каталоге выше.

## Границы

- Это `mode=selftest`; он не разрешает production apply.
- Actual N-file output генератора A, D2-применение, build/runtime из applied проекта и
  browser/UI не закрыты этим пакетом.
- Legacy C1 (`g1-c-summary-v1` + `PlanReceipt`) не изменён; одиночный v2 C2 сохраняет
  прежние поля и required suite.
