# U1: вывод UReport из приложения — план и инвентарь

- Дата: 2026-10-17.
- Статус: **U1.1–U1.4 выполнены по коду, реестрам и документам; D2/D5/D9
  подтверждены владельцем 2026-10-18** ([ADR-0027](decisions/ADR-0027-report-composition-without-ureport.md)).
  Runtime UReport удалён (production-классы, конфигурация, UI-ветки, адаптер каталога,
  регистрация), Maven-зависимость и supply chain вычищены, iText 5 ушёл вместе с форком.
  Нейтральный каталог собирает только UDR/JR/UniDR. Приёмка на существующей БД и
  выполнение DB1 (данные, файлы, гранты) остаются. Полный `mvn clean verify` (2335
  тестов, 39F/15E) красный только на чужом коде: незакоммиченный срез
  platform-vaadin/explorer и давний долг C5/RLS; разбор — в
  `evidence/u1-ureport-removal-20261018.md`.
- Основание: решение владельца готовить удаление UReport; этапы C1/U1 локального
  плана `docs/plans/query-report-separation-plan.md` (вне Git, не является
  нормативным источником).
- Связанные документы: [`d-r-report-addon-inventory.md`](d-r-report-addon-inventory.md),
  [`ADR-0007`](decisions/ADR-0007-canonical-data-access-path.md),
  [`ADR-0009`](decisions/ADR-0009-deep-links.md).

## 1. Результат и границы

Цель U1 — в рабочем приложении не остаётся UReport:

- нет production-классов `org.ipro.ureport.*`, `UReportConfig`, `UReportJpqlDataset`,
  `UreportParamsDialog` и их бинов;
- нет `com.bstek.ureport` в POM, разрешённом runtime-графе и bootstrap-манифесте;
- нет servlet/XML-контекста, `/ureport/*`, настроек `ureport.*` и записей в
  `AutoConfiguration.imports`;
- каталог отчётов, печать из списка/карточки, JR, UniDR, UDR и REST-превью JPQL
  работают без UReport;
- старый адрес `/ureport/...` не открывает живой дизайнер;
- таблица `ureport_template`, XML-файлы и гранты не удаляются в U1 — их судьба
  относится к DB1 (раздел 8).

Не входит в U1:

- удаление UDR и перенос его сценариев (этап R2);
- нейтральные query-контракты и отвязка редактора/UniDR от `ReportTemplate`
  (этапы Q1–Q3);
- удаление внешнего checkout `../UReportFork2/ureport3`;
- удаление данных, файлов шаблонов и исторических evidence-документов;
- переписывание исторических документов (`UnionReport1.md`, отчёты этапов).

Инварианты, которые U1 не меняет: ключ каталога — пара `(type, id)`; гейт чтения
каталога; `fileMissing`; RLS и guard на пути JPQL; действия JR/UniDR в каталоге и
печати. Первый сегмент `ureport` больше не зарезервирован: D9 освободил его
(2026-10-18), строка `reserved ureport` снята из deep-link baseline.

## 2. Решения и блокеры

| ID | Решение | Состояние | Что означает для U1 |
|---|---|---|---|
| D1 | Целевой состав отчётов — JRXML + UniDR, UReport выводится | Направление задано владельцем для подготовки U1 | Определяет необходимость плана; не отменяет D2/D5 |
| D2 | Отказ от браузерного дизайнера UReport; сложные макеты — в Jaspersoft Studio | **Подтверждено владельцем 2026-10-18** ([ADR-0027](decisions/ADR-0027-report-composition-without-ureport.md)) | Дизайнер не восстанавливается; печатные макеты — JRXML и UniDR |
| D5 | Судьба каждого существующего шаблона: замена UniDR/JRXML, архив или «не нужен» | **Подтверждено владельцем 2026-10-18: удалить все шаблоны** ([ADR-0027](decisions/ADR-0027-report-composition-without-ureport.md)) | Замена на JR/UniDR не готовится; реестр D5 становится аудиторским инвентарём, копии и проверка восстановления обязательны |
| D9 | Старый URL `/ureport/*` | **Подтверждено владельцем 2026-10-18**: адрес не нужен, не обслуживается и не перенаправляется; сегмент `ureport` освобождён | Старые ссылки не попадают в дизайнер; сегмент может занять alias сущности по общим правилам ADR-0009 |

Решение о целевом составе оформлено: [ADR-0027](decisions/ADR-0027-report-composition-without-ureport.md)
(2026-10-18). U1 менял продуктовую возможность, а не только код, поэтому решения
зафиксированы отдельно от реализации.

D5 подтверждён как «удалить все», но инвентарь всё равно снимается запросом к
каждому используемому окружению, а не по локальным файлам: без него нечего
сверять с файлами и прикладывать к evidence.

```sql
select id, name, file_name, enabled, target_entity_class
from ureport_template
order by id;
```

