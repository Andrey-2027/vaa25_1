# План выноса механизма регистра сведений в платформу

- Дата: 2026-10-13.
- Статус: план работ. Решения о модулях и о lifecycle строки требуют ADR (см. §3); до их
  принятия разрешены только работы S0, не создающие артефакта с бинами.
- Основание: [замер замыкания](information-register-extraction-slice.md) (движок `org.ipro.register`
  не называет ни одного прикладного типа); [ADR-0013 §5](decisions/ADR-0013-information-register-v1.md)
  (платформенная природа инфраструктуры — осознанный временный долг);
  [план §14 (Р10)](information-register-development-plan.md) (условия повышения статуса);
  [план регистратора](information-register-recorder-plan.md) (Г-серия);
  [мост D2 → D3](d3-subsystem-extraction.md) (методика выноса и найденные классы ошибок).

## 1. Предмет и границы

Выносится **общий механизм** регистра: декларация, чтение, запись по ключу, протокол набора
владельца и стандартный интерфейс. Остаётся приложению **предметное**: сущности, их lifecycle,
сервисы, политика чтения отчётов и собственно формирование записей.

| Часть | В платформенный модуль | Остаётся приложению |
| --- | --- | --- |
| Декларация | Аннотации, descriptor, валидатор, базовые классы, периодичность, `SliceSpec` | Сущность регистра, измерения, ресурсы, JPA mapping, смежные справочники |
| Чтение | Реестр, стартовая проверка, точечное и срезовое чтение, листовые фазы отбора, **фабрика читателя** | Предметные методы вида «текущий тип учёта номенклатуры в цехе» |
| Запись независимого регистра | `set`/`delete` по ключу, версии, конфликты, повторы, аудит, **декларационные правила ключа** | Допустимость значений и решение, когда вызывать запись |
| Запись по регистратору | Набор владельца, технические поля, транзакционная защита, порядок регистрации, **вызов lifecycle строки** | Какие строки документ формирует и как пересохранение влияет на прежние данные |
| Смена ключа | Команда, право, Hibernate-guard, диалог | Разрешение в декларации и дополнительные предметные ограничения |
| Формы | Стандартный `FormTypeCustomization` регистра, диалог ключа, показ регистратора | Особые колонки, действия и формы конкретной задачи |
| Запросы и отчёты | `SliceSpec` как контракт | Текст отчёта, генератор CTE, политика чтения — отчётный модуль |
| Доступ | C5-операции регистра, RLS-авторизатор набора | Назначение прав пользователям |

**Уточнение против замера.** §4 замера оставлял интерфейс регистра приложению. Проверка по
импортам это опровергает: `RegisterKeyDialog`, `RecorderRefDisplay`, `RecorderRefPresentation`,
`RecorderRefPresentationResolver` не называют ни одного прикладного типа — их держит в дереве
только адрес пакета; прикладные связи есть лишь у `RegisterFormCustomization` (четыре локальных
сотрудника и прямая сборка `RegisterSliceReader` с `EntityManagerFactory`). Поэтому интерфейс
выносится, но **отдельным срезом S4** и после фабрики читателя (S1).

**Модули.** `platform-register` (backend, без UI) и `platform-register-vaadin` (адаптер форм,
Vaadin в scope `provided`), как и предложено. Язык запросов остаётся отчётному модулю — это уже
решение Р4, модуль его не касается. Java-пакеты сохраняются (`org.ipro.register*`): и D2, и замер
показали, что переезд пакета не требует правок у потребителей и не создаёт split package.

## 2. Что уже измерено и что замером не закрывается

Из замера (полностью — [срез контрактов](information-register-extraction-slice.md)): 43 файла
движка, 0 прикладных ссылок; 17 тестов движка, из них 5 файлов с внешними для модуля опорами
(2 импортируют `org.ip.model.*`, 4 — `org.ipro.registerfixtures`, один файл в обоих множествах);
30 контрактных типов, названных вне пакета; 50 файлов потребителей; транзитивное замыкание
253 файла; 1 файл регистрации (`AutoConfiguration.imports`).

