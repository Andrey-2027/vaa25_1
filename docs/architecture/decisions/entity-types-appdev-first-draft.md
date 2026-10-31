# ADR-проект: минимальный AppDev-контракт сущностей и единая классификация

- Статус: **проект**, подготовлен в ET0 2026-10-21. Не отменяет принятые ADR.
- Номер ADR намеренно не занят до принятия: каталог развивается параллельно.
- Область: declarations/discovery/resolved metadata/classification и граница design-core.
- План реализации: локальный `docs/plans/entity-types-appdev-first-plan.md`, ET0–ET9.

## Контекст и проверенное исходное состояние

ADR-0003/0004 обещают минимальный standard path, но сейчас `MetadataResolver` требует
`EntityMetadata`, `EntityDescriptorCatalog` трактует любой IllegalArgumentException
резолвера как отсутствие metadata, а discovery нумерации/Explorer/подсистем/ссылок
расходится. `StandardCatalogEntity` и `StandardDocumentEntity` пока INTERNAL.

ET0 измерил application PU на H2: 44 типа, 21 STANDARD_ROOT, 7 OWNED_ROW,
16 INTERNAL_STORE; наследников Standard* нет. Два проверенных регистра имеют локальный
PLAIN, но query уже выделяет категорию «Регистр». Эти runtime-факты относятся к
загруженным локальным SNAPSHOT JAR, не доказывают parity всех dirty sources.

`getFormFields()` — фактический источник обычных query-полей. Простое включение всех
JPA-атрибутов изменило бы публикацию. P8 уже имеет независимый EntityDraft и capture-рецепт,
поэтому создание второго design-core или классификатора внутри UI дублирует правила.

## Предлагаемое решение D1–D12

| ID | Нормативное предложение | Реализационный этап |
| --- | --- | --- |
| D1 | `@Entity` + ancestry StandardCatalogEntity/StandardDocumentEntity достаточно для поддержанного стандартного прикладного профиля. EntityMetadata задаёт локальные отличия. | ET3–ET5 |
| D2 | Обычный BaseEntity без прикладного intent остаётся внутренним. EntityMetadata или иной поддержанный модульный профиль/ownership дают соответствующую применимость; все managed-типы не становятся корнями. | ET1/ET4 |
| D3 | Отсутствие применимого описания, успешное описание и ошибка объявления — разные исходы API. Empty допустим только для первого; strict resolve сохраняется. Ошибка содержит FQN, property и первопричину. | ET1 |
| D4 | Структура persistent-атрибута из JPA, публикация в канале и оформление — отдельные факты и APIs. Наличие структуры не расширяет каналы. | ET2 |
| D5 | Публикация консервативна: inherited code/name и number/date уже опубликованы базами; дополнительный реквизит объявляется локально, сейчас FieldMetadata. Labels/editors/required/reference могут выводиться; повторный набор field ACL не вводится. | ET2/ET4 |
| D6 | Один PU-scoped managed snapshot и согласованные правила применимости используются runtime-потребителями. Marker scans не-JPA subsystem/settings остаются по назначению. | ET3 |
| D7 | Минимальный discovery-порт расположен ниже core, предпочтительно platform-metadata; классификация в core. Не допускается numbering→core при core→numbering. | ET3 |
| D8 | Один итоговый semantic kind вычисляется в core из базовых metadata и непротиворечивых проверенных вкладов. INFORMATION_REGISTER подтверждает RegisterDescriptor, не один marker. Source failure не превращается в PLAIN. | ET6: принято в [ADR-0029](ADR-0029-entity-semantic-classification.md), уточняет ADR-0013/F-ERP-3 |
| D9 | Semantic kind, exposure/ownership, identity и generic writes — независимые характеристики. Interned CATALOG допустим; OWNED_ROW не новый kind. Origin/symbol сохраняются без угадывания. | ET6–ET7 |
| D10 | Только полный контракт интернирования может задавать безопасные write defaults; текущий маркер этого не делает. Natural key описывается структурно вместе с нормализацией, NULL/unique/create channel; предметная канонизация остаётся явной. | ET8 |
| D11 | Standard и custom используют единый платформенный путь с локальными отличиями. Custom BaseEntity не обязан иметь code/name/number/date фиксированных имён и типов; возможность проверяет необходимые ей роли. | ET4–ET5 |
| D12 | Развивается существующий EntityDraft/design-core и версионированный рецепт. Runtime авторитетен для действующего поведения; CheckedStructure не является compile/startup/persistence-приёмкой генерации. Capture-рецепт не меняет смысл задним числом. | ET4/ET9 |

### Порядок фактов и инициализации

JPA metamodel → M/A (managed set / применимость) → базовые metadata, numbering и
sections → проверенные модульные descriptors → итоговая core-классификация → UI/query
и проекция в design-core. Проверка DOCUMENT регистратора использует базовое проверенное
описание; RecorderTypeRegistry не запрашивает каталог, зависящий от RegisterRegistry.
При конфликте вкладов нет победителя по Spring order. Некорректный снимок не публикуется
как частично здоровый; startup aggregation сохраняет причины.

### Минимальный контракт и каналы

```java
@Entity
public class Warehouse extends StandardCatalogEntity {
}
```