и фактическим листингом хранилища (`UREPORT_FILESTORE_DIR`, по умолчанию
`ureport-files`; локальная конфигурация может указывать другой путь). Если БД
недоступна, это фиксируется как неизвестное; пустой локальный каталог не заменяет
замер.

## 3. Инвентарь точек касания

### 3.1. Движок и его app-owned обвязка — удалить

| Точка | Сейчас | Действие |
|---|---|---|
| `src/main/java/org/ipro/ureport/UreportTemplateRepository.java` | Репозиторий `ureport_template` | Удалить |
| `.../ureport/config/UreportAutoConfiguration.java` | Бины сервиса и адаптера каталога | Удалить |
| `.../ureport/config/UreportPersistenceAutoConfiguration.java` | `@EntityScan`/`@EnableJpaRepositories` | Удалить |
| `.../ureport/dom/UreportTemplate.java` | Сущность, таблица `ureport_template` | Удалить класс; таблицу не трогать |
| `.../ureport/params/ParamUiType.java` | Тип поля диалога параметров | Удалить |
| `.../ureport/params/UreportParamSpec.java` | Описание параметра из XML | Удалить |
| `.../ureport/service/UreportCatalogContributor.java` | Адаптер каталога: гейт, флаг, `fileMissing`, URL дизайнера | Удалить после U1.1 |
| `.../ureport/service/UreportTemplateService.java` | CRUD метаданных, XML-хранилище, `ReportParser`, `designerUrl` | Удалить |
| `src/main/java/org/ip/config/UReportConfig.java` | `UReportServlet` на `/ureport/*`, XML-контекст, datasource, инициализация каталога | Удалить |
| `src/main/java/org/ip/config/UReportJpqlDataset.java` | `JpqlDatasetExecutor` для bean/SQL-датасетов UReport | Удалить |
| `src/main/java/org/ip/views/reportstudio/UreportParamsDialog.java` | Диалог параметров и эндпоинты `/ureport/preview`, `/ureport/pdf`, `/ureport/excel` | Удалить |

Итого 11 production-файлов (863 строки) и 2 тестовых файла (216 строк). Внешний
checkout `../UReportFork2/ureport3` не удаляется.

### 3.2. Нейтральный каталог — сузить контракт

| Точка | Сейчас | Действие |
|---|---|---|
| `org/ipro/report/catalog/ReportCatalogItemFactory.java` | Собирает строки UDR/UReport3/JR/UniDR; UReport-перегрузка импортирует `ureport.dom` и `UreportTemplateService.designerUrl` | U1.1: перенести сборку UReport-строки в UReport-слой (`UreportTemplateService.rowOf`); адаптер, печать и UI используют этот узел. После удаления движка фабрика не знает `ureport` |
| `ReportEngineType.java` | `UDR, UREPORT3, JR, UNIDR` | Удалить `UREPORT3`; порядок объявления `UDR, JR, UNIDR` сохраняет разрешение одинаковых имён |
| `ReportEngineCapabilities.java` | Строка `UREPORT3`, цель `DESIGNER` | Удалить строку и `DESIGNER`; остаются `EDITOR`, `INFO`, `UNIDR_EDITOR` |
| `ReportCatalogItem.java` | Поле `designerUrl` и javadoc про `ureport_template` | Удалить поле: после U1 внешнего дизайнера нет; обновить javadoc |
| `ReportCatalogService.java`, `ReportCatalogReadGate.java` | Javadoc ссылается на UReport3 | Обновить формулировки, логику не менять |

Сейчас `ReportCatalogItem` конструируется только фабрикой
(`ReportCatalogAssemblyGateTest`). После удаления поля `designerUrl` правятся
вызовы конструктора в фабрике, `ReportCatalogServiceTest`, `ReportCatalogViewTest`
и `ContextualReportLauncherTest`, а также проверки `designerUrl` в
`UdrCatalogContributorTest` и `JrCatalogContributorTest`.

### 3.3. UI создания, запуска, печати и удаления

| Точка | Что привязано к UReport | Действие |
|---|---|---|
| `ReportCatalogView.java` | Импорты 43–45; поле 64; параметр конструктора 82; `UreportTemplateServiceBridge` 99, 503–515; пункт «UReport3 — веб-дизайнер» 202–211; диалог создания 249–271; ветка запуска 287, 314–332; `openDesigner` 365–366; удаление 452, 475–486; текст 171 | Убрать импорты, параметр, мост, пункт меню, диалог, ветки `UREPORT3`/`DESIGNER`, методы; текст оставить про UDR/JR/UniDR |
| `ReportPrintDialog.java` | Импорты 25–30; поле 65; параметр конструктора 72–91; текст 143–145; пункт меню 170–177; `mergedItems` 230–232; ветка запуска 255–262; `createUreport` 304–330; `fileNameOf` 332+ | Убрать UReport-параметр/поле/ветки; JR и UniDR сохранить |
| `ReportPrintAction.java` | Поле 66, параметр 76, передача в диалог 122 | Убрать параметр; сигнатура конструктора меняется |
| `ContextualReportLauncher.java` | Поле 39; перегрузки 63, 79, 121 и максимальная 140–157; передача в диалог 158–160 | Убрать UReport-параметр и перегрузки только с ним; JR-перегрузки сохранить |
| `ItemFormReportActions.java` | Перегрузки 45 и 59 | Убрать UReport-параметр; оставить базовую и JR-перегрузку |
| `ReportCatalogViewTest.java` | Мок сервиса, фикстуры `ureportItem`, тесты открытия/удаления UReport, конструктор 537–543 | Удалить UReport-тесты и фикстуры; конструктор обновить |
| `ReportPrintActionTest.java`, `EntitySummaryActionPilotTest.java` | Передают мок `UreportTemplateService` в конструктор | Убрать аргумент |

