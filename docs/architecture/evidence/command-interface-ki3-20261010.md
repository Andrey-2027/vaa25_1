# КИ-3: единый снимок меню и плиток

Дата: 2026-10-10. Этап плана: подключение профилей к пользовательской навигации.

## Реализация

`NavigationUiSnapshot` — прикладной Spring component со скоупом `vaadin-ui`.
При первом построении меню он читает `NavigationProfileReader.currentProfile()`:
аутентификация и преобразование логина в неизменяемый `User.id` остаются в
адаптере КИ-2. Профиль передаётся в `NavigationResolver`, а права чтения проверяются
существующим `RlsReadGate` с аутентифицированным именем пользователя.
Отсутствие назначения означает значения разработчика. Ошибки аутентификации,
чтения профиля и вычисления прав дают ошибку загрузки динамической части,
а не дерево по умолчанию. Повторной попытки в том же снимке нет; обновление
страницы создаёт новый UI и новый снимок.

`MainLayout` получает UI-scoped адаптер и строит динамическое меню из разрешённых
корней и потомков. Обработчик передаёт в `SubsystemHomeView` тот же узел снимка;
плитки берутся только из `groupsRecursive()` разрешённого узла. Исходное дерево
метаданных и права не перечитываются при открытии раздела. Идентификатор вкладки
во включённом режиме использует стабильный ID подсистемы.

Плитка открывает список через прежний `FormNavigator`. Проверки доступа при
открытии формы и чтении данных продолжают действовать. Профиль управляет только
навигацией. Служебные пункты MainLayout, поиск, формы выбора и deep-link не
получают настроек профиля. Уже открытые вкладки не закрываются.

При `platform.navigation.enabled=false` MainLayout использует прежнее дерево
`SubsystemRegistry`, а плитки — прежний путь с `RlsReadGate`. Профили не удаляются.
При включённом флаге обязательные backend-бины получаются через `getObject()`;
отсутствие проводки не переводит оболочку в прежний режим.

## Проверки

`NavigationUiSnapshotTest` проверяет реальный Vaadin UI scope в Spring-контейнере:
два UI получают разные профили и разные наборы плиток, повторное построение меню
не перечитывает профиль и права, SHOW не обходит отказ `RlsReadGate`.
Отдельные проверки проходят по DOM-событию меню к тому же разрешённому узлу,
сравнивают родительские/дочерние плитки и проверяют вызов `FormNavigator`.
Вложенная тестовая метамодель расположена вне прикладного scan root.
Скрытый потомок отсутствует и в меню, и на родительской странице; явный SHOW
его элемента не обходит скрытие предка. Ошибка чтения сохраняет служебный пункт
и показывает недоступный пункт с сообщением об ошибке. Выключенный режим не
требует backend-бинов; включённый без них завершается ошибкой проводки.

Модуль: 40 успешных тестов; PostgreSQL-тест пропущен в обычном прогоне.
Схема хранения в КИ-3 не менялась; PostgreSQL DDL и restart проверены в КИ-2.
Локальный артефакт установлен в Maven repository. Signature baseline не менялся:
шесть типов проекции получили роль APP_API вместо MODULE_API без изменения API.
В reviewed-реестр приложения добавлены пять непосредственно используемых типов.
Fingerprint изменён только для `platform-navigation` и воспроизведён диагностикой.

Прикладной регрессионный прогон: 46 проверок, 41 успешная, один отказ и четыре
ошибки запуска. Все девять наборов, непосредственно проверяющих интеграцию КИ-3,
прошли (36 проверок): `ApplicationNavigationConfigurationTest`,
`ApplicationNavigationSecurityTest`, `NavigationApplicationSmokeTest`,
`NavigationDisabledApplicationSmokeTest`, `NavigationUiSnapshotTest`,
`EntityExplorerAddressWiringTest`, `MainLayoutLeaveGateTest`,
`PlatformApiBaselineTest` и `PlatformArchitectureTest`.

Оставшиеся проблемы расширенного прогона относятся к соседним изменениям:

- `DeepLinkRouteBaselineTest`: пять успешных проверок; один отказ из-за
  объявленного маршрута `/unidr`, отсутствующего в baseline.
- `RlsReadGateTest`: четыре ошибки загрузки контекста из-за дублирующегося бина
  `security` в самостоятельных UniDR smoke-приложениях. Этот набор не подтверждает
  data-path RLS в текущем общем дереве. Прикладные navigation smoke-тесты используют
  изоляцию test-classes из КИ-2 и успешно поднимают приложение; production scan
  не изменялся.
- `PlatformPublicSurfaceTest`: прежние пять отказов — wildcard/FQN UniDR,
  незарегистрированные отчётные типы/`RlsContext`, legacy `EntityRef` и общий дрейф
  замеров. Навигационные типы отсутствуют в списке нарушений. Фактическое число
  прикладных исходников в этом прогоне — 235 против документированных 200;
  в документ внесена только дельта КИ-3, остальные изменения не поглощались.

Три проверки manifest graph/coordinates прошли. `git diff --check` для изменений
этапа прошёл. Общая сборка не объявляется полностью зелёной.

Команды (Java 21, подготовленные прежние локальные зависимости):

```powershell
mvn -o -f platform-navigation/pom.xml install
mvn -o test '-Dtest=NavigationUiSnapshotTest,ApplicationNavigationConfigurationTest,ApplicationNavigationSecurityTest,NavigationApplicationSmokeTest,NavigationDisabledApplicationSmokeTest,PlatformApiBaselineTest,PlatformArchitectureTest,MainLayoutLeaveGateTest,EntityExplorerAddressWiringTest,DeepLinkRouteBaselineTest,RlsReadGateTest' '-Dplatform.api.baseline.artifact=platform-navigation'
mvn -o surefire:test '-Dtest=PlatformPublicSurfaceTest,WorkspaceManifestTest#manifestOrderIsATopologicalOrderOfItsOwnDependencies+everyDeclaredDependencyMatchesTheRealMavenWorkspaceEdges+everyPublishedCoordinateHasASingleOwner'
& .\scripts\diagnose-source-fingerprint.ps1 -ProjectId platform-navigation -Cultures InvariantCulture
```

Локальные журналы находятся в `outputs/navigation-ki3-module.log`,
`outputs/navigation-ki3-tests.log`, `outputs/navigation-ki3-regression.log`,
`outputs/navigation-ki3-surface.log` (ignored output, не часть поставки).
Mockito/Spring-тестам разрешён локальный JVM attach IPC; smoke-базы — H2.

Браузерная приёмка с назначением из административного редактора ещё не проводилась:
редактор относится к КИ-4, пилотная приёмка — к КИ-5. Флаг оставлен выключенным.
Посторонние изменения UniDR, регистров, REST и прочих модулей сохранены.
