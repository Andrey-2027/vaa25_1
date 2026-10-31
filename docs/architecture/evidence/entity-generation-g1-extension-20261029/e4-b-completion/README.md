# E4.B — завершение P8 и стандартных форм табчастей

Дата: 2026-10-30. Продолжение [частичного delivery slice](../e4-b/README.md),
после [backend gate E4.A](../e4-a/README.md). Реализация и компонентная приёмка E4.B
завершены. Браузерная общая приёмка остаётся E5.B; migration gate M не запускался.

## Что завершено

- Реальные JSON/context из семи принятых E4.A сценариев проходят обычные P8 import,
  «Показать Java», выбор каждого файла и DownloadHandler. Synthetic preview seam
  в этих проверках не используется.
- Проверены catalog/document с 0/1/2 секциями и owner-enabled catalog с двумя
  секциями и row reference: 1/2/3 Java entries. Каждый entry и manifest совпадает
  с принятым в E4.A планом; ZIP содержит ровно manifest и generated Java по
  relative paths. Сохранены фактически скачанные ZIP/single Java.
- Preview показывает все пути и точный текст выбранного файла. Single Java и ZIP
  сохраняют правила UTF-8/LF, freshness/access и JSON checkpoint/dirty.
- Поздний download отказывает после row edit, перестановки секций, изменения
  owner, потери target context без смены version label, смены principal и прав.
- Section inspector и diagnostics различают связь строки с корнем, root owner и
  обычные references. Document/catalog hints учитывают вид корня. Ошибка
  технического row-поля открывает конкретную секцию и поле по стабильным UUID.
- Старые component expectations «sections пока запрещены» заменены проверкой
  настоящей ошибки технического parent-имени; ожидаемые версии обновлены до v4.

## Проверки

| Прогон | Проверки | Результат |
|---|---:|---|
| Core/JSON/runtime/P8/platform forms component regression | 39 suites, 244 tests | 0 failures/errors/skips |
| Actual downloaded Java → standard forms + H2 | 7 cases × 4 tests = 28 | 0 failures/errors/skips |

Actual form matrix:
`CatalogSections0/1/2`, `DocumentSections0/1/2`, `OwnedCatalogSections2`.
Для zero-section cases проверены root create/reopen/save/access/read-only и отсутствие
секций; для остальных — все объявленные секции. Число 28 учитывает семь независимых
runtime consumers, без повторного суммирования probe-прогонов.

Form consumers компилируют **скачанные P8 bytes** после проверки manifest/SHA-256/bytes.
Используются текущий `platform-vaadin` из изолированного Maven reactor, штатная
FormAutoConfiguration, TableSectionFactory, ItemForm, ItemTable и
MetadataDrivenItemFormSaveAdapter. Metadata, section service, lookup и aggregate
persistence настоящие; application row repository/service не объявляются.
Mockito заменяет только VaadinSession и UI settings/view stores, не бизнес-данные
или persistence. ReferenceTarget берётся из отдельного support JAR.

Реальные кнопки и row dialogs покрывают add/edit/remove, переключение TabSheet,
required validation без закрытия диалога, lookup и смену reference, сохранение
root + всех секций, line numbers, clear и reopen вне прежней формы. Технические
parent/lineNumber отсутствуют в row form. Owner остаётся locked после сохранения,
а row reference редактируется. Read-only отключает section actions.

После отказа root write форма сохраняет dirty и строки; после возврата grant
запись проходит. После SQL FK failure строки остаются в UI, persisted aggregate
не содержит неудачную вставку; ссылка исправляется в том же row dialog и повторный
save проходит. Lookup без target read grant запрещён canonical boundary.

## Связь с C/D и окружение

P8 output имеет те же planId/manifestSha256 и точные Java bytes, что actual C/D
evidence E4.A. Эти C/D-прогоны не пересчитываются как новые проверки E4.B. Отдельный
UI запуск D не добавлялся. Form consumers — дополнительные runtime checks UI path.

Для форм использован отдельный repository E4.A с актуальным autoconfigure:
его передача EntityManager в aggregate owner guard уже описана в evidence E4.A.
Зависимости загружаются только из file:// mirror общего cache; общий cache и
рабочие SNAPSHOT не переустанавливаются. Platform-vaadin собирается в отдельный
build directory внутри текущего run. Его source/class hashes и effective consumer
dependency tree/classpath сохранены отдельно от C/D inventory.

## Повторение и артефакты

1. `scripts/test-entity-generation-g1-section-ui.ps1 -RunId <unique-id>` — component
   reactor и actual download в `outputs/entity-generation-g1-extension/e4-b/<id>/delivery`.
2. `scripts/test-entity-generation-g1-section-forms.ps1 -DeliveryDirectory <delivery>
   -RunId <unique-id>` — семь чистых consumers. Для отдельного Maven repository
   передаются `-LocalRepository` и при необходимости `-Settings`.
   Нужны актуальные platform backend artifacts, включая autoconfigure owner wiring.

Финальные runs: `component-r2`, `forms-final` в `outputs/entity-generation-g1-extension/e4-b/`.

- [Component results](component-test-results.json), [XML](component-reports/), [log](component-tests.log).
- [Form results](form-test-results.json), [case reports и generated sources](forms/), [log](form-tests.log).
- [Скачанные файлы](delivery/), [delivery bindings](delivery-bindings.json).
- [Проверки связи output/manifest/runtime](verification.json), [source hashes](source-hashes.json).
- [Независимый form test](GeneratedSectionFormsTest.java).

Предыдущие partial evidence и E4.A receipts сохранены без переписывания. Общая
интеграция, браузерные сценарии и согласованный artifact baseline остаются E5;
миграции и deployment этим результатом не подтверждаются.
