# Evidence: гейт перечня путей записи сущностей платформы

Дата: 2026-10-15. Реализация: `src/test/java/org/ipro/PlatformWritePathLifecycleTest.java`,
реестр — `src/test/resources/platform-write-paths.txt`.

## 1. Зачем гейт и какую дыру он закрывает

ADR-0005 обещает, что `EntityLifecycle`-хуки вызываются на **всех** стандартных путях сохранения.
Обещание один раз разошлось с машиной: путь набора владельца писал строки напрямую, и правила
строки молча не исполнялись — нашлось это замером при принятии ADR-0025, то есть **после** того,
как путь был написан и прожил несколько срезов выноса. Общий признак дефекта: полноту «кто вообще
пишет» не держал ни один гейт, поэтому каждый новый путь был обязан вспомнить про lifecycle сам.

Гейт проверяет обратное направление: **путь записи не может появиться, не объявив**, вызывает ли
он хуки или почему нет. Это не замена приёмке семантики (ровно один раз на строку, порядок
относительно валидации) — это её предпосылка: приёмка конкретного пути бессмысленна, если
существование пути никем не замечено.

## 2. Что считается предметом перечня

Предмет — два пересекающихся множества классов production-кода модулей `platform-*`:

1. **Пути записи** — из кода класса идёт вызов мутации состояния:

| Контур | Что ловится | Почему в перечне |
| --- | --- | --- |
| `JPA` | `EntityManager.persist/merge/remove` (и то же у `Session`) | прямая запись сущности |
| `BULK` | `executeUpdate` JPQL/native | обходит и хуки, и валидацию |
| `SPRING_DATA` | `save/saveAll/saveAndFlush/delete…` на репозитории | запись мимо `EntityManager` в коде |
| `JDBC` | `update/batchUpdate/execute` на `JdbcTemplate`/`JdbcClient` | журнальные таблицы: тот же вопрос «кто пишет и почему без правил» |
| `FLUSH` | `EntityManager.flush()`/`Session.flush()` | `RegisterKeyChanges` меняет строку dirty checking'ом: `merge` в его байткоде нет, а путь записи есть |

2. **Исполнители правил** — классы, вызывающие хуки `EntityLifecycleRegistry`. Это множество нужно
именно потому, что первое неполно по построению (см. `FLUSH`), и потому, что обещание ADR-0005
дано про исполнение правил, а не про вызов `persist`.

Определение — по байткоду (ArchUnit `getMethodCallsFromSelf()`), а не по тексту: комментарий или
javadoc «путь записи» путём записи не становится, fully-qualified вызов и лямбда ловятся, а
`Map.remove` на чужом контуре — нет (проверено классификатором, §3). Вложенные классы относятся к
классу-владельцу исходника: путь не дробится по тому, как он разложен по классам, и внутренний
класс не может спрятать запись внутри объявленного исключения.

## 3. Вердикты и что каждый подтверждается

| Вердикт | Смысл | Чем подтверждается в байткоде |
| --- | --- | --- |
| `HOOKS` | путь исполняет правила | вызывает `EntityLifecycleRegistry.before*/onSave/beforeAggregateSave`; множество вердиктов `HOOKS` совпадает с множеством исполнителей правил **в обе стороны** |
| `DELEGATES` | сам не пишет, передаёт объявленному пути | зависит от названной цели, цель объявлена |
| `HOSTED` | пишет как механизм, обязательство несёт вызывающий путь | цель объявлена как `HOOKS` и зависит от механизма (обратная зависимость) |
| `EXCEPTION` | контракт lifecycle не относится | причина **и** решение обязательны: строка без `; решение: …` не разбирается и роняет гейт |

## 4. Перечень на 2026-10-15 (19 субъектов)

| Модуль | Класс | Вердикт |
| --- | --- | --- |
| platform-core | `org.ipro.data.CanonicalWriteExecutor` | HOOKS |
| platform-core | `org.ipro.crud.jpa.ValidatedJpaCrudService` | HOOKS |
| platform-core | `org.ipro.crud.MetadataDrivenAggregateSaveService` | HOOKS |
| platform-core | `org.ipro.crud.GenericOwnedSectionService` | HOSTED → `MetadataDrivenAggregateSaveService` |
| platform-register | `org.ipro.register.RecorderSetWriter` | HOOKS (ADR-0025) |
| platform-register | `org.ipro.register.RegisterKeyWrites` | HOOKS |
| platform-register | `org.ipro.register.RegisterKeyChanges` | HOOKS |
| platform-register | `org.ipro.register.RecorderSetContributionParticipant` | DELEGATES → `RecorderSetWriter` |
| platform-register | `org.ipro.register.RegisterRecords` | DELEGATES → `RecorderSetWriter` |
| platform-register | `org.ipro.register.RegisterWriteMutex` | EXCEPTION (замок, не запись состояния) |
| platform-register | `org.ipro.register.RecorderOrderClockBootstrap` | EXCEPTION (техническая строка часов) |
| platform-numbering | `org.ipro.numbering.NumberingCounterService` | EXCEPTION |
| platform-numbering | `org.ipro.numbering.NumberingRuleService` | EXCEPTION |
| platform-settings | `org.ipro.settings.SettingsService` | EXCEPTION |
| platform-navigation | `org.ipro.navigation.internal.DefaultNavigationAdministrationService` | EXCEPTION |
| platform-telemetry | `org.ipro.telemetry.core.AsyncEventSink` | EXCEPTION |
| platform-telemetry | `org.ipro.telemetry.core.RetentionPurgeJob` | EXCEPTION |
| platform-telemetry | `org.ipro.telemetry.core.TraceServiceImpl` | EXCEPTION |
| platform-telemetry | `org.ipro.telemetry.config.FieldAuditSelfTest` | EXCEPTION |

