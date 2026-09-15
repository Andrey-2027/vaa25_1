# platform-events

Runtime-срез этапа D2. Если `platform-contracts` проверял границу на декларациях, которые
обе стороны только читают, то этот модуль проверяет её на том, чего до сих пор не выносили:
**на бинах и конфигурации контейнера**.

## Что здесь лежит

| Тип | Роль |
|---|---|
| `org.ipro.events.EntityEventPublisher` | публикует `Entity*Event`, `EntityChangedEvent` — только после commit |
| `org.ipro.lifecycle.EntityLifecycleRegistry` | fail-fast registry прикладных `EntityLifecycle`, `@EventListener` на после-коммитный `EntityChangedEvent` |
| `org.ipro.events.config.EventsAutoConfiguration` | создаёт publisher, `@ConditionalOnMissingBean` |

Типы событий и lifecycle-контексты — в `platform-contracts`; здесь только исполнение.

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

## Сборка

```bash
mvn -o -f platform-contracts/pom.xml clean install
mvn -o -f platform-events/pom.xml clean install
mvn -o verify
```

Порядок важен и зафиксирован в манифесте воркспейса (`scripts/local-dependencies.json`):
`platform-events` собирается после `platform-contracts` и до приложения.
