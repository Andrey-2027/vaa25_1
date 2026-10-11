# REST read v1: reviewed contracts

Эти снимки принадлежат F-REST-READ-4.5 и проверяются обычным запуском тестов.
Изменение снимка требует review версии ресурса; перегенерация сама по себе
не разрешает менять опубликованный v1.

| Файл | Источник |
| --- | --- |
| [openapi-v1.json](openapi-v1.json) | `RestOpenApiGenerator.generate(gateway)` из schema facts валидированного каталога приложения; OpenAPI 3.1.1 |
| [wire-v1.json](wire-v1.json) | Реальные H2 gateway/parser/read service результаты приложения, сериализованные production codec: default/id-only/maximum для четырёх операций |
| [problems-v1.json](problems-v1.json) | Production Problem writer: восемь статусов, headers, безопасные поля и parser reason; только traceId нормализован |

OpenAPI различает maximum/default fields для list/detail, присутствие свойства
и nullable значение. `id` обязателен, остальные aliases могут отсутствовать;
`additionalProperties:false` не позволяет расширить проекцию случайным полем.
DECIMAL остаётся точным JSON number, LONG — int64 number, без перекодирования
в строку. Temporal/UUID форматы не добавляются в исполняемый каталог.

Нестандартные ограничения запроса отражены как extensions/описания:
`x-max-window`, запрет неизвестных/повторных параметров и `x-wire-pattern` для
lexical grammar numeric id/filter. Стандартная JSON Schema не выражает
межпараметрическое условие `page * effectiveSize + effectiveSize <= maxWindow`;
его проверяет runtime parser. `size=0` означает default. Фильтры только EQUALS,
STRING literal сохраняет empty/`null`; DETAIL принимает только `fields` и path id.

Снимки не содержат host, времени запуска или DB-generated ids. Wire normalization
заменяет fixture строки на `example` и id/journalId на 1, сохраняя поля, JSON типы,
null, envelope и paging. Default examples OpenAPI сравниваются с wire snapshot.
Порядок полей декларации сохраняется; ресурсы/major/filter maps сортируются.
Экспорт и сравнение нормализуют CRLF в LF.

## Воспроизведение

Использовать JDK 21 и Maven с подготовленным local dependency workspace.
Обычные tests не перезаписывают reviewed snapshots:

```text
mvn --offline -Dmaven.repo.local=<workspace>/.local-maven-repository -f platform-rest-web/pom.xml install
mvn --offline -Dmaven.repo.local=<workspace>/.local-maven-repository -Dtest=RestReadPipelinePilotIT test
```

Для первого экспорта либо согласованного изменения контракта:

```text
mvn --offline -Dmaven.repo.local=<workspace>/.local-maven-repository -Dtest=RestReadPipelinePilotIT#openApiAndWireSnapshotsMatchValidatedApplicationCatalog -Drest.contract.write=true test
mvn --offline -Dmaven.repo.local=<workspace>/.local-maven-repository -f platform-rest-web/pom.xml -Dtest=RestOpenApiGeneratorTest#runtimeProblemSnapshotIsStableAfterTraceNormalization -Drest.contract.write=true test
```

После экспорта обязательно выполнить обычный прогон без `rest.contract.write`.
V1 drift gate сравнивает актуальный generated OpenAPI и wire с reviewed файлами;
owner tests проверяют schemas/examples/Problems, негативные payload cases,
точность чисел и изоляцию v1/v2 grants. Проверка JSON Schema ограничена всеми
validation keywords, которые выпускает данный generator; неизвестный keyword
роняет тест. Это не универсальный validator произвольного OpenAPI документа.

Generator не выполняет чтений и не проверяет/выдаёт grants. Администратор
назначает API/entity/reference/RLS grants отдельно; attribute grants для
опубликованных полей не требуются ([ADR-0016](../../decisions/ADR-0016-rest-resource-field-access.md)). Для conditional
v2 нужны собственные API grants: v1 права не наследуются автоматически.
Swagger UI, публичный каталог и HTTP endpoint документации не добавлены.
HTTP/H2 security/SQL pilots реализованы в 4.6: 12 reviewed wire cases проверяются
через настоящий socket, int64 > 2^53 — через storage/HTTP/client parser.
[Evidence 4.6](../../rest-read-api-stage4-46-evidence-20261005.md),
[инструкция клиента](../../rest-read-api-client.md). Итоговая приёмка 4.7 остаётся
открытой; внешнее включение ждёт не C5 (он закрыт в объёме операций 2026-10-10,
[план C5](../../c5-operation-permissions-plan.md)), а общий verification package этапа D
(D3.6/D3.8) и PostgreSQL-приёмку.

Нормативные источники: [OpenAPI 3.1.1](https://spec.openapis.org/oas/v3.1.1.html),
[план этапа 4](../../rest-read-api-stage4-http-plan.md).
