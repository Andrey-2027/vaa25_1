# F-REST-READ-3: evidence завершения

- Дата: 2026-10-03, Europe/Moscow.
- Статус: З0–З2 выполнены; З3–З5 и полная приёмка этапа остаются открытыми.
- План: [`rest-read-api-stage3-completion-plan.md`](rest-read-api-stage3-completion-plan.md).
- Контракт: [`rest-read-api-stage3-security-read-plan.md`](rest-read-api-stage3-security-read-plan.md).
- SQL trace: [`evidence/rest-read-stage3-z0-sql-20261003.txt`](evidence/rest-read-stage3-z0-sql-20261003.txt).
- SQL trace З1: [`evidence/rest-read-stage3-z1-sql-20261003.txt`](evidence/rest-read-stage3-z1-sql-20261003.txt).

## 1. Что выполнено в З0

Зафиксированы inputs; пересобраны и установлены текущие C5/core/autoconfigure/REST
артефакты; выполнен app pilot с классификацией SQL; сопоставлены UI LIST и
независимые REST profiles двух ресурсов; выполнена strict bootstrap validation
и диагностика её отказов. Производственная логика чтения и выдача grants в З0
не менялись. Изменён только app pilot и документация.

Исторические 20/16 statements получили проверяемое объяснение: отличаются
RLS-readable-ids cache misses, а не C5 decisions. Остаток build gates перенесён
в З5 с конкретными наблюдениями; их диагностика не считается успешным bootstrap.

## 2. Inputs и артефакты

Git HEAD: `a48ac3ad42431ebbc7caa56181b6e4f3c4e9c54c`.
Checkout содержит незакоммиченные изменения: C5/canonical read/REST corrective
pass, autoconfigure registrars, их tests/baselines и build manifest. Есть также
отдельные изменения Explorer и информационного регистра; они не включались в
работы З0 и не откатывались. Один HEAD не описывает проверенный source snapshot.

Дополнительный application source fingerprint после прогона: 742 файла
(`pom.xml` и все файлы `src`), SHA-256
`0268b896b7ed7671d0e8eae8498fd5b5a9f75f6db79643f31273d6971a6b92fc`.
Алгоритм: относительные пути с `/`, ordinal sort, конкатенация
`path + NUL + lowercase file SHA-256 + LF`, затем SHA-256 UTF-8 bytes.
Это идентификатор текущего dirty snapshot, не замена сохранённой истории Git.

| Инструмент | Фактически использован |
| --- | --- |
| JDK | Axiom JDK 21.0.11 |
| Maven | 3.9.9, bundled IntelliJ IDEA Community Edition 2025.2.3 |
| PowerShell host | 7.6.5 |
| Hibernate ORM | 7.2.12.Final |
| H2 | 2.4.240 |
| Maven local repository | `.local-maven-repository` в checkout |
| REST pilot | OSIV off, Hibernate L2 off, production C5 provider/storage/role resolver |

Версии Hibernate/H2 и пути четырёх owner JAR подтверждены фактическим
`java.class.path` в Surefire XML. Hibernate статистика включена. Тестовая
подмена — только `DataInitializer`; user/role resolver не подменён, но baseline
использует прямые grants и не создаёт назначенные application роли.

Source fingerprints сверены strict validation с manifest до первого sibling
отказа; все четыре строки ниже были `OK fingerprint`. Затем модули собраны
последовательно `install -DskipTests`. Owner tests в этом прогоне не запускались;
результаты прежних 257 tests не приписываются новому прогону.

| Модуль | Source fingerprint SHA-256 | Input files |
| --- | --- | --- |
| `platform-rls` | `797d5b34f6a97f2c7368f8faab624c8c218ef546b96678c08bec33a57b17e116` | 45 |
| `platform-core` | `aeada1cfdb85b35490fde4a84bb43e32a13ddb874d5d0257be3e83851a24e8db` | 131 |
| `platform-spring-boot-autoconfigure` | `ea1b5dbf98164fb19b97a7d5b2574d06ab1b7d89ad474246a7cb5ed9aad95d49` | 17 |
| `platform-rest` | `3efcf891f4ef07bed1bb140cef1ddb2a9c43b9fae6351509a41a4e618e492e18` | 50 |

