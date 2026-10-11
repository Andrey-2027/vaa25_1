# F-REST-READ-3: evidence завершения

- Дата: 2026-10-03, Europe/Moscow.
- Актуализация 2026-10-06: З0–З5 и Gate 3A/3B закрыты. Полный стандартный bootstrap на стабильном source snapshot: 1830 application tests, failures/errors 0; frontend/package и strict validation — exit 0. Общий C5/C и внешнее включение остаются отдельными; [evidence 4.7](rest-read-api-stage4-47-evidence-20261006.md).
- Статус: З0–З5 выполнены; Gate 3A/3B и локальная серверная приёмка этапа закрыты.
- План: [`rest-read-api-stage3-completion-plan.md`](rest-read-api-stage3-completion-plan.md).
- Контрольное ревью: [2026-10-05, 40/40 и открытый З5](rest-read-api-stage3-review-20261005.md).
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

## 10. З3 — реальные роли и политика UI/service callers, 2026-10-04

### 10.1. Что проверено

Прикладной IT `src/test/java/org/ip/rest/RestReadRoleMatrixIT.java` (8/8) работает на
полном контексте `Application`. Фикстуры — настоящие `User`/`Role`/назначения
(`UserRepository`/`RoleRepository`); гранты ролей сохраняются действующим
`AccessGrantAdminService` (`saveSingleGrant` для entity/API/ATTR, `saveGrants` для
построчных JOURNAL/BRANCH и legacy CHECK_ONLY), а прямой пользователь-эталон parity-
теста — тем же `AccessGrant`-хранилищем. Production-механизмы не подменяются:
`UserRepositoryRlsRoleResolver`, `C5ReadDecisionService`,
`DefaultC5PermissionEvaluator`, `C5ReadPermissionRegistry`, Hibernate RLS/фильтры и
`RlsReadGate` — настоящие; единственная тестовая подмена — `DataInitializer`.
Условия прогона те же, что у пилота: `spring.jpa.open-in-view=false`, Hibernate
second-level cache off, `platform.rest.read.max-window=10000`; публичная поверхность
и API baseline в З3 не менялись (новых типов нет).
Приложение в З3 не пересобиралось: изменён только app-тест и документация.

### 10.2. Матрица §7 плана

| Пункт §7 плана | Проверка в `RestReadRoleMatrixIT` |
| --- | --- |
| 1. Реальные fixtures, production resolver/provider не мокать | все 8 тестов: роли/пользователи/назначения, гранты через admin service, production `UserRepositoryRlsRoleResolver` и C5-контур |
| 2. API/entity/output/filter/sort/id и reference-требования через роль | `roleGrantsMatchDirectGrantsForEqualPermissions`; `missingSinglePermissionDeniesBeforeAnyBusinessData` (API, entity, output `comment`, filter `journal`, default sort `code`, reference target, `id`) |
| 3. Direct vs role parity; два субъекта с разными JOURNAL | `roleGrantsMatchDirectGrantsForEqualPermissions` (одинаковые данные/total); `differentJournalRolesSeeDifferentRowsAndHiddenDetailsStayHidden` (каждый — только своя строка; скрытый и отсутствующий detail — `DETAIL_NOT_FOUND`; FK-фильтр на скрытую цель пуст) |
| 4. Отзыв/снятие роли в свежем вызове, без переноса решений | `revocationAndRoleChangesTakeEffectWithoutRestart` (отзыв `REST:...:LIST` при живом `DETAIL`, отзыв `ENTITY:PrdSpec`, снятие и возврат назначения; witness-субъект всё время видит только свою строку) |
| 5. Отсутствие одного права — deny до business data; fixed fetch superset не требует лишнего гранта | `missingSinglePermissionDeniesBeforeAnyBusinessData` (SQL-аудит: ни одного `from prd_spec`/`from nomenclature`/`from journal`); `unrequestedProjectionFieldNeedsNoGrantDespiteFixedFetchSuperset` (LIST `id/codeSpec` и DETAIL `id/codeSpec/journalId` без грантов Nomenclature проходят, хотя LIST/DETAIL maximum paths содержат `nomenclature.code`; запрошенный `nomenclatureCode` в той же роли отказывает) |
| 6. Anonymous/system/bypass, несовпадение identity, скрытый/отсутствующий detail, FK-фильтр | `anonymousSystemAndPrivilegedBypassAreRejected` (UNAUTHENTICATED для отсутствующей аутентификации, `system`, `anonymous` — без SQL; `RlsContext`-bypass через `withDimensionAdministration` — FORBIDDEN без business SQL); identity mismatch — `differentJournalRoles...` (пользователь без назначенной роли deny, роли с грантами существуют); скрытый/отсутствующий detail и FK-фильтр — `differentJournalRoles...`; отказ provider — owner-тест З2 `C5ReadDecisionServiceTest.storageFailurePropagatesInsteadOfAllowOrEmptySuccess` (мокать production provider в app IT §7.1 запрещает) |
| 7. UI list/detail, app service read, запрещённый caller, class/row RLS | `uiAndServiceCallersUseRoleEntityGrantsWhileRestNeedsApiGrants` (`EntityDataAccessResolver` list/detail и `CanonicalEntityService<PrdSpec>` по `ENTITY:PrdSpec` работают без REST-ключей; REST тем же субъектом — FORBIDDEN до явной выдачи API/ATTR; caller без entity — `C5AccessDeniedException`); `legacyClassAndRowRlsStillApplyToRoleReaders` (C5 + `ENTITY:ReceivingDocument` CHECK_ONLY + JOURNAL/BRANCH построчно; отзыв CHECK_ONLY — пустое чтение, отзыв C5 — исключение, отзыв BRANCH — строка скрыта) |

