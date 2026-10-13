# Замер замыкания `org.ipro.register` и предлагаемый срез контрактов

- Дата: 2026-10-13.
- Статус: замер выполнен и воспроизводим; срез предложен как план работ. Решение о выносе,
  модуле и `EntityKind` **не принято** — оно требует отдельного ADR (см. §6). Детальный план
  работ — [план выноса](information-register-extraction-plan.md) (срезы S0–S5, гейты, приёмка).
- Основание: [ADR-0013 §5](decisions/ADR-0013-information-register-v1.md) называет платформенную
  природу инфраструктуры регистра в прикладном пакете осознанным временным долгом;
  [план §14 (Р10)](information-register-development-plan.md) задаёт условие повышения статуса.

## 1. Что и как измерялось

Инструмент — [`scripts/analyze-register-closure.mjs`](../../scripts/analyze-register-closure.mjs),
прогон — [протокол](../../docs/plans/register-closure-20261013.log). Разбор идёт до полноты по
четырём синтаксисам кода (явный и статический `import`, wildcard-импорт с проверкой реально
используемых простых имён, полное имя в теле, простое имя своего пакета) и **отдельно** по
ссылочным средам, которые код не видит: строковые литералы (имена классов для рефлексии) и javadoc.
Комментарии и литералы разбираются врозь осознанно: javadoc-ссылка зависимостью не считается
(правило D1), а имя типа в литерале — считается, и смешение этих двух даёт завышенный замер ровно
так же, как разбор по одним `import` — заниженный.

Владельцы типов размечаются по источнику: `register` (семена), `application` (`org/ip` в дереве),
`platform-in-tree:<пакет>` (платформенный код, ещё лежащий в дереве), модули `platform-*` и соседние
проекты манифеста (`../crudui/*`, `../FilterGrid/*` и другие). Неразрешённые ссылки печатаются
явно: тип без исходников в воркспейсе — это опубликованный артефакт, и выдавать его за «нет
зависимости» нельзя.

Охват: 36 корней исходников, 7258 объявлений типов, семена — 43 файла `org.ipro.register` и 17 файлов
его тестов.

## 2. Результат

| Измерение | Значение |
| --- | --- |
| Однопачечные ссылки из движка (43 файла) | 14 групп пакетов — **все** платформенные: `platform-core` (`org.ipro.crud`, `org.ipro.data`, `org.ipro.metadata`), `platform-persistence` (`org.ipro.crud.BaseEntity`), `platform-contracts` (`org.ipro.events`, `org.ipro.lifecycle`, `org.ipro.metadata.annotation`), `platform-events`, `platform-rls` (+`rls.c5`), `platform-telemetry.api` (audit), `crudui/platform-identity-api`, `FilterGrid/filtergrid-core` |
| Однопачечные ссылки из тестов движка (17 файлов) | 17 групп: те же платформенные плюс `application` (`org.ip.model.*`) и `platform-in-tree-test:org.ipro.registerfixtures` |
| Транзитивное замыкание | 253 файла: `platform-core` 71, `register` 43, `platform-rls` 36, `platform-contracts` 34, `register-test` 17, `platform-numbering` 15, `platform-telemetry` 11, `application` 9, `FilterGrid/filtergrid-core` 8, `platform-events` 2, `platform-metadata` 2, фикстуры регистра 2, `crudui` 2, `platform-persistence` 1 |
| Пересечение в приложение | 9 файлов, и все достигнуты **через тесты**: `RegisterReaderTest` → `org.ip.model.Nomenclature`, `RegisterListFilterPhasesTest` → `org.ip.model.NomenclaturePrice`, далее соседи этих сущностей в самой модели |
| Потребители движка (обратное направление) | 50 файлов: `reportstudio` в дереве 18 main + 6 test, `application` 15 main + 9 test, тест `crud` 1, фикстуры регистра 1 |
| Типы движка, названные вне пакета | 30 (это и есть фактическая контрактная поверхность) |
| Ссылки движка в литералах и javadoc | 0 и 0 — рефлексией и документацией движок на чужие типы не ссылается |
| Файлы регистрации | 1: `src/main/resources/META-INF/spring/...AutoConfiguration.imports` называет `org.ipro.register.config.RegisterAutoConfiguration` |
| Неразрешённые ссылки в пространстве имён проекта | 0 — типов «мимо воркспейса» нет; сторонних (JDK, Spring, Jackson, JPA) 216 |
| Split package в замыкании | 0 — ни один тип замыкания не объявлен дважды |

