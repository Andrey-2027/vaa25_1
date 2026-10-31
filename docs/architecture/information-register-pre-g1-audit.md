# Регистр сведений перед G1: сверка планов, кода и evidence

**Уточнение 2026-10-31:** этот документ сохраняет результаты среза 28.10. Исправление первоначального CTE-пути выполнено; свежий регистровый CTE/security-набор — 60/60, но три ошибки `CLASSIFICATION_SOURCE_MISSING` остаются в двух соседних query-suites. REG-A2 после повторного обращения пользователя возвращён в обязательный план следующего расширения: отдельный backend consumer для runtime и последующей generated-приёмки; он ещё не реализован. REG-A3 не закрыт. Актуальные статусы, evidence и работы: [подробный план подготовки регистра к G1](../plans/information-register-g1-preparation-plan.md).

Дата: **2026-10-28**, Europe/Moscow. Статус: **аудит выполнен; остатки перечислены явно; весь регистровый выпуск не объявляется принятым**.

**Последующее решение пользователя:** сократить предварительные работы и начать G1 справочника. Перечисленные регистровые остатки остаются backlog; их закрытие не является условием первого catalog-среза. Очерёдность задаёт [минимальный план G1](../plans/entity-generation-g1-minimum-plan.md). Факты и результаты проверок этого аудита сохраняются.

Проверены [основной план Р0–Р14](information-register-development-plan.md), [план регистратора Г0–Г8](information-register-recorder-plan.md), [вынос S0–S5](information-register-extraction-plan.md), [итерация Р8](../plans/information-register-r8-listform-key-commands.md), последующие AppDev First ET6–ET9 и разделение query/report. Исторические протоколы не переписываются задним числом.

## 1. Вывод

Платформенный механизм уже реализует все четыре сочетания: самостоятельный/подчинённый и периодический/непериодический регистр. Полный проверенный `RegisterDescriptor` существует. Повторно разрабатывать периодичность, регистратор, чтение или протокол записи набора не требуется.

Остаток имеет три разных владельца:

1. **Приёмка существующей платформы:** актуализировать classification sources у старых query/test consumers, добавить внешний регистровый consumer и завершить текущий общий gate. Найденная ошибка CTE-приёмки воспроизведена свежим прогоном.
2. **Новая работа P8/G1:** перенести register facts в независимый design snapshot, определить draft/JSON/редактор и проверенный профиль генерации. Это не недоделка runtime-движка.
3. **Последующее развитие/эксплуатация:** новые операции, production-кейсы, migration/backfill существующих данных, дополнительные DB-профили и проведение. Их нельзя автоматически включать в условие начала G1.

Контракт GenerationPlan и первый G1 справочника можно разрабатывать сейчас. Для заявления о готовности **генерации регистра** сначала нужны текущая регистровая приёмка и внешний consumer, затем gate самого сгенерированного результата. Все перечисленные ниже остатки остаются открытыми, если явно не указано обратное.

## 2. Свежая проверка и границы доказательств

| Прогон 2026-10-28 | Результат | Доказательство |
| --- | --- | --- |
| `platform-register`, все `*Test` | **124 tests, 0 failures/errors/skips**, BUILD SUCCESS | [лог](../../outputs/register-pre-g1-audit-20261028/backend-tests-agent.log) |
| `platform-register-vaadin`, все tests | **24 tests, 0 failures/errors/skips**, BUILD SUCCESS | [лог](../../outputs/register-pre-g1-audit-20261028/vaadin-tests.log) |
| Приложение, `NomenclaturePriceCteSpikeTest` после Maven compile/testCompile | **5 tests, 0 failures, 5 errors**, BUILD FAILURE | [лог](../../outputs/register-pre-g1-audit-20261028/application-probe.log) |

Положительный owner-набор — **148 тестов**, не сумма всех прежних запусков. Backend PostgreSQL `*IT` в него не входят. Прогон приложения подтверждает один текущий блокер, а не число всех ошибок полного application verify.