Целевой стандартный пример требует одного entity-класса и одной Entity-аннотации,
без обязательных EntityMetadata, repository/service/handler, повторного kind,
display/search метода и ручной регистрации по подсистемам платформы.
Для нового публикуемого address — поле, реальные ограничения и одна FieldMetadata;
непубликуемое persistent-поле остаётся структурно известным.

Само наследование не создаёт REST-resource, grants, business submenu или новую подсистему.
NoSubsystem не прячет прикладной тип из общего Explorer/каталога. C5/entity operations/RLS
и ADR-0018 остаются действующими. UI formFields не расширяют REST projections.

### Совместимость и ограниченные профили

Поддерживаются проверенные standard catalog/document, custom root, owned mutable row,
internal store; register classification и full interning подключаются своими этапами.
Свободное произведение переключателей не обещается. Unsupported комбинация диагностируется.
APP_API повышение двух баз проходит surface/API/module gates; constructors/records
не ломаются молча. Schema migration текущих моделей — только отдельный конкретный diff.

## Изменения относительно прежних решений

| Основание | Что сохраняется | Предлагаемое уточнение |
| --- | --- | --- |
| ADR-0004 §2–6 | Реальные mapped bases, custom path, numbering policy, explicit ownership, независимые capabilities | Необязательная EntityMetadata у Standard*, APP_API роль, общий discovery и effective presentation/search defaults. Кандидат в старом тексте не доказательство schema compatibility. |
| ADR-0008 §1–6,8 | Kind отдельно от identity/writes; NaturalKeyCreateSupport, транзакционный retry, предметная канонизация и разрешённое переименование | Full opt-in контракт ET8 заменяет повторную infrastructure policy только после parity. Проверка «любой unique constraint» недостаточна для доказательства конкретного ключа. Не вводится reflection DSL канонизации. |
| ADR-0013 §6 / F-ERP-3 | Модульный проверенный register contract; пилот не считается production-примером | D8 принят отдельно в ADR-0029: проверенный платформенный descriptor + acceptance independent/recorded профилей. Итоговый INFORMATION_REGISTER действует с ET6; прежний PLAIN сохраняется только как raw metadata provenance. |
| Старый E3 §2.3 | EntitySummaryAssembler остаётся единственным сборщиком UI-карточки; FactSource/FactOrigin и raw symbol различаются | Общая runtime-классификация принадлежит core; assembler потребляет факты. Независимый design-core не зависит от Vaadin и не получает UI-specific classification. |
| P8 capture v1 | Сохранность IDs/raw input/schemaVersion/recipe.version, generation=false | baseApiRoleAtCapture остаётся историческим INTERNAL даже после повышения базы. Новый исполнимый рецепт получает новую версию, prerequisites проверяются отдельно. |

## Рассмотренные альтернативы

1. Исправить только resolver и оставить scans: дешёвый локальный diff, но bare entity
   исчезает из numbering/Explorer/query. ET1 полезен отдельно, D1 целиком не выполняет.
2. Унаследовать EntityMetadata: частично помогает scan, но не решает PU membership,
   raw/default provenance, channel contracts и циклические зависимости.
3. Все JPA-типы считать корнями/все persistent fields публиковать: нарушает D2/D4,
   каналы internal storage и опубликованный query/REST baseline.
4. Генератор повторяет wiring: скрывает дефект для generated case, оставляет ручной
   путь отличным и требует помнить несколько регистраций.
5. Новые INTERNED/OWNED_ROW kind: смешивает независимые оси и теряет различия двух
   действующих interning policies.

Выбран общий runtime-контракт с явными bounded profiles; цена — последовательная
миграция потребителей, адресные startup ошибки и API compatibility checks.

## Приёмка и статус принятия

ET0 фиксирует ожидания; ET1 может завершиться самостоятельно после ET0. D1 проверяется
сквозным внешним consumer и практической пробой ET5, D8 — отдельным ET6 решением,
D10 — ET8 parity, D12 не включает генерацию до реального G1 gate.
Обновление 2026-10-27: runtime-комплект ET0–ET7 реализован и подтверждён общим gate;
Standard* имеют APP_API-роль, внешняя/прикладная приёмка и budget подтверждены ET5/ET7.
ADX-01 закрыт в этом scope; D8 принят в ADR-0029 с уточнением ADR-0013/F-ERP-3.
[Evidence](../../plans/entity-types-appdev-first-et7-evidence.md).
D10 реализован в ET8 (2026-10-28): один полный opt-in контракт, derived write defaults,
parity двух моделей, PostgreSQL applied-schema/forced-race/rollback gate и binary consumer.
Нормативное уточнение — ADR-0008; [evidence ET8](../../plans/entity-types-appdev-first-et8-evidence.md).
D12 реализован в части ET9 handoff: прежний design-core, переносимые проверенные runtime facts,
явный catalog-v2 и матрица стадий/prerequisites. V1 capture/JSON сохранены; генерацию файлов
это не включает. Реальный GenerationPlan/файловый adapter/compile-runtime gate остаются G1,
generationEnabled=false у всех рецептов. [Evidence ET9](../../plans/entity-types-appdev-first-et9-evidence.md).
