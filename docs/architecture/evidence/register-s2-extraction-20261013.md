# D-R S2: запись по ключу и правила ключа переехали в `platform-register`

Дата: 2026-10-13. Статус: срез S2 закрыт — команды записи по ключу и смены ключа, их именованные
операции, охранник mapping'а и часы порядка живут в артефакте, прикладная обвязка записи удалена
(в приложении остался только протокол набора владельца — S3), правило ключа владеется платформой.
Предыдущие срезы: [S0](register-s0-extraction-20261013.md),
[S1](register-s1-extraction-20261013.md); план — [план выноса](../information-register-extraction-plan.md).

## Что переехало и чем это не является

Переезд снова **перемещение**: `git diff -M` показывает нулевые изменения содержимого у
`RegisterKeyChanges`, `RegisterKeyWrites`, `RegisterKeyChangePermissions`,
`RegisterKeyWritePermissions`, `RegisterKeyWriteGuardInstaller`, `RegisterRowState`,
`RegisterWriteMutex`, `RecorderOrderClockBootstrap` и `persistence/RecorderOrderClock`. Изменены
содержательно только две вещи, и обе названы ниже: засев часов приехал с сущностью, а охранник
получил владение порядком «нормализация → сравнение».

| Тип | Куда | Почему он в этом срезе |
| --- | --- | --- |
| `RegisterKeyWrites` | `platform-register` | команда `set`/`delete` по полному ключу с bounded retry и verdict-контрактом (ADR-0023) |
| `RegisterKeyChanges` | `platform-register` | отдельная команда смены ключа существующей строки (ADR-0021) |
| `RegisterKeyChangePermissions` | `platform-register` | именованная операция `CHANGE_KEY`: одно решение для команды и интерфейса (ADR-0017) |
| `RegisterKeyWritePermissions` | `platform-register` | именованные операции `SET_KEY`/`DELETE_KEY`, регистрация без выдачи грантов |
| `RegisterKeyWriteGuardInstaller` | `platform-register` | охранник mapping'а и UPDATE-событий: ключ неизменяем для обычных путей |
| `RegisterRowState` | `platform-register` | доступ к полям строки и независимый исходный снимок для lifecycle (урок Р9) |
| `RegisterWriteMutex` | `platform-register` | общая точка сериализации записи (см. развилку ниже) |
| `RecorderOrderClockBootstrap` + `persistence/RecorderOrderClock` | `platform-register` | часы порядка: цель мьютекса и владелец строки порядка, вместе со своим `@EntityScan` и засевом |
| `config/RegisterKeyWriteConfiguration` | `platform-register` (**новая**) | конфигурация среза со своими бинами |

Чем срез не является: модуль по-прежнему **не умеет заменять набор владельца**. `RegisterRecords`,
`RecorderSetWriter`, его участники и `RecordedRegisterReader` остаются приложению до S3 — и
пользуются теми же часами порядка и тем же мьютексом, а не вторым механизмом. Гейт
`PlatformRegisterModuleTest.eachSliceConfigurationBringsOnlyItsOwnSlice` проверяет это текстом
конфигураций, а не обещанием: read-конфигурация S1 не знает команд, key-write-конфигурация S2 не
знает протокола набора владельца.

## Развилка плана, решённая замером

План оставил решение на старте S2: `RegisterWriteMutex` импортирует `RecorderOrderClock` (срез S3),
значит либо часы переезжают в S2, либо мьютекс ждёт S3. Замер
`node scripts/analyze-register-closure.mjs --slice S2` назвал **ровно одну** ссылку за границу
среза — именно эту, — и ссылка рабочая: `RegisterKeyChanges` берёт под мьютексом ключ
подчинённого регистра (`if (descriptor.recorded()) RegisterWriteMutex.lock(...)`), чтобы смена
ключа не разошлась с заменой набора владельца. Оставить мьютекс в приложении означало бы оставить
в приложении часть самой команды.

Решение: **часы переехали вместе с командой**. Следствие названо, а не спрятано: артефакт стал
владельцем пакета `org.ipro.register.persistence` вместе с сущностью, её `@EntityScan` и её засевом.
Этим закрыт долг, объявленный срезом S1 («засев часов остаётся приложению, пока сущность
принадлежит приложению»): долг не был потерян — он переехал ровно тогда, когда переехала сущность.
Гейт `theOrderClockMovedWithItsOwner` требует, чтобы scanning и засев объявлял владелец сущности,
а приложение их не объявляло.

## Правило ключа: что стало платформенным, а что осталось прикладным

1. **Авторитет — охранник платформы.** `RegisterKeyWriteGuardInstaller` (в модуле с S2) сравнивает
   нормализованный ключ на flush и запрещает смену ключа любому пути, кроме команды. Это
   подтверждено тестами, которые проверяют именно обычные пути:
   `NomenclaturePricePersistenceTest.managedKeyChangeIsRejectedAtFlushEvenWithoutCanonicalSave` и
   `managedDimensionChangeCannotRideWithAResourceUpdate` ждут `REGISTER_KEY_CHANGE_REQUIRED`.
