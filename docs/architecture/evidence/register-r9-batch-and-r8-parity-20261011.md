# Р9 batch/точечное чтение и приёмка Р8 v1.2

Дата: 2026-10-11. Статус: Р9 закрыт на H2 по трём частям; приёмка Р8 v1.2
(UI/service parity и подсчёт boilerplate) закрыта на H2. Пользовательская проба
интерфейса и PostgreSQL-профиль Р9 остаются отдельными незавершёнными приёмками.

## Что было и что изменено

| Часть | До | После |
| --- | --- | --- |
| Точечное чтение среза | `list(0, 1)` со count-запросом | `RegisterSliceReader#first` — тот же победитель без total |
| Строки документа учёта | чтение среза на каждую строку | `RegisterSliceReader#latestForKeys` — один запрос на набор ключей |
| Потребители batch | `AccountingObjectSaveService`, `AccountingObjectRegisterGeneration` читали срез на строку | один `currentTypes(document, ids)` на документ |
| Тесты старого канала | два набора стабов `access.list` закрепляли прежний контракт | переведены на `first`; `list` на точечном чтении запрещён проверкой |

Реализация batch (`src/main/java/org/ipro/register/RegisterSliceReader.java`): ключи
превращаются в дизъюнкцию наборов измерений и подставляются в тот же предикат
победителя, что и у страницы, поэтому права, FetchPlan, экспозиция и telemetry — те
же. Контракт набора: одна дата для всех ключей; ключ содержит ровно измерения
регистра (ссылки — сохранённые идентификаторы); дубли читаются один раз; пустой
набор даёт пустой результат; отсутствующие и запрещённые правами строки просто не
появляются — их отсутствие обрабатывает вызывающий. Отказы до данных:
`SLICE_BATCH_KEY` (неполный или лишний ключ), `SLICE_VALUE_TYPE` (неверный тип
значения), `SLICE_BATCH_SIZE` (больше `MAX_BATCH_KEYS = 200`), `SLICE_RECORDER_TYPE`.
Дата на каждый ключ — другой контракт и не является скрытым расширением метода.

## Замеры (H2, `RegisterBatchReadIT`, 6 проверок)

| Окно замера | SQL |
| --- | --- |
| batch, 1 ключ | 4 |
| batch, 6 ключей | 4 |
| точечное чтение, 1 ключ (`first`) | 3 |
| точечный канал, 6 ключей | 18 |
| страница `(0, 1)` с count | 4 |
| SELECT по таблице регистра при сохранении корректировки: 1 позиция / 6 позиций | 6 / 6 |

Числа включают SQL окна целиком (в том числе обращения к таблицам прав), поэтому
служат относительным доказательством, а не обещанием «один SQL на вызов»:
batch не зависит от числа ключей (4 = 4), точечное чтение не платит count
(3 против 4), точечный канал платит за каждый ключ (18 против 4), а сохранение
документа читает срез один раз независимо от числа позиций (6 = 6).
Проверки печатают эти числа в отчёт теста (`[Р9]`).

## Parity с oracle

- `batchEqualsPointReadOracleForEveryKeyIncludingAbsence` — для семи номенклатур
  (шесть со строками среза, одна без) значение batch равно точечному чтению по
  каждому ключу, включая отсутствие.
- `batchExcludesTheEditableDocumentsOwnRecorderLikeThePointRead` — вычет собственного
  регистратора у редактируемого документа совпадает с точечным чтением: ускорение не
  изменило семантику среза.
- `correctionDocumentsKeepTheirOwnTypeAndPreviousTypeFromTheBatchRead` — запись через
  batch заполняет строки документа так же, как прежний построчный путь (свой ресурс
  корректировки и предыдущий тип из среза).

## Контракт обновления (названный долг исходного снимка)

Поддерживаемые каналы проверены тестами, которые прогонялись этим пакетом:

1. независимый регистр — canonical create/update: исходное состояние для
   `beforeUpdate` берётся отдельным чтением с `detach` (`CanonicalWriteExecutor#mutateNamed`),
   а не сравнением managed-объекта с самим собой;