`ContextualReportLauncherTest` UReport-конструктор не использует — правок не
требует, но его `new ReportCatalogItem(..., null, false)` обновится вместе с полем.

### 3.4. Общий путь JPQL

| Точка | Сейчас | Действие |
|---|---|---|
| `org/ip/config/JpqlRunService.java:86–99` | `runAsMaps` — UReport-семантика: точки в алиасах → `_`, `EntityRef` → строка | Удалить `runAsMaps` вместе с единственным потребителем `UReportJpqlDataset` |
| `JpqlRunService.java:104` | `displayValue` | **Оставить**: используется `ReportJpqlPreviewController:105` (REST/Jaspersoft Studio); JR имеет собственный аналог в `JpqlDatasetRunner` |
| `JpqlRunService.java:18–37` | Javadoc перечисляет UReport как потребителя | Обновить: UDR, JR/JSS, REST |
| `ReportQueryGuard.java:96` | Комментарий «UDR/UReport» | Обновить формулировку |
| `JpqlRunServiceTest.java:143–162` | Два теста `runAsMaps` | Удалить; тест лимита колонок 125 переименовать в стандартный лимит UDR |
| `JrxmlTemplate.java`, `JrxmlTemplateService.java`, `JrxmlExecutionService.java` | Javadoc ссылается на UreportTemplate/UReport | Обновить комментарии, код не менять |
| `JrAutoConfiguration.java:23`, `JrCatalogContributor.java:16`, `ReportCatalogAutoConfiguration.java:22–23` | Комментарии сравнивают JR-ветку/каталог с UReport3 | Переписать без UReport, сохранив смысл правила |

### 3.5. Регистрация, security, настройки

| Точка | Сейчас | Действие |
|---|---|---|
| `src/main/resources/META-INF/spring/...AutoConfiguration.imports:4–5` | `UreportAutoConfiguration`, `UreportPersistenceAutoConfiguration` | Удалить обе строки |
| `org/ip/config/SecurityConfig.java:55–57` | `/ureport/**` требует аутентификации | Удалить matcher |
| `SecurityConfig.java:62–65` | `/ureport/**` исключён из CSRF | Удалить исключение |
| `src/main/resources/application.properties:71–77` | Блок `ureport.*` (4 свойства) | Удалить блок |
| `application.properties:80` | Комментарий JR ссылается на `ureport.fileStoreDir` | Переписать без UReport |
| `config/application-local.example.properties:14` | `ureport.fileStoreDir` | Удалить строку; локальный `application-local.properties` — по желанию, на запуск не влияет |
| `org/ip/config/EntityClassificationConfig.java:37`, `org/ip/Application.java:38–39` | Комментарии | Обновить |

### 3.6. Сборка, манифест, supply chain

| Точка | Сейчас | Действие |
|---|---|---|
| `pom.xml:26` | `<ureport.version>3.0.1</ureport.version>` | Удалить property |
| `pom.xml:141–158` | `com.bstek.ureport:ureport3-console` + exclusion antlr | Удалить зависимость целиком |
| `scripts/local-dependencies.json:698–749` | Проект `ureport3`, fingerprint, 4 артефакта, 4 buildSteps, licenseGate | Удалить запись; внешний checkout не трогать |
| `docs/architecture/build/local-dependency-workspace.md` | UReportFork2 в графе, таблице и тексте | Удалить строки/упоминания; пересчитать граф |
| `scripts/run-unidr-u4.ps1:56–57,79–81` | Ссылается на уже несуществующие `org/ipro/ureport/catalog/*` | Удалить устаревшие аргументы/файлы из списка |
| `BootstrapScriptContractTest.java:31–34` | Комментарий про UReport3 | Обновить на «внешние форки» без перечисления |
| `PlatformStarterCompositionTest.java:117` | Ожидает `com.bstek.ureport:ureport3-console` в прямых зависимостях приложения | Удалить из ожиданий и добавить явную проверку отсутствия; `isReportCoordinate` можно оставить tombstone-проверкой |
| `platform-vaadin/pom.xml:28,88`, `platform-rls/pom.xml:60`, `platform-register-vaadin/pom.xml:79` | Комментарии-запреты «reportstudio/jr/ureport» в POM модулей | **Оставить**: это запрет для будущих потребителей, а не зависимость |
| `outputs/**`, `docs/plans/e322-11-evidence/**`, `.local-maven-repository/**` | Исторические копии, тестовые отчёты и Maven-артефакты | Не править; grep по ним не считается остатком runtime |