Причина пяти ошибок: `NomenclaturePriceCteSpikeTest.setUp:72` создаёт четырёхаргументный `QueryBuilderMetadataCatalog`. Его compatibility constructor строит `EntityDescriptorCatalog` с `List.of()` sources. На managed `NomenclatureAccountingObject` с candidacy INFORMATION_REGISTER получается `CLASSIFICATION_SOURCE_MISSING` до выполнения CTE. Это тот же класс ошибки, который указан в baseline от 2026-10-27. Боевая query-autoconfiguration получает готовый каталог; вывод о поломке всего runtime из этого прогона не следует.

Источник: [compatibility constructor](../../platform-query/src/main/java/org/ipro/query/QueryBuilderMetadataCatalog.java), [тест](../../src/test/java/org/ip/application/register/NomenclaturePriceCteSpikeTest.java), [боевая конфигурация](../../platform-query/src/main/java/org/ipro/query/config/QueryAutoConfiguration.java).

Исправление должно передать существующий проверенный каталог/его sources. Ослабление fail-closed проверки или повторный независимый scan не является закрытием дефекта. Для standalone fixtures нужен согласованный набор источников, включая backend-вклад регистра.

Условия запуска: offline Maven, JDK 21; Mockito задан через явный `-javaagent` из проверенного ET9 runtime arguments. Первая попытка backend без premain-agent остановлена после отсутствия продвижения; она не включена в успешное evidence. PostgreSQL и полный/random-order прогон приложения заново не выполнялись.

Архив текущей сверки: [результаты owner suites](../../outputs/register-pre-g1-audit-20261028/owner-results.json), [снимки исходников SHA-256](../../outputs/register-pre-g1-audit-20261028/source-hashes.json), [HEAD](../../outputs/register-pre-g1-audit-20261028/git-head.txt), [планы до правки](../../outputs/register-pre-g1-audit-20261028/plans-before.json). XML обоих модулей и application probe сохранены в `outputs/register-pre-g1-audit-20261028/reports/`.

Сохранённые PostgreSQL-доказательства учитываются со своими датами и scope: [Р8, 55/55](evidence/register-r8-postgresql-20261009.md), [Р9, 35/35](evidence/register-r9-postgresql-20261012.md), [Р11, 24/24](evidence/register-r11-key-write-implementation-20261013.md), [ADR-0025 и модульный повтор 48/48](evidence/register-adr0025-row-lifecycle-20261015.md). Это не новый PostgreSQL-прогон на всех сегодняшних исходниках.

## 3. Р-серия: фактический статус

