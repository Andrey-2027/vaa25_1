# F-REST-READ-4: API-review пакета 4.0

- Статус: контракт пакета 4.0 принят; реализация 4.1–4.5 выполнена. Текущие проверки и открытые общие gates: [evidence 4.5](rest-read-api-stage4-45-evidence-20261005.md). Пакеты 4.6–4.7 остаются открытыми.
- Дата: 2026-10-05.
- Проверенный Git HEAD: `d11c674`; рабочая копия содержит незакоммиченные изменения других потоков.
- План: [этап 4](rest-read-api-stage4-http-plan.md).
- Вход 4.6–4.7: [ревью этапа 3](rest-read-api-stage3-review-20261005.md); gate З5 открыт (§7).

## 1. Вывод

До написания кода зафиксированы решения, от которых зависят 4.1–4.5:
wire-контракт v1 с примерами по фактическим декларациям `specifications:v1`
(`PrdSpec`) и `nomenclature:v1` (`Nomenclature`), MODULE_API-шов `platform-rest`
(read gateway и immutable schema facts), readable-ids seam в `platform-rls`
и web-интеграция поверх него, приоритет HTTP-цепочек и фильтров, матрица
ошибок RFC 9457 со stable codes и проверяемым precedence.

Роли типов из §3.3 закреплены surface review реализации 4.1/4.2;
имена из §4.2/§5.2 реализованы в 4.2/4.3. Проверки из §7 закрепляют эти
решения; молчаливое отступление от них — изменение wire-контракта.

## 2. Wire-контракт v1

### 2.1. Маршруты, методы и заголовки

| Операция | URL |
| --- | --- |
| LIST | `/api/v{major}/{resource}` |
| DETAIL | `/api/v{major}/{resource}/{id}` |

- Namespace — `v{major}`; `major` — положительное десятичное число без знака и
  ведущих нулей; неканонический (`v`, `v0`, `v01`, `v-1`, `v1x`) или
  неизвестный/неопубликованный `(resource, major)` — 404 после authentication.
- Поддерживается только GET. Всё остальное, включая HEAD/OPTIONS, для
  опубликованного адреса даёт 405 с `Allow: GET` без business read; анонимный
  вход — 401 раньше; неизвестный адрес — 404 раньше. Это осознанная политика
  первого профиля: автоматические MVC-обработчики HEAD/OPTIONS не должны
  принимать это решение.
- Успех — `Content-Type: application/json`; ошибки — `application/problem+json`.
  Все ответы namespace несут `Cache-Control: no-store`. ETag/conditional GET,
  next/prev-ссылки и новые envelope-поля в v1 отсутствуют.
- `fields` на DETAIL ограничен DETAIL maximum; paging/filter/sort параметры на
  DETAIL — 400 после API/entity gate. Пустой id (trailing slash) — 404 (форма
  маршрута), не 400.

### 2.2. LIST query

| Параметр | Семантика v1 |
| --- | --- |
| `fields` | Одно comma-separated значение публичных aliases; отсутствие — resource default; `id` добавляется всегда |
| `page` | Строгий int ≥ 0; отсутствие — 0 |
| `size` | Строгий int; отсутствие/0 — resource default 50; диапазон 1..min(resource max, 200) |
| `sort` | Один объявленный alias с необязательным суффиксом `,asc`/`,desc` (строчные); без суффикса — ASC; отсутствие — resource default, затем id ASC tie-break |
| Объявленные фильтры | `specifications`: `journalId` LONG/EQUALS; `nomenclature`: `typeNom` STRING/EQUALS; разные фильтры — AND |

Строгие отказы (все — 400 `invalid_request`): неизвестный query key, повтор
singleton key (включая фильтр), пустой `fields`, пустой token/дубликат alias,
пустой или более чем один sort key, неизвестный суффикс направления.
Multi-value нельзя «разрешать» выбором первого/последнего значения.

