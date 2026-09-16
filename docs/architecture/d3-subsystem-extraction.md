# Мост D2 → D3: вынос платформенных подсистем

**Статус:** в работе. Срезы 5 (метаданные), 6 (нумерация), 7 (`settings`), 8а
(`platform-telemetry`) и 8б (`platform-rls`) закрыты. Последним — `reportstudio`.

## 0. Зачем это между D2 и D3

D2 ответил на вопрос «граница существует?» — четырьмя срезами, из которых два несли
контракты, один runtime-контур и один persistence-капсулу. Но D2 нёс материал, выбранный по
признаку «удобно и безопасно», а не по признаку «подсистема». Между D2 и полноценным D3
(адаптеры, starter, reference app) оставался неотвеченный вопрос: **подсистема платформы
действительно может жить вне дерева или это удалось только с типами без бинов, без сущностей
и без рефлексии?**

Этот документ фиксирует порядок ответа на него. Порядок задан замером, а не предпочтением.

## 1. Правило замыкания: чем измеряется готовность

Persistence-срез D2 установил ограничение, которое решает всё остальное: **модуль не может
ссылаться на дерево приложения**. Приложение зависит от модуля, поэтому обратная ссылка
замкнула бы сборку — именно поэтому `BaseEntity` пришлось везти вместе с сущностью.

Значит, готовность подсистемы к выносу измеряется её **замыканием**: типами дерева,
достижимыми транзитивно от её пакета. Не числом прямых импортов (важен транзитивный хвост) и
не размером подсистемы (`reportstudio` — 107 файлов, но его решение зависит от чужих
подсистем, а не от собственного объёма).

## 2. Замер на момент D2

| Подсистема | Файлов | Замыкание на дерево | Кто именно |
|---|---|---|---|
| `org.ipro.numbering` | 17 | **1** | `metadata.AnnotationClassScanner` |
| `org.ipro.settings` | 8 | **2** | `metadata.AnnotationClassScanner`, `metadata.ReferenceIndex` |
| `org.ipro.telemetry` | 64 | **1** | `rls.RlsStatementGuard` |
| `org.ipro.rls` | 33 | **17** | `metadata` (6), `crud` (3), `numbering` (4), `telemetry.api`+`core` (4) |
| `org.ipro.reportstudio` | 107 | **42** | `crud` (8), `rls` (8), `metadata` (8), `data` (4), `form` (3), `telemetry`, `security`, `fetch` |

Отсюда порядок: `numbering` (1) → `settings` (2) → `telemetry` (1, но в паре с `rls` из-за
цикла) → `rls` (17) → `reportstudio` (42).

Два вывода, которые замер сделал очевидными и которые нельзя было получить чтением кода:

1. **Весь упор подсистем в метаданные — это два типа.** Не «пакет метаданных», а сканер и
   индекс ссылок; остальные 35 классов `org.ipro.metadata` подсистемам не нужны.
2. **`telemetry` и `rls` образуют цикл по одному классу в каждую сторону**:
   `telemetry.core.SqlStatementInspector → rls.RlsStatementGuard` и
   `rls.RlsPolicyEnforcer → telemetry.core.SecurityEventLogger`. Ни одну из двух нельзя
   вынести первой, пока одна из связей не вывернута через контракт. Это находка, а не
   препятствие: она относится к классу «внутри дерева незаметно, на границе модуля — блокер».

Замер стал исполняемым правилом: `PlatformSubsystemClosureTest` сверяет замыкание каждой
подсистемы с reviewed-реестром, shrink-only в обе стороны. Рост означает новый импорт
подсистемы в приложение, сокращение — состоявшийся срез, который надо снять из реестра.

**Важно при чтении таблицы.** Числа выше измерены только по `import` и поэтому занижены —
см. §6: тот же замер, исправленный, даёт для телеметрии 32 типа вместо одного. Таблица
оставлена как исторический срез: она объясняет, почему срезы шли в этом порядке, и не
претендует на полноту.

## 3. Срез 5: позвоночник метаданных — `platform-metadata`

Вынесено **два типа** (`AnnotationClassScanner`, `ReferenceIndex`), а не `org.ipro.metadata`.
Модуль не несёт авто-конфигурации: бин `ReferenceIndex` объявляет
`MetadataAutoConfiguration` приложения, поэтому потеря артефакта ломает сборку, а не
проявляется тихо как «каталог пуст».