| Пункт | Код и более позднее evidence | Статус / действительный остаток |
| --- | --- | --- |
| Р0, воспроизводимость | Source/JAR фиксации отдельных этапов; текущий аудит сохраняет hashes и логи. | Выполнялось по срезам. Текущий общий application gate остаётся отдельным. |
| Р1–Р5, декларация, startup, права среза, размещение, SliceSpec/CTE | `RegisterMetadataValidator`, registry, readers; [Р2–Р3](information-register-r2-r3-acceptance.md), [Р5](evidence/register-r5-tests-20261004.md); сейчас owners регистра и CTE находятся в отдельных модулях. | Механизмы реализованы. Текущая сквозная CTE-приёмка упирается в ошибку sources из §2. |
| Р6, сохраняемый отчёт цен | Историческая реализация принята; [решение владельца 2026-10-20](evidence/r2-s7-price-report-removal-20261020.md) удалило сценарий. | **Снят**, восстановление отчёта и его Preview/PDF не является долгом регистра. Query slice сохранён. |
| Р7, canonical LIST slice | `RegisterSliceReader`, `SliceSpec`, list filter phases; [Г5](evidence/register-g5-cte-spike-20261005.md), [Р9](evidence/register-r9-batch-and-r8-parity-20261011.md). | Реализован; текущая межмодульная регрессия относится к gate приёмки. |
| Р8, стандартный UI, key change, recorder procedures | `RegisterFormCustomization`, key dialog, `RegisterKeyChanges`, `RegisterRecords`; [H2 parity](evidence/register-r9-batch-and-r8-parity-20261011.md), [PostgreSQL](evidence/register-r8-postgresql-20261009.md), свежие 24 UI tests. `captureOptions/applyViewState` сохраняют mode/asOf/context; код пилота проверяет JSON saved view и повторное открытие. | Техническая реализация подтверждена в указанном scope. **Отдельная пользовательская проба поздней Р8-итерации не подтверждена.** Положительная проба пилота от 2026-10-07 не принимается за отзыв на итерацию 2026-10-09/11. |
| Р9, point/batch и ключ | `RegisterSliceReader`, `RegisterBatchReadIT`; [PostgreSQL 35/35](evidence/register-r9-postgresql-20261012.md). | Закрыт в принятом объёме; прежний reader-bean/прикладной boilerplate позднее снят S1/S5. Не заводить его заново. |
| Р10, platform status и предметные потребители | [S0–S5](information-register-extraction-plan.md), ADR-0024, [ADR-0029](decisions/ADR-0029-entity-semantic-classification.md), [ET6](../plans/entity-types-appdev-first-et6-evidence.md). | Вынос и semantic kind реализованы. Условие «два production-примера до INFORMATION_REGISTER» **отменено ADR-0029**. Конкретный новый production-сценарий остаётся развитием, внешний consumer — незакрытой приёмкой поставки. |
| Р11, запись по ключу | `RegisterKeyWrites.set/delete`, versions/verdicts/retry/C5/RLS/lifecycle/audit; [evidence](evidence/register-r11-key-write-implementation-20261013.md), свежий owner gate. | Принятый set/delete-контракт реализован. `setAll`, commandId/outbox, явный SERIALIZABLE, REST и другие СУБД не реализованы этим пакетом и остаются расширениями. |
| Р12, дополнительные временные/ключевые семантики | Исходная таблица развития: first slice, history API, дополнительные ключи, интервалы и т. п. | Не входят в принятый минимальный runtime-контракт и первый G1. Добавлять по конкретному потребителю. |
| Р13, диагностика, генерация и эксплуатация | ET6–ET9 передали kind/ownership/common facts; полный register descriptor есть у backend owner. В Explorer/design snapshot нет полноценной специализированной проекции dimensions/resources/period/key. | **Частично.** Проекция и рецепт — следующий design/G1 пакет; миграции/физические проверки — gate соответствующего выпуска. |
| Р14, posting/накопление | Отдельный F-ERP-4; mutable recorder set уже работает без posting. | Отложен. Не является условием генерации регистра сведений. |

Отдельная граница Р11: стандартный UI ещё не предоставляет готового общего действия `SET_KEY/DELETE_KEY`; независимая карточка может писать через разрешённый canonical CRUD, а named set/delete доступны прикладному сервису. Рецепт, обещающий только именованные операции без generic CRUD, обязан определить и проверить собственный пользовательский путь. Новая кнопка или сервисная обвязка не могут считаться существующими по наличию backend API.

## 4. Г-серия: какие старые «открыто» устарели

