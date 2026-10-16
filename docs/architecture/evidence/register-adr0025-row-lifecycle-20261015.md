# Evidence: ADR-0025 — lifecycle строки регистра на пути набора владельца

Дата: 2026-10-15. Решение: [ADR-0025](../decisions/ADR-0025-row-lifecycle-on-recorder-set.md).
План: [вынос регистра](../information-register-extraction-plan.md), §3 (решения) и §4 (S3).
Долг был назван открытым в [evidence S3](register-s3-extraction-20261014.md) и
[evidence S4](register-s4-extraction-20261015.md); здесь он закрыт.

Что проверялось: путь набора владельца (`RegisterRecords.forRecorder` → `RecorderSetWriter`) и
очистка при удалении документа-владельца (`RecorderSetDeleteParticipant`) вызывают
`EntityLifecycle`-хуки строки **ровно один раз на вставленную строку**, **ни разу на неизменённую**
и в платформенном порядке «подготовка строки → порядок записи → хук → Bean Validation → запись».

## 1. Замер, выбравший ветку решения

Ветка «вызывать хуки на пути набора» выбрана по замерам M1–M6 ADR-0025; они воспроизводимы
поиском по коду приложения, а не объявлены:

| # | Что измерено | Значение (проверено) |
| --- | --- | --- |
| M1 | прикладных `EntityLifecycle` handler'ов | 12: `User`, `Role`, `AttributeType`, `AttributeValue`, `Nomenclature`, `NomSklAttribute`, `PrdSpec`, `ReceivingDocument`, `GridFormView`, `AccountingObjectInstallation`, `AccountingObjectCorrection`, `NomenclaturePrice` |
| M2 | из них на строку регистра | 1 — `NomenclaturePriceLifecycle` (`beforeUpdate`), и это **самостоятельный** регистр `NomenclaturePrice` (запись по ключу, хуки уже вызывались) |
| M3 | регистр на пути набора | 1 — `NomenclatureAccountingObject` (`recorderTypes = {AccountingObjectInstallation, AccountingObjectCorrection}`); handler'а строки у него **нет** (`grep -i lifecycle` по `src/main/java` — 0 совпадений) |
| M4 | порядок в соседних платформенных путях | хук **до** Bean Validation: `RegisterKeyWrites` (`lifecycles.beforeSave` → `validate`), `RegisterKeyChanges` (`beforeSave`/`beforeUpdate` → `validator.validate`) |
| M5 | где валидировались строки набора до решения | внутри `prepareRows`, то есть до присвоения окончательного порядка записи |
| M6 | диагностика | `lifecycleRows` описывает handler **типа**; оси «путь записи» нет |

Следствие для цены решения: сегодня **ни одно прикладное правило не пропускается молча** (M2/M3) —
пропускалась сама возможность его написать, а само обещание контракта ADR-0005 расходилось с
машиной. Поэтому отклонённый вариант 2 («не вызывать, но сделать видимым») стоил бы шестой оси
диагностики и правки контракта, не выигрывая ничего для M1, а вариант 3 («сопоставлять строки по
ключу») — протокола ADR-0019/0020.

## 2. Замер приёмки: счётчик хуков по строкам

Счётчик фиксирует каждый вызов отдельно (списком экземпляров), поэтому «ровно один раз» отличимо от
«хук вообще был». Числа ниже — то, что реально проверяют тесты
(`InformationRegisterRecorderPersistenceTest`, `RecorderSetWriterPostgresIT`).

| Операция пути набора | Вставлено | `beforeSave` | `onSave` | `beforeDelete` | `beforeUpdate` | Строки без хуков |
| --- | --- | --- | --- | --- | --- | --- |
| добавление 2 строк при 1 существующей (H2, `rowHooksRunExactlyOncePerInsertedRowAndNeverForUntouchedRows`) | 2 | **2** | **2** | 0 | 0 | 1 (существующая) |
| замена набора 2 → 3 через участника агрегата (H2, `setReplacementDeliversInsertHooksForNewRowsAndDeletionHooksForOldOnes`) | 3 | **3** | **3** | **2** | 0 | 0 |
| `clear` набора из 2 строк (H2, `removalOperationsDeliverDeletionHooksOnlyForRemovedRows`) | 0 | 0 | 0 | **2** | 0 | 0 |
| `delete` одной строки из двух (H2, там же) | 0 | 0 | 0 | **1** | 0 | 1 (соседняя) |
| очистка при удалении владельца, два регистра (H2, `documentDeleteCleanupDeliversRemovalHooksForEveryOwnedRowOfEveryRegister`) | 0 | 0 | 0 | **1 + 1** | 0 | 0 |
| вставка 2 / замена 3 / добавление 1 (PostgreSQL, `rowLifecycleHooksRunOncePerInsertedRowAndVetoRollsBackOrderAndVersion`) | 2 / 3 / 1 | **2 / 3 / 1** | **2 / 3 / 1** | 0 / **2** / 0 | 0 | все существующие строки |
| вето из `beforeSave` (H2 `vetoedRowHookRollsBackTheWholeOwnerSave`, PostgreSQL там же) | 1 (до вето) | 1 | **0** | — | — | набор, версия владельца и порядок записи откачены |

