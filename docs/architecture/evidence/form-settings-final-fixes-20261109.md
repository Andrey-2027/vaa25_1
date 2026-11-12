# Формы: поиск номера и архитектурные эталоны

Дата: 09.11.2026. Основание: технический остаток §12.47 рабочего плана;
поручение владельца выполнить только поиск номера и эталоны изменений форм.

## Поиск номера

В AF2 убрана зависимость серверного поиска от колонок формы. Для стандартного
документа без собственного @SearchFields и строкового @InstanceName это потеряло
поиск по number. Ожидание EffectiveStandardMetadataTest сохраняет принятый контракт.

PresentationResolver теперь использует number как типизированное умолчание для
StandardDocumentEntity после явных поисковых полей и строковых частей @InstanceName.
Произвольная сущность с полем number такого умолчания не получает. Колонки формы
в разрешении поиска не участвуют; текст представления, сортировка и RLS не менялись.

Регрессионные проверки покрывают обычное и tolerant-разрешение, наличие/отсутствие
InstanceNameResolver, колонки выбора с другим полем, приоритет явного @SearchFields,
@InstanceName и параметра вызывающего, отсутствие нового умолчания у обычной сущности.

## Проверенные изменения эталонов

Роли APP_API/MODULE_API/INTERNAL и бюджеты не расширялись. Тестовые ссылки записаны
по конкретным файлам, автоматическая перегенерация эталонов не применялась.

| Эталон | Изменение и основание |
| --- | --- |
| core test-usage | FetchPlanRegistry ← FormReadDependenciesIT: зависимости чтения AF5.6 |
| core test-usage | Удалена TableSectionGridMetadata ← FormSettingsSnapshotTest: снимок ROW перенесён на новый источник колонок |
| vaadin test-usage | TableSectionFactory ← AccountingObjectPilotIT: проверка табличной части формы |
| vaadin test-usage | EmbeddedListFactory ← ItemFormConstructionTest: сборка карточки со встроенным списком |
| vaadin test-usage | FormResolver ← EmbeddedListRuntimeIT: разрешение формы встроенного списка |

| Ребро матрицы | Проверенный потребитель |
| --- | --- |
| form → form.action | CommandBar: ActionId/CrudAction |
| form.builder → form.action | ListCommandLayout/ListUiCommand: идентификаторы команд |
| form.builtin → data | ItemForm: CatalogOwnerContext при сохранении |
| form.config → form.builtin | FormAutoConfiguration: фабрика встроенного ListForm |
| form.config → metadata.annotation | FormColumnDeclarationsCollector: аннотации состава колонок AF3.3 |
| form.coordinator → form.builder | FormStructureAffordance: команда DEVELOPMENT |
| form.registry → form.action | FormResolver: ActionId при разрешении команд списка |
| Удалено crud → fetch.instance | Прежний потребитель GenericOwnedSectionService делегирует пути представления PresentationResolver; прямой зависимости больше нет |

## Проверки

- platform-core: 316 тестов, 0 ошибок/падений/пропусков. Прежний падающий метод
  EffectiveStandardMetadataTest включён; исключений из прогона нет.
- Целевой прогон приложения: 13 тестов, 0 ошибок/падений/пропусков:
  FormSettingsSnapshotTest (5), PresentationApplicationParityTest (2),
  PresentationBoundaryTest (1), SearchFieldParitySnapshotTest (2),
  PlatformCoreSurfaceTest#applicationTestReferencesToInternalOrModuleTypesAreFrozenPerFile (1),
  PlatformVaadinSurfaceTest#applicationTestReferencesToInternalOrModuleTypesAreFrozenPerFile (1),
  PlatformPackageDependencyMatrixTest#everyRepositoryPackageEdgeIsReviewed (1).
  Снимки поиска и форм сохранены без перезаписи. Это целевые методы архитектуры,
  а не утверждение об успешности всего общего архитектурного набора.
- platform-core установлен в локальный Maven-репозиторий. Отпечаток 192 исходных
  файлов обновлён на 907ab6b2d3eac7f3a18ae2d3e8d1816c59c660c3ae484bcd062d419fb54d61f1.
- Строгий bootstrap -ValidateOnly подтвердил core и остальные проверенные модули,
  затем остановился на прежнем дрейфе platform-report-vaadin: ожидается 33 файла /
  66e51a92…, фактически 34 / 1aa43950…. Его отпечаток не менялся.
- git diff --check с cr-at-eol прошёл; существующие CRLF контрактов сохранены.

Логи текущего запуска: target/form-settings-final-fixes/{core,core-install,targeted,bootstrap}.log.
Полный прогон приложения, AF5.7, О5 и визуальные отсрочки не возобновлялись.