Замер **не** закрывает три вещи, и каждая превращена в гейт:

1. **Владение тестами.** Модуль не может владеть тестами, которые импортируют `org.ip.model.*`
   или `org.ipro.registerfixtures`. Нужны модульные фикстуры — тем же приёмом, каким их заводил
   `platform-vaadin` (его `EntityLifecycleRegistryTest` раньше требовал приложение).
2. **Владение сборкой.** `AutoConfiguration.imports` и `@EntityScan("org.ipro.register.persistence")`
   переезжают в модуль; ни одной новой обязательной регистрации в приложении появиться не должно.
3. **Сборка против исходников.** В D3 зависимость `telemetry → settings` нашла сборка, а не
   замер. Поэтому у каждого среза есть негативный гейт сборкой: без артефакта приложение
   не компилируется, без саморегистрации модуля — падает громко.

## 3. Решения, которые принимаются до кода

| Решение | Что фиксирует | Почему ADR, а не строка плана |
| --- | --- | --- |
| **ADR-0024. Платформенный модуль регистра и граница интерфейса** | Координаты и владелец артефактов, сохранение пакетов, необязательность UI-модуля, состав стартеров, роли поверхности, `EntityKind` **не** повышается | Меняет поставку: что приложение получает транзитивно, а что остаётся add-on |
| **ADR-0025. Lifecycle строки регистра на пути регистратора** | Вызываются ли `EntityLifecycle` строки при замене набора владельца; порядок относительно Bean Validation; что показывает диагностика, если хук не вызывается | Меняет контракт ADR-0005 на одном из путей записи и поведение существующих правил |
| **ADR по `EntityKind.INFORMATION_REGISTER` / F-ERP-3** | Повышение статуса | Гейт упирается в отсутствующий в коде второй production-регистр (кандидат `ИзмененияКомпонентовПоРаспространениям` есть только в документах) — решение откладывается, план его не назначает |

Внутри ADR-0024 отдельно решаются три детали: (а) `platform-register` входит в замыкание
`platform-backend-starter`, `platform-register-vaadin` — в `platform-vaadin-starter` (адаптер
инертен, пока регистр не объявлен, поэтому DX-цель «без boilerplate registration» важнее
opt-in-схемы `platform-rest`); (б) роли 30 контрактных типов назначаются до переноса, как в
D3.5.0; (в) `ReportFormSeamTest`/`FormAssemblyPurityTest` не должны ослабляться.

## 4. Срезы

Правило каждого среза: **компиляционная замкнутость** (модуль собирается после среза без
переезда остальных файлов) + **негативный гейт** + **shrinking-реестр**: сужение записывается
явно. Точная раскладка файлов подтверждается сборкой модуля после каждого среза, а не
объявляется заранее.

