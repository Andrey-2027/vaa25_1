# S5(б) по существу: payload команды доходит до lifecycle-хуков механизмом платформы

Дата: 2026-10-16. Область: [ADR-0026](../decisions/ADR-0026-command-payload-in-lifecycle.md) —
граница lifecycle-контракта: как позиции документа попадают в `beforeSave`/`onSave` его сущности.
Срез [плана §5, S5(б)](../information-register-extraction-plan.md) — последний прикладной долг S5.

## Что изменено

| Файл | Изменение |
| --- | --- |
| `platform-core/.../crud/CallbackScope.java` | **Новый тип (INTERNAL)**: одна дисциплина callback-scope'ов crud-пути — поток-владелец, закрытие в порядке открытия, вложенность стеком; регистрация — последнее действие конструктора наследника (`this` в вызов конструктора базового класса Java не пускает), пропустить её нельзя: незарегистрированный scope отказывает и на закрытии, и при чтении предыдущего |
| `platform-core/.../crud/AggregateSaveLifecycleScope.java` | Переведён на общую дисциплину: то же свидетельство callback'а владельца, тот же текст ошибки о порядке закрытия, но ThreadLocal и стек — из одного места |
| `platform-core/.../crud/AggregateCommandPayload.java` | **Новый тип (APP_API)**: объявление payload'а командой (`open` → handle только с `close()`), чтение хуком (`of(context)`), типизированный `section(Class)`; `sections()` и строки read-only; просматривается вся стопка объявлений, поэтому вложенная операция не отменяет внешнюю |
| `platform-core/.../crud/MetadataDrivenAggregateSaveService.java` | Платформенный шаг `bindManagedCopy(aggregate, saved)` сразу после сохранения корня: `merge` на обновлении возвращает управляемую копию, и её видит `onSave` |
| `src/main/java/org/ip/application/register/AccountingObjectPositions.java` | **Новый прикладной помощник**: вето `require(context, itemType)` («только командой, объявившей позиции») и тип позиции по типу документа |
| `AccountingObjectDocumentLifecycles.java` | `beforeSave` берёт вето из payload'а вместо прикладного scope'а |
| `AccountingObjectSaveService.java` | Команда объявляет позиции один раз: `AggregateCommandPayload.open(document, List.of(new AggregateSection(itemClass, positions)))` вокруг `aggregateSave.save(...)` |
| `AccountingObjectRegisterGeneration.java` | `onSave` читает позиции из payload'а; ни `id`, ни Hibernate-класс не сравниваются |
| `AccountingObjectSaveScope.java`, `AccountingObjectContribution.java` | **Удалены** (`git rm`): ThreadLocal с эвристиками и дублирующий носитель `Line(nomenclatureId, accountingTypeId)` |

Перезаписано намеренно, срезом: `src/test/resources/platform-core-surface.txt` (109 → 111 типов,
APP_API 34 → 35, INTERNAL 16 → 17), списки reviewed-типов в `PlatformCoreModuleTest` (приложение) и
`CoreModuleCompositionTest` (модуль), `src/test/resources/platform-core.api` (новый тип, вложенный
`Declaration`, и `AggregateSaveLifecycleScope`, который теперь наследует механизм), D1-реестр
`src/test/resources/platform-public-surface.txt` (+`AggregateCommandPayload`, +`AggregateSection`;
−устаревшая запись SPI `AggregateSaveContribution`, которую приложение больше не называет),
`docs/architecture/d1-surface-measurements.md` и fingerprint `platform-core` в
`scripts/local-dependencies.json` (150 → 153 файла).

## Измерения

