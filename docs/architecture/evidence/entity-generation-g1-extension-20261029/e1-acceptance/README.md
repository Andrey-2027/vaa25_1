# Приёмка E1 — Документ

Дата: 2026-10-29. Результат: **E1 принят** в однофайловом scalar scope.
Блокирующих замечаний по проверенной матрице не осталось.
Browser/E5.B и migration gate M остаются открытыми отдельными обязательствами.
Production sources при приёмке не менялись; обновлены evidence и статусы документации.

Рабочий run: `outputs/entity-generation-g1-extension/e1-acceptance/20261029-r1/`.
Свежий A/P8 run: `outputs/entity-generation-g1-extension/e1-ab/e1-acceptance-20261029-r1/`.

## Проверенный маршрут

1. Повторён core/JSON/runtime/P8 reactor на JDK 21, offline Maven, без install общего
   SNAPSHOT cache: создание/импорт документа, number/date, семь scalar mappings,
   dirty/freshness/access и preview/download.
2. EmptyDocument/ScalarDocument получены из настоящего builder свежего A run,
   с draft/context/reviewed plan/manifest. Для каждого пройден actual C2.
3. Созданы два новых consumer project из build-support без business entity source.
   До D из собственного pom каждого consumer получен runtime dependency tree.
   Все 17 effective platform JAR id/hash совпали с C2. D получил paths из этого
   current consumer resolution, а не из historical C tree.
4. D2 создал exact Java bytes: APPLIED → NOOP. Receipt соответствует C summary hash
   и manifest; journal после успеха отсутствует.
5. Build/H2 runtime выполнен из проектов, заполненных D: metadata/numbering,
   canonical create/read/update/reopen, display name, сохранность номера,
   обязательная дата, scalar constraints и отказы без grants.
6. После runtime повторно сверены source bytes/hash/size, receipt binding,
   фактический dependency set и hashes всех 17 JAR. Изменений нет.

## Результаты

| Проверка | Результат |
| --- | --- |
| Core/JSON/runtime/P8/session/workspace | 21 suites, 156 tests; failures/errors/skips = 0 |
| Source contract checks | 33/33 |
| C selftests | 6/6: positive catalog/document, broken compile, hash refusal, no tests, all skipped |
| D selftests | 61/61; failed=0, skipped=0 |
| Независимые probes прежнего review | 5/5 ожидаемых исходов |
| EmptyDocument actual C2 | 5 tests, success; no failures/errors/skips |
| ScalarDocument actual C2 | 5 tests, success; no failures/errors/skips |
| EmptyDocument и ScalarDocument D2 | Каждый APPLIED → NOOP; exact source, matching receipt, no journal |
| EmptyDocument applied-project runtime | 5 tests, BUILD SUCCESS; no failures/errors/skips |
| ScalarDocument applied-project runtime | 5 tests, BUILD SUCCESS; no failures/errors/skips |
| Completed relocation | NOOP с ledger скопированного completed project |
| Modified target | CONFLICT_MODIFIED; чужая правка сохранена |
| Actual JAR drift | REJECTED/DEPENDENCIES_CHANGED; изменена только отдельная копия JAR в outputs |
| Legacy Matrix C1 | 5 tests, success, kind=g1-c-summary-v1 |
| Legacy Matrix D1 | APPLIED → NOOP → CONFLICT_MODIFIED; изменённые bytes сохранены |
| Legacy Matrix applied-project runtime | 5 GenerationScenarioTest + 3 MatrixRuntimeTest; BUILD SUCCESS |

156 — tests основного reactor run. Повторные C/runtime scenarios не прибавляются
к ним как уникальные tests. Selftests/probes приведены отдельно. Legacy backend
повторён из сохранённого actual P8 Matrix export; текущий D1 заново проверил его
через validator/generator. Matrix-specific UI test в этом run не повторялся;
прежний P8 export не перезаписан.

