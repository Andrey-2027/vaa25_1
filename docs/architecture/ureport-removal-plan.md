# U1: вывод UReport из приложения — план и инвентарь

- Дата: 2026-10-17.
- Статус: **U1.1 выполнен** (2026-10-17). Сборка UReport-строки перенесена в
  UReport-слой, `org.ipro.report.catalog` больше не импортирует `org.ipro.ureport`.
  Удаление runtime, зависимостей и данных (U1.2–U1.4) не начато; D2/D5 не
  подтверждены.
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
печати; зарезервированный первый сегмент `ureport` в deep-link baseline.

## 2. Решения и блокеры

| ID | Решение | Состояние на 2026-10-17 | Что означает для U1 |
|---|---|---|---|
| D1 | Целевой состав отчётов — JRXML + UniDR, UReport выводится | Направление задано владельцем для подготовки U1 | Определяет необходимость плана; не отменяет D2/D5 |
| D2 | Отказ от браузерного дизайнера UReport; сложные макеты — в Jaspersoft Studio | **Требуется подтверждение владельца** | Если дизайнер нужен, U1 не начинается: удаление делает сценарий недоступным |
| D5 | Судьба каждого существующего шаблона: замена UniDR/JRXML, архив или «не нужен» | **Не собран реестр** | Без построчного реестра нельзя удалять таблицу/файлы; U1 может удалить только runtime |
| D9 | Старый URL `/ureport/*` | Предлагается: не перенаправлять в каталог, оставить сегмент зарезервированным | Старые ссылки не должны попадать в живой дизайнер и не должны стать alias'ом сущности |

Если D2 подтверждается, решение о целевом составе стоит оформить ADR (следующий
свободный номер): U1 меняет продуктовую возможность, а не только код. До этого
документ остаётся планом подготовки.

D5 выполняется запросом к каждому используемому окружению, а не по локальным
файлам:

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
| `src/test/resources/routes/deep-link-baseline.txt:68` | `reserved ureport` | **Оставить**: сегмент остаётся занятым по ADR-0009 |

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

### U1.3. Убрать зависимость и supply chain

1. Удалить property и dependency из `pom.xml`.
2. Удалить запись `ureport3` из `scripts/local-dependencies.json`.
3. Обновить build-документ, `application-local.example.properties`, устаревшие
   пути в `run-unidr-u4.ps1`, комментарий `BootstrapScriptContractTest`.
4. Обновить ожидания `PlatformStarterCompositionTest` и добавить проверку
   отсутствия `com.bstek.ureport` в прямых и разрешённых зависимостях.
5. Проверить `mvn dependency:tree`: `com.bstek.ureport` отсутствует; отдельно
   проверить, не остался ли iText из другого источника (не предполагать).

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
- Запуск с `accounting-pilot` (validate) и существующей таблицей
  `ureport_template` — успешен.
- SQL до/после: `select count(*) from ureport_template` и число файлов не
  изменились.

## 6. Риски и ограничения

1. **Потеря авторского сценария.** Без D2 удаление лишает пользователей
   браузерного дизайнера; макеты придётся вести в Jaspersoft Studio.
2. **Шаблоны без замены.** D5 может показать формы, у которых нет аналога;
   runtime можно удалить, данные — нельзя, пока реестр не закрыт.
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

- [ ] D2/D5 подтверждены; реестр шаблонов заполнен по окружениям.
- [ ] В `src/main` нет `org.ipro.ureport`, `UReportConfig`, `UReportJpqlDataset`,
      `UreportParamsDialog`, `runAsMaps`.
- [ ] Каталог не содержит `UREPORT3`/`DESIGNER`/`designerUrl`; фабрика не знает
      движков, кроме UDR/JR/UniDR.
- [ ] Нет регистраций UReport, `/ureport/**` в security, настроек `ureport.*`.
- [ ] POM, runtime-граф и bootstrap-манифест без UReport; внешний checkout цел.
- [ ] `platform-public-surface` 53 типа, матрица и замеры перегенерированы.
- [ ] Адресные проверки и `mvn clean verify` зелёные либо краснота названа и
      отделена от U1.
- [ ] Старый URL не открывает дизайнер; каталог, печать, JR, UniDR и REST
      проверены на запущенном приложении.
- [ ] Данные и файлы не удалены; DB1 получает таблицу, файлы, гранты и реестр.

## 8. Передача в DB1

DB1 начинается только после U1 и решения D5. Ему передаются:

- таблица `ureport_template` (схема, число строк, резервная копия);
- файловое хранилище XML (путь, число файлов, владелец);
- строки `access_grant`, относящиеся к UReport-типу;
- реестр «шаблон → замена/архив/не нужен» с результатами проверки;
- перечень оставшихся упоминаний UReport в исторических документах (не
  подлежат правке).

Удаление таблицы/файлов без этого пакета не выполняется.
