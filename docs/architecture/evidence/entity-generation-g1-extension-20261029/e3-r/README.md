# E3.R — runtime-контракт владельца, без UI

Дата: 2026-10-29. Результат: **E3.R выполнен**; E3.A/E3.C/E3.B остаются открытыми.

Реализована только runtime-часть: объявление capability, startup-диагностики, resolved
факты в descriptor/runtime projection, обязательность и неизменяемость владельца в
canonical/named/aggregate путях и явный owner-контекст команды. P8, формы, Vaadin,
draft/JSON, generator/builder, C/D и браузер не менялись.

## Контракт

| Решение | Реализация |
| --- | --- |
| Объявление | `@CatalogOwner(property=...)` в `platform-contracts`, `TYPE`/`RUNTIME`, без `@Inherited` |
| Resolved descriptor | `org.ipro.metadata.CatalogOwnerDescriptor(property, targetTypeId)` |
| Portable facts | `RuntimeDesignSnapshot.CatalogOwnerFacts`, nullable `TypeFacts.owner` |
| Startup-коды | `CATALOG_OWNER_PROPERTY_MISSING`, `_ASSOCIATION_INVALID`, `_TARGET_INVALID`, `_REQUIRED_MISMATCH`, `_DUPLICATE`, `_SUBJECT_INVALID` — severity ERROR |
| Обязательность | `@ManyToOne(optional=false)` + явный `@JoinColumn(nullable=false)` + `@NotNull` + отсутствие `FieldMetadata(required=OPTIONAL)` |
| Guard | `CatalogOwnerGuard` создаётся executor'ом и aggregate save, не необязательный Spring-бин |
| Доверенное предсостояние | JPQL-проекция `owner.id` с `FlushModeType.COMMIT`, сравнение Long-id |
| Две фазы | preflight до нумерации/хуков/событий, recheck после callbacks и перед записью |
| Контекст | `CatalogOwnerContext` (тип + id владельца, scope потока); несовпадение — отказ |

## Точки подключения

- `MetadataConsistencyValidator` разбирает owner-capability для всех metadata-типов и строк
  секций; ошибка останавливает старт через существующий `MetadataConsistencyStartupCheck`.
- `EntityDescriptorCatalog` публикует `EntityDescriptor.owner`; прежние конструкторы
  `EntityDescriptor` сохранены как overload с `owner=null`.
- `EntityDesignRuntimeProjection` — единственный мост platform → design: `CatalogOwnerFacts`
  строится из descriptor'а, DTO другой стороны не импортируются.
- `CanonicalWriteExecutor`: preflight после capability/C5/RLS и загрузки предсостояния, до
  нумерации; recheck после `publishSaving`/`publishUpdating` (lifecycle hooks) и перед
  `persist`/`merge`. Named-путь снимает токен до mutation и проверяет после неё и после хуков.
- `MetadataDrivenAggregateSaveService`: preflight до `openAggregateOperation`,
  `publishAggregateSaving` и `beforeAggregateSave`; recheck после callbacks перед
  `saveAggregate`. Полный конструктор получает `EntityManager`; slice-контур без JPA
  сохраняет прежнюю проводку, а owner-enabled update в нём получает явный отказ
  `STATE_UNREADABLE`, а не пропуск проверки.

## Проверки

| Suite | Tests | Failures/errors/skips |
| --- | ---: | --- |
| `CatalogOwnerResolverTest` | 19 | 0/0/0 |
| `CatalogOwnerWriteIT` | 13 | 0/0/0 |
| `CatalogOwnerAggregateTest` | 3 | 0/0/0 |
| `platform-core` целиком | 286 | 0/0/0 |
| `CatalogOwnerFactsBridgeTest` | 3 | 0/0/0 |
| `entity-design-runtime` целиком | 5 | 0/0/0 |
| `platform-spring-boot-autoconfigure` | 41 | 0/0/0 |
| `platform-contracts` целиком | 6 | 0/0/0 |

```bash
export JAVA_HOME='C:/Program Files/Axiom/AxiomJDK-21'
export PATH="/c/maven/bin:$JAVA_HOME/bin:$PATH"
mvn -o -f platform-contracts/pom.xml test
mvn -o -f platform-core/pom.xml test
mvn -o -f entity-design-runtime/pom.xml test
mvn -o -f platform-spring-boot-autoconfigure/pom.xml test
```

Логи: [platform-contracts](platform-contracts-tests.log),
[platform-core](platform-core-tests.log),
[entity-design-runtime](entity-design-runtime-tests.log),
[autoconfigure](autoconfigure-tests.log). XML/текстовые отчёты E3.R-тестов —
в [surefire](surefire/).

Проверенные сценарии записи:

- detached child и уже изменённый managed child не меняют владельца;
- другой экземпляр и Hibernate-proxy с тем же id разрешены;
- смена id владельца отклонена;
- aggregate-отказ до событий и before-хуков; изменение владельца в `beforeAggregateSave`
  отклоняется до persistence;
- named mutation и lifecycle `beforeUpdate` не переносят владельца;
- явный контекст команды с чужим owner-id отклонён, совпадающий — принят;
- удаление child не удаляет owner; удаление owner с детьми отклонено FK-границей;
- `owner` опционален в `TypeFacts` и отсутствует у обычного справочника.

## Ограничения результата

- Фильтрация list/selection и передача owner-контекста из форм — E3.B; read/query API не
  расширялся.
- Aggregate-путь с owned-секциями проверен без секций: строки и их parent — E4.
- Draft/JSON v2, генерация `@CatalogOwner`, `catalog-java-v3`, C/D — E3.A/E3.C; этот срез
  их не заменяет.
- Существующие строки owner-enabled типа с `owner=null` не мигрируются: первая запись такого
  типа отклоняется как `OWNER_IMMUTABLE`/`OWNER_REQUIRED`, миграция — отдельный пакет M.
- UI и браузер не запускались.

## Замечание о параллельной правке

Во время прогона `entity-design-core` его файлы (`DraftValidator` и `DraftValidatorTest`)
изменялись вне E3.R: набор тестов вырос с 87 до 96, и один тест
`DraftValidatorTest.versionsAndLimitsAreSemanticSafetyNetsForInMemoryCallers` падает на
`UNSUPPORTED_VERSION` для `standard-catalog-v1@1`. E3.R не менял draft/validator; в
`entity-design-core` затронут только `RuntimeDesignSnapshot`. Падение не отнесено к этому
срезу и не исправлялось; [лог](entity-design-core-tests.log) сохранён как есть.
