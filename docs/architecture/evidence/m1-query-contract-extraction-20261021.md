# M1: query-контракт вынесен из reportstudio (2026-10-21)

- Дата: 2026-10-21.
- Основание: M1 плана разделения query/report
  ([план, §M1](../plans/query-report-separation-plan.md)), закрытые Q1–Q3.
- Срез: физический перенос query backend в `platform-query` плюс нейтральный
  результат запроса в `platform-report-core`. Домен UniDR больше не импортирует
  `org.ipro.reportstudio.*`.

## 1. Замер до переноса

Измерение сделано по фактическим импортам, а не по списку пакетов: от
16 файлов `org.ip.unidr.*` и `org.ip.views.unidr.*` собран транзитивный
closure по `org.ipro.reportstudio.*`.

```text
прямых reportstudio-типов, которые называют UniDR-файлы   30
транзитивный closure (включая UDR DOM и query UI)         45 типов / 9 084 строки
всего reportstudio.query (все подпакеты)                  83 файла / 15 657 строк
из них Vaadin-независимый backend (flat + register + sqm) 55 файлов
```

Внутри closure явно разделились две вещи:

- **query backend** — описание запроса, каталог метаданных, визуальный
  конструктор/парсер/компилятор, guard, исполнитель, квоты, register-slice
  рецепты, резолвер параметров. Vaadin и отчётный DOM не нужны;
- **мост к UDR-редактору** — `ReportTemplate`/`ReportParam`,
  `ReportTemplateQueryAdapter` и `query.editor`. Он существует только потому,
  что UniDR-экран переиспользует редактор UDR; умирает вместе с UDR (R3/M2).

Вывод замера: backend можно вынести целиком, не трогая UDR DOM; UI-мост
выносить нечем — он держится на DOM, который ещё жив.

## 2. Что стало верно

**`platform-query` — артефакт, а не пакет в приложении.** Перенесены:

```text
org.ipro.query                описание/исполнение запроса (flat-пакет, 47 типов)
org.ipro.query.register       register-slice рецепты (7 типов)
org.ipro.query.sqm            semantic analyzer (1 тип)
org.ipro.query.param          ReportParamResolver/ResolvedParams/EntityParamRefresher
org.ipro.query.model          QueryDefinition/Parameter/Sort/Limits/... (Q1)
org.ipro.query.document       словарь сохранённого query-документа
org.ipro.query.config         QueryAutoConfiguration
```

Пакет `org.ipro.reportstudio.query` больше не существует как flat-пакет:
в нём остались только `adapter`, `constructor`, `editor`, `catalog`, `list`
(28 файлов против 83), и ни один из них не является backend-ом.

**Словарь JSON остался словарём JSON.** `ReportComputedValue`,
`ReportParamKind` (`PERIOD`), `ReportParamSource` (`DEFAULT`) и
`ReportQuerySource` переехали в `org.ipro.query.document` **без переименования
констант**. Это важно: v2-документ UniDR хранит имена этих enum-ов, и
«нейтрализация» через `QueryParameterKind.RANGE`/`QueryValueSource.FIXED`
сломала бы чтение сохранённых отчётов. Нейтральные `QueryParameterKind`/
`QueryValueSource` остаются API-моделью, а мост между ними и persisted-словарём
живёт в `UniDrParameters` — одна конвертация, а не два формата.

**Результат запроса — платформенный контракт.** `ReportDataset`, `ReportRow`,
`QueryField`, `EntityRef` переехали в `platform-report-core` (4 типа, JDK-only).
Иначе перенос backend был бы невозможен: модуль не может импортировать типы из
дерева приложения. Пакет сохранён (`org.ipro.report.data`), поэтому приложение
и JR/UniDR-рендер не меняли импорты.

**Регистрация переехала к владельцу.** `QueryAutoConfiguration` объявляет
guard/executor/quota/preview/резолвер/catalog/assembly/run-service/register-slice
и подключается модульным `META-INF/spring/...AutoConfiguration.imports`.
`ReportStudioAutoConfiguration` сократился до отчётного слоя (движки, кэш,
задачи, шаблоны) и query-UI приложения; ни одного query-бина он больше не
собирает. Список бинов не изменился — изменился владелец.

**BOM и поставка.** `platform-report-core` и `platform-query` добавлены в
`platform-bom`, корневой `pom.xml` и `scripts/local-dependencies.json`
(топологически: report-core → query). `platform-query` собирается с тестами:
границы артефакта проверяются в нём самом.

## 3. Гейты

