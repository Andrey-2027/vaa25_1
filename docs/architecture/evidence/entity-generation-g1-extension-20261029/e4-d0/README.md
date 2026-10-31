# E4.D0 — N-file D2 без UI

Дата: 2026-10-30. Пакет D из
[плана §10](../../../../plans/entity-generation-g1-extension-plan.md) и
[контрактов E0.4](../../../entity-generation-g1-extension-contracts.md). UI, Vaadin,
browser, PostgreSQL, SQL и миграции не использовались. C summary в этом прогоне —
synthetic harness fixture, а не generated-acceptance.

## Результат

D2 расширен с одного source до полного manifest-комплекта без нового формата state:

- `plan.files()` содержит 1..N `CREATE` entries; пути уникальны case-insensitive,
  каждый SHA-256 и UTF-8 byte count сверены до записи.
- C2 обязан совпадать с полным manifest. Для N>1 обязательны `sources` ровно в
  порядке entries, `sourceSetSha256 = manifestSha256` и required suite
  `com.example.generated.harness.MultiFileScenarioTest`.
- Под project lock preflight проверяет **все** targets до первой записи: unowned,
  modified, identical foreign и foreign receipt дают CONFLICT; отсутствующий target
  собственного/чужого receipt даёт RECOVERY_REQUIRED.
- Публикация идёт последовательно в порядке manifest: temp + hash + exclusive hard
  link, journal с `entryIndex` обновляется до перехода к следующему entry.
  APPLIED возможен только после проверки всего набора и durable receipt; NOOP — только
  при собственном receipt на весь набор и неизменных targets.
- Rollback удаляет только доказанно собственные неизменённые entries. В late-кейсе
  второго target первый опубликованный файл удаляется, поздний чужой сохраняется;
  после durable receipt destructive rollback запрещён.
- D1 legacy остаётся однофайловым: его `multi-file-rejected` guard не изменён.

Multi fixture: сгенерированный document root + synthetic
`AppliedDocumentSections.java`, проверенный тем же `GenerationApplyV2`. Freshness
подтверждается identity rebuilder только в selftest; production оставляет публичный
validator/generator rebuild.

## Прогон

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/apply-entity-generation-g1.ps1 `
  -OutputDirectory outputs/entity-generation-g1-extension/e4-d0 `
  -RunId d0-final -SkipCoreBuild
```

Run: `outputs/entity-generation-g1-extension/e4-d0/d0-final`.
Результат: **75/75 passed, 0 failed, 0 skipped**.

| Проверка | Результат |
| --- | --- |
| baseline D1/D2 (66 кейсов) | 66/66 passed |
| N-file APPLIED→NOOP | APPLIED/APPLIED, затем NOOP; оба target hash совпадают, receipt entries=2 |
| modified second target | CONFLICT/`CONFLICT_MODIFIED`, первый target не изменён |
| missing second target | RECOVERY_REQUIRED/`OWNED_TARGET_MISSING`, не пересоздан |
| identical unowned second target | CONFLICT/`CONFLICT_UNOWNED` до записи первого |
| late second target | ROLLED_BACK/`CONFLICT_LATE_TARGET`; первый удалён, поздний сохранён |
| missing `sources` | REJECTED/`C_SUMMARY_MISMATCH` до записи |
| mismatched `sources` | REJECTED/`C_SUMMARY_MISMATCH` до записи |
| case-collision двух targets | REJECTED/`TARGET_CASE_COLLISION` до записи |
| foreign receipt на одном target | CONFLICT/`CONFLICT_OTHER_PLAN`; чужой target сохранён, новый не создан |

Первый промежуточный прогон после изменения D2 дал единичный
`APPLY_IO` на D1 journal rename (`empty-catalog-applied`); немедленный повтор прошёл
66/66, финальный прогон — 75/75. Падение не воспроизвелось; отдельного изменения
D1/D2 для него не потребовалось. Это остаётся наблюдением о возможной transient
Windows file lock, а не доказанной регрессией.

## Артефакты

- [D selftest summary](d-selftest-summary.json)
- `launcher.log`, `javac.log`, `selftest.log`, `status.txt`, `work/` — в run-каталоге.

## Границы

- Это D selftest с synthetic multi plan и одним synthetic dependency JAR; он не
  разрешает production apply и не заменяет actual generated-acceptance.
- Actual N-file plan из E4.A, matching C2, D apply в чистый проект, build/runtime из
  applied проекта и browser/UI остаются после E4.A/C.
- D1 legacy не получил N-file; миграции, SQL и deployment не выполнялись.