| Срез | Содержимое | Зависимости | Что доказывает |
| --- | --- | --- | --- |
| **S0. Декларация и контракт строки** | 17 файлов: `RegisterMetadata`, `RegisterPeriodicity`, `RegisterDescriptor` (+`FieldDescriptor`), `RegisterMetadataValidator`, `SliceSpec` (+`Condition`/`Mode`/`Operator`), `SliceSpecValidator`, `RegisterPeriodListener`, `RecorderType`, `RecorderRef`, базовые классы обеих схем (`PeriodicRegisterEntry*`, `RecordedRegisterEntry*`), `RecordedRegisterEntryWriteGuard`, `RecorderSetWriteContext`, `RegisterKeyChangeScope` | `platform-contracts` (аннотации метаданных), `platform-persistence` (`BaseEntity`) — проверено запуском `--slice S0` | Прикладные сущности и отчётный конструктор компилируются против контракта; артефакт без бинов |
| **S1. Реестр, чтение и фабрика читателя** | `RegisterRegistry`, `RegisterStartupValidator`, `RegisterReader`, `RegisterSliceReader`, `RegisterListFilterPhases`, своя read-конфигурация с imports, **фабрика читателя по дескриптору** | S0, `platform-core` (5 типов), `FilterGrid/filtergrid-core` (4 типа) — проверено `--slice S1` | Регистрация падает громко; приложение не приобретает обязательных регистраций; прикладной конфигурационный bean читателя удаляется |
| **S2. Запись по ключу и правила ключа** | `RegisterKeyChanges`, `RegisterKeyWrites`, `RegisterKeyChangePermissions`, `RegisterKeyWritePermissions`, `RegisterKeyWriteGuardInstaller`, `RegisterRowState`, `RegisterWriteMutex`, **декларационный запрет смены ключа и нормализация периода в одной точке**, конфигурация прав и guard | S0–S1, `platform-rls` (C5), `platform-telemetry` (аудит), `platform-events` | ADR-0023 не переписывается; правило ключа перестаёт быть прикладным; порядок «нормализация → сравнение ключа» проверяем |
| **S3. Регистратор и lifecycle строки** | `RecorderSetWriter`, `RecorderSetWriteRequest`, `RecorderSetWriteAuthorizer`, `RecorderSetContribution`, participants, `RecorderSetTransactionExecutor`, `RegisterRecords`, `RecorderTypeRegistry`, `RecordedRegisterReader`, `RlsRecorderSetWriteAuthorizer`, `RecorderOrderClock*`, реализация ADR-0025, конфигурация протокола | S0–S2, `platform-core` (участники агрегата), `platform-rls` | Набор владельца — один протокол; правило строки исполняется там, где обещано, либо отсутствие явно видно диагностике |
| **S4. Стандартный интерфейс** (`platform-register-vaadin`) | `RegisterFormCustomization` (после разделения), `RegisterKeyDialog`, `RecorderRefPresentation`, `RecorderRefDisplay`, `RecorderRefPresentationResolver`; Vaadin `provided`; читатель берётся из фабрики S1 | S1–S2, `platform-vaadin`, `platform-bom` | Приложение с любым интерфейсом не тянет Vaadin; приложение с Vaadin не дублирует стандартные формы |
| **S5. Сокращение обвязки приложения** | (а) стандартные подписи операций из метаданных, (б) поддержанный контекст позиций документа вместо самодельного scope, (в) модульные фикстуры вместо прикладных в тестах движка | S1–S4 | Объём прикладного кода сокращается измеримо; «поведение строки» перестаёт зависеть от ручной обвязки |

### S0 — детали

Проверенная, а не очевидная часть — раскладка подтверждена запуском
`node scripts/analyze-register-closure.mjs --slice S0` (режим добавлен 2026-10-13), а не чтением
кода:

- `RegisterMetadataValidator` ссылается на `RecordedRegisterEntryWriteGuard`, тот — на
  `RecorderSetWriteContext` и `RegisterKeyChangeScope`, а `RecordedRegisterEntryBase` объявляет
  guard через `@EntityListeners`. Поэтому «контракт строки» — компиляционно **одна** группа, и S0
  начинается с неё, а не с одних аннотаций; `RegisterMetadataValidator` переносится из S1 в S0
  по той же причине.
- `SliceSpec.java:72` вызывает `SliceSpecValidator.supportedType(...)` — первая редакция плана
  держала валидатор в S1 и срез не собирался бы. Валидатор перенесён в S0.
- Внешние зависимости среза оказались **меньше** заявленного: `platform-contracts`
  (`FieldMetadata`, `GridColumn`) и `platform-persistence` (`BaseEntity`); `platform-core`
  в S0 не нужен.

Режим `--slice <S0..S4>` печатает состав, ссылки за границу среза и список владельцев-модулей
(это и есть зависимости pom), а на непустом расхождении выходит с кодом 1 — гейт, а не отчёт.
Срез проверяется в порядке выноса: ссылка на уже перенесённый ранний срез — не блокер, а на
более поздний — блокер. Протокол прогона — [раскладка срезов](../plans/register-slices-20261013.log):
S0 — замкнут (17 файлов, `platform-contracts` + `platform-persistence`), S1 — 14 блокеров,
S2 — 1 блокер, S3 и S4 — замкнуты (с учётом перенесённых ранних срезов).