### 3.7. Тесты: удалить, переписать, заменить фикстуру

Удалить целиком:

- `src/test/java/org/ipro/ureport/UreportTemplateServiceTest.java`;
- `src/test/java/org/ipro/ureport/UreportCatalogContributorTest.java`.

Переписать:

- `src/test/java/org/ipro/report/catalog/ReportCatalogItemFactoryTest.java` —
  сейчас объявляет `package org.ipro.ureport` при пути `report/catalog`. После
  удаления UReport-кейсов пакет обязан стать `org.ipro.report.catalog`, иначе в
  тестовом дереве остаётся пустой `ureport`-пакет;
- `ReportCatalogServiceTest.java` — `UREPORT3` в 3 тестах заменить на `JR`
  (включая коллизию `(1, UDR)` против `(1, JR)` и порядок одинаковых имён);
- `ReportEngineCapabilitiesTest.java` — убрать `UREPORT3`/`DESIGNER`, проверить
  3 движка и 3 различные цели;
- `ReportCatalogViewTest.java` — убрать UReport-фикстуры/тесты, обновить
  конструктор и проверки удаления; счётчик действий без выделения остаётся 5;
- `ReportPrintActionTest.java`, `EntitySummaryActionPilotTest.java` — убрать
  аргумент-мок;
- `JpqlRunServiceTest.java` — удалить `runAsMaps*`;
- `PersistenceRegistrationIT.java` — убрать репозиторий из карты;
- `PersistenceTypeRegistrationTest.java` — убрать `org.ipro.ureport.dom` из
  `@EntityScan` и `org.ipro.ureport` из `@EnableJpaRepositories`;
- `CompatibilityMigrationArchitectureTest.java` — убрать
  `UreportTemplateService` из двух reviewed-списков; списки проверяются в обе
  стороны, поэтому запись обязана исчезнуть;
- `PlatformStarterCompositionTest.java` — см. 3.6;
- `RestReadHttpSecurityIT.java:116–120` — тест «чужой канал не стал REST»
  ожидает redirect на login для `/ureport/index.html`; после удаления проверять,
  что designer не жив (не 200 и не HTML дизайнера), а не конкретный redirect;
- `CanonicalReadBoundaryIT.java`, `CanonicalWriteBoundaryIT.java`,
  `FetchPlanScenarioFactsIT.java` — `UreportTemplate` использовался как
  представитель `INTERNAL_STORE`. Заменить на `ReportTemplate` (тот же
  `ValidatedJpaCrudService`, без canonical handle); выбор подтвердить прогоном.

Проверки-«надгробия» (строки `org.ipro.ureport..`, `com.ureport` в
`PlatformArchitectureTest`, `PlatformDependencyDirectionTest`, `PlatformRlsModuleTest`,
`VaadinModuleCompositionTest`, `RegisterVaadinModuleCompositionTest`) **оставить**:
это blacklist против повторного появления пакета, а не описание текущего кода.
Исключение — тест, который ожидает UReport как реальную зависимость приложения.

### 3.8. Реестры и замеры

| Артефакт | Сейчас | Действие |
|---|---|---|
| `src/test/resources/platform-public-surface.txt:344–347` | 4 типа `legacy-internal` (11 ссылок из 6 UI-файлов) | Удалить 4 строки; типов `legacy-internal` станет 57 → 53 |
| `PlatformPublicSurfaceTest.java:80` | `LEGACY_INTERNAL_BUDGET = 60` | Уменьшить до 53 (бюджет только сокращается) |
| `src/test/resources/platform-package-matrix.txt:219–220,347–358` | 14 рёбер с `org.ipro.ureport` | Перегенерировать; 2 ребра `report.catalog` и 12 рёбер движка исчезнут |
| `docs/architecture/d1-surface-measurements.md` | Замеры поверхности | Перегенерировать тем же тестом |
| `src/test/resources/report-form-seam.txt:91–92` | Комментарий перечисляет 6 app-конфигураций, включая две UReport | Обновить перечень (останется 4) |
| `src/test/resources/routes/deep-link-baseline.txt` | `reserved ureport` | **Снять** (D9, 2026-10-18): адрес не обслуживается, сегмент освобождён; `DeepLinkRouteBaselineTest` остаётся зелёным |

### 3.9. Данные, файлы и права