| Пункт | Подтверждение | Текущая граница |
| --- | --- | --- |
| Г0, семантика | ADR-0015, row/group key и winner order; права документа и регистра независимы. | Принято. Полевые права отменены ADR-0018 и не возвращаются через этот план. |
| Г1, RecorderRef/catalog | [RegisterStartupValidatorTest](../../platform-register/src/test/java/org/ipro/register/RegisterStartupValidatorTest.java): duplicate code и non-DOCUMENT recorder **уже есть и прошли сейчас**. JPA-тест различает два типа с одинаковым числовым ID; registry публикует immutable snapshot после полной проверки. | Исполнимый каталог реализован. Старое утверждение об отсутствии этих тестов снято. Проверка сохранённых ссылок при удалении/переименовании type code — отдельный эксплуатационный остаток, не покрытый startup-проверкой деклараций. |
| Г2, четыре модели/descriptor/read/guard | [InformationRegisterRecorderPersistenceTest](../../platform-register/src/test/java/org/ipro/register/InformationRegisterRecorderPersistenceTest.java), `RegisterDescriptorTest`, `RegisterReaderTest`: все четыре JPA-модели, row constraints, generic-write guard, temporal отказ у непериодического режима. Свежий backend gate зелёный. | Реализовано. Canonical HISTORY/DETAIL остаётся границей чтения; общего разрешения писать подчинённую строку не появилось. |
| Г3, writer/locks/version/order | `RecorderSetWriter`, clock/transaction executor; H2 owner и исторические PostgreSQL forced-race/rollback сценарии. | Реализовано. Новый PG-прогон не заявляется. |
| Г4, document save/delete coordination | [Г4 evidence](evidence/register-g4-tests-20261005.md), [пилот](accounting-object-pilot-iteration1-evidence.md), S5(б)/ADR-0026. `AccountingObjectPilotIT` содержит regenerate/cleanup/denial/stale/rollback проверки. | Реализовано и ранее принято в заявленном scope. Payload-scope и guards принадлежат платформе, повторный прикладной ThreadLocal не нужен. |
| Г5, общий winner | `RegisterSliceReader`, CTE generator; Г5/Р9 evidence. Winner: period → recordingOrder → lineNumber для периодического recorded-регистра. | Реализовано. Сегодняшний CTE application probe красный до query из-за catalog sources; это реальный остаток регрессии. |
| Г6, подписи/UI/отчёты | Свежие resolver tests подтверждают chunking, одинаковую маску missing/denied, отсутствие повторного использования подписи между субъектами, документ без формы. Пилот 2026-10-07 и Р8 покрыли реальный список и read-only actions. | Старое «подпись не подключена, пилота нет» устарело. Отдельный поздний UX-отзыв остаётся открытым. Старый UDR Preview/PDF gate снят вместе с потребителем; сохранённые query slices относятся к текущему query-контракту и его регрессии. |
| Г7, пилот/миграции/эксплуатация | Пилот с двумя реальными прикладными DOCUMENT-типами реализован, [пользователь оценил его положительно](information-register-accounting-object-pilot.md). Есть явные SQL подготовки отдельной базы и запуск validate. | Пилот закрыт в объёме итерации 1. Полная production-приёмка (orphan/type-code изменения, EXPLAIN point/slice/set, план отката/backfill существующих данных) общим evidence не подтверждена. Не подменять ей готовность песочницы. |
| Г8, AppDev/supply/final acceptance | ADR-0024/0025/0026, S0–S5, module-owned API/surface/tests и стартеры реализованы. | **Частично:** внешний consumer, актуальный app/общий gate и итоговый эксплуатационный scope не закрыты. Нельзя писать «Г0–Г8 полностью завершены». |

Пункт Г6 про сохранение наносекунд заменён принятым 2026-10-07 контрактом: `DateTimePicker` с шагом 1 ms; EXACT усекает до миллисекунд. Текущие `FieldFactory` и `RegisterPeriodicityTest` этому соответствуют. Возвращение прежнего nano-editor не является остатком.

## 5. S-серия и итоговая приёмка выноса

| Пункт | Результат сверки |
| --- | --- |
| S0–S3 | Контракт, readers/registry, key operations, recorder-set/clock/configuration находятся в `platform-register`. Backend не перенесён обратно в приложение; sources/tests принадлежат модулю. |
| S4 | Пять UI-классов и extension point находятся в `platform-register-vaadin`; свежие 24 tests, включая default/override/missing-backend и surface/composition. |
| S5(а) | Имя берётся из `RegisterMetadata.displayName`, `RegisterNamedOperations` собирает подписи. Приложению остаётся один предметный адаптер каталога операций. |
| S5(б) | `AggregateCommandPayload` и ADR-0026 заменили `AccountingObjectSaveScope`; условия допустимости предметной команды остались у приложения. |
| S5(в) | Tests/fixtures движка у владельца; свежие backend 124 подтверждают их сборку без `org.ip`-моделей. Исторический PostgreSQL module-gate 48/48 сохранён. |
| §6.1–2, негативная поставка/регистрации | Зафиксированы S1/S4/S5(а) evidence и module composition/extension checks. Старое «1 прикладной registration-файл» заменено достигнутым **0** для регистра. |
| §6.3, внешний consumer | **Открыто.** В `examples/platform-consumer` и `examples/entity-types-consumer` нет собственной сущности с `RegisterMetadata`. Тесты внутри модуля и consumer обычных Standard*-типов не заменяют этот пункт. |
| §6.4–5, полный текущий gate/random-order | **Открыто.** Зелёный общий A1 от 2026-10-26 не доказывает текущее дерево; baseline 2026-10-27 красный, сегодняшний probe подтверждает один из блокеров. Отдельный актуальный random-order результат с seed не найден. |
| §6.6, evidence | Документы срезов существуют; ссылки на старые кодовые пути в активном плане регистратора исправлены на владельцев модулей. Исторические логи сохраняют исходный смысл. |