Разбор значений: URL encoding парсится контейнером один раз; String-фильтр — без
trim и без enum; целые — только знаковая десятичная запись без пробелов/дроби/
exponent с проверкой диапазона (overflow — 400, не усечение); пустая строка
String-фильтра — литеральное значение `""`; строковая `null` не становится SQL
NULL. BOOLEAN/DECIMAL применяются, только если появятся в исполняемом каталоге
(сейчас их нет), без перехода через double.

Окно: `(long) page * effectiveSize + effectiveSize <= W`, W = существующее
`platform.rest.read.max-window` (default/pilot 10 000). Для обоих пилотов
`199/50` и `49/200` допустимы, `200/50` и `50/200` — 400 `out_of_window`.
Видимое `totalElements` окно не обрезает; unpaged/count-only режима нет.

### 2.3. JSON-примеры

LIST, defaults (`GET /api/v1/specifications`):

```json
{
  "content": [
    {"id": 1001, "codeSpec": "SP-001", "draft": null},
    {"id": 1002, "codeSpec": "SP-002", "draft": "новая"}
  ],
  "page": 0,
  "size": 50,
  "totalElements": 2,
  "totalPages": 1
}
```

LIST, выборка/сортировка/фильтр
(`GET /api/v1/specifications?fields=codeSpec,nomenclatureCode&sort=codeSpec,desc&journalId=42&size=2`):
content-элементы содержат `id`, `codeSpec`, `nomenclatureCode` (порядок ключей
не контракт; порядок элементов — да); `page`/`size` — эффективные значения.

DETAIL (`GET /api/v1/specifications/1001?fields=codeSpec,draft,comment`):

```json
{"data": {"id": 1001, "codeSpec": "SP-001", "draft": null, "comment": null}}
```

Пустой ресурс (`GET /api/v1/nomenclature` при 0 строк):

```json
{"content": [], "page": 0, "size": 50, "totalElements": 0, "totalPages": 0}
```

Страница за концом данных сохраняет реальные `totalElements`/`totalPages` и
возвращает пустой `content`.

401 (заголовки: `WWW-Authenticate: Basic realm="rest-read"`, `Cache-Control: no-store`):

```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Authentication is required for the REST read API",
  "instance": "/api/v1/specifications",
  "code": "unauthenticated",
  "traceId": "…"
}
```

400 (расширения допустимы только `param` + `reason`, само значение не эхоится):

```json
{
  "type": "about:blank",
  "title": "Invalid request",
  "status": 400,
  "detail": "Unknown query parameter",
  "instance": "/api/v1/specifications",
  "code": "invalid_request",
  "param": "foo",
  "reason": "unknown_parameter",
  "traceId": "…"
}
```

`instance` — path без query.

### 2.4. Типы, nullability и неизменность

- LONG/INTEGER — JSON numbers (int64/int32); DECIMAL — точный JSON number;
  BOOLEAN — boolean; STRING — string. Клиент обязан сохранять точность
  int64/decimal (contract cases включают Long > 2^53 и дробный BigDecimal);
  перекодирование id в строку внутри v1 не допускается.
- Nullable выбранное поле присутствует со значением null; незапрошенное поле
  отсутствует. Presence null не зависит от глобальной NON_NULL-настройки
  приложения: web-модуль сериализует ответ своим кодеком (§6.3).
- Temporal/UUID профиль не расширяется: текущий исполняемый каталог принимает
  только format NONE; hints UUID/DATE/DATE_TIME остаются неисполняемыми.
- Имена/типы/nullability/форматы, defaults/maximum, фильтры/сортировки и
  envelope фиксируются за `(resource, major)`. Новое доступное поле — новый
  major/API grants; v2 не наследует v1-гранты автоматически.

## 3. MODULE_API `platform-rest`: read gateway

### 3.1. Сигнатуры