### 10.3. Прогоны

| Набор | Результат |
| --- | --- |
| `RestReadRoleMatrixIT` (новый) | 8/8, BUILD SUCCESS (`target/rest-stage3-z3/run03.log`) |
| `RestReadRoleMatrixIT,RestReadPipelinePilotIT,C5GrantAdminParityIT,RlsAutoConfigurationSmokeTest,CanonicalReadBoundaryIT,CanonicalReadTransactionBoundaryIT,FetchPlanReadBoundaryIT,FetchPlanMeasurementIT,DetachedRenderCostIT,EntitySummaryAccessPilotTest,EntitySummaryActionPilotTest,EntitySummaryReadPlanPilotTest,RlsReadGateTest` | 99/99, BUILD SUCCESS (`target/rest-stage3-z3/run04-canonical-set.log`) |

Пилот-8/8 в общем наборе подтверждает прежние C5/RLS SQL-бюджеты (14 C5 + 2 data
queries на вызов PrdSpec, 10 C5 для Nomenclature) на неизменных production-классах.
Owner-модули в З3 не пересобирались: их код не менялся с проверенного в З2 набора.

### 10.4. Политика перехода UI/service callers

Фактические canonical read callers приложения, для которых действует entity C5:

| Caller | Путь | Что требуется для чтения |
| --- | --- | --- |
| UI list/detail форм (`FormCoordinator`, `ItemFormWrapperView`, `SelectionFormAssembler`, `FormResolver` через `ServiceLocator`/`EntityDataAccessResolver`) | canonical `readPage`/`readDetail` | `ENTITY:<FQN>` роли или прямо пользователя + действующие class/row RLS-гранты |
| App services `UserService`, `SklNomOpaService`, `NomSklAttributeService`, `AttributeValueService`, `GridFormViewService` и прямые UI-вызовы (`WorkshopListView` и т. п.) | canonical handle (`CanonicalEntityService`/`EntityDataAccess`) | то же `ENTITY:<FQN>` |
| `LookupService` (lookup/list по id) | canonical secured query | `ENTITY:<FQN>` |
| Global search (`GlobalSearchService`) | canonical search | `ENTITY:<FQN>` |
| REST чтение (`RestReadService`) | canonical `EntityReadAccess` | `ENTITY:<FQN>` **и** API L/D **и** `ATTR:<FQN>:<segment>` запрошенных полей/фильтров/сортировки **и** entity/attr связанных to-one целей |

