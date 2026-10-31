# Продолжение G1: документ, ссылки, владелец и табличные части

Дата актуализации: 2026-10-31. Статус: **E0 выполнен; E1 «Документ», E2 «Ссылки», E3 «Владелец», функциональный/компонентный E4 «Табчасти» и E5.H приняты; E5.B и M открыты**.

**Baseline 2026-10-31.** E5.H (согласованная сборка и общий автоматический прогон)
выполнен. E5.B (браузерная приёмка) и M (migration gate) остаются открытыми.
M выполняется только после
E1–E5 по решению пользователя и не заменяет их. Текущий extension заканчивается
на E5/M; он не включает регистр сведений.

**Следующий этап — отдельный register-этап, не часть этого extension:** сначала
`independent-register-v1`, затем `recorded-register-v1`. REG-D1 (register facts →
design snapshot) и REG-D2 (draft/recipe/operation profile) в текущем G1-extension
не реализуются; их наличие и приёмка не объявляются. Переход к этому этапу не
делает E5.B или M закрытыми.

Сверка готовности регистра и подробный backlog следующего расширения на 31.10:
[подготовка Регистра сведений к G1](information-register-g1-preparation-plan.md).

Контрактный baseline E0: [API, версии, manifest C/D v2, owner/JSON и распределение файлов](../architecture/entity-generation-g1-extension-contracts.md).
Проверки E0: [85 tests, отчёты и границы результата](../architecture/evidence/entity-generation-g1-extension-20261029/e0/README.md).

Уточнения по замечаниям внесены 2026-10-29 в этот план и нормативные контракты E0.
Они определяют будущую реализацию; прежний результат 85 tests не является проверкой
новых owner/C/D правил. E1 принят отдельным повторным прогоном;
[E2 проверен отдельно](../architecture/evidence/entity-generation-g1-extension-20261029/e2/README.md).
[E3 проверен полным маршрутом](../architecture/evidence/entity-generation-g1-extension-20261029/e3-c/README.md).
[E4.A проверен actual комплектами 0/1/2 секций обоих видов и owner + row reference](../architecture/evidence/entity-generation-g1-extension-20261029/e4-a/README.md).
[E4.B завершён на actual P8 downloads и стандартных формах с H2](../architecture/evidence/entity-generation-g1-extension-20261029/e4-b-completion/README.md).
[D-проверки трёхфайлового комплекта дополнены и приняты: 87/87 selftests](../architecture/evidence/entity-generation-g1-extension-20261029/e4-d-completion/README.md).
Функциональный/компонентный E4 закрыт;
[E5.H принят на согласованном artifact baseline и 15 actual routes](../architecture/evidence/entity-generation-g1-extension-20261029/e5-h/README.md).
Браузерная матрица E5.B и migration M остаются отдельно открытыми.

Основание: [минимальный G1](entity-generation-g1-minimum-plan.md), успешный
[сценарий «Матрица»](../architecture/evidence/entity-generation-matrix-20261029/README.md)
и решение владельца проекта продолжить функциональный этап, а migration gate выполнить **после него**.
Уточнение пользователя: владелец — другого типа. Пример в этом плане:
**Справочник договоров → Справочник контрагентов**; child и owner имеют kind=CATALOG.
Иерархический `parent` того же типа в этот план не входит.

## 1. Режим, результат и границы

- **Цель:** получить полный путь P8 → проверенный Java → C (compile/runtime) → D
  (применение файлов) для справочника и документа со ссылками и табчастями;
  для справочника дополнительно поддержать одного владельца существующего типа.
- **Режим:** завершённый механизм в указанном ограниченном scope и изолированном
  тестовом окружении. Это не объявление готовности миграций или deployment.
- **Предыдущий результат:** G1.1/G1.2 и файловый integration gate G1.3 подтверждены
  «Матрицей»; браузерная приёмка и migration gate этим результатом не закрыты.
- **Потребитель:** разработчик прикладных сущностей через существующий редактор P8.
- **Рабочий сценарий:** создать/импортировать draft → задать поля, ссылки, владельца,
  секции → проверить и просмотреть весь комплект Java → получить файлы → прогнать C →
  применить D в изолированный consumer → создать, изменить и повторно открыть данные.
- **Входит:** четыре последовательных функциональных среза E1–E4, необходимые общие
  контракты E0, итоговая интеграция и браузерная приёмка E5.
- **Окружение:** существующие platform JAR, изолированный Spring Boot/H2 consumer,
  отдельные output/build directories; generated-сущности не устанавливаются в основное приложение.
- **Ограничения:** CREATE новых исходников, существующие допустимые Long-ID reference
  targets, стандартные persistence/numbering defaults; без конструктора всех видов сущностей.
- **Гарантии:** отсутствие молчаливой потери ввода; свежесть draft/context/плана;
  проверка именно выданных файлов; сохранность чужих и изменённых файлов; стандартные
  серверные проверки доступа, ссылок и агрегатного сохранения.
- **Завершение:** E1–E5 приняты по матрице §12; отдельно обозначены результаты core,
  C, D, компонентных и браузерных проверок. Непройденная часть не заменяется другой.
- **Практическая проба:** воспроизводимый launcher, тестовая учётка и grants, адрес P8
  и форм изолированного приложения, fixtures, команда C/D и каталог evidence.
- **Обратная связь:** достаточны ли стандартные формы и выбранные ограничения owner/sections.
  Новые пожелания не расширяют текущую приёмку автоматически.
- **Следующий пакет:** M — отдельный migration gate после E5. Перехода от пилота
  к иному режиму в этом плане нет; критерий текущего ограниченного механизма задан выше.

**Принятый порядок функциональной приёмки:** E1 Документ → E2 Ссылки → E3 Владелец →
E4 Табчасти → E5 Общий прогон → M Миграции. Подготовительные работы C/D и runtime
могут идти параллельно раньше своих функциональных gates, как указано ниже.

## 2. Сразу о UI и параллельной работе

Здесь различаются три категории:

1. **Без UI:** не меняет P8/Vaadin/form code, не открывает браузер; проверяется core,
   metadata, canonical persistence, CLI или filesystem tests.
2. **UI-код без браузера:** меняет редактор или стандартные формы; проверяется
   session/component tests. Такой пакет нельзя называть работой без UI.
3. **Браузер:** запуск приложения и пользовательская проверка реальных форм.

