# Review E1.C/D: незакрытые требования

**Актуальный статус 2026-10-29:** все перечисленные ниже замечания закрыты
независимой [приёмкой E1](../e1-acceptance/README.md). Ниже сохранён исходный review
и история исправлений; старые отрицательные результаты не относятся к текущему run.

Дата: 2026-10-29. Проверены текущие source snapshots, обновлённые E0 contracts,
план G1 и evidence e1-cd. Production sources не изменялись.
Вывод: **gate E1.C/D пока не закрывать**.

Existing evidence подтверждает actual C EmptyDocument/ScalarDocument (5 tests каждый),
APPLIED→NOOP и foreign conflict, а также 57 D selftests. Дополнительные probes
ниже выявили случаи, отсутствующие в текущей приёмке. Из 57 D selftests только
9 именуются document-v2-*; успешный общий счётчик не означает полную D2 failure matrix.

## 1. P1: rollback удаляет foreign replacement с теми же bytes/timestamps

`GenerationApplyV2.identity():541–544` подменяет отсутствующий filesystem fileKey
строкой attrs:creation:modified:size. `deleteOwnedTarget():369` принимает её как
доказательство identity. Timestamp/size доступны для восстановления и не различают
файловые объекты. Это противоречит E0: неизвестная identity не разрешает DELETE.

Probe `foreign-identical-rollback`: перед receipt исходный target удалён и создан
другой с теми же bytes и восстановленными timestamps; затем injected receipt failure.
Получено ROLLED_BACK/RECEIPT_WRITE_FAILED, **replacement удалён**.
Нужны настоящая platform-specific identity/handle либо отказ от удаления с
RECOVERY_REQUIRED при невозможности доказать identity. Совпадения hash недостаточно.

## 2. P1: после durable receipt выполняется rollback committed output

После `writeReceipt():286` ошибки повторной проверки summary/writeJournal попадают
в общий rollback (`298–322`). Он удаляет receipt и target (`325–344`), не различая
до- и после-commit состояние. E0 требует сохранить committed output и сообщить
RECOVERY_REQUIRED при незавершённом journal.

Probe `journal-failure-after-receipt`: beforeReceipt делает journal readonly.
Receipt успешно записывается, последующий atomic journal replace падает.
Получено RECOVERY_REQUIRED/JOURNAL_DELETE_FAILED, однако **target и receipt удалены**,
а journal остался. Ожидание: RECOVERY_REQUIRED с сохранением target/receipt.
Нужен явный durable commit boundary и запрет destructive rollback после него.

## 3. P1: APPLIED подтверждает изменённый target

Target проверяется сразу после hard-link publication (`242–252`), но не после
`hooks.afterPublish():258` и перед receipt/APPLIED. После проверки публикуемый файл
может быть изменён сторонним процессом; receipt по-прежнему сохраняет expected hash.

Probe `changed-after-publish`: target изменён в afterPublish, без exception.
Получено **APPLIED**, receipt есть, journal удалён, actual target hash **не совпадает**
с планом. Нужно повторно проверить target bytes/identity/path перед durable receipt
и успешно завершённым apply. При чужой правке сохранить файл и вернуть RECOVERY_REQUIRED.
Это не требование атомарности ФС: probe меняет файл задолго до финального результата.

## 4. P1: C success при полностью skipped required suite

В `scripts/test-entity-generation-g1.ps1:665` учитываются failures/errors, но не skipped.
Required-suite predicate (`690–691`) также не требует skipped=0. Surefire tests/run
включает skipped cases, поэтому run>0 не доказывает выполнение тестов или startup.

В отдельной копии harness единственная модификация — `@Disabled` на
GenerationScenarioTest. Вызван generated-acceptance с настоящим source/manifest
EmptyDocument. Получено **status=success, tests.run=5, tests.skipped=5,
runtime.startup=success**, хотя runtime tests не выполнялись.
Нужен отказ при любом обязательном skip и отдельный negative selftest.
D2 сейчас отклонит такой summary по skipped, но сам C gate уже объявляет ложный успех.