| Объект | Факт | Действие U1 |
|---|---|---|
| Таблица `ureport_template` | Создаётся Hibernate (`ddl-auto=update` в основном профиле); SQL-миграции в репозитории нет | Не удалять; после удаления сущности остаётся orphan-таблицей |
| XML-шаблоны | `ureport.fileStoreDir`, env `UREPORT_FILESTORE_DIR`, локально может быть `C:/JavaProject/TestVaadin25/UReportFiles` | Не удалять; сначала реестр D5 |
| `access_grant` | Гранты C5/RLS хранятся в БД; измерения типа могли сохраниться | Инвентаризировать (`dimension ilike '%UREPORT%'`); не удалять в U1, не трогать `REPORTS:JPQL_PREVIEW` |
| Схема `ddl-auto=validate` (accounting-pilot) | Лишняя таблица не ломает validate | Проверить запуск после удаления сущности |

U1 не делает `drop table`, не чистит файловое хранилище и не удаляет гранты: это
DB1 с резервной копией и построчным решением D5.

### 3.10. Документация

- [`d-r-report-addon-inventory.md`](d-r-report-addon-inventory.md): строка UReport
  перестаёт быть «кандидатом в add-on» и ссылается на этот план; строка фабрики
  отмечает U1.1.
- [`README.md`](README.md): добавить этот план в таблицу источников.
- `docs/architecture/status/current-baseline.md`: после выполнения U1 — факт
  удаления, номера прогонов и названные чужие красные гейты. До выполнения
  статус не менять.
- `JMIX_GitVaa_Roadmap_v2.md`: после U1 убрать UReport из перечня отчётных
  интеграций; до этого roadmap не переписывать.
- `platform-rls/README.md:22`: после U1 остаётся только `reportstudio` как
  подсистема, регистрирующая свои пакеты; UReport из фразы убрать.
- `ReportFormSeamTest` и `report-form-seam.txt`: комментарии про шесть
  app-конфигураций обновить до четырёх, не меняя предмет проверки.
- Исторические `docs/architecture/evidence/**`, `UnionReport1.md`,
  `ReportDREditor.md` не редактируются: они фиксируют прошлое состояние.

## 4. Порядок работ

### U1.0. Baseline и реестр D5 (без изменений кода)

1. Зафиксировать `git status`, ветку и чужие незакоммиченные изменения; не
   смешивать их с U1.
2. Снять baseline адресных проверок: каталог, печать, JR, UniDR, JPQL-превью,
   `PlatformStarterCompositionTest`, `PlatformPublicSurfaceTest`,
   `PlatformPackageDependencyMatrixTest`, `ReportStudioAutoConfigurationSmokeTest`.
   Красные гейты чужой работы перечислить отдельно.
3. Собрать `ureport_template` и листинг файлов по каждому окружению; заполнить
   реестр «шаблон → используется → замена → проверка → состояние».
4. Подтвердить D2/D5 и, если D2 принят, оформить ADR о целевом составе.

Выход: baseline, реестр D5, подтверждённые решения. Без этого U1.1 не начинается.

### U1.1. Отделить сборку строки UReport от нейтрального каталога

Движок ещё работает, поведение не меняется.

1. Перенести сборку `UreportTemplate` → `ReportCatalogItem` (включая
   `designerUrl`, `enabled`, `fileMissing`) из `ReportCatalogItemFactory` в
   UReport-слой: единый узел `UreportTemplateService.rowOf`, которым пользуются
   адаптер каталога, печать из реестра и создание отчёта в UI.
2. Удалить UReport-перегрузку фабрики и её импорты; фабрика собирает только
   UDR/JR/UniDR.
3. Перенести UReport-кейсы из `ReportCatalogItemFactoryTest` в
   `UreportCatalogContributorTest`; пакет теста фабрики исправить на
   `org.ipro.report.catalog`.
4. Перегенерировать матрицу пакетов: должны исчезнуть
   `org.ipro.report.catalog -> org.ipro.ureport.dom` и `-> org.ipro.ureport.service`.

Выход: в `org.ipro.report.catalog` нет импортов `org.ipro.ureport`; каталог и
UReport-строки работают как раньше.

Состояние на 2026-10-17: выполнено. Единый узел — `UreportTemplateService.rowOf`;
им пользуются `UreportCatalogContributor`, печать из реестра и создание отчёта в
UI. Из `ReportCatalogItemFactory` удалена UReport-перегрузка, её тест перенесён в
`UreportCatalogContributorTest`, матрица перегенерирована: рёбер
`org.ipro.report.catalog -> org.ipro.ureport.*` больше нет.

### U1.2. Удалить runtime UReport (один compile-green срез)

1. Удалить 11 production-файлов и 2 теста из 3.1.
2. Убрать UReport-ветки и параметры в UI (3.3), сузить контракт каталога (3.2).
3. Удалить `runAsMaps` и его тесты, сохранить `displayValue` (3.4).
4. Удалить записи `AutoConfiguration.imports`, блок `ureport.*`, matcher/CSRF
   `/ureport/**` (3.5).