| # | Что измерено | Значение |
| --- | --- | --- |
| M1 | механизм целиком | `platform-core` **198 тестов, 0 отказов**: 8 из них — новые `AggregateCommandPayloadTest` ([протокол](../../plans/register-s5b-core-20261016.log)) |
| M2 | тождество | payload виден владельцу и связанной платформой копии; тот же тип, другой экземпляр — пусто; чужое объявление копию не принимает |
| M3 | истечение и поток | после `close` — пусто; на другом потоке — пусто; после исключения в операции объявление на потоке не остаётся |
| M4 | вложенность и порядок | внутреннее объявление перекрывает своё, внешнее продолжает обслуживать свою сущность; закрытие не в порядке открытия → `IllegalStateException` |
| M5 | неизменяемость | `sections()` и строки секции read-only; объявленный тип строки, не совпавший со строкой, отказывает громко (`ClassCastException`), а не сужает набор молча |
| M6 | приложение | `AccountingObjectPilotIT` **29/29**: вето, установка, корректировка, удаление документов, повторная установка, конкурентность, права — всё на новом пути ([протокол](../../plans/register-s5b-app-pilot-20261016.log)) |
| M7 | соседние пути пилота | `MetadataDrivenAggregateTelemetryTest` 3/3, `RegisterBatchReadIT` 6/6, `RegisterListFormToolbarTest` 4/4, `PilotFixtureWriteBoundaryIT` 3/3, `NomenclaturePriceCteSpikeTest` 8/8 — 24/24 |
| M8 | downstream-модули | `platform-register` 175/0 (52 пропуска — PostgreSQL-IT), `platform-register-vaadin` 24/24 ([протокол](../../plans/register-s5b-downstream-20261016.log)) |
| M9 | гейты поверхности | `PlatformCoreModuleTest` 6/6, `PlatformPackageDependencyMatrixTest` 3/3, `PlatformCoreSurfaceTest` 5/6 и `PlatformPublicSurfaceTest` 6/10 — красноты названы ниже ([протокол](../../plans/register-s5b-gates-20261016.log)) |
| M10 | замеры D1 среза | `app-source-files` 273 → **272** (−`Scope`, −`Contribution`, +`Positions`), `platform-artifact-files` 579 → **581** (+2 типа), `named-platform-types` 312 → **313** (+`AggregateCommandPayload`), `api-types` 177 → **179** (+`AggregateCommandPayload`, +`AggregateSection`), `spi-types` 33 → **32** (запись снята вместе с прикладной реализацией) |

Положительные протоколы: [core](../../plans/register-s5b-core-20261016.log),
[pilot](../../plans/register-s5b-app-pilot-20261016.log),
[downstream](../../plans/register-s5b-downstream-20261016.log),
[gates](../../plans/register-s5b-gates-20261016.log),
[gates, повторный прогон после сверки манифеста](../../plans/register-s5b-gates-r2-20261016.log).

## Сверка манифеста после среза (что перезаписано, а что осталось названным)

Красноту манифеста проверяли не чтением, а записью: прогон с `-Dplatform.manifest.write=true`
показал ровно четыре разошедшиеся записи — `platform-core` (свой срез), `platform-register`
(S5(а)/(в)), `platform-rls` и `platform-register-vaadin`. Первая перезаписана срезом; вторая уже
была перезаписана своим заходом; две чужие **откачены обратно** (`platform-rls` — 61 файл,
`platform-register-vaadin` — `9c5c121e…`), потому что запись чужого дрейфа — это одобрение чужого
изменения, а не побочный эффект этого среза. Повторный прогон
([протокол](../../plans/register-s5b-gates-r2-20261016.log)) подтвердил то же состояние, что и
первый: 38 тестов, 8 отказов в пяти наборах, и все они — названные ниже. Ни один отказ не
указывает на файл этого среза; реестры поверхностей правились только в строках самого среза
(`AggregateCommandPayload`, `CallbackScope`, `AggregateSection`, `RegisterNamedOperations` и снятые
записи S5(а)), поэтому `unidr`/`reportstudio`/`filtergrid`/`platform-rls`/`platform-navigation` в
красноте — это состояние `HEAD`, а не следствие этого изменения.

## Негативный контроль: «вне команды — отказ»

Вето S5(б) не ослаблено, и это проверено тем же тестом, что и до среза:
`AccountingObjectPilotIT.genericHeaderAndRegisterWritesAreClosed` подаёт прямой
`aggregateSave.save(document, секция с позициями, EventSource.UI)` — то есть сохранение, где позиции
**есть**, но команды нет, — и ждёт отказа с текстом про команду. Ветка «вывести payload из секций
операции» была отклонена именно этим измерением (ADR-0026, вариант 2): она сделала бы вето
непроверяемым («есть позиции» вместо «внутри команды»). Тест зелёный в M6.

## Замер сокращения прикладного кода

