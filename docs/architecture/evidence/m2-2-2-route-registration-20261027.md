# M2.2.2 (поправка): маршруты платформенного UI не были зарегистрированы (2026-10-27)

- Дата: 2026-10-27.
- Основание: [M2.2.2](m2-2-2-report-ui-20261026.md), [M2.2.3 cutover](m2-2-3-cutover-20261026.md),
  раздел «Runtime-проверка» которого прямо фиксировал, что интерактивная проверка
  против PostgreSQL не выполнялась.
- Повод: вопрос «отчёты уже в рабочем приложении или сбоку?» — проверка в браузере
  на живом стенде.

## 1. Что нашлось

`/report-catalog`, `/report-catalog-compact`, `/report-catalog-structured` и `/unidr`
отвечали как несуществующие адреса, хотя:

- `platform-report-vaadin` — зависимость приложения, `ReportCatalogView` и
  `UniDrView` лежат в его jar;
- `ReportUiAutoConfiguration` зарегистрирована через `META-INF/spring`
  самого артефакта, то есть бины модуля в контексте есть;
- `DeepLinkRouteBaselineTest` (6/0) видел все четыре маршрута — он читает исходники;
- `ReportCatalogViewTest`, `UniDrQueryUiTest`, контекстные smoke-тесты — зелёные.

То есть весь набор гейтов M2 проверял «класс существует и бин собран», но не
«адрес открывается».

## 2. Причина

Vaadin ищет `@Route` только в объявленных корнях сканирования. Корни берутся из
`EnableVaadin` (`VaadinScanPackagesRegistrar`), и **значение заменяет** пакет
`@SpringBootApplication`, а не дополняет его; без аннотации корень — пакет
приложения (`org.ip`). В `org.ip.Application` аннотации не было ни в одном виде,
поэтому после M2.2.2 UI из `org.ipro.*` остался вне корня: бины приезжали
артефактом, маршруты — нет. Тот же приём уже использован в проекте руками:
smoke-приложения UniDR объявляют `@EnableVaadin("org.ipro.unidr.ui")`.

## 3. Исправление

- `org.ip.Application`: `@EnableVaadin({"org.ip", "org.ipro"})` с комментарием,
  почему список обязан быть явным (без него переезд UI молча выключает маршруты).
  Список один на приложение, потому что `VaadinScanPackagesRegistrar` регистрирует
  единственный бин пакетов: объявление корня в самом модуле отменило бы корень
  приложения.
- `org.ip.routes.RouteRegistrationTest` (новый, 2/0) — забор реестра маршрутов:
  - `everyProductionRouteClassOfTheScanRootsIsRegistered`: корни читаются из
    `@EnableVaadin`, каждый production-класс с `@Route` обязан быть целью в
    реестре; тестовые demo-экраны (`org.ip.groupgrid`) отброшены по месту ресурса
    (`test-classes`); отдельно проверено, что прикладной `LoginView` остался в
    реестре — явный список не потерял корень приложения;
  - `reportUiOfPlatformArtifactIsReachableByPublishedPaths`: четыре адреса
    `deep-link-baseline` разрешаются в `ReportCatalogView`/`UniDrView`.
  - Реестр читается из атрибута сервлет-контекста (тот же
    `ApplicationRouteRegistry`, которым пользуется `VaadinServletContainerInitializer`):
    бина `RouteRegistry` в контексте Spring нет, `VaadinService.getCurrent()` в
    тестовом потоке пуст. Тест поднимает полный контекст приложения
    (`RANDOM_PORT`, `DataInitializer` замокан).

## 4. Живая приёмка (PostgreSQL, локальный стенд)

Стенд: `mvn -o spring-boot:run -Dspring-boot.run.profiles=dev`, PostgreSQL 5432,
порт 8080, вход `admin`.

| Адрес | Результат |
| --- | --- |
| `/report-catalog` | «Каталог отчётов», таблица с реальными строками БД (JR Jasper1 + пять UniDR-отчётов) |
| `/report-catalog-structured` | тот же каталог по alias-пути (44 ячейки грида) |
| `/unidr` | редактор UniDR: тулбар, выбор источника, вкладки «Настройка»/«Параметры отчёта», «Предпросмотр…» |
| `/queries` | «Конструктор запросов» — прикладной экран не потерян явным списком корней |

До правки первые три адреса отвечали отказом, как несуществующие (проверено в
браузере против того же стенда).

## 5. Мелкие долги рядом

### 5.1 Закрыт: гейт random-order не был записан в конфигурации

A1 записал его как «в конфигурации не найден», хотя прогоны в evidence выполнялись
именно так: порядок классов задают JUnit-свойства, а команда нигде не хранилась. В
корневой `pom.xml` добавлен профиль `random-order` (`mvn -o -Prandom-order clean
verify`, seed — `-Djunit.jupiter.execution.order.random.seed=<год><месяц><день>`),
выключенный по умолчанию. Проверено: `help:effective-pom -Prandom-order` показывает
слитую конфигурацию surefire, прогон с профилем зелёный (`DeepLinkRouteBaselineTest`
6/0). Полный random-order `verify` — по-прежнему отдельный вход.

### 5.2 Найден рядом, не закрыт: заметки о дефекте сидинга

Три теста (`RlsAutoConfigurationSmokeTest`, `RlsUnauthenticatedAccessIT`,
`ReportRuntimeConfigurationSmokeTest`) мокают `DataInitializer` из-за известного
дефекта сидинга на свежей БД (detached `Role` при сохранении пользователя). На
локальном стенде дефект не воспроизводится: база уже наполнена, а
`DataInitializer` пропускает работу, если роли и пользователи на месте; для
проверки нужна H2-фикстура со свежей схемой. В этом срезе долг не закрывался —
запись оставлена, чтобы его не искали заново.