5. Переписать тесты, использующие UreportTemplate как фикстуру (3.7).
6. Обновить комментарии в `Application`, `EntityClassificationConfig`,
   `JrxmlTemplate*`, `ReportQueryGuard`.

Выход: `test-compile` зелёный; контекст приложения поднимается; каталог и печать
работают; в production-коде нет имён UReport, кроме надгробий и комментариев,
названных в 3.7. Maven-зависимость на этом шаге ещё может оставаться — она не
используется.

Состояние на 2026-10-17: выполнено. Удалены `org.ipro.ureport.**`, `UReportConfig`,
`UReportJpqlDataset`, `UreportParamsDialog` и их тесты; из `AutoConfiguration.imports`
сняты обе UReport-регистрации, из SecurityConfig — matcher `/ureport/**`, из
`ReportEngineType`/capabilities/UI — ветка `UREPORT3`. Нейтральная
`ReportCatalogAutoConfiguration` осталась единственной точкой сборки каталога.
Тестовый контекст JR потребовал явного `jrxml.fileStoreDir` в заменяющем
`application.properties` — ключ добавлен; забор замыкания platform-subsystem
записал новые рёбра `reportstudio → org.ipro.report.catalog` и `→ org.ipro.jr.dom`.
Проверены адресные гейты каталога, печати, JR/UniDR-адаптеров, архитектуры и
smoke-контекст; полный `mvn verify` относится к U1.4.

### U1.3. Убрать зависимость и supply chain

1. Удалить property и dependency из `pom.xml`.
2. Удалить запись `ureport3` из `scripts/local-dependencies.json`.
3. Обновить build-документ, `application-local.example.properties`, устаревшие
   пути в `run-unidr-u4.ps1`, комментарий `BootstrapScriptContractTest`.
4. Обновить ожидания `PlatformStarterCompositionTest` и добавить проверку
   отсутствия `com.bstek.ureport` в прямых и разрешённых зависимостях.
5. Проверить `mvn dependency:tree`: `com.bstek.ureport` отсутствует; отдельно
   проверить, не остался ли iText из другого источника (не предполагать).

Состояние на 2026-10-18: выполнено. Из `pom.xml` удалены property `ureport.version`,
сама зависимость и `antlr`/`itext`-исключения; из `scripts/local-dependencies.json` — запись
`ureport3` и её fingerprint; из build-документа и `run-unidr-u4.ps1` — пути к форку;
`BootstrapScriptContractTest` больше не ищет `../UReportFork2`, а `PlatformStarterCompositionTest`
запрещает возврат координаты в POM и в разрешённый граф. `mvn dependency:tree` — 297 строк,
`com.bstek.ureport` и `itext` отсутствуют (iText не пришёл из другого источника);
`WorkspaceManifestTest` и bootstrap-скрипт зелёные. Внешний checkout не тронут.

Выход: приложение собирается без форка; runtime-граф и bootstrap не содержат
UReport; внешний checkout не изменён.

### U1.4. Гейты, реестры, документация, приёмка

1. Удалить 4 строки `platform-public-surface.txt`, бюджет 60 → 53.
2. Перегенерировать `platform-package-matrix.txt` и
   `d1-surface-measurements.md`.
3. Обновить `report-form-seam.txt`, `d-r-report-addon-inventory.md`, `README.md`,
   `current-baseline.md`, roadmap.
4. Прогнать полный `mvn clean verify` (clean обязателен: stale `target/classes`
   уже один раз маскировал уехавший артефакт — evidence S5b).
5. Проверить старый URL, каталог, печать, JR, UniDR, REST-превью и профиль
   `accounting-pilot` на существующей БД.
6. Передать DB1 таблицу, файлы и гранты; зафиксировать evidence.

Состояние на 2026-10-18: пункты 1–3 выполнены, 4 выполнен с известной чужой
краснотой, 5–6 остаются.

- Реестр поверхности: строки UReport сняты вместе с runtime (U1.2); бюджет
  legacy-internal после U1 — **52**, ссылок — **180** (рост к 137 записан как долг
  среза UniDR, см. evidence). Числа в `d1-surface-measurements.md` получены тем же
  измерением; финальные `platform-artifact-files` и `usage-links` сверены с кодом
  вручную, потому что тестовое дерево временно не компилируется из-за чужого
  незавершённого теста. `platform-package-matrix.txt` пересборки не потребовал
  (гейт зелёный на текущем графе).
- Документы: `report-form-seam.txt` (7 строк UReport заменены нейтральными),
  `d-r-report-addon-inventory.md`, `README.md`, `current-baseline.md` и roadmap
  описывают состояние после U1.3.