Гейты: модуль компилируется без приложения; `PersistenceTypeRegistrationTest` знает
`org.ipro.register.persistence` как пакет модуля; `PlatformApiBaselineTest` получает
`platform-register.api`; поверхность S0 фиксируется reviewed-реестром (черновик печатает
`scripts/analyze-platform-surface.mjs`, роли назначает ревью).

### S1 — детали

**Фабрика читателя** закрывает сразу два дефекта: приложение объявляет bean
`RegisterSliceReader<NomenclatureAccountingObject>` с `EntityManagerFactory`, а UI его игнорирует
и конструирует читатель сам. После среза `AccountingObjectRegisterConfiguration` удаляется,
прикладной сервис получает читатель по дескриптору, UI — из той же фабрики.

**Потребители в дереве.** 18 main-файлов `reportstudio` (24 вместе с тестами) и 1 тест `crud`
после переноса видят артефакт; правок исходников не требуется, но `PlatformSubsystemClosureTest`
теряет у `reportstudio` записи reach-back **по мере переноса файлов** (реестр shrink-only, см. §5), а
`platform-package-matrix.txt`/`PlatformPackageDependencyMatrixTest` получают новый владелец.

**Проверка раскладки (`--slice S1`) дала 14 блокеров — это и есть содержание работ среза:**

- 12 из них — монолитный `RegisterAutoConfiguration`: он импортирует команды ключа (S2) и
  протокол набора владельца (S3). Поэтому его разделение на срезовые конфигурации — не деталь
  реализации, а условие первого выноса: read-конфигурация S1 не знает ни команд, ни регистратора.
- `RegisterRegistry` → `RecorderTypeRegistry` (S3): реестр разрешает коды регистраторов. Решение
  на старте S1: либо каталог типов переезжает вместе с реестром, либо разрешение кодов получает
  S3-расширение.
- `RegisterStartupValidator` → `RecorderOrderClockBootstrap` (S3): стартовая проверка требует
  bootstrap часов порядка. Решение аналогично — сузить проверку S1 или перенести bootstrap.

Зависимости pom S1 по тому же прогону: `platform-core` (5 типов), `FilterGrid/filtergrid-core`
(4 типа — узлы отбора), плюс S0.

### S2 — детали

**Декларационные правила ключа.** Сейчас `NomenclaturePriceLifecycle.beforeUpdate` запрещает смену
измерения и периода, а `beforeSave` нормализует период; при этом нормализация уже есть в
JPA-listener'е `RegisterPeriodListener` (`PeriodicRegisterEntryBase`) и в `RecorderSetWriter.prepareRows`.
Прикладной вызов нужен по **таймингу**: сравнение ключа идёт до flush. Значит правило платформы
обязано владеть и порядком («нормализовать → сравнить»), иначе «объявил и забыл» даст guard,
сравнивающий ненормализованное значение. После среза прикладной lifecycle сохраняет только
предметное.

**Проверка раскладки (`--slice S2`)** дала один блокер: `RegisterWriteMutex` импортирует
`persistence.RecorderOrderClock` (S3). Мьютекс записи и часы порядка — одна единица: либо
`RecorderOrderClock` переезжает в S2, либо мьютекс в S3. Решение принимается на старте S2.

Гейты: `RegisterKeyWritesTest`, `RegisterKeyChangesTest`, `RegisterKeyWriteConcurrencyPostgresIT`,
`RegisterWriteConcurrencyPostgresIT`; отрицательный сценарий «обычный UPDATE ключа отклонён»;
`NomenclaturePricePersistenceTest` как приёмка перевода пилота.

### S3 — детали

**Дефект, который чинится здесь.** `RecorderSetWriter` не знает `EntityLifecycleRegistry`: строки
пишутся `persist`/`remove` напрямую, тогда как `RegisterKeyWrites` и `RegisterKeyChanges` вызывают
`lifecycles.beforeSave/beforeUpdate/onSave/beforeDelete`. Правило строки, написанное для
подчинённого регистра, сегодня молча не исполняется — при этом диагностика
(`EntityLifecycleInspection`, `EntitySummaryPanel`) показывает handler по типу.