Побочный результат, которого не было в плане: у `ReferenceIndex` был `@Value` с default
`org.ip`, хотя бин всегда создаётся с явным значением из конфигурации — то есть
мёртвый default, единственное место, где платформенный артефакт называл имя прикладного
пакета. Default снят вместе с переносом, и D1-реестр строковых связок **сократился с 13
файлов до 12** без переписывания причины. Тот же снятый default обнаружен и снят в
`NumberingMetadataRegistry` (срез 6) — реестр дошёл до **11 файлов / 11 литералов**.

Дополнительно забор расширен: `PlatformStringDependencyTest` теперь сканирует и исходники
платформенных артефактов, а не только дерево. После D2 часть платформы лежит вне дерева, и
строковая связка там заметнее не становится — наоборот, модуль читается как чужая
зависимость.

## 4. Срез 6: `platform-numbering` — первый модуль-подсистема

Вынесена подсистема целиком (17 типов): аннотации (`@Numbered`, `@NumberingRole`,
`@NumberingPolicy`), правила и счётчики с репозиториями, сервисы, резолвер scope и
авто-конфигурация. Реестр замыканий после среза: `numbering` из него снят.

Что этот срез проверяет такого, чего не проверяли предыдущие четыре:

| Свойство | Кто проверял раньше | Что даёт нумерация |
|---|---|---|
| Типы видны компилятору только через артефакт | contracts | + сущности и репозитории |
| Модуль сам регистрирует свои бины | events | + сущности, репозитории и пакеты сканирования |
| Модуль сам объявляет свои `@EntityScan`/`@EnableJpaRepositories` | persistence | + авто-конфигурация из шести бинов |
| Рефлексия читает аннотации с артефакта | — | **впервые**: `@Numbered` стоит на сущностях *приложения*, сканирует их `platform-metadata` |
| Направление «верх → низ» держится | — | **впервые**: `rls` зависит на нумерацию (даёт ей `NumberingScopeResolver`), а не наоборот |

Как проверялось:

```
mvn -o -f platform-numbering/pom.xml -DskipTests clean install   BUILD SUCCESS (17 типов)
mvn -o clean verify                                              1359 тестов, 0 failures/errors/skipped
random-order gate                                                1359 тестов, 0 failures/errors (seed 20260915)
bootstrap -ValidateOnly                                          OK fingerprint: пять платформенных модулей
```

Приложение после среза **не потеряло ни одной регистрации и не приобрело ни одной**:
`org.ipro.numbering` убран из `@EntityScan` приложения и из списка платформенного хаба
`RlsAutoConfiguration` (тот перечисляет только то, что ещё живёт в дереве), а запись
`NumberingAutoConfiguration` ушла из imports-файла приложения в собственный файл модуля.
Реестр авто-конфигураций (`PlatformAutoConfigurationRegistryTest`) проверяет это по всем
артефактам сразу: каждая авто-конфигурация зарегистрирована ровно один раз и именно своим
артефактом.

## 5. Срез 7: `platform-settings` — подсистема, которую сломали дважды

Вынесено **8 типов**: сущность `SettingValue` с репозиторием, `SettingsRegistry` (вместе с
`FieldDescriptor`/`GroupInfo`), `SettingsService`, `SettingsReverseReferenceSource`, каталог
`Setting`/`SettingsGroup` и своя авто-конфигурация с тремя бинами. Реестр замыканий после
среза: `settings` из него снят.

Что срез проверяет сверх шестого: подсистема **сканирует пакет приложения** — каталог
констант читает `@Setting`/`@Subsystem` с прикладных классов через `platform-metadata` — а её
сущность участвует в **обратных ссылках** (`SettingValue.entityRefId` ссылается на прикладную
сущность). То есть модуль не только читает свои аннотации, но и работает с моделью
приложения как с данными.

Главный результат среза — не сам вынос, а то, что его **не подтвердил ни один тест модуля**.
Обе регрессии нашёл полный прогон:

| Регрессия | Как проявилась | Почему не была видна раньше |
|---|---|---|
| `SettingsServiceSliceIT` (9 методов) | `Not a managed type: class org.ipro.settings.SettingValue` | `@DataJpaTest` отключает авто-конфигурации: срез перечислял пакет модуля сам и тем самым терял его `@EntityScan` |
| `RlsIntegrationTest.deletionBlockedWhenSettingReferencesEntity` | `Unknown entity type 'org.ipro.settings.SettingValue'` | срез ссылается на сущность модуля из кода (`new org.ipro.settings.SettingValue(...)`), не называя пакет ни в аннотации, ни строкой |