```java
package org.ipro.rest.gateway;

public interface RestReadGateway {

    /** Опубликованные пары в стабильном порядке; только wire-факты, без Java-типов. */
    List<RestReadResourceSchema> publishedResources();

    /** Разрешение маршрута; пустой Optional — 404-эквивалент, без grants. */
    Optional<RestReadResourceSchema> resolve(String resource, int major);

    /** Authentication/resource/API/entity gates, parser callback и read pipeline в одной read-only transaction. */
    RestPageResult list(String resource, int major, RestListRequestFactory requestFactory);

    RestDetailResult detail(String resource, int major, RestDetailRequestFactory requestFactory);
}

@FunctionalInterface
public interface RestListRequestFactory {
    RestListQuery create(RestListContract contract);
}

@FunctionalInterface
public interface RestDetailRequestFactory {
    RestDetailQuery create(RestDetailContract contract);
}

public record RestReadResourceSchema(String resource, int major,
                                     RestListContract list, RestDetailContract detail) { }

public record RestListContract(
    String resource, int major,
    List<RestFieldFact> maximumFields,
    List<RestFieldFact> defaultFields,
    Map<String, RestFilterFact> filters,
    List<String> sortAliases,
    String defaultSortAlias,                 // null, если default sort не объявлен
    RestSortDirection defaultSortDirection,
    int defaultPageSize,
    int effectiveMaxPageSize,                // min(resource max, 200)
    int maxWindow) { }

public record RestDetailContract(
    String resource, int major,
    List<RestFieldFact> maximumFields,
    List<RestFieldFact> defaultFields,
    RestFieldFact idField) { }

public record RestFieldFact(String alias, RestFieldType type,
                            RestFieldFormat format, RestNullability nullability) { }

public record RestFilterFact(String alias, RestFieldType type,
                             RestFilterOperator operator) { }
```

Контракты не содержат JPA-путей, repositories, `Class`/FQN, `EntityDescriptor`
и INTERNAL-типов каталога; web-парсер и OpenAPI-генератор видят только aliases/
types/limits. `RestFilterFact`/`RestFieldFact` — MODULE_API, потому что их
читает web-модуль.

### 3.2. Семантика и порядок

1. Gateway исполняется как bean `platform-rest` (INTERNAL-класс реализации,
   `@Transactional(readOnly = true)`), без Servlet/Security/HTTP типов.
2. `publishedResources()`/`resolve()` — чистые schema-операции без grants и без
   обращения к JPA-типам; они обслуживают namespace-фильтр и OpenAPI (§5, 4.5).
3. `list`/`detail`: authentication (subject == RlsCurrentUser) → resolve 404 →
   API grant 403 → root entity C5 403 → parser callback (400/406-семантика
   значений) → остаток pipeline тем же `RestReadService` (attribute/reference
   C5 403, canonical read, scalar projection) → возврат `.data`/page-результата.
4. Callback не получает прав обходить повторную entity-проверку read boundary
   или RLS: pipeline остаётся существующим `RestReadService`; двойная
   проверка API/entity на gateway и на read boundary — осознанная (отзыв прав).
5. Парсер, бросивший web-исключение, не перехватывается gateway: исключение
   всплывает, транзакция read-only откатывается без записи.
6. Сериализация выполняется вне транзакции (§6.3), SQL после возврата gateway
   не допускается.

### 3.3. Роли и видимость типов

| Тип | Роль 4.0 | Действие при реализации |
| --- | --- | --- |
| `org.ipro.rest.gateway.*` (gateway, schema, contracts, facts, factories) | **MODULE_API** (новые) | добавить в `platform-rest-surface.txt` и signature baseline |
| `RestListQuery`, `RestDetailQuery`, `RestPageResult`, `RestDetailResult` | INTERNAL → **MODULE_API** | перенос роли; сигнатуры не меняются |
| `RestReadOutcome`, `RestReadException` | INTERNAL → **MODULE_API** | перенос роли; web маппит outcome → HTTP (§6) |
| `RestReadService`, `RestScalarProjector`, `RestFilterSpecificationCompiler`, `RestResourceCatalog`/catalog types, `RestReferenceAuthorizationValidator`, `C5RestOperationRegistrar`, auto-configurations | INTERNAL | без изменений; web их не называет |