ADR-0025 выбирает один из двух исходов, и оба совместимы с планом:

- **Вызывать хуки** на пути замены набора. Тогда вызов ставится **до** Bean Validation
  (`prepareRows` валидирует внутри; в canonical пути верно обратное — валидация после
  `beforeSave`/`beforeUpdate`, и это тот порядок, который нельзя менять на обратный молча).
- **Не вызывать, но сделать видимым**: диагностика сообщает «путь записи не вызывает хуки строки»
  вместо `PLATFORM_DEFAULT`, чтобы объявленное правило нельзя было принять за исполняемое.

Приёмка: тест «хук исполнен ровно один раз на вставленную строку и ни разу на неизменённую»,
тест порядка (мутация из хука либо валидируется повторно, либо отклоняется), согласованность
диагностики для типа без handler'а и с ним.

### S4 — детали

`RegisterFormCustomization` разделяется: общая часть (режимы «История/Срез последних», фазы
отбора, блокировка ключа, диалог смены ключа, колонки регистратора) уезжает; всё, что называет
прикладной код, остаётся в приложении точкой расширения. Русские подписи — не новость для
платформы: `platform-vaadin` уже публикует пользовательские тексты. Vaadin — `provided`, поэтому
модуль не навязывает UI-стек потребителю.

Гейты: `FormAssemblyPurityTest`, `FormDeadApiTest`, `ReportFormSeamTest`, `PlatformVaadinWiringIT`,
`RegisterListFormToolbarTest`, `NomenclaturePriceFormTest`, `AccountingObjectPilotIT`,
`PilotSecurityQueryMeasurementIT`; негативная сборка без UI-модуля.

### S5 — детали

**(а) Подписи операций.** `RegisterKeyPermissionTitles` — четыре бина на регистр, где приложение
пишет только текст: id права уже считает `RegisterKeyChangePermissions.permissionId(type)`.
Блокер, которого нет в предложении: `NamedOperationOwner` — прикладной интерфейс
(`org.ip.application.catalog`), потребляемый прикладными `PermissionCatalogService` и `AdminView`;
платформенный модуль его реализовать не может. Выбор: нейтральный SPI (прецедент —
`EntityPresentationService`, который уже решил ту же задачу для показа регистратора) либо
динамическая регистрация владельцев из `RegisterRegistry`. Решение — часть ADR-0024.

**(б) Контекст позиций документа.** `AccountingObjectSaveScope` — ThreadLocal с эвристиками по
identity, id и Hibernate-классу. Причина не в регистре: у `EntityLifecycle` нет поддержанного
способа донести payload команды до `onSave`. Движок уже решил задачу того же класса корректно —
`RegisterRecords.forRecorder` проверяет callback-токен (`AggregateSaveLifecycleScope`/
`CanonicalSaveLifecycleScope`), `entityManager.contains`, активную транзакцию и делает handle
истекающим. Обобщается именно этот механизм; второй scope рядом не заводится. Работа получает
собственный ADR (граница lifecycle-контракта, не регистра).

**(в) Тесты движка.** 5 файлов с внешними опорами — 2 с прикладными сущностями
(`RegisterReaderTest`, `RegisterListFilterPhasesTest`) и 4 с внутридеревными фикстурами
(`InformationRegisterRecorderPersistenceTest`, `RecorderSetWriterPostgresIT`, `RegisterReaderTest`,
`RlsRecorderSetWriteAuthorizerTest`) — переводятся на модульные фикстуры; остальные 12 переезжают
вместе со своими срезами.

## 5. Что правится в гейтах и инфраструктуре

