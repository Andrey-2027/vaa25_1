# D-R S4: стандартный интерфейс регистра переехал в `platform-register-vaadin`

Дата: 2026-10-15. Статус: **перемещение среза S4 закрыто** — стандартный интерфейс регистра
(адаптер форм, диалог смены ключа, показ регистратора) живёт в артефакте
`org.ipro:platform-register-vaadin`, прикладной каталог `src/main/java/org/ip/views/register`
**пуст**, приложение получает интерфейс через `platform-vaadin-starter` и не называет ни одного
типа артефакта в production-коде.

Открытым внутри среза осталось ровно то, что было открыто до него: решение ADR-0025 (lifecycle
строки на пути набора владельца, названо в S3) и срез S5 — см. «Оставшийся долг», а не «не заметили».

Предыдущие срезы: [S0](register-s0-extraction-20261013.md), [S1](register-s1-extraction-20261013.md),
[S2](register-s2-extraction-20261013.md), [S3](register-s3-extraction-20261014.md); план —
[план выноса](../information-register-extraction-plan.md).

## Что переехало

Переезд снова **перемещение** — содержимое классов не менялось, менялось место и пакет. Новая
только конфигурация среза: у перенесённых классов не было своего бинового владельца.

| Тип | Куда | Почему он в этом срезе |
| --- | --- | --- |
| `RegisterFormCustomization` | `platform-register-vaadin` | адаптер форм: режимы «История/Срез последних», фазы отбора среза, блокировка ключа, команда смены ключа, колонки регистратора |
| `RegisterKeyDialog` | `platform-register-vaadin` | диалог смены ключа: отдельный черновик, только идентификаторы ключа и версия строки |
| `RecorderRefPresentationResolver` | `platform-register-vaadin` | показ регистратора: каноническое чтение пачками под текущим C5/RLS-контекстом, подписи типов и документов |
| `RecorderRefPresentation` | `platform-register-vaadin` | результат резолвера: значение с подписью и ссылкой |
| `RecorderRefDisplay` | `platform-register-vaadin` | компонент колонки: потребитель ставит его в свой рендерер, не копируя ни показ недоступного документа, ни правило ссылки |
| `config/RegisterVaadinConfiguration` | `platform-register-vaadin` (**новая**) | авто-конфигурация среза: резолвер показов и заменяемый бин стандартного адаптера |

**Смена пакета — не косметика и не отступление от «пакеты сохраняются».** Плановое правило
(«Java-пакеты сохраняются») держалось на замером подтверждённом факте: у движка нет прикладных
имён, поэтому `org.ipro.register.*` переезжает без правок у потребителей. У интерфейса носителем
был прикладной пакет `org.ip.views.register`, и прикладной пакет внутри платформенного артефакта
замкнул бы цикл сборки. Поэтому адаптер переехал в `org.ipro.vaadin.register` (прецедент —
телеметрический адаптер), а запрет «`org.ip` в платформенном модуле» проверяется забором состава
артефакта, а не соглашением.

## Разделение точки расширения: чего ждал план и что показал замер

План (§4, S4-детали) предполагал разделение `RegisterFormCustomization`: «общая часть уезжает,
всё, что называет прикладной код, остаётся точкой расширения». К S4 признак разреза исчез, и это
измерение, а не пропуск:

- S1 заменил прикладную сборку читателя фабрикой `RegisterReaderFactory`, поэтому у адаптера не
  осталось ни `EntityManagerFactory`, ни прикладных сущностей;
- прогон `node scripts/analyze-register-closure.mjs --slice S4` (протокол:
  [`docs/plans/register-s4-closure-20261015.log`](../../plans/register-s4-closure-20261015.log))
  дал **5 файлов, 0 ссылок за границу среза, 0 неразрешённых ссылок на `org.ipro.register.*`**.
  Пять файлов перечислены как «нет на диске» — это ожидаемая сторона переезда, а не расхождение:
  замер перечисляет состав среза, а он уже уехал.

Создавать «прикладную часть точки расширения» искусственно значило бы оставить в приложении
подкласс без потребителя. Точкой расширения стал механизм платформы, и он **проверяется, а не
описан**: `RegisterVaadinExtensionPointTest` (новый гейт среза, три сценария на
`ApplicationContextRunner`):

