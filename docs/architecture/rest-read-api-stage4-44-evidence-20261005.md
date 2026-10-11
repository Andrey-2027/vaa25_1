# F-REST-READ-4.4: единые ошибки HTTP

- Дата: 2026-10-05.
- Статус: реализация и компонентная проверка 4.4 завершены. Общая приёмка этапа 4 остаётся открытой.
- Контракт: [план §7](rest-read-api-stage4-http-plan.md#7-ошибки), [API-review §6](rest-read-api-stage4-api-review-20261005.md).
- Предыдущий пакет: [исправления 4.0–4.3](rest-read-api-stage4-fixes-40-43-20261005.md).

## 1. Реализация

`RestProblemFactory` классифицирует семантические read/parser/security/framework
исключения и их причины; циклическая cause chain ограничивается identity set.
Установленная инфраструктурная причина имеет приоритет над внешним denial.
Неизвестные исключения и ошибки программирования остаются 500.

| HTTP | code | Условие |
| --- | --- | --- |
| 400 | `invalid_request` | Parser, invalid read request, framework binding |
| 401 | `unauthenticated` | Anonymous, invalid credentials, disabled account |
| 403 | `forbidden` | Отказ доступа |
| 404 | `not_found` | Неизвестный ресурс/версия, отсутствующий или скрытый detail |
| 405 | `method_not_allowed` | Метод вне GET; `Allow: GET` |
| 406 | `not_acceptable` | Неподдерживаемое представление |
| 503 | `security_unavailable` | Установленная недоступность security/auth/storage |
| 500 | `internal_error` | Programming/projection/serialization failure |

Серверный owner нормализует начальный C5 configuration failure и известные
отказы хранилища в `RestReadException(SECURITY_UNAVAILABLE)`, сохраняя cause.
Обработаны также Hibernate connection/JPA timeout исключения plain EntityManager
read path, где Spring repository translation может отсутствовать.
В web owner нет зависимости от Hibernate: connection/timeout причины распознаются
по стандартным JDBC типам и SQLState класса `08`, обозначающему connection failure
([Java 21 JDBC](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/SQLNonTransientConnectionException.html)).
Нарушение целостности или SQL grammar error не превращаются автоматически в 503.

Один `RestProblemWriter` обслуживает scoped MVC advice, REST-only resolver,
namespace filters и security entry points. Resolver покрывает MVC ошибку до
выбора handler; request-scope filter покрывает исключение authentication provider
до MVC. Оба ограничены REST chain/маркером. Изоляция UI/JPQL и disabled servlet
registrations сохранены; MODULE_API/APP_API и зависимости артефакта не расширены.

## 2. Безопасность и целостность ответа

- Problem имеет `application/problem+json`, `about:blank`, безопасные title/detail,
  status/code, instance без query и новый серверный UUID `traceId`.
- Parser 400 может содержать стабильный reason и безопасный публичный param;
  значение и произвольное сырое имя query parameter не отражаются.
- `WWW-Authenticate: Basic realm="rest-read"` появляется только на 401;
  `Allow: GET` — только на 405. Ответы имеют `Cache-Control: no-store`, без redirect.
- Missing и RLS-hidden detail дают одинаковый 404 contract (кроме instance/traceId).
- Ответ полностью сериализуется до записи. При отказе success-кодека получается
  Problem 500; при отказе самого Problem-кодека независимый закрытый scalar mapper
  готовит полный резервный 500. Он не является Spring mapper bean и не используется
  для успешных данных. Буфер сбрасывается до записи, committed ответ не переписывается.
- 500/503 логируются с тем же traceId и исходным Throwable/cause; сообщения,
  permission keys, SQL, FQN и stacktrace отсутствуют в JSON.

## 3. Проверки и границы evidence

JDK 21 Axiom, Maven IntelliJ IDEA CE 2025.2.3, offline, локальный repository.

| Прогон | Результат |
| --- | --- |
| `platform-rest install` | 111/111; 0 failures/errors/skipped, JAR установлен; 21:25:53 Москва |
| `platform-rest-web install` | 98/98; 0 failures/errors/skipped, JAR установлен; 21:29:03 Москва |
| App API/surface/package/starter + H2 REST read pilot | 33 tests, 5 failures, 0 errors; 28 passed, завершение 21:33:35 Москва |
| H2 REST read pilot (в составе app-gates) | 12/12 |
| Адресный повтор `PlatformStarterCompositionTest` | 5/5, завершение 21:34:55 Москва |
| Strict `bootstrap -ValidateOnly` | Остановка на source drift `platform-core` |
| Diagnostic `-ValidateOnly -AllowSourceDrift` | Graph/POM/coordinates/layout проверены; оба REST fingerprints совпадают |

`RestProblemHttpIntegrationTest` использует настоящий managed MVC/Security
контекст и production security chain через MockMvc. Fault injection покрывает
матрицу статусов, приоритеты, authentication provider, framework error до выбора
handler, success/Problem serialization и отсутствие session/утечек.
Storage gateway заменён тестовым источником отказов: этот тест не доказывает SQL
budget или полный HTTP/H2 pilot 4.6.

`RestReadIdentityRlsIntegrationTest` проверяет реальный Basic → CGLIB gateway →
read service → RLS activator/request cache при замещённых C5/storage границах:
ранний отказ C5 до parser/read, восстановление следующего запроса и missing/hidden
404. `RestProblemWriterTest` проверяет cause wrapping/cycles, headers, fallback,
committed response и совпадение traceId с полной диагностикой в Logback.
Серверные gateway/service fault tests отдельно проверяют сохранение причины,
включая raw Hibernate/JPA outage; отказ не маскируется пустым успешным результатом.

Четыре подтверждённых общих расхождения совпадают с предыдущим пакетом:

- Core API baseline не содержит constructor/contribution API
  `MetadataDrivenAggregateSaveService`.
- Reviewed package matrix не содержит register → events/rls и
  register.config → crud/data/rls.
- Surface registry содержит четыре contribution/delete типа, которые scanner
  application references не находит.
- Измерение `platform-tree-files`: expected 168 / actual 162.

Пятое падение в общем прогоне — starter runtime tree без `platform-rest-web`,
при наличии прямой зависимости в текущем `pom.xml`. Адресный повтор заново
сгенерировал runtime tree и прошёл 5/5 без изменений кода/POM/test.
Это расхождение generated input в общем рабочем `target`; параллельная работа
в checkout могла перезаписать его. Общий прогон не объявлен зелёным на основании
адресного повтора. REST API/surface нарушений этот прогон не выявил.

Диагностический bootstrap допускает общий drift core/autoconfigure и внешних
DynamicReports/UReport3 (v1); это не зелёный strict gate. Эти inputs/fingerprints
не менялись пакетом 4.4. Полный bootstrap/verify не выполнен.

## 4. Идентичность поставки

Обновлены fingerprints только двух REST owners в `scripts/local-dependencies.json`.

| Owner | Source fingerprint v2 | Files | JAR SHA-256 |
| --- | --- | --- | --- |
| platform-rest | `5bae2f6ceb08af06fb38c85ef2060e62a926c3b48147cdf1fcf5751bce682d7f` | 60 | `6f4ff434fca1b3bf2182c133c5cde6457ab309dc8cc038e62d3457a8969cc284` |
| platform-rest-web | `001fa9ccaafbd178bb415c20aed901427340af469e6504d33ed9f97d089a9262` | 35 | `e91df0c8c57f9fe699b7c74e1520ec88b030085011066c1eec9917d17499ba55` |

Локальные логи: `target/rest-stage4-44/{rest-tests,web-tests,app-gates,starter-recheck,strict-validation,diagnostic-validation}.log`.
Они удаляются при clean. Owner tests воспроизводятся командой
`mvn --offline -Dmaven.repo.local=<workspace>/.local-maven-repository -f <owner>/pom.xml install`.

Далее по плану: 4.5 OpenAPI/snapshots, 4.6 реальные HTTP/H2 pilots, 4.7 итоговые
gates; общие source/API/surface/package расхождения нужно согласовать перед
сквозной приёмкой. Закрытие 4.4 не закрывает C5/C или весь этап 4.
