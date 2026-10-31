# Q3: редактор запроса на draft/session, UniDR без `ReportTemplate` (2026-10-20)

Основание: [ADR-0028](../decisions/ADR-0028-author-query-as-document.md) (Q3 обязателен),
[Q1/Q2 evidence](q1-q2-query-contract-20261019.md) §4. Срез закрывает Q3 по коду:
`UniDrQuerySource` перестаёт быть отчётным шаблоном, редактор, конструктор и диалог
запроса работают с нейтральным черновиком и сессией, «Применить»/«Отмена» — операции
над сессией.

## 1. Что изменено

**`UniDrQuerySource` — документ запроса, не шаблон.** Record хранит `kind`, `jpql`,
`visualQueryJson`, `parameters`, `tableEntity`; методов `from(ReportTemplate)`/`template()`
больше нет, тип не импортирует `ReportTemplate`/`ReportParam`. Энумы
`ReportParamKind`/`ReportParamSource`/`ReportComputedValue` остаются словарём сохранённого
v1/v2 JSON — это контракт данных, а не владение шаблоном.

**Одна точка отображения.** `UniDrQueryDefinitions` переводит `UniDrQuerySource` ↔
`QueryDefinition`/`QueryDraft` (`of`, `source`). `UniDrQueryUiSupport` собирает
`QueryEditorSession` и открывает диалог; шаблон существует только внутри переходного
адаптера на время вызова.

**Черновик и сессия.**
- `QueryDraft` — неизменяемый: нормализует пустые строки в `null`, `source` по умолчанию
  `MANUAL`, `VISUAL` без `visualQueryJson` — `IllegalArgumentException`; умеет
  `definition()`, `of(QueryDefinition)`, `withParameters(...)` и операции правки ниже.
- `QueryEditorSession` — `baseline`/`draft`, `update` с уведомлением слушателя, `apply`,
  `cancel`, `dirty`, `unapplied`. Отмена — возврат ссылки на подтверждённый черновик, а не
  откат мутаций шаблона.
- `ReportQueryEditor` — `open(QueryEditorSession)`, `open(QueryDraft)`, `draft()`;
  изменения контролов идут через `fireChanged()` в сессию/`changeListener`. Снапшот-откат
  `captureQuerySnapshot()` удалён: отмена больше не восстанавливает мутированный шаблон.
  Группировки и сортировки для предпросмотра приходят отдельными презентационными
  подсказками (`setPresentationHints`), а не отчётным DOM.
- `QueryDraft` получил неизменяемые операции (`withJpql`, `withSource`,
  `withVisualQueryJson`, `withParameter` с заменой по имени, `withoutParameter(s)`,
  `nextParameterPosition`), `QueryParameter` — `with...` для правки объявлений.
- `QueryEditorAnalysisService` принимает `QueryParameter` вместо `ReportParam`;
  `QueryTestParam.toParamKind()` отдаёт `QueryParameterKind`.
- `ReportQueryDialog` — единственный конструктор принимает сессию. «Применить и закрыть»:
  `editor.analyze()` → `session.update(editor.draft())` → обработчик экрана → `session.apply()`.
  Отказ guard'а или исключение обработчика возвращают прежний черновик и оставляют окно
  открытым; «Отмена» и закрытие окна — `session.cancel()` + `editor.open(session)`.
- `ReportEditorView` передаёт диалогу сессию, а запись в редактируемый `ReportTemplate`
  делает `ReportTemplateQueryAdapter.applyTo`: объявления параметров переиспользуются по
  имени, пропавшие удаляются, новые добавляются, порядок сохраняется.
- Встроенная вкладка «Запросы» пишет черновик в шаблон в `changeListener`, а
  `applyFormToTemplate` перед сохранением переносит весь черновик (текст, представление,
  объявления), а не только JPQL: иначе краткий запрос сохранялся бы без `VISUAL`-части.

## 2. Инвентарь поверхности

| Область | Файлов с `ReportTemplate`/`ReportParam` | Комментарий |
|---|---:|---|
| `src/main/java/org/ip/unidr` | 5 | `UniDrQuerySource` — только энумы; остальные — отображение/рендер (`UniDrParameters`, `UniDrQueryDefinitions`, `UniDrQuerySourceService`, `UniDrReportCompiler`) |
| `query/editor` | 0 | редактор, диалоги и анализ — на `QueryDraft`/`QueryParameter` |
| `query/constructor` | 0 | конструктор уже не импортирует отчётный DOM (раньше считался по подстроке `RegisterSliceReportTemplates`) |
| `query/*` всего | 3 | только именованные переходные адаптеры: `ReportTemplateQueryAdapter`, `RegisterSliceDraftAdapter`, `RegisterSliceConstructorSources` |

`setTemplate` у виджета больше нет: вход — `open(QueryDraft|QueryEditorSession)`, а
запись в отчётный шаблон делает экран (`ReportTemplateQueryAdapter.applyTo`) в
обработчике изменения. В UniDR-тестах `ReportTemplate` остался входом к легаси-описанию
(`ReportTemplateQueryAdapter.describe`), а не моделью источника.

## 3. Проверки

- `mvn -o -B -DskipTests test-compile` (JDK 21) — BUILD SUCCESS, 440 main-исходников.
- Регрессия `query/**`, `views/reportstudio`, `views/unidr`, `unidr/**` (только unit
  `*Test`) — **877 тестов, 0 failures / 0 errors, 18 skipped**.
- Новое покрытие: сессия (apply/cancel/dirty/слушатель и восстановление параметров),
  диалог (отказ применения, отмена, закрытие без применения), `applyTo` (идентичность
  объявлений, удаление и добавление, перенос `visualQueryJson`/`visualFilterJson`),
  неизменяемый черновик (`QueryDraftTest`), виджет без DOM (срез, конструктор, перенос
  тестовых параметров, смена текста).
- Новый гейт `QueryBackendBoundaryTest.editorAndConstructorPackagesDoNotUseReportDom`
  запрещает `ReportTemplate`/`ReportParam` и импорт `org.ipro.reportstudio.dom`
  в `query/editor` и `query/constructor`.
- Не выполнялись: полный `mvn verify`, PostgreSQL-прогон, браузерная приёмка, пересборка
  frontend.

## 4. Остаток и границы

Q3 по коду закрыт: `query/editor` и `query/constructor` не знают отчётного DOM.
`ReportTemplate`/`ReportParam` остались только в переходных адаптерах
(`query/adapter`) и в UI-экранах отчёта.

Что остаётся вне этого среза:
- Владение таблицей результата и query CSS из плана Q3 не переносились.
- Q2-остаток №2–3 (presentation hook в executor, read-only transaction boundary) в срез не
  входил и не менялся.
- `UniDrQuerySourceService`/`UniDrReportCompiler` собирают отчётный шаблон на границе
  рендера — это R2/S10, а не хранение запроса.
- Удаление самих адаптеров — после R3, когда UDR перестанет быть композитором отчёта.