| Файл/гейт | Что меняется | Срез |
| --- | --- | --- |
| `platform-build/pom.xml`, `platform-bom`, `scripts/local-dependencies.json` | Два новых проекта: `dependsOn` по фактическим compile-импортам, артефакты, `buildSteps`, fingerprints, порядок сборки | S1 (backend), S4 (UI) |
| Стартеры `platform-backend-starter` / `platform-vaadin-starter` | Новые артефакты в замыкании; `PlatformStarterCompositionTest` подтверждает, что `platform-rest` остаётся вне замыкания | S1/S4 |
| `PersistenceTypeRegistrationTest` | `SOURCE_ROOTS` + новый модуль; владелец `org.ipro.register.persistence` переходит к модулю вместе с `@EntityScan` **тогда, когда переезжает сама сущность** (S3) — до этого приложение сохраняет своё сканирование | S1 (модуль), S3 (владелец сущности) |
| `PlatformSubsystemClosureTest` | Снятие записей reach-back у `reportstudio` **на каждом срезе**, а не один раз: тест сканирует `src/main/java`, поэтому уехавший класс исчезает из замыкания сразу и реестр становится несимметричным до правки | S0–S3 |
| `PlatformApiBaselineTest` + `platform-api-baseline/` | Baselines `platform-register.api`, `platform-register-vaadin.api` | S0/S4 |
| `platform-*-surface.txt` + surface-тесты | Reviewed-реестры ролей (APP_API/APP_SPI/MODULE_API/INTERNAL) для обоих модулей | S0/S4 |
| `PlatformDependencyDirectionTest` | Backend регистра не знает `form`/`reportstudio`/`ureport`/`jr`/`org.ip`; UI-модуль может знать `form`, но не отчёты | S1/S4 |
| `PlatformAutoConfigurationRegistryTest` | Каждая авто-конфигурация зарегистрирована ровно один раз и своим артефактом; запись `RegisterAutoConfiguration` уходит из app imports | S1–S3 |
| `Platform*ModuleTest` | Новые `PlatformRegisterModuleTest`, `PlatformRegisterVaadinModuleTest` (состав, объявленные зависимости, запрет прикладных ссылок) | S1/S4 |
| `examples/platform-consumer` + `scripts/test-platform-consumer-gate.ps1` | Кейс «объявил регистр, подключил модуль, не копировал инфраструктуру» | S5 |

## 6. Приёмка программы

1. **Негативно:** без каждого артефакта приложение не компилируется; без саморегистрации модуля
   старт падает громко.
2. **Регистрации:** число обязательных регистраций в приложении не выросло (сейчас 1 файл).
3. **Внешний потребитель:** `platform-consumer` объявляет собственный регистр, использует чтение и
   одну команду записи, не копируя инфраструктурные классы и не подключая внутренние пакеты.
4. **Совместимость поведения:** приёмка Р8/Р9/Р11 (включая PostgreSQL-скрипты) остаётся зелёной;
   семантика среза не меняется.
5. **Полные прогоны:** `mvn verify` приложения и random-order прогон с зафиксированным seed на
   закрытии каждого среза; тесты модулей включаются в его сборку не позже S3 (у модуля своя
   composition-проверка).
6. **Evidence:** на каждый срез — документ в `docs/architecture/evidence/` с протоколом, командой,
   результатом и явными ограничениями; протокол замера — `docs/plans/`.

## 7. Порядок, зависимости и что не входит

**Шаг 0 (до S0).** Зафиксировать текущее состояние пакета в коммите. На 2026-10-13 работа Р11 в
дереве незакоммичена: `RegisterKeyWrites`, `RegisterKeyWritePermissions`, `RegisterRowState` и три
теста ключа — **не отслеживаются** (`??`), а `RegisterDescriptor`, `RegisterKeyChanges`,
`RegisterMetadata`, `RegisterMetadataValidator`, `config/RegisterAutoConfiguration`, прикладные
`NomenclaturePriceService`, `RegisterKeyPermissionTitles` и три конфигурации — изменены.
Перенос файлов поверх незафиксированного дерева смешает срезы с незавершённой работой, а для
untracked-файлов `git mv` истории не сохранит вовсе. Это же условие и для S5: она трогает
`RegisterKeyPermissionTitles` и `NomenclaturePriceService`, где сейчас живут чужие правки.