2. форма и отсоединённый payload (`NomenclaturePriceFormTest`, `NomenclaturePricePersistenceTest`);
3. подчинённый регистр — команда записи по регистратору плюс отдельная команда смены
   ключа (ADR-0021): собственный снимок, optimistic version, rollback и durable-аудит.

Fail-closed: managed payload без независимого исходного состояния отклоняется
(`canonicalUpdateRejectsAnUnknownOriginalForAManagedEntity`,
`managedKeyChangeIsRejectedAtFlushEvenWithoutCanonicalSave`). Непубличный
`Session.getEntityEntry`/`SessionImpl` в write-путях не используется, чужой
persistence context не очищается. Отдельная typed resource-only команда строки в этом
пакете не вводится: потребителя нет, запись по ключу и ресурсу относится к Р11.

## Регрессии, найденные при закрытии

Предыдущая запись плана называла целевые наборы зелёными, но два набора стабов
закрепляли старый канал:

- `RegisterReaderTest.recordedPeriodicGetLastUsesFullWinnerOrderOnCanonicalRead`
  стабил `access.list` и проверял `Pageable` — после перевода `getLast` на `first`
  точка возвращала пусто. Тест переведён на bounded-контракт и дополнительно
  проверяет `verify(access, never()).list(...)`.
- `RegisterSliceCteGeneratorTest` — четыре проверки точечного чтения
  (`criteriaPointReaderDistinguishesMilliseconds`,
  `canonicalPagedRecorderSliceMatchesCteAndIndependentWinnerOracle`,
  `canonicalPointPageAndCteAgreeForEveryNormalizedCalendarPeriodicity`,
  `recordedPeriodicCteUsesPeriodThenRecordingOrderThenLineAndProjectsRecorderScalars`)
  падали по той же причине; в стабы добавлен канал `first` (общий помощник
  `withBoundedFirst` и `readFirst` через canonical executor).

Оба набора зелёные после исправления; ни один тест не пропущен и не ослаблен.

## Приёмка Р8 v1.2

- **Parity прав UI и сервиса.**
  `AccountingObjectPilotIT.uiKeyChangeDecisionMatchesTheCommandDecisionForTheSameSubject`
  прогоняет пять субъектов (нет прав / только чтение / чтение и запись без операции /
  чтение, запись и операция / операция без данных) на одной строке: решение UI
  (`RegisterKeyChangePermissions#canChangeKey`) совпадает с фактическим решением
  команды, а действие предлагается только при полном наборе прав. Oracle — сам вызов
  команды, не вторая копия правила.
  `PilotRegisterListToolbarIT` подтверждает: create предлагается только у независимого
  регистра, фикстура пилота выдаёт ровно те операции, которые спрашивает тулбар,
  стартовый отчёт называет недостающее право его ключом. `RegisterListFormToolbarTest`
  закрепляет, что подчинённый список read-only и не предлагает create.
- **Прикладной boilerplate.** Декларация регистра — одна аннотация `@RegisterMetadata`
  на сущности (файл 69 строк вместе с mapping, индексами и RLS-объявлением).
  Стандартные список, история, срез, подписи и ссылки строятся из descriptor:
  `RegisterFormCustomization` сам создаёт `RegisterSliceReader` для формы, прикладных
  форм и reader-регистраций нет, ручного HQL/CTE в UI и сервисе регистра нет.
  Измеренный остаток: приложение, чей сервис инжектит `RegisterSliceReader<X>`,
  объявляет его bean — `AccountingObjectRegisterConfiguration`, 9 строк. Автоматическая
  регистрация reader'ов по descriptor в этом пакете не вводится.

## Прогон

Команда из корня проекта (JDK 21, Maven, offline, профиль H2):