## 5. P1: legacy C1 generated-acceptance больше не проходит

Старый вход с `PlanReceipt` ещё разбирается, но не заполняет summary.manifest.
Затем unconditional generated-acceptance проверка `test-entity-generation-g1.ps1:683–684`
объявляет manifest-required. Передать F1 manifest вместо этого нельзя:
Read-PlanManifest принимает только F2 (`265–266`). Summary всегда создаётся как C2.

Дополнительно legacy `scripts/entity-generation-g1-matrix/ScenarioSpec.java`
не содержит новых BASE/BASE_FIELDS/DATE_* declarations, используемых общим
GenerationScenarioTest. Прежняя Matrix fixture больше не совместима с этим harness.
Это статически подтверждённые несовместимости; actual Matrix C/D повторно не запускался.
Нужно сохранить явный C1 legacy branch/harness и заново пройти настоящую Матрицу
через F1/C1/D1, без миграции старых inputs на extension profile.

## 6. P2: D2 принимает произвольную required suite

`CSummaryV2.verify():181–205` требует лишь наличие хотя бы одной suite с
required=true. Её имя не сравнивается с independently required scenario suites.
Probe `wrong-required-suite` заменяет имя единственной suite на unrelated.FakeSuite,
сохраняя valid counts/binding. D2 возвращает **APPLIED**.
В E1 нужно требовать реальную GenerationScenarioTest либо независимый contract
ожидаемых suites, не доверяя одному required boolean из самого summary.

## 7. P2: late target имеет неверный итоговый статус

Контракты E0.4 требуют ROLLED_BACK для late target, когда receipt отсутствует,
а собственные output/journal безопасно убраны. Сейчас failure.status=CONFLICT
передаётся из create через rollback без изменения.
Probe `late-target`: CONFLICT/CONFLICT_LATE_TARGET, чужие bytes сохранены,
journal и receipt отсутствуют. Безопасность bytes соблюдена, но status contract нарушен.
Текущий document-v2-late-target selftest закрепляет именно этот неверный статус.

## Что ещё требуется в evidence

- Добавить воспроизведённые cases в permanent C/D regression suites, затем повторить
  actual EmptyDocument/ScalarDocument C2→D2→NOOP/conflict и legacy Matrix C1→D1.
- Разделить доказанный receipt failure до commit и сбой после durable receipt;
  добавить foreign identical replacement и changed-after-publication.
- Зафиксировать current consumer dependency resolution. В actual командах используется
  dependency tree из C consumer, тогда как D applied project содержит только Java/state,
  без build configuration. Hash reread проверяет bytes предоставленных JAR, но не
  доказывает, что именно их выберет сборка applied consumer.
- Сохранить повторный build/runtime из D-applied project (план §12, пункт 4).
  До D C runtime уже подтверждён; build после D в опубликованном e1-cd evidence отсутствует.
- Исправить воспроизводимую команду A в e1-cd README: launcher document имеет
  `#requires -Version 7.0`, поэтому `powershell.exe` (5.1) для него не подходит;
  нужен PowerShell 7/pwsh. Это отдельное исправление инструкции, не runtime blocker.

Browser/E5, references/E2, owner/E3, sections/ZIP/E4 и migration M не являются
недостающими implementation tasks данного E1.C/D review.

## Пробы и воспроизведение

Рабочий root: `outputs/entity-generation-g1-extension/e1-cd-review/20261029/`.
В нём immutable копия core classes и D sources, отдельные compiled classes,
Java probe, c-snapshot и отдельные project fixtures. Общий Maven cache не менялся.

Первые D attempts внутри sandbox не выполняли hard-link publication; их результаты
не использовались как доказательство проверяемых post-publication paths.
Затем те же isolated fixtures/probes выполнены с разрешёнными hard links.
Никакие project files вне собственного review outputs не удалялись и не изменялись.

