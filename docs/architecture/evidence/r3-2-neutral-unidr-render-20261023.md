# R3.2: UniDR без `ReportTemplate` — блокер снят (2026-10-23)

- Дата: 2026-10-23.
- Основание: §R3 плана разделения query/report
  ([план](../../plans/query-report-separation-plan.md)); предыдущий срез —
  [R3.1](r3-1-udr-entry-removal-20261023.md).
- Срез: только развязка UniDR (рендер и параметры) от UDR DOM. Само удаление
  `reportstudio.dom`/compiler/run/service/transfer и persistence — следующий шаг
  R3.2; физические таблицы — DB1 UDR.

## 1. Где был блокер

`UniDrReportCompiler` с R1 собирает `JasperPrint` из нейтрального
`ReportDataset` (`platform-report-core`) и `ReportTemplate` не называет. Блокер
оставался в UI-пути UniDR:

- `UniDrQueryUiSupport` строил `ReportTemplate` через
  `ReportTemplateQueryAdapter.toTemplate(...)`, чтобы отдать его
  `ReportParamEditor` и диалогу канонических параметров;
- `ReportParamEditor` редактировал `ReportParam`/`ReportTemplate`, то есть
  единственный редактор параметров запроса был привязан к UDR DOM;
- `ReportCatalogItemFactory.of(ReportTemplate)` держал DOM в общем каталоге
  отчётов.

Пока эти связи живы, удаление `reportstudio.dom` ломало UniDR, query UI и
каталог.

## 2. Что сделано

**Редактор параметров.** `ReportParamEditor` переведён на
`QueryDefinition`/`QueryParameter`: `setDefinition`/`getDefinition`, каждая
правка создаёт новый `QueryParameter` (`withName`, `withCaption`, `withKind`,
`withValueSource`, `withDefaultValue`, `withComputed`, `withRequired`,
`withShowOnForm`, `withEntityClass`, `withPosition`). Имя binding'а проверяется
по контракту нейтрального описания: недопустимый ввод помечается, но не
переписывает уже подтверждённое объявление; отмена — это отбрасывание
неизменяемой копии, а не откат мутаций.

**UniDR UI.** `UniDrQueryUiSupport` больше не собирает `ReportTemplate`:

- диалог «Параметры запроса» работает с `QueryDefinition`;
- `addMissingParameters` возвращает новое описание с добавленными
  `QueryParameter`, а не мутирует шаблон;
- `openCanonicalParameters` принимает и возвращает `QueryDefinition`;
- удалены переходные хелперы `templateOf/cloneTemplate/sourceOf/copyParameters`
  и неиспользуемый `form(UniDrQuerySource)`;
- форма запуска строится из `List<QueryParameter>`.

**Каталог.** `ReportCatalogItemFactory.of(ReportTemplate)` удалён: строку UDR
никто не строит с R3.1, а каталог больше не знает DOM движка.

**Шов выбора сущности.** `FormEntitySelection` переехал из
`org.ipro.reportstudio.query.adapter` в прикладной `org.ip.views.query`.
Адаптер не знает UDR DOM, поэтому `org.ip.views.unidr` теперь не импортирует
`org.ipro.reportstudio.*` вообще.

**Гейты.** В `NeutralReportLayerBoundaryTest` добавлен
`udrDomHasNoConsumersOutsideReportStudio`: production-деревья приложения и
`platform-*` модулей не называют `org.ipro.reportstudio.dom`.
`UniDrQueryContractBoundaryTest` больше не содержит разрешённого списка моста —
он пуст.

## 3. Что это даёт R3.2

- `org.ipro.reportstudio.dom` называют только 30 файлов внутри
  `org.ipro.reportstudio` (render/run/service/transfer/dto/layout/config).
  Их удаление не задевает UniDR, query UI, каталог, печать и платформу.
- `ReportTemplateQueryAdapter` используется только внутри
  `ReportExecutionService` — это тот же UDR, он уходит вместе с движком.
- Порядок дальше: удалить `reportstudio.dom` + `render/ReportCompiler` +
  `run/ReportExecutionService` + `service/ReportTemplate*` + `transfer/*` +
  repository/persistence registration; затем DB1 UDR (таблицы
  `report_template*`); затем `ReportEngineType.UDR` и UDR-ветку каталога.

## 4. Проверки

Приложение собирается против локально установленных `platform-query` (новые
`with*`-методы `QueryParameter`) и `platform-vaadin` из рабочего дерева.

```text
mvn -o -Dvaadin.skip=true compile                            BUILD SUCCESS
UniDrReportCompilerTest                                     48/0
UniDrQueryUiTest                                            11/0
ReportParamEditorTest                                        9/0
ReportParamFormTest                                          8/0
ReportCatalogItemFactoryTest + ReportCatalogServiceTest +
ReportCatalogAssemblyGateTest                               11/0
NeutralReportLayerBoundaryTest                               4/0
UniDrQueryContractBoundaryTest                               2/0
UniDr*/Report*/QueryWorkbench*/launcher/print — общий набор  413/0
```

Ограничение прогона: полный `test-compile` дерева сейчас упирается в
незакоммиченный `EntityExplorerPilotPanelTest` параллельной работы по Explorer
(в `EntitySummary.StructuralFieldRow` появились новые компоненты записи, тест
ещё не обновлён). Тестовые исходники, кроме этого файла, скомпилированы
`javac` по classpath проекта, наборы среза прогнаны через `surefire:test`.
`PlatformPublicSurfaceTest` после снятия записей `ReportParam`/`ReportTemplate`
и пересчёта `d1-surface-measurements.md` даёт те же 3 прежних падения
(wildcard-импорты, fully-qualified ссылки, типы `org.ipro.query.*` без роли).
`WorkspaceManifestTest` фиксирует дрейф fingerprint `platform-core`,
`platform-query` (этот срез) и `platform-vaadin` (параллельная работа) — манифест
не перезаписывался из-за одновременной правки; это ограничение окружения, а не
среза.

## 5. Что осталось в R3

1. **R3.2 — движок.** Удалить DOM/compiler/run/service/transfer и persistence.
   Блокера со стороны UniDR больше нет.
2. **DB1 UDR.** Физические `report_template`, `report_band`, `report_field`,
   `report_param`, `report_order` — отдельной процедурой после R3.2.
3. **Чистка каталога.** Убрать `ReportEngineType.UDR` и UDR-ветку печати после
   удаления движка.