Для каждого модуля SHA-256 `target/<artifact>-1.0-SNAPSHOT.jar` совпал с JAR,
установленным в выбранный repository:

| Модуль | JAR SHA-256 |
| --- | --- |
| `platform-rls` | `466758bd9d5a86bf2bcc88ac21d92d655125d246f52195acf7e5f94dfc8b43b4` |
| `platform-core` | `86841ab53058c26b8857a95ea99efe0d20ce48b8cb0ac0f29905811f7eaa4173` |
| `platform-spring-boot-autoconfigure` | `003d658f033ad8c3039a1c48f7a69ffdc8cc62648fb7a83f5e1d46d680009c15` |
| `platform-rest` | `9531af19e5bd6efbe7795c7e7e59ac89bced72cac6b64e2b2745c8c3fa861605` |

SHA-256 проверенного `RestReadPipelinePilotIT.java`:
`fb10c7e156082d4d194e66536c8710431aa0c3324dd8048c9c4396c9cc9da9f7`.
JAR hashes относятся к этому запуску: повторная упаковка может изменить timestamp
в архиве. Идентичность исходников задают source fingerprints, не только JAR hash.

## 3. Прогон и состав SQL

`RestReadPipelinePilotIT` — **7 tests, 0 failures, 0 errors, 0 skipped**.
Maven завершился 2026-10-03 в 19:32:20 по Москве. Время Maven 1:42;
время suite 40.583 s включает старт Spring context и не является latency REST.

SQL захватывается через `SqlStatementAuditBridge`: наблюдатель добавляется к
существующему inspector и снимает только себя в `finally`. RLS-canary сохраняется.
Сбор ограничен потоком измеряемого вызова; SQL подготовки fixture исключён.
Категории классификатора привязаны к конкретным H2 statements этого pilot.
Unknown SQL остаётся в `OTHER`, не скрывается в grants. Для 1/50 budget trace
count совпал с `Statistics.getPrepareStatementCount()`, `OTHER == 0`.

### 3.1. `PrdSpec` LIST: объяснение 20/16

Fixture: 50 видимых спецификаций одного JOURNAL; прямые USER grants;
поля `id/codeSpec/nomenclatureCode`, фильтр `journalId`, sort `id`, page 0.
Каждый вызов проходит отдельную persistence transaction без OSIV/L2.

| Вызов | Exact C5 grants | RLS grants | User/role resolution | Content | Count | Всего |
| --- | --- | --- | --- | --- | --- | --- |
| Первый, size 1 | 14 | 2 | 2 | 1 | 1 | 20 |
| Повторный, size 1 | 14 | 0 | 0 | 1 | 1 | 16 |
| Повторный, size 50 | 14 | 0 | 0 | 1 | 1 | 16 |

Первый вызов рассчитывает readable ids для FILTERABLE RLS dimensions через
`RlsFilterActivator` → session-scoped `RlsReadableIdsCache` → `AccessService`.
Два расчёта дают по одному dimension/wildcard grant query и одному
`UserRepository.findByUsername` query. Следующие вызовы переиспользуют readable ids
того же субъекта/версии grants; новый persistence context этот отдельный кеш не сбрасывает.

`DefaultC5PermissionEvaluator` каждый раз выполняет те же 14 exact/global grant
queries. Исчезновение четырёх statements не доказывает и не предполагает кеш C5.
В этих fixtures нет application User с назначенными ролями: две user queries
возвращают отсутствие пользователя. Стоимость настоящих role grants ещё измеряется в З3/З4.

Count читает только `prd_spec` с RLS/filter predicates; content дополнительно
соединяет `nomenclature` по fixed LIST profile. Количество запросов одинаково
для 1 и 50 строк. После чтения scalar результата дополнительных statements нет.

### 3.2. Сопоставимый UI LIST / REST LIST

Одна fixture, одинаковый root/filter/page/size/sort, один субъект и уже рассчитанные
session RLS ids. Это изолирует разницу read graphs и дополнительных C5 требований.