- Полный `mvn clean verify` — **2335 тестов, 39 failures, 15 errors** в 24 наборах.
  Ни один красный набор не относится к U1: адресные гейты каталога, печати, JR,
  UniDR, report-form seam, starter composition и bootstrap-контракта зелёные.
  Красное принадлежит двум чужим потокам: (а) незакоммиченный срез
  platform-vaadin/explorer в момент прогона (новый `EntityStructureFactsAssembler`:
  138 → 139 типов, fingerprint манифеста, API baseline без `mvn install`);
  (б) уже существовавший долг C5/RLS (`ApplicationBoundaryTest` — 37 ссылок на
  `EntityManager` в `org.ip.application.*`, `RlsDeniedEventTest` — ожидает
  `RlsAccessDeniedException`, получает `C5AccessDeniedException`, и RLS-IT).
  Замороженные списки `legacy-internal` приведены к факту отдельной пометкой
  «долг UniDR, не повышение роли»; после завершения чужого среза verify надо
  повторить. Повторная проверка после сверки реестров (те же гейт-классы, запущены
  напрямую: полный `test-compile` блокирует чужой незавершённый тест) даёт
  зелёные `PlatformPublicSurfaceTest`, `PlatformCoreSurfaceTest`,
  `PlatformVaadinSurfaceTest` и все адресные U1-гейты; красным остаётся только
  `WorkspaceManifestTest` — дрейф fingerprint `platform-core` от чужой правки.
  Полный разбор — в evidence.
- Пункты 5–6 (старый URL, каталог и печать на существующей БД, профиль
  `accounting-pilot`, передача таблицы/файлов/грантов в DB1) не выполнялись: нет
  тестовой БД. Решения D2/D5/D9 подтверждены 2026-10-18 и DB1 этим разблокирован,
  но само выполнение DB1 требует доступа к окружению.

Выход: Definition of Done раздела 7.

## 5. Проверки

### 5.1. Адресные

```text
mvn -DskipTests test-compile
mvn -Dtest=ReportCatalogServiceTest,ReportCatalogItemFactoryTest,\
ReportEngineCapabilitiesTest,ReportCatalogViewTest,ReportPrintActionTest,\
JpqlRunServiceTest,ReportStudioAutoConfigurationSmokeTest test
mvn -Dtest=PlatformPackageDependencyMatrixTest,PlatformPublicSurfaceTest,\
PlatformAutoConfigurationRegistryTest,PlatformStarterCompositionTest test
mvn -Dtest=PersistenceTypeRegistrationTest,PersistenceRegistrationIT,\
CompatibilityMigrationArchitectureTest,CanonicalReadBoundaryIT,\
CanonicalWriteBoundaryIT,FetchPlanScenarioFactsIT,RestReadHttpSecurityIT test
```

Ожидание: нет ссылок на удалённые классы; каталог/печать не потеряли JR/UniDR;
полный контекст приложения собирается без UReport-бинов.

### 5.2. Полный прогон

```text
mvn clean verify
```

Если в дереве есть чужие незакоммиченные изменения, сначала записать их
известную красноту и не приписывать её U1. Windows-флак `@TempDir`
(`AccessDeniedException` в JR/UReport-тестах) после удаления UReport-теста
исчезает; оставшиеся JR-случаи фиксировать как инфраструктурные, а не как
успех.

### 5.3. Реестры

```text
mvn -Dtest=PlatformPackageDependencyMatrixTest -Dplatform.matrix.write=true test
mvn -Dtest=PlatformPublicSurfaceTest -Dplatform.measurements.write=true test
```

Перед записью проверить diff: генератор пишет фактическое состояние всего дерева,
включая чужие правки. В матрице не должно остаться ни одного ребра с
`org.ipro.ureport`.

### 5.4. Зависимости и bootstrap

```text
mvn dependency:tree -Dincludes=com.bstek.ureport
mvn dependency:tree -Dincludes=com.lowagie:itext
powershell -File scripts/bootstrap-local-dependencies.ps1 -ValidateOnly
mvn -Dtest=WorkspaceManifestTest,BootstrapScriptContractTest,PlatformApiBaselineTest test
```

Ожидание: первая команда пуста; вторая — только если iText остаётся у другого
потребителя (это надо назвать, а не замолчать); manifest валиден без `ureport3`;
топология и fingerprint'ы манифеста сходятся после удаления проекта.

### 5.5. Runtime и данные

- `GET /ureport/index.html` (и `/ureport/designer`) не отдаёт дизайнер: нет 200 с
  HTML/JS UReport; конкретный код (302/404) фиксируется тестом и не считается
  доказательством сам по себе.
- Каталог `/report-catalog`: создание UDR/JR/UniDR, открытие, запуск, удаление
  JR; UReport-пункта нет.
- Печать из списка и карточки: UDR/JR/UniDR; UReport-строк нет.
- `POST /api/report-jpql/preview` работает с `REPORTS:JPQL_PREVIEW`.
- Запуск с `accounting-pilot` (validate) при **отсутствующей** `ureport_template`
  (состояние после DB1) — успешен.