Повторный прогон 2026-10-13 при проверке раскладки срезов
([протокол](../plans/register-slices-20261013.log)) дал замыкание 254 файла вместо 253,
при неизменном составе срезов и прежних 43 main + 17 test с нулём прикладных ссылок. Причина —
незакоммиченные правки в общем чекауте (в дереве изменены файлы `platform-rls`, удалён
`C5EntityReadPermissionRegistrar`), а не изменение движка; поэтому счётчик транзитивного замыкания
годится как замер состава, но не как метрика для сравнения между днями. Устойчивы однопачечные
ссылки движка и состав срезов.

**Главный вывод.** Движок регистра не зависит от приложения: ни одной прикладной ссылки из 43
файлов. Зависимость есть только в обратную сторону — приложение и отчётный код называют 30 типов
движка, а тесты движка берут прикладные сущности и внутридеревные фикстуры. То есть направление
`application → register` уже единственное, и это то направление, которое для платформенного
артефакта законно.

## 3. Чего замер не видит

- **Контракт через рефлексию.** Аннотация `@RegisterMetadata` читается с прикладных сущностей, поэтому
  «нет ссылок» не значит «нет контракта»: базовые классы, аннотации и `SliceSpec` — контракт для
  прикладного кода даже там, где движок не называет приложение ни разу.
- **Вклад сборки.** `@EntityScan("org.ipro.register.persistence")` и запись в Boot imports — связи,
  которые видны только сборке и рантайму; в замер они попали как файл регистрации и найдены в коде.
- **Честная сборка.** Замер по исходникам её не заменяет: в D3 зависимость `telemetry → settings`
  нашла сборка, а не замер. Поэтому негативная проверка границы сборкой входит в гейты каждого среза.
- **Не измерялось:** порядок инициализации бинов, RLS-конфигурация, тестовое владение по существу
  (только по ссылкам), совместимость опубликованных артефактов.

## 4. Предлагаемый срез

| Срез | Содержимое | Зависимости | Что доказывает |
| --- | --- | --- | --- |
| **S0. Контракты** (без бинов) | `RegisterMetadata`, `RegisterPeriodicity`, `RegisterDescriptor` (+`FieldDescriptor`), `SliceSpec` (+`Condition`/`Mode`/`Operator`), `RecorderType`, `RecorderRef`, базовые классы строк (`PeriodicRegisterEntry`, `PeriodicRegisterEntryBase`, `RecordedRegisterEntry`, `RecordedRegisterEntryBase`, `PeriodicRecordedRegisterEntryBase`), SPI `RegisterPeriodListener` | `platform-contracts`, `platform-persistence`, идентификаторы | Прикладные сущности и отчётный конструктор компилируются против одного контрактного артефакта; вынос аннотаций не тянет за собой сервисную границу |
| **S1. Реестр и чтение** | `RegisterRegistry`, `RegisterMetadataValidator`, `RegisterStartupValidator`, `RegisterReader`, `RegisterSliceReader`, `SliceSpecValidator`, `RegisterListFilterPhases`, своя авто-конфигурация с собственными imports и `@EntityScan` для `register.persistence` | S0, `platform-core` (`EntityDataAccess`, `ManagedEntityCatalog`) | Потеря регистрации падает громко; приложение не приобретает обязательных registrations (паттерн `platform-numbering`/`platform-settings`) |
| **S2. Запись по ключу** | `RegisterKeyChanges`, `RegisterKeyWrites`, `RegisterKeyWritePermissions`, `RegisterKeyChangePermissions`, `RegisterKeyChangeScope`, `RegisterKeyWriteGuardInstaller`, `RegisterRowState`, `RegisterWriteMutex` | S0–S1, `platform-rls` (C5), `platform-telemetry` (durable-аудит), `platform-events` | Смена и запись ключа живут в модуле вместе со своим Hibernate-guard и правами; ADR-0023 не переписывается |
| **S3. Регистратор** | `RecorderSetWriter`, `RecorderSetWriteContext`, `RecorderSetWriteRequest`, `RecorderSetWriteAuthorizer`, `RecorderSetContribution`, `RecorderSetContributionParticipant`, `RecorderSetDeleteParticipant`, `RecorderSetTransactionExecutor`, `RegisterRecords` (прикладные процедуры набора владельца), `RecorderTypeRegistry`, `RecordedRegisterReader`, `RecordedRegisterEntryWriteGuard`, `RlsRecorderSetWriteAuthorizer`, `RecorderOrderClock`, `RecorderOrderClockBootstrap` | S0–S2, `platform-core` (участники агрегата), `platform-rls` | Набор владельца и защита регистратора остаются одним протоколом, а не сервисом приложения |