| Пакет | Работа с UI | Когда можно выполнять параллельно | Что требуется для окончательной приёмки |
| --- | --- | --- | --- |
| E0 — контракты и совместимость | Нет | Короткий общий старт; изменения shared DTO согласованно, одним потоком | Зафиксированные API, версии, fixtures и границы файлов |
| E1.A — документный draft/validator/projection/generator | Нет | С E1.C, E1.D и E1.B после E0; UI ждёт документный DTO/API | Реальный Java из builder, core/JSON tests |
| E1.C — документный compile/runtime harness, C2 для одного entry | Нет | После E0; инструмент можно готовить на ручной контрольной fixture, вместе с C v2 foundation | Generated-acceptance только на результате E1.A; legacy C1 остаётся отдельным |
| E1.D — legacy D1 и однофайловый extension D2 | Нет | После E0, независимо от P8; с D v2 foundation, до E1.A/C — только selftest | Настоящий план E1.A + matching C2 + current dependency locations |
| E1.B — P8 для документа | UI-код, без обязательного браузера | После документного model/API; одновременно с C/D | Реальный builder и компонентная интеграция |
| E2.A — генерация ссылок и перенос фактов targets | Нет | После E1.A; одновременно с E2.C и адаптацией E2.B | Core/JSON/runtime integration |
| E2.C — C/D сценарии ссылок | Нет | Specs и target fixtures готовятся после E0; actual acceptance после E2.A | Сгенерированные catalog/document со ссылками |
| E2.B — P8/стандартные формы ссылок | UI-код | После reference API; с E2.C | Реальные lookup и публикации |
| E3.R — runtime-контракт владельца | Нет | После owner-контракта E0 и фиксации правил ссылки; может идти во время E1/E2 | Серверные metadata/persistence/access tests |
| E3.A — owner в draft/JSON/generation/projection | Нет | После E2.A и публичного API E3.R; с E3.C/E3.B | Реальная декларация владельца и согласованный fingerprint |
| E3.C — owner acceptance | Нет | Specs после E0; runtime tests с E3.R, generated-acceptance после E3.A | Независимые ожидания и actual source |
| E3.B — P8 и формы подчинённого справочника | UI-код | После owner DTO/runtime API; с E3.A/C | Серверный guard + формы; одного readonly недостаточно |
| E4.C0 — N-file расширение C2 | Нет | После manifest-контракта E0; с E1/E2/E3 и E4.D0; однофайловый foundation нужен E1.C | N-file generated-acceptance после E4.A; однофайловый C2 принимается уже в E1 |
| E4.D0 — N-file D2, journal/rollback | Нет | После E0; одновременно с C и UI; однофайловый foundation нужен E1.D | N-file selftest до E4.A, actual integration после E4.A/C; формат D2 не меняется |
| E4.A — генерация root + rows | Нет | После E1/E2 и фиксации owner-модели E3.A; с E4.B/C | Комплект из реального builder и aggregate runtime |
| E4.B — P8 preview/download комплекта, формы секций | UI-код | После manifest/model API; компоненты можно делать во время E3 | Согласованный полный комплект, actual metadata |
| E5.H — общий автоматический прогон | Нет | Отдельные сценарии можно запускать независимо в изолированных каталогах | Все реальные producers/consumers собраны одной версией |
| E5.B — браузерная приёмка | Браузер | После сборки соответствующего среза; один активный прогон на одно окружение | Работающий изолированный стенд и тестовые grants |
| M — migration gate | Нет | **Только после большого этапа E1–E5**, по решению пользователя | Отдельный последующий план |

**Наиболее независимые задачи без UI:** E1.C, E1.D, E3.R, E4.C0, E4.D0.
Их можно передавать отдельными заданиями после E0, не ожидая готовности P8.
Для E3.R дополнительно фиксируется reference-политика; если её ещё нет, готовятся
только descriptor/API и негативные metadata fixtures. Остальные backend-задачи также
не требуют UI, но меняют общие model/generator files и должны идти последовательно
либо строго в согласованных разных файлах.

Отсутствие UI-зависимости не означает отсутствие зависимостей от model, platform JAR
или настоящего Java. Ручной контрольный класс достаточен для selftest инструмента,
но не заменяет функциональную приёмку генератора.

## 3. Исходная техническая база (до E0/E1.A/B)

| Область | Сейчас | Что требуется расширить |
| --- | --- | --- |
| Recipe | `standard-catalog-v2@2`; документ `HANDOFF_ONLY` | Явно подключить документный draft/генерацию, не включать остальные HANDOFF_ONLY |
| Наследуемые поля | E0 опубликовал recipe-aware API; validator/projection/P8 ещё используют справочные правила | Подключить готовый API в consumers; no-arg API сохраняет `code/name` |
| Validator | Root резервирует `code/name`; references и sections уже описаны | Документные поля и owner; не дублировать semantic validator в builder |
| Generator | E0 опубликовал `EntityJavaGenerator`, пока делегирующий legacy catalog builder | Подключить документ и versioned extension profiles, REFERENCE, owner, root + rows |
| Draft/JSON | Immutable core; отдельный `entity-design-json`, строгий envelope | Документ; versioned расширение owner без переосмысления старого JSON |
| Runtime facts | `entity-design-runtime`, `RuntimeDesignSnapshot`, catalog adapter | Нужные факты документа/owner и target eligibility |
| P8 | Справочные подписи и builtin navigation; поля/секции/ссылки уже редактируются | Recipe selection, документные builtins, owner, комплект исходников |
| C | Один source; spec/test ожидают `StandardCatalogEntity` | Два base contracts, target fixtures, комплект source files |
| D | Один catalog CREATE; контроль через `CatalogJavaGenerator`; summary/ledger v1 | Routing двух рецептов, затем complete file set и state v2 |
| Sections runtime | Metadata-driven service и aggregate save уже существуют | Генерировать их стандартные декларации и проверить на actual rows |
| Root owner | Явного контракта нет; ownership строки уже существует | Отдельная capability подчинённого справочника, не выводимая из имени поля |

Основные точки изменений: `entity-design-core`, `entity-design-json`,
`entity-design-runtime`, `src/main/java/org/ip/views/admin/`,
`scripts/entity-generation-g1/`, `scripts/entity-generation-g1-apply/`.
Owner runtime затрагивает `platform-contracts`, `platform-core` и проекцию metadata;
формы — `platform-vaadin`. Прикладные handwritten модели основного приложения
не переводятся на новый контракт в рамках этого этапа.

## 4. E0 — общие контракты до разделения работ

| Шаг | Работа | Выход / проверка |
| --- | --- | --- |
| E0.1 | Зафиксировать recipe-specific inherited fields: catalog `code/name`, document `number/date`; baseType, default publications, numbering role | Таблица решений и contract tests; старый no-arg inherited API сохраняет справочный смысл |
| E0.2 | Зафиксировать единый вход генерации с нынешними аргументами `CheckedStructure`, current draft/context и прежним result | Dispatcher выбирает текущий профиль recipe; D rebuild использует whitelist recipe/generatorVersion, включая фиксированный legacy handler |
| E0.3 | Зафиксировать границы JSON/DTO, owner capability, переноса facts и fingerprint | Новые поля не теряются; old fixtures/constructors проверены; нет `Class`, IO или Vaadin в core |
| E0.4 | Зафиксировать manifest C/D v2 и state v2 по §10 | Fixtures valid/missing/extra/hash mismatch; один contract для C и D, без двух конкурирующих форматов |
| E0.5 | Утвердить независимые scenario specs §12, ownership файлов и output paths | Каждый поток получает вход, допустимые изменения, команду selftest и условия передачи интегратору |

Не строить абстрактный registry всех будущих видов. Достаточно поддержки двух точных
recipe и небольшого общего механизма dispatch/inherited metadata. Зафиксированная
сигнатура позволяет начать C/D и UI до реализации каждого builder.

Изменения recipe/generator/fingerprint версионируются явно. Не менять задним числом
смысл `standard-catalog-v1@1` и его `create()`/JSON. Подключение документа использует
существующий ref `standard-document-v1@1`, чей draft-контракт сейчас ещё не реализован.
Envelope v1 можно сохранить для документа без новых структурных полей; owner требует
нового envelope, см. §8. Новые версии manifest/ledger независимы от версии draft.

**Gate E0:** опубликованы API/fixtures и распределение общих файлов; это короткий
подготовительный пакет, не отдельная программа улучшения генератора.

### 4.1. Версии и момент подключения v2

Нормативная полная матрица `recipe × slice → generator/fingerprint/manifest/C/D/envelope`
находится в **E0.3.1** [контрактов E0](../architecture/entity-generation-g1-extension-contracts.md).
Краткая привязка работ:

| Срез | Текущий catalog extension | Текущий document | Путь extension C/D |
| --- | --- | --- | --- |
| E1 | Ещё отсутствует; legacy `catalog-java-v1` сохранён | `document-java-v1` | F2 / M2 / C2 / D2, один файл |
| E2 | `catalog-java-v2` | `document-java-v2` | F2 / M2 / C2 / D2, один файл |
| E3 | `catalog-java-v3` | `document-java-v3`, envelope v2 только с owner=null | F2 / M2 / C2 / D2, один файл |
| E4–E5 | `catalog-java-v4` | `document-java-v4` | F2 / M2 / C2 / D2, root + N rows |