Дополнительно измерено (те же тесты):

- **контекст хука — контекст строки**: `aggregateType` = тип строки, имя операции
  `set-register-recorder-set:<Регистр>` / `delete-register-recorder-set:<Регистр>`
  (у записи по ключу — `*register-key:`), поэтому правило различает путь;
- **хук видит строку в окончательном виде**: номер строки присвоен (`containsExactly(1, 2)`, добавление → `4`),
  порядок записи настоящий (`containsOnly(1L)` на свежем регистре), период нормализован
  (замер: вход `2026-10-15T13:45:00.000123` → хук и база видят `…13:45:00.123`, периодичность
  регистра `EXACT`);
- **порядок «хук → валидация»**: хук, обнуляющий обязательное поле, получает отказ
  `RECORDER_SET_INVALID: amount …` (то есть валидация идёт после хука, а не до);
- **у хука строки нет прав владельца**: `RegisterRecords.forRecorder(context)` из хука строки
  отклоняется с `REGISTER_OWNER_WRITE_CONTEXT_REQUIRED`;
- **выключателя хуков нет**: `javap` по собранному артефакту показывает, что у `RecorderSetWriter`,
  `RecorderSetContributionParticipant`, `RecorderSetDeleteParticipant` и `RegisterRecords` **все**
  конструкторы требуют `EntityLifecycleRegistry` — конструктора «с пустым реестром по умолчанию»
  не существует, и конфигурация протокола передаёт реестр во все три бина.

## 3. Протокол прогонов

Все команды — из корня `GitVaa25`, JDK 21, локальный `.m2` (`~/.m2/repository`).

1. Сборка и установка изменённых платформенных артефактов (негативный гейт компиляцией: приложение
   компилируется против собранного модуля, а не против исходников):
   `mvn -o -f platform-build/pom.xml -pl :platform-register -am -DskipTests install` → **BUILD SUCCESS** (10 модулей, ~12 s).
2. Тесты модуля: `mvn -o -f platform-build/pom.xml -pl :platform-register test` →
   `RegisterSurfaceRoleTest` **3/3**, `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`.
3. H2-приёмка и соседние пути:
   `mvn -o test -Dtest='RegisterKeyWritesTest,RegisterKeyChangesTest,RegisterStartupValidatorTest,RegisterReaderTest,RlsRecorderSetWriteAuthorizerTest,InformationRegisterRecorderPersistenceTest'` →
   **64/64** (`InformationRegisterRecorderPersistenceTest` 28, `RegisterKeyWritesTest` 12,
   `RegisterKeyChangesTest` 9, `RegisterReaderTest` 8, `RegisterStartupValidatorTest` 6,
   `RlsRecorderSetWriteAuthorizerTest` 1), `Failures: 0, Errors: 0, Skipped: 0`.
4. Гейт поверхности: решение **меняет публичную подпись** — `RegisterRecords` (MODULE_API) получает
   обязательный `EntityLifecycleRegistry`. Поэтому поверхность артефакта перезаписана намеренно и
   видна в диффе:
   `mvn -o test -Dtest=PlatformApiBaselineTest -Dplatform.api.baseline.write=true -Dplatform.api.baseline.artifact=platform-register`,
   затем без `write` → `Tests run: 4, Failures: 0`. Участники набора и писатель остаются INTERNAL
   (`register-surface.txt`), поэтому в baseline они не попадают — поверхность растёт только там,
   куда приложение действительно смотрит.
5. PostgreSQL-гейт — воспроизводимым скриптом (тем же порядком, что у соседних срезов):
   `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/register-adr0025-postgresql.ps1` →
   одноразовая база `g3_register_test_adr0025_*` (имя печатается и сохраняется для диагностики;
   тесты включаются переменными `IPRO_REGISTER_G3_JDBC_URL`, `IPRO_REGISTER_G3_DB_USER`,
   `IPRO_REGISTER_G3_DB_PASSWORD` из `config/application-local.properties`) →
   **48/48**: `RecorderSetWriterPostgresIT` 7, `RegisterKeyWriteConcurrencyPostgresIT` 20,
   `RegisterWriteConcurrencyPostgresIT` 21, `BUILD SUCCESS`.