Порядок обязателен: S0 → S1 → S2 → S3 → S4 → S5. S4 требует фабрики читателя (S1) и не должен
начинаться раньше; S5(а) требует ADR-0024, S5(б) — ADR по контракту lifecycle. «Сокращение
обвязки двух пилотов» **нельзя** делать раньше решения S3: иначе пилот сократят против контракта,
который сразу изменится.

Не входит в план: переименование Java-пакета; повышение `EntityKind`/снятие F-ERP-3;
`setAll`/`commandId`/`SERIALIZABLE` и другие СУБД (остатки Р11 — свои решения и измерения);
язык запросов и CTE-генератор (отчётный модуль); `RegisterReportReadPolicy` (остаётся у отчётов,
как решено в Р4); предметные сущности, сервисы и policy приложения.

## 8. Риски

| Риск | Как закрывается |
| --- | --- |
| Тихая потеря регистрации при выносе (класс ошибок D2/D3) | Модуль несёт свои imports; негативный эксперимент «убрать imports → старт падает» |
| Сборка находит то, чего не видит замер по исходникам | Негативный гейт сборкой у каждого среза (урок `telemetry → settings`) |
| Вызов lifecycle строки меняет существующее поведение | ADR-0025 фиксирует исход **и** порядок; тесты «ровно один раз», «до валидации»; отклонённый вариант тоже документируется |
| UI-модуль становится обязательным | Vaadin `provided`, модуль в стартере отдельно; негативная сборка без UI-модуля |
| `ReportFormSeamTest`/чистота форм ломаются о перенос | S4 не ослабляет тесты: разделение `RegisterFormCustomization`, а не правка проверок |
| Прикладной SPI (`NamedOperationOwner`) не даёт платформе владеть подписью | Решение в ADR-0024: нейтральный SPI либо динамическая регистрация владельцев |
| Тесты движка тянут приложение во владельца | Модульные фикстуры до переноса соответствующего среза |

## 9. Метрики прогресса

- Прикладные файлы инфраструктуры: `org.ipro.register` 43 main + 17 test, `org.ip.views.register` 5,
  конфигурация читателя 1, подписи прав 1 → цель S4: 0 (кроме точки расширения).
- Контрактных типов, названных вне пакета: 30 → reviewed-роли на все 30.
- Reach-back `reportstudio` на регистр: 16 → 0 записей.
- Обязательных регистраций в приложении: 1 → 1 (не больше).
- Прикладной код пилота (lifecycle + конфигурация): до/после S5 — измеряется числом строк и
  числом классов, а не впечатлением.

## Источники

- [Замер замыкания `org.ipro.register` и срез контрактов](information-register-extraction-slice.md),
  [протокол](../plans/register-closure-20261013.log), [инструмент](../../scripts/analyze-register-closure.mjs).
- [ADR-0013: информационный регистр v1](decisions/ADR-0013-information-register-v1.md) — Р4, §5, §6.
- [Протокол раскладки срезов](../plans/register-slices-20261013.log) — прогон `--slice S0..S4`
  с кодами выхода.
- [План развития регистров](information-register-development-plan.md) — Р10, Р11, Р13, сквозная матрица §19.
- [План регистратора](information-register-recorder-plan.md) — Г0–Г8; [ADR-0015](decisions/ADR-0015-information-register-recorder.md), [ADR-0020](decisions/ADR-0020-register-recorder-procedures.md).
- [ADR-0021](decisions/ADR-0021-register-key-change-command.md), [ADR-0022](decisions/ADR-0022-standard-register-listform.md), [ADR-0023](decisions/ADR-0023-register-key-write.md).
- [ADR-0005: EntityLifecycle](decisions/ADR-0005-entity-lifecycle-and-application-behavior.md).
- [Мост D2 → D3](d3-subsystem-extraction.md), [вынос platform-core](d3-core-extraction.md) — методика и гейты.
