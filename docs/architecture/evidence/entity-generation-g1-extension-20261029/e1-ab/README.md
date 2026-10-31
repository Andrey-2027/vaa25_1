# E1.A/B — Документ: ядро генерации и P8

Дата: 2026-10-29. Результат: **E1.A и E1.B выполнены**.
Actual C/D закрыты в [E1.C/D](../e1-cd/README.md); браузерная приёмка относится к E5.

## Реализация

- `standard-document-v1@1` переведён в DRAFT_READY; `DraftOperations.create(recipe)`
  создаёт документ, legacy `create()` по-прежнему создаёт catalog-v1.
- Validator резервирует number/date + system names для документа и code/name + system
  names для справочника. Document code/name допустимы как пользовательские поля.
  Нужны complete runtime snapshot и AVAILABLE для STANDARD_DOCUMENT_APP_API/ET0_ET7_RUNTIME.
- Общая scalar реализация вызывается через EntityJavaGenerator: catalog-java-v1 /
  plan-fingerprint-v1 сохранены, документ использует document-java-v1 / plan-fingerprint-v2.
  V2 связывает полный draft/context, ordered operation/path/hash и UTF-8 byte count.
- Java наследует StandardDocumentEntity, публикует number/date, не дублирует inherited
  поля и не добавляет numbering policy, UNIQUE(number), posting или explicit kind.
  Семь scalar types/constraints/required modes/labels/escaping используют общий builder.
- Строгий JSON codec регистрирует точный document ref в envelope schemaVersion=1.
  Старый schema resource не меняется. UUID, raw invalid input и DraftText сохраняются;
  неизвестные поля и owner по-прежнему отклоняются.
- P8: действие **Новый → Документ / Справочник**, dirty-confirmation перед заменой;
  recipe заполненного draft не переключается. Document header/base hints, Номер/Дата,
  LocalDate, обязательность, numbering и section owner hints выбираются по recipe.
  После удаления последнего поля/секции selection возвращается на builtin:date.
- Session preview и Java download используют общий dispatcher/version contract,
  повторную access/freshness/context проверку и прежний JSON checkpoint protocol.
  Java preview/download не снимают dirty. Старые chooser callbacks после close или
  смены пользователя не создают новый документ.

Runtime projection уже передавал подтверждённый STANDARD_DOCUMENT_APP_API, поэтому
новый scanner или подмена фактов не понадобились. Проверки UNKNOWN/UNAVAILABLE
сохранены на core и UI boundaries. Runtime owner и canonical persistence не менялись.

## Проверки

Воспроизводимая команда (PowerShell 7, JDK 21, offline Maven):

```powershell
./scripts/test-entity-generation-g1-document.ps1
```

Launcher создаёт уникальный run, временный reactor core/json/runtime/application,
отдельный app build directory, запускает Mockito agent при старте JVM и удаляет
временный application pom. Он не выполняет install/clean общего Maven cache и не
переписывает прежнюю «Матрицу». Одновременные сборки core target не предполагаются.

Принятый run: `outputs/entity-generation-g1-extension/e1-ab/20261029-accepted/`.

| Проверка | Suites | Tests | Failures/errors/skips |
| --- | ---: | ---: | --- |
| Core: старые draft/validator/generator/manifest/compatibility + DocumentGenerationTest | 8 | 82 | 0/0/0 |
| JSON: старые fixtures и document envelope | 1 | 10 | 0/0/0 |
| Runtime adapter contract | 1 | 2 | 0/0/0 |
| P8/session/workspace + EntityDraftDocumentTest | 11 | 61 | 0/0/0 |
| Всего | 21 | 155 | 0/0/0 |

Дополнительно `scripts/test-entity-design-contract.ps1 -SkipBuild` прошёл 33 source
contract checks, включая unchanged legacy schema, API roles и source fingerprint.
Это отдельные проверки, они не включены в число tests. Повторы запусков не суммируются.

[Результаты suites](test-results.json), [полный build log](tests.log),
[Surefire XML](surefire/), [contract checks](contract-results-source-only.json).

DocumentGenerationTest проверяет empty/scalar output, recipe resource, семь типов,
required modes, escaping, детерминизм, reserved names/columns, stale draft/context,
UNKNOWN/UNAVAILABLE и адресный отказ на sections/required mismatch. Общий catalog
generator suite по-прежнему сверяет golden Java и отрицательные границы.

EntityDraftDocumentTest проверяет создание, type-specific builtins и delete fallback,
dirty replacement/cancel, поздний chooser callback, импорт, точные preview/download
bytes, неизменность JSON checkpoint, context/access revocation и адресную диагностику.
Прежний набор P8 проверяет catalog-v1/v2, JSON, устаревшие downloads и workspace.

## Передача в E1.C/D

Сохранены actual outputs настоящего builder:

- [EmptyDocument.java](input/empty-document/com/example/generated/EmptyDocument.java),
  [draft](input/empty-document/current-draft.json), [context](input/empty-document/current-context.json),
  [reviewed plan](input/empty-document/reviewed-plan.json), [manifest](input/empty-document/manifest.json).
- [ScalarDocument.java](input/scalar-document/com/example/generated/ScalarDocument.java),
  [draft](input/scalar-document/current-draft.json), [context](input/scalar-document/current-context.json),
  [reviewed plan](input/scalar-document/reviewed-plan.json), [manifest](input/scalar-document/manifest.json).

[Независимая сверка saved source bytes/hash/size с plan и manifest](source-verification.json)
выполнена для обоих комплектов. Это builder outputs с тестовым context, ещё не
C success и не D apply. Для actual C/D потребуются согласованные независимые spec,
runtime dependencies и собственный matching receipt/summary.

Reactor использовал текущие core/json/runtime classes, без установки SNAPSHOT JAR.
Хэши остальных JAR из фактического app test classpath сохранены в
[dependency-hashes.json](dependency-hashes.json). Source fingerprints:

| Module | Files | gitvaa-source-tree-sha256-v2 |
| --- | ---: | --- |
| entity-design-core | 51 | a87b353e3d4bca47b9d0abe02d2779bf293d8a6223ce30286fa530b1270affff |
| entity-design-json | 4 | af5d2ee9875390b73029ac6ffa1408a89fa48439ca7bb877c8432c2b0f4d9bff |

Исходные fingerprint entries обновлены в scripts/local-dependencies.json.
Generated-классы не добавлены в business sources основного приложения.
Не выполнялись compile/runtime generated consumer, canonical document persistence,
D/APPLIED/NOOP, browser, изменения БД или migration gate. Ссылки, owner и sections
остаются следующими функциональными срезами.
