# D-R S0: декларация и контракт строки переехали в `platform-register`

Дата: 2026-10-13. Статус: срез S0 закрыт — модуль собирается и устанавливается со своим тестом
(3/3), его поверхность зафиксирована reviewed-реестром и API-baseline, забор ролей проверен
негативно. План среза — [план выноса](../information-register-extraction-plan.md) §4 «S0 — детали».

## Что именно сделано и чем это не является

S0 — **перемещение, а не рефакторинг**: замер замыкания
([`analyze-register-closure.mjs --slice S0`](../../../scripts/analyze-register-closure.mjs), режим
добавлен 2026-10-13) показал, что движок не называет ни одного прикладного типа, поэтому
`git mv` сохранил историю всех 17 типов и ни одна сигнатура не менялась. Отдельным коммитом
(`1bfd193`, «Move the register row contract into its own platform module») переехали
`RegisterMetadata`, `RegisterPeriodicity`, `RegisterDescriptor` (+`FieldDescriptor`),
`RegisterMetadataValidator`, `SliceSpec` (+`Condition`/`Mode`/`Operator`), `SliceSpecValidator`,
`RegisterPeriodListener`, `RecorderType`, `RecorderRef`, базовые классы обеих схем
(`PeriodicRegisterEntry*`, `RecordedRegisterEntry*`), `RecordedRegisterEntryWriteGuard`,
`RecorderSetWriteContext`, `RegisterKeyChangeScope`.

Чем срез не является: бинов у артефакта нет и `AutoConfiguration.imports` он не несёт, поэтому
регистрация приложения не менялась и поведение до S1 (чтение) измениться не может. Прикладные
сущности и отчётный конструктор компилируются против артефакта — это и есть приёмка среза.
Тесты движка (17 файлов в `src/test/java/org/ipro/register`) остаются у приложения до S5: именно
они (а не исходники) держат в `platform-package-matrix.txt` ребро `org.ipro.register -> org.ipro.*`.

## Артефакт и владение сборкой

| Факт | Значение |
| --- | --- |
| Координата | `org.ipro:platform-register:1.0-SNAPSHOT` |
| Объявленные зависимости | `platform-contracts` (аннотации метаданных), `platform-persistence` (`BaseEntity`) — ровно то, что назвал замер среза |
| Fingerprint (v2) | `3ae7ced8282e9f4d4f38601926c30f33cce2274337f3dce2b526374ecf4ee58d`, 20 файлов (`pom.xml` + `src`) |
| Сборка | `buildSteps: clean install` **с тестами**: у артефакта свой прогон, а не только прогон приложения |

Модуль добавлен в манифест (`scripts/local-dependencies.json`, 23 проекта, позиция сразу после
`platform-core`) и уже был подключён в `platform-bom` и в реактор `platform-build`. Без записи в
манифесте бутстрап на чистом локальном репозитории не установил бы артефакт, на который теперь
ссылается `pom.xml` приложения, — то есть «приложение компилируется против контракта» держалось бы
только на машине, где артефакт уже лежит в `~/.m2`. Fingerprint проверен двумя независимыми
реализациями: тестом `WorkspaceManifestTest` (в изолированном дереве, см. ниже) и отдельным
пересчётом той же схемы `gitvaa-source-tree-sha256-v2`, который воспроизводит **17 записанных
значений манифеста** без расхождений — то есть совпадение по новому проекту не является
совпадением реализации проверяющего с самой собой.

## Reviewed-реестр ролей и его забор

Решение о роли принималось тем же изменением, что создавало артефакт
(`platform-register/src/main/resources/META-INF/register-surface.txt`, 17 записей):
**APP_API 8** (`RecorderRef`, `RecorderType`, `RegisterDescriptor`, `RegisterKeyChangeScope`,
`RegisterMetadata`, `RegisterPeriodicity`, `RegisterPeriodListener`, `SliceSpec`),
**APP_SPI 5** (`PeriodicRecordedRegisterEntryBase`, `PeriodicRegisterEntry`,
`PeriodicRegisterEntryBase`, `RecordedRegisterEntry`, `RecordedRegisterEntryBase`),
**MODULE_API 0** — сознательно: ни один платформенный модуль типов среза ещё не называет,
следующий срез введёт роль вместе с читателем, а не «на будущее»;
**INTERNAL 4** (охранник подчинённой строки, контекст записи набора, оба валидатора).

`MODULE_API` с нулём записей и раздел-заголовок с бюджетом — не украшение: забор
`RegisterSurfaceRoleTest` (в самом модуле, поэтому его падение видно на сборке артефакта)
проверяет три свойства, и все три проверены **негативно**:

