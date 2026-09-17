# platform-core

Backend платформы: generic CRUD, доступ к данным, метаданные, поиск, fetch-планы и фильтры.
**92 production-типа** в пакетах `org.ipro.crud`, `data`, `fetch`, `filter`, `metadata`, `search`,
`security`.

## Что здесь означает «core»

«Без UI и без кода приложения», а не «чистый Java». Spring, JPA и `jakarta.validation` в этом
модуле допустимы: сюда переехало исполнение, а не контракты.

Запрещено:

* **Vaadin** — UI-инфраструктура живёт в `platform-vaadin`. Как только Vaadin появляется здесь,
  backend перестаёт подключаться к не-UI потребителю, а «вынос платформы» превращается в «вынос
  приложения целиком»;
* **`org.ip`** — приложение зависит от платформы, а не наоборот. Это же правило держат
  `CoreModuleCompositionTest` (в модуле) и `PlatformCoreModuleTest` (в приложении);
* **авто-конфигурация до D3.4** — пакеты `org.ipro.*.config` остаются в дереве приложения до
  отдельного шага, поэтому модуль объявляется в POM приложения явно, а не через стартер. Это
  временное состояние, названное в плане D3, а не целевое.

## Поверхность: роли, а не «всё public»

У каждого production-типа ровно одна reviewed-роль, записанная в
`src/test/resources/platform-core-surface.txt` (`PlatformCoreSurfaceTest`):

| Роль | Сколько | Что означает |
|---|---|---|
| `APP_API` | 28 | поддерживаемый API приложения: приложение называет тип, платформа его не наследует |
| `APP_SPI` | 4 | `BaseService`, `InternedEntity`, `HasDisplayName`, `GlobalSearchProvider` — приложение реализует или расширяет |
| `MODULE_API` | 44 | контракт между platform-модулями (сегодня их читает production-код будущих autoconfigure/Vaadin/report) |
| `INTERNAL` | 16 | реализация без обещания совместимости: менять и удалять можно свободно |

Доступ приложения к типам с ролью не `APP_*` — отдельная ортогональная запись, а не роль:
legacy-ссылки production-кода заморожены в D1-реестре `platform-public-surface.txt`, ссылки
тестов — в overlay `test-usage` того же реестра ролей. Оба списка только сокращаются.

Роль — reviewed-решение, а не вывод теста. Черновик воспроизводится инструментом
`node scripts/analyze-platform-surface.mjs --core-registry`; решения по спорным типам записаны в
шапке реестра.

## Почему нельзя было переносить по одному пакету

Замер рёбер (без `config`, которые остаются в дереве):

```text
metadata -> crud   MetadataResolver -> crud.StandardCatalogEntity / StandardDocumentEntity
                   SectionMetadataRegistry -> crud.TableSectionService
                   ManagedEntityCatalog -> crud.ValidationException
crud -> metadata   13 файлов
crud -> data       LookupService -> data.CanonicalReadExecutor / DetailRead / ListRead / LookupRead
data -> crud       CanonicalEntityService -> crud.BaseService, CanonicalWriteExecutor -> crud.*
crud -> fetch      BaseService -> fetch.plan.FetchScenario, LookupService -> fetch.plan.FetchScenario
fetch -> metadata  InstanceNameResolver, FetchPlanRegistry
```

`metadata`, `crud`, `data` и `fetch` образуют один цикл, а пакет нельзя перенести частично:
типы одного пакета в двух артефактах — это split package, который компилятор различает по
порядку classpath, а не по замыслу. Поэтому группа переносилась целиком: 78 классов в одном
шаге, зависимые `search` (12) и `filter` (2) — следом, `security` (1) — как лист.

## Тесты

Тесты переехали вместе с production-классами, когда модуль может собрать их сам: модуль даёт
**116 тестов** (архитектурные проверки состава, поведение перенесённых сервисов, `*Test` и `*IT`).

Что осталось в приложении и почему:

* тесты, которым нужны прикладные фикстуры (`org.ip.model.*`) или Spring-контекст приложения —
  они проверяют интеграцию платформы с реальными сущностями и настройками, а не поведение
  платформы. Переписать их фикстуры на нейтральные модели можно, но это отдельная работа, а не
  побочный эффект переноса;
* тесты конфигураций (`org.ipro.*.config`) — уезжают в D3.4 вместе с конфигурациями.

Границы модуля проверяются с обеих сторон: `CoreModuleCompositionTest` (§4 теста) внутри модуля и
`PlatformCoreModuleTest` (§5 тестов) в приложении — последний проверяет по `CodeSource`, что класс
приходит из артефакта, а не из `target/classes` приложения.
