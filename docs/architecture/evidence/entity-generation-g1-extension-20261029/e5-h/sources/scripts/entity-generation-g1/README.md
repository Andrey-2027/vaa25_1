# G1.2 compile/runtime harness (поток C)

Независимый backend-harness для проверки Java-файла, созданного генератором A. UI, Vaadin,
forms, browser и PostgreSQL не используются. Оригинальный `examples/entity-types-consumer`
не изменяется: каждый запуск копирует шаблон в отдельный временный каталог.

## Состав

E3.C добавляет `scenarios/owned-catalog`, `owner-document-v1`, `owner-document-v2`
и независимый `owner-tests/OwnerScenarioTest.java`. Маркер REQUIRED_SUITE в owner
spec включает профиль `g1-owners`; required suite фиксируется в C2 и проверяется D2
по source plan. `owner-support/` — отдельный JAR обычной ссылки без owner capability,
цель — существующий `reference-target` JAR; ни один не является generated entry.
Actual owner/doc inputs экспортируются regression launcher в `owner-acceptance-input/`.

- `consumer/` — сокращённый Spring Boot/H2 consumer с настоящими platform JAR,
  autoconfiguration, `MetadataConsistencyStartupCheck`, canonical API и C5.
- `consumer/src/test/java/.../GenerationScenarioTest.java` — generic проверки,
  ожидания берутся только из `ScenarioSpec`.
- `scenarios/empty-catalog/`, `scenarios/future-catalog/` — независимые ожидания
  catalog-сценариев.
- `scenarios/empty-document/` и `scenarios/scalar-document/` — независимые ожидания
  actual `document-java-v1` (inherited `number/date`, titles, publications, constraints,
  update/reopen, отказ без даты и без grants).
- `scenarios/control-document/ScenarioSpec.java` — ожидания документного control fixture.
- `scenarios/multifile/ScenarioSpec.java` — независимые ожидания root + двух owned rows.
- `multifile/MultiFileScenarioTest.java` — metadata linkage и aggregate persistence всего
  комплекта одним startup; копируется в isolated consumer только при N>1 entries.
- `fixtures/control/ControlCatalog.java`, `fixtures/control/ControlDocument.java` —
  контрольные источники для selftest.
- `fixtures/multifile/` — контрольный document root и две строки для N-file selftest.
- `fixtures/broken/BrokenCatalog.java` — заведомо некомпилируемый источник для selftest.
- `scenarios/sections-{catalog,document}-{0,1,2}/` — независимые ожидания E4.C для
  корня с нулём, одной и двумя секциями.
- `section-tests/SectionsScenarioTest.java` — suite для 1/2 секций; 0-секционный
  корень остаётся на generic `GenerationScenarioTest`.
- `../test-entity-generation-g1.ps1` — раннер.

## E4.C — независимые section specs (подготовка, без запуска)

На 2026-10-30 подготовлены независимые `ScenarioSpec` для обоих видов и 0/1/2
секций. C harness, генератор, D и browser в этом шаге не запускались; actual
plan/manifest появятся после E4.A.

| Scenario | Generated files | Sections | Runner selection |
| --- | ---: | ---: | --- |
| `sections-catalog-0` / `sections-document-0` | 1 | 0 (`SECTIONS=List.of()`) | generic `GenerationScenarioTest`; section suite не копируется |
| `sections-catalog-1` / `sections-document-1` | 2 | 1 | `SectionsScenarioTest`, профиль `g1-sections` |
| `sections-catalog-2` / `sections-document-2` | 3 | 2 | `SectionsScenarioTest`, профиль `g1-sections` |
| `sections-owned-catalog-2` | 3 | 2 + owner | `SectionsScenarioTest`, профиль `g1-sections`; combined case |

Specs задают class/path/table, titles, base fields, row table/title/order, parent
field `catalog`/`document`, `lineNumber` и note sample. `SectionsScenarioTest` берёт
row field/sample из spec, а не из сгенерированных annotations. 0-секционные specs
намеренно не включают section suite: generic suite проверяет root metadata, numbering,
validation и access; отсутствие `@TableSections` у них пока не проверяется — это
остаётся actual E4.C после E4.A.

Actual C acceptance ждёт E4.A: нужен настоящий `GenerationPlan` с root + rows и
matching manifest. Эти spec-файлы — подготовка ожиданий, не результат C/D.

## Режимы

