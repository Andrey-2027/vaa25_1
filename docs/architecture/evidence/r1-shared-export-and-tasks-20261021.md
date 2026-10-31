# R1/S10: общий экспорт Jasper, пул воркеров и типы датасета (2026-10-21)

- Дата: 2026-10-21.
- Основание: R1 плана разделения query/report, остаток §4
  [R3 evidence](r3-neutral-run-seam-20261020.md) и блокеры S9–S10
  [реестра R2](../r2-udr-scenario-inventory.md).
- Срез закрывает S10 и вторую половину R1 №2: UniDR больше не импортирует
  `org.ipro.reportstudio.{data,render,run,task}`, а JR ставит фоновую задачу
  через нейтральный исполнитель; экспорт, пул воркеров и типы датасета живут в
  `org.ipro.report.*`.

## 1. Что стало верно

**Экспорт отделён от компиляции макета.** `ReportCompiler` сузился до
`compile(ReportTemplate, ReportDataset)` и больше не несёт экспортного API.
Готовый `JasperPrint` экспортирует `org.ipro.report.render.JasperExporter`
(реализация `JasperReportExporter`): форматы `PDF/XLSX/DOCX/CSV`, свойства
числовых XLSX-ячеек, CSV в UTF-8, ошибка `ReportRenderException`. Компилятор
UDR bands остался в `reportstudio/render` и знает только модель + датасет.

**Пул воркеров нейтрален.** `ReportTaskExecutor` и `ReportTaskCompletion`
перенесены в `org.ipro.report.task` без изменения поведения: снимок
аутентификации и request attributes, очистка ThreadLocal в `finally`, bounded
queue, отказ до постановки задачи, `destroyMethod = close`. UniDR берёт
исполнитель напрямую в `UniDrRunService` и не проходит через
`ReportExecutionService.executeAsync`.

**Типы датасета нейтральны.** `ReportDataset`, `ReportRow`, `QueryField`,
`EntityRef` находятся в `org.ipro.report.data`, `FormulaEvaluator` — в
`org.ipro.report.render`. UniDR-рендер, вычисляемые колонки и query backend
работают на них, отчётный DOM для этого не нужен.

**Общая композиция, раздельное владение.** `ReportStudioAutoConfiguration`
регистрирует `jasperExporter` и передаёт его в `ReportExecutionService`;
UDR-фасад остаётся потребителем тех же нейтральных операций, а не владельцем
экспорта. UniDR по-прежнему отдаёт повторный экспорт одного запуска из
`UniDrRenderedReport` (кэш по формату на прогон), не создаёт второй pool и не
перечитывает БД.

**JR ставит задачу напрямую.** `JrxmlRunDialog` принимает `ReportTaskExecutor`,
а не `ReportExecutionService`; исполнитель прокинут через
`ReportCatalogView`, `ReportPrintDialog`, `ContextualReportLauncher`,
`ReportPrintAction` и `ItemFormReportActions.CardLauncher`. Снимок
аутентификации/request attributes и очистка ThreadLocal остались за
исполнителем; второго pool нет. `executeAsync` на фасаде остался только у
UDR-диалога и уйдёт вместе с ним.

## 2. Гейт

`NeutralReportLayerBoundaryTest` фиксирует обе стороны шва:

- пакеты `report/data`, `report/render`, `report/task` не импортируют
  `org.ipro.reportstudio.*`, UniDR, `org.ip.views.*`, Vaadin и не упоминают
  `ReportTemplate`/`ReportParam`/`ReportBand`;
- `org/ip/unidr/**` и `org/ip/views/unidr/**` не импортируют
  `reportstudio.{data,render,run}`;
- `org/ip/views/reports/**` (JR) не импортирует `reportstudio.run` — фоновые
  задачи идут через `org.ipro.report.task`.

Гейт проверяет именно вынесенные куски, а не «UniDR вообще без reportstudio»:
оставшиеся импорты UniDR — query-контракт (`reportstudio.query`,
`reportstudio.query.model`, `reportstudio.param.ReportContext`) и словарь
сохранённого JSON (`ReportComputedValue`/`ReportParamKind`/`ReportParamSource`/
`ReportQuerySource`). Их физический вынос — M1, а не S10.

## 3. Проверки

```text
mvn -o -B -DskipTests test-compile                          EXIT=0   (JDK 21)
mvn -o -B test -Dtest=NeutralReportLayerBoundaryTest,
    QueryBackendBoundaryTest,ReportTaskExecutorTest,
    FormulaEvaluatorTest,JasperReportCompilerTest,
    CountRowsDynamicReportsSpikeTest,UniDrReportCompilerTest,
    UniDrRenderedReportTest,UniDrRunServiceTest,UniDrDownloadsTest,
    UniDrConfigurationTest,RegisterSliceReportSecurityTest  145 тестов, 0/0
mvn -o -B test -Dtest=ReportCatalogViewTest,ReportPrintActionTest,
    ContextualReportLauncherTest,ReportCardPrintCustomizationTest,
    ReportPrintDialogUniDrTest,EntitySummaryActionPilotTest,
    UiThreadingBoundaryTest,ApplicationBackgroundBoundaryTest,
    UniDrRunServiceTest,UniDrDownloadsTest,UniDrEditorTest,
    UniDrConfigurationTest,RegisterSliceReportSecurityTest,
    QueryBackendBoundaryTest,NeutralReportLayerBoundaryTest 139 тестов, 0/0
mvn -o -B test -Dtest=NomenclaturePriceCteSpikeTest,
    ReportEditorViewTest,ReportEditorViewDirtyTest          36 тестов, 0/0
```

- Перенесённые тесты: `ReportTaskExecutorTest` → `org.ipro.report.task`,
  `FormulaEvaluatorTest` → `org.ipro.report.render`.
- `QueryBackendBoundaryTest` больше не ожидает `export` в `ReportCompiler`;
  `JasperReportCompilerTest` проверяет форматы через новый экспортёр.
- `NomenclaturePriceCteSpikeTest` подтверждает полный Spring-контекст с новым
  параметром `ReportTaskExecutor` у каталога/действий.
- Первая строка набора — срез S10 до перевода JR-диалога; после него
  `test-compile` — BUILD SUCCESS, а адресные наборы — 139 и 36 зелёных тестов.
- Не выполнялись: полный `mvn verify`, PostgreSQL, браузерная приёмка.

## 4. Что осталось

- `ReportExecutionService` (включая `executeAsync` для UDR-диалога),
  `ReportCompiler.compile(ReportTemplate, …)`, модель `ReportTemplate` и
  переходный адаптер удаляются вместе с UDR (R3/S4, S8).
- UniDR-импорты query-контракта снимаются выносом query-слоя (M1).
- Квоты и лимиты очереди у JR-пути нет — она общая `ReportRunQuota` UniDR/UDR;
  это свойство сценария, а не шва исполнителя.