F2 = `plan-fingerprint-v2`, M2 = `g1-generation-manifest-v2`,
C2 = `g1-c-summary-v2`, D2 = receipt/journal `g1-apply-*-v2`.
Legacy catalog сохраняет `catalog-java-v1` / F1 / C1 / D1; документ не подключается
к C1/D1. Однофайловый foundation v2 реализуется уже в E1.C/D и разделяется с E4.C0/D0;
готовность N-file apply не требуется для E1, его schema/API при этом уже зафиксированы.

Текущий extension generator выбирается сборкой по recipe, не наличием сложных полей
и не скрытым параметром «этап». Legacy имеет фиксированный отдельный вход.
Предыдущие extension profiles вне whitelist текущей сборки отклоняются до CREATE/NOOP.
Версия, изменяющая output/scope, получает новый planId и приёмку в чистом project root.

### 4.2. Общие правила диагностики

Использовать коды и точные Targets из **E0.3.4** контрактов. Для owner fieldId=null,
propertyKey=`owner` или `owner.targetTypeId/fieldName/columnName/label`; UI ведёт в
отдельную панель, не ищет искусственный UUID. Для root/row references остаётся
fieldId; у row дополнительно sectionId. Семантические ошибки остаются в DraftValidator;
новый semantic код — `OWNER_UNSUPPORTED_RECIPE`, пять GenerationDiagnostic.Code переиспользуются.

Все builders отклоняют `serverRequired=true + uiRequired=OPTIONAL` кодом
`UNREPRESENTABLE_REQUIRED_MODE` до source output, включая document и fields строк.
Warning validator `UI_REQUIRED_MISMATCH` не заменяет этот rejection. Required owner
имеет фиксированный согласованный режим и не получает пользовательский OPTIONAL.

## 5. E1 — документ без табличных частей

E1.A/B: реализованы document draft/validator/JSON, scalar root generation и P8.
Результаты и команды: [evidence E1.A/B](../architecture/evidence/entity-generation-g1-extension-20261029/e1-ab/README.md).
E1.C/D: C2/D2, actual EmptyDocument/ScalarDocument APPLIED→NOOP:
[evidence E1.C/D](../architecture/evidence/entity-generation-g1-extension-20261029/e1-cd/README.md).
Замечания review по identity/commit boundary/late target/skip/legacy C1 исправлены;
повторные прогоны и build/runtime сведены в
[review status](../architecture/evidence/entity-generation-g1-extension-20261029/e1-cd-review/README.md).
Gate E1 принят 2026-10-29 после независимой повторной проверки:
[приёмка E1: 156 tests, C/D selftests, actual applied runtime и legacy Matrix](../architecture/evidence/entity-generation-g1-extension-20261029/e1-acceptance/README.md).
Browser smoke по-прежнему относится к E5.

### E1.A. Модель и Java, без UI

1. Подключить документный recipe в DraftOperations и DraftValidator. Runtime
   prerequisites проверять для него столь же явно, как для catalog-v2;
   UNKNOWN не считать AVAILABLE.
2. Ввести recipe-aware inherited/reserved fields. Для документа зарезервировать
   `number/date` и BaseEntity fields; `code/name` не навязывать как документные builtins.
3. Протянуть recipe в FieldApplicationMapping, suggestions и выбор inherited rows.
   Document list/selection по умолчанию публикуют `number/date`, собственные поля —
   по пользовательским applications. Унаследованные поля не дублировать в Java.
4. Генерировать `extends StandardDocumentEntity`, `@Entity`, явную таблицу,
   EntityMetadata и те же семь scalar types/constraints. Kind выводится через AUTO.
5. Нумерацию брать из стандартной базы: `DOCUMENT_NUMBER`, dateField=`date`.
   Не добавлять собственную нумерацию, UNIQUE(number), проведение или регистраторов.
6. Протянуть документ через JSON import/export и runtime prerequisite projection.
   Сохранить UUID, raw input, dirty/freshness и адресную диагностику.
7. Проверить empty document, все scalar mappings, titles/labels/publications,
   required modes, экранирование, детерминизм и отказы на ещё неподдержанные конструкции.

### E1.C / E1.D. C и D, без UI

Реализовано 2026-10-29: C2 (`g1-c-summary-v2`) с manifest/dependencies/suites, D2
(`document-java-v1`, `.g1-apply-v2`) и actual EmptyDocument/ScalarDocument
APPLIED→NOOP. Evidence: [e1-cd](../architecture/evidence/entity-generation-g1-extension-20261029/e1-cd/README.md).

1. Отделить в ScenarioSpec ожидания catalog/document: base, kind, inherited fields,
   representation и numbering. Ожидания пишет acceptance-поток из входного сценария,
   а не считывает из generated annotations.
2. Добавить generated-acceptance для пустого и комбинированного документа:
   compile/startup/metadata, canonical create/read/update, дата и сохранность номера,
   стандартное назначение номера, **отказ при отсутствии обязательной даты**, constraints
   и отказ без grants. `date` остаётся NotNull/REQUIRED; базовый контракт не меняется.
3. Проверить display name номера/даты после повторного чтения. Не требовать
   непрерывной последовательности номеров или дополнительных policy, которых нет в recipe.
4. В D сохранить отдельный legacy catalog D1 и подключить документный D2 с matching
   manifest/C2/F2. Rebuild выбирает поддержанный recipe/generatorVersion по E0.3.1;
   до секций extension scope ограничен одним CREATE. Path/ownership/freshness guards сохраняются.
5. Пройти actual plan → matching C → APPLIED → NOOP; конфликт и позднее появление
   target сохраняют чужие bytes. C2 и D2 сверяют реальные current dependency JAR
   по §10.4; receipt failure и перенос проекта проверяются уже для одного файла.
   Регрессия «Матрицы» выполняется через фиксированный legacy profile.

### E1.B. P8, UI-код

1. Явный выбор «Справочник / Документ» при создании нового draft. В этой итерации
   recipe не переключается у уже заполненного draft: новый документ создаётся обычным
   действием создания с действующей защитой dirty, без неявного преобразования полей.
2. Recipe-specific заголовки, base description, builtin navigation и подсказки секций.
   Для документа показывать `Номер / Дата`, их типы, обязательность и стандартную нумерацию.
3. Preview/download используют настоящий dispatch и повторную проверку access/freshness.
   Импортированный документ проходит тот же путь; preview не снимает dirty/checkpoint JSON.
4. Session/component tests проверяют создание обоих видов, импорт, diagnostics,
   поздние callbacks, context change и сохранность старых catalog-v1/v2 workflows.

**Gate E1:** один документ проходит полный однофайловый маршрут, создаётся и повторно
открывается в изолированном приложении. Browser smoke можно выполнить сразу после
среза; обязательная итоговая браузерная приёмка включена в E5.

## 6. E2 — ссылки на существующие сущности

Выполнен 2026-10-29: оба вида прошли core/JSON/P8, actual C2/D2 APPLIED→NOOP,
runtime из applied projects и компонентные формы/lookup. Проверены JAR freshness,
dependency отказы и legacy C1/D1. [Evidence и воспроизведение](../architecture/evidence/entity-generation-g1-extension-20261029/e2/README.md).
Браузерная приёмка остаётся E5.B; owner/sections — E3/E4.

### E2.A. Core/generation/facts, без UI

1. Использовать существующие ReferenceDraft и ReferenceTargetDescriptor. Допуск:
   подтверждённый самостоятельный managed root, совместимый Long-ID, представление
   и штатный выбор. Не загружать draft FQN через reflection в pure core.