Обе — один класс ошибки: **срез не подключает регистрацию модуля, а имитирует её сам**. При
выносе нумерации это лечили правкой одного теста; теперь это правило.

Правило живёт в `PersistenceTypeRegistrationTest` и не знает заранее ни одного имени: модули
берутся из их собственных объявлений (артефакт — саморегистрирующийся, если объявил
`@EntityScan` и `@EnableJpaRepositories`, а его persistence-типы и классы-регистраторы
выводятся из того же обхода). От каждого `@DataJpaTest`-среза требуется: трогаешь
persistence-тип модуля — полным именем, импортом или строковым литералом пакета —
**используй** его регистрацию (импорт без использования не считается: он ничего не
подключает). Ссылка на бин модуля поводом не является: `CanonicalWritePathIT` мокает
`NumberingService` и не нуждается ни в одной сущности нумерации — иначе правило давало бы
ложное срабатывание.

Проверялось негативно в обе стороны: снятие `@ImportAutoConfiguration(...)` из
`SettingsServiceSliceIT` роняет правило с точным сообщением, а сам полный прогон нашёл
`RlsIntegrationTest` до того, как о нём знал тест (правило было обобщено после того, как
падение стало известно из `mvn verify`, и следующий прогон нашёл его уже сам).

```
mvn -o -f platform-settings/pom.xml clean install   BUILD SUCCESS (8 типов)
mvn -o clean verify                                 1365 тестов, 0 failures/errors/skipped
random-order gate                                   1365 тестов, 0 failures/errors (seed 20260915)
```

## 6. Шаг 8: пара `telemetry` + `rls` — начали с честного замера

Порядок работ задавал замер замыкания, и замер оказался неверным. Он читал только `import`,
поэтому не видел двух вещей: ссылки полным именем в теле (а конфигурации платформы пишут
именно так — `org.ipro.rls.RlsBypassAudit` в типе возврата бина телеметрии) и однопачечные
зависимости посещённого типа (файл не импортирует то, что лежит в его собственном пакете,
поэтому обход останавливался на границе пакета).

Из-за второго `telemetry` выглядела как «один тип до независимости», хотя тянула пакет RLS
целиком. Честный замер (импорт + полное имя вне строкового литерала + простое имя своего
пакета) дал другую картину:

| Подсистема | В реестре было | Честно до выворота | После выворота |
|---|---|---|---|
| `telemetry` | 1 | **32** | **0** |
| `rls` | 13 | **29** | **31** |
| `reportstudio` | 42 | **92** | **93** |

Вывод, который меняет не только числа: «одна связь цикла» была формулировкой замера, а не
архитектуры. Связей наблюдения с деревом оказалось три, и телеметрия тянула метаданные и
нумерацию транзитивно — через канарейку RLS и через мост имён.

### Что вывернуто

| Связь | Было | Стало |
|---|---|---|
| SQL-стейтменты | `SqlStatementInspector` звал `org.ipro.rls.RlsStatementGuard.inspect(...)` | инспектор зовёт `telemetry.api.SqlStatementAudit` через `SqlStatementAuditBridge`, канарейку в шов ставит конфигурация RLS |
| Привилегированные окна | `TelemetryAutoConfiguration` сама создавала бин `org.ipro.rls.RlsBypassAudit` | бин переехал в `RlsAutoConfiguration`: RLS знает телеметрию, телеметрия RLS — нет |
| Объявленное имя сущности | `EntitySnapshot` звал статину `org.ipro.fetch.instance.InstanceNameBridge.declaredName(...)` | шов `telemetry.api.DeclaredNameSource`; реализацию (fetch-мост имён) ставит `InstanceNameBridgeInstaller` вместе со своим мостом |

Направление выбрано по слою, а не по цене. Наблюдение обязано работать без принуждения: до
выворота телеметрия физически не собиралась без RLS. Обратное направление (`rls` вниз) не
даёт выносимого модуля — у RLS оставалось 31 тип дерева (22 после выноса телеметрии),
и первым он не поедет.

### Что нашлось при этом

- **Одна ячейка состояния в шве — ошибка, а не упрощение.** Первая версия
  `DeclaredNameBridge` держала один `volatile` источник и стирала его на любом `destroy()`.
  В общем прогоне это уронило `InstanceNamePilotIT`: контексты живут в одной JVM и делят
  статику, закрытие соседнего обнуляло регистрацию живого. Швы переведены на
  регистрационный список — снятие адресное (по объекту), как в `InstanceNameBridge`.