2. **Порядок «нормализация → сравнение» тоже платформенный.** Охранник нормализует период по
   объявленной периодичности перед сравнением. Замерено, а не предположено: если снять эту
   нормализацию, тест `managedPeriodEditInsideTheSameDailyBucketKeepsTheKey` **остаётся зелёным**,
   потому что JPA-callback `RegisterPeriodListener` сегодня успевает выровнять значение раньше
   охранника. Поэтому строка оставлена осознанно и названа своей целью: она делает правило
   независимым от порядка слушателей Hibernate, а не чинит наблюдённый дефект. Новый тест —
   регрессионный забор наблюдаемого поведения: правка времени внутри того же дня ключом не
   считается и хранится выровненной.
3. **Прикладная нормализация убрана.** `NomenclaturePriceLifecycle.beforeSave` больше не
   нормализует период: это было второе место нормализации на записи, и оно могло разойтись с
   listener'ом. Нормализация вызывается там, где нужна для сравнения.
4. **Прикладной ранний отказ остался — и это названная граница, а не дубль правила.**
   `NomenclaturePriceLifecycle.beforeUpdate` даёт canonical-ошибку `ValidationException` до flush и
   на языке предмета; авторитетом он не является. Убрать его целиком срез не смог по найденной
   архитектурной причине: реестр `EntityLifecycle` допускает **ровно один** authoritative handler на
   тип сущности, поэтому общее правило платформы (охранник) и предметный handler не могут
   сосуществовать как два handler'а. Класс переписан так, чтобы это было видно из кода: он объявляет
   себя ранним отказом и объясняет, что снять его можно только вместе с прикладной обвязкой (S5).
5. Побочный эффект, который тоже правило: правка времени внутри того же дня больше не отвергается
   прикладным ранним отказом (сравнение идёт по нормализованным значениям). Это и есть поведение,
   которое декларация разрешает, и оно закреплено тестом.

## Артефакт и владение сборкой

| Факт | Значение |
| --- | --- |
| Координата | `org.ipro:platform-register:1.0-SNAPSHOT` |
| Новые зависимости | `platform-rls` (11 типов — единственный авторитет операции, ADR-0017/0023), `platform-events` (canonical lifecycle-хуки и события после commit), `platform-telemetry` (durable-аудит через существующий `EventSink`, новых таблиц нет), `platform-identity-api` (`IdentifiableEntity` — тот же Java-only leaf, на который опирается `platform-core`), `spring-orm`, `spring-tx`, `spring-boot-persistence`, `jackson-databind` |
| Fingerprint (v2) | `ef0307a9d65ae3bc1e8d29895d0d872bc9d61dc469f68871a4eb091986ec7ec9`, 39 файлов |
| Сборка | `buildSteps: clean install` **с тестами**: `RegisterSurfaceRoleTest` 3/3, `BUILD SUCCESS` |
| Регистрация | две записи в `AutoConfiguration.imports` — по одной на срез (read S1, key write S2) |

`dependsOn` манифеста обновлён по фактическим compile-импортам и включает `crudui` (внешний реактор
leaf-контрактов), `filtergrid`, `platform-contracts`, `platform-core`, `platform-events`,
`platform-persistence`, `platform-rls`, `platform-telemetry`. Гейт `WorkspaceManifestTest`
подтвердил обе стороны: `dependsOn` совпадает с реальными workspace-рёбрами, а `platform-register`
**отсутствует в списке дрейфа fingerprints** (в списке — пять чужих проектов, см. ниже).

## Reviewed-реестр ролей

`META-INF/register-surface.txt` — **35 типов**: APP_API 18, APP_SPI 5, **MODULE_API 2**, INTERNAL 10.

- APP_API прибавили обе команды и обе таблицы именованных операций — по всё тому же признаку
  «приложение называет тип» (сервис цен, форма списка, диалог ключа);
- **MODULE_API впервые не пуст**: `RecorderOrderClock` и его засев нужны следующему срезу (S3).
  Оговорка названа честно и в самом реестре: пока S3 живёт в приложении, прикладной
  `RecorderSetWriter` называет часы порядка напрямую (берёт их из мьютекса и вызывает `advance`).
  Это единственное место, где запись не следует букве «приложение его не называет», и оно исчезает
  вместе с переездом S3, а не в результате решения о роли;
- охранник, чтение состояния строки, мьютекс и вторая авто-конфигурация — INTERNAL: приложение их
  не называет, обещание среза — поведение, а не имена.

API-baseline артефакта перезаписан **только для `platform-register`**
(`-Dplatform.api.baseline.artifact=platform-register`), поэтому чужая правка `platform-rls` в
baseline не попала.

## Прогоны гейтов

