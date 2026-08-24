# План: Единый каталог отчётов UDR + UReport3 (UnionReport1)

> Статус: утверждён к реализации. Дата: 2026-08-24.
> Итог консолидации трёх мнений (Вар1 + Мнение2 + Мнение3) и проверки по коду.

---

## 1. Цель и границы

**Цель**: единый каталог отчётов приложения с колонкой «Тип» (UDR / UReport3),
единым диалогом параметров для запуска отчётов обоих типов, и раздельными
механизмами редактирования (конструктор UDR vs веб-дизайнер UReport3).

**Принцип объединения** (ключевое решение): объединяем то, что видит
**пользователь** (список отчётов, диалог параметров, запуск), и **не** объединяем
то, что видит только код (хранение, внутренние модели движков).

**Вне границ текущего плана** (отложено в Ф3): серверный рендер UReport3 через
ExportManager, DBReportProvider (шаблоны в БД), caption-переопределения параметров.

---

## 2. Принятые архитектурные решения

| # | Решение | Обоснование |
|---|---------|-------------|
| Р1 | Хранение: **метаданные UReport3 в БД** (`UreportTemplate`), сам XML — в файловом хранилище (`FileReportProvider`, `ureport.fileStoreDir`) | Единый source of truth для описаний/enabled/прав; движку нужен файл; альтернатива «сканировать файлы» лишает метаданных и создания из каталога |
| Р2 | **Раздельные сущности** `ReportTemplate` (UDR) и `UreportTemplate` (UReport3); объединение — read-модель DTO в сервисе каталога | Не сливать разные по природе модели в одну таблицу (паттерн NumberingRule/SettingValue); nullable-колонки «чужих» движков — антипаттерн; масштаб каталога (десятки–сотни) позволяет merge в памяти |
| Р3 | **Прямой SQL на «Рабочей базе» — НЕ блокируем** (решение от 2026-08-24, отложено). RLS-контекст: `jpql:`-датасеты защищены (Hibernate-фильтры через `RlsFilterActivator`); сырой SQL через JDBC фильтры обходит — риск принимается (отчётчики доверенные). Потенциальное ужесточение (запрет без маркера `jpql:` на уровне форка) — в Ф3/бэклоге, «может пригодиться» как escape-hatch | Гибкость для авторов шаблонов; осознанный принятый риск, задокументирован |
| Р4 | **Результат отчёта — в новой вкладке браузера**, не iframe в модалке | Проще (печать/скролл/размеры), одинаковое поведение после «Выполнить» для обоих типов |
| Р5 | **`<search-form>` в XML UReport игнорируется**; диалог строится только из `Parameter` датасетов | Избегаем дубля форм; зафиксировать как соглашение для авторов шаблонов (документ + комментарий в каталоге) |
| Р6 | **Caption параметров UReport3** — на Ф3 через таблицу-override `ureport_template_param` (нет строки → показываем name) | Паттерн Settings; не заставляем автора шаблона заводить подписи, чтобы диалог открылся |
| Р7 | **Права `REPORTS:*`** (CHECK_ONLY-паттерн, как в settings) — в Ф1, не в Ф3 | Инфраструктура готова; «кто видит/запускает отчёт» — дёшево сделать сейчас |
| Р8 | Отсутствие XML-файла у записи — **грид показывает «файл отсутствует»**, действия кроме «Удалить»/«Дизайнер» отключены | Защита от ручного удаления файла |
| Р9 | **Действия каталога диспетчеризуются по паре (type, id)**, не по голому id: `ReportCatalogItem` несёт `type`, обработчик клика берёт `type()` оттуда же и резолвит запись в соответствующем сервисе (`ReportTemplateService` / `UreportTemplateService`) | id из `report_template` и `ureport_template` — разные namespace (оба автоинкремент с 1): коллизия id двух РАЗНЫХ строк одного грида реальна. Отрицательные id как трюк — отклонено (хрупко, нечитаемо в логах/дебаге) |

---

## 3. Текущее состояние (что уже сделано ранее)

- UReport3 внедрён: форк `ureport3` (Spring 6.2/jakarta, POI 5, JDK 17+),
  собран в локальный .m2 (`com.bstek.ureport:ureport3-console:3.0.1`);
  сервлет `/ureport/*` рядом с Vaadin; SecurityConfig: `/ureport/**` authenticated,
  CSRF-игнор `/ureport/**`, `/error` permitAll, frameOptions sameOrigin.
- Русификация дизайнера (i18n-ru.js + патчи шаблонов), хранилище
  «Файловое хранилище» (рус. имя провайдера).