`RestModuleCompositionTest` точечно пересматривается: reviewed набор получает
ровно перечисленные MODULE_API-типы, любой другой MODULE_API — падение.
Запрет HTTP/Vaadin/app-зависимостей в `platform-rest` сохраняется. Новый пакет
`org.ipro.rest.gateway` вносится в `platform-package-matrix.txt` только с
разрешёнными рёбрами (api/catalog/service/rls/c5); implementation-класс
gateway — INTERNAL и в baseline не публикуется.

### 3.4. Wiring

`RestReadAutoConfiguration` объявляет gateway bean (`@ConditionalOnMissingBean`)
при `platform.rest.read.enabled=true`; gateway получает catalog, `RestReadService`,
`RlsCurrentUser`, `C5PermissionEvaluator` и `maxWindow`. Пустой каталог —
легальное состояние: `publishedResources()` пуст, `resolve` пуст, HTTP-адрес
любого ресурса даёт 404; никакой JPA-тип не публикуется. При непустом каталоге
отсутствие любого collaborator — fail-fast (существующие правила модуля).

## 4. RLS readable-ids seam (пакет 4.3)

### 4.1. Интерфейс и совместимость

```java
package org.ipro.rls;

@FunctionalInterface
public interface RlsReadableIdsResolver {
    /** null — доступ без ограничений по измерению (wildcard-грант). */
    List<Long> getReadableIds(String dimension, String username);
}
```

- `RlsReadableIdsCache implements RlsReadableIdsResolver`; session-scope и
  семантика (AccessService, username, AccessGrantVersion) не меняются — это
  default UI-реализация.
- Оба конструктора `RlsFilterActivator` (3- и 4-аргументный) принимают
  `RlsReadableIdsResolver` вместо конкретного класса; существующие вызовы
  с `RlsReadableIdsCache` компилируются без правок.
- `RlsAutoConfiguration` объявляет default resolver bean
  `@ConditionalOnMissingBean(RlsReadableIdsResolver.class)`, делегирующий
  session cache; bean `rlsReadableIdsCache` остаётся как есть. Два resolver-бина
  (например, при ошибке конфигурации) останавливают startup, а не выбираются
  молча.

### 4.2. Web-интеграция (INTERNAL `platform-rest-web`)

- `RestAwareRlsReadableIdsResolver` — resolver-bean web-модуля,
  `@AutoConfigureBefore(RlsAutoConfiguration.class)`: REST-запрос обслуживает
  request-scoped `RestReadableIdsCache` (те же AccessService/username/version
  semantics), UI-запрос делегирует session cache через `ObjectProvider` (бин
  разыменовывается только на UI-пути).
- `RestRequestScopeFilter` — первый фильтр REST-цепочки (до authentication
  filters): ставит request-атрибут REST-контекста и снимает его в `finally` на
  success и всех error paths. Так исключается разыменование session bean даже
  при чтениях во время authentication.
- `RestReadableIdsCache` — `@RequestScope`, очищается контейнером вместе с
  запросом; async dispatch в первый профиль не вводится.
- Выбор HTTP-контекста принадлежит web-модулю; core entity metadata и UI cache
  не меняются. На REST-пути `getSession(false) == null`, `Set-Cookie:
  JSESSIONID` отсутствует, входящий чужой/старый cookie и повтор без cookie
  работают; два последовательных и параллельных субъекта не смешивают ids;
  отзыв grant/роли действует в следующем запросе; UI session reuse и
  инвалидация сохраняются.

## 5. HTTP-цепочки и приоритет

### 5.1. Цепочки

| Цепочка | Order | Matcher |
| --- | --- | --- |
| `jpqlPreviewApiChain` (существующая, приложение) | 1 | `/api/report-jpql/**` |
| REST read chain (web-модуль, `platform.rest.web.enabled=true`) | 2 | `/api/v*/**` |
| Vaadin fallback (существующая, приложение) | default (LOWEST) | остальное |

