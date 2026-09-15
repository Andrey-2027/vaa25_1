# platform-settings — модуль констант

**D2 → D3.** Артефакт `org.ipro:platform-settings:1.0-SNAPSHOT` содержит подсистему констант
целиком (8 типов): маркеры `@Setting`/`@SettingsGroup`, `SettingsRegistry` (каталог значений по
умолчанию), `SettingsService` с сущностью `SettingValue` и её репозиторием, источник обратных
ссылок для индекса метаданных и авто-конфигурацию.

## Почему вторым

Порядок задан замером замыкания (типов дерева, достижимых транзитивно; модуль не может
ссылаться на дерево). До выноса `platform-metadata` замыкание констант было **2 типа** —
сканер классов по аннотации и индекс обратных ссылок, то есть ровно те два типа, что уехали
в позвоночник метаданных. После этого среза замыкание пустое, а запись о подсистеме снята с
реестра `PlatformSubsystemClosureTest`. Порядок и замер по остальным — в
`docs/architecture/d3-subsystem-extraction.md`.

Этот срез проверяет не новое свойство механизма, а воспроизводимость приёма на подсистеме
другого профиля: сущность с репозиторием, каталог, который сам сканирует пакет приложения по
аннотации, и обратная ссылка в индекс метаданных. Направление `settings → metadata`
сохранено: подсистема реализует `ReferenceIndex.ReverseReferenceSource`, а метаданные о ней
не знают.

## Что модуль не содержит

Реализаций верхних слоёв (`data`, `form`, `rls`, `telemetry`, UI). Разрешённые зависимости —
`platform-contracts`, `platform-metadata`, `platform-persistence` и API-артефакты
(JPA, Spring Data JPA, автоконфигурация Boot). Набор reviewed и проверяется
`PlatformSettingsModuleTest`.

## Регистрация

Сущности и репозитории объявляет сама подсистема
(`@EntityScan("org.ipro.settings")` и `@EnableJpaRepositories("org.ipro.settings")` в
`SettingsAutoConfiguration`), авто-конфигурация зарегистрирована собственным imports-файлом.
Приложение больше не перечисляет `org.ipro.settings` — ни в своём `@EntityScan`, ни в
платформенном хабе репозиториев. Две независимые декларации `@EnableJpaRepositories` не
перекрываются (проверено persistence-срезом и `PlatformAutoConfigurationRegistryTest`).

## Чем срез подтверждён (и почему не тестами модуля)

Ни один тест самой подсистемы не заметил вынос: регрессии нашёл полный прогон. Обе — один
класс ошибки: `@DataJpaTest`-срез **имитирует** регистрацию модуля вместо того, чтобы её
подключить, а авто-конфигурации в срезе отключены, поэтому `@EntityScan` теряется.

| Где | Что было видно |
|---|---|
| `SettingsServiceSliceIT` | `Not a managed type: class org.ipro.settings.SettingValue` — срез перечислял пакет модуля сам |
| `RlsIntegrationTest` | `Unknown entity type 'org.ipro.settings.SettingValue'` — срез пишет сущность модуля `new org.ipro.settings.SettingValue(...)`, не называя пакет ни в аннотации, ни строкой |

Лечение — общее правило в `PersistenceTypeRegistrationTest`: модули выводятся из их
собственных объявлений, и срез, трогающий persistence-тип модуля, обязан **использовать**
его регистрацию. Ссылка на бин модуля поводом не считается (`CanonicalWritePathIT` мокает
`NumberingService` и сущностей нумерации не трогает). Правило проверено негативно: снятие
`@ImportAutoConfiguration(SettingsAutoConfiguration.class)` роняет его с точным сообщением.

```
mvn -o clean verify      1365 тестов, 0 failures/errors/skipped
random-order gate        1365 тестов, 0 failures/errors (seed 20260915)
```

## Сборка

```
mvn -f platform-contracts/pom.xml  -DskipTests clean install
mvn -f platform-metadata/pom.xml   -DskipTests clean install
mvn -f platform-persistence/pom.xml -DskipTests clean install
mvn -f platform-settings/pom.xml   -DskipTests clean install
```

Порядок зафиксирован в `scripts/local-dependencies.json`
(`dependsOn: platform-contracts, platform-metadata, platform-persistence`).
