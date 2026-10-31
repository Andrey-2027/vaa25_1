# E4 — дополнение D-проверок трёхфайловым комплектом

Дата: 2026-10-31. Результат: **87/87 D selftests passed, 0 failed, 0 skipped**.
Это прежние 75 проверок и 12 новых, без суммирования повторных прогонов.
Функциональная и компонентная приёмка E4 завершена с учётом ранее принятых
[actual A→C→D/runtime E4.A](../e4-a/README.md) и
[P8/download/standard forms E4.B](../e4-b-completion/README.md).
E5.H, E5.B и migration gate M остаются открытыми; в этом пакете они не запускались.

## Комплект и граница проверки

Каждый новый case получает документ `AppliedDocument` и две настоящие generated
rows `AppliedDocumentRow1/2`. Draft с двумя SectionDraft проходит публичные
DraftValidator и EntityJavaGenerator (`document-java-v4`, F2/M2/C2/D2).
GenerationApplyAdapter использует обычный freshness rebuild, без identity rebuilder.
Все три исходника и manifest находятся в сохранённых reviewed plans.

C summary и один dependency JAR здесь являются **synthetic D selftest fixtures**.
Required suite для настоящего section draft — `SectionsScenarioTest`; это
название проверяется D. Данный пакет не заявляет новый actual compile/runtime C:
реальные комплекты обоих видов уже приняты в E4.A. Production D не изменялся:
изменён только ApplyHarness и выбор required suite в его synthetic summary.

## Новые сценарии

| Case (`three-file-…`) | Ожидание и проверяемый результат |
|---|---|
| applied-noop | APPLIED → NOOP, receipt содержит все три entries; полный snapshot файлов при NOOP не меняется |
| io-before-file-2 | Инъекция IOException перед второй публикацией после первой; ROLLED_BACK, все targets/temp/journal и созданные source directories убраны |
| io-before-file-3 | Та же проверка после двух публикаций, до третьей |
| late-third | После двух публикаций появляется чужой третий target; ROLLED_BACK/CONFLICT_LATE_TARGET, первые два удалены, чужие bytes сохранены, receipt/journal/temp отсутствуют |
| io-after-third | IOException после публикации всех трёх; полный ROLLED_BACK, без targets/temp/journal/receipt и созданных source directories |
| receipt-rollback | IOException до receipt при всех трёх опубликованных файлах; ROLLED_BACK/RECEIPT_WRITE_FAILED и полный cleanup |
| receipt-modified | До receipt чужой процесс меняет третьи bytes и возникает IOException; первые два собственных target удалены, чужая правка сохранена, RECOVERY_REQUIRED/TARGET_MODIFIED с journal |
| receipt-unknown | До receipt создаётся неопределённый receipt и возникает IOException; RECOVERY_REQUIRED/RECEIPT_STATE_UNKNOWN, все три target, неизвестный receipt и journal сохранены |
| receipt-committed | IOException после durable receipt; RECOVERY_REQUIRED/APPLY_IO, все три target, собственный трёхфайловый receipt и journal сохранены |
| completed-move-noop | Перенос completed проекта с ledger → NOOP; bytes всего проекта/receipt не меняются; чужой файл по старому provenance path сохранён |
| completed-copy-noop | Копирование completed проекта с новыми filesystem identities → NOOP; исходный и скопированный проекты не меняются |
| pending-move-recovery | Остановка после двух публикаций, journal PUBLISHED/entryIndex=1 с тремя entries → перенос → RECOVERY_REQUIRED/JOURNAL_INCOMPLETE; targets/temp/journal и чужой файл по старому пути не меняются |

Во всех трёх receipt recovery cases повторный запуск блокируется pending journal;
до/после сравнивается полный snapshot bytes проекта. Pending relocation моделирует
резкую остановку через test-only Error, обходящий catch/rollback: это **не отдельный
OS-process hard-crash test**. I/O failures инъецируются через существующие hooks
beforePublish/afterPublish/beforeReceipt/afterReceipt; механизм публикации,
hard links, journal, receipt и rollback настоящий.

## Прогон и воспроизведение

Финальный run: `outputs/entity-generation-g1-extension/e4-d-completion/three-file-20261031-final`.
JDK: `C:/Program Files/Axiom/AxiomJDK-21`. Launcher предварительно выполнил offline
`entity-design-core test-compile` (BUILD SUCCESS), затем javac 17 D sources и всю
матрицу. Maven install и обновление общего cache не выполнялись.

```powershell
& scripts/apply-entity-generation-g1.ps1 `
  -OutputDirectory outputs/entity-generation-g1-extension/e4-d-completion `
  -RunId <new-unique-id>
```

Запуск требует среды с разрешёнными hard links и junctions. Первоначальный
sandbox-run не допускал публикацию hard links и пропустил две alias-проверки.
После запуска вне sandbox прежние 75 cases прошли; в новой unknown-receipt
fixture потребовалось создать родительский каталог receipt перед инъекцией.
Также первоначальная synthetic summary для нового section draft была исправлена
на обязательный SectionsScenarioTest. Это исправления harness, не production D.
Промежуточные результаты сохранены в [prior-attempts](prior-attempts/);
они не включены в итоговые 87 passed и не скрывают итоговые failures/skips.

## Сохранённые артефакты

- [Полная D summary](selftest-summary.json), [selftest log](selftest.log),
  [launcher log](launcher.log), [core build](core-build.log), [status](status.txt).
- [12 proofs после assertions](proofs/): outcomes, manifest/C binding и snapshot
  файлов каждого нового case.
- [Проекты после проверок](projects/): reviewed plans, synthetic C, targets,
  receipt/journal/temp в соответствующих rollback/recovery состояниях; также
  исходный completed copy и старые provenance directories с чужими sentinel bytes.
- [Source snapshots](sources/), [source hashes](source-hashes.json) и
  [build/runtime hashes](runtime-hashes.json).
- [Воспроизводимая сверка архива](verify-evidence.ps1) и [её результат](verification.json).

Архив projects — копии bytes для анализа, не инструкция применять recovery к
живым проектам. Внутри journal/receipt сохраняются исходные абсолютные provenance
paths и identities; их не переписывали при архивировании. Повторение тестов всегда
создаёт новые изолированные проекты.

## Статус

Найденные пробелы многофайловой D-приёмки закрыты. E4 принят в пределах
функционального и компонентного scope: A/C/D, aggregate runtime, P8 ZIP и формы.
Согласованный общий artifact baseline и итоговая браузерная матрица остаются
обязательствами E5.H/E5.B. Этот результат не закрывает E5 или M.
