# E4.A — генерация табчастей и backend gate

Дата: 2026-10-30. Генератор: `catalog-java-v4` / `document-java-v4`, F2/M2/C2/D2.

Реализован ordered комплект root → rows для обоих видов. Root объявляет
TableSections; каждая строка — отдельный BaseEntity с явными Table и
TableSectionMetadata, required LAZY parent, техническим Integer lineNumber и
subject scalar/reference fields. Parent, root owner и обычная row reference
проверяются как три разные роли. UI публикации и ограничения берутся из checked
projection. Проверяются технические имена/колонки и case-insensitive Java paths.
Публичный generate API и captured legacy catalog profile сохранены.

| Actual JSON input | Java entries | C tests | Applied runtime tests |
|---|---:|---:|---:|
| CatalogSections0 | 1 | 5 | 5 |
| CatalogSections1 | 2 | 9 | 9 |
| CatalogSections2 | 3 | 9 | 9 |
| DocumentSections0 | 1 | 5 | 5 |
| DocumentSections1 | 2 | 9 | 9 |
| DocumentSections2 | 3 | 9 | 9 |
| OwnedCatalogSections2 + row reference | 3 | 9 | 9 |

Нейтральный reactor: **127 tests**, без failures/errors/skips. Отдельно C:
**55 tests**, D foundation: **75/75 selftests**, runtime применённых файлов:
**55 tests**, без пропусков. Selftests явно отделены от actual generated-acceptance.

В каждом case сохранены исходный JSON/context, reviewed plan, manifest, точные
Java bytes, независимая ScenarioSpec, C summary/XML/log, current dependency tree
и locations, D receipt/APPLIED/NOOP, applied runtime XML/log. Комплект перепроверен
по всем entries, SHA-256/bytes, C/plan/receipt binding и отсутствию pending journal.
Точные checks — [verification.json](verification.json).

Aggregate тесты покрывают create/reopen с references вне транзакции, line numbers,
replace/update/insert/reorder/remove/clear, сохранение неподключённой секции,
удаление root со строками, invalid row и SQL FK failure в последней секции с rollback
шапки и всех секций, чужую row identity, optimistic conflict и запрет standalone
row CRUD. Чтение строк выполняется после canonical authorization корня:
GenericOwnedSectionService по своему контракту является внутренним persistence
слоем. Отдельный row grant не обходит отказ canonical root read/write.

Actual negative probes: missing/changed последняя row отклоняется C до Maven;
чужая последняя row останавливает D до публикации первого файла и сохраняется;
неподходящая required suite и drift target JAR отклоняются D. Более широкая
матрица D guards/rollback/receipt/crash/relocation/lock — отдельные 75 foundation
selftests; её не выдаём за 75 actual бизнес-сценариев.

При combined owner-прогоне обнаружен устаревший локальный autoconfigure JAR:
он не передавал EntityManager в aggregate owner guard. Текущий исходник уже содержит
правильную проводку. Для этого case текущий модуль собран в отдельный
`outputs/entity-generation-g1-extension/e4-a/work/current-repository`, зависимости
получены только через file:// mirror общего cache. Общий cache не обновлялся.
Build log, source hashes, settings и точные JAR hashes сохранены. Остальные шесть
cases используют исходные JAR из общего cache; каждый C→D маршрут сверяет свой
полный inventory (17 JAR без references, 18 с references).

Support tests сохранены внутри каждого case и сверены с его C supportSources.
При параллельной работе общий harness получил обобщение sample/fieldName;
evidence сохраняет фактически проверенную версию каждого прогона. Independent
e4a specs соответствуют именно этим JSON inputs.

Повторение: `scripts/test-entity-generation-g1-sections.ps1`; C —
`scripts/test-entity-generation-g1.ps1` с каталогом input, manifest и
`scripts/entity-generation-g1/scenarios/e4a-<case>/ScenarioSpec.java`.
Маршрут подготовки чистых consumers, D и applied runtime —
[actual-acceptance.ps1](actual-acceptance.ps1), negative probes —
[negative-acceptance.ps1](negative-acceptance.ps1). В скриптах указаны пути конкретного
прогона; для повторения выбираются новые каталоги проектов и RunId.

**E4.A backend gate выполнен.** Preview/download/ZIP и формы E4.B, браузерная
приёмка E5 и migration gate M этим результатом не закрываются.
