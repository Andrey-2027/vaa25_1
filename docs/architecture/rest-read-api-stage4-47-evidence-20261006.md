# F-REST-READ-4.7: итоговые gates

- Дата: 2026-10-06, Europe/Moscow.
- Git HEAD: `d11c67424497f232d50181fb3626d90e87760123`, dirty checkout; общий снимок включает параллельные изменения регистра/Report Studio.
- Статус: локальная read-приёмка завершена. З5/Gate 3A/3B и 4.7 закрыты полным стандартным bootstrap на согласованном стабильном checkout; внешний C5/C, consumer и deployment gates остаются отдельными.
- План: [этап 4](rest-read-api-stage4-http-plan.md).
- Контракты и пилоты: [4.5](rest-read-api-stage4-45-evidence-20261005.md), [4.6](rest-read-api-stage4-46-evidence-20261005.md), [инструкция клиента](rest-read-api-client.md).

## 1. Исправления и review границ

1. Default `RlsReadableIdsResolver` теперь является отдельным делегатом session-scoped cache.
   Возвращение самого cache регистрировало второй bean того же concrete-типа и ломало injection
   в `RlsAutoConfigurationSmokeTest`. Scope и RLS policy не менялись. Новый owner-тест проверяет
   единственность concrete-cache bean и реальное делегирование; owner — 27/27.
2. Старые canonical read/write/lookup fixtures получили явные `ENTITY:<FQN>` C5 read grants
   только нужным субъектам и типам. RLS wildcard не стал C5-разрешением. BRANCH/JOURNAL,
   validation veto и запрет чужой строки сохранены; отзыв в deep-link тесте снимает JOURNAL,
   оставляя независимый C5 prerequisite. Анонимный canonical caller отклоняется первым C5 gate;
   direct repository tests продолжают проверять RLS authentication denial.
3. Reviewed core/autoconfigure additions: aggregate contribution/delete callbacks, constructors
   и подключение participants к bean factories. Старые core constructors сохранены. Baseline
   обновлён адресно для core и autoconfigure; подпись factory method не является app callback.
   Participants реализуются платформенным регистром: роль `MODULE_API`, без неиспользуемых
   app SPI entries. Новые register package edges перечислены явно.
4. Новый RecorderRef UI действительно называет пять платформенных типов. Их app API usage
   зафиксирован явно; `EntityReadAccess` и `InstanceNameResolver` получают роль `APP_API`
   в core registry. Причина — bounded fixed-graph reads и планирование объявленных instance-name
   paths. REST-граф по-прежнему независим от UI FetchPlan; API baseline фиксирует существующие
   сигнатуры, новый read SPI в этом пакете не добавлялся.
5. Register entity scan ограничен `org.ipro.register.persistence`; состав core module и
   транзитивное Report Studio → register замыкание обновлены по фактическим декларациям.
   Замыкание теперь содержит 15 явно перечисленных типов, а не wildcard. Физическое выделение
   регистра и Report Studio остаётся отдельной работой.
6. Intentional register period change `DATE → DATETIME` отражён в after snapshot и точном
   semantic diff. Исторический before snapshot сохранён. Startup-validator fixtures предоставляют
   новые обязательные collaborators автоконфигурации, сохраняя реальные JPA mappings и startup validation.
7. Consolidation guard проверяет текущий flow editor вместе с `pageSettingsPanel()`, как
   `ReportEditorView`. Шесть старых backing controls заменены flow/drag-and-drop/palette controls;
   остальной список остаётся точной проверкой attachment. Поведенческий parity — 42/42,
   consolidation — 2/2; production UI этим пакетом не изменён.

## 2. Подтверждённые проверки до полного bootstrap

| Проверка | Результат |
| --- | --- |
| RLS owner install, включая новый regression | 27/27, exit 0 |
| Исправленные C5 fixtures, deep links, smoke, numbering, metadata, register startup | Подтверждены адресным прогоном; в исходных 141 tests оставались только четыре structural/guard failures, затем исправлены |
| Final structural set: API/public/core/Vaadin surface, package matrix, persistence registration, starters, bootstrap/manifest, report parity | 90/90, exit 0 |
| Architecture follow-up: app/background/UI/core boundaries, dependency direction, report authorization | 36/36; единственный отдельный отказ в closure исправлен |
| Final subsystem closure | 2/2, exit 0 |
| Strict `-ValidateOnly`, без drift overrides | exit 0 |

Все scoped baseline/measurement write properties выключены в полном bootstrap.
Функциональная БД — H2; PostgreSQL не является обязательным условием REST read-приёмки.

## 3. Воспроизводимость