Раннер выдаёт `g1-c-summary-v2`; legacy-вход с `PlanReceipt` без manifest сохраняет
отдельный `g1-c-summary-v1`. Обязательные поля v2: `kind`, `mode`, `status`, `manifest`,
`scenario {id, specSha256}`, `sources`, `sourceSetSha256`, `dependencies`,
`supportSources`, `compile`, `runtime`, `tests {reports, run, failures, errors, skipped,
suites}`. `sources` повторяет manifest entries в том же порядке, `sourceSetSha256` равен
`manifestSha256`. Дополнительные legacy-поля (`source`, `plan`, `harness`, `maven`,
`platformJars`) сохраняются для D1 и диагностики.

- `generated-acceptance` требует `-ManifestFile` (или legacy `-PlanReceipt`) и принимает
  только успешный прогон: Maven exit 0, required suite без failures/errors/skips,
  неизменные platform JAR и непустой dependency set. Для одного entry required suite —
  `GenerationScenarioTest`; для N>1 — `MultiFileScenarioTest`. Любой skipped test — отказ.
- `selftest` допускает заведомо отрицательные прогоны; он никогда не разрешает D2 apply.

Manifest проверяется до compile: kind `g1-generation-manifest-v2`, `plan-fingerprint-v2`,
`CREATE` entries, path/hash/bytes каждого точного UTF-8 source и пересчитанный
`g1-file-set-sha256-v2`. Для нескольких entries передайте `-SourceDirectory`; C проверяет
весь каталог и отклоняет missing/extra/duplicate sources до Maven. Несовпадение — отказ,
а не исправление файла. Каждый entry manifest материализуется интегратором из настоящего
`plan.files()`; production API A не меняется.

`dependencies` содержит все effective `org.ipro` JAR consumer-а в виде
`groupId:artifactId:type:classifier:version` + SHA-256, отсортированные по id. JAR
хэшируются до и после build; изменение даёт `failure`. `supportSources` фиксирует
harness-файлы (кроме generated source и ScenarioSpec), `scenario.specSha256` — точные
байты spec.

## Материализация actual source

Экспорт выполняет integration-support тест builder-а:

```powershell
# launcher имеет #requires -Version 7.0
pwsh -NoProfile -ExecutionPolicy Bypass `
  -File scripts/test-entity-generation-g1-document.ps1 -RunId e1-c-actual
```

Для документа он пишет в `outputs/entity-generation-g1-extension/e1-ab/<run>/input/<scenario>/`:
`current-draft.json`, `current-context.json`, `reviewed-plan.json`, `manifest.json` и
Java по `relativePath`. Каталог затем передаётся C и D без переформатирования.

Старый catalog export через `CatalogJavaGeneratorTest` сохраняется для Matrix legacy.

## Generated-acceptance

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/test-entity-generation-g1.ps1 `
  -SourceFile outputs\entity-generation-g1-extension\e1-ab\e1-c-actual-20261029\input\empty-document\com\example\generated\EmptyDocument.java `
  -RelativePath com/example/generated/EmptyDocument.java `
  -ExpectedSha256 a28039602c6cae7ea4ea75cd6b1d6f9be9e6fbc2e42909e2ab028574d409ad34 `
  -SpecFile scripts\entity-generation-g1\scenarios\empty-document\ScenarioSpec.java `
  -Scenario empty-document `
  -ManifestFile outputs\entity-generation-g1-extension\e1-ab\e1-c-actual-20261029\input\empty-document\manifest.json `
  -RunId actual-empty-document
```

`ExpectedSha256`, manifest entry и source bytes должны описывать один и тот же файл.
`-PlanReceipt` остаётся совместимым входом для старых catalog-прогонов; при наличии
manifest он не обязателен.

Для комплекта передаётся каталог и полный manifest; отдельные `-SourceFile`,
`-RelativePath`, `-ExpectedSha256` не нужны (root берётся из первой entry):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/test-entity-generation-g1.ps1 `
  -SourceDirectory outputs\entity-generation-g1-extension\e4-ab\input\multi-file `
  -ManifestFile outputs\entity-generation-g1-extension\e4-ab\input\multi-file\generation-manifest.json `
  -SpecFile scripts\entity-generation-g1\scenarios\multifile\ScenarioSpec.java `
  -Scenario multifile -RunId actual-multifile
```

По умолчанию Maven запускается offline (`-o`) с общим `.m2`. Для отдельного repository C
передайте `-LocalRepository <path>`; для первичного заполнения repository допустим `-Online`.
`-KeepIsolation` оставляет временную копию для разбора.

