# Реестр сущностей — R1: общий каталог и refresh

Дата: 2026-11-10. Реализован R1 [плана, редакция 2](../../plans/entity-registry-ux-plan.md).
Платформенные панели и новый реестр относятся к R2; старый UI и адресные правила сохранены.

## Реализация

- Прикладной `EntityExplorerCatalog` (`@UIScope`, не Vaadin-компонент) владеет общим
  ленивым immutable снимком: платформенный `EntitySummaryAssembler.explorerSnapshot()`
  вызывается один раз на ревизию. `Facts` хранит проекцию `CatalogView.of(snapshot)` и
  общую диагностику; проекция замыкает тот же снимок, второго инвентаря нет.
- Допуск проверяется перед чтением, сборкой и refresh. Отпечаток контекста включает principal,
  отсортированные authorities и локаль текущего UI. Общая ревизия данных и ревизия контекста
  разделены: refresh не означает смену пользователя и не очищает его выбор/фильтры.
- Старый `EntityExplorerView`, `EntityExplorerNavigation` и `EntityDraftProjectCatalog`
  получают один каталог через Spring. Проверка доступности навигации и сборка проектного
  каталога больше не создают Explorer-компонент. Существующий callback уточнения owned-секции
  в навигации оставлен для старого UI; выделение карточек относится к R3.
- Refresh сбрасывает общий снимок, увеличивает ревизию и оповещает read-only потребителей.
  Уже созданный старый UI перестраивается с сохранённым допустимым выбором и местом,
  поиском, фильтрами, группировкой и раскрытиями. Очищаются **оба** кеша: `panels` и
  `pilotPanels`; новая подробная панель заменяет старую в видимой области даже для того же типа.
  Это работает и для скрытой Workspace-вкладки: её повторная активация сама не вызывает `init`.
  Отсутствующий после обновления тип обрабатывается прежним `ExplorerUiState.restore`.
- Незагруженный UI не создаётся ради refresh. Незаконченные сессии создания и изменения
  не инвалидируются. Подписка старого вида живёт вместе с его UI-scoped экземпляром;
  каталог предоставляет снимаемую регистрацию для будущих потребителей с более коротким сроком жизни.
- Смена контекста очищает read-only кеши и прежнее состояние вида при следующем входе/чтении,
  даже если новую ревизию уже первым увидел другой потребитель каталога.

## Ошибка компиляции и проверочный скрипт

`GenerationDiagnostic.Code` уже содержал `FORBIDDEN_FORM_SETTING`, но исчерпывающий switch
в `EntityDraftEditorView.generationMessage` его не обрабатывал. Добавлен конкретный русский
текст о вынесении настроек в конфигурацию формы; общий default не скрывает будущие коды.

`scripts/test-entity-explorer-structure.ps1` расширен целевыми проверками R1. JUnit engine
и Mockito теперь выбираются из разрешённого classpath: оставшиеся JAR в старых нумерованных
каталогах больше не дают ложную ошибку «Expected one JUnit engine». Параметр `TestClasses`
позволяет явно выбрать тестовые классы; без него выполняется весь прежний набор с добавлениями R1.
`SkipCompile` сохраняет прежнее назначение и допустим только после свежей компиляции.

## Проверки

Промежуточная адресная проверка после реализации: 35/35 — общий каталог, адреса старого вида
и создание в настоящем Spring UI-scope. Проверены один снимок для навигации/генератора,
ленивость, допуск, смена principal/ролей/локали, стабильность при перестановке authorities,
раздельные UI, refresh dirty-черновика, оба read-only кеша, видимая панель и место просмотра.

