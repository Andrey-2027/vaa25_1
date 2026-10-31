# UniDR: печать из списка и карточки по типу сущности (S5/S6)

**Дата:** 2026-10-18. **Срез:** R2, шаги S5–S6 из
[`r2-udr-scenario-inventory.md`](../r2-udr-scenario-inventory.md).

## Что изменено

Привязка отчёта к реестру стала частью определения, а не свойством UDR-шаблона:

- `UniDrDefinition.targetEntityClass` — необязательное поле схемы 2 (FQN Java-класса, до 200
  символов). Пустое значение означает «только каталог». Старые файлы без поля читаются как
  `null`; версия схемы не повышалась.
- `UniDrFileStore.Entry.targetEntityClass` и `UniDrFileStore.findPrintable(entityClass)` —
  отбор без декодирования всего определения (читается то же поле из `definition`).
- Редактор UniDR: поле «Печать для типа сущности» (список типов из метаданных конструктора плюс
  ручной ввод FQN). Привязка переживает сохранение, повторное открытие, смену источника и
  «Сохранить как».
- `ReportPrintDialog` объединяет UDR, JR и UniDR-записи, привязанные к типу контекста, и запускает
  UniDR через `UniDrRunDialogFactory.open(definition, context)` — тем же путём, что каталог, но с
  контекстом строки/карточки.
- «Создать → UniDR» в диалоге печати открывает `/unidr?targetEntityClass=…`; `UniDrView`
  применяет привязку к новому отчёту (S6).
- `ReportPrintAction` (список) и `ContextualReportLauncher`/`ItemFormReportActions` (карточка)
  получают `UniDrFileStore` и `UniDrRunDialogFactory` через nullable-зависимости: при выключенном
  `app.unidr.enabled` печать UDR/JR работает как раньше.
- `ReportCardPrintCustomization` реализует `FormTypeCustomization`: «Отчёты» появляются в подвале
  каждой сохранённой JPA-сущности без вызова `ItemFormReportActions` из конфига формы. Уже
  добавленная форма-specific кнопка не дублируется; новая запись и карточки регистров пропускаются.
  Сборка самой кнопки живёт в `ItemFormReportActions.CardLauncher` (вложенный `@Component`), чтобы
  JR/UniDR-зависимости не протекали в общий слой форм.
- Роль `org.ipro.reportstudio.param.ReportContextFactory` в `platform-public-surface.txt` поднята
  с `legacy-internal` до `API`: это публичный построитель контекста для экранов, и теперь его
  используют два входа печати, а не только `ReportPrintAction`.

## Проверка

Команды выполнялись из корня чекаута с JDK 21:

```text
mvn -Dtest=UniDrDefinitionJsonCodecTest,UniDrFileStoreTest,UniDrEditorTest,ReportPrintDialogUniDrTest,ContextualReportLauncherTest,ReportPrintActionTest test
mvn -Dtest=UniDrFileUiTest test
mvn -Dtest=UniDrDefinitionV2Test,UniDrDefinitionTest,UniDrAppearanceTest,UniDrQuerySourceTest,UniDrSourceReconcilerTest,UniDrReportCompilerTest test
mvn -Dtest=ReportCardPrintCustomizationTest,ReportFormSeamTest,PlatformPublicSurfaceTest,PlatformVaadinSurfaceTest,PlatformArchitectureTest,PlatformSubsystemClosureTest,PlatformApiBaselineTest,FormDeadApiTest,PlatformDependencyDirectionTest,PlatformPackageDependencyMatrixTest test
mvn -Dplatform.measurements.write=true -Dtest=PlatformPublicSurfaceTest test
```

Результат: перечисленные наборы зелёные (71 тест в первом прогоне, 43 в заборах без
`WorkspaceManifestTest`, 30 в наборе с общей кастомизацией). Замеры D1 перегенерированы.
Новые проверки:

- `ReportPrintDialogUniDrTest` — привязанный отчёт попадает в список, не привязанный — нет; выбор
  строки вызывает `UniDrRunDialogFactory.open(definition, context)` с контекстом строки.
- `ContextualReportLauncherTest` — карточка получает тот же список (общий диалог).
- `UniDrFileUiTest` — `?targetEntityClass=` доходит до сохранённого файла и `findPrintable`.
- `UniDrDefinitionJsonCodecTest`, `UniDrFileStoreTest`, `UniDrEditorTest` — round-trip поля,
  отбор, отсутствие привязки и её снятие.
- `ReportCardPrintCustomizationTest` — одна кнопка на сохранённую JPA-карточку, отсутствие для новой
  записи и не-сущности, отсутствие дубля при явном подключении.

## Ограничения

- Живой Spring-контекст с новыми бинами не проверялся: на момент прогона рабочее дерево
  содержало незавершённый рефакторинг `platform-vaadin`/`org.ip.views.admin`, из-за которого
  сборка приложения падала вне файлов этого среза. `ReportCardPrintCustomizationTest`
  проверяет кастомизацию напрямую; факт регистрации бинов остаётся проверить после стабилизации
  дерева (запуск `PlatformVaadinWiringIT`).
- Полный прогон не зелёный по причинам вне диффа: `WorkspaceManifestTest` фиксирует дрейф
  `platform-core`/`platform-vaadin`, а `UngrantedDefinitionsReportTest` падает на файлах прав,
  изменённых в рабочем дереве другим срезом. Оба набора не затрагиваются этим изменением.
- Сценарии в [`r2-udr-scenario-inventory.md`](../r2-udr-scenario-inventory.md) по-прежнему не
  исполнялись на реальных данных: проверка — тестовая, на синтетическом определении.
