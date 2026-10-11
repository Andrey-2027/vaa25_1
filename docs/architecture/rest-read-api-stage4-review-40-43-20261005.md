# F-REST-READ-4: ревью пакетов 4.0–4.3

- Дата: 2026-10-05, Europe/Moscow.
- Состояние замечаний после ревью: R1–R6 исправлены; [проверки и ограничения](rest-read-api-stage4-fixes-40-43-20261005.md). Ниже сохранён исходный результат ревью до исправлений.
- Git HEAD: `d11c674`; проверялась рабочая копия с незакоммиченными REST и другими изменениями.
- План: [этап 4](rest-read-api-stage4-http-plan.md).
- Принятые сигнатуры/семантика: [API-review 4.0](rest-read-api-stage4-api-review-20261005.md).
- Область: 4.0–4.3. Полная матрица обработки ошибок 4.4 не является условием этого ревью;
  уже включённые handlers проверялись на влияние на другие каналы приложения.

## 1. Вывод и статус пакетов

Основные компоненты реализованы: optional artifact, gateway/schema MODULE_API,
строгий parser, scalar JSON codec, Basic chain, request RLS resolver/cache.
Проверки трёх модулей прошли **212/212**. Однако это пока не завершённые 4.0–4.3:
есть два блокера запуска/изоляции и четыре дополнительных дефекта контракта
или модульной границы. Runtime-воспроизведения ниже показали то, чего не
проверяют существующие owner tests с mocked gateway и вручную добавленной chain.

| Пакет | Оценка | Остаток до закрытия |
| --- | --- | --- |
| 4.0 | Контракт/API-review подготовлены | Соблюсти INTERNAL/MODULE_API границу в реализации; согласовать описание empty catalog и его фактические mappings |
| 4.1 | Artifact/BOM/manifest/surface и module composition реализованы | Проверить реальный application startup; отключить servlet auto-registration security-only filters; повторить app gates после восстановления общего build |
| 4.2 | Parser/codecs/controller/gateway реализованы | Исправить proxy failure и проверить read-only transaction на настоящем Spring bean, parsing после C5 gate, serialization после transaction |
| 4.3 | Basic/filter chain/request cache реализованы | Исправить влияние фильтров на UI/JPQL, context path и Accept; доказать identity bridge, выполнение RLS без session и изоляцию запросов |

## 2. Замечания по приоритету

### R1 — P1: final gateway не запускается с транзакционными настройками приложения