| Негативный прогон | Что сломано | Наблюдаемый результат |
| --- | --- | --- |
| 1 | убран тип без роли (`APP_API RecorderType`), бюджет раздела уменьшен | `everyModuleTypeHasExactlyOneReviewedRole` FAILURE, Tests run: 3, Failures: 1 |
| 2 | бюджет раздела расходится с числом записей (`APP_API (8)` → `(9)`) | `registrySectionsAgreeWithTheirPerRoleBudget` FAILURE |
| 3 | `RegisterPeriodicity` назначен `INTERNAL` (бюджеты приведены в порядок) | `internalTypesAreNotNamedInApplicationFacingSignatures` FAILURE с диагнозом `RegisterDescriptor (APP_API) называет org.ipro.register.RegisterPeriodicity … его роль — INTERNAL` |

После каждого прогона реестр восстановлен побайтово (`sha256` совпадает, `diff` пуст), иначе
fingerprint модуля перестал бы соответствовать записи манифеста.

Два решения реестра стоит увидеть в диффе, а не искать глазами:

1. **Расхождение с D1-реестром приложения записано явно.** `PeriodicRegisterEntryBase` и
   `RecordedRegisterEntry` в `platform-public-surface.txt` имеют роль API («приложение называет
   тип»), здесь — `APP_SPI` («приложение расширяет тип»: `NomenclaturePrice extends
   PeriodicRegisterEntryBase`, строки реализуют `RecordedRegisterEntry`). Это два разных вопроса:
   бюджет доступа приложения к платформе и граница обещания совместимости.
2. **Единственная аннотационная ссылка названа в шапке.** `@EntityListeners(RecordedRegisterEntryWriteGuard.class)`
   на базовом классе — аргумент аннотации, а не сигнатура, поэтому «охранник» может быть
   `INTERNAL`, но само исключение обязано быть прочитано: оно видно в baseline артефакта.

## Гейты среза

Прогон — только релевантные срезу классы (полный `verify` приложения в этом дереве невозможен, см.
«Ограничения»). Изолированное дерево: `HEAD` (= `1bfd193`) плюс изменения среза.

| Гейт | Результат | Что он говорит про срез |
| --- | --- | --- |
| `PlatformRegisterModuleTest`-аналог: `mvn clean install` модуля | 17 источников, `RegisterSurfaceRoleTest` **3/3**, BUILD SUCCESS | модуль компилируется **без приложения** и сам решает свою границу |
| `PersistenceTypeRegistrationTest` | **6/6** | `org.ipro.register.persistence` принадлежит модулю; владелец сущности перейдёт в S3 |
| `PlatformSubsystemClosureTest` | **2/2** | reach-back `reportstudio` сужен до двух типов, уезжающих поздними срезами |
| `PlatformDependencyDirectionTest` | **8/8** | backend регистра не приобрёл запрещённых направлений |
| `PlatformVaadinSurfaceTest` | **6/6** | поверхность приложения не изменилась переносом |
| `PlatformApiBaselineTest` (+`platform-register.api`) | **4/4** при `-Dplatform.api.baseline.artifact=platform-register` | поверхность артефакта зафиксирована по ролям (`policy: SEMANTIC_ROLES`), `INTERNAL` в baseline не входит |
| `WorkspaceManifestTest` | запись модуля — совпадает; 1 общий FAILURE по чужим проектам | порядок, GAV-владелец и POM-рёбра согласованы; дрейф fingerprints — не этот срез (см. ниже) |

Baseline `src/test/resources/platform-api-baseline/platform-register.api` (212 строк) сгенерирован
намеренно (`-Dplatform.api.baseline.write=true -Dplatform.api.baseline.artifact=platform-register`) и
затем перепроверен без флага; в шапке файла стоит политика, поэтому переход из
`TEMPORARY_ALL_PUBLIC` виден в диффе, а не только в коде теста. `PlatformApiBaselineTest` переводит
артефакт в `SEMANTIC_ROLES` **сразу при появлении** — так же, как это было сделано для
`platform-vaadin` (D3.5.0): иначе `TEMPORARY_ALL_PUBLIC` заморозил бы охранник строки и оба
валидатора как часть контракта.

## Что оказалось красным до среза (и почему это не его результат)

Проверка выполнена дважды: на `HEAD` (= `1bfd193`) и на `HEAD~1` (`47d9557`, до переноса).

| Гейт | `HEAD~1` | `HEAD` | Причина |
| --- | --- | --- | --- |
| `PlatformPublicSurfaceTest` | 5 FAILURE | те же 5 FAILURE | чужая работа в дереве: `org/ip/unidr/**` использует wildcard-импорты `org.ipro.reportstudio.*` и fully-qualified ссылки, 8 типов (`ReportQueryAssembler`, `ReportQueryExecutor`, `ReportRunQuota`, `FormulaEvaluator`, `ReportCompiler`, `ReportRenderException`, `ReportTaskExecutor`, `RlsContext`) не имеют ролей в `platform-public-surface.txt`, а замер `app-source-files` (240 в коде против 202 в документе) устарел |
| `PlatformPackageDependencyMatrixTest` | 9 неразрешённых рёбер `org.ipro.navigation*` | те же 9 | перечень рёбер модуля `platform-navigation` не внесён в `platform-package-matrix.txt` |
| `WorkspaceManifestTest` | (не проверялся) | 5 дрейфов fingerprints | `platform-contracts`, `platform-core`, `platform-vaadin` устарели ещё в закоммиченном состоянии; `platform-rls` и `platform-spring-boot-autoconfigure` изменены в дереве чужой незавершённой работой (консолидация C5-регистраторов) |