- **Шов обязан работать без слушателя.** Инспектор вызывает наблюдателей до своих ранних
  выходов по `TelemetryGuard`: канарейка RLS живёт независимо от того, включена ли
  телеметрия (свойство было и до выворота — его пришлось сохранить, а не получить бонусом).
- **Половины утверждения проверяются в разных местах.** «У телеметрии нет ссылок на RLS» —
  это `PlatformSubsystemClosureTest` (запись `telemetry` = пусто); «канарейка стоит в шве» —
  `RlsStatementGuardTest.canaryIsRegisteredThroughTheTelemetrySeam`. Одна половина без
  другой ничего не стоит: пустой шов тоже дал бы нулевое замыкание.

```
mvn -o clean verify      1369 тестов, 0 failures/errors/skipped
random-order gate        1369 тестов, 0 failures/errors (seed 20260915)
```

### Срез 8а: `platform-telemetry` — третий модуль-подсистема

Вынесено целиком наблюдение (**66 типов**): API (включая нейтральные швы
`SqlStatementAudit`/`DeclaredNameSource`), core, сущности журнала, репозиторий и своя
авто-конфигурация с `@EntityScan("org.ipro.telemetry.model")` /
`@EnableJpaRepositories("org.ipro.telemetry.repository")` и собственным imports-файлом.
Vaadin-адаптеры (`TelemetryVaadinInitListener`, `TelemetryErrorHandler`) в модуль не
вошли — живут в дереве приложения (`org.ip.telemetry.vaadin`, решение 1 этапа A);
`Application` и хаб `RlsAutoConfiguration` пакеты модуля больше не перечисляют.
Unit-тесты без Spring (`TelemetrySeamBridgesTest`, `JournalSearchServiceTest`, 8 методов)
переехали в модуль, интеграционные остались в приложении. Зависит на
`platform-settings` (пробная сущность `FieldAuditSelfTest` — `SettingValue`), дальше
только библиотеки; reviewed-зависимости — в `PlatformTelemetryModuleTest`.

Что нашлось при этом (честная сборка видит то, чего не видит замер по исходникам):

- **`FieldAuditSelfTest` тянул `platform-settings` полным именем в теле**
  (`org.ipro.settings.SettingValue`) — import-замер по дереву этого не показал, т.к.
  `settings` уже артефакт. Зависимость честная (`telemetry → settings`, верх на низ,
  цикла нет) и зафиксирована в `pom` + `PlatformTelemetryModuleTest`, а не спрятана.
- **`AppLifecycleLogger` звал `com.vaadin.flow.server.Version` полным именем** —
  для `javac` это compile-зависимость, `try/catch` не спасает. Версия Vaadin теперь
  читается рефлексией (`Class.forName`), без Vaadin в classpath — `"?"`.
- **Строковый реестр тоже переезжает**: запись pointcut
  `"execution(* org.ip.service..*(..))"` сменила ключ с `src/main/...` на
  `platform-telemetry/src/main/...` (`PlatformStringDependencyTest` сканирует и
  артефакты).

```
mvn -o -f platform-telemetry/pom.xml -DskipTests clean install   BUILD SUCCESS (66 типов)
mvn -o -f platform-telemetry/pom.xml test                         8 тестов, 0 failures/errors
mvn -o clean verify                                                1367 тестов, 0 failures/errors/skipped
random-order gate                                                  1367 тестов, 0 failures/errors (seed 20260915)
bootstrap -ValidateOnly                                            OK fingerprint: семь платформенных модулей
```

Приложение после среза **не потеряло ни одной регистрации и не приобрело ни одной**:
`org.ipro.telemetry.model` убран из `@EntityScan` приложения,
`org.ipro.telemetry.repository` — из хаба `RlsAutoConfiguration`, запись
`TelemetryAutoConfiguration` — из imports-файла приложения в собственный файл модуля.
Реестр замыканий после среза: запись `telemetry` снята (пакета в дереве нет), `rls`
`31 → 22`, `reportstudio` `93 → 85` (типы телеметрии больше не типы дерева).

### Срез 8б: `platform-rls` — четвёртый модуль-подсистема

