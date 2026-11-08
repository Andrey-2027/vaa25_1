# AF6 — этап 1: контракт и описание (08.11.2026)

## Результат и границы

Реализована часть AF6.1: описание встроенного списка, типизированная фабрика,
её передача до сборки новой формы и стартовая проверка. Прикладные Workshop
и ReceivingDocument не менялись; эскиз остаётся на согласовании.
Весь AF6.1 и весь AF6 этим шагом не закрываются.

Добавлены EmbeddedListNode и EmbeddedListFactory. Layout поддерживает
list(Entity.class), list(id, Entity.class), setVariant, setFilter,
setReadOnly и commands; через FormAdjust доступен addList.
Несколько списков одного типа различаются явными идентификаторами.

Описание не содержит компонентов. Проверяются пути целевой сущности, тип self,
родительские источники l.value, типы констант, null, идентификаторы, вариант
и команды. Связь CatalogOwner выводится без чтения данных; несовместимый родитель
и подмена owner другим источником отклоняются. Фабрики ListForm и обработчики
действий при старте не вызываются. Вариант с одними колонками допустим;
целый View вместо списка отклоняется. Команды проверяются для LIST_TOOLBAR
целевого типа/варианта с сохранением семантики REQUIRED/OPTIONAL/LOCAL.

Фабрика находится в FormContext отдельным типизированным полем. FormResolver
передаёт её новой форме до layout/buildDescription. Старые конструкторы контекста
сохранены. Адаптер FormAutoConfiguration разрешает UI-scoped FormNavigator
только при фактическом вызове и делегирует штатному созданию ListForm
с ограничениями открытия. Второй CRUD и инфраструктурные бизнес-параметры не введены.

**Runtime-host пока нет:** попытка отрисовать EmbeddedListNode завершается
явной ошибкой о необходимости этапа 2 и не вызывает фабрику. Это проверено тестом;
в прикладные формы новый узел пока не включается. Жизненный цикл и право чтения —
этап 2; применение контекста/действий/заголовков — этап 3; пилот — этап 4.
[Контракт и ограничения](../../forms/embedded-lists.md).

## Проверки

- **75 целевых тестов platform-vaadin прошли** без ошибок и пропусков:
  EmbeddedListDescriptionTest (14), EmbeddedListFactoryTest (3),
  ItemFormDescriptionTest, ItemFormLayoutNodesTest, FormReadDependenciesTest,
  FormResolverListCommandsTest, CustomizerRegistrarTest, FormAutoConfigurationTest,
  VaadinModuleCompositionTest. Итог — `target/af6-stage1/platform-install-final.log`.
- **74 прикладных теста прошли:** ItemFormConstructionTest (38),
  ItemFormRenderParityTest (32), WorkshopItemFormTest (4). Используются настоящие
  реестр/резолвер. Журнал — `target/af6-stage1/application-tests.log`.
- **4 проверки PlatformApiBaselineTest прошли** после обновления сигнатур:
  `target/af6-stage1/api-final.log`. Два новых типа классифицированы MODULE_API;
  учтены состав модуля (168 типов) и бюджет ролей. Baseline обновлён только
  для затронутых блоков, прежние конструкторы сохранены.
- Модуль установлен в локальный Maven-кэш; SHA-256 установленного jar совпал
  с `platform-vaadin/target/platform-vaadin-1.0-SNAPSHOT.jar`.

Первый запуск остановлен из-за отказа Windows attach-канала Mockito. Итоговые
проверки используют mockito-core как javaagent при старте JVM. Production-настройки
ради среды не менялись. Первая установка не смогла записать вне workspace;
итоговая установка выполнена с разрешением на локальный Maven-кэш.

## Общие ограничения проверки

Расширенный целевой набор приложения: 97 проверок, 89 успешных, 8 failures,
без errors/skipped. Один failure был форматом завершающей пустой строки нового
API baseline; формат исправлен, отдельный итоговый API-прогон зелёный.
Остальные семь failures относятся к существующим изменениям вне этапа 1:

- FormAssemblyPurityTest: сырой идентификатор variant в параллельно изменённом ListForm.
- PlatformVaadinSurfaceTest: неучтённый test-usage TableSectionFactory в уже изменённом
  AccountingObjectPilotIT. Остальные пять проверок класса прошли: в том числе
  полнота ролей, их бюджет и замкнутость публичных сигнатур.
- Пять проверок PlatformPublicSurfaceTest: устаревшие/недостающие записи D1,
  wildcard-импорты PrdSpec, fully-qualified обращения форм/проектировщика
  и устаревший app-source-files. Этап 1 не меняет прикладные исходники и не вводит
  этих обращений; общие реестры не переписывались ради зелёного результата.

Весь совместный состав не объявляется полностью зелёным. Установленный артефакт
включает существующие параллельные изменения platform-vaadin; указанные целевые
проверки выполнены на нём. Чужие изменения не откатывались и не коммитились.

## Отпечаток и bootstrap

По diagnose-source-fingerprint записан фактически собранный состав platform-vaadin:
283 файла, v2/ordinal,
`8490c595c8b662ac7deb04c05c118fc2e0a423f4b25c602be92c6debd9923013`.
Отпечаток включает совместный исходный состав, а не только файлы AF6.

Строгий bootstrap -ValidateOnly проходит platform-vaadin и останавливается на
прежнем drift platform-report-vaadin (33 → 34 файла, 66e51a92… → 1aa43950…).
Диагностический -AllowSourceDrift завершён успешно с этим единственным drift;
эталон чужого модуля не менялся. Журналы — `target/af6-stage1/bootstrap-*.log`.
Полный прогон приложения, frontend и браузерная приёмка не выполнялись:
интерфейс приложения на этом этапе не меняется.