## Закрытие замечаний review

| Замечание | Повторная проверка |
| --- | --- |
| Foreign identical replacement с прежними timestamps удалялся | RECOVERY_REQUIRED/TARGET_IDENTITY_CHANGED; replacement сохранён |
| Сбой journal после durable receipt удалял committed output | RECOVERY_REQUIRED/JOURNAL_WRITE_FAILED; target, receipt, journal сохранены |
| После изменения target сообщался APPLIED | RECOVERY_REQUIRED/TARGET_MODIFIED; изменённые bytes и journal сохранены |
| C success при полностью skipped suite | failure/tests-skipped, skipped=5, startup=failure |
| Legacy C1/Matrix перестал проходить | Свежие actual C1/D1 и applied runtime прошли |
| D2 принимал произвольную required suite | REJECTED/C_TESTS_INCOMPLETE до создания target |
| Late target имел неверный статус | ROLLED_BACK/CONFLICT_LATE_TARGET; чужой target сохранён, journal убран |

Исходный ReviewProbe повторён на отдельной копии текущих D sources и core classes.
Деструктивные ветки используют только собственные fixtures в outputs. Hard links
проверены вне ограничений sandbox. D snapshot после прогона сверён с текущими sources.
Identity соответствует обновлённому E0: platform fileKey либо собственный xattr/ADS
marker; неизвестная identity не разрешает DELETE. Общий Maven cache не менялся.

## Evidence

- [Машинный итог](acceptance-summary.json), [actual source/binding/dependency verification](actual-verification.json).
- [Core/JSON/P8 results](p8-test-results.json), [build log](p8-tests.log), [XML](p8-reports/).
- [Source contract checks](contract-results-source-only.json).
- [C selftests](c-selftest-summary.json), [all-skipped refusal](c-all-skipped-summary.json).
- [D selftests](d-selftest-summary.json), [D log](d-selftest.log).
- [Независимые probes](review-probes/), [их log](independent-probes.log).
- [EmptyDocument C/plan/manifest/receipt/runtime](empty-document/).
- [ScalarDocument C/plan/manifest/receipt/runtime](scalar-document/).
- [Relocation](relocation.json), [modified-target preservation](modified.json), [copied-JAR drift](dependency-drift.json).
- [Legacy Matrix C1/D1/runtime](matrix/), [проверенные D source hashes](d-source-hashes.json).

## Воспроизведение

```powershell
./scripts/test-entity-generation-g1-document.ps1 -RunId <new-run>
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-entity-generation-g1.ps1 -SelfTest -OutputDirectory <new-output>/c-selftest
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/apply-entity-generation-g1.ps1 -OutputDirectory <new-output>/d-selftest
```

Для actual C задаются SourceFile/RelativePath/ExpectedSha256, независимый ScenarioSpec
и ManifestFile нового A export. Consumer template копируется в новый project с его
ScenarioSpec; Maven `generate-test-resources` получает current tree. D `-Apply`
получает project, ReviewedPlan, C summary и current project DependencyTree.
Повтор D даёт NOOP. `mvn -o -f <applied-project>/pom.xml test` проверяет применённый Java.
В принятом run D скомпилирован напрямую javac в отдельный support-classes с immutable
snapshot core classes; использован тот же ApplyHarness --apply API. Логи/classpath
сохранены в рабочем run root; конкурирующих сборок общего core target не было.

## Границы

Приняты standard-document-v1@1, document-java-v1/F2, M2/C2/D2, один CREATE;
legacy catalog-java-v1/F1/C1/D1 сохранён. Generated entities не установлены в основное
приложение. Ссылки, owner и sections остаются будущими срезами. H2 create-drop
не подтверждает миграции. Browser smoke не выполнялся: обязательная браузерная
матрица остаётся E5.B, migration gate M — после E5.
Следующий функциональный срез — **E2: ссылки на существующие сущности**.
