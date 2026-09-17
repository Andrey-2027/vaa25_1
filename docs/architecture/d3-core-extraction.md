# D3: вынос платформы в модули — состояние и корректировки плана

**Статус:** D3.0–D3.4 закрыты, включая доработки по ревью D1–D3 (§2.7). Закрыто: D3.0
(воспроизводимый bootstrap: GAV, сверка POM-графа без сборки, пустой локальный репозиторий,
и `-ValidateOnly` без Maven), D3.1 (identity seam и владение событиями; FQN —
`org.ipro.identity`), D3.2 (разрыв core/UI-связок), D3.3 (`platform-core`: 92 production-типа
вынесены, тестовое владение исполнено, семантическая классификация поверхности закрыта —
§2.6 и §2.7), D3.4 (`platform-spring-boot-autoconfigure`: пять backend-
автоконфигураций вынесены в sibling-модуль, собственная регистрация
`AutoConfiguration.imports`, typed `PlatformProperties` без default для обязательного
`platform.subsystem-scan-package`).

**Чем это проверено после доработок:** `PlatformCoreSurfaceTest` 5/5,
`PlatformPublicSurfaceTest` 10/10, `PlatformApiBaselineTest` 3/3,
`BootstrapScriptContractTest` 2/2, `bootstrap-local-dependencies.ps1 -ValidateOnly` — все in-repo
fingerprints OK (внешний дрейф форков — отдельная политика, §2.5). Полный прогон
`mvn verify` по приложению и модулям — отдельной сборочной очередью (в этом воркспейсе
модули собираются не реактором, а manifest'ом).

Документ из двух частей: сначала **сверка плана D3 с фактами кода** (что в плане устарело и
почему), затем **что уже сделано** и чем это проверено. Сверка сделана до начала работ, а не
постфактум: две из четырёх существенных корректировок меняют состав ближайших задач.

---

## 1. Сверка плана D3 с фактами кода

### 1.1. D3.0 «восстановить воспроизводимую сборку» — уже сделан

План называет D3.0 обязательным prerequisite и перечисляет пять работ. Четыре из пяти
выполнены в закрытии D1/D2:

| Работа плана | Состояние |
|---|---|
| граф по `dependsOn` | есть — в скрипте и в `WorkspaceManifestTest` |
| топологическая сортировка | есть — Kahn в `bootstrap-local-dependencies.ps1`, порядок в манифесте топологичен |
| падать при цикле | есть — скрипт падает до первой сборки |
| падать при ссылке на отсутствующий проект | есть — там же |
| пересчитать fingerprints | есть — алгоритм `gitvaa-source-tree-sha256-v2` (ordinal-порядок путей) |
| проверка manifest без сборки проектов | сделано в остатках D3.0 (§2.4): скрипт читает POM'ы и сверяет граф сам |
| уникальность публикуемых GAV | сделано в остатках D3.0 (§2.4) |
| проверка на пустом локальном Maven repository | сделано и **выполнено** в остатках D3.0 (§2.4): 13/13 проектов из пустого репозитория |

Отдельно: план предлагает «`platform-contracts` должен собираться после identity/crudui в
зависимости от стадии миграции» и «`platform-persistence` сейчас тоже нельзя собирать раньше
crudui». Оба ребра (`platform-contracts -> crudui`, `platform-persistence -> crudui`) уже
исправлены, зависимости низкоуровневых модулей на `crudui-core` больше нет вовсе (§2.2).

**Вывод:** D3.0 из ближайших задач превращается в три остатка — GAV-уникальность, сверка
POM-графа без сборки приложения, проверка на пустом локальном репозитории. Все три закрыты
(§2.4), поэтому D3.0 можно считать завершённым целиком.

### 1.2. D3.1 «identity seam и ownership событий» — уже сделан, кроме FQN

Сделано: `platform-identity-api` создан как модуль реактора `crudui` с нейтральными
координатами; `org.ipro:platform-contracts` и `org.ipro:platform-persistence` больше не зависят
от `crudui-core`; `EntityLifecycleRegistry` создаётся `EventsAutoConfiguration`
(`@ConditionalOnMissingBean`), metadata-конфигурация им не владеет.

**Расхождение с планом.** §3 плана требует сохранить FQN `org.ipro.crud.IdentifiableEntity`, а
на диске уже сделан переход на `org.ipro.identity.IdentifiableEntity`. План обосновывает
сохранение FQN тем, что иначе D3 превращается в массовую миграцию импортов и ломается
бинарная совместимость. Аргумент верен ровно настолько, насколько существуют внешние бинарные
потребители: их нет — 60 файлов во двух репозиториях, все под контролем, публикуемых релизов
типа нет. Это решение вынесено в §3 как открытое, потому что оно определяет публичный FQN.

### 1.3. D3.2 «разорвать core/UI-циклы» — посылка устарела, объём был в 5 раз меньше

План описывает D3.2 как большую работу («перед физическим переносом классов нужно очистить
границы») и перечисляет пять проверок. Фактический замер до работ:

```text
crud: 0/17    data: 0/26    fetch: 0/9    filter: 0/2
metadata: 0/34    search: 1/15    security: 0/1     файлов с импортом com.vaadin
```

То есть в кандидатах на `platform-core` Vaadin практически не было. Реальных связок оказалось
ровно четыре, и каждая — не «грязный код», а одна строка ownership'а бина:

1. `MetadataAutoConfiguration` создавала `MetadataDrivenItemFormSaveAdapter` → `metadata -> form`;
2. `GlobalSearchAutoConfiguration` создавала `GlobalSearchNavigationAdapter` (нужен
   `FormCoordinator`) и `GlobalSearchHeader` (Vaadin) → `search -> form`;
3. `org.ipro.metadata.explorer` импортировал `form.registry.FormRegistry` и
   `form.builder.ContextFilterField` → `metadata -> form`;
4. `org.ipro.form` был в порядке, но `form.config` сам стал зависеть от metadata после (1).

**Уточнение к плану про explorer.** План говорит «metadata explorer перенести в UI-границу».
Замер подтверждает вывод, но по другой причине: explorer не содержит Vaadin вообще (0 файлов).
Он удаляется из metadata-ядра потому, что это read-model, **наполняемый из `FormRegistry`** —
то есть модель UI-слоя, живущая не в своём модуле.

### 1.4. Предупреждение плана про `platform-metadata` — подтверждено, и это важно

План предупреждает: не расширять существующий `platform-metadata` всей metadata-реализацией,
потому что `platform-numbering` уже зависит от него, а полная metadata-реализация использует
numbering → цикл. Проверено по POM: `platform-numbering` → `platform-contracts`,
`platform-metadata`, `platform-persistence`. Предупреждение верное.

Разрешается оно так: **контракты остаются в `platform-metadata`**, metadata-реализация уезжает
в `platform-core`, и направление получается `platform-core -> platform-numbering ->
platform-metadata`. Это одна из немногих зависимостей, которую нельзя «исправить» — её нужно
спроектировать заранее, иначе D3.3 встанет на цикле.

### 1.5. План не назвал, где живут новые модули

В этом репозитории нет Maven-реактора: `platform-*` — отдельные проекты в этом же чекауте,
которые собирает bootstrap-manifest, а приложение держит версии в `${platform-x.version}`.
Из этого следует то, чего в плане нет: новые модули (`platform-core`, `platform-vaadin`,
`platform-spring-boot-autoconfigure`, report-* и starters) должны быть siblings в этом чекауте
и попасть в манифест — иначе `WorkspaceManifestTest` их не увидит и воспроизводимость
бутстрапа снова перестанет быть проверяемой. `platform-bom` в этой картине не просто
«управляет версиями»: он снимает девять `${platform-x.version}` из POM приложения.

### 1.6. Оценка D3.6/D3.7 в плане занижена

`org.ipro.reportstudio` — 108 файлов (9 с Vaadin), плюс три редактора; `org.ipro.jr` — 7;
`org.ipro.ureport` — 10. Это самая тяжёлая часть D3: консолидация редакторов (§D3.6 плана) —
это работа с UI и тестами, а не перенос пакета. План сам требует не смешивать её с выносом
core (§5) — с этим согласен; важно, что D3.6 не «остаток после core», а сопоставимый по объёму
этап.

### 1.7. Мелкое уточнение к D3.4

Свойства из плана подтверждены: `platform.subsystem-scan-package=org.ip`,
`settings.scan-package=org.ip.settings`, `rls.dimension-scan-package=org.ip`. Первое уже
инжектится как `@Value("${platform.subsystem-scan-package}")` **без default**, то есть
отсутствие значения — fail-fast на старте; это лучше, чем описывает план, и типизация через
`@ConfigurationProperties` должна сохранить это свойство, а не заменить его на пустой дефолт.

---

## 2. Что сделано

### 2.1. D3.0 — воспроизводимый bootstrap (объём D1/D2)

Манифест читается сверху вниз, `dependsOn` совпадает с реальными POM-рёбрами, порядок
топологичен, fingerprints считаются алгоритмом v2 (ordinal-порядок путей — прежний
culture-aware `Sort-Object` давал «совпадение», зависящее от культуры процесса, а не от файлов).
Гейт: `WorkspaceManifestTest` (fingerprints + `dependsOn` = POM-рёбра + топологический порядок).
`bootstrap-local-dependencies.ps1 -ValidateOnly` подтверждает все девять in-repo проектов;
единственное расхождение — внешний `../DynamicReport7`, чужой fingerprint здесь намеренно не
переписывается.

### 2.2. D3.1 — identity seam и ownership событий

`org.ipro:platform-identity-api` — Java-only артефакт (ни Spring, ни JPA, ни Vaadin), модуль
реактора `crudui`. `platform-contracts` и `platform-persistence` зависят от него, а не от
UI-артефакта. `EntityLifecycleRegistry` создаётся `EventsAutoConfiguration` вместе с
`EntityEventPublisher`; `EventContourStartupCheck` в приложении остаётся защитой от потери
самой автоконфигурации.

### 2.3. D3.2 — разрыв core/UI-связок

| Что | Куда | Почему |
|---|---|---|
| бин `MetadataDrivenItemFormSaveAdapter` | `MetadataAutoConfiguration` → `FormAutoConfiguration` | бин реализует формовый контракт `ItemFormSaveHandler` и существует только там, где есть формы |
| `GlobalSearchHeader`, `GlobalSearchNavigationAdapter` | `org.ipro.search` → `org.ipro.vaadin.search` | Vaadin-компонент и навигация через `FormCoordinator` — UI-граница, а не ядро поиска |
| их бины | `GlobalSearchAutoConfiguration` → `GlobalSearchVaadinAutoConfiguration` | ядро поиска остаётся работоспособным без UI |
| explorer (`EntitySummary`, `EntitySummaryAssembler`, `SubsystemSummaryAssembler`, конфигурация) | `org.ipro.metadata.explorer` → `org.ipro.vaadin.explorer` | read-model, наполняемый из `FormRegistry`; metadata-ядро публикует API, события и модель |

Тесты переехали вместе с классами (`org.ipro.vaadin.*`), общая фикстура
`GlobalSearchTestSupport` стала публичной с объяснением причины, три прикладных view,
`MainLayout` и `InstanceNamePilotIT` обновили импорты.

**Доказательство, что связки действительно разорваны, — не отсутствие импортов, а diff
reviewed-матрицы.** Четыре ребра исчезли:

```text
- org.ipro.metadata.config -> org.ipro.form
- org.ipro.metadata.explorer -> org.ipro.form.builder
- org.ipro.metadata.explorer -> org.ipro.form.registry
- org.ipro.search -> org.ipro.form.coordinator
- org.ipro.search.config -> org.ipro.form.coordinator
```

и появились только законные: `org.ipro.form.config -> org.ipro.metadata{,.config}` (новый дом
бина адаптера) и рёбра `org.ipro.vaadin.*`. Это и есть закрытие исключения
`metadata -> form`, записанного в `d1-platform-boundary-map.md` §3.4 как «снимается в D2».

**Гейты** (`PlatformCoreBoundaryTest`, 4 теста): core-кандидаты не зависят от `com.vaadin`;
core-кандидаты не зависят от `org.ip`; `metadata` не зависит от `form`; `search` не зависит от
`form`; исходники `platform-*` не импортируют `org.ip.*`. Плюс уже существующие
`PlatformPackageDependencyMatrixTest` (полная allow-list направлений) и
`PlatformPublicSurfaceTest` (FQN → API/SPI/legacy-internal, бюджет internal только сокращается).

### 2.4. Остатки D3.0 — закрыты

**Уникальность публикуемых координат.** Реализована дважды и намеренно: в скрипте
(`Assert-PublishedCoordinatesAreUnique`) и в `WorkspaceManifestTest`. Два владельца одного GAV
делают `dependsOn` бессмысленным — в локальный репозиторий попадут артефакты того, кто
собрался позже. Текущий факт: 25 координат, у каждой один владелец.

**Сверка `dependsOn` с POM-графом без сборки приложения.** Раньше это умел только JUnit-тест,
для которого нужно скомпилировать всё дерево — то есть проверка воспроизводимости требовала
успешной сборки. Теперь то же делает скрипт (`Assert-ManifestGraphMatchesPoms`): читает POM'ы
build steps, раскрывает модули реакторов, вырезает `dependencyManagement`, сравнивает
фактические рёбра с `dependsOn` в обе стороны. Две независимые реализации сверяются между
собой, как и у fingerprints. Текущий факт: 15 рёбер, расхождений нет.

Гейт проверен негативно — три инъекции в манифест и ожидаемая реакция:

```text
дубль GAV                → Artifact 'org.ipro:platform-events' is published by both …
снятое ребро crudui      → platform-contracts: pom references crudui, but dependsOn does not declare it …
лишнее ребро             → platform-contracts: dependsOn declares platform-events, but no POM references it …
```

**Проверка на пустом локальном репозитории.** Добавлен `-FreshLocalRepository`: использует
отдельный каталог `.local-maven-repository-fresh` (а не тёплый кэш `.local-maven-repository`,
ради которого и существует `-Offline`), требует его пустоты и отказывается работать вместе с
`-Offline` — из пустого репозитория зависимости должны прийти по сети, и это свойство самого
гейта, а не неудобство.

Выполнено по-настоящему: прогон дошёл до конца (`Local dependency bootstrap completed
successfully`), **16 успешных Maven-вызовов по 13 проектам, 0 падений**, порядок ровно
топологический:

```text
crudui -> platform-contracts -> platform-events -> platform-persistence -> platform-metadata
 -> platform-numbering -> platform-settings -> platform-telemetry -> platform-rls
 -> dynamicreports -> reportui -> filtergrid -> ureport3
```

Шаг приложения в этом прогоне пропущен (`-SkipApplication`): его `clean verify` — это уже
проверка приложения, а не обещания манифеста об порядке сборки, и она выполняется отдельно
(`mvn -o clean verify`). Полный прогон с приложением — release-gate, а не проверка
внутреннего цикла.

**Находка: внешний дрейф блокирует бутстрап целиком.** `dynamicreports` — это проект манифеста
(#10) с исходниками в соседнем каталоге `../DynamicReport7`, и его fingerprint разошёлся
(предсуществующее состояние, не этой работой). Скрипт считает такой дрейф фатальным, поэтому
прогон выше потребовал `-AllowSourceDrift`. Это осознанное решение (изменилось то, **что**
собирается), но его следствие стоит назвать: в этом воркспейсе `bootstrap` без флага не
запускается вообще, а починить причину из этого репозитория нельзя. Политику я сначала предложил смягчить (внешние копии — предупреждение по умолчанию, потому что
починить их отсюда нельзя), но проверка ниже показала, что дрейф — настоящая правка файлов.
Фатальность здесь **правильна**, и предложение снимается: см. §2.5.

### 2.5. Дрейф внешних проектов: проверенные факты, а не догадки

Сообщение бутстрапа при дрейфе («review the changes and update the manifest intentionally») не
различает два случая: содержимое дерева действительно изменилось или сломан алгоритм (v1
culture-aware, поэтому одно дерево может давать разные SHA в зависимости от культуры процесса).
Разница определяет, кто должен действовать — владелец чужого репозитория или запись в манифесте.
Для этого добавлен диагностический инструмент `scripts/diagnose-source-fingerprint.ps1`: он
пересчитывает fingerprint проекта алгоритмом v2 (ordinal) и v1 при пяти культурах и показывает,
какая комбинация воспроизводит записанное значение.

Результат по всем четырём внешним проектам:

| Проект | Записано | v2 (ordinal) | Воспроизводит запись? | Вердикт |
|---|---|---|---|---|
| `dynamicreports` (1321 файл) | `1b193276…` | `d869ece4…` | нет ни при одной культуре | **содержимое изменилось** |
| `ureport3` (544 файла) | `7fe1ada3…` | `7eee06ea…` | нет ни при одной культуре | **содержимое изменилось** |
| `reportui` (13 файлов) | `3c93e54a…` | `f8838b4a…` | да (v1, все культуры) | дрейфа нет |
| `filtergrid` (160 файлов) | `bfa65774…` | `08936ae9…` | да (v1, все культуры) | дрейфа нет |

Выводы, которые из этого следуют:

1. **Манифест не сломан целиком**: два внешних проекта воспроизводятся точно. Проблема локальна
   в двух деревьях.
2. **У `dynamicreports` и `ureport3` правка настоящая.** Пять разных культур дают одно и то же
   новое значение, и ни одна не воспроизводит записанное — значит это не артефакт измерения, а
   изменение файлов.
3. **`v1` и `v2` дают разные значения для всех четырёх деревьев** (например `reportui`:
   `3c93e54a…` против `f8838b4a…`). Значит значения v1 и v2 не взаимозаменяемы, и перевод
   внешних проектов на ordinal-алгоритм — это осознанная перезапись, а не пересчёт.

**Смысл дрейфа `dynamicreports`.** Это не «просто внешняя библиотека»: это вендоренный форк
DynamicReports 7 с внутренней версией `7.0.0-ip.20260813`, который бутстрап собирает из
соседнего дерева и кладёт в локальный репозиторий (приложение берёт его через
`${dynamicreports.version}`). `README-vendor.md` форка задаёт контракт: версия фиксированная, не
SNAPSHOT, **артефакт неизменяем**, а процедура починки — «правка → пересборка → установка с
НОВОЙ версией `7.0.0-ip.<дата>` → обновление `dynamicreports.version`». Дрейф при неизменной
версии означает ровно одно: **версия в POM приложения больше не идентифицирует собираемые
биты**. Проверка манифеста — единственный сигнал об этом, и он обязан быть фатальным, пока
версия и содержимое расходятся.

**Смысл дрейфа `ureport3`.** Его `source.kind` — `dirty-git-worktree`, и манифест явно
фиксирует «tracked and untracked local changes are part of the recorded fingerprint». То есть в
пин включено незакоммиченное рабочее дерево, и дрейф будет возникать при каждой работе в том
форке. Это тот же дефект, что у `dynamicreports`, только виднее: пиннится состояние работы, а не
версия.

**Проверка:** `mvn -o clean verify` — 1368 тестов, 0 падений; модульные сборки
(`platform-contracts` 4, `platform-events` 27, `platform-persistence` 6) зелёные;
замеры D1 перегенерированы (`platform-tree-files` 309 → 310: добавлена
`GlobalSearchVaadinAutoConfiguration`), реестр D1 обновлён намеренно.

---

## 2.6. D3.3: первый срез platform-core и замер, который изменил план

### Главное: D3.3 нельзя нарезать мельче, чем на 78 классов

План предлагает переносить `platform-core` «согласованными пакетными группами» и перечисляет
семь групп. Замер рёбер (без `config`, которые остаются в дереве до D3.4) показывает, что четыре
из них образуют один цикл:

```text
metadata -> crud   MetadataResolver -> crud.StandardCatalogEntity / StandardDocumentEntity
                   SectionMetadataRegistry -> crud.TableSectionService
                   ManagedEntityCatalog -> crud.ValidationException
crud -> metadata   13 файлов
crud -> data       LookupService -> data.{CanonicalReadExecutor,DetailRead,ListRead,LookupRead}
data -> crud       CanonicalEntityService -> crud.BaseService; CanonicalWriteExecutor -> crud.*
crud -> fetch      BaseService, LookupService -> fetch.plan.FetchScenario
data -> fetch      InstanceNameResolver, FetchPlanRegistry, …
fetch -> metadata  InstanceNameResolver, FetchPlanRegistry
```

Сначала показалось, что цикл `metadata ↔ crud` создаётся конфигурацией, которая остаётся в
дереве (она действительно импортирует `crud`). Проверка отдельных файлов это опровергла: связи
есть и в обычных классах, без `config`.

Следствие, которого в плане нет: **первая группа — это сразу 78 классов** (`crud` 16, `data` 25,
`fetch` 8, `metadata` 29), потому что пакет нельзя перенести частично: типы одного пакета в двух
артефактах — это split package, который компилятор различает по порядку classpath, а не по
замыслу. План прав, что `crud` + `data` едут вместе, но молчит, что `metadata` и `fetch` в том же
цикле.

Вне цикла стоят три пакета, и это важно для порядка:

| Пакет | Рёбра из него | Кто на него ссылается | Место |
|---|---|---|---|
| `search` (12) | data, fetch, metadata | никто из кандидатов | после цикла |
| `filter` (2) | metadata | только UI (`form.builtin`) | после цикла |
| `security` (1) | ни одного | data (один файл) | лист — может ехать когда угодно |

Совпадение, которого нельзя терять: 30 из 56 тестов кандидатов импортируют `org.ip` (прикладные
фикстуры). Они не могут переехать в модуль, который обязан собираться без приложения. Политика:
тест едет с классом только если компилируется в модуле; тест с прикладными фикстурами остаётся в
приложении как проверка интеграции и назван долгом, а не «почти выносом».

### Что сделано в первом срезе

Группа выбрана по признаку «не зависит ни от чего, кроме JDK» — `org.ipro.metadata.facet`
(5 типов). Цель среза не в его ценности, а в том, чтобы на маленьком объёме проверить весь
конвейер выноса, который для 78 классов проверять уже поздно:

| Что | Как |
|---|---|
| Новый проект манифеста | `platform-core`: `dependsOn: []` (срез без compile-зависимостей), `artifacts`, fingerprint v2, `buildSteps` **без** `-DskipTests` |
| Топология бутстрапа | порядок: `… platform-telemetry -> platform-rls -> platform-core -> внешние` |
| Приложение берёт классы из артефакта | `PlatformCoreModuleTest.sliceTypesAreResolvedFromTheArtifactAtRuntime` — по `CodeSource`, а не по исходникам |
| Гейт владельца | `CoreModuleCompositionTest` (4 теста) в модуле |
| Гейт потребителя | `PlatformCoreModuleTest` (5 тестов) в приложении |

Замеры D1 сдвинулись направленно и на одно и то же число — `platform-tree-files` 310 → 305,
`platform-artifact-files` 171 → 176: перенос ровно пяти файлов, а не десять или два. Это и есть
смысл генерации: два числа, меняющиеся на один шаг в разные стороны, проверяют друг друга.

**Проверка:** `mvn -o clean verify` — 1373 теста, 0 падений; `mvn -f platform-core/pom.xml
clean install` — 4 теста модуля; бутстрап: 10/10 in-repo fingerprints, 26 координат с единственным
владельцем, 15 рёбер `dependsOn` совпадают с POM-графом.

### Найденное по ходу: гейт ловил документацию

Первая версия проверки «модуль не зависит от Vaadin» искала подстроку `vaadin` в тексте POM и
падала на комментарии, который объясняет, почему Vaadin здесь запрещён. Гейт, который требует
удалить объяснение, чтобы стать зелёным, учит обратному тому, зачем написан. Переписан на разбор
**объявленных зависимостей** (блоки вне `test`-scope). Это второй раз за D-этапы, когда проверка
по тексту POM оказалась неверной; способ проверки изменился, вывод тот же.

### Тестовое владение: реестр исполнен (завершение D3.3)

Политика «тест с прикладными фикстурами остаётся в приложении» уточнена реестром: для каждого
из 47 остававшихся тестовых файлов шести пакетов проверены импорты и предмет проверки.

**Перенесено по владельцу поведения, не по имени пакета:**

| Действие | Файлы | @Test |
|---|---|---|
| В `platform-core` без изменений (0 импортов `org.ip`, все зависимости уже в compile/test classpath модуля) | 13: EntityCopyService, ServiceLocatorCanonicalFallback, ValidatedJpaCrudService(+Guard), EffectiveFieldFacts, EntityKindResolution, MetadataHierarchy, MetadataInvalidate, MetadataConsistencyValidator, MetadataConsistencyStartupCheck, SectionDescriptorResolution, GridViewStateFilterTree, EventContourStartupCheck, ColumnPathFilterFieldResolver | 54 |
| `GlobalSearchProviderRegistryTest` — переписан в модуле | прикладные `Nomenclature`/`PrdSpec` заменены нейтральными маркерами: реестру нужен только `Class`, а `GlobalSearchSource` — публичный record, каталог и JPA-фикстуры тесту не нужны | 2 |
| `BaseEntityEqualityTest` → `platform-persistence` | владелец `BaseEntity` — persistence, а не core; критерий — проверяемая ответственность | 4 |

Пункт «настроить запуск `*IT` в модуле» был закрыт ранее: surefire `platform-core` включает
`**/*Test.java` + `**/*IT.java`, `CanonicalWritePathIT` выполняется в модуле (29 методов).

**Остаток в приложении — 31 файл, у каждого есть причина:** 29 импортируют `org.ip` (интеграционные
проверки на прикладных сущностях/контексте — п. 2.6 плана); 2 без `org.ip`, но обоснованно:
`MetadataSnapshotRenderer` — хелпер Spring-теста с `DataInitializer`, а
`FetchPlanInstanceNameAutoConfigurationTest` — тест конфигурации, уезжает в D3.4 вместе с ней.

**Числа проверяют друг друга:** приложение 1375 → 1260 (−115 = 55 предыдущего среза + 60 этого),
`platform-core` 60 → 116 (= 60 + 54 + 2), `platform-persistence` 6 → 10 (= 6 + 4).

**Проверка:** `mvn -o clean verify -f platform-core/pom.xml` — 116 тестов, 0 падений;
`-f platform-persistence/pom.xml` — 10; полный verify приложения — 1260 тестов, единственное
падение — fingerprints `platform-core` (104→118 файлов) и `platform-persistence` (8→9),
обновлены write-mode (`-Dplatform.manifest.write=true`), затем 4/4; `bootstrap -ValidateOnly` —
все in-repo fingerprints OK, единственный дрейф — внешний `dynamicreports` (политика:
внешние форки — библиотеки, в этом воркспейсе бутстрап с `-AllowSourceDrift`).

## 2.7. Доработки по ревью D1–D3: полнота гейтов и классификация поверхности

Ревью подтвердило, что физический вынос сделан верно, но нашло, что <b>обещанные гарантии</b>
были слабее заявленных: реестр можно было обойти синтаксисом, проверка манифеста зависела от
Maven, а baseline фиксировал реализацию. Шесть замечаний (3 P1 и 3 P2) закрыты; ниже — что
именно изменилось и чем это проверяется.

| Замечание | Состояние |
|---|---|
| P1. Реестр D1 обходится без импорта | закрыто: ссылки распознаются во всех трёх синтаксисах; wildcard-импорты и fully-qualified ссылки в прикладном коде запрещены (17 файлов / 39 ссылок мигрированы) |
| P1. `-ValidateOnly` требует Maven | закрыто: разрешение toolchain перенесено за ветку проверок; оба направления закреплены тестом |
| P1. API baseline: недоохват и переохват | закрыто: список артефактов выводится из манифеста, `platform-core` фиксируется по семантическим ролям, остальные помечены `TEMPORARY_ALL_PUBLIC` |
| P2. `platform-crud-api` не в измерении | закрыто: 264 → 265 файлов |
| P2. Тесты telemetry отключались | закрыто: `-DskipTests` убран, оба тестовых класса модуля запускаются |
| P2. Документация расходится с кодом | закрыто: этот раздел, `platform-core/README.md`, `current-baseline.md` |

### Реестр: ссылка, а не строка импорта

Измерение шло по строкам `import`, и это была настоящая дыра: пять production-зависимостей
приложения жили в других синтаксисах и не попадали ни в замер, ни, следовательно, в реестр.
Правило «приложение не называет незарегистрированный тип» работало только для тех, кто пишет
импорты единообразно.

Что сделано. Сначала измерение: ссылка распознаётся в импортах, в fully-qualified употреблениях
(token разрешается по classpath компиляции, включая вложенные типы) и в wildcard-импортах.
При этом два последних синтаксиса теперь ещё и запрещены — но не догма: за
`import org.ipro.metadata.annotation.*` скрыт набор типов, которого строка импорта не называет,
а FQ-ссылка не видна в шапке файла, где читающий смотрит зависимости.

Запрет применён буквально, потому что объём оказался обозримым: в прикладном коде было
**17 файлов и 39 fully-qualified ссылок** — они мигрированы на явные импорты (включая
вложенные типы вроде `AccessService.EffectiveGrant`), четыре wildcard-импорта раскрыты в
явные, а `-Dplatform.measurements.write` больше не нужен для FQ: их нет. Недоразрешимая
ссылка `org.ipro.*` роняет сборку, а не выпадает из замера молча.

Отдельно стоит назвать одну ошибку, которую эти гейты поймали друг на друге. Инструмент
`analyze-platform-surface.mjs` считал пространство имён только по этому чекауту и потому не
видел тип из соседнего проекта манифеста (`org.ipro.filtergrid.DateRangeFilter` из `FilterGrid`):
инструмент говорил «ссылок нет», а Java-гейт по classpath находил одну. Исправлены оба:
код получил явный импорт, а инструмент — источник из соседних проектов манифеста. Правило,
которое из этого следует и уже было записано в D2: две независимые реализации проверки ценны
именно тем, что расходятся.

Пять пропущенных типов добавлены с ролями: `WorkspaceGateway` и `JpqlDatasetRunner` — SPI
(приложение их реализует), `SectionRlsPolicy` — API, `JrxmlTemplateService` и `ServiceParams` —
legacy-internal до выноса reports. Бюджет: 73 → 75 как **исправление измерения** (два новых
внутренних типа), затем 75 → 74 как осознанное решение — `NaturalKeyCreateSupport` признан
поддерживаемым API, потому что это generic-механизм прикладных interned use cases, а не
внутренность. Оба шага записаны в шапке реестра.

Замеры после исправления: `named-platform-types` 200 → 206, `api-types` 105,
`spi-types` 26, `legacy-internal-types` 74 (после снятия §2.8 — 72). Числа генерируются
и сверяются тестом, а не пишутся в текст.

### Семантические роли 92 типов

D1-реестр отвечает на вопрос «что делает приложение», но ничего не говорит о типах, которых
приложение не называет: 44 типа образуют контракт между модулями, а 16 — реализация. Обещание
«internal можно менять свободно» было непроверяемым.

Новый реестр `platform-core-surface.txt`: у каждого из 92 production-типов ровно одна базовая
роль — `APP_API` 28, `APP_SPI` 4, `MODULE_API` 44, `INTERNAL` 16. Legacy-доступ к типам с
ролью `MODULE_API`/`INTERNAL` остаётся ортогональной записью (legacy-записи D1 и overlay
`test-usage`), а не пятой ролью: тип может быть `MODULE_API` и одновременно иметь временные
ссылки, которые обязаны исчезнуть.

Гейт `PlatformCoreSurfaceTest` проверяет: полноту 92/92 в обе стороны; согласованность двух
классификаций (что делает приложение ↔ какой контракт у типа); API closure транзитивно (в
публичной сигнатуре `APP_API`/`APP_SPI` не может быть `INTERNAL`); заморозку ссылок тестов по
файлам; и то, что production-код приложения не дотягивается до `INTERNAL` вне legacy-записей.

Семь типов (`EntityDescriptor`, `SearchContext`, `FactOrigin`, `MetadataDiagnostic`,
`MetadataCache`, `GlobalSearchMatchKind`, `EntityCapabilities`) переведены в `APP_API` не
решением о конкретном типе, а выполнением правила 2: они стоят в публичных сигнатурах
APP_API-типов, и `INTERNAL` в этой позиции был бы скрытой утечкой реализации в API.

**Отклонение от механического среза ревью.** Срез предполагал 13 типов, названных только
тестами приложения, оставить `INTERNAL`. Замер по production-источникам показал другое: 12 из
них называет production-код metadata/data/fetch-конфигураций и form/reportstudio-слоя, то есть
по правилу 3 они `MODULE_API`. Роль legacy-доступа от этого не меняется: приложение (main) их
не называет, ссылки тестов заморожены отдельным overlay — 12 записей.

Черновик реестра воспроизводится инструментом `scripts/analyze-platform-surface.mjs`
(`--core-registry`, `--delta`, `--strip`), который считает ссылки для обеих сторон и потому
может быть перепроверен независимо от Java-гейта.

### Проверка манифеста больше не требует Maven

Скрипт искал и запускал `mvn` раньше ветки `-ValidateOnly`, поэтому на машине без Maven самая
дешёвая проверка воспроизводимости падала — и выглядело это как дефект манифеста. Теперь
координаты, POM-граф, топология, раскладка и fingerprints проверяются до разрешения
toolchain'а, а JDK/Maven и локальный репозиторий инициализируются только для реальной сборки.

Оба направления закреплены `BootstrapScriptContractTest`: `-ValidateOnly` с несуществующим
`-MavenCommand` обязан завершиться успешно и сказать, что инструментарий не разрешается;
настоящая сборка с тем же аргументом обязана упасть и назвать причину (`Maven executable not
found`), иначе «починка порядка» превратилась бы в отключение сборки.

### Baseline: контракт вместо реализации

Прежний список из пяти артефактов был написан руками и уже разошёлся с поставкой: metadata,
numbering, settings, telemetry, rls и оба leaf-контракта публиковались без фиксации
поверхности. Теперь список выводится из манифеста плюс явно названные leaf-контракты соседнего
реактора `crudui` — 11 артефактов; тест падает и на артефакте без baseline, и на baseline без
артефакта.

Политика фиксации записана в шапке каждого baseline-файла. `platform-core` фиксируется по
семантическим ролям (типы с ролью `INTERNAL` в baseline не попадают — реализацию можно менять
свободно), остальные десять помечены `TEMPORARY_ALL_PUBLIC`: до семантической классификации их
поверхность приходится морозить целиком. Тест отдельно требует, чтобы в режиме
`TEMPORARY_ALL_PUBLIC` оставались только неклassифицированные артефакты и чтобы `SEMANTIC_ROLES`
применялся ровно к `platform-core`: временный режим обязан быть виден и сокращаться, а не
расползаться на новые модули.

### Долги, которые эти правки фиксируют, а не скрывают

* **Owner-side тесты.** У `platform-core` (116 тестов) и `platform-persistence` (10) тесты свои,
  у `platform-telemetry` — включены сейчас; у `platform-metadata`, `platform-numbering`,
  `platform-settings`, `platform-rls` своих тестов нет, и manifest собирает их с `-DskipTests`.
  Это зарегистрированный долг D3.9 — снятие `-DskipTests` возможно только после переезда тестов
  к владельцу, иначе флаг лишь создаёт видимость верификации.
* **`TEMPORARY_ALL_PUBLIC` у 10 из 11 артефактов** — снимается вместе с семантической
  классификацией остальных модулей (D3.9).
* **Внешние форки** (`dynamicreports`, `ureport3`) дрейфуют относительно записанных fingerprints;
  политика — внешние библиотеки, прогон в этом воркспейсе идёт с `-AllowSourceDrift` (§2.5).
* **Overlay тестов** покрывает только `platform-core`. Ссылки тестов приложения на внутренности
  `platform-events`, `platform-rls`, `platform-telemetry`, `reportstudio` ещё не заморожены; до
  независимого релиза модулей их нужно либо снять, либо зафиксировать тем же механизмом.
* **Legacy-overlay `platform-core`** (72 ссылки после снятия §2.8) обязан опустеть:
  `LookupService`/`ServiceLocator` снимаются в D3.5, report-контракты — в D3.7.

### 2.8. Снятие legacy-ссылок до D3.4 (§4 плана D3)

**Статус:** выполнено. Из пяти пунктов плана §4 закрыты четыре, пятый отпал как выполненный
заранее. Бюджет legacy-ссылок: 74 → 72, обе записи удалены из D1-реестра (только уменьшение).

1. **`NaturalKeyCreateSupport` → `APP_API`.** Роль была исправлена ещё на этапе классификации
   (роль `legacy-internal` не сходилась с фактическим употреблением — generic механизм,
   намеренно используемый прикладными interned use cases). Здесь отмечена только его связь с §4.
2. **`CurrentUser` удалён из `platform-core`** (93 → 92 типа, пакет `org.ipro.security` исчез):
   * `CanonicalReadExecutor` получил `RlsCurrentUser` через конструктор (тот же SPI, что у
     остальных RLS-коллабораторов); `DataAccessAutoConfiguration` передаёт бин и требует его
     через `@ConditionalOnBean` — отсутствие `RlsCurrentUser` теперь ошибка wiring, а не
     тихий fallback на статическое чтение SecurityContext;
   * `SecurityRlsUser` читает Spring Security context напрямую (та же логика anonymous →
     «system»); прикладные `GridFormViewService`/`GridFormViewLifecycle` получают
     `RlsCurrentUser` инъекцией; `ReportRunDialog.emptyContext()` (не-бин) читает
     SecurityContext локально;
   * платформа больше не владеет статическим доступом к SecurityContext: чтение
     SecurityContext из `platform-core` отсутствует (проверка — отсутствие пакета
     `org.ipro.security`).
3. **`GlobalSearchProvider.classify(...)` стал default-методом** со стандартной
   классификацией EXACT/PREFIX/SUBSTRING (перенесена из `JpaGlobalSearchProvider` без
   изменения поведения). Кастомные провайдеры больше не обязаны копировать правило или
   делегировать implementation-классу.
4. **`PrdSpecGlobalSearchProvider` больше не делегирует** `JpaGlobalSearchProvider`:
   наследует default-классификацию и удаляет последнюю legacy-ссылку приложения на
   INTERNAL-тип. `JpaGlobalSearchProvider` остаётся внутренней default-реализацией registry.
5. **«LookupService/ServiceLocator в D3.5» и report-ссылки в D3.7** — не относятся к «до
   D3.4», остаются по плану.

Обновлённые артефакты следования: baseline `platform-core` (default-метод `classify`,
конструкторы `CanonicalReadExecutor`), замеры D1 (`named-platform-types` 206 → 204,
`platform-artifact-files` 265 → 264, `legacy-internal-types` 74 → 72), fingerprint
`platform-core` (118 → 117 файлов).

### Найдено попутно

Новый реестр был исключён правилом `.gitignore` — то есть reviewed-список не попал бы в поставку,
а гейт защищал бы только машину автора. Исключение добавлено; это третий случай за D-этапы,
когда широкое правило (`*.md`, `*.txt`) скрывало часть поставки, и третий раз причиной была не
ошибка гейта, а отсутствующее исключение.

### 2.9. D3.4 — вынос Spring Boot auto-configuration

D3.4 закрывает регистрацию backend-контуров платформы как отдельный поставляемый артефакт
`org.ipro:platform-spring-boot-autoconfigure:1.0-SNAPSHOT`. В sibling-модуль перенесены пять
конфигураций: metadata, CRUD, data access, fetch/instance-name и global search. Модуль
сам содержит `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
а соответствующие записи удалены из application imports, поэтому ownership регистрации
совпадает с ownership кода.

`PlatformProperties` — единственный typed bean для `platform.subsystem-scan-package`.
Default отсутствует: при отсутствии или пустом значении конфигурация падает с явной ошибкой.
Metadata и Vaadin explorer используют этот bean; `@Value` для данного свойства не осталось.

Закрытие подтверждено тестами модуля (`28/0/0`), D3.4-related root gates (`63/0/0`),
полным random-order `verify` (`1271/0/0`, без `VisualQuerySubqueryIT` по принятому исключению),
bootstrap validation manifest/fingerprints и `git diff --check`.

---

## 3. Решённые вопросы (ранее открытые)

1. **FQN `IdentifiableEntity`.** Решено: нейтральный `org.ipro.identity.IdentifiableEntity` в
   артефакте `platform-identity-api`; внешних бинарных потребителей нет, переименование не
   требует major-цикла. План допускал оба варианта (§3 плана), выбран тот, что убирает понятие
   «identity-тип живёт в UI-артефакте».
2. **Где живут новые модули.** Решено: отдельные проекты в этом чекауте, как существующие
   `platform-*`, с записью в bootstrap-manifest — иначе исчезает единственный автоматический
   гейт воспроизводимости. Leaf-контракты (`platform-identity-api`, `platform-crud-api`) — в
   реакторе соседнего `crudui`, потому что от них зависит сам `crudui-core`.
3. **Имя пакета UI-границы.** Решено: `org.ipro.vaadin.*` (search, explorer), как принято в D3.2.
   Альтернатива (держать UI-под историческими пакетами) дешевле по диффу, но не выражает
   границу в коде.

---

## 4. Порядок дальше

D3.0–D3.4 закрыты; дальше — по плану: D3.5
(`platform-vaadin`), D3.6 (консолидация редакторов), D3.7 (report-модули), D3.8 (BOM + starters +
переключение приложения), D3.9 (governance и документация) — с долгами из §2.7.

Ближайший шаг D3.5 начинается с двух вещей, которые упрощают его вдвое:
`platform-core` уже публикует роли (`MODULE_API` — это и есть перечень того, что нужно
autoconfigure), а `EntityDataAccess` повышен до `APP_API`, поэтому `JpqlDatasetRunner` и
прикладные адаптеры остаются реализациями приложения, а не переносятся в модуль.
