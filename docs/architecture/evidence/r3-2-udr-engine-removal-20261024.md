# R3.2: UDR-движок удалён (2026-10-24)

- Дата: 2026-10-24.
- Основание: §R3 плана разделения query/report
  ([план](../../plans/query-report-separation-plan.md)); продолжение
  [R3.2 «UniDR без ReportTemplate»](r3-2-neutral-unidr-render-20261023.md), где был
  снят последний блокер со стороны UniDR.
- Срез: удалён сам движок — DOM, компилятор, execution service, шаблонный
  сервис/transfer, persistence-регистрация и ветка UDR в каталоге. Таблицы
  `report_template*` на момент среза оставались; в тот же день удалены из
  `DBVaa25` ([DB1 UDR](db1-udr-removal-20261024.md)). Архивный читатель
  `org.ip.archive.UdrTemplateArchive` не затронут.

## 1. Что удалено

`src/main/java/org/ipro/reportstudio` — 123 файла:

```text
query/       72   backend и адаптер шаблона (уехали в platform-query ещё в M1)
dom/         21   ReportTemplate/ReportBand/ReportField/ReportParam/...
run/          4   ReportExecutionService, кэш артефактов, UDR-фасад
param/        4   resolver/refresher поверх DOM
transfer/     3   экспорт/импорт JSON шаблона
service/      2   ReportTemplateService и UdrCatalogContributor
render/       2   ReportCompiler и JasperReportCompiler
dto/          2   DTO/маппер шаблона
config/       2   ReportSchemaCompatibility, ReportStudioPersistenceAutoConfiguration
layout/       1   ReportLayoutOperations
```

Плюс `ReportTemplateRepository` и `ReportEngineType.UDR` с записью в матрице
возможностей: каталог знает только JR и UniDR. `ReportStudioAutoConfiguration`
сохраняет нейтральные бины (`JasperExporter`, `ReportTaskExecutor`,
`ReportContextFactory`, `ObjectMapper` для query-слоя); модульная
`QueryAutoConfiguration` продолжает регистрировать backend запроса.

## 2. Реестры

- `META-INF/spring/...AutoConfiguration.imports` — убрана регистрация
  `ReportStudioPersistenceAutoConfiguration`.
- `PersistenceTypeRegistrationTest` — из reviewed-списков убраны
  `org.ipro.reportstudio.dom` (`@EntityScan`) и `org.ipro.reportstudio`
  (`@EnableJpaRepositories`).
- `PersistenceRegistrationIT` — из карты репозиториев убран
  `ReportTemplateRepository`.
- `CompatibilityMigrationArchitectureTest` — удалён `ReportTemplateService` из
  reviewed-списков `BaseService`-реализаций и internal-store адаптеров; забор
  stale-записей сам поймал удаление.
- `ReportEngineCapabilitiesTest` / `ReportCatalogServiceTest` — матрица и
  каталог проверяются на паре JR/UniDR.
- `ReportQueryUiBoundaryTest` — гейты `reportstudio/query` и `reportstudio/run`
  стали «пусто»: адаптер формы и UDR-фасад удалены, отчётного DOM в run-слое нет.

## 3. Тесты

Восемнадцать тестовых файлов перестали компилироваться после удаления классов.
Приведённые ниже переведены на нейтральный контракт; остальные обновлены под
отсутствие UDR (реестры в §2 и три теста каталога/автоконфигурации).

```text
UniDrQuerySourceTest, UniDrReceivingItemsQueryTest   → UniDrQuerySource/QueryDefinition
RegisterSliceCteGeneratorTest                        → createSliceQuery/QueryDefinition
RegisterSliceReportSecurityTest                      → ReportQueryRunService + ReportDataset
VisualQueryPackageAssemblyIT                         → ReportQueryAssemblyService.assemble
ReportQueryAssemblyServiceVisual* (3)                → QueryDefinition
ListaggPostgresSpikeTest, UniDrReceivingItemsSpikeTest → QueryDefinition + QuerySort
NomenclaturePriceCteSpikeTest                        → удалены два UDR-roundtrip-теста
CanonicalReadBoundaryIT, CanonicalWriteBoundaryIT,
FetchPlanScenarioFactsIT                             → UserFormSettings вместо ReportTemplate
```

`ReportCatalogServiceTest`, `ReportEngineCapabilitiesTest`,
`ReportStudioAutoConfigurationSmokeTest` обновлены под отсутствие UDR.

## 4. Проверки

```text
mvn -o -q -pl . -am test-compile -DskipTests        0 ошибок
сводный набор среза (persistence, UniDr, register,
  smoke, boundary, visual assembly, гейты)          158/0
каталог/печать/параметры/UniDR UI                     43/0
UniDrReceivingItemsSpike                              7/0
ListaggPostgresSpike                                 26 запусков, 19 skipped
                                                     (PostgreSQL-профиль)
```

## 5. Что осталось

1. ~~**DB1 UDR.**~~ **Закрыт 2026-10-24:** физические `report_template`,
   `report_band`, `report_field`, `report_param`, `report_order` удалены из
   `DBVaa25` после инвентаря и проверенного бэкапа; post-checks чистые,
   компиляция — exit 0 ([runbook](../db1-udr-data-inventory.md),
   [evidence](db1-udr-removal-20261024.md)).
2. **Старые URL.** `report-editor*` удалены в R3.1; `deep-link-baseline.txt`
   сокращён тогда же.
3. **Чужие красные.** `PlatformPublicSurfaceTest` держит те же три падения,
   что зафиксированы в R3.2-предыдущем срезе (wildcard-импорты и fully-qualified
   ссылки в параллельной работе по Explorer, типы без reviewed-роли);
   `WorkspaceManifestTest` не перезаписывался из-за одновременного дрейфа
   `platform-core`/`platform-vaadin`. К этому срезу они отношения не имеют.
