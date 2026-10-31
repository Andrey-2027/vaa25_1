# G1.1: закреплённый результат preview/download

- [Исходный draft](draft.json) — fixture catalog-v2 со стабильными ID и семью scalar types.
- [Полный GenerationPlan](plan.json) — recipe/version, generator version, source/context binding и CREATE-файл.
- [Выданный Java-файл](org/example/generated/G1PreviewCatalog.java) — bytes настоящего DownloadHandler.
- [Сведения о выдаче](delivery.json) — имя/путь, SHA-256, число bytes и `compiled=false`.
- [Результаты тестов](ui-test-results.json) — один успешный прогон: 56 tests, 0 failures/errors/skips.
- [Локальные JAR и SHA-256](ui-dependencies.json).

Логи успешного прогона и первой попытки с неполным test UI сохранены рядом.
Повторить генерацию этих данных можно `scripts/test-entity-generation-g1-editor.ps1`;
тест `evidenceComesFromImportedFixtureThroughRealPreviewAndDownload` пишет свежий комплект в
`outputs/entity-generation-g1/g1-1/download/`. Закреплённая копия обновляется после успешной приёмки.

[Описание реализации и границ приёмки](../entity-generation-g1-download-20261028.md).
Компиляция сгенерированного класса, startup и persistence относятся к отдельному G1.2.