- [ReviewProbe.java](ReviewProbe.java) использует существующие package-private hooks
  и reflection только для доступа к private fixture helpers ApplyHarness.
- [Итоговый D log](probes-full.log), отдельные [probe results](probes/).
- [C summary с пятью skipped tests](c-all-skipped-summary.json).
- [SHA-256 проверенных D sources](source-hashes.json); snapshot сверён с исходниками
  после выполнения probes, расхождений не было.

Для повторения D compile: javac Java 21, copied core-classes + Jackson 2.18.4,
все copied D sources и ReviewProbe; main — org.ipro.design.apply.ReviewProbe,
единственный аргумент — новый пустой output root. Нужен filesystem с hard links.
Каждая проба создаёт отдельный synthetic dependency и project, а не подменяет .m2 JAR.

Production sources, plan checkboxes и уже сохранённые успешные evidence не изменялись.

## Статус после исправлений (2026-10-29)

Все семь пунктов получили код и постоянные regression-пробы. Gate остаётся открытым до
повторного review, но перечисленные блокеры больше не воспроизводятся.

1. **Foreign replacement.** `fileKey`/xattr identity заменила attrs-эвристику;
   неподтверждённая identity запрещает DELETE. Проба
   `document-v2-replaced-target-preserved` сохраняет подменённый файл и даёт
   `RECOVERY_REQUIRED/TARGET_IDENTITY_CHANGED`.
2. **Durable receipt.** После успешной записи receipt выставлена commit-граница;
   любой дальнейший сбой возвращает `RECOVERY_REQUIRED`, target и receipt сохраняются.
   Пробы `document-v2-post-receipt-target-modified` и
   `document-v2-post-receipt-journal-failure` проходят.
3. **Финальная проверка target.** Hash/identity проверяются после публикации, перед
   receipt и перед `APPLIED`; изменение после публикации больше не даёт `APPLIED`.
4. **Skipped C.** C отвергает любой skip; required suite должен быть
   `com.example.generated.harness.GenerationScenarioTest` с `skipped=0`, а
   `runtime.startup` не объявляется успешным при пропущенных тестах. В C selftest
   добавлен `allSkipped`; actual C2 после исправления — 5/5 без skip.
5. **Legacy C1.** C сохраняет отдельную ветку `g1-c-summary-v1` для `PlanReceipt` без
   manifest; D1 не изменён. `ScenarioSpec` матрицы дополнен `BASE/BASE_FIELDS/DATE_*`.
   Матрица повторно прошла полный P8→C1→D1: `APPLIED → NOOP → CONFLICT_MODIFIED`,
   applied-project runtime 5+3 теста, `run-20261029-200426`.
6. **Required suite.** D2 принимает только реальную
   `GenerationScenarioTest`; проба `document-v2-arbitrary-required-suite` получает
   `REJECTED/C_TESTS_INCOMPLETE`.
7. **Late target.** После безопасной уборки собственных temp/journal возвращается
   `ROLLED_BACK/CONFLICT_LATE_TARGET`; foreign bytes сохраняются.

Повторная приёмка:

- D selftest: 61/61, `outputs/entity-generation-g1-extension/e1-d/apply-selftest-20261029-reviewfix/`.
- Actual C2: EmptyDocument/ScalarDocument `success`, 5/5, 17 JAR —
  `outputs/entity-generation-g1-extension/e1-c/actual-*-document-reviewfix/`.
- Actual D2: `APPLIED → NOOP` для обоих сценариев —
  `outputs/entity-generation-g1-extension/e1-d/actual-*-document-reviewfix*/`.
- Build/runtime из D-applied проектов: `BUILD SUCCESS`, по 5/5 тестов —
  `outputs/entity-generation-g1-extension/e1-d/build-*-reviewfix/`.
- Dependency provenance: build tree против C2 dependencies — 17/17, без расхождений,
  `outputs/entity-generation-g1-extension/e1-d/dependency-verification.json`.

Подробности и обновлённые таблицы — в [e1-cd evidence](../e1-cd/README.md).