| Путь | C5 | RLS/roles | Content | Count | Всего |
| --- | --- | --- | --- | --- | --- |
| `PrdSpec` UI LIST | 1 | 0 | 1 | 1 | 3 |
| `PrdSpec` REST LIST | 14 | 0 | 1 | 1 | 16 |
| `Nomenclature` UI LIST | 1 | 0 | 1 | 1 | 3 |
| `Nomenclature` REST LIST | 10 | 0 | 1 | 1 | 12 |

У `PrdSpec` фильтр — тот же JOURNAL, sort `id`; у `Nomenclature` — тот же
`typeNom`, sort `code/id`. Размер страницы 1, total обоих путей совпадает,
выбранные id совпадают с fixture. Все четыре trace имеют `OTHER == 0`.
UI read требует только root entity C5; REST дополнительно проверяет API,
output/filter/sort/id и reference requirements. Значения 3/16 и 3/12 поэтому
не описывают одинаковый объём авторизации и не являются wall-time сравнением.

## 4. Графы и фактическая загрузка

| Root | UI LIST association paths | REST maximum LIST | REST maximum DETAIL |
| --- | --- | --- | --- |
| `PrdSpec` | `journal`, `nomenclature` | `id`, `codeSpec`, `draft`, `nomenclature.code` | `id`, `codeSpec`, `draft`, `comment`, `journal.id`, `nomenclature.code` |
| `Nomenclature` | `groupNom`, `unitOfMeasurement` | `id`, `code`, `name`, `typeNom` | `id`, `code`, `name`, `typeNom` |

UI paths получены настоящим `ScenarioFetchGraphResolver.resolvePaths(LIST)`;
REST paths — из `ResolvedRestResource.fixedFetchPaths`. В SQL:

- UI `PrdSpec` content содержит JOIN `journal` и `nomenclature`; REST LIST
  содержит JOIN `nomenclature` и не содержит JOIN `journal`.
- UI `Nomenclature` content содержит JOIN `unit_of_measurement` и LEFT JOIN
  `group_nom`; REST content не соединяет эти таблицы.
- Count не использует эти fetch joins.
- UI loaded-state: `PrdSpec.journal/nomenclature == loaded`,
  `materials/operations == not loaded`; `Nomenclature.unitOfMeasurement == loaded`.
  `groupNom` в этой fixture null: `isLoaded == true` не доказывает загрузку
  непустой группы. Такой fixture нужен для полной приёмки З4.
- Существующий `prdSpecListAndDetailRespectJournalGrantsForEachSubject` проходит
  проверку REST loaded-state в транзакции: LIST nomenclature/code загружены,
  journal не загружен; DETAIL допускает journal/id. Два субъекта, RLS count,
  FK-фильтр и скрытый/отсутствующий detail проходят.

Независимый fetchgraph не является SQL column projection: наблюдаемый Hibernate
content SQL выбирает и другие BASIC/audit columns корня и загруженной цели.
Контракт этого среза ограничивает graph associations и наружную scalar projection;
он не обещает выбор только перечисленных SQL columns. Наличие basic columns в
серверном чтении не разрешает выдавать их без выбранного alias и его permissions.

Это baseline для З4. Maximum/default DTO fields, DETAIL budgets, size 200,
глубокое окно и непустые незаявленные references ещё не приняты.

## 5. Bootstrap: результаты и открытые причины

Strict `-ValidateOnly` завершился exit 1 на `dynamicreports` после проверки
33 published coordinates, 44 dependency edges и fingerprints платформенных модулей.
Diagnostic `-ValidateOnly -AllowSourceDrift` завершился exit 0 и перечислил два отказа:

| Sibling | Expected SHA-256 | Actual SHA-256 / files |
| --- | --- | --- |
| `dynamicreports` | `0362d8b3eb2a877fd361589f68aefd81074d936e707305dc7fe98c509e6efe41` | `1b1932764f7018d3e5d2b718a2a4ac790e5c872e03043276022219df9890a27b` / 1321 |
| `ureport3` | `8edfe8eddc98a6f6d1b4c1cbc48bd1df1072a4e4dfd20cecb931acdb874204ed` | `7fe1ada3089bc6c1e7486f44b33612881af70f5a08056bf86dcb8898ed9ba23f` / 544 |

