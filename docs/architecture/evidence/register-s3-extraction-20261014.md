# D-R S3: протокол набора владельца переехал в `platform-register`

Дата: 2026-10-14. Статус: **перемещение среза S3 закрыто** — протокол набора владельца, его участники,
шов авторизации и читатель подчинённых строк живут в артефакте, а приложение **не регистрирует регистр
вовсе**: каталог `src/main/java/org/ipro/register` пуст, запись `org.ipro.register.config.RegisterAutoConfiguration`
удалена из app imports. Открытым внутри среза названо решение ADR-0025 (lifecycle строки на пути набора) —
см. «Оставшийся долг», а не «не заметили».

Предыдущие срезы: [S0](register-s0-extraction-20261013.md), [S1](register-s1-extraction-20261013.md),
[S2](register-s2-extraction-20261013.md); план — [план выноса](../information-register-extraction-plan.md).

## Что переехало

Переезд снова **перемещение**: содержимое классов не менялось, менялось место. Новая только
конфигурация среза — у монолитной прикладной конфигурации не было будущего, её место занимают три
срезовые.

| Тип | Куда | Почему он в этом срезе |
| --- | --- | --- |
| `RegisterRecords` (+ вложенный `ForRecorder`) | `platform-register` | процедуры набора подчинённых строк под токеном canonical `onSave`: `add`/`clear`/`delete` (ADR-0019) |
| `RecorderSetWriter` | `platform-register` | полная замена набора: сохранение id, номеров строк и порядка записи, блокировка под общим мьютексом |
| `RecorderSetWriteRequest` | `platform-register` | до/после-снимок набора, по которому принимается решение о записи |
| `RecorderSetWriteAuthorizer` | `platform-register` | шов авторизации: решение по каждой строке набора принимает владелец |
| `RlsRecorderSetWriteAuthorizer` | `platform-register` | платформенная реализация шва (C5 + RLS) — рабочая, а не единственная возможная |
| `RecorderSetContribution` | `platform-register` | полезная нагрузка набора в canonical-сохранении агрегата |
| `RecorderSetContributionParticipant` | `platform-register` | участие набора в сохранении агрегата-владельца |
| `RecorderSetDeleteParticipant` | `platform-register` | участие набора в удалении агрегата-владельца |
| `RecorderSetTransactionExecutor` | `platform-register` | граница транзакции попытки и retry на блокировках |
| `RecordedRegisterReader` | `platform-register` | каноническое чтение истории и деталей подчинённых строк |
| `config/RegisterRecorderSetConfiguration` | `platform-register` (**новая**) | конфигурация среза со своими бинами и заменяемым швом авторизации |

Чем срез **не** является: он не меняет контракт набора. Замер замыкания (`--slice S3`) назвал 13
файлов, но два из них — `RecorderOrderClockBootstrap` и `persistence/RecorderOrderClock` — уехали
раньше, в S2, вместе с командой ключа: замер перечислял файлы, на которые ссылается срез, а не только
переносимые. Развилки здесь не было: набор владельца берёт часы порядка через тот же
`RegisterWriteMutex`, что и команда ключа, — второго механизма нумерации строк не появилось.

## Исчезновение прикладной регистрации — структурный результат среза

| Факт | Значение |
| --- | --- |
| Прикладной каталог движка | `src/main/java/org/ipro/register` — **пуст** (переезжали, а не копировали: две копии движка — это ровно тот дефект, который закрывал S1) |
| app `AutoConfiguration.imports` | записи `org.ipro.register.*` нет вовсе |
| Регистрация артефакта | три записи в модульном imports — read (S1), key write (S2), recorder set (S3) |
| Монолитная `RegisterAutoConfiguration` | удалена `git rm`-ом: её содержимое разошлось по трём срезовым конфигурациям, и «привозить больше нечего» стало фактом, а не планом |

Это меняет не только раскладку: до среза форма списка могла собрать читатель рядом с bean'ом, и
расхождение двух сборок не видел никто. Теперь второй сборки нет ни в приложении, ни в тестах —
гейт `theApplicationKeepsNoRegisterRegistrationAtAll` проверяет и каталог, и imports, и отсутствие
`new RegisterSliceReader<>`/`new RegisterRecords(` в прикладных исходниках.

## Найденный и починенный разрыв: `RegisterBatchReadIT`

Срез S1 удалил прикладной конфигурационный bean читателя, но один тест продолжал его ожидать:
`@Autowired RegisterSliceReader<NomenclatureAccountingObject>` в `RegisterBatchReadIT` падал
`NoSuchBeanDefinitionException`-ом (6 ошибок из 6). Это ровно тот класс ошибки, ради которого срез
делался: вторая точка сборки жила ещё и в тесте, и её «не видели», пока никто не позвал этот гейт.

Починка — не возврат bean'а, а переход на единственную точку сборки артефакта:
`RegisterReaderFactory.slice(NomenclatureAccountingObject.class)`. После правки — **6/6**.

