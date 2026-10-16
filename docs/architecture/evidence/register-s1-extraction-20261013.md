# D-R S1: чтение и фабрика читателя переехали в `platform-register`

Дата: 2026-10-13. Статус: срез S1 закрыт — модуль собирается и устанавливается со своим тестом
(`RegisterSurfaceRoleTest` 3/3), реестр объявлений, точечный и срезовый читатели, фазы отбора и
фабрика читателя живут в артефакте, прикладная конфигурация читателя удалена, гейты модуля зелёные.
План среза — [план выноса](../information-register-extraction-plan.md) §«S1 — детали»,
предыдущий срез — [S0](register-s0-extraction-20261013.md).

## Что переехало и чем это не является

Переезд снова **перемещение, а не рефакторинг**: `git diff -M` показывает 0 изменённых строк у
`RegisterRegistry`, `RegisterReader`, `RegisterSliceReader`, `RegisterListFilterPhases` и
`RecorderTypeRegistry`. Единственное исключение — `RegisterStartupValidator`: он **сужен** по
владельцу (см. ниже), и это осознанное решение среза, а не попутная правка.

| Тип | Куда | Почему он в этом срезе |
| --- | --- | --- |
| `RegisterRegistry` | `platform-register` | реестр объявлений — ядро чтения: без него декларация не проверяется и читатель не собирается |
| `RecorderTypeRegistry` | `platform-register` (развилка плана) | реестр разрешает коды регистраторов; без каталога декларация проверялась бы слабее — см. «Развилки» |
| `RegisterReader` | `platform-register` | точечное чтение по ключу |
| `RegisterSliceReader` | `platform-register` | срезовое чтение (победитель по последней версии) — 428 строк, ноль изменений |
| `RegisterListFilterPhases` | `platform-register` | фазы отбора листового списка делят измерения и ресурсы до выбора победителя |
| `RegisterReaderFactory` | `platform-register` (**новый тип**) | единственная точка сборки читателя по дескриптору; в `HEAD` его не было |
| `RegisterStartupValidator` | `platform-register`, сужен | проверка деклараций падает громко; засев часов порядка остаётся приложению до S3 |
| `config/RegisterReadConfiguration` | `platform-register` (**новый**) | read-конфигурация модуля со своим `AutoConfiguration.imports` |

Чем срез не является: модуль по-прежнему **не умеет писать**. В нём нет ни команд ключа (S2), ни
протокола набора владельца (S3), ни сущности `RecorderOrderClock`; `PlatformRegisterModuleTest`
проверяет это текстом конфигурации (список имён более поздних срезов), а не обещанием в комментарии.

## Дефект, который закрыт: две сборки читателя перестали расходиться

До среза было две сборки одного читателя: приложение объявляло bean
`RegisterSliceReader<NomenclatureAccountingObject>` с `EntityManagerFactory` в
`AccountingObjectRegisterConfiguration`, а форма списка конструировала читатель рядом сама и
прикладной bean не использовала. Расхождение двух сборок не было видно никому — оба пути
компилировались и работали, пока совпадали.

После среза `AccountingObjectRegisterConfiguration` удалён, и оба потребителя (сервис пилота и
форма списка) берут читатель из `RegisterReaderFactory` **по дескриптору**. Гейт
`PlatformRegisterModuleTest.theApplicationNoLongerAssemblesReaders` проверяет и отсутствие файла, и
отсутствие `new RegisterSliceReader<>`/`new RegisterReader<>` во всём прикладном дереве.

Побочный, но полезный эффект: приёмочный тест приложения `RegisterStartupValidatorTest` покраснел
шесть раз из шести, потому что он собирал контекст из одной прикладной конфигурации и потерял bean
реестра. Это ровно то доказательство, которое срез должен был получить: приложение больше не
владеет чтением. Тест не «подлатан» импортом — он теперь собирает модульную авто-конфигурацию так
же, как её собирает работающее приложение (`AutoConfigurations.of(RegisterReadConfiguration.class)`),
и остаётся прикладным приёмочным тестом: «декларации падают громко в контексте, собранном прикладной
конфигурацией». Тесты движка при этом остаются у приложения до S5 по решению плана, поэтому
`RegisterStartupValidatorTest` не переезжает в модуль.

## Развилки, названные планом, и принятые решения

1. **`RecorderTypeRegistry` — в S1, а не в S3.** План назвал развилку S1: «либо каталог типов
   переезжает вместе с реестром, либо разрешение кодов получает S3-расширение». Выбран переезд:
   иначе реестр перестал бы проверять `RECORDER_TYPE_NOT_MANAGED`, а чтение — видеть, что декларация
   ссылается на управляемый `DOCUMENT`. Цена — один тип читающего среза; альтернативная цена —
   второй способ разрешения кодов в дереве.
