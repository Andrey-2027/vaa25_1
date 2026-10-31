# M1.2: query Vaadin вынесен в артефакт, шов формы снят (2026-10-21)

- Дата: 2026-10-21.
- Основание: второй шаг M1 плана разделения query/report
  ([план, §M1](../plans/query-report-separation-plan.md)); продолжение
  [M1](m1-query-contract-extraction-20261021.md).
- Срез: `editor`, `constructor`, `list`, `catalog` переехали из приложения в
  `platform-query-vaadin`. Прямая зависимость UI запроса от
  `org.ipro.form` снята портом, а не переездом формы в артефакт.

## 1. Замер до переноса

```text
editor        10 файлов / 2 739 строк
constructor   13 файлов / 4 215 строк
list           3 файла  /   211 строк
catalog        1 файл   /    18 строк
итого         27 файлов / 7 183 строки
импортов org.ipro.form в этих пакетах   4 (в 2 файлах)
```

Импорты были ровно двух типов: `SelectionFormAssembler` (колонки и модальный
выбор) и `EntityField` (поле ссылки). Всё остальное — `org.ipro.query`,
`org.ipro.metadata`, `org.ipro.rls`, `org.ipro.register`, `org.ipro.report.data`,
`org.ipro.crud` и Vaadin.

Вывод замера: тащить `platform-vaadin` в query UI незачем. Нужен не весь слой
форм, а один вопрос — «дай колонки и поле выбора ссылки». Это порт, а не
зависимость.

## 2. Что стало верно

**`platform-query-vaadin` — артефакт.** Пакеты:

```text
org.ipro.query.ui.editor        редактор, сессия черновика, каталог, диалог среза
org.ipro.query.ui.constructor   визуальный конструктор и его вкладки
org.ipro.query.ui.list          CTE списка
org.ipro.query.ui.catalog       дерево источников
org.ipro.query.ui.selection     порт EntitySelection (новый)
org.ipro.query.ui.config        QueryUiAutoConfiguration
```

29 файлов main + собственный `AutoConfiguration.imports`. Зависимости только
платформенные: `platform-core`, `platform-identity-api`, `platform-rls`,
`platform-register`, `platform-query`, `platform-report-core`, `filtergrid-core`,
Vaadin (`provided`) и Spring/Jackson/JPA для конфигурации. Приложение,
`reportstudio`, Jasper/DynamicReports и `org.ipro.form` запрещены гейтом
`QueryUiBoundaryTest`.

**Шов вместо зависимости.** `EntitySelection` — три операции: колонки выбора,
поле, чтение/запись значения. Поле — компонент Vaadin плюс `HasDisplayName`;
модальный диалог выбора остался деталью реализации и в порт не попал.
Прикладная сторона — `org.ipro.reportstudio.query.adapter.FormEntitySelection`:
он собирает `EntityField` и `SelectionFormAssembler` так же, как формы
приложения, и остаётся единственным местом, где query UI встречается с формой.

**Регистрация переехала к владельцу.** `QueryEditorAnalysisService` и
`QueryMetadataCatalogService` больше не объявляет
`ReportStudioAutoConfiguration`: их создаёт `QueryUiAutoConfiguration`
(`@AutoConfiguration(after = QueryAutoConfiguration.class)`). Список бинов тот
же; изменился владелец, поэтому редактор собирается и без отчётного слоя.

**Мост UniDR сократился.** В `UniDrQueryUiSupport` было 10 импортов
`org.ipro.reportstudio.*` (DOM + редактор UDR), осталось 4: два типа DOM и два
адаптера приложения. Гейт `UniDrQueryContractBoundaryTest` по-прежнему
shrink-only: новый импорт падает сборкой.

## 3. Гейты

| Гейт | Где | Что проверяет |
|---|---|---|
| `QueryUiBoundaryTest` (2 теста) | `platform-query-vaadin` | ни один исходник модуля не импортирует `org.ipro.form`, `reportstudio`, `org.ip`, Jasper/DynamicReports и отчётные `render/task`; imports-файл содержит ровно `QueryUiAutoConfiguration` |
| `ReportQueryUiBoundaryTest` | приложение | в `src/main/java/org/ipro/reportstudio/query` остался только `adapter` — копия UI в приложение не возвращается |
| `UniDrQueryContractBoundaryTest` | приложение | домен UniDR без `reportstudio`; мост UI — точный shrink-only список из 4 импортов |
| `ReportFormSeamTest` | приложение | шов формы: редактора в держателях больше нет, новый держатель — только `FormEntitySelection`; слой отчёта сжался до 69 файлов, порог невакуумности снижен намеренно (было 100) |
| `PlatformPackageDependencyMatrixTest` | приложение | матрица перегенерирована штатным `-Dplatform.matrix.write=true`; появились рёбра `org.ipro.query.ui -> ...` |
| `PlatformPublicSurfaceTest` | приложение | типы `org.ipro.query.ui.*` и адаптер получили reviewed-роли; `EntitySelection` в реестр не вносится — его называет только адаптер, а не `org/ip` |
| `WorkspaceManifestTest` | приложение | запись `platform-query-vaadin` топологична по `dependsOn` (без фантомного `platform-identity-api`: его приносит `crudui`), fingerprint совпадает |

## 4. Проверки

```text
mvn -o -B -f platform-query-vaadin/pom.xml install     EXIT=0
    Tests run: 2 (QueryUiBoundaryTest), 0/0
mvn -o -B -DskipTests test-compile                     EXIT=0
mvn -o -B test -Dtest='org.ipro.query.ui.*.*Test,
    ReportQueryDialogTest,UniDrQueryUiTest,ReportEditorViewTest,
    ReportEditorViewDirtyTest,ReportCatalogViewTest,ReportParamEditorTest,
    UniDrQuerySourceTest'                              Tests run: 169, 0/0
mvn -o -B test -Dtest='PlatformPackageDependencyMatrixTest,
    ReportFormSeamTest,ReportQueryUiBoundaryTest,
    UniDrQueryContractBoundaryTest'                    Tests run: 13, 0/0
```

`169` включает 87 тестов перенесённых пакетов (`ui.editor` 22,
`ui.constructor` 56, `ui.list` 9). Сами тесты остались в приложении: они
поднимают контекст и используют его фикстуры (`org.ip.model`), то есть
принадлежат потребителю, а не артефакту — их перенос на модульные фикстуры
— следующий шаг M1.3.

## 5. Что осталось и что не проверено

- **Тесты UI ещё в приложении.** 16 файлов под `src/test/java/org/ipro/query/ui`
  компилируются и идут в сборке приложения; своего набора у артефакта пока
  только `QueryUiBoundaryTest`.
- **Мост UniDR к UDR-редактору (4 импорта)** остаётся: он умирает вместе с UDR
  (R3/M2), а не вместе с query UI.
- **Чужие красные** в общем чекауте (не этот срез): `PlatformPublicSurfaceTest`
  (wildcard `org.ipro.design.*`, неревьюенные `TableSectionService`/
  `ProjectDesignContext`, FQN в `ReceivingDocumentFormConfig`, замер
  `app-source-files` 280 против 288) и `WorkspaceManifestTest` (fingerprint
  `platform-core` 153 → 154 файла, порядок `entity-design-json` перед
  `entity-design-core`). Записи этого среза этими красными не затронуты;
  полный `verify` не выполнялся.
- **Поведение не менялось:** перенос не касается текста запроса, guard,
  исполнения и печати; браузерная/PostgreSQL-приёмка не выполнялась.