Оба sibling fingerprint используют legacy v1, зависящий от сортировки host/culture.
`diagnose-source-fingerprint.ps1` в текущем PowerShell не воспроизвёл expected
hash в проверенных cultures. Ordinal v2 hashes:
`dynamicreports` — `d869ece49c684ae9db3a7619d381821b80dbebc411443d02f06fd46c66534288`;
`ureport3` — `7eee06ead8b6985348dd5fe5cc395d96b4eb7c4a6aaba3f449bf039eb5d04eca`.
Это не достаточное основание автоматически заменить expected: требуется
сопоставление исходного snapshot и исключение различий legacy host.

`dynamicreports` не имеет локального `.git`; у `ureport3` есть tracked/untracked
изменения JPQL/report UI/POM. Git status сам по себе не показывает, какие из них
были уже включены в прежний manifest. Проверка diagnostic script через Windows
PowerShell 5.1 остановилась на ExecutionPolicy до расчёта; настройки не изменялись.
Strict validation не проверяет toolchain: JDK/Maven в З0 проверены отдельным вызовом.

Для прежнего отказа `clean` исследован конкретный файл
`../crudui/platform-identity-api/target/platform-identity-api-1.0-SNAPSHOT.jar`:
он существует, 2831 bytes, attribute Archive, не ReadOnly; read-only probe с
`FileShare.None` прошёл; ACL содержит Modify для Authenticated Users.
Это исключает подтверждённую текущую блокировку эксклюзивного чтения, но не
доказывает возможность удаления из build process. Sibling находится вне writable
workspace текущего исполнения. Удаление или повторный sibling clean в З0 не выполнялись.

**Открыто в З5:** review исходных sibling snapshots/legacy v1, обоснованная фиксация
manifest, установление причины delete failure в build environment и стандартный
bootstrap без diagnostic drift flags. PostgreSQL к этим отказам отношения не имеет.

## 6. Контрольные решения для следующих пакетов

1. **З1:** один платформенный default W=10 000; `page * size + size <= W` в long;
   size 0 выбирает ресурсный default. Общая server configuration подключается
   auto-configuration; новые builder параметры для текущих пилотов не требуются.
   W окончательно принимается после deep-page измерения З4.
2. **З2:** определения C5 read keys и exact/global evaluator принадлежат
   `platform-rls`; existing admin получает решение USER/ROLE через общую C5
   read-границу. Ключи распознаются по явной регистрации, не по RLS wildcard или
   `ENTITY:` prefix. Read decision отражает allow и источник подходящих grants;
   write/delete/value grants не преобразуются в read. Конкретные Java signatures
   и public roles проверяются перед изменением кода З2.
3. **З4:** baseline business budget — 1 content + 1 count; дополнительные
   business/reference queries отсутствовали. Текущая direct-grant стоимость
   C5 — 14 (`PrdSpec` выбранный запрос) и 10 (`Nomenclature` выбранный запрос).
   Это стартовые измерения, не универсальные пределы для maximum fields/role grants.
   Число C5 queries — отдельный кандидат на оптимизацию после проверки общей
   read semantics; межзапросный кеш решений не вводится по этим результатам.

## 7. Команды и возможность повторения

```powershell
$env:JAVA_HOME = 'C:\Program Files\Axiom\AxiomJDK-21'
$restMaven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd'
$restRepo = [IO.Path]::GetFullPath('.local-maven-repository')
& $restMaven --version

foreach ($restModule in @('platform-rls','platform-core','platform-spring-boot-autoconfigure','platform-rest')) {
    & $restMaven --batch-mode --no-transfer-progress --offline "-Dmaven.repo.local=$restRepo" -DskipTests -f "$restModule/pom.xml" install
    if ($LASTEXITCODE -ne 0) { throw "Installation failed: $restModule" }
}

& $restMaven --batch-mode --no-transfer-progress --offline "-Dmaven.repo.local=$restRepo" '-Dtest=RestReadPipelinePilotIT' test

.\scripts\bootstrap-local-dependencies.ps1 -ValidateOnly -JavaHome $env:JAVA_HOME -MavenCommand $restMaven -LocalRepository $restRepo
# Strict отказ выше ожидается на записанном snapshot; diagnostic запуск отдельный.
.\scripts\bootstrap-local-dependencies.ps1 -ValidateOnly -AllowSourceDrift -LocalRepository $restRepo
.\scripts\diagnose-source-fingerprint.ps1 -ProjectId dynamicreports
.\scripts\diagnose-source-fingerprint.ps1 -ProjectId ureport3
```

