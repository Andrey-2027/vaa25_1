# F-REST-READ-4: HTTP-адаптер и приёмка первого REST read-среза

> Актуальная модель полей: [ADR-0016](decisions/ADR-0016-rest-resource-field-access.md),
> 2026-10-06. API + entity read достаточно для опубликованных полей, фильтров
> и сортировок. Attribute grants из прежних критериев ниже больше не требуются;
> entity read используемых ссылок и RLS сохраняются.

- Статус: 4.0–4.7 завершены для локальной read-приёмки. Полный стандартный bootstrap на стабильных inputs: application 1830 tests, failures/errors 0, 6 PostgreSQL-only skipped; frontend/package и strict validation — exit 0. З5/Gate 3A/3B закрыты. Общий C5/C, consumer и deployment gates остаются отдельными; HTTP opt-in выключен. [evidence 4.7](rest-read-api-stage4-47-evidence-20261006.md).
- Реализация 4.3 (отличия механики от текста review, семантика та же): resolver web-модуля вынесен в отдельный `RestRlsResolverAutoConfiguration` — совместить `@AutoConfigureAfter(RestReadAutoConfiguration)` и `@AutoConfigureBefore(RlsAutoConfiguration)` в одном классе нельзя, цепочка `DataAccessAutoConfiguration → RestResourceCatalog → RestRead` уже держит RLS до REST-конфигураций (AutoConfigure cycle). `RlsFilterActivator` выбирает ровно один независимый **singleton** resolver (scoped-кэши — состояние, а не источник; proxy-delegate `ScopedProxyFactoryBean` не считается), два источника останавливают startup. `RestRequestScopeFilter` маркирует request-атрибут и при необходимости сам привязывает `RequestContextHolder`, чтобы request-scoped кэш был разрешим даже при чтениях во время authentication; на UI-пути session-бин разыменовывается лениво через `ObjectProvider`.
- Дата: 2026-10-05.
- Родитель: [REST read plan](rest-read-api-plan.md).
- Серверный контракт: [этап 3](rest-read-api-stage3-security-read-plan.md).
- Проверка входа: [ревью 2026-10-05](rest-read-api-stage3-review-20261005.md).
- API-review 4.0: [wire, gateway, RLS seam, HTTP priority, errors](rest-read-api-stage4-api-review-20261005.md).
- Архитектура: [ADR-0007 §4.1](decisions/ADR-0007-canonical-data-access-path.md#rest-fixed-fetch-profile).
- Сборка: [local dependency workspace](build/local-dependency-workspace.md).

## 1. Результат и границы

Внешний клиент выполняет `GET list/detail` двух явно опубликованных ресурсов:
`specifications:v1` и `nomenclature:v1`. Платформа принимает HTTP, аутентифицирует
пользователя, разбирает только объявленные параметры, вызывает готовый read
pipeline и возвращает версионированный JSON. Поля/фильтры/сортировки и предметные
grants остаются ответственностью приложения. Для третьего потребителя того же
ресурса достаточно отдельной учётной записи и grants.

Это **последний функциональный этап первого REST read-среза**. Пилоты и итоговая
приёмка, перечисленные в родительском плане пунктами 5–6, входят в этот этап,
а не образуют необязательные последующие работы. Закрытие этапа означает готовый
read-контракт и его проверенную поставку; публикация во внешней среде дополнительно
требует уже установленного gate C5/C и deployment проверки выбранной СУБД.

Вне среза: запись, синхронизация, change feed/cursor/snapshot, owned-секции,
универсальный query DSL, автоматическая публикация JPA, генератор/Studio,
новый IAM/token server и полный write/action/UI/admin rollout C5.
OAuth2 может стать отдельным транспортным профилем по потребности клиента.

## 2. Входные gates и замечание к этапу 3

З0–З4 проверены; контрольный набор 2026-10-05 — 40/40.
З5/Gate 3A/3B закрыты полным стандартным bootstrap 2026-10-06: 1830 application tests, failures/errors 0; все прежние 14 C5/problem classes проходят. Подробности и стабильный source/JAR snapshot — [evidence 4.7](rest-read-api-stage4-47-evidence-20261006.md). Полный C5/C и внешняя публикация этим результатом не объявляются завершёнными.

| Gate | Что разрешает | Что требуется |
| --- | --- | --- |
| Подготовка 4.0–4.5 | Wire-контракт, API-review, изолированная реализация и тесты | Стабильный серверный read contract; выполнен |
| Сквозная приёмка 4.6–4.7 | Подтверждение готового HTTP read-среза | Закрытые Gate 3A/3B и З5, повторные checks затронутых owner/app modules |
| Открытие внешнему потребителю | Включение маршрутов в целевой среде | Приёмка read-среза, общий C5/C gate, TLS/учётки/grants, deployment checks |

Оставшийся обязательный входной результат: устранить регрессии полного C5
в существующих callers/test fixtures и записать зелёный стандартный bootstrap
по плану завершения этапа 3. Полный bootstrap не заменяется адресными 40 tests.
Изолированный HTTP pilot можно запускать до этого, но он не закрывает gate.

## 3. Модули, владельцы и подключение

Выбранное размещение — отдельный optional **`platform-rest-web`**:

| Владелец | Ответственность |
| --- | --- |
| `platform-rest` | Builder/catalog, авторизация операции и read pipeline; узкий MODULE_API для web-адаптера |
| `platform-rest-web` | MVC routes, parser/codecs, wire DTO, Problem Details, security chain, OpenAPI generation, request RLS integration |
| `platform-rls` | C5/RLS decision; узкий seam для выбора источника readable ids при сохранении UI cache |
| `platform-core`/autoconfigure | Canonical read и транзакционная граница; их существующий контракт сохраняется |
| Приложение | Декларации ресурсов, UserDetailsService/PasswordEncoder/RlsCurrentUser, учётки/grants и выбор среды |

`platform-rest-web` зависит от `platform-rest` и managed Spring MVC/Security
стека Boot 4.0.6/JDK 21. Ни Vaadin, ни `org.ip.*`, ни WebFlux, ни Spring Data REST
в нём не требуются. Существующий `platform-rest` сохраняет запрет на MVC/HTTP
зависимости; такой запрет уже проверяется `RestModuleCompositionTest`.

Новый artifact учитывается в BOM, build manifest/dependency order, D1 surface и
artifact measurements. Backend/Vaadin starters его транзитивно не подключают;
приложение делает явный opt-in. Jackson/MVC/Security версии берутся из managed
стека; второй JSON mapper или обновление Boot ради адаптера не вводятся.

В `RestModuleCompositionTest` точечно пересматривается прежнее правило
«нет MODULE_API»: допускается только reviewed gateway/schema/result seam.
Запрет HTTP/Vaadin/app dependencies в `platform-rest` сохраняется. Новый
`RestWebModuleCompositionTest` проверяет optional web dependency closure,
auto-configuration и registry нового artifact.

Включение — `platform.rest.web.enabled=true`, default **false**. Оно требует
enabled server read (`platform.rest.read.enabled`) и исправных security/read
collaborators; противоречивая конфигурация останавливает startup. Наличие
`UserDetailsService`/`PasswordEncoder` или согласованного AuthenticationProvider
проверяется; web-модуль не создаёт default пользователя или пароль.

Без artifact/при disabled property нет REST controller и его mappings.
При пустом каталоге generic `RestReadController` и его mappings остаются:
он маршрутизирует только явно опубликованные пары resource/major и не открывает
JPA типы автоматически. Ни одна пара не опубликована, поэтому аутентифицированный
запрос получает 404, а анонимный — 401 до проверки каталога.
При enabled namespace неизвестные адреса всё равно дают контрактные 401/404,
через namespace security/error handling, без перехода к Vaadin login.

## 4. HTTP-контракт v1

### 4.1. Маршруты

| Операция | URL |
| --- | --- |
| LIST | `/api/v{major}/{resource}` |
| DETAIL | `/api/v{major}/{resource}/{id}` |

Общий controller разрешает только `(resource, major)` из immutable каталога;
не выполняет поиск class/entity name и не обращается к repository напрямую.
Ресурс соответствует builder pattern `^[a-z][a-z0-9-]*$`. Major — положительное
десятичное число без ведущих нулей; неканонический/неизвестный major или путь
даёт 404 после authentication. Он не обозначает глобальную версию платформы.

Поддерживаемый метод — GET. Для аутентифицированного запроса к опубликованному
адресу остальные методы, включая HEAD/OPTIONS, дают 405 с `Allow: GET`, без
business read. Это явная политика первого профиля: автоматические MVC HEAD/OPTIONS
не обходят её. Неизвестный ресурс даёт 404, анонимный вход — 401.

### 4.2. LIST query

| Параметр | Семантика |
| --- | --- |
| `fields` | Одно comma-separated значение публичных aliases; отсутствие = resource default; обязательный id добавляется |
| `page` | Целый int ≥ 0; default 0 |
| `size` | Целый int; отсутствие/0 = resource default 50; фактический диапазон 1..min(resource max, 200) |
| `sort` | Один объявленный sort alias; отсутствие = resource default, затем id ASC tie-break |
| Направление в `sort` | `sort=codeSpec,asc` или `sort=codeSpec,desc`; без суффикса ASC; отдельного параметра direction нет |
| Объявленные фильтры | `journalId` (Long/EQUALS) или `typeNom` (String/EQUALS); разные фильтры соединяются AND |

Существующие reserved names builder (`fields,page,size,sort`) сохраняются.
Неизвестный query key, повтор singleton key, пустой `fields`, пустой sort,
пустой token/дубликат alias, более одного sort key или неизвестный суффикс — 400.
Нельзя молча выбрать первое/последнее значение из multi-value query. Для String
фильтра пустая строка является буквальным значением EQUALS; отсутствие параметра
означает отсутствие фильтра. Строка `null` не становится SQL NULL.

Парсинг стандартного URL encoding выполняется один раз. String filter не trim'ится
и не переводится в enum; numeric значения принимают только знаковую десятичную
целочисленную запись без пробелов/дроби/exponent, с проверкой диапазона. Overflow
не превращается в усечённое число. BOOLEAN — только `true/false`, DECIMAL —
согласованная десятичная запись в BigDecimal без перехода через double, если такие
фильтры присутствуют в реально разрешённом каталоге.

Окно — существующее `platform.rest.read.max-window`, default/final pilot W=10 000:
`(long) page * effectiveSize + effectiveSize <= W`. Оно не обрезает visible total.
Для обеих сущностей `199/50` и `49/200` допустимы; `200/50` и `50/200` — 400.
Unpaged чтения, count-only и обхода окна через другой HTTP параметр нет.

### 4.3. DETAIL query/id

Разрешён только `fields`. LIST paging/filter/sort параметры здесь дают 400
после API/entity authorization. Long/Integer id разбирается строго в разрешённый
Java тип по фактам каталога; String id остаётся строкой. Составной id/association
id не появляются через новый codec. UUID/date hints не расширяют поддержанные
каталогом identifier types. Для двух пилотов id — Long.

### 4.4. JSON

LIST сохраняет форму нормализованного `RestPageResult`:

```json
{
  "content": [{"id": 123, "codeSpec": "SP-001", "draft": null}],
  "page": 0,
  "size": 50,
  "totalElements": 1,
  "totalPages": 1
}
```

DETAIL имеет envelope `{"data":{"id":123,"codeSpec":"SP-001"}}`.
Для пустого list — content `[]`, totalElements/totalPages 0, корректные page/size.
Nullable выбранное поле присутствует с null; незапрошенное поле отсутствует.
Порядок object keys не является wire-контрактом, порядок элементов content — является.
Оба ответа — `application/json`; XML/HTML, entities, proxies, class/type metadata
и рекурсивные associations не сериализуются.

LONG/INTEGER передаются JSON numbers (int64/int32), DECIMAL — точным JSON number,
BOOLEAN — boolean, STRING — string. Клиент должен сохранять точность int64/decimal;
перекодирование id в строку для отдельных клиентов не допускается внутри v1.
Contract cases включают Long > 2^53 и дробный BigDecimal, без double conversion.
Builder допускает hints UUID/DATE/DATE_TIME, но нынешний executable catalog
принимает только format NONE; этап 4 не расширяет этот профиль temporal/UUID
mapping. Nullable presence проверяется
через фактический managed Jackson стек, независимо от глобальной NON_NULL настройки.

Ответы и ошибки защищённого namespace имеют `Cache-Control: no-store`.
ETag/conditional GET, ссылки next/prev и новые envelope поля в этот срез не входят.

### 4.5. Неизменность версии

Имена/типы/nullability/форматы, defaults/maximum, фильтры/сортировки и envelope
фиксируются за resource major по консервативной политике родителя. Расширение
контракта, включая доступное новое поле, требует нового major/API grants.
Resource v2 не наследует автоматически grants v1. Server operational window
документируется отдельно; его нельзя менять в обход повторной cost проверки.

## 5. Authentication и последовательность исполнения

### 5.1. Первый транспортный профиль

Для текущего приложения принят **HTTP Basic поверх TLS**: существующие
`UserDetailsServiceImpl`, `PasswordEncoder`, enabled user и отдельная сервисная
учётка для каждого внешнего потребителя. В тестах допустим локальный HTTP.
TLS termination/trusted proxy на deployment настраиваются явно; пароли не
передаются через query и не записываются в логи. Новый реестр API tokens не нужен.

Новая `SecurityFilterChain` — `@Order(2)`, после существующей `@Order(1)` JPQL preview chain,
до Vaadin fallback, с собственным matcher только versioned REST namespace
`/api/v{major}/**`, включая неизвестные major/resource. Проверяется реальная
семантика matcher Spring Security 7; широкого `/api/**` перехвата нет.

STATELESS, NullSecurityContextRepository и отсутствие saved request. Cookie
Vaadin session не является credential для REST; Basic header обязателен при
каждом запросе. Form login redirects отсутствуют. 401 содержит
`WWW-Authenticate: Basic realm="rest-read"` и Problem Details даже для XHR.
Authentication/roles не подменяются `user`, `X-User`, filter или route parameter.

CSRF изменение ограничено этой read-only цепочкой; unsafe methods не выполняют
никаких действий и возвращают 405 после authentication. Vaadin/UReport/JPQL
цепочки сохраняют свою политику. Cross-origin browser access не включается:
без permissive CORS/credentials; машинному потребителю CORS не требуется.

### 5.2. Порядок

1. HTTP authentication: отсутствующий/неверный credential или disabled user — 401.
2. Разрешение canonical route и опубликованного `(resource, major)` — 404.
3. Проверка GET для существующего ресурса — иначе 405 без business read.
4. API operation и root entity C5; субъект тот же, что в RlsCurrentUser — 403.
5. Строгий parsing и validation query/id/fields/window — 400.
6. Attribute/reference C5 для фактически запрошенного, filter и effective sort — 403.
7. Canonical C5/RLS read; scalar projection внутри read-only transaction.
8. Serialization scalar DTO после транзакции, без SQL.

Синтаксически некорректный request target, отвергнутый servlet container/firewall
до application security, может дать ранний 400. Матрица 401/403 до parsing
относится к запросам, дошедшим до адаптера, а не к невозможному HTTP framing.

Нельзя использовать MVC `@PathVariable Long`/`@RequestParam int` и автоматическое
преобразование до шага 4. Controller получает сырые path/query strings и
multi-value query, без Spring Data Pageable/Specification binding.

### 5.3. Узкий gateway и API-review

Предлагаемый MODULE_API — `RestReadGateway` и необходимые immutable
request/result/schema DTO. Gateway выполняет authentication/resource/API/entity
gate, затем синхронно вызывает typed request factory/parser с разрешёнными schema
facts; выполняет остаток pipeline в той же read-only transaction. Например:

```java
gateway.list(resource, major, contract -> parser.list(contract, rawQuery));
gateway.detail(resource, major, contract -> parser.detail(contract, rawId, rawQuery));
```

`contract` содержит только нужные codecs/aliases/limits, без исходных JPA paths
и repositories. Окончательные сигнатуры закрепляются в 4.0 API-review перед кодом.
Parser работает в `platform-rest-web`; gateway не получает Servlet/Security web
types. INTERNAL `RestReadService` остаётся внутренним исполнителем.

Нормализованные DTO/result можно повысить до MODULE_API только в явно принятом
узком наборе; каталожные INTERNAL types целиком не становятся API. Не нужен
второй самостоятельный authorization engine в controller. API/root gate не
дублируется из-за factory; каноническая повторная entity проверка на read boundary
сохраняется для отзыва прав. Callback не получает право обходить её или RLS.

Gate тесты: malformed id/page/filter + нет API/entity → 403; тот же malformed
input с grants → 400; anonymous + любой такой input → 401; unknown resource →
401/404 по субъекту. На отказах 400/403 нет business SQL.

## 6. RLS без HTTP session

`RlsReadableIdsCache` сейчас session-scoped. Включить STATELESS только в security
chain недостаточно: приложение всё ещё может создать session при обращении к бину.
Поэтому это самостоятельный пакет 4.3, обязательный для сквозного pilot.

В `platform-rls` вводится узкий readable-ids resolver seam; `RlsFilterActivator`
потребляет его, а существующий UI session cache остаётся default implementation.
Web integration выбирает для REST кеш, ограниченный одним запросом, с тем же
`AccessService`, username/version semantics; для UI делегирует прежнему scoped
бину. Имена/сигнатуры seam и совместимость конструкторов — API-review 4.0.

Выбор HTTP-контекста принадлежит web integration, а не core entity metadata.
Не следует глобально заменять UI session cache request scope или сохранять
permissions между HTTP запросами. На REST пути session bean не разыменовывается.
Request state очищается на success и всех error paths; sync MVC достаточно.
Async dispatch в первый профиль не вводится; ERROR dispatch не теряет нужный
security/error контекст и не запускает повторный business read.

Проверить `getSession(false)==null`, отсутствие `Set-Cookie: JSESSIONID`, работу
со входящим чужим/старым cookie и повтором без cookie. Два последовательных и
параллельных субъекта не смешивают ids; отзыв grant/назначения роли действует
в следующем запросе. UI retains session reuse и invalidation checks.

SQL budget HTTP включает отдельные authentication, C5, RLS/roles, content/count
категории. Внутренние 16/12 с прогретым RLS cache не являются полным HTTP budget;
на stateless запросе RLS ids разрешаются заново. Численные пределы для direct/
role и выбранного числа ролей закреплены до итогового прогона 4.6:
[SQL table и границы бюджета](rest-read-api-stage4-46-evidence-20261005.md#2-измеренные-sql-и-закреплённые-пределы).
Максимальные проекции с fixture filters: direct LIST specs/nom 26/21,
DETAIL 25/17; через две назначенные роли пределы LIST 79/59, DETAIL 78/46.
Business SQL и security categories проверяются отдельно, serialization SQL=0.

## 7. Ошибки

Общий формат — Problem Details RFC 9457, `application/problem+json`.
Одна фабрика/writer обслуживает MVC advice, AuthenticationEntryPoint,
AccessDeniedHandler и namespace error handling. Один ControllerAdvice не
покрывает отказы фильтров Spring Security.

| Исход | HTTP / stable code |
| --- | --- |
| `UNAUTHENTICATED`, bad credential/disabled user | 401 / `unauthenticated` |
| `FORBIDDEN` | 403 / `forbidden` |
| `RESOURCE_NOT_FOUND`, `DETAIL_NOT_FOUND`, unknown route/major | 404 / `not_found` |
| `INVALID_REQUEST`, invalid typed values | 400 / `invalid_request` |
| Unsupported method | 405 / `method_not_allowed`, `Allow: GET` |
| Unsupported Accept | 406 / `not_acceptable` |
| `SECURITY_UNAVAILABLE`, установленный отказ security provider/storage | 503 / `security_unavailable` |
| Непредвиденная ошибка исполнения/проекции/serialization | 500 / `internal_error` |

Поля: `type` (в первом профиле `about:blank`), `title`, `status`, безопасный
`detail`, `instance` (path без query), stable `code`, server-generated `traceId`.
400 может включать имя публичного параметра и reason code, без echo secret/value.
Stacktrace, FQN, permission keys, SQL, внутренние property paths и exception
messages напрямую наружу не передаются. Полная диагностика остаётся в серверном логе.

Отсутствующий и RLS-скрытый detail имеют один status/code/title/detail/shape;
уникальный traceId/instance не обещает byte-for-byte равенство разных обращений.
Class-level RLS empty read сохраняет семантику этапа 3: list пуст, detail 404.
Отказ provider не превращается в 200 с пустым content или 404.

500/503 mapping определяется типом установленного отказа; нельзя любой exception
маскировать как invalid_request/security_unavailable. Test seam fault injection
проверяет error mapping отдельно от реальных storage/security app pilots.
Отказ C5/grant store в начальном API gate и ошибка authentication provider также
проверяются: установленная недоступность backend — 503, неверный пароль — 401.
Нормализация этих ошибок не подавляет причину и не ослабляет enforcement.
При ошибке сериализации не отправляется частичный успешный JSON; маленький
ограниченный scalar response формируется до commit HTTP headers.

## 8. OpenAPI и consumer contract

OpenAPI **3.1.1** генерируется из того же validated resource contract, с отдельными
конкретными четырьмя paths двух v1 ресурсов, стабильными operationId, параметрами,
default/max fields, nullable value vs optional property, id codecs, paging/window,
Problem Details, Basic security и status/header матрицей. Схема проекции допускает
только maximum aliases (`additionalProperties:false`); id required, остальные
могут отсутствовать из-за `fields`. Default response примеры закреплены отдельно.

App programmer не пишет второй OpenAPI вручную и не дублирует controller annotations
для каждой сущности. Generator использует reviewed schema facts; не рефлектирует
неопубликованные JPA types. Нет зависимости от Vaadin/display metadata.

Основной результат — детерминированный JSON artifact/snapshot в versioned docs
и JSON contract tests. Swagger UI и публичный каталог ресурсов не требуются;
HTTP endpoint документации в этом срезе не открывается автоматически. Библиотека
schema validation допускается после проверки совместимости с managed Jackson;
подключение springdoc не является необходимым архитектурным условием.

Test snapshots не содержат времени запуска, DB-generated ids или environment host.
Нормализация JSON key order допустима, потеря nullable/default/security facts — нет.
Проверяются реальные success/error bodies против schemas, examples против runtime,
v1 совместимость и условный второй major без расширения v1 grants.

Перед внешним включением согласовать с первым потребителем URL, Basic/TLS,
envelope, id precision, filters/default fields, paging window и handling 400/401/
403/404/503. До согласования контракта разработка работает на закреплённом draft
v1, но публикация и объявление consumer acceptance не выполняются.

## 9. Пакеты реализации

| ID | Работа и основные файлы | Проверяемый результат |
| --- | --- | --- |
| 4.0 | Wire examples/schema facts; review gateway и RLS resolver seam, HTTP priority; входные gates | Зафиксированы сигнатуры/видимость, матрица ошибок и JSON; нет неразрешённого INTERNAL coupling |
| 4.1 | Новый `platform-rest-web/pom.xml`, auto-configuration/imports, BOM/manifest/surface/measurements | Optional artifact без Vaadin/app refs; disabled/empty/non-web contexts; explicit dependencies |
| 4.2 | Gateway, controller, strict parser/codecs, wire DTO | Один read path; parsing после gates; default/selected fields; точные типы/id/window |
| 4.3 | Security chain, Basic handlers, identity bridge checks и request RLS integration | Real credentials, 401/403, no session, роль/отзыв/два пользователя; UI cache не деградировал |
| 4.4 | Problem factory/advice/security writer, namespace/framework errors | Однородные 400/401/403/404/405/406/500/503, no leaks/redirects, trace/headers |
| 4.5 | OpenAPI generator и versioned contract/snapshots | Четыре конкретных paths из деклараций, schemas/examples/error/security parity |
| 4.6 | App real HTTP/H2 pilots обоих ресурсов, SQL/graph budgets, consumer examples | Сеть → authentication → C5/RLS → read → JSON; 1/50/200/W+1, no N+1/session/extra SQL |
| 4.7 | Итоговые owner/app/starter/bootstrap gates, evidence и rollout checklist | Входной З5 закрыт; исходники/JAR/контракт и результаты связаны; статус внешнего gate явно записан |

Порядок: 4.0 → 4.1 → 4.2 → 4.3 → 4.4 → 4.5 → 4.6 → 4.7.
Промежуточные пакеты проверяются изолированно; HTTP opt-in остаётся выключенным
в обычной конфигурации до приёмки. Разработка не требует деплоя или открытия URL
во внешней среде. Новые gateway/schema DTO фиксируются baseline после API-review;
публичные signatures не обновляются автоматически ради зелёного теста.

## 10. Обязательная матрица приёмки

| Сценарий | Ожидание и уровень |
| --- | --- |
| Web artifact absent / disabled / catalog empty | Нет entity handlers; module composition/context tests и app opt-in smoke |
| Anonymous + known/unknown/malformed query | 401 Problem + Basic challenge; никакого business SQL |
| Bad password/disabled account/session cookie only | 401; реальная цепочка, не только mock user |
| Authenticated + unknown resource/major/unpublished JPA type | 404; resource resolution не публикует class |
| No LIST, есть DETAIL / обратный случай | Независимые API grants, 403 только запрещённой операции |
| No entity/id/selected attr/filter attr/effective sort/ref target grant | 403 до business SQL, реальные grants |
| No API/entity + malformed id/page/filter | 403; с grants тот же запрос 400 |
| Fields default/subset/max, unknown/dotted/source/duplicate/empty | Точная shape/id/null; 400 для неразрешённого ввода |
| Unknown/duplicate query, wrong numeric type/overflow/empty numeric | 400; no implicit coercion/first-value choice |
| Page 0/1, size 0/1/50/200, resource smaller max, boundary/overflow | Correct total/order/window, 400 за окном, no business SQL на отказе |
| Разные JOURNAL у двух субъектов | Только свои list/detail; отсутствующий/скрытый id — одинаковый 404 |
| FK filter на скрытую цель / Nomenclature без RLS | Не раскрывает скрытый journal; обязательные C5 остаются для второго ресурса |
| Role grant, лишняя неназначенная роль, отзыв/снятие роли | Реальные назначения; следующий HTTP запрос отражает изменения |
| Success/deny/error/два параллельных субъекта | No HttpSession/JSESSIONID/state leak; next request не reuse чужой cache |
| Unsupported methods/Accept/framework binding/error dispatch | Контрактные 405/406/400/500, без Vaadin HTML и повторного read |
| Provider/storage failure / unexpected error | 503/500, никогда 200/empty/404; owner/web fault test seam |
| Managed JSON nullable/int64/decimal/scalar consume | Присутствие null, точность, 0 SQL во время serialization |
| UI/Vaadin login и `/api/report-jpql/**` | Прежняя цепочка/гранты/формат; новый matcher не перехватывает |
| Unrequested reference в fixed fetch superset | Не требует лишнего grant и не попадает в JSON; ADR graph сохраняется |
| Schema/runtime examples/v1 vs v2 | Совпадают paths/types/defaults/errors; отсутствие автоматических v2 grants |
| SQL 1/50/200, last full page на W+1 строках, LIST/DETAIL maximum | Business ≤1 content+≤1 count LIST, ≤1 content DETAIL; security categories измерены отдельно |

Owner unit/MVC tests могут подменять read gateway для isolated HTTP behavior.
App HTTP pilots используют настоящий provider/storage, C5/roles/RLS и Hibernate/H2;
не подменяют production EntityReadAccess/C5 evaluator/role resolver, не включают
RLS bypass. Авторизованный transport test использует HTTP Basic credentials,
а не только `@WithMockUser`/`.with(user(...))`. Fixture setup SQL исключён.
Отдельно проверяется serializer вне read transaction при OSIV/L2 off.

## 11. Команды и evidence

Новые HTTP tests реализованы в 4.6; их результаты и отдельный build output
зафиксированы в evidence. Команды полного итогового gate ниже выполнены в
4.7. Один JDK 21 и Maven repository используются
последовательно. Установить изменённые modules в manifest dependency order:

```powershell
$restRepo = [IO.Path]::GetFullPath('.local-maven-repository')
# $restMaven/JAVA_HOME задаются по действующему build contract.
& $restMaven "-Dmaven.repo.local=$restRepo" -f platform-rls/pom.xml install
& $restMaven "-Dmaven.repo.local=$restRepo" -f platform-rest/pom.xml install
& $restMaven "-Dmaven.repo.local=$restRepo" -f platform-rest-web/pom.xml install
& $restMaven "-Dmaven.repo.local=$restRepo" -f platform-backend-starter/pom.xml verify
& $restMaven "-Dmaven.repo.local=$restRepo" -f platform-vaadin-starter/pom.xml verify
& $restMaven "-Dmaven.repo.local=$restRepo" '-Dtest=RestReadHttpPilotIT,RestReadHttpSecurityIT,RestReadHttpSessionIsolationIT,RestReadHttpContractIT,RestReadPipelinePilotIT,RestReadRoleMatrixIT,C5GrantAdminParityIT,ReportJpqlPreviewControllerIT' test
& $restMaven "-Dmaven.repo.local=$restRepo" '-Dtest=PlatformApiBaselineTest,PlatformPublicSurfaceTest,PlatformPackageDependencyMatrixTest,PlatformStarterCompositionTest,BootstrapScriptContractTest' test
.\scripts\bootstrap-local-dependencies.ps1 -LocalRepository $restRepo -ValidateOnly
.\scripts\bootstrap-local-dependencies.ps1 -LocalRepository $restRepo
```

При изменении core/autoconfigure/BOM они также устанавливаются в правильном
dependency order до web/app. Проверка существующей JPQL chain входит в новый
HTTP security IT и прежние report preview security tests. Точное имя прежнего
набора — `ReportJpqlPreviewControllerIT`; состав прогонов фиксируется явно. Полный application verify включает `*Test` и `*IT` через действующую конфигурацию Surefire; адресные команды используют `-Dtest`. Выполнение подтверждено XML reports, а не одной компиляцией.

Evidence сохраняет Git revision/dirty scope, source fingerprints, JAR hashes и
runtime classpath, toolchain/DB, команды, counts и exit codes, wire/OpenAPI snapshot,
SQL categories и budgets, graph paths/loaded state, отсутствие sessions и
пример клиентского запроса. Logs сохраняются вне очищаемого target либо их
необходимая доказательная часть переносится в versioned evidence до clean.

H2 достаточен для функциональной приёмки. На выбранной deployment СУБД повторить
schema/grant и data/security/cost проверки перед открытием; при смене DB повторить
их. PostgreSQL не является обязательным условием этого этапа.

## 12. DoD и включение потребителю

- [x] Входной З5/Gate 3 завершён с новым evidence; результаты полного bootstrap зелёные.
- [x] 4.0: wire contract, gateway/RLS seam и surface review закреплены (API-review 2026-10-05).
- [x] 4.1: optional artifact `platform-rest-web` собран с BOM/manifest/surface/baseline/measurements. Первичный каркас прошёл module 11/11 и app 27/27; после ревью исправлена servlet-регистрация фильтров. Текущий общий gate/manifest status приведён в заголовке и evidence: прежний зелёный снимок не заменяет итоговую приёмку.
- [x] 4.2–4.3: gateway/parser/controller и authentication/session isolation реализованы и проверены компонентно; замечания R1–R6 исправлены ([evidence](rest-read-api-stage4-fixes-40-43-20261005.md)). Сквозные pilots и общая приёмка остаются в 4.6–4.7.
- [x] 4.4: единая error matrix, initial C5/authentication/framework faults, no leaks/redirects, headers/trace/log и serialization fallback проверены; owner tests 111/111 + 98/98 ([evidence](rest-read-api-stage4-44-evidence-20261005.md)). Общие app/strict gates этим пакетом не закрыты.
- [x] 4.5: generated OpenAPI 3.1.1 и runtime wire/Problem snapshots зафиксированы; v1 drift gate действует, schemas/examples/numeric precision и conditional v2/grant isolation проверены ([contracts](contracts/rest-read/README.md), [evidence](rest-read-api-stage4-45-evidence-20261005.md)). Consumer/deployment acceptance остаётся отдельным gate.
- [x] 4.6: оба ресурса прошли реальные HTTP/H2 security/role/RLS/window/graph/SQL pilots; 17 HTTP + 33 compatibility tests, 50/50. Исправлена session rotation, закреплены численные direct/two-role budgets; [evidence](rest-read-api-stage4-46-evidence-20261005.md), [инструкция клиента](rest-read-api-client.md). Это функциональный пакет; входные/final gates закрыты в 4.7, deployment gates остаются отдельными.
- [x] 4.7: полный стандартный bootstrap, owner/API/surface/starter/strict и REST HTTP/compatibility gates зелёные; source inventory до/после совпал, installed/target JAR совпали для восьми owners ([evidence 4.7](rest-read-api-stage4-47-evidence-20261006.md)).
- [x] Примеры и инструкция клиента подготовлены; rollout/rollback checklist записан в evidence 4.7.
- [ ] Учётки/grants/TLS, общий C5/C, consumer contract и deployment acceptance подтверждены перед внешним включением.
- [x] Родительский план/roadmap/evidence имеют согласованный статус завершения локального read-среза.

Перед внешним включением отдельно подтвердить общий gate C5/C и deployment
условия. App programmer публикует ресурс декларацией, administrator назначает
учётке API/entity/reference/RLS grants; регистрация ключей сама grants
не выдаёт. Следующий потребитель существующего ресурса не требует Java controller.
Новый ресурс требует декларации, review его версии/прав/стоимости и собственных
contract/security fixtures. Rollback HTTP — выключение web opt-in и отзыв
consumer grants, без ослабления canonical C5/UI прав.

## 13. Первичные источники технических решений

Spring chain matching/order и STATELESS проверены по официальным документам;
они объясняют механизм, а проектные границы и выбранные wire решения заданы выше.

- [Spring Security 7: multiple chains и securityMatcher](https://docs.spring.io/spring-security/reference/7.0/servlet/configuration/java.html).
- [Spring Security 7: persistence/session management](https://docs.spring.io/spring-security/reference/7.0/servlet/authentication/session-management.html).
- [Spring MVC: ProblemDetail/ErrorResponse](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html).
- [RFC 9457: Problem Details](https://www.rfc-editor.org/rfc/rfc9457.html).
- [OpenAPI 3.1.1](https://spec.openapis.org/oas/v3.1.1.html).