| Гейт | Где | Что проверяет |
|---|---|---|
| `QueryBackendBoundaryTest` (5 тестов) | `platform-query` | модуль не импортирует `org.ipro.reportstudio.*`, `org.ip.*`, Vaadin; не зовёт `ReportExecutionService`; register-slice не строит `ReportTemplate`; executor зависит только от порта презентации; imports-файл содержит ровно `QueryAutoConfiguration` |
| `UniDrQueryContractBoundaryTest` (2 теста) | приложение | `org/ip/unidr/**` — **ноль** импортов `org.ipro.reportstudio.*`; `org/ip/views/unidr/**` — точный shrink-only список моста (10 импортов в одном файле), новый импорт падает сборкой |
| `ReportQueryUiBoundaryTest` | приложение | редактор/конструктор не говорят на DOM; run-слой держит DOM только в UDR-фасаде |
| `PlatformPackageDependencyMatrixTest` | приложение | матрица перегенерирована: плоские рёбра `reportstudio.query -> ...` заменены на `org.ipro.query -> ...` |
| `PlatformPublicSurfaceTest` | приложение | старые строки `reportstudio.query/model/dom` удалены; 30 названных приложением типов `org.ipro.query.*` получили роль API; мост adapter/editor остался `legacy-internal` с файлами |

## 4. Проверки

```text
mvn -o -B -f platform-report-core/pom.xml clean install   EXIT=0
mvn -o -B -f platform-query/pom.xml clean install         EXIT=0
    Tests run: 20 (OrderByApplierTest 15 + QueryBackendBoundaryTest 5), 0/0
mvn -o -B -DskipTests test-compile                        EXIT=0
mvn -o -B test -Dtest=PlatformPackageDependencyMatrixTest
    Tests run: 3, 0/0
mvn -o -B test -Dtest=UniDrQueryContractBoundaryTest,
    ReportQueryUiBoundaryTest,NeutralReportLayerBoundaryTest
    Tests run: 7, 0/0
mvn -o -B test -Dtest=WorkspaceManifestTest
    3 из 4 зелёные; красная — fingerprint drift platform-core/platform-vaadin
    (чужая незакоммиченная работа, см. §5)
```

`OrderByApplierTest` переехал в модуль вместе с кодом: он проверяет
package-private `findOrderBy`, и в дереве приложения после переноса тест
перестал бы компилироваться — это ожидаемый признак того, что тест принадлежит
артефакту, а не приложению.

Гейт R1 `NeutralReportLayerBoundaryTest` теперь читает `org.ipro.report.data`
в `platform-report-core`, а не в дереве приложения: иначе перенос уронил бы его
`NoSuchFile`, а копия датасета, тайком вернувшаяся в `src/main`, не была бы
замечена.

## 5. Что осталось и что не проверено

- **UI-мост UniDR.** `org.ip.views.unidr.UniDrQueryUiSupport` всё ещё держит
  `ReportTemplate`/`ReportParam` и редактор UDR (10 импортов в одном файле).
  Это не query-контракт, а переиспользование UDR-редактора; снимается вместе с
  R3/M2 (`platform-query-vaadin` + нейтральная форма параметров). Гейт
  фиксирует список, чтобы он не рос.
- **query Vaadin** (второй шаг M1) на момент этого среза ещё был в приложении;
  он выполнен отдельно — [M1.2](m1-2-query-vaadin-extraction-20261021.md):
  `editor`/`constructor`/`list`/`catalog` уехали в `platform-query-vaadin`,
  `org.ipro.form` заменён портом `EntitySelection`.
- **Тесты backend-а** (кроме `OrderByApplierTest`) пока остались в приложении:
  они используют `org.ip.model` и Spring-контекст приложения. Их перенос с
  модульными фикстурами — отдельный шаг M1.2.
- **Чужие блокеры в общем чекауте** (не этот срез): параллельная незакоммиченная
  работа над `platform-vaadin/explorer`, `platform-core` (`FieldMetadataInfo`) и
  `org.ip.views.admin.DraftEditorSession`/`ReceivingDocumentFormConfig` держит
  красным `PlatformPublicSurfaceTest` (wildcard `org.ipro.design.*`, FQN
  `FieldFactory`/`MetadataResolver`, неревьюенный `org.ipro.crud.TableSectionService`,
  замер `app-source-files` 280) и fingerprints `platform-core`/`platform-vaadin`
  в `WorkspaceManifestTest`. Общий `test-compile` на повторном прогоне зелёный.
  Записи манифеста этого среза (`platform-query`, `platform-report-core`,
  `platform-bom`) пересчитаны и совпадают; чужие fingerprint-ы намеренно
  не перезаписывались, чтобы не закрепить чужую незакоммиченную работу.
  Полный `verify` не выполнялся.
- PostgreSQL, браузерная приёмка и повторный экспорт/печать не проверялись:
  срез не меняет поведение исполнения запроса, только владельца кода.