`DataInitializer` создаёт роли `ADMIN`/`MANAGER`/`VIEWER`, но **не создаёт ни одного
`AccessGrant`**; startup registrars (`C5EntityReadPermissionRegistrar`,
`C5RestOperationRegistrar`) только регистрируют определения ключей и тоже не выдают
гранты. Поэтому переход существующих читателей — явное действие администратора:
ключ → роль → причина → проверочный caller → ожидаемый отказ до выдачи → успех после.

| Ключ/грант роли | Причина | Проверочный caller | Отказ до выдачи | Успех после выдачи |
| --- | --- | --- | --- | --- |
| `ENTITY:org.ip.model.PrdSpec` + `JOURNAL` (id журнала) | UI list/detail спецификаций этой роли | `EntityDataAccessResolver.resolve(PrdSpec.class).list/detail`, `CanonicalEntityService<PrdSpec>.findAll/findById` | `C5AccessDeniedException` (caller без entity-гранта в том же IT) | total = 1 и строка журнала A; строка журнала B и её detail скрыты |
| `REST:specifications:v1:LIST`, `:DETAIL`, `ATTR:...PrdSpec:id/codeSpec/draft/comment`, `ATTR:...PrdSpec:journal`, `ENTITY:org.ip.model.Journal`, `ATTR:...Journal:id`, `ATTR:...PrdSpec:nomenclature`, `ENTITY:org.ip.model.Nomenclature`, `ATTR:...Nomenclature:code` | серверное REST-чтение спецификаций | `RestReadService.list/detail` | `FORBIDDEN` на API-гранте (до выдачи) / attribute / filter / sort / reference — каждая ветка отдельно | те же строки и total, что у прямого пользователя с тем же набором |
| `ENTITY:org.ip.model.Nomenclature` (+ `REST:nomenclature:v1:LIST`, `ATTR:id/code/name/typeNom`) | UI/REST справочника номенклатуры | UI canonical handle / `RestReadService` | `C5AccessDeniedException` / `FORBIDDEN` | list/detail справочника; без RLS-строк (у типа нет FILTERABLE-измерений) |
| `ENTITY:org.ip.model.ReceivingDocument` + legacy `ENTITY:ReceivingDocument` + `JOURNAL` + `BRANCH` | UI list/detail накладных этой роли | `CanonicalEntityService<ReceivingDocument>` | без C5 — `C5AccessDeniedException`; без CHECK_ONLY — пустое чтение; без BRANCH-строки — строка скрыта | накладная видна в list/detail |

Выдача — per key и per role, не массовая по каталогу: роль получает только те FQN,
которые действительно читают её callers. Тестовые роли/гранты фикстур не являются
production seed или migration script и создаются только в БД теста.

### 10.5. Границы

- Отказ provider (ошибка хранилища) не повторён в app IT намеренно: §7.1 запрещает
  мокать production provider; поведение deny/error зафиксировано owner-тестом З2
  `C5ReadDecisionServiceTest.storageFailurePropagatesInsteadOfAllowOrEmptySuccess` и
  веткой `SECURITY_UNAVAILABLE` серверного REST read (platform-rest tests).
- Привилегированный bypass проверен через действующий `withDimensionAdministration`;
  этот scope по дизайну `RlsStatementGuard` не допускает строковых чтений, поэтому
  REST-вызов внутри него отказывает до SQL — реальный business-read под bypass
  невозможен и не эмулируется.
- Attribute-права проверяются только для запрошенных полей/фильтров/сортировки;
  присутствие пути в fixed fetch superset (например `nomenclature.code` в LIST
  спецификаций) не требует гранта — подтверждено SQL-успехом без грантов Nomenclature.
- `RlsReadableIdsCache` инвалидируется по версии грантов; снятие назначения роли
  проверяется свежим C5-решением (роль резолвится на каждом вызове), а не кешем.