Результат: `input/`, `manifest.json`, `summary.json`, `status.txt`, `maven.log`,
`reports/`, `consumer-runtime-dependency-tree.json`, `consumer-test-classpath.txt`,
`platform-jars.sha256`.

## Selftest

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/test-entity-generation-g1.ps1 -SelfTest `
  -OutputDirectory outputs/entity-generation-g1-extension/e1-c
```

Selftest (`mode=selftest`) проверяет:

1. catalog control source компилируется и проходит runtime-проверки;
2. document control source проверяет inherited `number/date`, нумерацию, отказ при
   отсутствии даты, display name и canonical create/read/update;
3. broken source даёт compile failure;
4. неверный ожидаемый SHA-256 отклоняется до Maven;
5. отсутствие тестов не считается успехом;
6. полностью skipped required suite даёт `tests-skipped`, а не `success`;
7. N-file комплект materializes 3 entries, проходит metadata linkage и aggregate
   persistence всех секций (`MultiFileScenarioTest`, 2/2);
8. missing/extra/duplicate source и изменённая row отклоняются до Maven;
9. ошибка компиляции одной row даёт Maven failure.

Control source и selftest summary не являются generated-acceptance. Actual-приёмка
документа выполняется только на `plan.files()` из A с матчащимся manifest.

## Статус

`success` выставляется только при успешном Maven, хотя бы одном выполненном test,
нулевых failures/errors/skips, неизменных platform JAR, непустых dependencies и успешной
required suite с `run>0` и `skipped=0`: `GenerationScenarioTest` для одного entry,
`MultiFileScenarioTest` для synthetic N>1 и `SectionsScenarioTest` для actual E4.A sections
(включая одну секцию). Compile failure, startup failure, `Tests run: 0` или
любой skip всегда дают `failed`.

## E4.A: actual root + rows

`scripts/test-entity-generation-g1-sections.ps1 -RunId <unique-id>` запускает все
нейтральные core/JSON/runtime тесты в отдельных build directories и экспортирует
семь actual JSON→validator→builder комплектов в
`outputs/entity-generation-g1-extension/e4-a/<unique-id>/input`.
Общий SNAPSHOT cache и UI build directories этот launcher не обновляет.

Независимые specs этого экспорта находятся в `scenarios/e4a-*`. Specs `sections-*`
могут описывать другие inputs; совпадения одного className недостаточно для их замены.
Для C передайте SourceDirectory, manifest.json и соответствующий e4a spec. Для
секций spec выбирает `g1-sections` и обязательный `section-tests/SectionsScenarioTest.java`:
это H2 metadata/aggregate тесты actual файлов, без бизнес service/repository.
Zero-section cases используют прежнюю обязательную GenerationScenarioTest.

D проверяет SectionsScenarioTest по source draft, а не только по числу Java entries.
При переносе specs между проектами запускайте applied runtime с `clean test`, чтобы
timestamp-based incremental compiler не оставил предыдущие константы ScenarioSpec.
Для owner + sections нужен актуальный platform-spring-boot-autoconfigure с передачей
EntityManager в aggregate owner guard. Изолированная сборка и hashes используемых
артефактов зафиксированы в evidence E4.A; старый локальный JAR не подтверждает исходную проводку.

## E4.B: P8 delivery и стандартные формы

`scripts/test-entity-generation-g1-section-ui.ps1 -RunId <unique-id>` проверяет P8
на actual E4.A JSON/context обоих видов с 0/1/2 секциями и combined owner/reference.
Результат включает настоящие single Java/ZIP downloads в `delivery/<case>`.
Состав и bytes сверяются с принятым в E4.A manifest; synthetic seam не используется.

`scripts/test-entity-generation-g1-section-forms.ps1 -DeliveryDirectory <delivery>
-RunId <unique-id>` материализует эти downloads в семь чистых consumers,
проверяя SHA-256/bytes до компиляции. Текущий platform-vaadin берётся из isolated
reactor. `section-form-tests/GeneratedSectionFormsTest.java` проверяет штатные
autoconfiguration, factory, ItemForm/ItemTable, lookup и aggregate H2 persistence.
Нужны актуальные backend artifacts; `-LocalRepository` и `-Settings` позволяют
использовать отдельный repository и file:// mirror без изменения общего cache.
UI settings/view stores и VaadinSession — тестовые seams; entity/row services и
persistence не подменяются. Браузерная общая приёмка относится к E5.B.
