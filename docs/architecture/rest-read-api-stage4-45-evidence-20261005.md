# F-REST-READ-4.5: OpenAPI и snapshots

- Дата: 2026-10-05.
- Статус: реализация и компонентный contract gate 4.5 завершены; общий strict/acceptance gate остаётся открытым.
- Контракт: [план §8](rest-read-api-stage4-http-plan.md#8-openapi-и-consumer-contract), [API-review](rest-read-api-stage4-api-review-20261005.md).
- Результаты и команды экспорта: [reviewed contracts](contracts/rest-read/README.md).

## 1. Реализация

`RestOpenApiGenerator` находится в `platform-rest-web`, роль INTERNAL.
Он получает только `gateway.publishedResources()` — reviewed immutable schema facts
validated каталога; не обращается к JPA classes/paths, metadata UI или read service.
Экспорт использует собственный закрытый mapper, стабильный порядок и LF; не зависит
от настроек response mapper, host, часов или данных БД.

В enabled web-контексте generator зарегистрирован как bean, disabled/non-web
конфигурации остаются неактивными. Новый HTTP endpoint не добавлен; документация
поставляется JSON artifact. Новых runtime dependencies/APP_API/MODULE_API нет.
Reviewed surface содержит 17 INTERNAL top-level types; artifact measurement
увеличен на один production Java source, web fingerprint — 37 inputs.

OpenAPI [3.1.1](https://spec.openapis.org/oas/v3.1.1.html) описывает четыре concrete
paths `specifications:v1`/`nomenclature:v1`, стабильные operationId и Basic security.
Отдельные list/detail schemas включают maximum aliases, default examples,
`additionalProperties:false`, обязательный id и nullable type unions.
Все типы сохраняют runtime JSON encoding, включая LONG > 2^53 и точный DECIMAL number.
Query параметры отражают fields CSV/unique selection, equality filters, sort,
default/max size и finite window. Lexical numeric grammar и межпараметрическое
ограничение окна явно описаны extensions/текстом, проверяются parser tests.

Problem schemas и status/header matrix покрывают 400/401/403/404/405/406/500/503,
безопасные parser extensions, traceId и no-store; challenge только 401, Allow только
405. Nullability значения не превращается в обязательность присутствия свойства.
Temporal/UUID hints не расширяют исполняемый профиль.

## 2. Contract gate

- `RestReadPipelinePilotIT.openApiAndWireSnapshotsMatchValidatedApplicationCatalog`
  экспортирует схему из настоящего startup catalog и сравнивает её с reviewed
  OpenAPI. Читает через gateway/parser/C5/RLS/read service с H2, сериализует production
  codec; 12 cases: default/id-only/maximum для четырёх операций.
- Wire snapshot нормализует только fixture strings/id values, сохраняя поля,
  типы, null, envelope/paging. Это read/serialization pilot; полный HTTP/H2
  security/SQL budget acceptance остаётся в 4.6.
- Owner tests сверяют application snapshots с schemas и default examples,
  проверяют настоящий codec/MVC/parser/Problem writer, все статусы/headers,
  отрицательные payloads (id absent/null/string, extra aliases, wrong nullability,
  decimal string, overflow), executable scalar types и точность чисел.
- Условный v2 не меняет v1 paths/schemas; настоящий gateway при grants только
  v1 отклоняет v2 list/detail до parser. Генерация каталога grants не выдаёт.
- Обычный прогон не перезаписывает snapshots. Явный `rest.contract.write=true`
  разрешён для первоначального экспорта/согласованного изменения; после него
  требуется обычный прогон. V1 drift нельзя закрывать автоматической перегенерацией.

Schema assertions в owner tests поддерживают все validation keywords, выпускаемые
generator, и отвергают неизвестные; это ограниченная проверка данного schema
профиля, не универсальная OpenAPI validation library. Новые зависимости не добавлены.

## 3. Проверки

JDK 21 Axiom, Maven IntelliJ IDEA CE 2025.2.3, offline local repository.

| Прогон | Результат |
| --- | --- |
| `platform-rest-web install`, обычный прогон | 108/108, 0 failures/errors/skipped; JAR установлен, 21:49:12 Москва |
| Application export из установленного JAR | 1/1; 21:51:00 Москва |
| Owner contract check на финальных snapshots без write | 10/10; 21:52:00 Москва |
| Обычный H2 REST read pilot без snapshot write | 13/13; 21:52:58 Москва |
| API/surface/package/starter, отдельный build directory | 21 tests, 4 failures, 0 errors; 17 passed; 21:56:19 Москва |
| Starter composition в составе structural gate | 5/5 |
| Strict `bootstrap -ValidateOnly` | Остановка на source drift `platform-core` |
| Diagnostic `-ValidateOnly -AllowSourceDrift` | Graph/POM/coordinates/layout проходят; REST fingerprints совпадают, общий drift остаётся |

Первоначальные промежуточные прогоны выявили две ошибки тестовой v2 fixture:
отсутствие обязательных default fields и unstubbed resource key. Исправлены;
изменения production read/security механизма для прохождения теста не требовались.

### Общие незакрытые gates

В изолированном structural прогоне повторились прежние четыре расхождения:
core API baseline (`MetadataDrivenAggregateSaveService` constructor/contribution),
register package edges (events/rls, config → crud/data/rls), registry четырёх
contribution/delete типов и D1 `platform-tree-files` (документ 162, код 168).
Эти контракты не переписаны пакетом 4.5 ради зелёного результата. Новые нарушения
REST API/surface/package границ не обнаружены; owner surface/closure проверены.

Первый обычный app запуск выполнил только 13 H2 tests: architecture test classes
исчезли из общего `target/test-classes`, compiler metadata указывала на другую
выборочную компиляцию. Поэтому его BUILD SUCCESS не принят как успех общего gate.
Для достоверного повтора создан временный POM с теми же dependencies/source/test
configuration и отдельным build directory `target/rest-stage4-45/app-validation`;
его единственные изменения — build output directory и сохранение штатного пути
runtime dependency tree для starter guard. Все четыре structural класса реально
выполнены, результат 17/21. Временный POM после прогона удалён.

## 4. Идентичность

| Owner | Source fingerprint v2 | Files | JAR SHA-256 |
| --- | --- | --- | --- |
| platform-rest-web | `346375322c277e562e07de12b6ec894f1ce5ce671ee0169c0cae404e2645f26f` | 37 | `2a9525946db8e2e1d290b5e7cf7b311c9c2a81d9ca3d8b199c2c3c20d25b545d` |

Серверный REST source не менялся: fingerprint
`5bae2f6ceb08af06fb38c85ef2060e62a926c3b48147cdf1fcf5751bce682d7f`, 60 inputs.
Manifest обновлён только для web owner. Локальные логи находятся в
`target/rest-stage4-45`, удаляются при clean. Полный bootstrap/verify не выполнен;
общий этап 4 и C5/C этим пакетом не закрыты. Следующие пакеты: 4.6 реальные
HTTP/H2 pilots и 4.7 итоговые gates.

Логи итоговых прогонов: `web-tests.log`, `contract-export.log`,
`contract-check.log`, `app-gates.log`, `structural-gates.log`, `strict-validation.log`,
`diagnostic-validation.log`. Промежуточные export logs не заменяют итоговый
прогон без write. Diagnostic validation также показывает drift autoconfigure
и внешних DynamicReports/UReport3; их inputs не менялись в 4.5.