| Что | До | После |
| --- | --- | --- |
| Классов пилота (команда + lifecycle + генерация) | 5 | **4** — удалены `AccountingObjectSaveScope` и `AccountingObjectContribution`, добавлен `AccountingObjectPositions` |
| Собственных ThreadLocal'ов приложения | 1 | **0** |
| Эвристик по `id` и Hibernate-классу | 1 | **0** (копию связывает платформа) |
| Строк кода без комментариев и пустых | 216 | 222 |

Строк стало на шесть больше: объявленный payload и вето теперь называются явно (тип позиции по типу
документа, `require`), тогда как прежде это было размазано между scope'ом и генерацией строк. Срез
сокращает не текст, а механизм: у приложения не осталось ни своего средства донести данные, ни
догадки о том, какой экземпляр ему вернёт `merge`.

## Названная краснота (не этот срез)

Проверено тем, что обвиняемые файлы не изменены этим заходом (`git status` их не показывает) — то
есть та же краснота есть и на `HEAD` без этого среза:

| Гейт | Что красно | Причина |
| --- | --- | --- |
| `PlatformPublicSurfaceTest` (4 из 10) | wildcard-импорты `org.ipro.lifecycle.*`/`org.ipro.rls.*`; FQ-ссылки в 10 файлах; `UniDrQuerySourceService` на `EntityRef`; 39 типов вне реестра | файлы P-серии «Пользователи и права» и `unidr`, вне S5(б) |
| `PlatformCoreSurfaceTest` (1 из 6) | `applicationTestReferencesToInternalOrModuleTypesAreFrozenPerFile`: `UniDrReceivingItemsQueryTest`, `UniDrQuerySmokeApplication` | тесты `unidr`, вне среза |
| `PlatformApiBaselineTest` (1 из 4) | `platform-rls`: `RlsHibernateWriteGuardInstaller` против `RlsGrantMatching` | чужой дрейф, названный тем же способом в [S5(а)](register-s5a-operation-titles-20261016.md) |
| `WorkspaceManifestTest` (1 из 4) | fingerprint `platform-rls` (61 → 62 файла) и `platform-register-vaadin` (тот же fileCount, другая сумма) | запись не перезаписана намеренно: она либо остаётся чужой, либо одобряет чужое изменение. Дрейф назван измерением, а не догадкой: с временно снятой собственной правкой S5(а) в `RegisterKeyDialogTest` (там 3 строки диффа — имя регистра в фикстуре) фактический v2 модуля интерфейса — `4d0c31b7…` против записанного `9c5c121e…`, то есть запись разошлась с содержимым **до** этого среза. Остальные записи после S5(а)/(в)/(б) сходятся: `platform-core` 153, `platform-register` 71, оба стартера — перезаписаны своими срезами, и повторный прогон их в красноте не называет |
| `PlatformStarterCompositionTest` (1 из 5) | прямая зависимость приложения `org.ipro:platform-navigation` в `pom.xml` | `pom.xml` не изменён этим заходом (стоит в HEAD) — чужая тема |

## Ограничения

- **PostgreSQL-гейты не запускались**: `scripts/register-*-postgresql.ps1` и ветка `Concurrency`
  требуют одноразовой базы; в модульных прогонах это 52 пропуска, не отказы.
- Не запускались: полный `mvn verify`, random-order прогон (§6.5), `platform-rls`-baseline.
- **`CanonicalSaveLifecycleScope` не перебазирован** на общую реализацию: он живёт в `org.ipro.data`,
  а база — package-private механизм `org.ipro.crud`. Это названная граница ADR-0026 §3, а не забытая
  правка.
- **Payload не покрывает** ключевой путь (`RegisterKeyWrites`/`RegisterKeyChanges`), `afterCommit`
  (scope закрыт к моменту commit) и хуки строк регистра (тождество сверяется с владельцем
  объявления) — те же границы названы в ADR-0026.
- **Изменение публичной подписи**: у `AggregateSaveLifecycleScope` `close()` теперь наследуется от
  package-private базы, поэтому извне `org.ipro.crud` он недостижим (baseline это показал). Открытие
  и прежде было package-private: ни один поддержанный потребитель ничего не теряет — снаружи
  используются только `tokenFor`/`requiresVersionAdvance`, и это проверено сборкой
  `platform-register` (M8).