| Файл | SHA-256 |
| --- | --- |
| `src/test/java/org/ip/rest/RestReadRoleMatrixIT.java` | `17f3040b76c6296c7fd3550726be1b18ce1a0e41b19ca166a04cf8d3653b2d5f` |
| `docs/architecture/rest-read-api-stage3-completion-plan.md` | `118442ec196ab0112bd27ee79d11bd58262a8326c826fd3975eb56c2a50f2fc9` |
| `docs/architecture/rest-read-api-stage3-security-read-plan.md` | `5ad1c6a0d26ab9dd7bf24b35a6b6f2b958067c3e49c52f0eff444e586f01c7cc` |
| `docs/architecture/README.md` | `62c260fd4334fcf0e36a7ec9ac5e7aeaae27b4229750b6627bc2dad00e0c6113` |

З3 завершён; следующий пакет — З4, численные SQL/graph budgets обоих ресурсов.

## 11. З4 — численные SQL/graph/window бюджеты, 2026-10-04

Измерения выполнены app-пилотом `RestReadPipelinePilotIT` (12/12, BUILD SUCCESS,
`target/rest-stage3-z4/run03.log`) на настоящих JPA/Hibernate: OSIV и L2 выключены,
каждый замер идёт в свежем persistence context, session-scoped `RlsReadableIdsCache`
прогревается до измерения, SQL категоризируется мостом `SqlStatementAuditBridge`
(`C5_GRANT`, `RLS_GRANT`, `ROLE_RESOLUTION`, `CONTENT`, `COUNT`, `OTHER`). Прямой
субъект — USER-грант без ролей; ролевой субъект — реальные `Role`/`User` через
репозитории. `count` может отсутствовать на неполной странице (Spring Data) — это
допустимо, total проверен отдельно; в текущем H2 наблюдался 1 count на всех
сценариях. RLS predicate входит в content/count и не является отдельным запросом.

### 11.1. LIST — прямые гранты, RLS-кеш прогрет

| Сценарий | size | statements | C5 | RLS | roles | content | count | other | total |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `PrdSpec` default `id,codeSpec,draft` | 1/50/200 | 9 | 7 | 0 | 0 | 1 | 1 | 0 | 200 |
| `PrdSpec` max `+nomenclatureCode`, фильтр `journalId` | 1/50/200 | 17 | 15 | 0 | 0 | 1 | 1 | 0 | 200 |
| `Nomenclature` default `id,code,name` | 1/50/200 | 10 | 8 | 0 | 0 | 1 | 1 | 0 | ≥200 |
| `Nomenclature` max `+typeNom`, фильтр `typeNom` | 1/50/200 | 12 | 10 | 0 | 0 | 1 | 1 | 0 | 200 |
| `PrdSpec` фильтр на скрытый журнал (невидимая цель) | 50 | 12 | 10 | 0 | 0 | 1 | 1 | 0 | 0 |
| `Nomenclature` фильтр без совпадений | 50 | 10 | 8 | 0 | 0 | 1 | 1 | 0 | 0 |

Числа не меняются от size 1/50/200: бизнес-SQL и security-SQL не зависят от числа
строк (проверено ассертами). Unfiltered Nomenclature total зависит от общего
наполнения H2; проверены его консистентность по size и нижняя граница 200.

### 11.2. DETAIL

| Сценарий | statements | C5 | RLS | roles | content | count | other |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `PrdSpec` default `id,codeSpec,draft,comment` | 8 | 7 | 0 | 0 | 1 | 0 | 0 |
| `PrdSpec` max `+journalId,nomenclatureCode` | 16 | 15 | 0 | 0 | 1 | 0 | 0 |
| `PrdSpec` RLS-скрытый id → `DETAIL_NOT_FOUND` | 5 | 4 | 0 | 0 | 1 | 0 | 0 |
| `PrdSpec` отсутствующий id → `DETAIL_NOT_FOUND` | 5 | 4 | 0 | 0 | 1 | 0 | 0 |
| `Nomenclature` default `id,code,name` | 7 | 6 | 0 | 0 | 1 | 0 | 0 |
| `Nomenclature` max `+typeNom` | 8 | 7 | 0 | 0 | 1 | 0 | 0 |
| `Nomenclature` отсутствующий id → `DETAIL_NOT_FOUND` | 5 | 4 | 0 | 0 | 1 | 0 | 0 |
| Scalar-consume DETAIL и LIST вне транзакции | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

