# Q2-остаток: register slice отдаёт QueryDefinition (2026-10-19)

- Дата: 2026-10-19. Статус: **закрыто**; `ReportTemplate` больше не собирается
  в `query/register`.
- Основание: Q2, п. 3 плана `docs/plans/query-report-separation-plan.md`
  (локальный, не нормативный); [Q1/Q2 evidence](q1-q2-query-contract-20261019.md);
  [ADR-0028](../decisions/ADR-0028-author-query-as-document.md).

## 1. Что изменилось

- `RegisterSliceReportTemplates.createSliceQuery(...)` возвращает
  `QueryDefinition` (визуальный JSON, JPQL, параметры даты и отборов, лимиты
  по умолчанию) вместо `ReportTemplate`.
- `createDraft(...)` возвращает нейтральный `SliceDraft` (имя, описание,
  `QueryDefinition`, колонки макета) вместо готового UDR-шаблона.
- Появился переходный `RegisterSliceDraftAdapter` — единственное место, где
  `SliceDraft` становится `ReportTemplate` с DETAIL-бэндом.
- `ReportTemplateQueryAdapter` получил обратное преобразование
  `QueryDefinition → ReportTemplate`, `QueryParameter → ReportParam` и
  перенесённые helpers `dateParameters/dateTimeParameters/parameterTypes`.
- `RegisterSliceQueryDialog.Result` несёт `QueryDefinition`; редактор получает
  первый параметр через `QueryParameter` и создаёт `ReportParam` только в
  переходном адаптере.
- `ReportParamForm` читает типы дат через `ReportTemplateQueryAdapter`.
- `query/register` больше не импортирует `org.ipro.reportstudio.dom`; в нём нет
  упоминаний `ReportTemplate`/`ReportParam`.

## 2. Заборы

`QueryBackendBoundaryTest` расширен:

- каталог `query/register` включён в backend-замыкание (запрещены импорты
  отчётного DOM, UniDR, run-слоя и Vaadin);
- отдельный тест `registerSliceRecipesDoNotBuildReportTemplates` ищет
  `\bReportTemplate\b`/`\bReportParam\b` в `query/register` и падает при
  попытке снова собирать шаблон в генераторе среза.

## 3. Проверки

| Набор | Тестов | Результат |
|---|---|---|
| Адресный: `RegisterSliceCteGeneratorTest`, `RegisterSliceReportSecurityTest`, `NomenclaturePriceCteSpikeTest`, `ReportQueryEditorTest`, `ReportCatalogViewTest` | 97 | 0 failures / 0 errors |
| Регрессия: `query.**`, `param.**`, `unidr.query.**`, `unidr.render.**`, `application.register.**`, `views.reportstudio.**`, `views.unidr.**`, `report.**` | **891** | **0 failures / 0 errors** |
| `QueryBackendBoundaryTest` (отдельно) | 3 | зелёный |

`git diff --check` чистый; случайная CRLF-нормализация в трёх файлах убрана,
диффы сжаты до реальных строк.

Границы: полный `mvn verify`, PostgreSQL-прогон, production frontend и
браузерная приёмка не выполнялись.

## 4. Что осталось в Q2

1. Presentation hook в `ReportQueryExecutor` (`EntityPresentationService`,
   `RecorderTypeRegistry`) — вынести декоратором результата.
2. Read-only transaction boundary и лимиты — сделать границей выполнения, а не
   параметрами вызова.

Q3 (`UniDrQuerySource`, редактор, `setTemplate` → query draft/session) не начат.
