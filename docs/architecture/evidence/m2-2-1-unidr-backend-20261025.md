# M2.2.1: backend UniDR вынесен в `platform-unidr` (2026-10-25)

- Дата: 2026-10-25.
- Основание: [M2 плана](../../plans/query-report-separation-plan.md), [preflight M2.2](m2-2-preflight-20261024.md).
- Срез: физический перенос backend UniDR, переименование пакета и снятие трёх
  прикладных точек через порты. UI каталога/печати и UniDR остаются на M2.2.2.

## 1. Что переехало

32 production-файла в новый артефакт `platform-unidr`, пакет `org.ipro.unidr`:

- `model` — определение отчёта, параметры, JSON-кодек v1/v2, валидатор,
  вычисляемые колонки, query-источник;
- `source` — `UniDrSourceDefinition`, реестр и каталог источников;
- `store` — файловое хранилище и вклад UniDR в каталог отчётов;
- `render` — компиляция DynamicReports/Jasper и результат рендера;
- `run` — доступ/RLS, резолвер параметров, канонические значения, запуск и квоты;
- `query` — query-сервис, фильтры, проекции, реконсилятор источников и таблицы.

Вместе с рендером переехал `src/main/resources/dynamicreports-defaults.xml`.

## 2. Что осталось приложению

Пять production-файлов в `src/main/java/org/ip/unidr`:

- `UniDrReceivingItemsSource` — прикладной JPQL-источник «позиции накладных»;
- `UniDrQueryAdapter` — реализация порта `UniDrRunQueryAdapter`;
- `UniDrReferenceService` — реализация порта `UniDrReferenceCatalog` поверх
  `EntityLookup` и прикладных сущностей;
- `UniDrDefinitions` — прикладные рецепты;
- `UniDrConfiguration` — регистрация источника и сборка бинов.

Модуль знает только `UniDrSourceDefinition`, `UniDrReferenceCatalog` и
`UniDrRunQueryAdapter`; `org.ip`, Vaadin, reportstudio и прикладные сущности
запрещены гейтом `UniDrModuleBoundaryTest`.

## 3. Тесты

Шесть тестов без прикладных сущностей переехали в модуль с пакетом
`org.ipro.unidr`: `UniDrFilterSupportTest`, `UniDrQueryDefinitionsTest`,
`UniDrSourceReconcilerTest`, `UniDrTableSourcesTest`,
`UniDrRenderedReportTest`, `UniDrCanonicalValuesTest`. Прикладные типы в них
заменены локальными type-token'ами (`String`/`Long`, вложенные `Nomenclature`,
`UnitOfMeasurement`, `Branch`), поэтому `org.ip.model` в тестах модуля больше
не встречается.

В приложении остались тесты адаптеров, `UniDrDefinitions`, JPA/Postgres,
spike-сценарии и UI. Всего в модуле 8 тестовых классов, **31 тест**.

## 4. Сборка и реестр

- Корневой `pom.xml` и `platform-bom` подключают `platform-unidr`.
- В `scripts/local-dependencies.json` добавлен проект `platform-unidr` с
  зависимостями `dynamicreports, filtergrid, platform-metadata, platform-query,
  platform-report, platform-report-core, platform-rls` и fingerprint v2
  (43 файла), поэтому bootstrap собирает модуль на чистом локальном репозитории.
- В `dependsOn` для `platform-query` и `platform-query-vaadin` добавлен
  `platform-metadata`: pom'ы ссылались на него, а манифест — нет.

## 5. Гейты

- `WorkspaceManifestTest` — зелёный: рёбра совпадают с pom'ами, fingerprints
  пересчитаны намеренно (`-Dplatform.manifest.write=true`).
- `ReportFormSeamTest` — зелёный: из реестра убраны `Dirtyable`/`Savable`
  (единственный держатель `ReportEditorView` удалён в M1.2), `FormEntitySelection`
  переехал в `org.ip.views.query`, минимум исходников слоя снижен с 60 до 20
  (после переноса редактора/конструктора в `platform-query-vaadin` слой — 27
  файлов).

## 6. Проверки

- `platform-unidr`: **31/0**.
- `ReportFormSeamTest` + `WorkspaceManifestTest`: **10/0**.
- Адресный прогон приложения (UniDR, каталог/печать, гейты): **298/0**
  (было 325 тестов и 5 падений; 27 тестов переехали в модуль, оба гейта
  исправлены).

## 7. Граница среза

Не входили: UI каталога/печати, UI UniDR, порты `UniDrCatalogGateway`/
`UniDrRunLauncher`, перенос `ReportParamEditor`/`ReportQueryDialog`. Это M2.2.2;
cutover приложения и runtime-проверка — M2.2.3.