## Reviewed-реестр ролей: 46 типов

`META-INF/register-surface.txt`: APP_API **20**, APP_SPI **7**, MODULE_API **0**, INTERNAL **19**.

- APP_API прибавил `RegisterRecords` и `RecordedRegisterReader` — по всё тому же измеренному признаку
  «приложение (main) называет тип»: генерация набора владельца вызывает процедуры, сервис чтения
  собирает читателя истории. Это первый срез, который приносит прикладной **протокол**, а не только
  движок, поэтому и роль у него APP_API, а не MODULE_API: обещание предъявляется приложению;
- APP_SPI прибавил `RecorderSetWriteAuthorizer` и `RecorderSetWriteRequest` — приложение не вызывает
  авторизатор, а реализует его (в тестах — лямбдой), платформа вызывает обратно. Обе роли
  application-facing **вместе**: `Request` стоит в сигнатуре `Authorizer`, и INTERNAL у `Request`
  утащил бы за собой весь шов — это ловит забор замыкания API, а не ревью;
- **MODULE_API 2 → 0: понижение, а не косметика.** `RecorderOrderClock` и `RecorderOrderClockBootstrap`
  были объявлены контрактом между модулями **затем**, чтобы S3 взял их из другого артефакта. Срез
  приехал и взял их из своего же, поэтому обещание стало некому адресованным; измерение (ни один
  другой модуль этих двух типов не называет) превратило роль в INTERNAL. Понижать — не то же самое,
  что «забыть расширить реестр»: здесь решение принималось вместе с фактом;
- INTERNAL прибавил восемь типов среза (реализация писателя, участники, исполнитель транзакции,
  реализация авторизатора, конфигурация) и два пониженных: обещание среза — поведение, а не имена.

Забор `RegisterSurfaceRoleTest` зелёный на этих данных: полнота (новый тип без роли роняет сборку),
самосогласованность бюджетов разделов и замыкание API (application-facing сигнатура не называет
INTERNAL-тип) — то есть роли проверены сборкой артефакта, а не глазами.

## Артефакт и владение сборкой

| Факт | Значение |
| --- | --- |
| Координата | `org.ipro:platform-register:1.0-SNAPSHOT` |
| Новые зависимости | **нет** — и это результат замера, а не экономия: протокол держится за `platform-core` (участники агрегата и каноническое удаление), `platform-rls` (решение об операции строки) и `spring-tx` (граница транзакции владельца), уже привезённые S2 |
| Fingerprint (v2) | `7f38e904b067dcda592eb946956b03f25f824f169edd35ffa87b3a107de4c326`, 50 файлов |
| Сборка | `buildSteps: clean install` **с тестами**: `RegisterSurfaceRoleTest` 3/3, `BUILD SUCCESS` (прогнано дважды: до и после правок конфигурации) |
| Регистрация | три записи в `AutoConfiguration.imports` — по одной на срез |

`dependsOn` манифеста **не изменился** — измерение, а не пропуск: срез не привёз нового владельца
типов. `source.state` манифеста переписан под S3 (что уехало, почему `dependsOn` не вырос, что стало
с ролями и почему MODULE_API пуст). `WorkspaceManifestTest` — 4/4 после осознанного обновления
fingerprints.

## Прогоны гейтов

| Гейт | Результат |
| --- | --- |
| `platform-register: mvn clean install` | `BUILD SUCCESS`, `RegisterSurfaceRoleTest` **3/3** (46 ролей) |
| `PlatformRegisterModuleTest` | **5/5** — модуль не называет `org.ip`; каждая конфигурация привозит свой срез; последний срез приносит протокол и заменяемый шов; в приложении нет ни одной регистрации; часы порядка и последний их потребитель в одном артефакте |
| `PlatformApiBaselineTest` | baseline обновлён намеренно **только для `platform-register`**; после записи — 4/4, поверхность S3 зафиксирована (`RegisterRecords`, `RecorderSetWriteAuthorizer`, `RecorderSetWriteRequest`, `RecordedRegisterReader`, `RegisterRecords$ForRecorder`) |
| `WorkspaceManifestTest` | 4/4 (`dependsOn` совпадает с workspace-рёбрами; fingerprints обновлены) |
| `PersistenceTypeRegistrationTest` | 6/6 — владелец `org.ipro.register.persistence` — модуль |
| `PlatformAutoConfigurationRegistryTest` | 3/3 — у приложения не осталось регистраций регистра |
| `PlatformSubsystemClosureTest` / `PlatformDependencyDirectionTest` / `PlatformPersistenceModuleTest` | 2/2, 9/9, 4/4 |
| H2-набор движка | `RegisterKeyWritesTest` 12/12, `RegisterKeyChangesTest` 9/9, `RegisterKeyChangePermissionsTest` 7/7, `RegisterReaderTest` 8/8, `RegisterDescriptorTest` 5/5, `RegisterPeriodicityTest` 4/4, `RegisterListFilterPhasesTest` 3/3, `RegisterMetadataValidatorTest` 17/17 (+Mapping 11/11), `RegisterStartupValidatorTest` 6/6, `RlsRecorderSetWriteAuthorizerTest` 1/1, `RegisterKeyDialogTest` 2/2, `RegisterSliceCteGeneratorTest` 29/29 |
| H2-набор набора владельца | `InformationRegisterRecorderPersistenceTest` **21/21** (откат, замена, авторизация строки), `MetadataDrivenAggregateTelemetryTest` 3/3, `RegisterBatchReadIT` **6/6** (после правки), `ReportStudioAutoConfigurationSmokeTest` 4/4, `PersistenceRegistrationIT` 3/3, `RegisterListFormToolbarTest` 4/4 |
| H2-набор пилота | `NomenclaturePricePersistenceTest` 25/25, `NomenclaturePriceFormTest` 6/6, `NomenclaturePriceLifecycleTest` 4/4, `NomenclaturePriceRegisterOracleTest` 7/7 |
| **PostgreSQL 17, изолированная база** (`scripts/register-s3-postgresql.ps1`) | `RecorderSetWriterPostgresIT` **6/6** + `RegisterWriteConcurrencyPostgresIT` **21/21** = **27/27**, `BUILD SUCCESS` (`g3_register_test_r11_a6e21aa1585c4e37b2e0e8cb124260fb`) |