Локальные полные logs и Surefire XML находятся в `target/rest-stage3-z0` и
`target/surefire-reports`; они временные и будут удалены при root clean.
Versioned SQL artifact выше сохраняет фактический trace, группируя одинаковые
шаблоны внутри одного вызова. Bound parameters и SQL setup в него не включены.
SHA-256 versioned SQL artifact:
`e19e5e318df7cf4f063a4097c9eb750c7bc0bf96193d5db156b317431a407e88`.

Новый прогон З0 не заменяет owner verify, реальные User/Role/grant-admin acceptance,
финальные budgets, starter checks и strict bootstrap З5. Следовавший пакет З1
проверен отдельно ниже; stage 3 и Gate 3B пока не закрываются.

## 8. З1 — конечное окно страниц, 2026-10-03

### 8.1. Реализованный контракт

Server property — `platform.rest.read.max-window`, default **10 000**.
Auto-configuration передаёт значение в `RestReadService`; общий default задан
одной константой и используется также прежним пятиаргументным конструктором.
Настройка ресурса через builder для этого окна не требуется.

- Допустимый конфигурационный тип — positive int. Значения 0, -1, пустая строка,
  nonnumeric и 2 147 483 648 останавливают enabled startup.
- Окно меньше default page size любого опубликованного ресурса также запрещено
  на старте: обычный default-запрос должен помещаться в окно.
- Disabled read не связывает property и не создаёт read service.
- `page >= 0`; отрицательный size запрещён, size 0 выбирает resource default.
  Сохраняется меньший resource max и платформенный max size 200.
- `offset = (long) page * size`, `windowEnd = offset + size`;
  требуется `windowEnd <= maxWindow`. Сохранена проверка безопасного int offset.
- API и root entity authorization предшествуют paging validation;
  attribute/reference authorization и canonical business read следуют за ней.
  Отказ за окном — существующий внутренний `INVALID_REQUEST`.
- Total и totalPages описывают все видимые строки, не обрезаются по W. Запрос
  страницы с `windowEnd > W` запрещён даже если за окном осталась одна строка.
  HTTP mapping и описание ограничения клиенту относятся к этапу 4.

Java изменения затронули INTERNAL `RestReadService` и INTERNAL auto-configuration:
добавлен конструктор с int limit, прежний сохранён. APP_API builder, core MODULE_API
и типы результата не менялись. API/surface проверки прошли без обновления baseline.

### 8.2. Owner проверки и приложение

| Набор | Результат |
| --- | --- |
| `platform-rest` install, включая verify/tests | 91/91, 0 failures/errors/skipped |
| В том числе `RestReadServiceTest` | 26/26 |
| В том числе `RestReadAutoConfigurationTest` | 10/10 |
| `RestReadPipelinePilotIT` | 8/8 |
| `PlatformApiBaselineTest` | 3/3 |
| `PlatformPublicSurfaceTest` | 10/10 |
| Root command total | 21/21 |

Owner прогон завершён в 20:12:31, root — в 20:16:29 по Москве.
JDK/Maven/Hibernate/H2 и repository те же, что в З0.
Рабочий checkout параллельно изменяется в части информационного регистра;
эти результаты относятся к указанным REST inputs и выполненному набору tests,
а не к последующим изменениям всего checkout.