### 11.3. Роли: short-circuit и независимость от size

| Сценарий (max-проекция, size 50) | `PrdSpec`: statements / C5 / roles | `Nomenclature`: statements / C5 / roles |
| --- | --- | --- |
| Прямой USER-грант | 17 / 15 / 0 | 12 / 10 / 0 |
| USER-грант + выданная роль (short-circuit USER) | 17 / 15 / 0 | 12 / 10 / 0 |
| Одна разрешающая роль, size 1 | 48 / 30 / 16 | — |
| Одна разрешающая роль, size 50 | 48 / 30 / 16 | 33 / 20 / 11 |
| Две разрешающие роли (short-circuit первой) | 48 / 30 / 16 | 33 / 20 / 11 |

Роль резолвится на каждом вызове; второй разрешающий грант роли не удваивает SQL.
После сохранения grant кеш RLS прогревается; отзыв/снятие роли проверены в З3.
Числа — фактические пределы нынешнего C5-контура; межзапросный кеш решений не вводится.

### 11.4. Край окна и финальный W

`seedWindowRows` — 10 000 W-строк поверх фикстуры (итого 10 001 видимая строка
каждого ресурса). Успешные страницы у края: `page=199 size=50` (offset+size =
10 000) и `page=49 size=200` (offset+size = 10 000) — полные, total = 10 001;
statements: `PrdSpec` 16 (14 C5 + content + count; первый вызов 20 = 2 RLS + 2 roles
сверх прогретого), `Nomenclature` 12 (10 C5 + content + count). Следующая страница
(`page=200 size=50`, `page=50 size=200`, `page=200 size=0`) отклоняется как
`INVALID_REQUEST` за 2 statements (2 C5), без content/count. **Финальный W =
10 000** (default size 50, max 200) принят по этому измерению.

### 11.5. Графы: maximum paths и фактическая загрузка

| Ресурс/операция | Maximum paths (paths/prefix/глубина) | Загружено (fresh context) |
| --- | --- | --- |
| `PrdSpec` LIST | `id,codeSpec,draft,nomenclature.code` (4/1/2) | root id; nomenclature + code; **не** unitOfMeasurement, groupNom, journal, materials, operations |
| `PrdSpec` DETAIL | `id,codeSpec,draft,comment,journal.id,nomenclature.code` (6/2/2) | root id; journal (explicit subgraph, terminal id; identity = журнал фикстуры); nomenclature + code; **не** unitOfMeasurement, groupNom, materials, operations |
| `Nomenclature` LIST/DETAIL | `id,code,name,typeNom` (4/0/1) | root id; **не** unitOfMeasurement/groupNom (groupNom в фикстуре непустой) |

Скалярные колонки joined-строки (`journal.code`) материализуются to-one join'ом —
это разрешённая ADR-0007 дополнительная загрузка (граф описывает required state, не
список SQL columns); своих associations у `Journal` нет. `materials`/`operations`
не загружаются; коллекций/N+1 нет; scalar DTO не содержит entities/proxies (проверено
consume-шагом вне транзакции). UI LIST comparator на той же строке: UI paths
`[journal, nomenclature]`, SQL 3 (1 C5 + content + count), loaded journal/nomenclature/
UOM/groupNom=true; REST 16/12 с независимым фиксированным профилем — delta объяснена
отсутствием UI union/deepen и дополнительными attribute/reference C5 проверками.

### 11.6. Итог и файлы

Исторические 20/16 согласуются с §3: первый вызов 20 = 14 C5 + 2 RLS + 2 roles +
content + count; повторный 16 = 14 C5 + content + count. Записанные пределы:
LIST — ≤ 1 content, ≤ 1 count, 0 OTHER/RLS, C5 не растёт с size; DETAIL — ≤ 1 content,
0 count/OTHER/RLS; после scalar — 0 SQL.