| Сценарий | Что доказывает |
| --- | --- |
| потребитель не объявил ничего | бин стандартного адаптера есть, и это именно `RegisterFormCustomization`, а не подкласс; резолвер показов приезжает тем же артефактом |
| потребитель объявил свой бин адаптера | `@ConditionalOnMissingBean` отступает: бин один и он **тот же объект**, что объявил потребитель (тождество, а не факт наличия бина — «бин есть» верно и когда выиграл платформенный) |
| движка нет вовсе | контекст падает с отсутствующим `RegisterRegistry`, а не собирает себе второй читатель: негативный гейт сторожит ровно тот дефект двух сборок, который закрывал S1 |

Частичная настройка точки расширения не теряется: слой форм применяет **все**
`FormTypeCustomization`, поддерживающие тип, поэтому дополнительный адаптер потребителя добавляется
рядом со стандартным, а не вытесняет его. Это свойство `FormResolver`, и S4 его не ослаблял —
`FormAssemblyPurityTest` и `FormDeadApiTest` зелёные (см. прогоны).

## Reviewed-реестр ролей: 6 типов

`META-INF/register-vaadin-surface.txt` создан тем же изменением, что и артефакт, и забор
(`RegisterVaadinSurfaceRoleTest`, 3/3) гоняется **сборкой артефакта**, а не только сборкой
приложения: APP_API **3**, APP_SPI **1**, MODULE_API **0**, INTERNAL **2**.

- **APP_API 3** — `RecorderRefPresentationResolver`, `RecorderRefPresentation`, `RecorderRefDisplay`:
  потребитель называет их, чтобы отрисовать колонку регистратора своей формой. Каноническое чтение
  остаётся за платформой, а имя типа — обещание;
- **APP_SPI 1** — `RegisterFormCustomization`, и это пересмотренное решение, а не инерция старой
  роли. Приложение больше не объявляет его бином, зато платформа по-прежнему вызывает адаптер
  обратно через `FormResolver`, а потребитель расширяет или заменяет его. Обещание двойное —
  поведение стандартных форм и возможность отступления, — поэтому роль application-facing, а не
  `INTERNAL`;
- **MODULE_API 0** — измеренный факт: ни один другой платформенный модуль типов интерфейса не
  называет. Пустой раздел записан явно (бюджет `0` сверяется тестом), потому что «роль без записей»
  и «о роли не подумали» должны быть различимы;
- **INTERNAL 2** — `RegisterKeyDialog` и `RegisterVaadinConfiguration`. У диалога роль и
  package-private видимость сходятся: забор замыкания API не даст вывести его в публичную сигнатуру
  случайно. У конфигурации обещание — бины, которые она приносит, а не её имя.

В прикладном D1-реестре (`platform-public-surface.txt`) срез **снял шестнадцать записей**
legacy-internal и не добавил ни одной: до S4 эти типы называл ровно один прикладной файл — тот самый
адаптер форм. Реестр обязан совпадать с фактом в обе стороны, поэтому записи сняты, а не оставлены
«на будущее»; бюджет API при этом сократился, а не вырос.

## Артефакт и владение сборкой

| Факт | Значение |
| --- | --- |
| Координата | `org.ipro:platform-register-vaadin:1.0-SNAPSHOT` |
| Compile-зависимости | `platform-register`, `platform-vaadin`, `platform-core`, `platform-persistence`, `platform-identity-api`, `filtergrid-core` + Spring/Jackson/slf4j; **Vaadin — `provided`** |
| Направление | `platform-register-vaadin → platform-register`, обратного ребра нет: backend не знает ни Vaadin-артефакта, ни `com.vaadin`-импорта — проверяет состав артефакта в обе стороны |
| Fingerprint (`gitvaa-source-tree-sha256-v2`) | `9c5c121e2f8377553386e8dc492ca44ee99415dfe01281d04e138e7a9d111f9b`, 14 файлов |
| Сборка | `mvn -f platform-build/pom.xml -pl ../platform-register-vaadin clean install` — **с тестами**: `BUILD SUCCESS`, 24/24 |
| Регистрация | своя запись в `AutoConfiguration.imports` артефакта; приложение не регистрирует чужие авто-конфигурации |
| Поставка | артефакт входит в замыкание `platform-vaadin-starter`; прямая зависимость приложения на `platform-register` **снята** (движок и интерфейс приезжают стартером) |

