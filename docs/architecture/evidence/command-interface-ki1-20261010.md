# КИ-1: каталог навигации и разрешение видимости

- Дата: 2026-10-10.
- План: [пилот командного интерфейса](../command-interface-pilot-plan.md).
- Результат: КИ-1 реализован; весь пользовательский пилот ещё не готов.
- Окружение: Windows, Java 21.0.11, Maven из IntelliJ IDEA; зависимости доступны локально, прогоны выполнены offline.

## Реализация

Создан необязательный backend-артефакт `org.ipro:platform-navigation:1.0-SNAPSHOT`.
Единственная production-зависимость — `platform-core`. Он принимает уже
инициализированный `SubsystemRegistry`, копирует заголовки, иконки, порядок
и принадлежность в неизменяемый `NavigationCatalog`. Нового сканера,
редактируемого реестра сущностей и изменения исходного дерева нет.

`NavigationDefaultsContributor` объявляет явные ID и исходную видимость через
`NavigationDefaults`. Неполные, повторные и неизвестные декларации отвергаются
при построении каталога; порядок contributors не разрешает конфликт.
ID имеют формат ASCII lower-kebab-case, ключи профиля — `subsystem:<id>`
и `item:<id>`. Переименование Java-типа, пакета или перенос пункта не требует
изменения ID. Намеренная смена ID потребует миграции настроек.

В `ApplicationNavigationConfiguration` опубликован contributor для всех
4 подсистем и 18 пунктов списков текущего приложения. Их исходная видимость
включена. Состав устойчивых ключей зафиксирован в тестовом ресурсе
`src/test/resources/navigation/pilot-navigation-ids.txt`.

`NavigationResolver` принимает каталог, переопределения DEFAULT/SHOW/HIDE
и предикат чтения сущности. Он возвращает отдельный неизменяемый снимок,
учитывает всю цепочку родителей и удаляет пустые поддеревья. SHOW не обходит
скрытого родителя и запрет чтения. `Node.groupsRecursive()` предоставляет
группы видимых пунктов потомков для будущей страницы родителя.
Неизвестные ключи возвращаются в `staleKeys()` и игнорируются при разрешении;
исходные настройки не меняются. Ошибка предиката чтения передаётся вызывающему коду.

Роли APP_API/APP_SPI/MODULE_API/INTERNAL записаны в поставляемом
`META-INF/navigation-surface.txt`; публичные подписи — в
`src/test/resources/platform-api-baseline/platform-navigation.api`.
Модуль включён в BOM, прикладной POM и порядок локальной сборки.
В общие starters он не добавлен. Fingerprint нового модуля проверен отдельным
диагностическим скриптом: 15 входных файлов, алгоритм `gitvaa-source-tree-sha256-v2`.

## Проверки

| Проверка | Результат |
| --- | --- |
| `NavigationCatalogTest` | 17 успешных: реальные metadata fixtures, полнота/уникальность ID, неизвестные цели, порядок, неизменяемость |
| `NavigationResolverTest` | 11 успешных: defaults, профиль, чтение, скрытые предки, группы, пустые узлы, stale keys, независимость пользователей, сохранность скрытия при переносе/переименовании |
| `NavigationModuleCompositionTest` | 2 успешных: полнота ролей включая вложенные типы, поставка реестра, backend-зависимости и отсутствие приложения/Vaadin на classpath |
| `ApplicationNavigationConfigurationTest` | 1 успешная: contributor из Spring-контекста и полный каталог на реальном `SubsystemRegistry("org.ip")` |
| `PlatformApiBaselineTest` с фильтром нового артефакта | 4 успешных: подписи сверены без перезаписи; полный состав baseline-артефактов и политика ролей также проверены |
| `WorkspaceManifestTest`: порядок, реальные Maven-рёбра, единственный владелец координат | 3 успешных |
| `PlatformArchitectureTest` | 3 успешных: bytecode-границы платформы и приложения |

Итого: **41 успешная проверка** в затронутом контуре. Production- и test-исходники
приложения скомпилированы. Независимая сборка нового модуля также прошла;
проверенный JAR установлен в локальный Maven-репозиторий для приложения и IDE.

Для повторения на Java 21 из корня репозитория после подготовки существующих
локальных зависимостей:

```powershell
mvn -o -f platform-navigation/pom.xml clean install
mvn -o test '-Dtest=ApplicationNavigationConfigurationTest,PlatformApiBaselineTest,PlatformArchitectureTest,WorkspaceManifestTest#manifestOrderIsATopologicalOrderOfItsOwnDependencies+everyDeclaredDependencyMatchesTheRealMavenWorkspaceEdges+everyPublishedCoordinateHasASingleOwner' '-Dplatform.api.baseline.artifact=platform-navigation'
```

Первичное создание signature baseline выполнялось с фильтром
`-Dplatform.api.baseline.artifact=platform-navigation` и явным
`-Dplatform.api.baseline.write=true`; baseline других артефактов не перезаписывались.

## Общий gate и границы результата

Дополнительно выполнен весь `PlatformPublicSurfaceTest`: 10 проверок,
5 успешных и 5 неуспешных. Диагностика относится к соседнему UniDR-коду:

- wildcard-импорты и fully-qualified обращения к платформенным типам;
- непокрытые reviewed-роли отчётных типов и `RlsContext`;
- новая ссылка `UniDrQuerySourceService` на legacy-internal `EntityRef`;
- устаревший общий замер: фактически 229 прикладных исходников,
  в документе 197 после добавления одного файла КИ-1.

Навигационные классы в списке нарушений отсутствуют. Чужие исходники и
reviewed-роли не менялись для получения зелёного результата. В замеры D1
добавлена только дельта КИ-1; прежний дрейф остаётся открытым. Общая сборка
репозитория не объявляется полностью зелёной. Полная сверка fingerprints
прочих изменённых модулей и их signature baseline в этот прогон не входила.

Хранилища профилей, назначения, административный сервис, автоконфигурация
и флаг включения относятся к КИ-2. Подключение реального `RlsReadGate`,
бокового меню и плиток к снимку — к КИ-3. В КИ-1 меню приложения продолжает
работать существующим способом. Браузерная приёмка профилей пока неприменима.
