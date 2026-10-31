# R3: нейтральный шов исполнения запроса (2026-10-20)

- Дата: 2026-10-20.
- Основание: [ADR-0028](../decisions/ADR-0028-author-query-as-document.md)
  («переходный доступ — только через именованный адаптер»), остаток Q2 №2–3 в
  [evidence Q1/Q2](q1-q2-query-contract-20261019.md) §4 и §5.4
  [реестра R2](../r2-udr-scenario-inventory.md).
- Срез закрывает ту часть R3, которая не требует удаления UDR: execution seam.
  Удаление UDR целиком по-прежнему ждёт S4/S8, прод-инвентарь и решения §7.

## 1. Что стало верно

**Подпись ссылок — порт, а не сеттер backend'а.** `ReportQueryExecutor` больше не
знает `EntityPresentationService`/`RecorderTypeRegistry` и не имеет
`usePresentations`. Он принимает `QueryResultPresenter`; реализация
`org.ipro.reportstudio.query.QueryResultLabelPresenter` живёт в приложении и делает ровно то,
что раньше было в executor'е: физическая проекция → подписи + `ReferenceColumn`.

**Нейтральный transactional вход исполнения.** `ReportQueryRunService` собирает
запрос (guard + параметры + визуальный пакет) и выполняет его в
`@Transactional(readOnly = true)`, возвращая `ReportQueryRun` (assembled + dataset).
Лимиты берутся из `QueryDefinition.limits()`, а не из констант шаблона. UDR-фасад
переведён на него; `ReportPreviewService` принимает уже собранный запрос, а UniDR
сохраняет собственную границу (canonical lookups, фильтры, проверка схемы) — их
перевод на этот вход в срез не входил.

**Кэш артефактов не знает модели отчёта.** `ReportArtifactCache.key` принимает
строковую идентичность документа и `Map` bindings; `ReportTemplate` из кэша ушёл.
Фасад запуска формирует идентичность сам: `udr=<id>/<version>`.

**`run/` — нейтральная инфраструктура с одним названным исключением.**
`ReportExecutionService` остался UDR-композитором (шаблон → запрос → read-only
исполнение → компиляция макета → кэш) и единственным файлом пакета, который
знает `ReportTemplate`. Это зафиксировано shrink-only гейтом: список файлов с
отчётным DOM в `run/` должен совпадать ровно с одним файлом; новый такой файл
не добавить.

## 2. Почему layout не нейтрализован в этом срезе

Полностью убрать `ReportTemplate` из `run/` можно только вместе с нейтральной
моделью макета (bands/поля/страница) и обратным отображением в неё. Это
дублирование модели, которая исчезает вместе с UDR в R2/S4, и оно не даёт
продуктового результата: JRXML и UniDR макет не используют. Поэтому шов проведён
там, где он реально нужен — между запросом и печатью; макет остаётся UDR-доменом
до его удаления. Позиция зафиксирована гейтом, а не комментарием.

## 3. Проверки

```text
mvn -o -B -DskipTests test-compile                          EXIT=0   (JDK 21)
mvn -o -B test -Dtest=QueryBackendBoundaryTest,
    ReportArtifactCacheTest,ReportTemplateQueryAdapterTest,
    ReportTemplateQueryAdapterOrderingTest,ReportCatalogViewTest,
    ReportEditorViewTest,NomenclaturePriceCteSpikeTest       60 тестов, 0/0
mvn -o -B test -Dtest=RegisterSliceReportSecurityTest,
    RegisterSliceCteGeneratorTest,UniDrReceivingItemsQueryTest,
    ReportExecutionIT                                       77 тестов, 0/0
Регрессия query/**, views/reportstudio/**, views/unidr/**,
    unidr/**, run/**                                        927 тестов, 0 failures / 0 errors, 18 skipped
```

- `ReportExecutionIT` подтверждает прежнее поведение запуска (guard, параметры,
  кэш, экспорт) через новый шов.
- `RegisterSliceReportSecurityTest` — подпись недоступной ссылки и C5/RLS-отказы
  после перевода на порт.
- `NomenclaturePriceCteSpikeTest` — полный Spring-контекст с новыми бинами.
- Полный `mvn verify`, PostgreSQL и браузерная приёмка не выполнялись.

## 4. Что осталось в R3/R1

- S10 закрыт 2026-10-21: экспорт Jasper, пул воркеров и типы датасета вынесены в
  `org.ipro.report.{render,task,data}`, UniDR больше не импортирует
  `reportstudio.{data,render,run,task}`
  ([evidence](r1-shared-export-and-tasks-20261021.md)). Оставшиеся импорты UniDR —
  query-контракт (`reportstudio.query`/`param`/`dom`); его физический вынос — M1.
- `ReportExecutionService`, `ReportCompiler.compile(ReportTemplate, …)`, модель
  `ReportTemplate` и переходный адаптер удаляются вместе с UDR после S4/S8 и
  прод-инвентаря.
- `ReportPreviewService` сохраняет собственный вход (assembled/raw JPQL); он не
  переводился на `ReportQueryRunService`, потому что принимает уже собранный
  запрос, а не `QueryDefinition`.