- Источник «Рабочая база» (`BuildinDatasource` → DataSource приложения).
- **JPQL-мостик**: маркер `jpql:` в SQL-датасете → `UReportJpqlDataset`
  (`org.ip.config`) → `ReportQueryGuard` → `ReportPreviewService` →
  `ReportQueryExecutor` (RLS через `RlsFilterActivator`, maxRows=10000, timeout=30с).
  Перехваты в форке: `SqlDatasetDefinition.buildDataset` (выполнение),
  `DatasourceServletAction.previewData` (превью), `buildFields` (автозаполнение
  полей, без JDBC-соединения), нормализация имён (`a.x` → `a_x`),
  EntityRef → displayName, исправлен switch-без-break в `buildParameters`
  (пустой параметр → настоящий null), двойная обработка ошибок в UReportServlet.

---

## 4. Фаза 1 — Единый каталог

### 4.1. Сущность `UreportTemplate` (org.ipro.ureport.dom)

```java
@Entity @Table(name = "ureport_template")
public class UreportTemplate extends BaseEntity {
    @NotBlank @Size(max = 200) private String name;        // отображаемое имя
    @NotBlank @Size(max = 255) private String fileName;    // имя в fileStoreDir, напр. "proba1.ureport.xml"
    @Size(max = 1000) private String description;
    private boolean enabled = true;
    // уникальность name (uk), fileName (uk)
}
```

- Регистрация: `@EntityScan` в `Application` (+ пакет), ddl-auto=update.
- Репозиторий `UreportTemplateRepository` (org.ipro.ureport.repository).
- Сервис `UreportTemplateService`: CRUD, `fileExists()` (проверка по
  `ureport.fileStoreDir`), `createWithDesigner()` — создаёт запись + минимальный
  XML (`<ureport><cell .../></ureport>` + paper) в хранилище.

### 4.2. Read-модель каталога

```java
// org.ipro.ureport (или общий пакет reportstudio.catalog — решить по границе org.ipro)
public record ReportCatalogItem(
    Long id,                          // id ВНУТРИ своего движка (namespace = type, см. Р9)
    String name, String description, boolean enabled,
    ReportEngineType type,            // enum { UDR, UREPORT3 } — обязательная часть ключа записи
    String designerUrl,               // для UREPORT3: /ureport/designer?_u=file:<fileName> (URL-encoded)
    boolean fileMissing) {}
```

- ⚠️ **Диспетчеризация действий — только по паре (type, id)** (Р9): обработчики
  кликов резолвят запись через сервис своего типа; сравнение/поиск записи в гриде —
  по `type + id`, никогда по одному `id`.

- `ReportCatalogService.findAll(String search)`: мёрж `ReportTemplateService` (UDR)
  + `UreportTemplateService` (UReport3) в память, сортировка по имени, поиск по подстроке.
- `fileMissing`: для UREPORT3 — отсутствие файла в хранилище.

### 4.3. UI каталога (модификация `ReportCatalogView`)

- Колонка **«Тип»** (бейдж/текст UDR / UReport3).
- **«Создать»** → диалог выбора типа:
  - UDR → существующий сценарий создания шаблона;
  - UReport3 → ввод name/description → создаётся запись + пустой XML →
    `UI.getPage().open(designerUrl)` (новая вкладка дизайнера).
- Действия по строке (кнопки/контекст):
  - UDR: «Открыть» (editor, как сейчас), «Выполнить» (Ф2), «Удалить»;
  - UREPORT3: «Дизайнер» (новая вкладка), «Выполнить» (Ф2), «Удалить»
    (запись + файл, с подтверждением), «Обновить из файла» — опционально.
  - `fileMissing` → бейдж «файл отсутствует», активны только «Удалить».
- Права: `REPORTS:*` через существующий CHECK_ONLY-паттерн settings
  (`REPORTS:VIEW`, `REPORTS:EDIT`, `REPORTS:RUN` — минимальный набор;
  per-отчётные права — Ф3 при необходимости).

### 4.4. Форк: прямой SQL на buildin-источнике — ОТЛОЖЕНО

- Решение (2026-08-24): **не блокировать** прямой SQL на «Рабочей базе» —
  может пригодиться авторам шаблонов. RLS-статус: `jpql:`-датасеты защищены,
  прямой SQL обходит Hibernate-фильтры (принятый риск, см. Р3).
- Потенциальное ужесточение (запрет датасетов без маркера `jpql:` в
  `BuildinDatasourceDefinition.buildDatasets`) — перенесено в Ф3 (бэклог).
  Если/когда будет внедряться: исключение `ReportComputeException` с понятным
  текстом, текст доходит до дизайнера через UReportServlet.