Хеши файлов на момент записи §11:

| Файл | SHA-256 |
| --- | --- |
| `src/test/java/org/ip/rest/RestReadPipelinePilotIT.java` | `4d7e8134117254ef1de1ab51a1d5e6b6c271cc299b08210a60c62a5452a565eb` |
| `docs/architecture/rest-read-api-stage3-completion-plan.md` | `3e5c6f4ff39b80e8a6a4448e84c915974d2e3d06c528c2c415d9dc1691076437` |
| `docs/architecture/rest-read-api-stage3-security-read-plan.md` | `eed2e9fb9e40fbae2aa9402342182401e4762647c18cdd4cf46b53ee2b63b626` |
| `docs/architecture/README.md` | `eb6061e683db8bdee3dbbcd16f7a226941fe46ca3551fae3055858fde5d4f602` |

З4 завершён; следующий пакет — З5, воспроизводимость и итоговые gates.

## 12. З5 — воспроизводимость: статус и открытый gate, 2026-10-04

### 12.1. Что проверено

| Проверка | Команда | Результат |
| --- | --- | --- |
| Owner install (verify) | `mvn -o -Dmaven.repo.local=.local-maven-repository -f platform-<модуль>/pom.xml install` | rls 23/23, core 147/147, autoconfigure 32/32, rest 91/91 |
| Starter verify | `... -f platform-backend-starter/pom.xml verify`, `... platform-vaadin-starter` | backend 1/1, vaadin 1/1 |
| Strict `-ValidateOnly` | `bootstrap-local-dependencies.ps1 -LocalRepository .local-maven-repository -ValidateOnly` | зелёный: 22 fingerprint'а (включая siblings), build order, координаты, workspace |
| Dependency bootstrap | `... -Offline -SkipApplication` | 23/23 BUILD SUCCESS, exit 0 |
| Полный bootstrap (application clean verify) | `... -Offline` | **FAILURE**: exit 1; app 1757 тестов, 20 failures + 22 errors в 14 классах |
| Root set canonical (13 классов) | `-Dtest=RestReadRoleMatrixIT,RestReadPipelinePilotIT,C5GrantAdminParityIT,RlsAutoConfigurationSmokeTest,CanonicalReadBoundaryIT,CanonicalReadTransactionBoundaryIT,FetchPlanReadBoundaryIT,FetchPlanMeasurementIT,DetachedRenderCostIT,EntitySummaryAccessPilotTest,EntitySummaryActionPilotTest,EntitySummaryReadPlanPilotTest,RlsReadGateTest test` | 103/103 BUILD SUCCESS |
| Root set surface (6 классов) | `-Dtest=RlsAutoConfigurationSmokeTest,RlsDimensionFactsIT,PlatformApiBaselineTest,PlatformPublicSurfaceTest,PlatformStarterCompositionTest,BootstrapScriptContractTest test` | 49/49 BUILD SUCCESS |

Числа owner/starter/strict/dependency получены до финального bootstrap; их логи
лежали в `target/rest-stage3-z5/`, который удаляется application clean-фазой
(bootstrap включает `mvn clean` приложения). Финальные логи:
`target/rest-stage3-z5/bootstrap-full.log`, `root-set-canonical.log`,
`root-set-surface.log`, `app-verify-failing-classes.txt`.

### 12.2. Application clean verify: 14 красных классов, внешний C5

| Класс | Failures/Errors |
| --- | --- |
| `AttributeValueRefRlsIT` | 1/1 |
| `NomenclatureAttributesIT` | 0/3 |
| `PrdSpecMetadataAggregateIT` | 1/1 |
| `ReceivingDocumentRulesIT` | 1/1 |
| `DeepLinkTwoUserAccessIT` | 8/0 |
| `RlsServiceWriteBoundaryIT` | 4/3 |
| `RlsStatementGuardTest` | 0/2 |
| `RlsUnauthenticatedAccessIT` | 3/0 |
| `ReportEditorConsolidationTest` | 1/0 |
| `MetadataDrivenAggregateSaveServiceIT` | 0/1 |
| `CanonicalWriteBoundaryIT` | 1/0 |
| `CharacterizationStandardPathIT` | 0/7 |
| `FetchPlanLookupHydrationIT` | 0/2 |
| `NumberingEngineIT` | 0/1 |