Вынесен целиком RLS (**36 типов**: 33 класса подсистемы, 2 нейтральных SPI,
`RlsPersistenceAutoConfiguration`) с двумя авто-конфигурациями: `RlsAutoConfiguration`
(бины принуждения) и `RlsPersistenceAutoConfiguration`
(`@EntityScan`/`@EnableJpaRepositories("org.ipro.rls")`) — чтобы `@DataJpaTest`-срезы
подключали только persistence-часть. Зависимости на metadata/fetch заменены SPI
(`RlsDimensionValueLabelResolver`, `RlsOwnedSectionLookup`), которые реализует
приложение: lookup — в `MetadataAutoConfiguration` (только metadata-типы, запрет
`metadata → fetch` соблюдён), label-резолвер — в `FetchPlanInstanceNameAutoConfiguration`
(там уже есть `MetadataResolver`, направление `fetch → metadata` разрешено, backoff
сохранён через `@ConditionalOnBean`). Без адаптера контекст не стартует (fail-fast
вместо silent fail-open). Свойство `rls.dimension-scan-package` без default — задаёт
приложение. Заодно хаб разгружен: reportstudio и ureport объявляют свои
persistence-пакеты сами (`ReportStudioPersistenceAutoConfiguration`,
`UreportPersistenceAutoConfiguration`), `Application` — только `org.ip.model`.

Что нашлось при этом:

- **13 `@DataJpaTest`-срезов имитировали регистрацию RLS** ручным
  `@EnableJpaRepositories({"org.ipro.rls"})` — заменены на
  `@ImportAutoConfiguration(RlsPersistenceAutoConfiguration.class)`; правило
  `PersistenceTypeRegistrationTest` перечислило их само.
- **Строковый реестр тоже переезжает**: default `:org.ip` снят и в реестре, и в
  авто-конфигурации; свойство задаёт приложение.
- **BOM-ловушка**: правка файлов через PowerShell `Set-Content`/`Add-Content` пишет BOM,
  а проект собирается в Cp1251 — `javac` падает с `illegal character: '\ufeff'`.
  Править только редактором или `[System.IO.File]` + явное удаление BOM.

```
mvn -o -f platform-rls/pom.xml -DskipTests clean install   BUILD SUCCESS (36 типов)
mvn -o clean verify                                         1372 тестов, 0 failures/errors/skipped
random-order gate                                           1372 тестов, 0 failures/errors (seed 20260915)
bootstrap -ValidateOnly                                     OK fingerprint: восемь платформенных модулей
```

Приложение после среза **не потеряло ни одной регистрации и не приобрело ни одной**:
`org.ipro.rls` убран из `@EntityScan` приложения, `org.ipro.reportstudio`/`org.ipro.ureport`
— из хаба `RlsAutoConfiguration` в собственные persistence-конфигурации, запись
`RlsAutoConfiguration` — из imports-файла приложения в собственный файл модуля.
Реестр замыканий после среза: запись `rls` снята (пакета в дереве нет), `reportstudio`
минус 24 типа `org.ipro.rls.*`.

## 7. Что осталось и в каком порядке

| Шаг | Подсистема | Условие готовности | Открытый вопрос |
|---|---|---|---|
| ~~7~~ | ~~`settings`~~ | **закрыт, см. §5** | — |
| ~~8а (цикл)~~ | ~~`telemetry`~~ | **цикл вывернут, замыкание пусто (см. §6)** | — |
| ~~8а (срез)~~ | ~~`platform-telemetry`~~ | **закрыт: 66 типов, свой imports-файл, Vaadin-адаптеры в `org.ip` (см. §6.1)** | — |
| ~~8б~~ | ~~`platform-rls`~~ | **закрыт: 36 типов, два SPI, две авто-конфигурации (см. §6.2)** | — |
| 8 | `telemetry` + `rls` (пара) | обе связи цикла вывернуты, оба модуля вынесены | — |
| 9 | `reportstudio` | 61 тип замыкания + консолидация редакторов | три параллельные реализации редактора отчётов надо свести к одной **до** выноса: иначе избыточность зацементируется границей модуля, а кросс-модульный рефакторинг дороже внутреннего |

Зарегистрированные задачи D3 (не сделаны сознательно):

1. ~~**`platform.subsystem-scan-package` default в конфигурацию приложения.**~~ Снято
   полностью (шаг D3-зачистки): default-значения `platform.subsystem-scan-package`
   (4 класса метаданных + нумерация), `settings.scan-package` и строковый pointcut
   `org.ip.service..*` (переход на маркер `@Measured`, помечены 9 классов
   `org.ip.service`) устранены; свойства задаёт приложение. String-реестр пуст.