PostgreSQL-гейт среза запускается так же, как S2-шный: скрипт читает `config/application-local.properties`,
создаёт изолированную базу, гоняет набор владельца и гонки записи по ключу на **одном** сервере — срез
взял часы порядка у S2, поэтому проверять их порознь значило бы проверять не то.

Замеры D1 обновлены намеренно (`-Dplatform.measurements.write=true`): `app-source-files` 202 → 263,
`platform-tree-files` 184 → 146, `platform-artifact-files` 525 → 571, `named-platform-types` 285 → 310.
Числа описывают дерево целиком, а не только срез: прикладные файлы за это время добавляла и чужая
незавершённая работа. Расхождение существовало до среза — документ был устаревшим.

## Чужие красные гейты (не относятся к срезу)

- `PlatformPublicSurfaceTest` — незавершённая работа UniDR/REST: wildcard-импорты и fully-qualified
  ссылки в `org/ip/unidr/**`, `EntityRef` в `UniDrQuerySourceService`, 19 платформенных типов без
  reviewed-роли (reportstudio/filtergrid/rest/rls). Ни одного типа регистра в списке нет.
- `PlatformPackageDependencyMatrixTest` — не хватает reviewed-рёбер `org.ipro.navigation.*`
  (незакоммиченная работа навигации); рёбер регистра в списке нет.
- `PlatformVaadinSurfaceTest` — новый тест называет `org.ipro.form.coordinator.FormCoordinator`.
- `RegisterSliceReportSecurityTest` (2 падения + 14 ошибок) и `NomenclaturePriceCteSpikeTest` —
  `Cte root does not have an entity type` из `ReportReadAuthorizer` (изменён параллельной работой,
  Hibernate). Стек не проходит через типы регистра; `RegisterSliceCteGeneratorTest` 29/29 зелёный.
- `PilotRegisterListToolbarIT` — `missingDeclaredRights()` возвращает один и тот же ключ дважды:
  незакоммиченная правка P-U (`AccountingObjectPilotInitializer` использует два ключа, которые после
  переработки совпали). Сам тест не менялся — расхождение между чужим незакоммиченным кодом и
  закоммиченным тестом.

## Оставшийся долг

1. **ADR-0025 — lifecycle строки на пути набора владельца.** Перемещение этого не решает и не
   прячет: `RecorderSetWriter` пишет `persist`/`remove` напрямую, хуки `EntityLifecycle` строки не
   вызываются, а диагностика по-прежнему показывает handler по типу. Обе ветки решения из плана
   (вызывать хуки до Bean Validation либо не вызывать, но сделать отсутствие видимым диагностике)
   остаются открытыми вместе со своей приёмкой: «хук ровно один раз на вставленную строку и ни разу
   на неизменённую», тест порядка, согласованность диагностики. Срез S3 закрыт по перемещению;
   решение названо открытым, а не потеряно в статусе «сделано».
2. **S4** (`platform-register-vaadin`): вместе с UI-модулем пересматриваются роли UI-типов
   (`RegisterFormCustomization`, `RegisterKeyDialog`, представления регистратора).
3. **S5**: тесты движка (`src/test/java/org/ipro/register/**`) уезжают вместе со срезами — сегодня
   они живут в приложении и гоняются его сборкой; тогда же снимается прикладной ранний отказ
   `NomenclaturePriceLifecycle`.