2. Генерировать одиночный `@ManyToOne(fetch = LAZY)` и явный `@JoinColumn`;
   optional/nullable/NotNull согласовать с serverRequired. Не добавлять cascade
   persist/remove на цель ссылки и скалярные length/precision к ассоциации.
3. Тип и lookup target выводить из Java/JPA по действующему metadata-контракту;
   не размножать избыточные `type=REFERENCE`/`lookup.entity`, если runtime их выводит.
4. Сохранить label/order/required/form/list/selection. Проверить представление
   ссылочной колонки после закрытия persistence context, а не только внутри транзакции.
5. Разрешать FQN/import collisions детерминированно. Диагностировать недоступную,
   неизвестную, несовместимую цель, ссылку на новый draft/root/row до генерации.
6. Связывать checked/plan с актуальными facts targets. C фиксирует hashes применённых
   platform/target dependencies; D получает current dependency locations от consumer
   adapter и сам повторно хэширует JAR по §10.4. Изменение bytes при прежних facts
   требует нового C; изменение facts требует нового checked/плана. Одного timestamp C недостаточно.

### E2.C. Приёмка, без UI

1. Подготовить существующие targets в отдельном harness support source/JAR. Это
   зависимости сценария, не скрытые дополнительные generated files в однофайловом плане.
2. Сгенерировать catalog и document с optional/required references. Проверить
   запись, повторное чтение, замену A→B и очистку optional-ссылки; required-null отклоняется.
3. Проверить отсутствующую цель, штатные ограничения доступа к lookup/записи и
   запрет удаления используемой цели стандартным reference-check/FK механизмом.
4. Проверить, что создание/изменение/удаление ссылающейся записи не создаёт,
   не изменяет и не удаляет target. D всё ещё применяет один generated source.
5. Подменить bytes target JAR при неизменных type/context/plan: старый C не разрешает
   CREATE/NOOP. Отдельно проверить missing/extra/unreadable dependency и дрейф после публикации.

### E2.B. P8 и формы, UI-код

1. Переиспользовать существующий выбор типа ссылки в P8, показывать причину
   недоступности target и переходить к конкретному полю при generation diagnostic.
2. Проверить реальный lookup и отображение ссылки в стандартных item/list/selection
   forms; исправлять только обнаруженные проблемы этого пути.
3. Сохранить ограничения доступа и поведение required/optional в компонентных тестах.

**Gate E2:** оба вида поддерживают одиночные ссылки на существующие targets через
полный C/D маршрут. Множественные, полиморфные, self-references и связи между несколькими
новыми roots отложены. Ссылки в строках подключаются вместе с E4 на тех же правилах.

## 7. E3 — семантика владельца справочника

Пользователь подтвердил владельца другого типа. В примере **Справочник договоров →
Справочник контрагентов** оба объекта CATALOG; owner документа не входит в E1/E3.
Ни имя `parent`, ни обычная
ссылка сами по себе не делают объект подчинённым. Capability задаётся явно и доступна
metadata, runtime projection, draft и формам. Это root→root, не owned row aggregate.

Первый поддержанный контракт:

| Решение | Ограничение этапа |
| --- | --- |
| Наличие owner | Опциональная возможность типа справочника; у обычного catalog её нет |
| Child и target | Child CATALOG; один owner field, один существующий подтверждённый CATALOG target с Long-ID |
| Обязательность | Если capability включена, owner обязателен: nullable=false и NotNull |
| Значение при создании | Выбор из допустимых существующих владельцев; в owner-контексте подставляется этот owner |
| Изменение после сохранения | Запрещено в первом срезе, включая прямой canonical update; отдельная операция переноса отложена |
| Удаление владельца | Отклоняется, пока есть подчинённые записи; автоматического cascade delete roots нет |
| Нумерация/уникальность | Стандартная catalog-code policy; owner-scope numbering и UNIQUE(owner,code) не добавляются |
| Доступ | Существующие canonical grants/RLS и reference checks; наличие owner не выдаёт прав и не включает наследование RLS |
| Списки/выбор | Общий список — разрешённые пользователю записи; owner-контекст добавляет фильтр этого владельца |

Контекстный фильтр — поведение списка/выбора, а не новая граница авторизации.
При контекстном create/save сервер проверяет согласованность context owner и поля;
обход readonly или подмена параметров формы не должны менять владельца сохранённой записи.
Без owner-контекста разрешён обычный create с явным выбором owner.

### E3.R. Runtime, без UI

Выполнено 2026-10-29: [evidence](../architecture/evidence/entity-generation-g1-extension-20261029/e3-r/README.md).
E3.A/E3.C/E3.B остаются открытыми; read-фильтр и owner-контекст из форм — E3.B.

1. В `platform-contracts` ввести небольшую явную декларацию owner property;
   точное имя аннотации/descriptor фиксируется в E0. Resolver получает тип цели
   из объявленной association, не из соглашения об имени поля.
2. Проверять startup errors: missing property, не association, недопустимый target,
   nullable/required mismatch, несколько owners, попытка объявить section parent как root owner.
3. Добавить resolved owner facts в descriptor/runtime projection и нужные read/query
   контексты. Platform `org.ipro.metadata.CatalogOwnerDescriptor` преобразуется мостом
   `entity-design-runtime` в portable `RuntimeDesignSnapshot.CatalogOwnerFacts`;
   platform-core и design-core не импортируют DTO друг друга и не зависят от Vaadin.
4. В существующей серверной write boundary обеспечить mandatory owner и запрет
   смены после сохранения. Исходное значение читать из доверенного persisted state,
   а не из мутированного managed object; guard должен срабатывать до побочных действий.
5. Использовать существующие reference/delete checks. Проверить, что удаление child
   не удаляет owner, удаление owner с children отклоняется, ограничения transactional.
6. Проверить direct canonical вызов, aggregate save child с его собственными секциями,
   если этот путь подключён, и создание в owner-контексте с подменённым owner.

### E3.A / E3.C. Draft, генерация и headless acceptance

E3.A выполнен 2026-10-29: owner draft/JSON v2/validator/checked/projection и
catalog-java-v3/document-java-v3. [Проверки и actual Java](../architecture/evidence/entity-generation-g1-extension-20261029/e3-a/README.md).
E3.B выполнен отдельно; E3.C принят 2026-10-30: owner и document envelope v1/v2
прошли actual C2 → D2 APPLIED/NOOP → applied-project runtime.
[Evidence E3.C](../architecture/evidence/entity-generation-g1-extension-20261029/e3-c/README.md).

1. Добавить OwnerDraft и отдельный `CheckedStructure.Owner` по E0.3.2/3: targetTypeId,
   fieldName, columnName, label, order=30, applications=root(). Owner не имеет UUID,
   не дублируется FieldDraft и получает отдельный nullable `Projection.owner`/OwnerBinding.
2. Проверять collisions с inherited/system/subject fields и SQL names; хранить
   незавершённый owner-ввод как draft, не терять его при JSON export.
3. Генерировать association + явную owner capability и согласованную field metadata.
   Owner публикуется в form/list/selection после code/name; root subject fields имеют
   order=40+ при owner и 30+ без owner. Builder обрабатывает owner до UUID bindings;
   сохраняет checked/projection owner в F2, context и recipe-specific checks.
4. В C проверить двух owners A/B: create у A, чтение, owner-filter A/B, required-null,
   отказ A→B, отказ удаления A при наличии child, успешное удаление child без изменения A.
   Проверить exact owner publications/order и адресацию owner.* без fieldId.
5. Проверить no grants и обычную ссылку без owner capability: она не получает
   ограничения неизменяемого владельца по одному совпадению имени `owner`/`parent`.