Условие `@ConditionalOnClass(name = "com.vaadin.flow.component.Component")` обязательно именно
из-за `provided`: без него конфигурация падала бы загрузкой класса у потребителя без UI-стека, а не
отступала. Условие проверяет класс по имени — решение принимается без загрузки отсутствующего типа.

Снятие прямой зависимости на `platform-register` сделало видимым один транзитивный факт: `UniDrParameters`
регистрирует `JavaTimeModule` явно, а сам модуль времени раньше приезжал через эту зависимость.
Поэтому в pom приложения `jackson-datatype-jsr310` объявлен **прямо** — это фактический импорт
прикладного кода, а не удобство; без него падение было бы в рантайме, а не на сборке.

## Прогоны гейтов

| Гейт | Результат |
| --- | --- |
| `platform-register-vaadin: clean install` | `BUILD SUCCESS`; **24/24** — роли 3/3, состав и зависимости 9/9, диалог ключа 2/2, резолвер показов 7/7, точка расширения 3/3 |
| `PlatformRegisterVaadinModuleTest` | **7/7** — в приложении не осталось ни копии, ни пакета интерфейса; перенесённые типы приходят из артефакта (проверка `getProtectionDomain`, а не факта компиляции); регистрацию владеет артефакт; интерфейс берётся стартером; production-код приложения не называет типы модуля; потребительские тесты компилируются против артефакта; тесты приложения не занимают пакеты модуля |
| `PlatformRegisterModuleTest` / `PlatformVaadinModuleTest` / `PlatformDependencyDirectionTest` | **5/5**, **7/7**, **10/10** |
| `PlatformCoreSurfaceTest` (после правки реестра) | **5/6** — единственное падение названо в «чужих» ниже |
| `PlatformApiBaselineTest` | поверхность `platform-register-vaadin` зафиксирована новым `platform-api-baseline/platform-register-vaadin.api`; остаётся одно падение — чужое, ниже |
| `PersistenceTypeRegistrationTest` / `PlatformSubsystemClosureTest` / `PlatformPackageDependencyMatrixTest` / `PlatformAutoConfigurationRegistryTest` | **6/6**, **2/2**, **3/3**, **3/3** |
| Формы и швы | `FormAssemblyPurityTest` **1/1**, `FormDeadApiTest` **1/1**, `ReportFormSeamTest` **6/6** |
| Интерфейс в работающем контексте | `RegisterListFormToolbarTest` **4/4**, `NomenclaturePriceFormTest` **6/6**, `PlatformVaadinWiringIT` **2/2**, `PilotRegisterListToolbarIT` **3/3** |
| H2-набор движка | `RegisterKeyWritesTest` 12/12, `RegisterKeyChangesTest` 9/9, `RegisterReaderTest` 8/8, `RegisterDescriptorTest` 5/5, `RegisterPeriodicityTest` 4/4, `RegisterMetadataValidatorTest` 17/17, `RegisterStartupValidatorTest` 6/6, `RegisterSliceCteGeneratorTest` 29/29 |
| H2-набор набора владельца и пилота | `InformationRegisterRecorderPersistenceTest` **21/21**, `MetadataDrivenAggregateTelemetryTest` **3/3**, `PersistenceRegistrationIT` **3/3**, `RegisterBatchReadIT` **6/6**, `AccountingObjectPilotIT` **29/29**, `NomenclaturePricePersistenceTest` **25/25**, `NomenclaturePriceFormTest` **6/6**, `NomenclaturePriceLifecycleTest` **4/4**, `NomenclaturePriceRegisterOracleTest` **7/7** |
| Прочее в приёмке S4 | `PilotFixtureWriteBoundaryIT` **3/3**, `PilotSecurityQueryMeasurementIT` **1/1** |