Unit/context tests проверили точную границу, следующую страницу, default size,
отрицательный page/size, size выше resource/platform max, очень большой page,
настройку W=150, id tie-breaker, невалидную конфигурацию и отсутствие business read
при invalid paging. Mock-контур здесь доказывает контракт и wiring; SQL-проверка
в следующем подразделе использует настоящий provider/storage и Hibernate/H2.

### 8.3. Реальная граница на 10 001 видимой строке каждого ресурса

Новый app test — `pageWindowBoundaryUsesActualRowsForBothResources`.
У каждого ресурса одна исходная строка и 10 000 добавленных: `PrdSpec` одного
JOURNAL, `Nomenclature` одного typeNom. Bulk fixture DML использует H2
`system_range` через JdbcTemplate внутри transaction; оно исключено из trace.
Измеренные чтения идут через обычный REST → canonical → Hibernate путь,
настоящие C5 grants, RLS и отдельные persistence contexts, OSIV/L2 off.

| Запрос для каждого ресурса | Результат | Business SQL |
| --- | --- | --- |
| page 0 / size 50 | 50 строк, total 10 001 | 1 content + 1 count |
| page 199 / size 50 | Полная последняя разрешённая страница | 1 content + 1 count |
| page 199 / size 0 | Те же 50 строк, ресурсный default | 1 content + 1 count |
| page 49 / size 200 | Полные 200 строк; последние 50 совпадают с page 199/50 | 1 content + 1 count |
| page 200 / size 50 | INVALID_REQUEST | 0 |
| page 50 / size 200 | INVALID_REQUEST | 0 |
| page 200 / size 0 | INVALID_REQUEST | 0 |

Sort для `PrdSpec` — id, для `Nomenclature` — code/id; повторная последняя
страница совпадает, видимый total неизменен. Все успешные trace имеют `OTHER=0`.
Для каждой из шести запрещённых страниц trace содержит **только 2 exact C5
queries** (API/root entity), без content/count/RLS/role SQL. Это проверяется
assertions, а не выводится из пустого content.

На успешных глубоких страницах C5 — 14 queries для выбранного `PrdSpec` запроса,
10 для `Nomenclature`. Вместе с двумя data statements это 16 и 12 соответственно;
число data queries не растёт на size 200. Первый `PrdSpec` запрос дополнительно
считает RLS ids (2 grants + 2 user queries); далее они кешируются по отдельному
RLS механизму. Выбранные aliases/filters указаны в versioned SQL artifact.

Стоимость окна для этих direct-grant запросов подтверждена по statement count.
Maximum projections, DETAIL, реальные role grants и полные graph budgets остаются
в З2–З4. Значение 10 000 является рабочим default; финальный общий cost gate З4
и проверка выбранной deployment DB не заменяются этим H2 тестом.

### 8.4. Идентичность и команды

Новый `platform-rest` source fingerprint:
`a89b2dc6cf44406e2adfa8e11620fdaf8c7957a8b3e4a3197cbc14db78be0193`, 50 input files.
В manifest изменена только эта запись. Упакованный и установленный JAR SHA-256
совпадают: `b286c5c35fa200861b8a409f98148b7d7cc49ba15a69553c480dbf6334b93d75`.
Surefire classpath подтвердил путь к этому artifact в `.local-maven-repository`.
SHA-256 проверенного app pilot source:
`01a73122abfb93c158ba33c9f890f73de45257441ca71878cebc85e9176f7dce`.
SHA-256 [SQL artifact З1](evidence/rest-read-stage3-z1-sql-20261003.txt):
`97b68c49619bfc007c6ba94bfaa7455788dbfb2e0532d6c178041b8bc6b279b6`.

```powershell
# $restMaven, JAVA_HOME и $restRepo заданы по §7.
& $restMaven --batch-mode --no-transfer-progress --offline "-Dmaven.repo.local=$restRepo" -f platform-rest/pom.xml install
& $restMaven --batch-mode --no-transfer-progress --offline "-Dmaven.repo.local=$restRepo" '-Dtest=RestReadPipelinePilotIT,PlatformApiBaselineTest,PlatformPublicSurfaceTest' test
.\scripts\bootstrap-local-dependencies.ps1 -ValidateOnly -LocalRepository $restRepo
```

