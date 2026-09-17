# platform-events

Runtime-срез этапа D2. Если `platform-contracts` проверял границу на декларациях, которые
обе стороны только читают, то этот модуль проверяет её на том, чего до сих пор не выносили:
**на бинах и конфигурации контейнера**.

## Что здесь лежит

| Тип | Роль |
|---|---|
| `org.ipro.events.EntityEventPublisher` | публикует `Entity*Event`, `EntityChangedEvent` — только после commit |
| `org.ipro.lifecycle.EntityLifecycleRegistry` | fail-fast registry прикладных `EntityLifecycle`, `@EventListener` на после-коммитный `EntityChangedEvent` |
| `org.ipro.events.config.EventsAutoConfiguration` | создаёт publisher и registry, `@ConditionalOnMissingBean` |

Типы событий и lifecycle-контексты — в `platform-contracts`; здесь только исполнение.

Идентификатор сущности — в `platform-identity-api` (Java-only модуль реактора `crudui`):
реестр типизирован по `IdentifiableEntity`, но UI-библиотека ему для этого не нужна.

## Направление зависимостей

```
application  ──►  platform-events  ──►  platform-contracts
      (org.ip)        (runtime)              (contracts)
```

Это первая связка платформа→платформа: runtime-модуль знает контракты, контракты о нём не
знают. Ни Spring, ни реализации платформы (`CanonicalWriteExecutor`, metadata, UI) сюда не
попадают: контур событий — нижний слой, он не знает ни write path, ни прикладного пакета
`org.ip`.

## Модуль регистрирует себя сам

`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
перечисляет `EventsAutoConfiguration`. Поэтому **приложению не нужна ни одна строка про этот
модуль**: раньше его авто-конфигурация была в imports-файле приложения, теперь — здесь.
Это и есть требование roadmap «extraction не должен увеличивать число обязательных
registrations в application», проверенное не словами, а отсутствием записи в приложении.

Платная сторона решения: **потерять контур можно тихо** — если модуль не подключён или его
imports-файл пуст, приложение стартует, записи проходят, а lifecycle-правила и события просто
не срабатывают. Поэтому в приложении есть `EventContourStartupCheck`: он падает на старте,
если контур отсутствует, а прикладные `EntityLifecycle` объявлены.

## Модуль владеет и проводом контура

Раньше это было верно наполовину: `EntityEventPublisher` создавался здесь, а
`EntityLifecycleRegistry` — конфигурацией метаданных из дерева приложения. Самостоятельный
потребитель модуля получал publisher без реестра (правила обнаружить некому), а в полном
приложении дефект был невидим, потому что registry кто-то создавал. Владение классом без
владения wiring — та же недоделанная граница, только тише. Теперь оба бина создаёт
`EventsAutoConfiguration`, а внешний старт-чек остаётся защитой от потери самой
автоконфигурации.

## Свои тесты

`src/test` модуля держит его поведение и его провод: публикация до/после commit (включая
fail-closed без транзакции), fail-fast реестра на дублях, состав среза и compile-зависимости,
а также то, что автоконфигурация модуля поднимает оба бина и находит объявленные consumer'ом
handler'ы. Кросс-артефактные свойства (регистрация ровно один раз, отсутствие записи в
imports-файле приложения, происхождение класса из артефакта) остались в приложении —
`PlatformEventsModuleTest`, `EventContourWiringIT`.

## Сборка

```bash
mvn -o -f platform-contracts/pom.xml clean install
mvn -o -f platform-events/pom.xml clean install   # тесты модуля выполняются здесь
mvn -o verify
```

Порядок важен и выводится из `dependsOn` в манифесте воркспейса
(`scripts/local-dependencies.json`): `platform-events` собирается после `platform-contracts`
и до приложения. Порядок строк в JSON — тоже топологический, но полагаться на него не нужно:
скрипт сортирует проекты и падает на неизвестном id или цикле.