**Критерии приёмки Ф1**: в каталоге видны оба типа; создание UReport3 из каталога
открывает дизайнера с новым файлом; fileMissing отображается; `REPORTS:*`
перекрывает доступ. (Прямой SQL на «Рабочей базе» — не блокируется, Р3.)

---

## 5. Фаза 2 — Общий диалог параметров и запуск

> **Статус: реализовано (2026-08-24).**
> - `org.ipro.ureport.params`: `ParamUiType`, `UreportParamSpec` (name/caption=name/uiType/default).
> - `UreportTemplateService.loadParamSpecs(fileName)`: парсинг XML движком
>   (`ReportParser`), сбор `<parameter>` всех SQL-датасетов, дедуп по имени;
>   маппинг DataType → ParamUiType. Тесты: чтение параметров, ошибка отсутствия файла.
> - `org.ip.views.reportstudio.UreportParamsDialog`: поля по типам
>   (TextField/NumberField/DatePicker/Checkbox), кнопки Просмотр/PDF/Excel —
>   новая вкладка `/ureport/preview|pdf|excel?_u=file:...&par=...`;
>   пустые параметры не биндятся (паттерн `:p is null OR ...`).
> - Каталог: кнопка **«Выполнить»** — диспетчеризация (Р9): UDR → существующий
>   `ReportRunDialog`; UREPORT3 → `UreportParamsDialog`.
> - Caption-автоподстановка из метаданных (п. 5.1.1, шаг 2) — следующий шаг Ф2/Ф3.

### 5.1. Абстракция параметра

```java
// org.ipro.reportstudio.param (или соседний общий пакет)
public record ReportParamSpec(
    String name, String caption, ParamUiType uiType,
    Object defaultValue, boolean required) {}

public enum ParamUiType { STRING, INTEGER, FLOAT, BOOLEAN, DATE, PERIOD }
```

Адаптеры:
- **UDR**: `ReportParam` (только `valueSource=FORM`; kind PERIOD → PERIOD,
  ENTITY/ENTITY_LIST — как сейчас в существующем диалоге; caption/required из БД).
  Переиспользовать существующую форму, а не переписывать — адаптер оборачивает.
- **UReport3**: `UreportTemplateService.loadParamSpecs(fileName)`:
  `ReportParser.parse(provider.loadReport(file))` → `ReportDefinition.getDatasets()`
  → для каждого `SqlDatasetDefinition.getParameters()` →
  `ReportParamSpec(name, name, map(DataType→ParamUiType), defaultValue, false)`.

Маппинг типов: `Integer→INTEGER, Float→FLOAT, Boolean→BOOLEAN, Date→DATE
(формат yyyy-MM-dd), String→STRING, List→STRING (csv)`.

### 5.1.1. Caption параметров UReport3: порядок резолюции (приоритет — повышен из Ф3)

1. **Override из БД** — `ureport_template_param` (Ф3; нет строки → следующий шаг)
2. **Автоподстановка из метаданных** (делается в Ф2, механизм уже есть):
   если имя параметра совпадает с известным полем сущности — caption берётся из
   существующего `MetadataResolver`/`@FieldMetadata`/`HasDisplayName`
   (тот же источник подписей, что у обычных форм; параметр `journal` → подпись
   поля `journal` сущности). Реализация: в адаптере UReport3 при сборке
   `ReportParamSpec` попытаться резолвить caption по имени поля через
   `QueryMetadataCatalogService`/метаданные сущностей (по анализу JPQL или
   прямому поиску поля по имени в известных сущностях).
3. **Fallback** — само имя параметра.

Экономика: переиспользуется уже построенный резолвер метаданных, не заводится
третий независимый источник подписей; снимает главное неудобство «name вместо
подписи» на первой итерации.

### 5.2. Единый `ReportParamsDialog` (org.ip.views.reportcatalog)

- Вход: `List<ReportParamSpec>`, колбэк `onRun(Map<String,Object> values)`.
- Поля по uiType (TextField/NumberField/DatePicker/Checkbox); значения по умолчанию
  из spec; required-валидация (для UDR — как в существующем диалоге).
- Кнопки: «Просмотр», «PDF», «Excel», «Отмена».

### 5.3. Запуск

- **UDR**: как сейчас — `ReportExecutionService.run(template, context)` →
  `export(format)` → скачивание byte[]; просмотр — существующий механизм.
- **UReport3**:
  - построение query string из Map: `null`/пустые — пропускать; даты → `yyyy-MM-dd`;
    имя файла `_u=file:<fileName>` (URL-encode);
  - «Просмотр» → `UI.getPage().open("/ureport/preview?_u=...&par=...")` (новая вкладка);
  - «PDF» → `/ureport/pdf?...`, «Excel» → `/ureport/excel?...` (скачивание в сессии).
  - RLS: автоматически (jpql:-датасеты идут через executor под текущей сессией).