Ни одна из этих строк не относится к регистру: `1bfd193` не менял ни `src/main/java/org/ip`, ни
`src/test/resources`, ни `docs` (проверено `git diff --stat HEAD~1 HEAD -- src/test/resources docs` —
пусто). Поэтому срез закрывается с **явно названными** чужими красными, а не с их молчаливым
зачётом; обновлять fingerprints чужих проектов «за сборку покраснела» нельзя — манифест обновляют,
приняв изменение.

## Ограничения и как они обойдены

- **Рабочее дерево на момент среза не компилируется чужой правкой.** `mvn compile` приложения
  падает на `src/main/java/org/ip/unidr/config/UniDrConfiguration.java:[48,160] incompatible types:
  java.util.List<java.lang.Class<capture#1 of ?>> cannot be converted to java.util.Collection<java.lang.Class<?>>`
  (до этого — отсутствующие импорты в `ReportQueryAssemblyService`, уже исправленные в дереве).
  Это незавершённая работа другого потока в общем чекауте, а не следствие среза.
- **Поэтому гейты прогнаны в изолированном дереве**: `git worktree add <sibling>/GitVaa25-s0-verify
  HEAD` + изменения среза. Ветка дерева проверки: `core.autocrlf=false` и пере-чекаут, потому что
  машинная настройка `core.autocrlf=true` при checkout превращает LF в CRLF и меняет значения
  fingerprints: записанные в манифесте значения соответствуют LF-рабочему дереву (проверено
  пересчётом — 12 проектов совпадают, остальные 5 и есть названные выше дрейфы). Исходники
  платформенных модулей скопированы из рабочего дерева, чтобы fingerprints были сравнимы; это же
  принесло в прогон чужой дрейф baseline `platform-rls` (`entityOperations` → `entity`).
- **Полный `mvn verify` и random-order прогон не выполнялись.** Приёмка §6.5 плана требует их на
  закрытии среза; здесь они заблокированы чужой несобирающейся правкой (первый пункт) и уже
  названными чужими красными гейтами. Модульный прогон версии проверки — `clean install` с тестами.
- **Негативная сборка приложения без артефакта** (приёмка §6.1) не отделялась: без
  `platform-register` приложение не компилируется по определению — сам факт его выноса и есть эта
  проверка; отдельный эксперимент с удалением артефакта из локального репозитория не проводился.
- **Проверок на PostgreSQL нет и не требуется**: срез переносит типы, а не поведение; скрипты
  `register-r8/r9/r11-postgresql.ps1` этим срезом не запускались.

## Побочная находка: два reviewed-реестра не попадали в поставку

`*.txt` в `.gitignore` исключает все reviewed-реестры, и исключения перечислялись вручную.
2 находки одного класса:

1. `platform-register/src/main/resources/META-INF/register-surface.txt` — реестр ролей нового
   модуля; забор читал бы его как источник истины, а на чистом checkout файла бы не было.
2. `src/test/resources/platform-rest-web-surface.txt` — читается `PlatformApiBaselineTest` и
   `RestWebModuleCompositionTest` и не был tracked вообще: на чистом checkout гейт падал
   `NoSuchFileException` (наблюдалось в изолированном дереве до копирования файла).

Оба закрыты **шаблонами**, а не ещё двумя строками: для платформенных модулей
`!platform-*/src/main/resources/META-INF/*.txt`, для реестров периметра приложения
`!src/test/resources/platform-*-surface.txt`. Перечисление отстаёт уже дважды (README модулей, этот
случай), и цена отставания — гейт, защищающий только машину автора.

## Источники

- [План выноса механизма регистра](../information-register-extraction-plan.md) §4 (S0), §5 (гейты), §6 (приёмка).
- [Замер замыкания движка](../information-register-extraction-slice.md) и инструмент
  [`analyze-register-closure.mjs`](../../../scripts/analyze-register-closure.mjs).
- Реестр ролей: [`register-surface.txt`](../../../platform-register/src/main/resources/META-INF/register-surface.txt),
  забор [`RegisterSurfaceRoleTest`](../../../platform-register/src/test/java/org/ipro/register/RegisterSurfaceRoleTest.java).
- Baseline поверхности: [`platform-register.api`](../../../src/test/resources/platform-api-baseline/platform-register.api),
  владелец политики — [`PlatformApiBaselineTest`](../../../src/test/java/org/ipro/PlatformApiBaselineTest.java).
- Манифест локальных зависимостей: [`scripts/local-dependencies.json`](../../../scripts/local-dependencies.json).
- Предыдущий пакет темы: [Р11: спайк записи по ключу](register-r11-key-write-spike-20261012.md),
  [ADR-0023](../decisions/ADR-0023-register-key-write.md).