```
mvn -o -Dtest=RegisterBatchReadIT,AccountingObjectPilotIT,NomenclaturePriceFormTest,NomenclaturePricePersistenceTest,\
NomenclaturePriceLifecycleTest,RegisterKeyChangesTest,RegisterKeyChangePermissionsTest,RegisterReaderTest,\
RegisterListFormToolbarTest,PilotRegisterListToolbarIT,PilotSecurityQueryMeasurementIT,PilotFixtureWriteBoundaryIT,\
RegisterSliceCteGeneratorTest -DfailIfNoTests=false test
```

| Класс | Выполнено |
| --- | --- |
| AccountingObjectPilotIT | 29 |
| RegisterSliceCteGeneratorTest | 29 |
| NomenclaturePricePersistenceTest | 20 |
| RegisterKeyChangesTest | 9 |
| RegisterReaderTest | 8 |
| RegisterKeyChangePermissionsTest | 7 |
| RegisterBatchReadIT | 6 |
| NomenclaturePriceFormTest | 6 |
| NomenclaturePriceLifecycleTest | 4 |
| RegisterListFormToolbarTest | 4 |
| PilotRegisterListToolbarIT | 3 |
| PilotFixtureWriteBoundaryIT | 3 |
| PilotSecurityQueryMeasurementIT | 1 |

Итог прогона приведён в записи плана; failures 0, errors 0, skipped 0.

## Что не проверено

- PostgreSQL-профиль Р9: batch и точечное чтение измерены только на H2. PostgreSQL-гонки
  write-контура — отдельный прогон Р8 от 2026-10-09, он этим пакетом не повторялся.
- Пользовательская проба интерфейса Р8 (лист пробы ждёт ответа пользователя).
- Полный общий прогон приложения и платформы; платформенные canonical read/write наборы
  проверялись только целевыми классами.

## Замеченные красные гейты, не относящиеся к этому пакету

Отдельная проверка смежных гейтов (`PlatformVaadinSurfaceTest`, `PlatformCoreSurfaceTest`,
`PlatformPublicSurfaceTest`, `PlatformApiBaselineTest`, модульные тесты) дала: 45 проверок,
6 падений. Падения не относятся к этому пакету и не вылечены здесь намеренно:

| Проверка | Улика |
| --- | --- |
| `PlatformCoreSurfaceTest.applicationTestReferencesToInternalOrModuleTypesAreFrozenPerFile` | `org/ip/unidr/query/UniDrReceivingItemsQueryTest.java`, `org/ip/views/unidr/smoke/UniDrQuerySmokeApplication.java` — файлы вне диффа этого пакета |
| `PlatformPublicSurfaceTest` (5 проверок: wildcard-импорты, fully-qualified ссылки, роль типа, заморозка legacy-ссылок, замер `app-source-files`) | `org/ip/unidr/**` и `org/ip/views/unidr/**`, замер `d1-surface-measurements.md` (240 против 202) |

Признаки того, что это не регрессия Р9/Р8: улики (`import org.ipro.reportstudio.data.*`,
`CanonicalReadExecutor`) присутствуют уже в HEAD в нетронутых файлах, реестры
(`platform-core-surface.txt`, `platform-public-surface.txt`) в рабочем дереве не изменены,
а набор, содержащий все затронутые этим пакетом тесты (`PlatformVaadinSurfaceTest`),
зелёный — 6 / 6. Эти гейты остаются красными в текущем рабочем дереве и требуют отдельного
решения владельца тех файлов.

## Источники

- [Batch и точечное чтение](../../../src/main/java/org/ipro/register/RegisterSliceReader.java).
- [Сервис среза пилота](../../../src/main/java/org/ip/application/register/AccountingObjectRegisterService.java).
- [Потребители batch](../../../src/main/java/org/ip/application/register/AccountingObjectSaveService.java).
- [Проверки batch и замера](../../../src/test/java/org/ip/application/register/RegisterBatchReadIT.java).
- [Parity прав и пилот](../../../src/test/java/org/ip/application/register/AccountingObjectPilotIT.java).
- [Тулбарный parity](../../../src/test/java/org/ip/config/PilotRegisterListToolbarIT.java).
- [Предыдущий протокол Р8 на PostgreSQL](register-r8-postgresql-20261009.md).