Окончательный целевой набор: **147/147**, без пропусков. Помимо трёх классов выше,
в него вошли `ExplorerTreeModelTest`, `EntityStructureNavigationTest`,
`SubsystemStructureNavigationTest`, `EntityExplorerAddressWiringTest`,
`EntityExplorerRouteGateTest`, `EntityDraftCatalogAdapterTest`, `DraftEditorSessionTest`,
`EntityDraftEditorViewTest`, `EntityModificationTest`, `EntityDraftWorkspaceTest`,
`EntityDraftWorkspaceContractTest`, `WorkspaceDirtyGuardTest`, `WorkspaceRouteStateTest`.
Проверки API baseline: **4/4 platform-core**, **4/4 platform-vaadin**.
Проверка `PlatformVaadinSurfaceTest`: **6/6**. Разрешённая поверхность и baseline не расширялись.
После уточнения fixture компактной карточки адресный набор повторно прошёл **35/35**;
он проверяет восстановление явно выбранной вкладки «Представление», а не только начального места.

Логи: `outputs/entity-registry-r1/targeted.log`, `check.log`, `platform-core-api.log`,
`platform-vaadin-api.log`, `surface.log`. Полная компиляция текущих исходников приложения
и целевых тестов выполнена штатным скриптом; новые панели/R2 не подставлялись вместо старого UI.
В существующей проверке повторных операций Explorer: 62 операции, `extraSummaries=0`,
`extraFacets=0`, `extraMetadata=0` (это fixture теста, не production-инвентаризация).

Воспроизводимый целевой запуск с полной компиляцией:

```powershell
$tests = @(
    'org.ip.views.admin.EntityExplorerCatalogTest',
    'org.ip.views.admin.ExplorerTreeModelTest',
    'org.ip.views.admin.EntityExplorerViewAddressTest',
    'org.ip.views.admin.EntityExplorerViewSpringCreationTest',
    'org.ip.views.admin.EntityStructureNavigationTest',
    'org.ip.views.admin.SubsystemStructureNavigationTest',
    'org.ip.views.EntityExplorerAddressWiringTest',
    'org.ip.views.EntityExplorerRouteGateTest',
    'org.ip.views.admin.EntityDraftCatalogAdapterTest',
    'org.ip.views.admin.DraftEditorSessionTest',
    'org.ip.views.admin.EntityDraftEditorViewTest',
    'org.ip.views.admin.EntityModificationTest',
    'org.ip.views.admin.EntityDraftWorkspaceTest',
    'org.ip.views.workspace.EntityDraftWorkspaceContractTest',
    'org.ip.views.workspace.WorkspaceDirtyGuardTest',
    'org.ip.views.workspace.WorkspaceRouteStateTest'
)
& ./scripts/test-entity-explorer-structure.ps1 -TestClasses $tests
```

Расширенный запуск без `TestClasses`: **280/281**, без пропусков, лог
`outputs/entity-explorer-p1/test.log`. Единственное падение:
`EntitySummaryReadPlanPilotTest.exposureRuleIsNamedAndLookupReasonComesFromTheDeclaration`
(`src/test/java/org/ip/vaadin/explorer/EntitySummaryReadPlanPilotTest.java:164`). Тест ожидает
`lookup:PrdSpecMtr.nomenclature` в backend-плане LOOKUP; фактически список путей пуст.
Зависимость выбора уже задана через `setLookupPaths("unitOfMeasurement")` в
`PrdSpecMtrItemForm.adjust`, а прежнего `@Lookup.fetch` на поле `PrdSpecMtr.nomenclature`
нет. Проверка напрямую создаёт `FetchPlanRegistry`/assembler, не обращаясь к новому
каталогу или UI; файлы этого сценария в R1 не изменялись. Прежнее ожидание теста не
переписано, возвращение legacy-аннотации ради зелёного прогона не выполнялось.
Таким образом, целевые проверки R1 и API/surface проходят; весь расширенный набор
не объявляется зелёным. Согласование этого старого теста с переносом настроек формы
остаётся отдельным вопросом прежнего среза.

`git diff --check` для файлов R1 — без замечаний. Живой браузер/БД не запускались:
R1 не меняет CSS/компоновку, а визуальная реализация и снимки предусмотрены на R2/R5.