Локальные logs — `target/rest-stage3-z1`. Повторная strict validation показала
`OK fingerprint: platform-rest`, затем остановилась на прежнем sibling
`dynamicreports`. Внешние fingerprints и clean failure не закрывались изменением
REST manifest. З1 завершён; следующий пакет — З2, C5/admin parity.

## 9. З2 — C5/admin parity, 2026-10-04

### 9.1. Записанные решения API-review

1. `C5ReadPermissionRegistry` (`platform-rls`) хранит определения C5 read keys,
   переданные registrars (`C5RestOperationRegistrar`, `C5EntityReadPermissionRegistrar`).
   Признак C5 read — только явная регистрация; префикс, CHECK_ONLY или wildcard не
   используются. Регистрация не выдаёт грант.
2. `C5ReadDecisionService` (`platform-rls`) — единственная реализация exact/global/read:
   точный ключ, `dimensionValueId == null`, только `canRead`; USER — прямые гранты плюс
   гранты назначенных ролей, ROLE — только собственные; отказ хранилища пробрасывается.
   `DefaultC5PermissionEvaluator` делегирует все три проверки сервису; прежние
   конструкторы сохранены, bean создаётся из общего сервиса.
3. App admin: `collectEffective` для C5 keys использует общее решение (источники
   «прямой»/«роль: X», update/delete всегда не-allow), `currentSingleGrant` читает
   только exact global строку, `saveSingleGrant` редактирует только её и отвергает
   update/delete, `saveGrants` для C5 key отвергается, value-scoped строки не
   меняются; UI не предлагает update/delete как рабочие C5-права.
4. Прочие CHECK_ONLY измерения, обычные RLS-измерения, wildcard `*`, `AccessService`
   и storage grants не менялись.

### 9.2. Идентичность и инструменты

Проверка выполнена на JDK 21.0.11 (Axiom), Maven из IntelliJ IDEA CE 2025.2.3,
offline, против `.local-maven-repository`; Hibernate 7.2.12.Final, H2 2.4.240.
Перед root-прогонами текущие `platform-rls`, `platform-spring-boot-autoconfigure` и
`platform-rest` установлены в repository (`install`); `platform-core` не менялся.

| Файл | SHA-256 |
| --- | --- |
| `platform-rls/.../c5/C5ReadPermissionRegistry.java` | `7507029aa3bc0ff3ef2d63acf7e83e1118fa62eca301ea284954172a6ee5b479` |
| `platform-rls/.../c5/C5ReadDecisionService.java` | `96c1c1128cb186f158b5958b8497656ce42ce1e4505163ff42f77eed6afb85da` |
| `platform-rls/.../c5/DefaultC5PermissionEvaluator.java` | `7ad2d8664d202fe60638afe3ad149f18127a9e62b09679348d79cfdd118f26b7` |
| `platform-rls/.../config/RlsAutoConfiguration.java` | `84ec1b3c915aeb7c30140117fb53d6a8af7c39a228f535ff8af80a54c424be40` |
| `platform-rest/.../security/C5RestOperationRegistrar.java` | `82f5b50ed5d4d9902f6ac07678af14e6e271579f1cb3236b219456b3bb5aa4b9` |
| `platform-rest/.../config/RestReadAutoConfiguration.java` | `468bd7d8b728152405034ee9702c58060cf429576c930aa2240ad6cac528d962` |
| `platform-spring-boot-autoconfigure/.../data/config/C5EntityReadPermissionRegistrar.java` | `76cdee6c374d38da92551acad26e1bcf0a924a812e36fdd7d9ca3174307c6a58` |
| `platform-spring-boot-autoconfigure/.../data/config/DataAccessAutoConfiguration.java` | `6f3a5bcb21a9e1ae10d864e8d8a7cd99bdf66052e77daac0a5d98a8ea5aacc9e` |
| `src/main/java/org/ip/service/AccessGrantAdminService.java` | `5691e21acc3770ddc6e387e8c700fb74ae93703ae7583ae4cdb9ded770496c43` |
| `src/main/java/org/ip/views/admin/AdminView.java` | `c2770d8e75d25c6ea8ef68eba091c45efed275c53c0c57db889f7cd10e4c443c` |
| `src/test/java/org/ip/security/C5GrantAdminParityIT.java` | `b31c49c74c4f1e2c12fd259df3f9abc5b0bb8faaec961bfd22df956a842fe770` |
| `src/test/resources/platform-api-baseline/platform-rls.api` | `f3463244cca4b4d5ca628c9f7232aacd76d76a90df580bfca5d2ee04fca49b98` |
| `src/test/resources/platform-public-surface.txt` | `d0864834e6ef94088fdfe2f34ea5b5c4250a199d43aed3fb863cbb7334c628d0` |
| `docs/architecture/d1-surface-measurements.md` | `e44386a89e9d568a0a2f2201f8e6a4f0172720415be50ca821795a878221132c` |