- После DB1: `to_regclass('public.ureport_template')` → null во всех БД, старый
  каталог файлов отсутствует, архив доступен; каталог/печать работают на данных,
  которые UReport-шаблонами не были.

## 6. Риски и ограничения

1. **Потеря авторского сценария.** Принято решением D2: браузерного дизайнера
   больше нет, макеты ведутся в Jaspersoft Studio.
2. **Шаблоны без замены.** Принято решением D5: удаляются все, замена не
   готовится. Остаточный риск — формы, у которых после DB1 не будет печати;
   их печать делается новым JR/UniDR-отчётом, а не восстановлением UReport.
3. **Orphan-таблица и файлы.** `ureport_template` и XML останутся; это осознанное
   состояние до DB1, а не забывчивость.
4. **Stale build output.** `target/classes` может сохранить удалённые классы и
   импорт-файл; проверять только после `clean`.
5. **Совместное дерево.** Генераторы матрицы/замеров и полный `verify` захватят
   чужие незакоммиченные изменения; diff реестров review'ить, чужие строки не
   присваивать U1.
6. **Гранты и права.** Старые строки `access_grant` могут ссылаться на
   удалённый тип; чистить только по инвентарю и не вместе с `REPORTS:*`.
7. **Надгробия в гейтах.** Строки `org.ipro.ureport..` остаются в blacklist
   намеренно; grep «ureport» не должен пониматься как «удаление не завершено».
8. **Внешний форк.** `../UReportFork2/ureport3` с dirty worktree и iText-лицензией
   остаётся вне GitVaa; его нельзя удалять автоматически, а лицензионный gate
   после U1 перестаёт быть ограничением runtime-поставки.

## 7. Definition of Done

- [x] D2/D5/D9 подтверждены 2026-10-18 ([ADR-0027](decisions/ADR-0027-report-composition-without-ureport.md));
      реестр шаблонов как аудиторский инвентарь заполняется в DB1 по окружениям.
- [x] В `src/main` нет `org.ipro.ureport`, `UReportConfig`, `UReportJpqlDataset`,
      `UreportParamsDialog`, `runAsMaps` (grep пуст; каталога нет).
- [x] Каталог не содержит `UREPORT3`/`DESIGNER`/`designerUrl`; фабрика не знает
      движков, кроме UDR/JR/UniDR.
- [x] Нет регистраций UReport, `/ureport/**` в security, настроек `ureport.*`.
- [x] POM, runtime-граф и bootstrap-манифест без UReport; внешний checkout цел.
- [x] `platform-public-surface` 53 типа, матрица и замеры перегенерированы
      (`d1-surface-measurements.md`, гейт `PlatformPublicSurfaceTest` зелёный).
- [x] Адресные проверки зелёные; полный `mvn clean verify` — 2340 тестов,
      35 failures / 14 errors в 19 наборах, вся краснота названа и отделена от U1
      (долг C5/RLS и чужой срез explorer) — evidence §4.1.
- [x] Старый URL не открывает дизайнер (302 → `/login`), REST-превью без сессии — 401.
- [ ] Каталог и печать JR/UniDR/UDR и профиль `accounting-pilot` — интерактивно не
      проверены: нужны учётные данные, которых в окружении нет.
- [x] Данные и файлы не удалены в U1; DB1 выполнен 2026-10-18 на локальном PostgreSQL
      (инвентарь, копии с проверкой восстановления, таблица/файлы/гранты) — evidence
      в `evidence/db1-ureport-data-removal-20261018.md`.

## 8. Передача в DB1

DB1 выполнен 2026-10-18 на локальном PostgreSQL (U1 закрыт, D5 — «удалить все
шаблоны»): таблица удалена в пяти БД, UReport-грантов не было, файлы заархивированы,
конфиг очищен. Протокол — [`evidence/db1-ureport-data-removal-20261018.md`](evidence/db1-ureport-data-removal-20261018.md).
Runtime-проверки приложения остались и вынесены в §5.5. Пакет, который был передан:

- таблица `ureport_template` (схема, число строк, резервная копия);
- файловое хранилище XML (путь, число файлов, владелец);
- строки `access_grant`, относящиеся к UReport-типу;
- инвентарь «шаблон → файл → найден/не найден»: решение по каждой строке уже
  принято (удалить), поэтому таблица нужна для сверки и evidence, а не для
  выбора судьбы шаблона;
- перечень оставшихся упоминаний UReport в исторических документах (не
  подлежат правке).

Удаление таблицы/файлов без этого пакета и без проверенной резервной копии не
выполняется: «удалить все» не означает «удалить без копии».

Исполняемый runbook DB1 — [`db1-ureport-data-inventory.md`](db1-ureport-data-inventory.md):
read-only инвентаризация (таблица, файлы в обе стороны, гранты по точным
`dimension`), реестр D5, резервные копии с проверкой восстановления, порядок
вывода (гранты → таблица → файлы → настройки) и откат по шагам.