6. Пройти actual plan/C/D; generated source пока один. Для owner-target использовать
   fixture существующего catalog, не добавлять в план второй новый root.
7. Проверить документный JSON v1 и v2 с owner=null через document-java-v3/C2/D2;
   v2 с непустым owner сохраняется как invalid draft и даёт OWNER_UNSUPPORTED_RECIPE.
   Эти проверки совместимости не включают owner capability документа.

### E3.B. P8 и стандартные формы, UI-код

E3.B выполнен 2026-10-30: отдельная owner-панель P8, owner-lock в стандартной
карточке, фиксированный owner-context списка/выбора и resolved owner в Explorer.
[Компонентная регрессия и серверные guards](../architecture/evidence/entity-generation-g1-extension-20261029/e3-b/README.md).
E3.C принят отдельным actual прогоном; browser E5.B остаётся открытым.

E3.B выполнен 2026-10-30: отдельная owner-панель P8, owner-lock в стандартной
карточке, фиксированный owner-context списка/выбора и resolved owner в Explorer.
[Компонентная регрессия и серверные guards](../architecture/evidence/entity-generation-g1-extension-20261029/e3-b/README.md).
E3.C actual owner C2/D2/runtime и браузер E5.B остаются открытыми; gate E3 ещё не закрыт.

1. Добавить отдельную настройку «Владелец справочника» с допустимым target,
   именем поля/колонки и label. Иерархия не предлагается под этой подписью.
2. В item form owner выбирается при create и недоступен для смены после сохранения;
   в owner-контексте подставлен и зафиксирован с самого начала.
3. Подключить контекстный owner-filter к стандартным list/selection путям;
   отображать активного владельца. Не вводить отдельную custom form на каждый catalog.
4. Показать явный контракт в Explorer «Родитель / Владельцы», переиспользуя runtime facts.
   Не превращать обычные ссылки или владельца строки в owner root.

**Gate E3:** готова именно семантика подчинения, включая серверные запреты и формы,
а не только Java-поле ссылки. Полиморфизм, несколько владельцев, перенос, иерархия,
owner-scoped numbering и новые RLS policies не входят.

## 8. Совместимость draft, JSON, recipe и receipts

1. Старые catalog-v1/v2 JSON и raw values сохраняют смысл. Документ регистрируется
   явно; импорт v1 catalog не становится новым document или owner-enabled draft.
2. Для owner вводится envelope v2 с явным owner-блоком. Decoder продолжает читать
   envelope v1; encoder сохраняет v1 для неизменённого старого draft и v2 для нового
   расширенного draft. Преобразование при включении owner — явная чистая операция,
   сохраняющая UUID/поля/секции/raw values и увеличивающая editor revision.
3. Новые DTO поля получают совместимые source constructors/accessors, где это
   возможно. Затронутый опубликованный binary API проверяется отдельно; невозможная
   совместимость отражается новой версией и синхронным обновлением consumers.
4. Не менять захваченную v1 schema. Добавить schema v2 и registry поддержанных
   recipe/envelope pairs. `$ref` при проверке разрешается относительно выбранной
   schema, а не всегда старой v1. Строгость, bounded parsing и duplicate-key guards сохраняются.
5. Новые owner/facts поля входят в versioned fingerprint. Старый профиль сохраняет
   прежний fingerprint и output при неизменённых правилах; новый профиль имеет новую
   generator/fingerprint version по E0.3.1. Изменение вывода или scope требует явного
   bump. Документ сохраняет v1; с E3 допускает также v2 с owner=null. Непустой owner
   у документа отклоняется semantic кодом OWNER_UNSUPPORTED_RECIPE, включая импорт v2.
6. Старые C summaries/receipts читаются только для их подтверждённого однофайлового
   профиля. Receipt другого generator/plan не присваивает ownership новым файлам.
   Если профиль снят с поддержки, возвращается явный version refusal до записи,
   а не ложный NOOP или попытка восстановить владение по content hash.
7. Legacy «Матрица» проверяет прежний поддержанный профиль. Если сознательно выбран
   новый generator version, её новый прогон получает собственный plan/evidence и
   отдельный чистый project root; старые квитанции не переписываются.

## 9. E4.A — табчасти обоих видов: Java и runtime, без UI

1. Использовать существующие SectionDraft/CheckedStructure.Section. Не создавать
   второй draft-язык или новый обязательный section service/repository для приложения.
2. Генерировать root и отдельный `BaseEntity` row-класс на секцию. Root объявляет
   `@TableSections`, row — `@TableSectionMetadata` с точным root type/parent field,
   title, явным `order=10*(i+1)`, `MUTABLE_REPLACE_ALL` и `INHERIT_ROOT`.
   Каждый row имеет явный `@Table(name=checkedSection.tableName())`.
3. Parent row→root — `ManyToOne(LAZY)`, явная колонка NOT NULL и NotNull. Он не
   становится пользовательским lookup/колонкой формы и не объявляется root owner.
4. Выбрать фиксированный технический `Integer lineNumber` / `line_number`, назначаемый
   штатным section service; объявить его в section metadata и исключить из subject fields.
   Указать `lineNumberField="lineNumber"`; subject row fields получают order=10+10*i.
   Добавить collision checks для parent/lineNumber и их columns. Настройку minRows
   в этой итерации не расширять: стандартный `0`, без нового пользовательского правила.
5. Генерировать scalar и существующие reference fields строк с их form/table applications,
   labels и constraints. Не добавлять коллекцию `OneToMany(EAGER)` в root или JPA cascade
   в качестве замены штатному aggregate service.
6. Сохранить порядок секций и полей; порядок строк сохраняется через штатный line number.
   Порядок manifest фиксирован: root, затем rows в draft-порядке; Java content каждого
   entry, imports и hashes детерминированы. Публикация D не обещает видимость файлов одним шагом.
7. Проверить cross-file Java names, table/column names, совпадения путей и коллизии
   на case-insensitive filesystem. Диагностика ссылается на конкретную секцию/поле.
8. Через canonical aggregate path проверить сохранение root + секций, replace-all,
   update/remove rows, очистку секции, повторное открытие и удаление root со строками.
9. Проверить rollback всего агрегата при invalid row/ошибке второй секции, запрет
   перепривязки строки к чужому root и optimistic conflict; standalone CRUD rows запрещён.
10. Повторить минимальную матрицу 0/1/2 секций для catalog/document; добавить один
    комбинированный owner-enabled catalog с секцией и ссылкой в строке.

**Gate E4.A:** actual multi-file output проходит compile/startup/metadata/aggregate
приёмку; отсутствие браузера не блокирует этот backend gate. Финальный E4 также
требует файловую интеграцию §10 и UI §11.

**Результат 2026-10-30:** gate E4.A выполнен, `catalog-java-v4` / `document-java-v4`.
Семь actual JSON→builder→C→D→applied runtime комплектов: catalog/document 0/1/2
секции и owner-enabled catalog с двумя секциями и row references. 127 neutral tests,
55 C и 55 applied runtime tests, 75 foundation D selftests; без failures/errors/skips.
Комплекты применены целиком, повторный запуск даёт NOOP. Отрицательные actual probes
проверяют missing/changed row, foreign last row, wrong required suite и JAR drift.
Подробности, hashes, receipts, XML и границы результата:
[evidence E4.A](../architecture/evidence/entity-generation-g1-extension-20261029/e4-a/README.md).
P8 preview/download/ZIP и формы E4.B, общая приёмка E5 и migration M остаются отдельно.

## 10. E4.C0 / E4.D0 — комплект файлов C и D, без UI

Эти пакеты готовятся после E0 параллельно E1–E3. Общий v2 foundation с одним entry
подключается к E1.C/D; E4.C0/D0 расширяют тот же формат до N entries. Пока input
искусственный, результат называется selftest; actual N-file приёмка ждёт E4.A.
Нормативный manifest/summary/state и их версии заданы в E0.3.1/E0.4 контрактов;
другой transport или переходный однофайловый формат не вводится.