2. **`RegisterStartupValidator` сужен до деклараций.** Стартовая проверка требовала
   `RecorderOrderClockBootstrap`, который пишет строку прикладной сущности. Тип, владеющий
   сущностью, не может пересечь границу модуля раньше своего persistence, поэтому засев часов
   остаётся прикладным стартовым шагом **до S3** — и это названный долг, а не тихая потеря: гейт
   `theOrderClockSeedingStaysVisibleUntilItsEntityMoves` требует, чтобы шаг в прикладной
   конфигурации был и был вызван. Снять проверку можно только вместе с переездом сущности.
3. **Монолитная конфигурация разделена по срезам.** Read-конфигурация модуля не знает ни команд, ни
   регистратора; прикладная `RegisterAutoConfiguration` остаётся ровно одна и по-прежнему владеет
   S2–S3. Приложение **не приобрело обязательных регистраций**: запись в `AutoConfiguration.imports`
   была и осталась одна, а бины чтения приходят из артефакта (`@ConditionalOnMissingBean`, поэтому
   потребитель вправе заменить читателя или реестр своим).
4. **Плановая формулировка «запись `RegisterAutoConfiguration` уходит из app imports» не выполнена
   в буквальном виде — и не должна была.** К S1 из прикладной конфигурации уехало чтение, а не вся
   регистрация: команды ключа и набор владельца остаются прикладными до S2/S3. Поэтому имя остаётся
   прикладным, а «уходит из app imports» станет верным к S3. Гейт `PlatformAutoConfigurationRegistryTest`
   (3/3) проверяет содержательное требование: каждая авто-конфигурация зарегистрирована ровно один
   раз и своим артефактом.

## Артефакт и владение сборкой

| Факт | Значение |
| --- | --- |
| Координата | `org.ipro:platform-register:1.0-SNAPSHOT` |
| Объявленные зависимости | `platform-contracts`, `platform-persistence` (S0) + `platform-core` (managed-каталог, `MetadataResolver`, канонический порт `EntityDataAccess`), `filtergrid-core` (дерево условий), `spring-data-commons/jpa`, `spring-boot-autoconfigure`, `spring-beans/context` |
| Fingerprint (v2) | `beae4353eb02ef792bdf4dd5087154b1ba37b8c7d3c08e8da5aed4da244d5403`, 29 файлов (`pom.xml` + `src`) |
| Сборка | `buildSteps: clean install` **с тестами**: `RegisterSurfaceRoleTest` 3/3, `BUILD SUCCESS` |

Замер замыкания (`analyze-register-closure.mjs --slice S1`) дал 14 блокеров; все 14 закрыты
разделением конфигурации и двумя решениями выше. `dependsOn` манифеста обновлён по фактическим
compile-импортам: `["filtergrid", "platform-contracts", "platform-core", "platform-persistence"]`.
Fingerprint проверен независимым пересчётом схемы `gitvaa-source-tree-sha256-v2` (ordinal-порядок
путей, `path\0sha256\n`, sha256 по UTF-8) — совпадение с записью манифеста точное, включая число
файлов, то есть это не совпадение реализации проверяющего с самим собой.

## Reviewed-реестр ролей

`platform-register/src/main/resources/META-INF/register-surface.txt` — **25 типов**: APP_API 14,
APP_SPI 5, MODULE_API 0, INTERNAL 6. Решения среза:

- читатели и реестр — **APP_API** по признаку «приложение называет тип» (сервис пилота, форма
  списка, прикладная конфигурация записи), а не по признаку «тип лежит в модуле»;
- `RegisterStartupValidator` и `RegisterReadConfiguration` — **INTERNAL**: приложение их не
  называет, обещание — поведение («регистрация падает громко», набор заменяемых бинов), а не имя;
- **MODULE_API по-прежнему 0** — измеренное состояние, а не пропуск: ни один платформенный модуль
  типов регистра не называет. Роль появится вместе с потребителем (S4), и это будет видно в диффе.

Приложение с другой стороны границы получило одну новую reviewed-запись:
`API org.ipro.register.RegisterReaderFactory` в `src/test/resources/platform-public-surface.txt`.
Это закрыло ровно свою часть `PlatformPublicSurfaceTest`: `RegisterReaderFactory` ушёл из списка
«назван, но роль не назначена».

## Прогоны гейтов

