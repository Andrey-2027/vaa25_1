# D3: вынос платформы в модули — состояние и корректировки плана

**Статус:** в работе. Закрыто: D3.0 (воспроизводимый bootstrap, включая остатки: GAV,
сверка POM-графа без сборки, пустой локальный репозиторий), D3.1 (identity seam и владение
событиями; решение по FQN принято — `org.ipro.identity`), D3.2 (разрыв core/UI-связок).
D3.3 начат: каркас `platform-core` и первый срез (§2.6); главная группа — 78 классов
`crud`/`data`/`fetch`/`metadata`, они не разделяются.

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

## 3. Открытые решения

1. **FQN `IdentifiableEntity`.** План требует `org.ipro.crud.IdentifiableEntity`; на диске
   `org.ipro.identity.IdentifiableEntity`. Внешних бинарных потребителей нет, но это публичный
   контракт `crudui`.
2. **Где живут новые модули.** Рекомендация: отдельные проекты в этом чекауте, как
   существующие `platform-*`, с записью в bootstrap-manifest — иначе исчезает единственный
   автоматический гейт воспроизводимости.
3. **Имя пакета UI-границы.** В D3.2 принято `org.ipro.vaadin.*` (search, explorer).
   Альтернатива — держать UI-классы под их историческими пакетами и разделять модули только
   артефактами; это дешевле по диффу, но не даёт выразить границу в коде.

---

## 4. Порядок дальше

Согласен с планом: D3.3 (`platform-core`), затем D3.4 (autoconfigure), D3.5 (`platform-vaadin`),
D3.6 (консолидация редакторов), D3.7 (report-модули), D3.8 (BOM + starters + переключение
приложения), D3.9 (governance и документация). Изменения к этому порядку:

* остатки D3.0 (GAV, сверка POM-графа без сборки, чистый локальный репозиторий) — до D3.3,
  потому что каждый новый модуль добавляет в manifest новое ребро;
* D3.2 выполнен до D3.3 (как и планировалось) и подтвердил, что переносить core можно
  пакетными группами без переписывания внутренней архитектуры CRUD/data.