Локальные logs — `target/rest-stage3-z2`. Новые owner tests:
`C5ReadDecisionServiceTest` (9), `C5ReadPermissionRegistryTest` (2); обновлены
`C5RestOperationRegistrarTest`, `C5EntityReadPermissionRegistrarTest`; app IT
`C5GrantAdminParityIT`.

### 9.3. Прогоны

| Набор | Результат |
| --- | --- |
| `platform-rls` install (verify + tests) | 23/23 (было 12; +9 decision, +2 registry) |
| `platform-spring-boot-autoconfigure` install | 32/32 |
| `platform-rest` install | 91/91 |
| Root `C5GrantAdminParityIT,RlsAutoConfigurationSmokeTest` | 24/24 (7/7 + 17/17) |
| Root `PlatformApiBaselineTest,PlatformPublicSurfaceTest,PlatformRlsModuleTest,PlatformCoreSurfaceTest` | 24/24 (3+10+5+6) |
| Root `RlsReadGateTest,RlsDimensionFactsIT,RestReadPipelinePilotIT` | 24/24 (5+11+8) |

### 9.4. Матрица §6.3 в прогонах

| Сценарий | Подтверждение |
| --- | --- |
| Только `dimension="*"` | `wildcardGrantDoesNotAuthorizeC5ReadButKeepsLegacyRlsSemantics`; C5 deny, RLS `JOURNAL` allow/unlimited |
| Точный global USER read grant | `saveAndRevokeThroughAdminIsImmediatelyVisibleToServer` (save → allow) |
| Точный ROLE read grant и назначенная роль | `roleGrantIsEffectiveOnlyWhileRoleIsAssigned` |
| Точный grant только с dimension value | `valueScopedOnlyRowDoesNotBecomeGlobalC5Read` |
| No grant / отзыв | `saveAndRevoke...` (revoke → deny), `C5ReadDecisionServiceTest.noGrantAndRevokeFailClosed` |
| USER без allow, ROLE с allow | `roleGrantIsEffectiveOnlyWhileRoleIsAssigned` |
| Роль без назначения пользователю | `roleGrantIsEffectiveOnlyWhileRoleIsAssigned` (unassigned user deny; ROLE-решение allow) |
| Provider/storage failure | `C5ReadDecisionServiceTest.storageFailurePropagatesInsteadOfAllowOrEmptySuccess` |
| Read-only grant contract | `c5SaveRejectsWriteFlagsAndValueScopedInputAndKeepsHistoricalValueRows`; update-only row — `updateOnlyGlobalRowDoesNotGrantReadAndIsNotOfferedAsC5Right` |
| Legacy CHECK_ONLY не сломан | `legacyCheckOnlyDimensionKeepsExistingSemantics`; smoke 17/17 сохраняет прежние проверки |

### 9.5. Границы

Изменение — рефакторинг общей C5 read-семантики: серверный запрос остался тем же
exact/global запросом, поэтому SQL-бюджеты REST-пилота (14/10 C5 queries) не меняются
и подтверждены повторным `RestReadPipelinePilotIT` 8/8. Существующие value-scoped строки
не мигрировались и не удалялись. Межзапросный кеш решений не вводился. З2 завершён;
следующий пакет — З3, реальные роли и политика UI/service callers.
