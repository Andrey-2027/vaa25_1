# T1: самостоятельный экран «Конструктор запросов» (2026-10-22)

- Дата: 2026-10-22.
- Основание: §T1 плана разделения query/report
  ([план](../plans/query-report-separation-plan.md)); продолжение
  [Q3](q3-editor-draft-session-20261020.md) и [M1.4](m1-4-query-q6-fixtures-20261022.md).
- Срез: query → таблица без отчёта. Экран `/queries` работает с черновиком в памяти,
  показывает ограниченный снимок (N и признак «есть ещё»), а таблица результата
  вынесена из редактора в компонент `platform-query-vaadin`.

## 1. Что было не так

Редактор запроса показывал строки так же, как редактор отчёта: `preview(maxRows)`
возвращал `ReportDataset`, и «сколько строк» выводилось из `rowCount()`. Ровно N
строк — это одновременно «весь результат» и «усечённый до N», различить их по
числу строк нельзя. Отдельного экрана не было: единственным «самостоятельным»
входом оставался тестовый `/reports-preview` (`org.ip.views.test.ReportQueryPreviewView`),
который план прямо запрещает объявлять production UI.

## 2. Что стало верно

**Контракт снимка отделяет «получено N» от «есть ещё».**
`QuerySnapshotService` (platform-query) просит у исполнителя `N+1` строку, а не N,
и по факту лишней строки ставит `truncated`. Лишняя строка отрезается до
`ReportDataset`, reference-колонки укорачиваются до той же длины, поэтому ключи
строк остаются согласованными. `QuerySnapshot` — запись `(dataset, truncated, limit)`
с инвариантом: усечённый снимок содержит ровно `limit` строк. Политика запуска
(`QueryLimits`, guard, RLS) не дублируется — снимок только ограничивает уже
разрешённое чтение.

**Таблица результата — компонент, а не часть редактора.**
`org.ipro.query.ui.result.QueryResultTable` (platform-query-vaadin) владеет гридом,
фильтром и подписью снимка. Она показывает «Получено N строк» либо «Показано N
строк: результат больше лимита L», а при фильтре — «Фильтр по снимку: K из N» и
прямо говорит, что сортировка и фильтр действуют на снимок, а не на запрос.
`ReportQueryEditor` использует тот же компонент; отчётный режим (без снимка)
сохранён перегрузкой `show(fields, dataset)`.

**Экран `/queries`.** `QueryWorkbenchView` собирает редактор из тех же query-бинов,
открывает пустой черновик и не сохраняет его: каталог шаблонов, очереди и отчётные
движки не участвуют. Пункт меню «Конструктор запросов» появляется только по
правилу `QueryWorkbenchAccess` — анонимный пользователь не проходит, право чтения
конкретных сущностей по-прежнему проверяет guard запроса. Прежний
`/reports-preview` удалён, а не переименован: тестовый экран не должен наследовать
production-адрес.

**Адрес и baseline.** `/queries` внесён в `routes/deep-link-baseline.txt`
(`DeepLinkRouteBaselineTest`), запись `/reports-preview` удалена. `FormEntitySelection`
остаётся единственным местом встречи query UI с формой платформы; новый экран
называет его так же, как редактор отчёта, — это зафиксировано в реестре публичной
поверхности (`platform-public-surface.txt`) явным списком файлов, а не скрытой
ссылкой.

## 3. Проверки

Новые адресные тесты (22):

```text
platform-query          QuerySnapshotServiceTest                 5
platform-query-vaadin   QueryResultTableTest                     6
platform-query-vaadin   ReportQueryEditorSnapshotTest            4
приложение              QueryWorkbenchAccessTest                 4
приложение              QueryWorkbenchBoundaryTest               3
```

`QuerySnapshotServiceTest` — 0/N/N+1, ровно N без усечения, усечение с
reference-колонками. `QueryResultTableTest` — подпись снимка, фильтр по снимку и
типизированный компаратор колонки (9 раньше 10, хотя строка показа дала бы обратный
порядок). `ReportQueryEditorSnapshotTest` — редактор спрашивает N+1,
показывает лимит, повторный запуск заменяет снимок, ошибка очищает строки и
пишет причину. `QueryWorkbenchAccessTest` — анонимный/неаутентифицированный
отклонён, аутентифицированный допущен. `QueryWorkbenchBoundaryTest` — маршрут
`/queries`, `@PermitAll` + правило входа, в пакете `org.ip.views.query` нет
импортов отчётных движков и нет `ReportTemplate`.

Полные прогоны модулей:

```text
platform-query install/test          Tests run: 151, 0/0   BUILD SUCCESS
platform-query-vaadin test           Tests run: 150, 0/0   BUILD SUCCESS
приложение: QueryWorkbench*, DeepLinkRouteBaselineTest   13/0
приложение: test-compile             EXIT=0
```

`PlatformPublicSurfaceTest`: исправлены два следствия этой работы — снят запрет
на новый файл, называющий `FormEntitySelection`, и пересчитан
`d1-surface-measurements.md` (`app-source-files` и остальные замеры). Остаются
3 падения, не связанные с T1: wildcard-импорты в `EntityDraft*`/`ExplorerFormPreview`,
fully-qualified ссылки в `ReceivingDocumentFormConfig` и два типа без роли
(`TableSectionService`, `ProjectDesignContext`) — это долг соседней работы по
entity draft. `ReportEditorViewSupersetTest`/`ReportEditorViewToolbarUniquenessTest`
падают на уже принятом закрытии создания UDR (кнопка «Новый шаблон» убрана) — их
книга возможностей ещё не обновлена, к T1 это не относится.

## 4. Что этот срез не обещает

- Нет серверной пагинации, total count и сортировки/фильтра на стороне запроса:
  таблица работает по полученному снимку и говорит об этом пользователю.
- Нет сохранения запроса как документа (D3/ADR-0028) — черновик живёт в вкладке.
- Шаг 5 M1 (отрицательный потребитель без Vaadin/Jasper) не выполнен: экран —
  положительный потребитель тех же артефактов, но удаление `platform-query` пока
  не доказывается падением отдельной сборки.
- UI-мост UniDR (`org.ip.views.unidr`) продолжает использовать тот же редактор;
  он умирает вместе с R3/M2.