### 5.4. Соглашение для авторов UReport-шаблонов (документ)

- Параметры объявлять **в параметрах датасета** (имя = `:биндинг` в JPQL);
- `<search-form>` в XML не используется (диалог строится приложением);
- на «Рабочей базе» — только `jpql:`-датасеты;
- entity-поля выводить алиасами (`as journal`), entity-значения отображаются
  через `getDisplayName()`.

**Критерии приёмки Ф2**: диалог параметров одинаково запускает UDR и UReport3;
пустые/заполненные параметры UReport3 работают (включая `:p is null OR ...`);
экспорт PDF/Excel скачивается; результат — в новой вкладке.

---

## 6. Фаза 3 — отложенное (бэклог)

1. `ureport_template_param` (override caption/required для параметров UReport3) —
   паттерн Settings: нет строки → показываем name; автоподстановка из метаданных
   уже работает с Ф2 (п. 5.1.1), override — высший приоритет резолюции.
2. `DBReportProvider` — шаблоны UReport3 в БД (единый source of truth, sync-проблемы
   исчезают); миграция fileStoreDir → БД.
3. **Ужесточение RLS: запрет прямого SQL на «Рабочей базе»** (перенесено из Ф1
   по решению 2026-08-24, см. Р3 / п. 4.4): в `BuildinDatasourceDefinition.buildDatasets`
   отклонять датасеты без маркера `jpql:` — если/когда принятый риск перестанет
   быть приемлемым.
4. Серверный рендер UReport3 (`ExportManager`) — фоновая генерация, рассылки,
   без HTTP-раундтрипа.
5. Per-отчётные права (`REPORTS:<name>:RUN`) и права на каталоги/папки.

---

## 7. Затрагиваемые файлы (ориентир)

**Приложение (GitVaa25):**
- `org/ipro/ureport/dom/UreportTemplate.java` (новый)
- `org/ipro/ureport/repository/UreportTemplateRepository.java` (новый)
- `org/ipro/ureport/service/UreportTemplateService.java` (новый)
- `org/ip/views/reportcatalog/ReportCatalogService.java`, `ReportCatalogItem.java`,
  `ReportParamsDialog.java`, `ReportParamSpec.java` (новые)
- адаптер параметров UReport3: caption-резолюция через существующий
  `QueryMetadataCatalogService`/метаданные сущностей (п. 5.1.1)
- `org/ip/views/reportstudio/ReportCatalogView.java` (модификация: Тип, Создать,
  действия UREPORT3)
- `org/ip/config/UReportConfig.java` (право REPORTS:*, если требуется бин-конфиг)
- `org/ip/Application.java` (@EntityScan + пакет ureport)
- `docs/` — соглашение для авторов UReport-шаблонов

**Форк (UReportFork2/ureport3):**
- (Ф1: изменений НЕТ — запрет прямого SQL отложен, см. Р3 / п. 4.4 / Ф3 п.3)
- пересборка `mvn install` core/console — только при новых правках форка,
  рестарт приложения после обновления jar

**Тесты:**
- `UreportTemplateServiceTest` (создание записи+XML, fileMissing)
- `ReportCatalogServiceTest` (мёрж типов, поиск, **уникальность (type,id)**)
- интеграционный: jpql:-датасет с пустым/заполненным параметром
- тест caption-резолюции: override БД → метаданные → имя (п. 5.1.1)
- ручная приёмка по критериям Ф1/Ф2

---

## 8. Риски и открытые пункты

| Риск | Митигация |
|------|-----------|
| Ручное удаление XML мимо приложения | fileMissing в гриде (Р8); Ф3: DBReportProvider |
| Появление третьего движка отчётов | Read-модель каталога легко расширяется новым адаптером |
| Пользователь построит search-form в дизайнере | Р5 + документ-соглашение (Ф2) |
| Тяжёлый JPQL из дизайнера | guard + maxRows/timeout в UReportJpqlDataset (уже есть) |
| Утечка данных через прямой SQL на «Рабочей базе» | **Осознанный принятый риск** (Р3, отчётчики доверенные); ужесточение — Ф3 п.3 (запрет без маркера `jpql:` в форке) |

## 9. Порядок работ

1. Ф1: сущность+сервис → каталог (Тип/Создать/Действия) → право REPORTS:* → тесты Ф1.
2. Ф2: ParamSpec+адаптеры → ReportParamsDialog → запуск UReport3 (вкладка+экспорт) →
   тесты Ф2 + ручная приёмка.
3. Ф3 — по потребности (в т.ч. ужесточение RLS — запрет прямого SQL, п. 4.4/Р3).