2. **Свести `IdentifiableEntity` и `BaseEntity`.** `org.ipro.crud` разделён между тремя
   артефактами, причём `IdentifiableEntity` живёт в другом репозитории (`crudui`).
   Попытка переноса в `platform-contracts` отменена владельцем: границы дженериков
   `crudui-core` (`AbstractCrudView`/`AbstractEntityForm`/`CrudService`) ссылаются на
   этот тип, и переезд тянет зависимость `crudui-core → platform-contracts` через
   границу репозиториев. Владелец — `crudui-core`, решение отложено; статус-кво
   восстановлен byte-identical (проверено diff + verify).
3. **Консолидировать редакторы `reportstudio`** до его выноса (шаг 9).
4. ~~**Вывернуть связь цикла `rls` ↔ `telemetry`** перед шагом 8.~~ Сделано полностью:
   шаг 8а (§6) вывернул цикл, срез 8а (§6.1) вынес `platform-telemetry` (66 типов),
   срез 8б (§6.2) вынес `platform-rls` (36 типов). RLS опирается на телеметрию как
   верхний слой на артефакт — это направление законно и остаётся.
5. ~~**Вынести `telemetry` (шаг 8а).**~~ Закрыт (§6.1): Vaadin-адаптеры
   (`TelemetryErrorHandler`, `TelemetryVaadinInitListener`) остались в дереве в пакете
   приложения `org.ip.telemetry.vaadin`, модуль зависимости на UI не имеет.

## 8. Инвентарь на текущий момент

| Артефакт | Типов | Что несёт | Зависит на |
|---|---|---|---|
| `platform-contracts` | 34 | аннотации метаданных, event/lifecycle SPI, нейтральные identifiers | `crudui-core`, `slf4j-api` |
| `platform-metadata` | 2 | скан по аннотации, индекс обратных ссылок | `platform-contracts` |
| `platform-persistence` | 4 | сущность `JrxmlTemplate`, её репозиторий, `BaseEntity`, своя регистрация | `crudui` |
| `platform-events` | 3 | publisher событий, fail-fast registry, своя авто-конфигурация | `platform-contracts` |
| `platform-numbering` | 17 | подсистема нумерации целиком | `platform-contracts`, `platform-metadata`, `platform-persistence` |
| `platform-settings` | 8 | сущность константы с репозиторием, каталог, сервис, источник обратных ссылок, своя авто-конфигурация | `platform-contracts`, `platform-metadata`, `crudui` |
| `platform-telemetry` | 66 | наблюдение целиком: API со швами, core, сущности журнала, репозиторий, своя авто-конфигурация | `platform-settings`, JPA/Hibernate, Spring Data/JDBC/TX/Web/Security, Jackson, Logback |
| `platform-rls` | 36 | RLS целиком: гейты, аспект, канарейка, 2 SPI, гранты, две авто-конфигурации | `platform-persistence`, `platform-numbering`, `platform-telemetry`, JPA/Hibernate/validation, Spring Data/TX/Web/Security, AspectJ |

Распределение типов платформы, которые называет приложение: **42** в дереве, **21** в
контрактах, **7** в нумерации, **6** в константах, **2** в persistence, **66** в
телеметрии, **36** в RLS, **15** во внешних артефактах (+2 Vaadin-адаптера телеметрии
уехали в приложение). Платформенных файлов в дереве: **303** (было 336 до среза, 404
до telemetry, 469 на момент D1, 431 после D2).

## 9. Честные оговорки

- **`reportstudio` — самая дорогая часть и самая рискованная**: 61 тип замыкания включает
  `crud.BaseService`/`ServiceLocator`/`ValidatedJpaCrudService` и `data.CanonicalReadExecutor`,
  то есть вынос отчётов тянет за собой определение самой сервисной границы — это уже предмет
  D3, а не моста.
- **`telemetry` как отдельный модуль без `rls` оказался возможен, но не так дёшево, как
  следовало из замера.** Замер показывал замыкание 1 и «одно класс в каждую сторону»; честный
  разбор дал три связи и 32 типа, включая транзитивные метаданные. Выворот сделан целиком до
  переноса типов — именно потому, что вынос подсистемы с невидимой зависимостью ломается не
  на сборке, а в рантайме.
- **Тестовое дерево разделяет пакеты с модулями** (`org.ipro.numbering` есть и в
  `src/test`, и в `platform-numbering`). На classpath это работает и package-private доступ
  сохраняется, но при переходе на module path потребуется переименование — тот же класс
  проблем, что у split package `org.ipro.crud`.