### 10.1. Общий контракт комплекта

`GenerationPlan.files()` уже является списком. Не вводить по независимому плану на
каждую строку: один source draft/context, один planId, один комплект и один результат C/D.

- Manifest transport v2: точные planId, recipe/version, generator/fingerprint profile,
  entries `operation/relativePath/sha256/bytes`; content берётся из reviewable плана.
- Каждый путь уникален с учётом поддержанного filesystem; empty/duplicate entries
  и операции кроме CREATE отклоняются. Хэш комплекта вычисляется по канонической
  последовательности пути, операции, bytes и file hash; алгоритм общий для A/C/D.
- `g1-c-summary-v2` содержит полный список проверенных generated sources и hash
  комплекта, plan binding, hashes зависимостей, compile/runtime/test outcome.
  Не принимать `getFirst()` как подтверждение всего плана.
- Поддержанные target/support sources перечисляются отдельно от generated manifest
  и не применяются D как новые business sources. C не подмешивает незаявленные generated files.
- Изменение одного файла, их состава или context делает старый C непригодным;
  одного успешного compile root без rows недостаточно.

### 10.2. C: проверка комплекта

1. Материализовать все entries в чистом isolated consumer без форматирования,
   CRLF-конверсии или дописывания annotations; сверить точные UTF-8 bytes.
2. Одним build/startup проверить весь комплект и согласованную независимую spec.
   Для rows проверить metadata linkage и настоящую aggregate persistence.
3. Отрицательные selftests: missing/extra/duplicate source, изменённая row, ошибка
   компиляции одной строки, неверный plan/hash, startup failure и отсутствие tests.
4. Summary success требует реального compile/startup и tests>0, failures/errors/skips=0
   для обязательных suites. Не объявлять успех по одному exit code или старым reports.

### 10.3. D: согласованное применение комплекта

1. Сохранить explicit roots, real-path/alias/case/ADS/device guards, no-replace
   publication и один project lock. Проверить **все** paths/entries и matching C до
   публикации первого target, затем повторять нужные проверки перед каждым CREATE.
2. Использовать receipt/journal v2 в `<project>/.g1-apply-v2/`: общий applyId/planId/
   manifest/C hash, verifiedDependencies, пофайловые expected и published identities,
   созданные каталоги и durable phase. SourceRoot/target/temp/createdDirs — project-relative;
   absolute projectRoot/realPath — provenance. Структура и guards нормативны в E0.4.
3. APPLIED только после создания полного комплекта, сверки и durable receipt;
   NOOP только если собственная завершённая квитанция покрывает весь тот же комплект
   и каждый target неизменён. Нельзя принимать смесь старых/чужих файлов.
4. Если хотя бы один target foreign/modified, остановиться до записи всего комплекта;
   поздний конфликт вызывает ограниченный rollback только собственных созданных файлов.
   При чужой правке уже созданного файла его сохранить и вернуть RECOVERY_REQUIRED.
5. При обычном сбое безопасно убирать только entries, чьи identity/hash и принадлежность
   текущему apply подтверждены. Проверять ошибки на втором/третьем файле и записи ledger.
   Receipt failure с доказанным отсутствием durable receipt и полным rollback/cleanup
   даёт ROLLED_BACK; неопределённый receipt outcome или неполный cleanup — RECOVERY_REQUIRED.
   Если receipt уже durable, а journal cleanup не завершён, committed output не удалять
   и APPLIED не сообщать: вернуть RECOVERY_REQUIRED.
6. После hard crash распознавать pending journal и возвращать RECOVERY_REQUIRED.
   Не запускать build/runtime из такого проекта через integration launcher до
   явного восстановления. Автоматические roll-forward и удаление неоднозначных файлов
   не входят; выдаётся понятная инструкция по сохранённым phase/path.
7. Это согласованный протокол с rollback/recovery, **не атомарная транзакция всей ФС**:
   внешние процессы могут увидеть промежуточное состояние. Partial не является успехом.
8. Обязательная D-матрица: successful N files, whole-set NOOP, foreign identical,
   modified/missing row, другой planId, stale context/C, late target, сбой записи и
   receipt с обоими rollback/recovery outcomes, pending journal, path aliases/case
   collisions, current dependency drift и lock двух процессов. Completed проект с
   ledger после переноса/копирования допускает NOOP по новым paths/bytes; старые fileKey
   не обязательны для NOOP и не разрешают очистку. Pending journal после переноса — RECOVERY_REQUIRED.
9. Повторить реальный A→C→D маршрут на multi-file plan, затем build/runtime уже из
   применённого проекта. Selftest adapter не закрывает этот integration gate.

### 10.4. Текущие зависимости и relocation — общие для E1–E4

Consumer/integration adapter передаёт D current dependency locations `{id, path}` из
реальной конфигурации build. D не берёт эти пути из summary/receipt, сам хэширует JAR
под lock до CREATE/NOOP и повторно до durable receipt; набор id/hash должен точно
совпадать с C2. Missing/extra/unreadable — REJECTED, changed bytes —
`REJECTED/DEPENDENCIES_CHANGED`; дрейф после публикации запускает ограниченный rollback.
Точный контракт и статусы — E0.4, включая отсутствие автоматической перезаписи receipt
при новом C/dependency set. Hashes передаются в D не вместо доступа к actual JAR.

Перенос completed проекта сохраняет относительный sourceRoot/targets и ledger.
Абсолютный projectRoot служит provenance: D пересчитывает пути от текущего root,
заново проверяет aliases/containment, C/dependencies и весь комплект bytes, затем
допускает NOOP. Pending journal блокирует и relocated проект; старые absolute paths
и fileKey не используются для автоматического удаления. Изменение sourceRoot
внутри проекта — изменение binding, а не перенос всего проекта.

### 10.5. Результат D-приёмки E4 на 2026-10-31

E4.C0/E4.D0 реализованы ранее; actual multi-file A→C→D и applied runtime обоих
видов приняты в E4.A. Дополнительно закрыты пробелы D-проверок на настоящем
generated-комплекте document root + две rows с обычным public freshness rebuild:
I/O failures перед вторым/третьим и после третьего файла, late third target,
receipt failure с полным rollback, частичным cleanup/чужой правкой, неопределённым
receipt и сбоем после durable receipt, completed move/copy → NOOP и pending
move → RECOVERY_REQUIRED. Проверена неизменность recovery-повторов и старых
provenance paths. Полная D-матрица: **87/87 passed, 0 failed, 0 skipped**
(75 прежних + 12 новых; повторные прогоны не суммируются).

Новые cases — D selftests с synthetic C/dependency JAR; они дополняют, а не
заменяют actual E4.A/C/D. Production D не менялся. Pending stop моделируется
test-only Error после двух публикаций; отдельный OS hard-crash этим не заявляется.
[Evidence, точная матрица, исходники и воспроизведение](../architecture/evidence/entity-generation-g1-extension-20261029/e4-d-completion/README.md).
E4 принят в функциональном/компонентном scope вместе с E4.A/B. В самом пакете E4
E5.H/E5.B и M не запускались. Последующий результат E5.H указан в §13;
E5.B и M остаются открытыми.

## 11. E4.B — P8 и пользовательская выдача комплекта, UI-код

1. Сохранить существующий редактор секций; включить документные подписи/owner hints
   и адресные переходы diagnostics без переделки layout всех видов.
2. Preview показывает список всех путей/операций и текст выбранного файла. Пользователь
   может просмотреть root и каждую row до скачивания; счётчик соответствует manifest.
