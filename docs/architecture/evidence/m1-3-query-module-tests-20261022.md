# M1.3: тесты запроса переехали к владельцу, прикладные фикстуры заменены (2026-10-22)

- Дата: 2026-10-22.
- Основание: шаг 2 этапа M1 плана разделения query/report
  ([план, §M1](../../plans/query-report-separation-plan.md)); продолжение
  [M1.2](m1-2-query-vaadin-extraction-20261021.md).
- Срез: тесты, которые проверяют код `platform-query` и `platform-query-vaadin`,
  перенесены в эти артефакты. Ссылки на `org.ip.model` в модульных тестах заменены
  модульными фикстурами; прикладные acceptance-сценарии и UDR-мост остались в приложении.

## 1. Замер до переноса

```text
src/test/java/org/ipro/query/**                 18 тестов UI / 2 134 строки
src/test/java/org/ipro/reportstudio/query/**    58 переносимых файлов:
                                                37 backend + 21 UI/parser
                                                26 остаётся в приложении (модель, Q6, UDR, гейты)
```

До среза модули собирались с двумя тестами (`platform-query`) и одним гейтом
(`platform-query-vaadin`), а всё остальное проверялось сборкой приложения. Это
маскировало зависимость артефакта: тест с `org.ip.model.Journal` компилировался
только потому, что лежал рядом с приложением.

## 2. Что стало верно

**`platform-query` — 39 тестовых файлов, 136 тестов.** Перенесены backend-тесты
описания/подготовки/выполнения запроса: компилятор визуального запроса, пакеты CTE,
парсеры, guard-диагностика, параметры, квоты, register-slice, контракт модели.
Два интеграционных теста (`CteHibernateFilterIT`, `SubqueryRlsIT`) остались
самодостаточными: собственные H2-сущности, `@DataJpaTest`, без `org.ip`. Для них в
POM добавлены test-scope `spring-boot-starter-test`, `spring-boot-starter-data-jpa-test`,
`h2`; surefire включает `*IT`, как в приложении.

**`platform-query-vaadin` — 26 тестовых файлов, 140 тестов.** 18 UI-тестов редактора,
конструктора и списков переехали без изменения поведения. Прикладные классы заменены
фикстурами:

```text
org.ipro.query.ui.fixture.DemoDocument        вместо org.ip.model.Journal / AccountingObjectInstallation
org.ipro.query.ui.fixture.DemoDocumentItem     табличная часть для ListCteGenerator
org.ipro.query.ui.fixture.DemoProduct          вместо org.ip.model.Nomenclature
org.ipro.query.ui.fixture.EmptyEntitySelection тестовый порт EntitySelection
```

`ReportQueryEditorTest` больше не импортирует прикладной
`org.ipro.reportstudio.query.adapter.EmptyEntitySelection`; имя сущности берётся из
`DemoDocument.class.getName()`, а не из строкового литерала прикладной модели.
Три теста парсеров (`NaturalLanguageQueryParserTest`, `VisualQueryTextParserTest`,
`VisualQuerySubqueryTest`) используют `QueryConstructorDraft`, поэтому переехали в
UI-артефакт: backend не может зависеть от UI, а разрезать сценарий на две копии
означало бы проверять разное.

**Гейты смотрят и на тестовое дерево.** `QueryBackendBoundaryTest` (6 тестов) и
`QueryUiBoundaryTest` (3 теста) теперь дополнительно требуют, чтобы ни один тест
модуля не импортировал `org.ip.`/`org.ipro.reportstudio.` (UI-гейт — ещё и
`org.ipro.form.`). Гейт пропускает только самого себя, потому что его константы
содержат те же строки. В приложении `ReportQueryUiBoundaryTest` получил проверку,
что дерево `src/test/java/org/ipro/query` не вернулось: копия тестов снова начала бы
компилироваться против прикладных фикстур.

**Что осталось в приложении осознанно:**

```text
8 тестов на прикладной модели/безопасности   QueryBuilderMetadataCatalogTest,
                                             ReportQueryExecutionIT, ReportQueryGuardTest,
                                             SqmQuerySemanticAnalyzerTest, VisualQueryGuardIT,
                                             VisualQueryPackageDeepPathIT, VisualQuerySubqueryIT,
                                             VisualQueryQ6HibernateIT
5 IT на Q6-фикстурах приложения              CteHibernateExecutorIT, CteHibernateSmokeIT,
                                             CteVirtualCatalogIT, VisualQueryPackageParserIT,
                                             VisualQueryPackageRlsIT
6 тестов UDR-моста/адаптера                  ReportQueryAssemblyServiceVisual*Test (3),
                                             VisualQueryPackageAssemblyIT,
                                             RegisterSliceCteGeneratorTest,
                                             RegisterSliceReportSecurityTest
2 теста прикладного адаптера формы           ReportTemplateQueryAdapterTest,
                                             ReportTemplateQueryAdapterOrderingTest
1 кросс-артефактный ArchUnit-гейт            ReportAuthorizationWiringTest
```

Q6-фикстуры общие с тестами UDR-адаптера, поэтому их перенос требует отдельного
test-fixtures артефакта — это следующий шаг, а не M1.3.

## 3. Проверки

```text
mvn -o -B -f platform-query/pom.xml test          Tests run: 136, 0/0   BUILD SUCCESS
mvn -o -B -f platform-query-vaadin/pom.xml test   Tests run: 140, 0/0   BUILD SUCCESS
mvn -o -B -DskipTests test-compile                EXIT=0               BUILD SUCCESS
mvn -o -B test -Dtest='ReportQueryUiBoundaryTest,ReportFormSeamTest,
    UniDrQueryContractBoundaryTest,PlatformPackageDependencyMatrixTest,
    ReportQueryDialogTest,UniDrQueryUiTest,ReportEditorViewTest,
    ReportEditorViewDirtyTest,ReportCatalogViewTest,ReportParamEditorTest,
    UniDrQuerySourceTest'                          Tests run: 96, 0/0
mvn -o -B test -Dtest=WorkspaceManifestTest       4 tests, 2 failures — только чужие
```

`WorkspaceManifestTest` после обновления fingerprints двух модулей называет только
известный чужой дрейф: `platform-core` (153 → 155 файлов), `platform-vaadin`
(226 → 229) и порядок `entity-design-json`/`entity-design-core`. Записи
`platform-query` (114 файлов) и `platform-query-vaadin` (57 файлов) совпадают.

## 4. Что осталось и что не проверено

- **Отрицательный потребитель (шаг 5 M1) не собран.** Независимый backend-потребитель
  без Vaadin/Jasper и UI-потребитель без отчётных движков — следующий шаг; M1 не
  закрыт одним переносом тестов.
- **Q6-фикстуры и IT на них** остаются в приложении до появления test-fixtures
  артефакта; это не прикладные сценарии, а модульная интеграция, и она должна
  переехать следующей.
- **Поведение не менялось:** текст запроса, guard, исполнение и печать не затронуты;
  PostgreSQL/браузерная приёмка не выполнялась.
- **Чужие красные** общего чекаута не перезаписывались: fingerprints `platform-core`
  и `platform-vaadin` обновит владелец их среза.

Продолжение: остаток Q6-фикстур и пяти IT закрыт в
[M1.4](m1-4-query-q6-fixtures-20261022.md) 2026-10-22.