## 6. Действительные остатки и порядок закрытия

| ID | Что закрыть | Критерий | Отношение к G1 |
| --- | --- | --- | --- |
| **REG-A1** | Catalog sources у старых query/test consumers после AppDev First | Сегодняшние 5 ошибок устранены передачей validated catalog; затем зелёные затронутые register/query/security/application suites. Проверить остальные места короткого constructor, а не только один тест. | Первая работа для восстановления доверенной сквозной регистровой приёмки. Не блокирует разработку GenerationPlan справочника. |
| **REG-A2** | Внешний регистровый consumer | Отдельный binary-only consumer объявляет собственный регистр, стартует без ручной регистрации инфраструктуры, читает и выполняет одну разрешённую запись; generic/denied путь отвергается. Для заявления о recorded-рецепте — отдельный пример document + set. Backend-вариант не требует UI/REST. | До признания регистрового рецепта пригодным для внешних приложений. Может стать основой будущего generated-consumer gate. |
| **REG-A3** | Актуальный выпускной gate | После A1 и разбора других известных общих ошибок: актуальный согласованный app/architecture/API набор и random-order с seed, явный PostgreSQL scope. Пропуски и чужой API drift не объявлять успехом. | Перед заявлением о готовности выпуска; не условие начала написания генератора. |
| **REG-D1** | Register facts → design snapshot | Проекция validated registry: fields/roles, periodicity, row/group keys, recorder codes, allowed operations. Без второго scanner, business read и core → register зависимости. | Новая работа design/G1, после принятого runtime-контракта. |
| **REG-D2** | Draft/recipe/operation profile | Типизированный draft/JSON/validator/UI; выбран поддержанный generic либо named write path; миграция и compile/startup/persistence/behavior сгенерированного кода. | Сам G1 регистра. Наличие API не снимает этот gate. |
| **REG-U1** | Поздняя UX-приёмка Р8 | Явный отзыв пользователя на стандартный список, slice state и key-change текущей итерации. Проба пилота от 07.10 остаётся принятой отдельно. | Закрытие UX-итерации/редактора, не prerequisite ядра генератора. |
| **REG-O1** | Эксплуатационный recorded-register профиль | Правила смены/удаления stable codes, диагностика orphan refs, выбранные физические индексы/EXPLAIN и rollback/migration существующих данных на целевой БД. | При вводе в эксплуатацию и изменении существующих регистров/регистраторов; для первой генерации новых таблиц достаточно своего ограниченного migration gate. |

Очередь отдельного закрытия регистровой приёмки: REG-A1 → REG-A2 → REG-A3, затем новая интеграция REG-D1/D2. По последующему решению пользователя этот пакет **отложен**, а ближайшей работой стал минимальный G1 справочника. Возвращать конкретный остаток в текущий scope только при доказанной зависимости выбранного сценария.

## 7. Что не включать в блокеры первого G1

- Второй production-регистр как условие semantic kind: условие пересмотрено ADR-0029. Новые предметные сценарии не объявляются реализованными, но не возвращают тип в PLAIN.
- Полевые права, nano-editor и удалённый UDR-отчёт цен: соответственно отменённый контракт, заменённый контракт и снятый потребитель.
- `setAll`, commandId/outbox, новый SERIALIZABLE-профиль, REST, другие DB backend, first slice/интервалы, кеширование: отдельные расширения с собственным потребителем и gate.
- Posting/накопление, произвольный backfill старых данных, IDE и одновременное проектирование нескольких новых связанных root: отдельные этапы.

Аудит меняет статусы и очередность в планах, но не закрывает перечисленные REG-A*/D*/U*/O* составлением документа. Реализация Java/SQL и пользовательские права этим пакетом не изменены.
