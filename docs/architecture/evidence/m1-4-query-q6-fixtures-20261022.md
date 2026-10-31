# M1.4: Q6-фикстуры и пять IT переехали в platform-query (2026-10-22)

- Дата: 2026-10-22.
- Основание: остаток шага 2 M1, названный в
  [M1.3](m1-3-query-module-tests-20261022.md); продолжение
  [плана, §M1](../../plans/query-report-separation-plan.md).
- Срез: три Q6-фикстуры и пять зависящих от них интеграционных тестов переехали
  из приложения в `platform-query`. Прикладным acceptance-тестам оставлен только
  тестовый артефакт модуля.

## 1. Замер до переноса

```text
src/test/java/org/ipro/reportstudio/query/q6/
    Q6Journal, Q6Product, Q6JpaTestConfiguration      3 файла
src/test/java/org/ipro/reportstudio/query/
    CteHibernateExecutorIT, CteHibernateSmokeIT,
    CteVirtualCatalogIT, VisualQueryPackageParserIT,
    VisualQueryPackageRlsIT                            5 IT / 10 тестов
```

Все пять IT используют только query-модуль, RLS и H2; прикладных импортов в них
не было. Две прикладные проверки (`VisualQueryPackageAssemblyIT`,
`VisualQueryQ6HibernateIT`) тоже используют Q6-сущности, но проверяют UDR-адаптер
и RLS-исполнение — они остаются acceptance приложения.

## 2. Что стало верно

**Фикстуры у владельца.** `Q6Journal` и `Q6Product` переехали в
`platform-query/src/test/java/org/ipro/query/q6`. Вместе с пятью IT модуль теперь
сам проверяет CTE-исполнение, виртуальный каталог, парсер пакетов, guard по CTE и
RLS-поведение: 47 тестовых файлов, **146 тестов**.

**Test-jar вместо копии фикстур.** Приложению нужны те же две сущности для двух
acceptance-тестов, поэтому `platform-query` публикует `tests`-jar
(`platform-query-1.0-SNAPSHOT-tests.jar`), а приложение объявляет его
test-scope зависимостью. Проверено содержимое: в jar есть `Q6Journal`/`Q6Product`
и нет `Q6JpaTestConfiguration`.

**Конфигурация не утекает.** `Q6JpaTestConfiguration` остаётся у каждой стороны
своей: модульная — в тестовом дереве `platform-query` и исключена из test-jar;
прикладная — `org.ipro.reportstudio.query.q6.Q6JpaTestConfiguration` в дереве
приложения. Иначе на тестовом classpath приложения оказались бы два
`@SpringBootConfiguration`, и autodetection `@SpringBootTest` упал бы на
«Found multiple @SpringBootConfiguration». Прикладные тесты задают
`@EntityScan("org.ipro.query.q6")` явно.

**Диалект H2 задан явно.** `CteHibernateSmokeIT` выключает
`use_jdbc_metadata_defaults`; в приложении диалект приходил из
`src/test/resources/application.properties`. В модуле добавлен такой же
тестовый `application.properties` с `org.hibernate.dialect.H2Dialect`.

**Что осталось в приложении (20 файлов):**

```text
8  acceptance на прикладной модели/безопасности
   QueryBuilderMetadataCatalogTest, ReportQueryExecutionIT, ReportQueryGuardTest,
   SqmQuerySemanticAnalyzerTest, VisualQueryGuardIT, VisualQueryPackageDeepPathIT,
   VisualQuerySubqueryIT, VisualQueryQ6HibernateIT
6  UDR-мост/адаптер: ReportQueryAssemblyServiceVisual* (3), VisualQueryPackageAssemblyIT,
   RegisterSliceCteGeneratorTest, RegisterSliceReportSecurityTest
2  адаптер формы: ReportTemplateQueryAdapterTest, ...OrderingTest
1  прикладная Q6-конфигурация (нужна двум acceptance-тестам выше)
3  гейты/фикстура: ReportQueryUiBoundaryTest, ReportAuthorizationWiringTest,
   EmptyEntitySelection
```

## 3. Проверки

```text
mvn -o -B -f platform-query/pom.xml install        Tests run: 146, 0/0   BUILD SUCCESS
jar tf platform-query/...-tests.jar                Q6Journal, Q6Product; конфигурации нет
mvn -o -B -DskipTests test-compile                 EXIT=0
mvn -o -B test -Dtest='VisualQueryPackageAssemblyIT,
    VisualQueryQ6HibernateIT,InitialGrantIssuanceWiringTest'   Tests run: 5, 0/0
```

`VisualQueryQ6HibernateIT` — три сценария CTE/LISTAGG/guard на H2; он поднимает
только тестовый артефакт модуля и прикладной RLS-контекст. Отдельный
`@SpringBootTest` (`InitialGrantIssuanceWiringTest`) подтверждает, что второй
`@SpringBootConfiguration` не появился.

## 4. Что осталось и что не проверено

- **Отрицательный потребитель (шаг 5 M1)** всё ещё не собран: backend без
  Vaadin/Jasper и UI без отчётных движков.
- **Полный `verify`** не выполнялся. `WorkspaceManifestTest` по-прежнему красный
  только на чужом дрейфе (`platform-core` 153 → 155, `platform-vaadin`
  226 → 229, порядок `entity-design-json`); запись `platform-query` обновлена
  намеренно — 123 файла.
- **Поведение не менялось:** текст запроса, guard, исполнение и печать не
  затронуты; PostgreSQL/браузерная приёмка не выполнялась.
