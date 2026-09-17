# platform-core

Backend платформы: generic CRUD, доступ к данным, метаданные, поиск, fetch-планы и фильтры.

## Что здесь означает «core»

«Без UI и без кода приложения», а не «чистый Java». Spring, JPA и `jakarta.validation` в этом
модуле допустимы и понадобятся: сюда переезжает именно исполнение, а не контракты.

Запрещено:

* **Vaadin** — UI-инфраструктура живёт в `platform-vaadin`. Как только Vaadin появляется здесь,
  backend перестаёт подключаться к не-UI потребителю, а «вынос платформы» превращается в «вынос
  приложения целиком»;
* **`org.ip`** — приложение зависит от платформы, а не наоборот. Это же правило держат
  `CoreModuleCompositionTest` (в модуле) и `PlatformCoreModuleTest` (в приложении);
* **авто-конфигурация до D3.4** — пакеты `org.ipro.*.config` остаются в дереве приложения до
  отдельного шага, поэтому модуль объявляется в POM приложения явно, а не через стартер.
  Это временное состояние, названное в плане D3, а не целевое.

## Текущий срез

`org.ipro.metadata.facet` — read-model переопределений надписей (`FacetKey`, `FacetKind`,
`FacetResolver`, `FactSource`, `ResolvedValue`). Пять типов, которые не знают ничего, кроме
`java.util`.

Выбран первым не потому, что важен, а потому что **не зависит ни от чего**: на нём проверяется
весь конвейер выноса — новый проект в манифесте, топология бутстрапа, зависимость приложения на
артефакт вместо `target/classes`, гейты владельца — до того, как в модуль переедут 78 классов,
которые нельзя разделить.

## Почему нельзя переносить по одному пакету

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
порядку classpath, а не по замыслу. Поэтому первая настоящая группа — 78 классов сразу.

Зависимые, но не входящие в цикл:

| Пакет | Рёбра | Порядок |
|---|---|---|
| `org.ipro.search` | → data, fetch, metadata | после цикла |
| `org.ipro.filter` | → metadata | после цикла |
| `org.ipro.security` | ни одного | лист, может ехать когда угодно |

## Тесты

Тесты едут с модулем, если модуль может их собрать сам. Тест, которому нужны прикладные
фикстуры (`org.ip.model.*`), остаётся в приложении: он проверяет интеграцию платформы с
фикстурами приложения, и переносить его в модуль можно только переписав фикстуры на модульные.
Таких тестов на весь оставшийся объём D3.3 — 30 из 56, и это отдельная работа, а не побочный
эффект переноса.