Toolchain: Axiom JDK 21.0.11, Maven 3.9.9; один repository `.local-maven-repository`, offline.
Bootstrap запущен через Windows PowerShell 5.1: legacy fingerprints v1 внешних forks совпадают
с manifest именно в этой среде; PowerShell 7 сортировал их иначе. External fingerprints
не подгонялись. Reviewed v2 fingerprints core/autoconfigure/RLS обновлены намеренно.

JVM temp направлен в workspace через `MAVEN_OPTS`:
`-Djava.io.tmpdir=<workspace>/.rest47-evidence/tmp -DargLine=-Djava.io.tmpdir=<workspace>/.rest47-evidence/tmp`.
`JAVA_TOOL_OPTIONS` не использован: его stderr banner прерывал toolchain probe Windows PowerShell.

Диагностическая app сборка имела отдельный Maven output с окончанием `/target/test-classes`,
чтобы ArchUnit `DO_NOT_INCLUDE_TESTS` сохранял семантику. Ранний произвольный output path
включал test probes в production guards; эти результаты не засчитаны как product defects.
Resolved dependency-tree path для isolated gate задаётся свойством
`platform.runtime.dependency-tree`; default обычной сборки сохранён.

Стандартный bootstrap использует настоящий `pom.xml`, обычный `target`, все manifest steps
и application `clean verify`. `SkipApplication`, source/toolchain drift overrides и пропуск
application tests не применяются. Некоторые dependency steps manifest штатно имеют `skipTests`;
это не заменяет отдельные required owner tests, в частности RLS 27/27.

До application compile был сохранён диагностический inventory 998 inputs (`src`, шесть read-owner directories и `scripts`).
После устранения последнего рассогласования опубликован текущий snapshot всего `src`, всех `platform-*`,
`scripts` и корневого POM: [1366 inputs](evidence/rest-read-stage4-47-sources-20261006.sha256),
SHA-256 `f371e5ad9ae5044d159c3013c78740de6edcb62caf1cc16eab69c6f30d599acc`.
В финальном стабильном прогоне inventory сохранён до и после bootstrap: оба файла
имеют указанный SHA-256 и совпадают построчно. Предыдущий нестабильный прогон
не используется как доказательство неизменности исходников.
SHA-256 установленных owner JAR совпал с соответствующим `target` JAR для восьми artifacts:
[artifact receipts](evidence/rest-read-stage4-47-artifacts-20261006.json).

## 4. История нестабильного прогона и исправления

Стандартный bootstrap 2026-10-06 прошёл все dependency steps и дошёл до application tests.
Required owner results: core 149/149, autoconfigure 32/32, REST 111/111, REST-web 109/109,
backend starter 1/1, Vaadin starter 1/1. RLS отдельно проверен 27/27.

Application: **1830 tests, 4 failures, 1 error, 6 skipped**, exit 1.
Все прежние 14 C5/problem classes из Stage-3 evidence §12 прошли.
Четыре HTTP IT — 17/17; Pipeline 13 + Roles 8 + Admin 7 + JPQL preview 5 — 33/33;
совместный REST/compatibility результат **50/50**, skipped 0.
Direct HTTP maxima: 26/25 для specifications LIST/DETAIL, 21/17 для nomenclature.
Две роли: 79/78 и 59/46; short-circuit нашёл разрешение второй роли, в отличие от
меньших observations 4.6. Принятые ceilings не менялись. Size 1/50/200, content/count,
loaded-state и serialization SQL=0 проходят. Шесть skipped — только
`RecorderSetWriterPostgresIT`, требующий отдельной PostgreSQL среды; REST H2 tests не пропущены.

Отказы были в `PlatformApiBaselineTest`, `PlatformPublicSurfaceTest`,
`PlatformVaadinModuleTest`, `PlatformVaadinSurfaceTest`, `WorkspaceManifestTest`.
После сборки Vaadin owner параллельно появился package-private `PreciseLocalDateTimeField`:
source count 211 → 213, production types 130 → 131. Runtime JAR ещё не содержал этот класс;
baseline не мог быть проверен, fingerprint и measurements стали stale. Поэтому предыдущие
зелёные structural results не закрывают полный gate. Frontend/package phases не выполнились.

Текущий helper и его binding/tests просмотрены: это внутренний precise DATETIME editor,
который сохраняет fractional seconds. После этого Vaadin переустановлен — **475/475**;
source counts и internal-role budget синхронизированы, fingerprint обновлён намеренно,
Vaadin baseline проверен адресно, artifact measurement 483. Финальный повтор пяти
отказавших gate-классов — **30/30, exit 0**, без baseline/measurement write flags;
последний strict `-ValidateOnly` — **exit 0**.

