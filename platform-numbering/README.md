# platform-numbering — первый модуль-подсистема

**D2 → D3.** Артефакт `org.ipro:platform-numbering:1.0-SNAPSHOT` содержит подсистему
нумерации целиком: аннотации (`@Numbered`, `@NumberingRole`, `@NumberingPolicy`), правила и
счётчики с их репозиториями, сервисы, резолвер scope и авто-конфигурацию.

## Почему именно нумерация и почему первой

Не по размеру и не по «удобству»: порядок задан замером замыкания — типами дерева, которые
подсистема тянет транзитивно. Модуль не может ссылаться на дерево (приложение зависит от
модуля, обратная ссылка замкнула бы сборку), поэтому замыкание — точная мера готовности:

| Подсистема | Замыкание на дерево | После чего стало возможно |
|---|---|---|
| `numbering` | 1 тип → 0 | вынос позвоночника метаданных (`platform-metadata`) |
| `settings` | 2 типа → 0 | то же |
| `telemetry` | 1 тип (`rls.RlsStatementGuard`) | пара `telemetry` + `rls`, цикл по одному классу |
| `rls` | 17 типов | после `telemetry`/`numbering` и метаданных-движка |
| `reportstudio` | 42 типа | последним, после консолидации трёх редакторов отчётов |

Реестр замыканий — `PlatformSubsystemClosureTest`, shrink-only: рост означает новый импорт
подсистемы в приложение, сокращение — состоявшийся срез.

## Что нового проверяет этот модуль

Предыдущие срезы проверяли по одному свойству: контракты — «читает компилятор», events —
«модуль сам регистрирует свои бины», persistence — «модуль сам регистрирует свои пакеты».
Нумерация несёт всё сразу, и именно поэтому она — настоящая проверка механизма перед D3:

- **сущности и репозитории** объявлены модулем (`@EntityScan`, `@EnableJpaRepositories` в
  `NumberingAutoConfiguration`), приложение и платформенный хаб `RlsAutoConfiguration`
  больше их не перечисляют;
- **авто-конфигурация** зарегистрирована собственным imports-файлом, а не файлом
  приложения;
- **reflection-регистрация** работает через артефакт: `@Numbered` стоит на сущностях
  *приложения* (`org.ip.model.*`), а сканирует их `platform-metadata`;
- **направление зависимости сохранено**: `rls` зависит на нумерацию (предоставляет ей
  `NumberingScopeResolver` и объявляется `@AutoConfigureBefore` её авто-конфигурации), а не
  наоборот.

## Что модуль не содержит

Реализаций верхних слоёв: `data`, `form`, `rls`, `telemetry`, UI. Разрешённые зависимости —
`platform-contracts`, `platform-metadata`, `platform-persistence` и API-артефакты
(JPA/validation, Spring Data JPA, автоконфигурация Boot). Набор reviewed и проверяется
`PlatformNumberingModuleTest`.

## Сборка

```
mvn -f platform-contracts/pom.xml -DskipTests clean install
mvn -f platform-metadata/pom.xml  -DskipTests clean install
mvn -f platform-persistence/pom.xml -DskipTests clean install
mvn -f platform-numbering/pom.xml -DskipTests clean install
```

Порядок зафиксирован в `scripts/local-dependencies.json`
(`dependsOn: platform-contracts, platform-metadata, platform-persistence`).