Порядок — S0 → S1 → S2 → S3, каждый срез по образцу D2/D3: сначала контракты, потом рантайм;
граница доказывается **негативно** (без артефакта приложение не компилируется), тесты переезжают к
владельцу, у модуля свой surface-реестр (черновик ролей умеет печатать тот же анализатор),
обязательных регистраций в приложении не прибавляется.

Авто-конфигурация делится по срезам так же, как у уже вынесенных подсистем: у каждой части свои бины
и свой файл регистрации (S1 — реестр, валидатор, `@EntityScan` для `register.persistence`; S2 — права
команд и Hibernate-guard; S3 — протокол набора владельца), а запись `RegisterAutoConfiguration`
переезжает из app imports в модульные — иначе вынос добавил бы приложению обязательную регистрацию.

Остаётся приложению: предметные сущности (`NomenclaturePrice`, `NomenclatureAccountingObject`,
документы), их lifecycle и сервисы, `RegisterKeyPermissionTitles` (подписи прав — блокер в
прикладном SPI `NamedOperationOwner`), policy чтения для отчётов `RegisterReportReadPolicy`
(остаётся на стороне отчётов, как решено в Р4), прикладные тестовые фикстуры.

**Поправка 2026-10-13 (после проверки по импортам).** Прежняя редакция этого абзаца оставляла
приложению и интерфейс регистра. Это неверно: `RegisterKeyDialog`, `RecorderRefDisplay`,
`RecorderRefPresentation`, `RecorderRefPresentationResolver` не называют ни одного прикладного типа,
а прикладные связи есть только у `RegisterFormCustomization` (четыре локальных сотрудника и прямая
сборка `RegisterSliceReader` с `EntityManagerFactory`). Поэтому интерфейс вынесён в план отдельным
срезом S4 после фабрики читателя — см. [план выноса](information-register-extraction-plan.md) §1 и §4.

## 5. Что закрыть до первого среза (всё измерено)

1. **Тесты движка.** 5 из 17 файлов опираются на внешнее для модуля: 2 импортируют прикладные
   сущности (`RegisterReaderTest`, `RegisterListFilterPhasesTest`), 4 — внутридеревные фикстуры
   `org.ipro.registerfixtures` (`RegisterReaderTest` в обоих множествах). Нужны модульные
   тестовые сущности и фикстуры тем же приёмом, каким это делал `platform-vaadin`.
   *Исправлено 2026-10-13 по импортам: здесь стояло «2 и 2» — верно 2 и 4 (проверено
   `grep` по `^import` в `src/test/java/org/ipro/register`); подробная раскладка — в
   [плане выноса](information-register-extraction-plan.md) §4/S5.*
2. **Потребители в дереве.** 18 файлов `reportstudio` (и 1 тест `crud`) — перевести на координаты
   модуля; это тот же шов, который Р4 уже провёл для policy чтения.
3. **Владение сборкой.** Запись в Boot imports и `@EntityScan("org.ipro.register.persistence")`
   переезжают в модуль вместе с ним.
4. **Роли поверхности.** 30 типов, названных вне пакета, получают роли API/SPI/legacy в реестре
   модуля; часть уже внесена в прикладной реестр (например, команды ключа), остальное — решение ревью,
   а не механическая строка.
5. **`SERIALIZABLE` и прочие СУБД** остаются открытыми из Р11 и не блокируют срез: они не про границу.

## 6. Что замером не решается

- `EntityKind.INFORMATION_REGISTER` и снятие F-ERP-3 — отдельный ADR по второму production-примеру.
  Проверено: кандидат `ИзмененияКомпонентовПоРаспространениям` существует пока только в документах
  (ADR-0013 §2/§6, план §14), в коде его нет — поэтому гейт упирается в отсутствующий предметный
  регистр, а не в формулировку решения.
- Имя, координаты и владелец модуля, версии и совместимость — предмет ADR-0024
  ([плана выноса](information-register-extraction-plan.md) §3) и этапа Р13 «поставка».
- Переезд Java-пакета не предлагается: и D2, и этот замер показывают, что сохранение пакета
  `org.ipro.register` не создаёт split package и не требует правок в потребителях.

## Источники

- [Замер (протокол прогона)](../../docs/plans/register-closure-20261013.log) и
  [инструмент](../../scripts/analyze-register-closure.mjs).
- [ADR-0013: информационный регистр v1](decisions/ADR-0013-information-register-v1.md) — размещение
  Р4 и условие повышения статуса.
- [План развития регистров](information-register-development-plan.md) — Р10 (статус), Р11 (запись по
  ключу), Р13 (поставка).
- [Мост D2 → D3](d3-subsystem-extraction.md) — методика замера и уроки выноса подсистем.
- [Реализация Р11](evidence/register-r11-key-write-implementation-20261013.md).
