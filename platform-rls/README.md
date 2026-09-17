# platform-rls — подсистема Row-Level Security

**D2 → D3.** Артефакт `org.ipro:platform-rls:1.0-SNAPSHOT` несёт подсистему RLS целиком
(36 типов): измерения и реестр политик, read/write-гейты, Hibernate-фильтры и
enforcement-аспект, канарейку «тихих утечек» SQL, нейтральные SPI
(`RlsDimensionValueLabelResolver`, `RlsOwnedSectionLookup`), сущность грантов
`AccessGrant` с репозиторием и две авто-конфигурации.

## Почему выносится четвёртой

Порядок задан замером замыкания (`PlatformSubsystemClosureTest`): прямые ссылки RLS на
дерево жили ровно в трёх файлах (`RlsDimensionValueCatalog`, аспект границы
репозиториев, хаб). После замены их нейтральными SPI замыкание стало пустым.
Направление `rls → telemetry` — верх на артефакт (шаг 8а); `rls → numbering` —
предоставление резолвера scope через контракт нумерации.

## Регистрация

Две авто-конфигурации со своим imports-файлом: `RlsAutoConfiguration` (бины
принуждения) и `RlsPersistenceAutoConfiguration` (`@EntityScan`/`@EnableJpaRepositories`
пакета `org.ipro.rls`) — чтобы `@DataJpaTest`-срезы подключали только persistence-часть.
Приложение и imports-файл приложения эти конфигурации не перечисляют. Свойство
`rls.dimension-scan-package` без default — его задаёт приложение.

## SPI

Оба шва реализует приложение (`MetadataAutoConfiguration`,
`FetchPlanInstanceNameAutoConfiguration`), модуль их только потребляет обязательными
параметрами бинов: без адаптера контекст не стартует (fail-fast вместо silent fail-open).