В PostgreSQL-прогоне отдельно проверено то, чего H2 не показывает: порядок записи, выделенный
операцией, **откатывается вместе с вето хука** (`clockOrder` до и после вето совпадают), версия
владельца не растёт, ни одна строка не остаётся записанной.

## 4. Найденное при приёмке

Пункты 1–3 исправлены здесь же; пункт 4 — наблюдение чужой красноты, намеренно не тронутое.

1. **Устаревшие классы приложения затеняли уехавший артефакт.** В `target/classes` приложения
   оставались 63 класса `org/ipro/register/*` от раскладки до выноса. Компиляция тестов видела их
   вместо JAR `platform-register` и падала с «`EntityLifecycleRegistry` cannot be converted to int»
   — то есть сигнатуры выглядели старыми при уже собранном новом модуле. Лечится `mvn clean`;
   вывод для будущих срезов: **после переезда пакета чистая сборка потребителя обязательна**, иначе
   гейт компиляцией даёт ложную картину.
2. **`RegisterWriteConcurrencyPostgresIT` собирал `RegisterRecords` без реестра** — пропущенный
   аргумент после решения. Исправлено передачей реестра; тест остался «чужим» для ADR-0025 по
   предмету (он про гонки записи), но больше не компилируется против старого контракта.
3. **Гейт подписи поймал изменение контракта сам.** `PlatformApiBaselineTest` упал строкой
   `baseline: … RecorderSetWriteAuthorizer, PlatformTransactionManager` против
   `код: … EntityLifecycleRegistry, RlsFilterActivator, …` — то есть новый обязательный участник
   виден как изменение контракта, а не как деталь реализации. Это и есть ожидаемое поведение гейта:
   перезапись сделана отдельной командой (§3.4), а не «заодно» с прогоном.
4. **Замечено, но не тронуто: чужая краснота в том же рабочем дереве.** Полный прогон гейтов
   красный не из-за этого решения: `platform-rls.api` разошёлся с кодом (`RlsGrantMatching` против
   `RlsHibernateWriteGuardInstaller` — незакоммиченный файл другой работы), `PlatformPublicSurfaceTest`
   (4 из 10: новые файлы `org/ip/application/access/*`, `org/ip/views/admin/Permission*`)
   и `WorkspaceManifestTest` (отпечаток чужого in-repo проекта). Перезапись их baseline и правка
   их реестров — это принятие чужих решений, поэтому здесь они только названы; baseline
   `platform-register` обновлён **только** фильтром по своему артефакту.

## 5. Что не покрыто

- **Прикладной пилот (`AccountingObjectPilotIT`) не запускался в этом шаге.** По замеру M3 регистр
  пилота `NomenclatureAccountingObject` сегодня не имеет handler'а строки, поэтому пилотный прогон
  не является доказательством для этого решения; его собственный протокол (одноразовая база,
  `app.accounting-pilot.*`, скрипт `scripts/accounting-object-pilot.ps1`) не воспроизводился здесь.
  Первое прикладное правило строки регистра обязано приезжать вместе со своим пилотным прогоном.
- **События и аудит строк на этом пути не публикуются** — граница решения (ADR-0025 §7), а не
  пробел приёмки.
- **`beforeAggregateSave` и `beforeUpdate` на этом пути не вызываются** — проверено отсутствием
  вызова (счётчик), отдельного положительного сценария у них здесь быть не может (ADR-0025 §3–4).
- **Полнота перечня путей записи приёмкой этого решения не доказывается** — она вынесена в
  отдельный гейт: [PlatformWritePathLifecycleTest](../../../src/test/java/org/ipro/PlatformWritePathLifecycleTest.java)
  с реестром `src/test/resources/platform-write-paths.txt` требует, чтобы каждый путь записи
  платформы был объявлен (`HOOKS`/`DELEGATES`/`HOSTED`/`EXCEPTION`) — см.
  [evidence гейта](platform-write-paths-gate-20261015.md). Это закрывает ровно ту причину, по
  которой дефект ADR-0025 жил незамеченным: путь набора владельца существовал годы, а "кто
  вообще пишет" не проверял никто.
- Диагностика (`EntitySummaryPanel` / `lifecycleRows`) по-прежнему описывает handler **типа**:
  после решения состояние «путь хуки не вызывает» перестало существовать, отдельная ось не вводилась.