REST chain: `authorizeHttpRequests(anyRequest().authenticated())`,
`SessionCreationPolicy.STATELESS`, `NullSecurityContextRepository`, request cache
выключен (saved request не создаётся), CSRF выключен только в этой read-only
цепочке, CORS не включается, form-login redirects отсутствуют. Basic realm —
`rest-read`; 401-content — Problem Details даже для XHR. `/api/report-jpql/**`
продолжает обслуживаться цепочкой 1; приоритет проверяется реальным прогоном
существующих report-preview security tests. Широкого `/api/**`-перехвата нет:
не-versioned пути остаются на прежних цепочках.

### 5.2. Фильтры namespace (порядок, закреплённый для тестов)

1. `RestRequestScopeFilter` — до authentication filters; ставит/снимает
   REST-атрибут (§4.2).
2. `RestReadNamespaceFilter` — после `AuthorizationFilter`, т.е. только для
   прошедших authentication:
   1. `Cache-Control: no-store` на response;
   2. `RestRequestTargetParser` разбирает raw путь относительно context path приложения
      (без дополнительного URL decoding; LIST/DETAIL, канонический
      major, непустой id) и вызывает `gateway.resolve(...)`; неизвестная/
      неопубликованная/неканоническая цель — Problem 404;
   3. метод ≠ GET (включая HEAD/OPTIONS) — Problem 405 + `Allow: GET`;
   4. Accept несовместим с `application/json` (допустимы `*/*`, `application/*`,
      `application/json` с параметрами) — Problem 406. Quality определяется наиболее
      конкретным совпавшим диапазоном: `application/json;q=0, */*;q=1`
      запрещает JSON; quality 0 и некорректные quality/header значения дают 406;
   5. иначе — запрос идёт дальше в MVC.

Precedence матрицы (анонимный вход раньше всего; 404 раньше 405 раньше 406
раньше grants) фиксируется тестами. MVC `HandlerMapping` для namespace получает
только GET-формы уже прошедших фильтр запросов; автоматические HEAD/OPTIONS
MVC решений не принимают.

### 5.3. MVC-правила controller

- Один общий controller; маршрутизация только по `(resource, major)` из
  каталога, без поиска class/entity name и без repository.
- Path variables — `String`; query — сырая multi-value карта контейнера
  (`Map<String, String[]>`), парсится один раз; никаких `@PathVariable Long`,
  `@RequestParam int`, Pageable/Specification-биндинга, `@Valid`-DTO.
- Controller вызывает gateway с parser-callback; успех сериализуется кодеком
  (§6.3); ошибки парсинга/гейтов маппятся Problem-фабрикой.

## 6. Матрица ошибок

### 6.1. Статусы и stable codes

| Исход | HTTP / code | Примечания |
| --- | --- | --- |
| Missing/bad credential, disabled user, session-cookie без Basic, `UNAUTHENTICATED` pipeline | 401 / `unauthenticated` | `WWW-Authenticate: Basic realm="rest-read"` |
| `FORBIDDEN`: API/entity/attribute/reference/RLS grants | 403 / `forbidden` | без business SQL на отказе |
| Неизвестный route/major/resource, `RESOURCE_NOT_FOUND`, `DETAIL_NOT_FOUND`, RLS-скрытый detail | 404 / `not_found` | отсутствующий и скрытый detail неразличимы по shape |
| `INVALID_REQUEST`, типизированные значения, window | 400 / `invalid_request` | см. reason codes |
| Неподдержанный метод (вкл. HEAD/OPTIONS) | 405 / `method_not_allowed` | `Allow: GET` |
| Неподдержанный Accept | 406 / `not_acceptable` | |
| `SECURITY_UNAVAILABLE`: отказ C5/grant store/security provider/storage | 503 / `security_unavailable` | в т.ч. authentication provider outage |
| Непредвиденная ошибка исполнения/проекции/сериализации | 500 / `internal_error` | |

Класс-level RLS empty read сохраняет семантику этапа 3: list пуст, detail 404.
Установленная недоступность backend никогда не маскируется под 200/empty/404.
500/503 определяются типом установленного отказа, а не «любым exception».

### 6.2. Problem-поля и reason codes

