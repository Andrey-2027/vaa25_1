# F-REST-READ-4: исправления ревью 4.0–4.3

- Дата: 2026-10-05, Europe/Moscow.
- Проверялась рабочая копия на HEAD `d11c674` с незакоммиченными REST и параллельными core/register изменениями.
- Основание: [ревью R1–R6](rest-read-api-stage4-review-40-43-20261005.md).
- План: [этап 4](rest-read-api-stage4-http-plan.md).
- Результат: шесть замечаний исправлены, недостающие component integration checks добавлены. Общие app/strict gates пока не зелёные; это не закрытие всего этапа 4.

## 1. Что исправлено

| Замечание | Исправление | Регрессия |
| --- | --- | --- |
| R1, P1 | `DefaultRestReadGateway` допускает CGLIB subclass; read-only `@Transactional` перенесена на `list/detail`. `resolve/publishedResources` не открывают transaction | Настоящий Spring context с `proxyTargetClass=true`: callback/read идут в active read-only transaction, успешные reads commit, parser failure rollback; после возврата transaction отсутствует |
| R2, P1 | Для обоих REST security filters добавлены `FilterRegistrationBean` с `enabled=false` | Реальный Boot `ServletContextInitializerBeans` содержит две отключённые регистрации; security filters продолжают работать внутри REST chain |
| R3, P2 | `RestReadExceptionHandler` ограничен `RestReadController` через `assignableTypes` | 404 другого контроллера сохраняется; REST unexpected failure по-прежнему даёт 500 без раскрытия диагностики |
| R4, P2 | Web startup check больше не использует INTERNAL `RestReadService`; требует MODULE_API gateway. Проверки внутренних read dependencies остаются в `platform-rest` | Enabled context запускается без bean INTERNAL-сервиса при наличии gateway; composition guard читает REST surface и запрещает INTERNAL imports, кроме согласованной configuration ordering ссылки |
| R5, P2 | Namespace parser получает raw URI относительно servlet context path, без повторного decoding | Root и `/app` LIST/DETAIL; canonical/unknown routes; HTTP authentication при context path |
| R6, P2 | Accept parser учитывает все header lines, quality, specificity type/subtype/parameters; JSON исключается наиболее конкретным q=0 диапазоном. Некорректный header/quality даёт 406 | Exact/wildcard exclusions, q=0/positive/invalid/NaN, charset specificity, несколько строк Accept; MVC проверка `application/json;q=0, */*;q=1` даёт 406 |

Дополнительно filter завершает собственные `ServletRequestAttributes` через
`requestCompleted()` перед reset: request destruction callbacks выполняются
на success/failure. Чужие attributes не завершаются этим filter.

Уточнён контракт пустого каталога: generic controller/mappings остаются,
но опубликованных пар нет; anonymous request получает 401, authenticated — 404.
Это не автоматическая публикация JPA типов.

## 2. Недостающие проверки 4.3

Новый `RestReadIdentityRlsIntegrationTest` использует:

- настоящую Basic authentication и управляемую Spring security chain;
- CGLIB-proxied `DefaultRestReadGateway` и настоящий `RestReadService`;
- `RlsCurrentUser`, который читает текущий Spring Security principal;
- настоящие `RestAwareRlsReadableIdsResolver`, request-scoped cache и `RlsFilterActivator`;
- заменённые storage, AccessService/C5 backend и Hibernate Session. Активатор реально вычисляет и устанавливает `allowedIds`; хранение возвращает строки по установленному набору.

Проверены:

1. Alice/Bob получают разные rows; gateway/C5/RLS получают имя authenticated principal.
2. Повторные activation внутри запроса используют cache; следующий HTTP request заново читает grants.
3. Изменение readable ids и отзыв API/entity grants действуют в следующем request. Denied API precedes malformed query.
4. Два запроса действительно перекрываются: barrier в AccessService гарантирует одновременную работу двух субъектов без смешивания ids.
5. Нет session/JSESSIONID; UI cache не вызывается REST request.
6. После 400/401/403/404/406/500 и success не остаются REST marker, request attributes, authenticated security context или transaction.
7. Упорядоченные JPQL/UI test chains выбираются для своих маршрутов и не видят REST marker. Это managed chain coexistence test, не полноценный Vaadin HTTP pilot.
8. LIST/DETAIL codec сериализует после закрытия transaction.