Проверка проводилась с `JAVA_HOME` на JDK 21 (`/c/Program Files/Axiom/AxiomJDK-21`). Первый прогон
без явного `JAVA_HOME` дал `class file version 65.0 ... only recognizes ... up to 61.0` в форк-процессе
surefire — переменная окружения в этой оболочке указывает на JDK 17, тогда как сборка идёт на 21.
Это дефект окружения, а не артефакта: с установленным `JAVA_HOME` тот же модуль собирается и
проходит тесты.

Замеры D1 обновлены намеренно (`-Dplatform.measurements.write=true`): `app-source-files` 259 → 271,
`platform-tree-files` 146 → 147, `platform-artifact-files` 577 → 578, `named-platform-types` 294 → 312.
Числа описывают дерево целиком, и прирост прикладных файлов дала чужая незавершённая работа
(permissions workspace, UniDR), а не срез: S4 не добавил в приложение ни одного класса. Реестровые
числа среза — свои: `register-vaadin-surface.txt` 3/1/0/2, `platform-core-surface.txt` 34/6/53/16,
D1-реестр 178/33/57 с шестнадцатью снятыми legacy-internal записями.

## Чужие красные гейты (не относятся к срезу)

Все перечисленные падения воспроизводятся на текущем рабочем дереве без правок S4 и вызваны
незакоммиченной работой других направлений. Ни один из них не назван впервые этим срезом: класс
ошибок тот же, что в S2/S3.

- `PlatformPublicSurfaceTest` — **4/10**: wildcard-импорты и fully-qualified ссылки в `org/ip/unidr/**`,
  новый файл называет внутренний `org.ipro.reportstudio.data.EntityRef`, 165 типов платформы без
  reviewed-роли (unidr/permissions/navigation). Типов регистра в списке нет. Падение
  `documentedMeasurementsMatchTheCode` **было нашим** и закрыто осознанной перезаписью замеров;
- `PlatformVaadinSurfaceTest` — **2/6**: `org.ipro.form.coordinator.FormCoordinator` (роль INTERNAL)
  называет незакоммиченный `src/main/java/org/ip/views/admin/PermissionWorkspace.java`, а также тесты
  `PermissionWorkspaceP5IT` и `PermissionsWorkspaceP0IT`;
- `PlatformCoreSurfaceTest#applicationTestReferencesToInternalOrModuleTypesAreFrozenPerFile` — новый
  тест UniDR (`UniDrReceivingItemsQueryTest`, `UniDrQuerySmokeApplication`) начал называть
  `org.ipro.data.CanonicalReadExecutor`;
- `PlatformApiBaselineTest` — публичная подпись `platform-rls` разошлась с baseline:
  `RlsHibernateWriteGuardInstaller` → `RlsGrantMatching` (неотслеживаемый файл permissions workspace).
  Поверхность самого `platform-register-vaadin` при этом совпала с новым baseline, то есть проверка
  состоялась;
- `WorkspaceManifestTest` — дрейф fingerprint **только у `platform-rls`** (61 → 62 файла). Свой
  fingerprint модуля пересмотрен намеренно (14 файлов) после добавления теста точки расширения;
  чужой дрейф не переписывался: зафиксировать его значило бы записать в манифест полудоделанную
  правку чужого модуля;
- `PlatformStarterCompositionTest` — приложение объявляет `org.ipro:platform-navigation` прямо, а
  гейт допускает прямыми только три координаты. Объявление есть уже в `HEAD` и этим срезом не
  трогалось — это работа направления навигации, но она должна быть закрыта до общего `mvn verify`.

## Оставшийся долг

1. **ADR-0025** (lifecycle строки регистра на пути набора владельца) — перенесено без изменений из
   S3: перемещение этого не решает и не прячет. S4 его не касался: диалог смены ключа и адаптер форм
   идут через `RegisterKeyChanges`, который хуки вызывает.
2. **S5** — сокращение обвязки приложения: стандартные подписи операций из метаданных, поддержанный
   контекст позиций документа вместо самодельного scope, модульные фикстуры вместо прикладных в
   тестах движка (сегодня 29 прикладных файлов всё ещё называют `org.ipro.register` — это
   платформенный код, лежащий в дереве приложения: reportstudio, form-слой, пилот).
3. **Чужие красные гейты** — не долг этого среза, но условие общего `mvn verify`: без их закрытия
   зелёный результат не будет означать «в дереве всё в порядке».