| Гейт | Результат |
| --- | --- |
| `platform-register: mvn -o clean install` | `BUILD SUCCESS`, `RegisterSurfaceRoleTest` 3/3 |
| `PlatformRegisterModuleTest` | 4/4 — модуль не называет `org.ip`, read-конфигурация не знает S2/S3, приложение не собирает читатель, засев часов виден |
| `PlatformAutoConfigurationRegistryTest` | 3/3 |
| `PlatformSubsystemClosureTest` | 2/2 |
| `PlatformDependencyDirectionTest` | 9/9 |
| `PlatformPersistenceModuleTest` | 4/4 |
| `PlatformApiBaselineTest` | расхождений **по `platform-register` нет** (единственное расхождение — `platform-rls`, см. ниже) |
| `PersistenceTypeRegistrationTest` | 6/6 — владелец `org.ipro.register.persistence` остался прикладным до S3 |
| `WorkspaceManifestTest` | дрейф назван по пяти чужим проектам; **`platform-register` в списке дрейфа отсутствует** — запись манифеста совпадает с деревом |
| `RegisterStartupValidatorTest` (приложение) | 6/6 после композиции модульной авто-конфигурации |
| Прикладной набор регистра | `NomenclaturePricePersistenceTest` 24/24, `InformationRegisterRecorderPersistenceTest` 21/21, `RegisterKeyWritesTest` 12/12, `RegisterMetadataValidatorTest` 17/17 (+Mapping 11/11), `RegisterKeyChangesTest` 9/9, `RegisterReaderTest` 8/8, `RegisterKeyChangePermissionsTest` 7/7, `NomenclaturePriceFormTest` 6/6, `RegisterDescriptorTest` 5/5, `RegisterPeriodicityTest` 4/4, `RegisterListFilterPhasesTest` 3/3 |

Полный `mvn verify` срезом не выполнялся по той же причине, что и S0: рабочее дерево занято чужими
незавершёнными правками. Красные гейты ниже названы явно, а не зачтены молча.

## Чужие красные гейты (не относятся к срезу)

- `PlatformPublicSurfaceTest` — 5 падений, все на незавершённой работе UniDR/REST:
  wildcard-импорты `org.ipro.reportstudio.*`/`filtergrid`/`rls`, fully-qualified ссылки в
  `org/ip/unidr/**`, новый файл `UniDrQuerySourceService` с внутренним `reportstudio.data.EntityRef`,
  19 типов без роли (`EntityDescriptor`, `FilterGroup`, `ReportQueryAssembler`, `RlsContext`, …),
  и замер `app-source-files`: код даёт 263, документ фиксирует 202. Замер расходился **и до среза**:
  в `HEAD` в `src/main/java/org/ip` уже 240 файлов против записанных 202, а срез только уменьшает
  это число (7 файлов уехало).
- `PlatformPackageDependencyMatrixTest` — не хватает reviewed-рёбер `org.ipro.navigation.*`
  (9 рёбер, другой модуль). Рёбра регистра в allow-list есть (`org.ipro.register -> org.ipro.{data,
  metadata, crud, metadata.annotation, rls, events, lifecycle, telemetry.api}`,
  `org.ipro.register.config -> …`).
- `PlatformApiBaselineTest` — расхождение в `platform-rls`
  (`entityOperations(Class)` → `entity(Class)`), тема C5-прав.
- `WorkspaceManifestTest` — дрейф fingerprints у `platform-contracts`, `platform-rls`,
  `platform-core`, `platform-spring-boot-autoconfigure`, `platform-vaadin` (чужие незавершённые
  правки в рабочем дереве).
- `NomenclaturePriceCteSpikeTest` (2 ошибки) и `RegisterSliceReportSecurityTest` (14 ошибок,
  2 падения) — `UnsupportedOperationException: Cte root does not have an entity type` в
  `ReportReadAuthorizer$EntityCollector`. Файл `src/main/java/org/ipro/reportstudio/query/ReportReadAuthorizer.java`
  изменён в рабочем дереве другим срезом; ни один файл этого среза в падающем пути не участвует.

## Оставшийся долг

1. **S2**: команды ключа и правила ключа уезжают из приложения; `RegisterWriteMutex` и
   `RecorderOrderClock` — развилка, которую план уже назвал.
2. **S3**: протокол набора владельца (`RegisterRecords`, `RecorderSetWriter`, участники) и сущность
   `RecorderOrderClock` с её `@EntityScan` — вместе с ними в модуль возвращается засев часов.
3. **S4**: `platform-register-vaadin`; тогда же пересматривается `MODULE_API = 0`.
4. **S5**: тесты движка переезжают из приложения, и `platform-package-matrix.txt` теряет ребро
   `org.ipro.register -> org.ipro.*` для тестового дерева.
5. Замеры D1 (`app-source-files`) требуют осознанного обновления
   (`-Dplatform.measurements.write=true`) — это чужая незавершённая работа плюс семь файлов,
   уехавших этим срезом.