Это component HTTP integration с реальной security/gateway/RLS механикой,
не SQL приёмка HTTP-пилота. Hibernate SQL/graph/cost сценарии, реальная UI chain
и consumer HTTP pilots остаются в 4.6–4.7. Существующие UI cache tests не заменены.

## 3. Прогоны

JDK 21 (Axiom), Maven из IntelliJ IDEA CE 2025.2.3, offline,
`.local-maven-repository`.

| Прогон | Результат |
| --- | --- |
| `platform-rest install` | 108/108, 0 failures/errors/skipped; JAR установлен |
| `platform-rest-web install`, итоговый source | 87/87, 0 failures/errors/skipped; JAR установлен |
| `RestReadPipelinePilotIT`, H2 | 12/12 |
| `PlatformStarterCompositionTest` | 5/5 |
| API/surface/package/starter + REST pilot | 33 tests, 4 failures, 0 errors: 29 passed |
| Strict `bootstrap -ValidateOnly` | Source drift `platform-core`: остановка до сборки |
| Diagnostic `-ValidateOnly -AllowSourceDrift` | Graph/POM/coordinates/layout проверены, REST fingerprints совпадают; общий drift сохраняется |

REST install завершён 20:33:17, итоговый web install — 20:49:55 по Москве.
App-gates завершились 20:46:58; это серверный H2 pilot и структурные gates,
не приёмка полного HTTP adapter. Последнее уточнение specificity parameters
проверено итоговым web owner прогоном.

Первый app прогон не компилировался из-за устаревшего установленного core API.
Для повторного прогона `platform-core` пересобран/установлен из текущих
исходников с `-Dmaven.test.skip=true`; его код не изменялся этой работой.
Это обновление dependency input, не утверждение о прохождении core tests.

### Незакрытые общие gates

- `PlatformApiBaselineTest`: новый constructor/contribution API `MetadataDrivenAggregateSaveService` не записан в core baseline.
- `PlatformPackageDependencyMatrixTest`: новые edges `org.ipro.register -> events/rls`, `register.config -> crud/data/rls` отсутствуют в reviewed matrix.
- `PlatformPublicSurfaceTest.registryDoesNotCarryTypesTheApplicationNoLongerNames`: несогласованный registry четырёх contribution/delete типов из параллельного потока.
- `PlatformPublicSurfaceTest.documentedMeasurementsMatchTheCode`: `platform-tree-files`, expected 168 / actual 162.
- Strict fingerprint drift: core/autoconfigure; диагностическая проверка также показывает несовпадения внешних `dynamicreports`/`ureport3` с алгоритмом v1. Внешние inputs/fingerprints не изменялись. Причину этих двух несовпадений следует установить отдельно, включая зависимость v1 от культуры процесса.

Эти контракты не переписаны автоматически ради зелёного результата.
Полный bootstrap/verify не выполнялся; 4.4–4.7 не объявлены завершёнными.

## 4. Идентичность и локальные logs

В `scripts/local-dependencies.json` обновлены только fingerprints двух REST owners:

| Owner | Source fingerprint v2 | Files | JAR SHA-256 |
| --- | --- | --- | --- |
| platform-rest | `e34c1e6d9b3cf639fac91df8220d9116572b9d0c083524687698d441e27843e1` | 60 | `ee18291995b65a41019c0f05753509462f32c46b3ca62399b7ca4b8ccaca7110` |
| platform-rest-web | `77cf00163004a0676814b457badff37b4fd9d5d5f4e0f17fd144f3b3f0484a25` | 33 | `df90ceae1e894729a31a11beb25a9eb282568d5599f6971eba6ef9587727b4df` |

Logs находятся в `target/rest-stage4-review-40-43` и удаляются при clean:
`fix-rest-tests.log`, `fix-web-tests.log`, `fix-app-gates.log`,
`fix-core-dependency.log`, `fix-strict-validation.log`,
`fix-diagnostic-validation.log`. Результаты owner tests воспроизводятся
`mvn --offline -Dmaven.repo.local=<workspace>/.local-maven-repository -f <owner>/pom.xml install`.

После согласования параллельных core/register inputs повторить app/strict gates.
Продолжение 4.4–4.7 требует полного error/OpenAPI/HTTP pilot acceptance
по исходному плану.
