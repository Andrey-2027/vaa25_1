# R2/S7: сценарий «Создать отчёт о ценах» удалён (2026-10-20)

- Дата: 2026-10-20.
- Решение владельца: отчёт о ценах **не нужен**; кнопка и прикладной рецепт удаляются,
  а не переводятся на UniDR.
- Основание: [реестр R2](../r2-udr-scenario-inventory.md) §5.3 — «источник среза
  регистра в UniDR (S7) или явный отказ от сценария».
- Связанные документы: [ADR-0028](../decisions/ADR-0028-author-query-as-document.md)
  (запрос остаётся документом), [Р6](../information-register-r6-report.md)
  (историческое описание удалённого сценария).

## 1. Что удалено

| Узел | Факт |
|---|---|
| `org.ip.application.register.NomenclaturePriceReport` | прикладной рецепт «Номенклатура и цена на дату» — файл удалён |
| `org.ipro.reportstudio.query.adapter.RegisterSliceDraftAdapter` | единственный потребитель `SliceDraft` → `ReportTemplate` — файл удалён |
| `RegisterSliceReportTemplates.createDraft` | рецепт UDR-шаблона (`SliceDraft`, `DraftColumn`, `RootColumn`) удалён; `createSliceQuery`, `constructorSlice` и CTE-генератор не тронуты |
| `ReportCatalogView` | кнопка «Создать отчёт о ценах», поле и параметр конструктора `priceReport` |
| Тесты | `ReportCatalogViewTest.priceReportActionCreatesThroughTemplateServiceAndOpensTheSavedDraft`, `NomenclaturePriceCteSpikeTest.savedPriceReportReopensAndImportsWithRuntimeDateAndPointOracleParity`; освободившиеся `priceReport`/`renderer` в спайке убраны |

Сценарий не заменялся: по решению владельца отчёт о ценах не выпускается ни в UDR,
ни в UniDR. Срез как источник запроса остаётся доступен в конструкторе
(`RegisterSliceQueryDialog` → `createSliceQuery`), это отдельный сценарий Q3.

## 2. Что это меняет в R2

- Строка S7 больше не «только UDR»: у неё принятое решение «не нужен».
- Условие входа §5.3 закрыто явным отказом, а не реализацией замены.
- Прикладной код больше не производит `ReportTemplate`: единственными точками
  отчётного DOM остаются переходные адаптеры (`ReportTemplateQueryAdapter`),
  UI-экраны отчёта и `run`/`render`.
- `information-register-r6-report.md` остаётся историческим описанием Р6 и помечен
  как superseded: функциональность Р6 снята вместе со сценарием.

## 3. Проверки

```text
mvn -o -B -DskipTests test-compile                          EXIT=0   (JDK 21)
mvn -o -B test -Dtest=ReportCatalogViewTest,
    NomenclaturePriceCteSpikeTest,ReportEditorViewTest,
    ReportPrintDialogUniDrTest,ReportCatalogAssemblyGateTest
                                                            44 теста, 0 failures / 0 errors
```

- `NomenclaturePriceCteSpikeTest` — 7 тестов после удаления одного сценария;
  CTE-генерация, кодирование пакета, guard и оракул «срез против point-reader»
  остались зелёными.
- `ReportCatalogViewTest` — 23 теста; каталог, JR/UniDR-ветки и импорт не затронуты.
- Полный `mvn verify`, PostgreSQL и браузерная приёмка не выполнялись.

## 4. Границы

- Удалён только сценарий «создать отчёт о ценах». `RegisterSliceReportTemplates`
  как механизм среза сохранён: им пользуются конструктор запроса и `UniDRQueryUiSupport`.
- Локальные шаблоны «Номенклатура и цена на дату», созданные кнопкой ранее, не
  удалялись: их судьба — общий инвентарь S4/§5.5 реестра R2.
- Срез не затрагивает `ReportExecutionService`, `run`/`render` и R3: путь исполнения UDR остаётся как был.
