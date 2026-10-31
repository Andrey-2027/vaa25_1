# R3.1: входы UDR сняты (2026-10-23)

- Дата: 2026-10-23, Europe/Moscow.
- Основание: решение владельца 2026-10-23 — ни один UDR-отчёт не нужен, все шесть
  шаблонов тестовые ([R2 evidence](r2-udr-inventory-20261022.md)); §R3 плана
  разделения query/report ([план](../../plans/query-report-separation-plan.md)).
- Срез: первый шаг R3 — убрать пользовательские входы в UDR. Движок, DOM и
  persistence пока остаются: их удаление — R3.2, физические таблицы — DB1 UDR.

## 1. Что снято

**Каталог.** `ReportCatalogView` больше не показывает UDR-строки и не содержит
встроенного редактора: экран стал одной колонкой «UniDR + JR». Удалены кнопки
создания/копирования/экспорта/импорта UDR-шаблонов, запуск и открытие UDR.
Адаптер каталога `UdrCatalogContributor` удалён вместе с bean-регистрацией, поэтому
UDR-строки не попадают в список даже при живых данных в `report_template`.
«Новый» предлагает только UniDR и JR; импорт принимает JSON UniDR.

**Печать.** `ReportPrintDialog` собирает формы только из UniDR (по привязке
`targetEntityClass`) и JR. UDR-шаблоны не участвуют ни в списке, ни в карточке;
пункт «UDR — конструктор» из меню «Создать» удалён. `ContextualReportLauncher`,
`ItemFormReportActions` и `ReportPrintAction` больше не принимают UDR-сервисы
(`ReportTemplateService`, `ReportExecutionService`, `EntityLookup`,
`SelectionFormAssembler`).

**Редактор и маршруты.** Удалены `ReportEditorView`, `ReportStructureEditor`,
`ReportUserLayoutEditor`, `ReportParamEditor`-соседи по UDR-редактору
(`ReportEditorMode`, `ReconcileDialog`, `ReportRunDialog`,
`ReportPreviewDialog`) и их тесты. Маршруты `/report-editor`,
`/report-editor-compact`, `/report-editor-structured` исчезли из реестра
deep-link baseline; молчаливого редиректа нет — старый адрес не открывает чужой
экран.

**Реестры.** `platform-public-surface.txt` сокращён на 41 запись (38
`legacy-internal` + 3 API): UDR-типы (`ReportBand`, `ReportField*`,
`ReportExecutionService`, `ReportTemplateService`,
`ReportTemplateTransferService` и т. п.) больше не называет ни один файл
приложения. `d1-surface-measurements.md` пересчитан штатным
`-Dplatform.measurements.write=true`: 283 файла приложения, 15 legacy-типов,
40 legacy-ссылок.

## 2. Что сохранено намеренно

- `ReportParamEditor` и `ReportQueryDialog` — это query-UI, которым пользуется
  UniDR (`UniDrQueryUiSupport`), а не UDR-редактор.
- Каталог `/report-catalog` и его aliases остаются точкой входа для UniDR/JR.
- `UdrTemplateArchive`, описание `ipro-report-template/3` и шесть JSON-примеров
  остаются описанием старого формата: они больше не обещают переноса данных, но
  позволяют прочитать архивный JSON, если он где-то всплывёт.
- Движок UDR (`reportstudio.dom/render/run/service/transfer`) и таблицы
  `report_template*` не тронуты: это R3.2 и DB1.

## 3. Что это меняет для пользователя

| Действие | Было | Стало |
|---|---|---|
| Открыть каталог | UDR + JR + UniDR | JR + UniDR |
| Создать отчёт | UDR / JR / UniDR | JR / UniDR |
| Открыть UDR-шаблон | редактор по `/report-editor?id=…` | маршрута нет; шаблон не открывается |
| Запустить UDR-отчёт | каталог/печать | недоступно |
| Печать из списка/карточки | UDR + JR + UniDR | JR + UniDR |

## 4. Проверки

```text
test-compile                                          BUILD SUCCESS
ReportCatalogViewTest                                 1/0
ReportEditorRouteContractTest                         4/0
ContextualReportLauncherTest                          3/0
ReportPrintDialogUniDrTest                            2/0
ReportPrintActionTest                                 4/0
ReportCardPrintCustomizationTest                      3/0
DeepLinkRouteBaselineTest                             6/0
WorkspaceManifestTest                                 4/0
views reportstudio/routes/unidr/query/components       107/0
EntitySummaryActionPilotTest                          9/0
ReportStudioAutoConfigurationSmokeTest                4/0
NeutralReportLayerBoundaryTest, QueryWorkbenchBoundaryTest,
ReportQueryUiBoundaryTest, PlatformDependencyDirectionTest   19/0
PlatformPublicSurfaceTest                             7/10 — прежние 3 падения:
                                                      wildcard-импорты,
                                                      fully-qualified ссылки,
                                                      два типа без роли
```

Новые тесты фиксируют именно снятие входов: каталог не содержит кнопки
«UDR — конструктор» и не открывает редактор, диалог печати не ведёт на
`ReportEditorView`, а маршрутов `report-editor*` нет ни в одном исходнике
пакета.

## 5. Что осталось в R3

1. **R3.2 — движок.** Удалить `reportstudio.dom`, `render/ReportCompiler`,
   `run/ReportExecutionService`, `service/ReportTemplate*`, `transfer/*`,
   repository и persistence registration. **Блокер снят 2026-10-23:**
   рендер и параметры UniDR переведены на `QueryDefinition`/`QueryParameter`
   ([evidence](r3-2-neutral-unidr-render-20261023.md)); DOM называют только
   файлы самого `reportstudio`.
2. **DB1 UDR.** Физические `report_template`, `report_band`, `report_field`,
   `report_param`, `report_order` удалить отдельной процедурой после R3.2;
   шесть тестовых строк не архивируются.
3. **Чистка каталога печати.** После удаления движка убрать `ReportEngineType.UDR`
   из матрицы возможностей и `ReportCatalogItemFactory.of(...)` для UDR.
