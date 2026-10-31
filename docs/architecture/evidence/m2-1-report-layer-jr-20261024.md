# M2.1: общий отчётный слой и JR вынесены в артефакты (2026-10-24)

- Дата: 2026-10-24.
- Основание: [M2 плана](../../plans/query-report-separation-plan.md), первый срез —
  общий каталог/запуск/экспорт и движок JR; [D-R inventory](../d-r-report-addon-inventory.md).
- Срез: физический перенос из дерева приложения в два независимых артефакта —
  `platform-report` (нейтральный слой) и `platform-jr` (движок). UI каталога/печати
  и UniDR остаются приложению до M2.2.
- Предусловие: [M1 шаг 5](m1-5-negative-consumer-20261024.md) закрыт.

## 1. Состав артефактов

### `platform-report`

Перенесено 15 production-файлов:

```text
org/ipro/report/catalog/   7  ReportCatalogService/Item/ItemFactory/Contributor/ReadGate/EngineType/Capabilities
org/ipro/report/render/    5  JasperExporter/JasperReportExporter/ReportExportFormat/ReportRenderException/FormulaEvaluator
org/ipro/report/task/      2  ReportTaskExecutor/ReportTaskCompletion
org/ipro/report/config/    1  ReportCatalogAutoConfiguration
```

- Зависимости: `platform-rls`, `platform-report-core`, JasperReports, Spring
  context/tx/security/web, boot-autoconfigure.
- `ReportCatalogAutoConfiguration` теперь регистрирует и каталог, и `JasperExporter`,
  и `ReportTaskExecutor`; приложение этих бинов больше не объявляет.
- Фабрика строки каталога больше не знает `JrxmlTemplate`: осталась только
  механическая сборка `of(id, type, name, description, enabled, fileMissing)`.
  Правило JR (`fileMissing`, `enabled`) собирает адаптер в `platform-jr`.
- Собственный `META-INF/spring/...AutoConfiguration.imports`; Vaadin, `org.ip`,
  `reportstudio`, UDR DOM и JR-типы запрещены гейтом `ReportModuleBoundaryTest`.

### `platform-jr`

Перенесено 11 production-файлов: `JrxmlTemplate`, repository, service, catalog
contributor, execution service, JPQL executer/bundle/factory, порт
`JpqlDatasetRunner`, `JrAutoConfiguration`, `JrPersistenceAutoConfiguration`.
Ресурсы `jasperreports.properties` и `jasperreports_extension.properties` переехали
вместе с движком.

- Зависимости: `platform-report`, `platform-report-core`, `platform-core`,
  Spring Data JPA, JasperReports, Jakarta persistence/validation.
- JPQL-источник остаётся портом `JpqlDatasetRunner`; реализация `JpqlRunService`
  (guard + RLS) живёт в приложении. `org.ip`, `reportstudio` и Vaadin запрещены
  гейтом `JrModuleBoundaryTest`.
- Persistence-регистрация (`@EntityScan`/`@EnableJpaRepositories`) публикуется
  артефактом; приложение больше не перечисляет JR-сканы.
- Собственный `META-INF/spring/...AutoConfiguration.imports` (persistence + сервисы).

## 2. Переключение приложения

- Корневой POM зависит от `platform-report` и `platform-jr`.
- Из app-owned `AutoConfiguration.imports` удалены `ReportCatalogAutoConfiguration`,
  `JrPersistenceAutoConfiguration`, `JrAutoConfiguration`; остался
  `ReportStudioAutoConfiguration` с прикладными бинами (`ObjectMapper`,
  `ReportContextFactory`).
- `ReportStudioAutoConfiguration` больше не регистрирует `JasperExporter` и
  `ReportTaskExecutor` — они приходят из `platform-report`.
- `platform-bom` и `scripts/local-dependencies.json` получили оба артефакта;
  fingerprints посчитаны по v2 (24 и 20 файлов). `dependsOn` совпадает с POM-графом.

## 3. Проверки

```text
mvn -f platform-report/pom.xml clean install   33 tests, 0 failures, 0 errors
mvn -f platform-jr/pom.xml clean install       13 tests, 0 failures, 0 errors
mvn -DskipTests compile (приложение)           BUILD SUCCESS
scripts/check-query-consumers.ps1              PASS, обе стороны
```

Модульные тесты включают перенесённые `ReportCatalog*`, `FormulaEvaluator`,
`ReportTaskExecutor`, `JrCatalogContributor`, `JrPersistenceRegistration`,
`JrxmlTemplateService`, `JrxmlFormatProbe` и два новых boundary-гейта.

Обновлены под новый адрес: `NeutralReportLayerBoundaryTest` (нейтральные корни —
`platform-report`), `PersistenceTypeRegistrationTest` (JR-типы в `platform-jr`),
`PlatformSubsystemClosureTest` (замыкание `reportstudio` на дерево стало пустым).

**Не проверено:** `test-compile` приложения падает на уже известном чужом дрейфе
(`SubsystemRegistry`/`ManagedEntityTypes` в незакоммиченной работе Explorer), поэтому
app-level гейты (`NeutralReportLayerBoundaryTest`, `PlatformPublicSurfaceTest`,
`WorkspaceManifestTest`) в этом срезе не запускались. `d1-surface-measurements.md`
не перегенерирован по той же причине: числа `platform-tree-files`/
`platform-artifact-files` уже разошлись из-за параллельных переносов. Baseline
поверхности для новых артефактов (`platform-api-baseline/*.api`) не создавался —
это тот же незакрытый долг D3.9, что и у `platform-query`/`platform-report-core`.
Runtime-проверка каталога, печати JR и UniDR не выполнялась.

## 4. Что осталось на M2.2

- UI каталога, печати из списка/карточки, `ReportContextFactory` и прикладные
  потребители — переносятся вместе с UniDR.
- `dynamicreports-defaults.xml` и UniDR-рендер остаются в приложении.
- Точный состав артефактов (один `platform-report*` или отдельный UI-модуль)
  решается по фактической closure на M2.2.