Файл: [DefaultRestReadGateway.java:38](../../platform-rest/src/main/java/org/ipro/rest/gateway/DefaultRestReadGateway.java#L38).
Класс объявлен `final` и имеет `@Transactional(readOnly=true)`. Приложение явно
задаёт `@EnableTransactionManagement(proxyTargetClass=true)` в `Application.java:47`.
Spring создаёт CGLIB subclass, что невозможно для final класса.

Контрольный Spring context с таким же transaction mode и реальным gateway
завершился `Cannot subclass final class org.ipro.rest.gateway.DefaultRestReadGateway`.
Это ломает создание gateway при enabled server read, даже до HTTP opt-in.
Owner gateway tests создают объект напрямую/используют mock read service и
не поднимают production transaction proxy, поэтому зелёные tests не ловят отказ.

Исправить: убрать final либо явно обеспечить совместимый локальный механизм
proxy, сохранив принятую транзакционную границу. Не менять глобально proxy mode
приложения ради одного класса. Добавить context test с CGLIB и проверить active
read-only transaction в callback/read, затем отсутствие transaction/SQL при JSON.
Чистые `resolve/publishedResources` также стоит оставить без ненужной DB transaction.
[Ограничения CGLIB](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html).

### R2 — P1: REST-фильтры автоматически становятся глобальными servlet filters

Файл: [RestWebAutoConfiguration.java:172](../../platform-rest-web/src/main/java/org/ipro/rest/web/config/RestWebAutoConfiguration.java#L172)
и следующий bean `restReadNamespaceFilter`.
Оба фильтра объявлены `@Bean`, включены в SecurityFilterChain и не имеют disabled
FilterRegistrationBean. Boot автоматически адаптирует такие beans к servlet
registration, независимо от matcher новой security chain.

`ServletContextInitializerBeans` показал обе enabled регистрации. Воспроизведение
их servlet-прохода для `/login` получило **404, downstream не вызван**:
namespace filter безусловно разбирает любой URI как REST target. Scope filter
также безусловно ставит REST marker, поэтому UI RLS может выбирать request cache
вместо прежнего session cache. Аналогично затрагивается JPQL namespace.

Исправить: отключить servlet registration обоих фильтров, оставив добавление
только внутри соответствующей SecurityFilterChain. Проверять регистрацию через
Boot lifecycle, а не только `.addFilters(FilterChainProxy)` в standalone MockMvc.
После исправления `/login`, UI и `/api/report-jpql/**` должны доходить до прежних
handlers/chains; REST marker не должен появляться на этих путях.
[Spring Security: filter bean registration](https://docs.spring.io/spring-security/reference/servlet/architecture.html#servlet-declaring-your-filter-as-a-bean).

### R3 — P2: REST advice меняет исключения других controllers

Файл: [RestReadExceptionHandler.java:23](../../platform-rest-web/src/main/java/org/ipro/rest/web/problem/RestReadExceptionHandler.java#L23).
`@RestControllerAdvice` не ограничен REST controller/package, а handler
`Exception.class` действует на всё приложение при web opt-in.

Чужой controller `/outside`, бросающий `ResponseStatusException(NOT_FOUND)`,
получил **500 с REST internal_error**, вместо своего 404. Для review это
регрессия изоляции уже включённого компонента, даже если сам пакет 4.4 ещё открыт.

Исправить: ограничить advice нашим controller/его пакетом либо другим строгим
REST selector; оставить чужие ошибки прежним handlers. Добавить regression test
на не-REST controller с собственным статусом. Полную новую error matrix 4.4
это замечание не требует завершать сейчас.

### R4 — P2: web-модуль напрямую зависит от INTERNAL RestReadService

Файл: [RestWebAutoConfiguration.java:64](../../platform-rest-web/src/main/java/org/ipro/rest/web/config/RestWebAutoConfiguration.java#L64).
Startup check импортирует `RestReadService` и получает его через ObjectProvider.
В API-review §3.3 этот тип оставлен INTERNAL, прямо указано, что web его не называет.
Reviewed MODULE_API gateway уже есть, но startup wiring обходит эту границу.

Исправить: проверять готовность через gateway и reviewed configuration contract,
а проверку внутреннего read service оставить внутри `platform-rest`. Если новый
доступ действительно нужен, сначала изменить API-review/роль обоснованно.
Composition test должен проверять roles импортируемых типов, а не только разрешать
все `org.ipro.*`; нынешний whitelist это нарушение не обнаруживает.

### R5 — P2: namespace filter не учитывает servlet context path

Файл: [RestReadNamespaceFilter.java:55](../../platform-rest-web/src/main/java/org/ipro/rest/web/security/RestReadNamespaceFilter.java#L55).
В parser передаётся `getRequestURI()`, содержащий context path. Для приложения,
развёрнутого с `/app`, корректный `/app/api/v1/specifications` разбирается как
неизвестный маршрут. Воспроизведение — **404, downstream не вызван**.

Исправить: разбирать путь относительно servlet context, согласовав его с MVC
и security matcher. Добавить tests root context и `/app` для list/detail,
unknown route и authentication. Декодировать URL повторно не требуется.

### R6 — P2: Accept parser считает q=0 разрешением JSON

Файл: [RestReadNamespaceFilter.java:101](../../platform-rest-web/src/main/java/org/ipro/rest/web/security/RestReadNamespaceFilter.java#L101).
Проверяется только type/subtype; quality и precedence не учитываются.
`Accept: application/json;q=0` проходит namespace filter; затем реальный MVC
controller возвращает **200 с JSON content**, вместо принятого профилем 406.

Исправить: учитывать качество/специфичность media ranges и закрепить cases
`json;q=0`, `*/*;q=0`, explicit JSON exclusion + wildcard, positive quality и
невалидный q. Согласно RFC q=0 означает неприемлемое представление; RFC допускает
игнорирование negotiation сервером, но наш API-review §5.2 выбрал отказ 406 для
несовместимого Accept. Здесь замечание к принятому контракту, не к безусловному
запрету любого 200 со стороны RFC.
[RFC 9110 §12.4.2/§12.5.1](https://www.rfc-editor.org/rfc/rfc9110.html#section-12.4.2).

## 3. Чего ещё не доказали тесты 4.3

- Настоящий Basic subject проходит через реальный gateway и совпадает с
  `RlsCurrentUser`. В `RestReadBasicSecurityIntegrationTest:215` identity возвращает
  `system`, а gateway замокан; 200 не доказывает связку credential → C5/RLS subject.
- Отсутствие HttpSession/JSESSIONID **при выполнении RLS read**. Нынешний Basic
  тест не доходит до production readable-ids resolver; cache tests отдельно
  мокают AccessService/providers и вручную ставят RequestContextHolder/marker.
- Последовательные и параллельные запросы двух субъектов используют разные
  request state; ошибка/401/403 не оставляет state следующему запросу. UI path
  сохраняет свой session cache и invalidation, а REST не разыменовывает его.
- Существующие три цепочки выбираются по реальным requests. Текущая проверка
  порядка только читает `@Order(2)` через reflection; это не regression test
  `/api/report-jpql/**`, UI login и новой REST chain вместе.
- Отзыв роли/grant между HTTP запросами. Серверные role tests этапа 3 полезны,
  но не проверяют новый request cache/identity lifecycle HTTP.

Для компонентных 4.3 tests допустим изолированный storage/read seam. Полные
Hibernate/H2 SQL/graph/consumer pilots остаются в 4.6; их не требуется переносить
целиком в 4.3. Также не требуются сейчас OpenAPI 4.5 или вся error matrix 4.4.

## 4. Выполненные проверки и ограничения

| Прогон | Результат |
| --- | --- |
| `platform-rls` test | 26/26, 0 failures/errors/skipped |
| `platform-rest` test | 107/107, 0 failures/errors/skipped |
| `platform-rest-web` test | 79/79, 0 failures/errors/skipped |
| Изолированные runtime probes | R1/R2/R3/R5/R6 воспроизведены; R4 установлен по imports/roles/API-review |
| Root API/surface/package/starter + REST pilot | Не дошёл до tests: application compile failure в recorder/aggregate WIP |

Owner прогоны завершились 2026-10-05 в 20:08:24/20:08:28/20:08:29 по Москве.
JDK 21 (Axiom), Maven из IntelliJ IDEA CE 2025.2.3, offline,
`.local-maven-repository`. Owner tests использовали installed dependencies;
probe дополнительно ставил текущие `platform-rest/target/classes` и
`platform-rls/target/classes` в начало classpath. Owner artifacts этим ревью
не устанавливались; полный bootstrap не повторялся.

Root compile failure: `RecorderSetContribution`/`RecorderSetContributionParticipant`
и `RegisterAutoConfiguration` не находят `AggregateSaveContribution`,
`AggregateSaveParticipantContext`, `AggregateSaveContributionParticipant`
из текущего параллельного core/register WIP. Это отдельное сборочное препятствие;
его нельзя объявить ни зелёным app gate, ни результатом REST tests.

```powershell
$restRepo = [IO.Path]::GetFullPath('.local-maven-repository')
# $restMaven/JAVA_HOME — по build contract.
& $restMaven --batch-mode --no-transfer-progress --offline "-Dmaven.repo.local=$restRepo" -f platform-rls/pom.xml test
& $restMaven --batch-mode --no-transfer-progress --offline "-Dmaven.repo.local=$restRepo" -f platform-rest/pom.xml test
& $restMaven --batch-mode --no-transfer-progress --offline "-Dmaven.repo.local=$restRepo" -f platform-rest-web/pom.xml test
& $restMaven --batch-mode --no-transfer-progress --offline "-Dmaven.repo.local=$restRepo" '-Dtest=PlatformApiBaselineTest,PlatformPublicSurfaceTest,PlatformPackageDependencyMatrixTest,PlatformStarterCompositionTest,RestReadPipelinePilotIT' test
```

Локальные logs/helper source — `target/rest-stage4-review-40-43`, удалятся при clean.
Runtime helper использует Spring AnnotationConfig context, production gateway,
Boot ServletContextInitializerBeans, production filters/advice/controller,
Mock servlet requests/MockMvc; gateway/storage в HTTP shape probes изолированы.
Это не сетевой app pilot. Helper завершился с exit 0, потому что успешно
воспроизвёл ошибки; его exit code не означает успешную приёмку.

Ключевой вывод helper сохранён здесь:

```text
TX_CONTEXT_FAILURE: Cannot subclass final class org.ipro.rest.gateway.DefaultRestReadGateway
SERVLET_AUTO_FILTER: RestRequestScopeFilter enabled=true
SERVLET_AUTO_FILTER: RestReadNamespaceFilter enabled=true
UI_CONTAINER_FILTER_RESULT: status=404 continued=false
OUTSIDE_ADVICE_RESULT: status=500 code=internal_error
CONTEXT_PATH_RESULT: status=404 continued=false
Q_ZERO_MVC_RESULT: status=200 body={"content":[],"page":0,"size":50,"totalElements":0,"totalPages":0}
```

| Проверенный source | SHA-256 |
| --- | --- |
| `DefaultRestReadGateway.java` | `626efb342f844231ea2919c3cd23f7f11a8d5a3d90a211240cdbb13a1cefe0d8` |
| `RestWebAutoConfiguration.java` | `f7cb2668a1b3473ae46a53f6c43a51d175e505afe5542d2c79be28f1ef0ea75b` |
| `RestReadExceptionHandler.java` | `070411f8b8d3b7af5ba78a5d31d3761707e6a9d9da44ef0325c49b901a5ead28` |
| `RestReadNamespaceFilter.java` | `54faece7246bcdec8842cd9fee11b8e43da91e9954d929982f2017e8455a7a6c` |

## 5. Рекомендуемый порядок завершения

1. R1/R2: восстановить startup и изоляцию фильтров; добавить воспроизводящие regressions.
2. R3/R4: ограничить advice и вернуть web wiring к reviewed gateway API.
3. R5/R6: относительный request path и корректный Accept negotiation.
4. Доказать перечисленные identity/RLS/session/chain сценарии 4.3.
5. После восстановления core/register dependency inputs повторить app-gates
   на текущих owner JAR, согласовать plan/API-review/status/evidence пакетов.

Статус «реализовано» в текущем плане описывает наличие кода. Статус «принято»
для 4.0–4.3 пока не подтверждён. Работы OpenAPI, полной обработки ошибок и
SQL/graph приёмки следующих пакетов не заменяют перечисленные исправления.