| Гейт | Результат |
| --- | --- |
| `platform-register: mvn -o clean install` | `BUILD SUCCESS`, `RegisterSurfaceRoleTest` 3/3 (35 ролей) |
| `PlatformRegisterModuleTest` | 4/4 — модуль не называет `org.ip`, каждая конфигурация привозит свой срез, приложение не собирает ни читатель, ни команды, часы переехали с владельцем |
| `PersistenceTypeRegistrationTest` | 6/6 — `platform-register` объявлен сам-регистрирующимся владельцем `org.ipro.register.persistence` |
| `PlatformAutoConfigurationRegistryTest` | 3/3 |
| `PlatformSubsystemClosureTest` | 2/2 |
| `PlatformDependencyDirectionTest` | 9/9 |
| `PlatformPersistenceModuleTest` | 4/4 |
| `PlatformApiBaselineTest` | расхождений **по `platform-register` нет** (единственное — `platform-rls`, см. ниже) |
| `WorkspaceManifestTest` | `dependsOn` совпадает; `platform-register` отсутствует в дрейфе fingerprints |
| **PostgreSQL 17, изолированная база** (`scripts/register-r11-postgresql.ps1`) | `RegisterKeyWriteConcurrencyPostgresIT` 20/20 + `RegisterKeyWritesPostgresIT` 4/4 = **24/24**, `BUILD SUCCESS` (гонки отсутствующего ключа, проверяемая версия, откат попытки, набор ключей, классификация по SQLState) |
| H2-набор записи по ключу | `RegisterKeyWritesTest` 12/12, `RegisterKeyChangesTest` 9/9, `RegisterKeyChangePermissionsTest` 7/7, `NomenclaturePricePersistenceTest` **25/25** (включая новый `managedPeriodEditInsideTheSameDailyBucketKeepsTheKey`), `NomenclaturePriceLifecycleTest` 4/4, `NomenclaturePriceFormTest` 6/6, `NomenclaturePriceRegisterOracleTest` 7/7, `InformationRegisterRecorderPersistenceTest` 21/21, `RegisterStartupValidatorTest` 6/6, `RegisterMetadataValidatorTest` 17/17 (+Mapping 11/11), `RegisterReaderTest` 8/8, `RegisterDescriptorTest` 5/5, `RegisterPeriodicityTest` 4/4, `RegisterListFilterPhasesTest` 3/3 |

## Чужие красные гейты (не относятся к срезу)

- `PlatformApiBaselineTest` — расхождение в `platform-rls`
  (`entityOperations(Class)` → `entity(Class)`), тема C5-прав.
- `PlatformPackageDependencyMatrixTest` — не хватает reviewed-рёбер `org.ipro.navigation.*`.
- `PlatformPublicSurfaceTest` — незавершённая работа UniDR/REST (wildcard-импорты, fully-qualified
  ссылки, `EntityRef`), замер `app-source-files`: код 263 против 202 в документе (расходился уже в
  `HEAD`, где было 240).
- `WorkspaceManifestTest` — дрейф fingerprints у `platform-contracts`, `platform-rls`,
  `platform-core`, `platform-spring-boot-autoconfigure`, `platform-vaadin`.
- `NomenclaturePriceCteSpikeTest` и `RegisterSliceReportSecurityTest` — `Cte root does not have an
  entity type` в изменённом другим срезом `ReportReadAuthorizer`.
- `RegisterStartupValidatorTest.rejectsAMutableKeyWithoutAnyReader` пришлось поправить: с S2 в
  этом изолированном контексте грузится только read-конфигурация, поэтому отказ приходит от
  `RegisterStartupValidator` напрямую, а не обёрнутым в ошибку создания бина (охранник, который звал
  `initialize()` первым, уехал в key-write-конфигурацию). Проверка стала смотреть на корень цепочки
  включительно: гарантия та же — контекст не поднимается, сообщение содержит все нарушения.

## Оставшийся долг

1. **S3**: протокол набора владельца (`RegisterRecords`, `RecorderSetWriter`, участники,
   `RecordedRegisterReader`) — он уже пользуется часами порядка и мьютексом из модуля; вместе с ним
   уходит и MODULE_API-оговорка про прикладное именование часов.
2. **S5**: прикладной ранний отказ `NomenclaturePriceLifecycle` снимается вместе с прикладной
   обвязкой — либо переездом на платформенное правило, либо названным решением оставить
   canonical-ошибку приложению. Ограничение (один handler на тип) придётся решать там, а не здесь.
3. **S4**: `platform-register-vaadin`; тогда же пересматриваются роли UI-типов.
4. Замеры D1 (`app-source-files` и др.) требуют осознанного обновления: это чужая незавершённая
   работа плюс файлы, уехавшие срезами S1–S2.

PostgreSQL-гейты пройдены на локальном сервере 17.10 изолированной базой (24/24, см. таблицу):
скрипт `scripts/register-r11-postgresql.ps1` создаёт базу `g3_register_test_r11_<guid>` и удаляет её
после прогона, поэтому гонки и блокировки проверены на настоящем сервере, а не на H2.