3. Для одного файла сохранить `.java` download; для комплекта выдать ZIP с relative
   Java paths и корневым `generation-manifest.json` — ровно JSON `GenerationManifest`
   по E0.4 контрактов, UTF-8 без BOM, с LF. Никакого второго формата manifest.
   ZIP формирует IO/UI adapter, не pure core. Не включать
   машинные пути, target fixtures или чужие исходники; contents совпадают с планом.
4. Freshness/access проверять на весь комплект перед выдачей. Поздний callback
   не выдаёт старый ZIP после изменения любого row/owner/target context.
5. Preview/download не меняют JSON checkpoint и не снимают dirty. Статус «Java
   сформирован» не означает «C пройден» или «D применён».
6. Компонентно проверить 0/1/2 секций обоих видов, preview navigation, состав ZIP,
   отказ доступа и stale plan. Стандартные формы используют реальную metadata:
   добавление/изменение/удаление rows, reference lookup, ошибки и reopen.
7. Не добавлять UI запуска D/миграций в этом этапе: файловое применение остаётся
   явным существующим adapter/launcher шагом. Отдельный SQL/IDE/workspace designer не нужен.

Статус на 2026-10-30: **E4.B завершён на уровне реализации и компонентной приёмки**.
Первоначальная выдача reviewed plan по п.2–5 сохранена как
[partial evidence](../architecture/evidence/entity-generation-g1-extension-20261029/e4-b/README.md).
После E4.A проверены настоящие catalog/document inputs с 0/1/2 секциями и combined
owner + row reference: обычный P8 import → preview каждого файла → single Java/ZIP.
Все bytes и manifest совпадают с actual A→C→D evidence E4.A. Inspector/diagnostics
различают row parent, root owner и обычную reference; ошибки открывают нужное поле.
244 component/core/JSON/runtime tests и 28 standard form/H2 tests прошли без
failures/errors/skips. Form consumers компилируют скачанные P8 bytes и используют
текущие штатные factory, row dialogs, lookup и aggregate save: add/edit/remove,
validation/SQL errors без потери ввода, clear/reopen, access/read-only и owner lock.
[Completion evidence, ZIP, hashes и XML](../architecture/evidence/entity-generation-g1-extension-20261029/e4-b-completion/README.md).
На 2026-10-31 после дополнительной трёхфайловой D-приёмки (§10.5) функциональный
и компонентный E4 принят. Общая автоматическая интеграция E5.H выполнена
следующим пакетом (§13); браузерная приёмка E5.B и migration gate M не запускались.

## 12. Приёмочные сценарии и evidence

Все названия ниже — test fixtures из изолированного consumer, не новые сущности
основного приложения. Число tests определяется реальными suites, а не задаётся заранее.

| Сценарий | Основная проверка | Граница UI |
| --- | --- | --- |
| Matrix legacy | Старый catalog profile, scalar boundaries, C/D, conflict preservation | Existing component coverage; browser для нового этапа отдельно |
| EmptyDocument | Inherited number/date, representation, numbering, canonical save/read | Component P8 + browser item form |
| ScalarDocument | Семь scalar types, required/publications/titles, update/reopen | Component; browser representative fields |
| ReferenceCatalog / ReferenceDocument | Optional/required, A→B/null, lookup/access/target preservation | C headless + browser lookup |
| OwnedCatalog | Справочник договоров → справочник контрагентов; owners A/B, exact owner order/publications, scope filter, запрет смены/удаления используемого owner | Runtime/C headless + browser owner contexts |
| Catalog/Document 0/1/2 sections | 1/2/3 generated files, metadata, replace-all, delete, rollback | C headless + component; browser по обоим видам и двум секциям |
| OwnedCatalog + section reference | Root owner, row parent и обычная reference не смешиваются | Headless aggregate + один browser combined flow |
| Multi-file D failures | Whole-set conflict/no-op, late write failure, rollback/crash/lock | Полностью без UI |
| ReceiptFailure / ProjectRelocation | ROLLED_BACK при полном доказанном откате; RECOVERY_REQUIRED при неопределённости; completed relocation → NOOP, pending relocation → RECOVERY_REQUIRED | Полностью без UI, один файл уже в E1.D и комплект в E4.D0 |
| DependencyDrift | Изменённый JAR при прежнем plan/context не допускает старый C; отказ CREATE/NOOP и rollback/recovery при позднем дрейфе | Полностью без UI, E1–E4 |

Для E1–E4 сохранять отдельно:

1. Current draft/context, независимую spec, reviewed plan и actual Java/manifest.
2. Версии producer/consumer, recipe/generator/fingerprint/summary/state, hashes JAR.
3. C summary/logs/XML, D APPLIED/NOOP/conflict и project-local receipt/journal evidence.
4. Результат повторного build/runtime из проекта, заполненного D.
5. Component reports отдельно от browser steps/screenshots и найденных дефектов.

Рекомендуемые рабочие каталоги: `outputs/entity-generation-g1-extension/<slice>/<run>/`;
сохранённое evidence — `docs/architecture/evidence/entity-generation-g1-extension-<date>/`.
Не перезаписывать старую «Матрицу», не складывать журналы параллельных запусков в один run,
не суммировать повторные прогоны одних tests как дополнительные уникальные проверки.

## 13. E5 — интеграция и завершение большого этапа

### E5.H. Автоматическая интеграция без UI

1. Собрать согласованный набор core/json/runtime/platform artifacts и записать hashes.
   Последовательно обновить затронутые dependency manifests/API baselines по правилам проекта.
   Передать D current dependency locations, соответствующие effective consumer classpath;
   сравнение current bytes с C выполняет adapter, а не только итоговый отчёт интегратора.
2. Прогнать целевые module tests, C/D selftests и actual generated-acceptance для
   матрицы §12. Широкие platform checks выполнять по затронутым contracts/security/data
   boundaries; известные несвязанные register/CTE задачи не возвращать в scope автоматически.
3. Actual файлы для C брать из настоящего builder. После подключения P8 сверить
   выданные UI bytes/manifest с тем же планом; иной путь/хэш требует нового C.
4. Пройти D в чистом project root, whole-set NOOP, conflict copy и build/runtime
   из применённого проекта. Для нового профиля не использовать старый ledger.

**Результат 2026-10-31: E5.H выполнен.** В отдельный Maven repository последовательно
собраны/установлены 18 текущих design/platform-модулей и два support JAR;
общий cache не переустанавливался. На одном неизменяемом наборе JAR пройдены:

- Backend/design/JSON/runtime/metadata/canonical/security/autoconfigure:
  **83 suites, 481 tests**, без failures/errors/skips.
- P8/forms и production boundaries: **33 suites, 161 tests**; отдельная API baseline
  проверка **13 platform artifacts, 4 tests**; без failures/errors/skips.
- C harness: **12/12 selfchecks**; D legacy/extension/three-file: **87/87 selftests**,
  без failed/skipped checks. Отрицательный C allSkipped обязан отказать, он не
  считается успешным generated-acceptance.
- **15 actual routes** по §12: настоящий builder/P8 output → C (**100 tests**) →
  D APPLIED → whole-set NOOP → новый runtime из D-applied project (**103 tests**) →
  conflict copy с сохранением чужих bytes; failures/errors/skips=0.
- **7 downloaded form consumers, 28 tests** на установленном platform-vaadin JAR;
  **5 actual negative probes**: missing/changed row до Maven, current JAR drift на
  CREATE/NOOP и неверный required suite до CREATE.
- **1204 итоговых checks**: hashes неизменяемых JAR/POM и producer/test bytecode,
  точные generated/P8/C/applied bytes, manifest/C/receipt/dependency bindings,
  required reports, отсутствие pending journals и актуальные source fingerprints.