[Machine-readable results](evidence/rest-read-stage4-47-results-20261006.json).
После подтверждения пользователем завершения параллельных изменений выполнен новый
полный стандартный bootstrap. Его окончательный результат приведён ниже; адресные
прогоны не подменяют полную приёмку. Полный C5/C не объявляется завершённым.

### 4.1. Финальный стабильный прогон — 2026-10-06

Bootstrap без skip/drift overrides завершился **exit 0**, application завершено
в **12:07:35 +03:00**. Application `clean verify`: **1830 tests, failures 0,
errors 0, skipped 6**. Единственный пропущенный класс —
`org.ipro.register.RecorderSetWriterPostgresIT`; REST/H2 приёмка от PostgreSQL не зависит.

| Обязательный контур | Итог |
| --- | --- |
| Core / autoconfigure | 149/149 и 32/32 |
| Vaadin | 475/475 |
| REST / REST-web | 111/111 и 109/109 |
| Backend / Vaadin starters | 1/1 и 1/1 |
| RLS owner, отдельный install до bootstrap | 27/27 |
| HTTP contract / pilot / security / session isolation | 5 + 3 + 5 + 4 = 17/17 |
| Pipeline / roles / admin parity / JPQL preview | 13 + 8 + 7 + 5 = 33/33 |
| Прежние 14 C5/problem classes | Все прошли в полном application verify |
| API/public/Vaadin/manifest gates, ранее отказавшие | 30/30, без write properties |
| Strict validation после полного bootstrap | exit 0, без overrides |
| Source inventory до/после | 1366 inputs, SHA-256 совпал |
| Installed/target owner JAR | Совпали для всех восьми artifacts |

`vaadin:25.1.6:build-frontend` обнаружил необходимость нового production bundle,
выполнил Vite и завершил frontend за 30 720 ms. Затем выполнены JAR packaging
и Spring Boot repackage: `target/Vaa25_1-1.0-SNAPSHOT.jar`.
Runtime dependency tree: `target/application-runtime-dependency-tree.json`;
starter composition и API baseline проверены на разрешённых runtime artifacts.

Численные SQL/graph/window ceilings, v1 OpenAPI/wire/Problem snapshots,
отсутствие SQL при serialization и session/context isolation подтверждены
теми же HTTP/H2 и compatibility tests. Финальные counts и skipped-class names
записаны в machine-readable results, актуальные JAR SHA-256 — в artifact receipts.

З5/Gate 3A/3B и этапы 4.0–4.7 закрыты для локального read-среза. Родительский
план, Stage-3 планы/evidence и roadmap согласованы. Это не закрывает полный
C5 write/action/UI rollout, CI на чистом checkout или внешнюю consumer/deployment приёмку.

Raw logs и диагностические outputs сохраняются локально в
`.local-maven-repository/rest-stage4-47-evidence-20261006/`:
`bootstrap-final-stable.log`, `strict-final-stable.log`,
`stable-before.sha256`, `stable-after.sha256`,
`bootstrap-final.log`, `rls-fix-final.log`, `final-structural.log`,
`architecture-followup.log`, `subsystem-closure-final.log`, `vaadin-current.log`,
`vaadin-final-gates.log`, `strict-latest.log`. Исторические диагностические failures
сохранены, но не засчитаны итоговыми gates.

Повторная команда (без skip/drift switches):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Axiom\AxiomJDK-21'
$rest47Tmp = [IO.Path]::GetFullPath('.local-maven-repository/rest-stage4-47-evidence-20261006/tmp')
$env:MAVEN_OPTS = "-Djava.io.tmpdir=$rest47Tmp -DargLine=-Djava.io.tmpdir=$rest47Tmp"
& 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' -NoProfile -ExecutionPolicy Bypass `
    -File scripts/bootstrap-local-dependencies.ps1 `
    -JavaHome $env:JAVA_HOME `
    -MavenCommand 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd' `
    -LocalRepository .local-maven-repository -Offline
```

## 5. Внешнее включение

HTTP opt-in остаётся выключен. Для открытия потребителю отдельно нужны общий gate C5/C,
целевые учётки/roles/grants, TLS, согласованный consumer contract и проверки на выбранной
deployment БД. Тестовые grants не являются production seed.

Rollout checklist:

- Подтвердить C5/C и выбрать окружение/БД; при смене БД повторить deployment проверки.
- Согласовать v1 schema, поля/фильтры, int64/nullable handling и конечное окно с потребителем.
- Выдать отдельной учётке/роли API/entity/attribute/reference и RLS grants через действующий admin path.
- Проверить TLS, разрешённые list/detail и отказы 401/403/404; сверить cost и trace/log handling.
- Включить opt-in только после этих проверок. Rollback — выключить opt-in и отозвать consumer grants.

Запись/синхронизация, owned-секции, новый IAM и весь C5 write/action/UI rollout в read-срез не входят.
