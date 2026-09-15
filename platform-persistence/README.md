# platform-persistence

Persistence-срез этапа D2. Первые срезы проверяли границу на декларациях и на runtime-бинах;
этот отвечает на риск, который карта D1 (§3.5) назвала самым неприятным: **entity и
репозитории регистрируются централизованно**, поэтому вынесенный артефакт может потерять свои
persistence-типы — и непонятно, упадёт ли это громко.

## Что здесь лежит

| Тип | Роль |
|---|---|
| `org.ipro.crud.BaseEntity` | `@MappedSuperclass` база сущностей платформы (id, аудит, версия) |
| `org.ipro.jr.dom.JrxmlTemplate` | сущность шаблона `.jrxml` |
| `org.ipro.jr.JrxmlTemplateRepository` | Spring Data репозиторий шаблона |
| `org.ipro.persistence.config.PersistenceAutoConfiguration` | свои `@EntityScan` и `@EnableJpaRepositories` |

`BaseEntity` поехал вместе с сущностью не случайно: **entity не выносится без своей базы**.
Это первое, что выяснилось на срезе, — 44 файла дерева зависят от `BaseEntity`, и пока он
оставался в приложении, модуль просто не компилировался.

## Как модуль регистрируется

```java
@AutoConfiguration
@EntityScan("org.ipro.jr.dom")
@EnableJpaRepositories(basePackages = "org.ipro.jr")
public class PersistenceAutoConfiguration { }
```

Плюс свой `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
Приложение больше не перечисляет пакеты модуля — ни в `@EntityScan`, ни в центральном хабе
репозиториев.

**Две независимые декларации `@EnableJpaRepositories` не перекрывают друг друга.**
Это не предположение: на срезе проверено кодом и тестом — в проекте три декларации
(приложение для `org.ip`, платформенный хаб и этот модуль), и `PersistenceRegistrationIT`
требует наличия репозитория из **каждого** объявленного пакета. Если бы декларации
перекрывались, приложение просто не поднялось бы.

## Что показали негативные эксперименты

| Что отключено | Что произошло |
|---|---|
| imports-файл модуля (модуль не представился) | старт падает: `No qualifying bean of type 'org.ipro.jr.JrxmlTemplateRepository'` |
| только `@EntityScan` (entity вне persistence unit) | старт падает: `Not a managed type: class org.ipro.jr.dom.JrxmlTemplate` |

Потеря регистрации оказалась **громкой**, но это свойство среды, а не гарантия: оба отказа
пришли из того, что репозиторий нужен другому бину и создаётся сразу. При ленивой
инициализации или при доступе к entity только через `EntityManager` отказ переехал бы в
рантайм. Поэтому рядом стоит детерминированный `PersistenceTypeRegistrationTest`: он сверяет
**объявления**, а не последствия — каждый `@Entity` обязан попадать в объявленный
`@EntityScan`-пакет, каждый `JpaRepository` — в объявленный `@EnableJpaRepositories`-пакет.

## Зависимости

```
application ──► platform-persistence ──► crudui-core (BaseEntity → IdentifiableEntity)
```

Внешние: `crudui-core`, `jakarta.persistence-api`, `jakarta.validation-api`, `spring-data-jpa`,
`spring-boot-autoconfigure`, `spring-boot-persistence` (в Boot 4 `@EntityScan` живёт отдельным
модулем), `hibernate-core`. Набор reviewed — проверяется тестом.

## Сборка

```bash
mvn -o -f platform-persistence/pom.xml clean install
mvn -o verify
```

Порядок зафиксирован в манифесте воркспейса: модуль зависит от внешнего проекта `crudui`,
поэтому собирается после него и до приложения.