Контуры с предметом сегодня: `JPA`, `SPRING_DATA`, `JDBC`, `FLUSH`. `BULK` предмета не имеет —
bulk-записи в платформе нет; правило классификатора для него всё равно проверяется тестом
(«непроверенное правило и есть будущая дыра»), а требовать наличия предмета значило бы держать
гейт красным из-за отсутствующего кода.

## 5. Прогоны

| Прогон | Команда | Результат |
| --- | --- | --- |
| Первый прогон гейта (в реестре была одна строка — `CanonicalWriteExecutor`) | `mvn -o test -Dtest=PlatformWritePathLifecycleTest` | **exit 1**: найдены и названы 13 путей записи с сигнатурами и строками для реестра. Детектор в тот момент был без контура `FLUSH` — после его добавления (см. §2) перечень вырос до 19 субъектов |
| Зелёный прогон после наполнения реестра | та же команда | **exit 0**, `Tests run: 4, Failures: 0, Errors: 0` (~9–12 s) |
| Негативный прогон №1: из реестра убрана строка `RegisterKeyChanges` | та же команда | **exit 1**, 2 падения: `everyPlatformEntityWritePathAndRuleExecutorIsDeclared` называет класс, его сигнал (`EntityManager.flush`) и строку для реестра; `hookCallersAndHooksDeclarationsAgree` показывает расхождение «путь вызывает хуки, а объявлен не как HOOKS». Реестр восстановлен побайтно (`diff` — пусто), повторный прогон — **exit 0** |
| Негативный прогон №2 (после правки подсказки): убрана строка `RegisterRecords` | та же команда | **exit 1**, подсказка приведена к форме, которую можно вставить в реестр целиком: `<HOOKS\|DELEGATES\|HOSTED\|EXCEPTION> platform-register org.ipro.register.RegisterRecords — <почему это не расхождение с ADR-0005>; решение: <ссылка>`. Реестр восстановлен побайтно, повторный прогон — **exit 0** |
| Гейты, которые могли задеть новые файлы, после добавления гейта | `mvn -o test -Dtest='PlatformWritePathLifecycleTest,PlatformCoreBoundaryTest,PlatformDependencyDirectionTest,PlatformSubsystemClosureTest,PlatformStringDependencyTest,CompatibilityMigrationArchitectureTest,PlatformPackageDependencyMatrixTest'` | **exit 0**, `Tests run: 36, Failures: 0, Errors: 0`, `BUILD SUCCESS` |

## 6. Поставка реестра

Широкое правило `*.txt` в `.gitignore` исключило бы реестр из репозитория: на чистом checkout
файла бы не было, `PlatformWritePathLifecycleTest` падал бы на пустом реестре, и гейт защищал бы
только машину автора — тот же класс дефекта, который в этом репозитории уже описан у
surface-реестров и README модулей. Поэтому рядом с ними добавлено явное исключение
`!src/test/resources/platform-write-paths.txt` (`.gitignore`), проверено `git check-ignore`:
файл больше не игнорируется и виден `git status` как новый.

## 7. Границы: что гейт не доказывает

- **Семантику пути.** Гейт держит «путь объявлен и вердикт подтверждён зависимостью/вызовом», а не
  «хук вызван ровно один раз на строку в правильном порядке» — это приёмка самого пути (ADR-0025:
  H2 28/28 в классе, PostgreSQL 48/48 через `scripts/register-adr0025-postgresql.ps1`).
- **Полноту по вызовам внутри класса.** Объявляется класс, а не каждый его вызов: если в уже
  объявленном `HOOKS`-классе появится новая запись мимо хуков, гейт этого не заметит.
- **Правку загруженной сущности без `flush` в собственном коде.** Если класс только меняет
  состояние managed-сущности, а синхронизацию делает вызывающий, ни один контур не сработает.
  Такой путь виден только если он вызывает хуки (тогда он попадёт в перечень как исполнитель
  правил); если не делает ни того, ни другого — он невидим, и это остаточный риск, названный явно.
- **Приложение.** `org.ip.*` в перечень не входит: приложение пишет через платформенные пути.
- **Строковые и конфигурационные обвязки.** Гейт смотрит байткод: путь, «включаемый» строкой
  конфигурации (как `rls.write-boundary.enforced-types`), он не увидит.

## 8. Что нашлось при наполнении перечня

1. **Обязательство секций лежит на вызывающем, а не на механизме.** `GenericOwnedSectionService`
   пишет секции (`merge`/`persist`/`remove` + flush), но хуки исполняет `MetadataDrivenAggregateSaveService`.
   Это не дефект, а названный шов — вердикт `HOSTED` фиксирует его и проверяет, что вызывающий
   действительно зависит от механизма.
2. **`ValidatedJpaCrudService` зовёт хуки через `Optional<EntityLifecycleRegistry>`.** В сборке без
   `platform-events` правила молча не исполняются. Гейт делает это видимым в реестре; решение
   «оставлять ли Optional» — отдельный вопрос, и он теперь назван, а не спрятан.
3. **Путь записи можно написать, не вызвав ни `merge`, ни репозиторий.** `RegisterKeyChanges`
   меняет строку dirty checking'ом, поэтому его находит только контур `FLUSH`. Без этого контура
   гейт объявил бы «все пути записи перечислены», не увидев один из четырёх путей регистра.