Доминирующая причина — `C5AccessDenied Entity C5 read denied for org.ip.model.*`:
приложение уже требует entity C5 на чтение в write/lookup/канонических путях, а эти
тесты не выдавали таких грантов. Это состояние параллельного полного C5-rollout —
внешний gate из §9.3 («полный C5»), а не дефект F-REST-READ-3: application verify
падает на классах, не входящих ни в один Stage-3 набор, а все изменённые этапом
файлы (пилот, декларации REST, документация) в root-наборах зелёные.

### 12.3. Открытый gate и условие закрытия

По §9.1 «полный этап при открытом обязательном gate не закрывать»: обязательный
стандартный bootstrap включает application clean verify, и он красный. Быстрый путь:
в рамках полного C5 обновить 14 тест-классов (гранты/ожидания), повторить полный
bootstrap и root-наборы; после зелёного — отметить З5 `[x]`, обновить заголовок и
roadmap, опубликовать финальный evidence. Всё остальное из §9.2 зелёное.

Strict `-ValidateOnly` выполнен после двух намеренных правок манифеста:
`platform-spring-boot-autoconfigure` `ea1b5dbf…`→`a19f336c…` и `platform-rest`
`a89b2dc6…`→`a6c33812…`. Записанные значения не воспроизводились ни из одного
git-состояния модулей (ни CRLF-дерево, ни LF-blob, ни комбинации переводов строк),
при этом модули чисты в git и их owner-тесты зелёные; приняты хеши committed-снапшота.
Хеш `platform-rls` (`797d5b34…`→`5ca95045…`) был исправлен тем же принципом раньше —
после добавления owner-тестов З2/З3; strict-прогон с ним зелёный.

Хеши на момент записи §12:

| Файл | SHA-256 |
| --- | --- |
| `src/test/java/org/ip/rest/RestReadPipelinePilotIT.java` | `4d7e8134117254ef1de1ab51a1d5e6b6c271cc299b08210a60c62a5452a565eb` |
| `docs/architecture/rest-read-api-stage3-completion-plan.md` | `261bc2b48d186560d0fc3cca27c5504222a2ed2a2599d158ba442179da7344cd` |
| `docs/architecture/rest-read-api-stage3-security-read-plan.md` | `eed2e9fb9e40fbae2aa9402342182401e4762647c18cdd4cf46b53ee2b63b626` |

`docs/architecture/README.md` не входит в коммит З5: рабочая копия содержит смешанные
незакоммиченные правки соседнего потока (информационный регистр), включая строку
evidence; его текущий хеш `eb6061e683db8bdee3dbbcd16f7a226941fe46ca3551fae3055858fde5d4f602`.

З5 остаётся открытым до зелёного application clean verify; это внешний gate полного C5.

## 13. З5 — окончательное закрытие, 2026-10-06

Полный стандартный bootstrap на согласованном стабильном checkout завершился exit 0.
Application: 1830 tests, failures/errors 0, 6 skipped только в PostgreSQL-only
RecorderSetWriterPostgresIT. REST HTTP/H2 + compatibility — 50/50, skipped 0;
прежние 14 C5/problem classes зелёные. Frontend/package выполнены, strict validation
без overrides — exit 0. Source inventory 1366 inputs до/после совпал; target/installed
JAR совпали для восьми owners. З5 и Gate 3A/3B закрыты.

Команды, owner counts, reviewed API/roles, source/JAR receipts и machine-readable
results находятся в [evidence 4.7](rest-read-api-stage4-47-evidence-20261006.md). §12 сохраняет исторический отказ; его статус
заменён этим итоговым результатом. Полный C5/C и deployment gate не закрываются
успешной локальной read-приёмкой.