Одна фабрика/writer обслуживает MVC advice, AuthenticationEntryPoint,
AccessDeniedHandler и namespace-фильтры (один ControllerAdvice отказы фильтров
не покрывает). Поля: `type` (v1 — `about:blank`), `title`, `status`, безопасный
`detail`, `instance` (path без query), stable `code`, server-generated
`traceId`; для parser 400 дополнительно `param` (безопасный публичный alias)
и `reason`. Для framework/gateway 400 эти расширения необязательны;
сырое имя неизвестного параметра и его значение не отражаются в ответе.
Stacktrace, FQN, permission keys, SQL, внутренние пути и exception messages
наружу не передаются; полная диагностика остаётся в логе.

Reason codes 400: `unknown_parameter`, `duplicate_parameter`, `unknown_field`,
`duplicate_field`, `empty_fields`, `unknown_filter`, `invalid_filter_value`,
`invalid_page`, `invalid_size`, `out_of_window`, `unknown_sort`,
`multiple_sort_keys`, `invalid_sort_direction`, `invalid_id`. Набор расширяем
только вместе с contract/OpenAPI snapshot.

AuthenticationEntryPoint различает отказ: `AuthenticationServiceException`/
инфраструктурная причина → 503, credential/disabled/anonymous → 401.
Классификация проверяет причины исключения: установленный инфраструктурный
отказ не маскируется внешней оболочкой denial/credential failure. Начальный
C5 gate нормализуется серверным owner в `SECURITY_UNAVAILABLE`; ошибки
программирования и нарушения целостности остаются 500. Framework-ошибки
до выбора MVC handler и исключения authentication chain покрываются
resolver/filter только внутри маркированного REST request.

### 6.3. Serialization без частичного JSON

Успешный scalar-response сериализуется web-кодеком на базе managed
`ObjectMapper` (копия с принудительным ALWAYS-inclusion, чтобы null присутствовал
независимо от глобальной NON_NULL) и отдаётся только после успешной
сериализации — частичный успешный JSON при ошибке сериализации не отправляется.
Платформенные DTO не получают Jackson-аннотаций. Сериализация идёт вне read
transaction; SQL во время сериализации — 0.
Problem также полностью сериализуется до записи ответа. При отказе основного
Problem-кодека используется независимый закрытый scalar-кодек для безопасного
500; это не Spring bean и не кодек успешных ответов. `traceId` этого ответа
связывается с полной серверной диагностикой. Уже committed ответ не переписывается.

## 7. Входные gates и что закрепляют 4.1–4.5

- Входной gate З5 остаётся открытым: последний полный application clean verify —
  20 failures + 22 errors в 14 классах, доминирует полный C5 read
  ([evidence §12](rest-read-api-stage3-completion-evidence.md)). Пакет 4.0 его
  не закрывает; изолированные 4.1–4.5 допустимы, сквозная приёмка 4.6–4.7 —
  только после зелёного bootstrap.
- 4.1 обязан добавить `RestWebModuleCompositionTest` и контекстные проверки
  disabled/empty-catalog/non-web/противоречивой конфигурации; обновить BOM,
  manifest, surface/baseline, matrix и artifact measurements одним пакетом,
  иначе гейты (PlatformApiBaselineTest выводит артефакты из manifest) краснеют.
- 4.2 обязан закрепить §2 и §3 тестами: parser/codecs incl. overflow/empty/
  duplicate/window, MVC-срез с подменённым gateway, gate-порядок
  `403-before-400`, `401` на анониме для любого входа.
- 4.3 обязан закрепить §4 и §5: реальные credentials, disabled user,
  отсутствие сессии/JSESSIONID, чужой cookie, два субъекта, отзыв, приоритет
  цепочек против `/api/report-jpql/**` и Vaadin fallback, единственный
  resolver-бин.
- 4.4 обязан закрепить §6: полную матрицу, fault-injection 500/503, отсутствие
  redirects/HTML, error dispatch без повторного business read.
- 4.5 обязан сгенерировать OpenAPI 3.1.1 ровно из этих schema facts
  (`gateway.publishedResources()`) и закрепить snapshot/contract tests;
  v1 drift gate и отсутствие автоматических v2-грантов.