Обновлены четыре dependency fingerprints (contracts/core/runtime/autoconfigure),
reviewed owner surface/budgets и четыре API snapshots
(contracts/core/autoconfigure/vaadin); сравнение после записи прошло отдельно.
Для нестандартных evidence build directories исправлен ArchUnit import filter,
исключающий test-classes; production правила сохранены. Production generator и
canonical/owner/aggregate реализация в этом пакете не менялись.

Явное исключение по E5.H.2: per-file test-usage overlay method
`PlatformCoreSurfaceTest.applicationTestReferencesToInternalOrModuleTypesAreFrozenPerFile`
обнаруживает несвязанный `TwoDimensionRegisterCteSpikeTest → SectionMetadataRegistry`
drift и не входит в целевой прогон. Все шесть остальных production/API surface
проверок выполнены; исходный failed отчёт сохранён. Это не скрытый JUnit skip.
Подробности, отдельные counts, source/JAR hashes, XML, receipts и воспроизведение:
[evidence E5.H](../architecture/evidence/entity-generation-g1-extension-20261029/e5-h/README.md).
Повторные прогоны не суммируются как дополнительные уникальные tests.
**E5.B и M не запускались и остаются открытыми; весь E5 ещё не завершён.**

### E5.B. Браузерная приёмка

1. Поднять отдельный стенд с подтверждёнными артефактами, P8, стандартными формами,
   H2 test data и именованной тестовой учёткой/grants. Подготовка стенда входит в E5.
2. В P8 создать документ, задать scalar/reference fields, получить Java; проверить
   документные builtins и адресный отказ. Затем просмотреть/скачать комплект секций.
3. Открыть в изолированном consumer реальные generated catalog/document: create,
   update, reference replace/clear, reopen и сообщение required/constraint failure.
4. Проверить owner A/B contexts: подстановка при create, фильтр списка/выбора,
   запрет смены сохранённого owner и понятный отказ удаления используемого владельца.
5. Для обоих видов проверить sections: add/edit/remove/reopen, две секции, ошибка
   строки с сохранением пользовательского ввода; один combined owner/reference/section flow.
6. Приложить browser evidence. UI access и серверная безопасность не заменяют друг
   друга: отказ через direct canonical path уже проверяется в headless suites.

### Definition of Done

- [x] E0: общие контракты/версии/распределение файлов зафиксированы.
- [x] E1.A: document draft/validator/JSON, scalar root generation и core tests.
- [x] E1.B: выбор вида, document builtins/hints, preview/download и P8 components.
- [x] E1: документ прошёл core + JSON + P8 components + actual C/D, applied runtime и повторный review.
- [x] E2: ссылки у обоих видов прошли core/runtime + forms + actual C/D.
- [x] E3: владелец прошёл explicit metadata + server guards + P8/forms + actual C/D.
- [x] E4: оба вида с 0/1/2 sections, complete manifest C, multi-file D и ZIP приняты; трёхфайловые D failure/recovery/relocation cases подтверждены. Общая приёмка E5.H/E5.B отдельно.
- [x] E5.H: согласованная сборка, полный автоматический прогон и повторная работа данных из 15 D-applied projects подтверждены.
- [ ] E5.B: браузерная матрица выполнена на изолированном стенде.
- [x] Legacy совместимость и significant failure/access/data cases подтверждены в автоматическом scope; браузерные сценарии остаются E5.B.
- [x] Evidence и инструкция запуска отражают автоматический scope, отдельные счётчики и явное register/CTE исключение без скрытых skips.
- [ ] Следующий migration gate M явно остаётся отдельным незавершённым обязательством.

## 14. Практический порядок параллельных потоков

Это порядок будущего выполнения, а не запуск агентов или новых чатов данным документом.

| Волна | Основной поток model/generator | Независимые потоки без UI | Поток UI |
| --- | --- | --- | --- |
| 0 | E0: API, версии, scenario inputs | Сверка текущих C/D, фиксация selftest fixtures | Сверка P8 points, без изменения shared DTO |
| 1 | E1.A → документный model/API | E1.C/D: однофайловый C2/D2 foundation + current dependencies; E4.C0/D0: N-file расширение тех же форматов | E1.B после model/API |
| 2 | E2.A → reference generation/facts | E2.C; E3.R после owner/reference freeze; продолжение C/D multi-file | E2.B; E1 browser smoke при готовом стенде |
| 3 | E3.A → owner draft/JSON/generation | E3.C; завершение E4.C0/E4.D0 selftests | E3.B; E4.B shell после manifest API |
| 4 | E4.A → root + rows | E4.C actual acceptance → D integration | E4.B actual integration |
| 5 | E5.H, последовательная сборка артефактов | Изолированные regression/scenario runs | E5.B после готовности приложения |
| 6 | Большой этап завершён | M: отдельный migration plan и gate | UI не требуется для migration gate |

Условия безопасной параллельности:

1. **Один владелец shared files.** RecipeCatalog, DraftValidator, EntityDraft,
   FieldApplicationMapping, PlanFingerprint, JSON schema/codec и общий dispatcher
   изменяет основной поток; остальные передают требования и свои tests/fixtures.
   Runtime owner API согласуется с ним до изменения snapshot/descriptor.
2. **C и D делят контракт, не файлы.** C владеет harness/spec/report producer,
   D — adapter/receipt/journal/parser. Новая версия summary фиксируется в E0;
   последующие изменения согласовываются до правки обеих сторон. E1.C и E4.C0 —
   один C-поток с общим v2 foundation, E1.D и E4.D0 — один D-поток; две задачи
   не означают двух исполнителей, одновременно меняющих один harness/adapter.
3. **Разные build/output roots.** Не запускать конкурирующие Maven сборки одного
   module target или `clean` общего root target. Нынешний D launcher строит core:
   для параллельного selftest нужен заранее собранный неизменяемый core artifact
   либо отдельный build directory, а не `SkipCoreBuild` поверх меняющегося target/classes.
4. **Не перезаписывать общий Maven cache.** Install/обновление SNAPSHOT и fingerprints
   выполняет интегратор последовательно. Остальные используют зафиксированные JAR
   или собственные local repositories; actual C записывает hashes зависимостей.
5. **Один P8/form edit stream.** E1.B/E2.B/E3.B/E4.B трогают общие editor files и
   формы, поэтому их UI-изменения интегрируются по очереди. Headless C/D от этого не ждут.
6. **Разные runtime instances.** Параллельные C используют свои consumer directories,
   БД и порты; UI стенд и браузерный прогон имеют одного владельца на запуск.
7. **Сначала selftest, затем actual integration.** У каждого потока есть полезный
   независимый результат, но synthetic receipt/ручной Java не выдаются за готовность E1–E4.

## 15. Что откладывается до следующего пакета

**M — migration gate выполняется после E5 по прямому решению пользователя.**
Он не является prerequisite E1–E5, не входит в их DoD и не закрывается H2 ORM create-drop.
Исторический полный G1.3 до M остаётся незавершённым, даже когда функциональное
продолжение принято. Формулировка результата: «Функциональное расширение G1 и файловая
приёмка завершены; migration gate запланирован следующим пакетом».

Будущий M должен проверить явный schema plan для root/rows/reference/owner tables,
constraints/FK и порядок применения на выбранной тестовой СУБД; его точный scope и
критерии оформляются после стабилизации generated-модели. Сейчас migration scripts,
SQL preview, Liquibase/Flyway pipeline и изменение рабочей БД не реализуются.

Также вне текущего этапа: MODIFY/merge существующих Java; генерация нескольких новых
связанных roots; полиморфные и self-references; несколько owners и перенос между ними;
иерархия; owner-scoped numbering; posting/движения/регистровые recipes; custom persistence
секций; конструктор RLS; подсистемы со своим Java-mapping; schema import/G2; IDE и deployment.
