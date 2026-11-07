# AF5.6 — часть пакета 1: readOnly и зависимости чтения (07.11.2026)

## Объём

Выполнена необходимая часть AF5.6 после AF4.5: перенос единственного прикладного
`readOnly` и двух `@Lookup.fetch`, передача потребностей формы в LOOKUP/DETAIL/ROW.
Оставшаяся миграция поведения AF5.6 выполняется вместе с AF5.8 в пакете 2.
Закрытие этой части не закрывает весь AF5.6 или К4. Полный прогон не выполняется.

## Контракт формы и чтения

```java
l.field("nomenclature").setLookupPaths("unitOfMeasurement");
l.field("prdSpecMtr").setLookupPaths("nomenclature", "nomenclature.unitOfMeasurement");
l.display("nomenclature.unitOfMeasurement.shortCode");
l.display("formatted", formatter).dependsOn("nomenclature.unitOfMeasurement");
a.element("currentAccountingType").setReadOnly(true);
```

- `setLookupPaths` задаёт пути относительно выбранного значения. `LookupSearchFunction`
  и `LookupSelectionFormFactory` передают один набор путей в автокомплит и диалог.
  `FieldFactory` сохраняет независимые настройки поиска, колонок и варианта выбора.
- `EntityField.setSelectionFilter(Map)` сохраняет стандартную фабрику и зависимости.
  Фабрика, которая не умеет передавать отбор или зависимости, отказывает явно.
  Старые функции и фабрики без дополнительных зависимостей поддерживаются.
- `FormReadDependencies` собирает ассоциации из итогового дерева после раскрытия
  `remainingFields()` и `adjust()`, включая последующие вкладки, вложенные поля вывода
  и явно объявленные входы форматтера. Удалённые элементы путей не добавляют.
  Зависимости выбранного значения добавляются также с префиксом поля к чтению карточки/строки.
- Читатель перед открытием Workspace и route-входом получает описание для текущего
  открытия. Это не общий снимок состояния пользователя при старте. Для описания не
  создаются редакторы, собственные компоненты или обработчики. Ошибочные пути
  проверяются при компиляции описания и стартовой проверке аннотированных форм.
- `BaseService.findById(id, additionalPaths)` передаёт дополнительные пути в canonical
  `DetailRead` со сценарием DETAIL. Читатель без поддержки таких путей не теряет их молча.
  Dialog, Workspace, route-вход и копирование шапки используют этот вход. Пустой набор
  сохраняет прежний вызов для старых форм и сервисов.
- Для ROW фабрика секций объединяет потребности default-формы и объявленных вариантов
  строки. Они передаются при чтении, повторной загрузке ссылок, смене/сбросе сохранённого
  вида и копировании строк; состав видимых колонок ими не управляет.

Совместимость старого конструктора `ElementNode` сохранена. Метаданные сущности не
получают новые UI-зависимости; backend принимает дополнительные пути запроса, как для
активных колонок списка.

## Перенос пилотов

- `PrdSpecMtr`: default, material и product описаны классами `@FormFor`. Default сохраняет
  автоматический состав через `remainingFields()`, два варианта сохраняют свои прежние
  поля и порядок. Два `@Lookup.fetch` удалены из модели только после подключения обоих
  каналов выбора и ROW-зависимостей.
- Существующие обработчики подстановки единицы остаются в
  `PrdSpecMtrFormCustomization` как кастомайзеры собранной формы. Отбор материалов
  использует стандартную фабрику выбора, сохраняя зависимости. Перенос этих обработчиков
  на декларации AF5.8 и уточнение источников событий остаются пакетом 2.
- `AccountingObjectCorrectionItem.currentAccountingType`: `readOnly` удалён из
  `FieldMetadata`. Default-форма задаёт его в `adjust()`; существующий собственный
  редактор строки закрепляет `declaredReadOnly` в биндинге. Переключение всей формы
  в редактирование не снимает запрет этого элемента. `bindExternal`, `loading` и
  обработчики корректировки сохраняются до пакета 2.
- `ReceivingDocument` и поведение шапки `PrdSpec` этим шагом не переписываются.

## Р18: изменение планов чтения

Изменились ровно две строки общего снимка П.2:

| Глобальный план | До AF5.6 | После |
|---|---|---|
| Nomenclature LOOKUP | unitOfMeasurement | пусто |
| PrdSpec LOOKUP | journal,nomenclature,nomenclature.unitOfMeasurement | journal,nomenclature |

Эти пути теперь запрашивает конкретное поле формы. Обычный `LookupService.findAll`
не наследует потребности другого экрана. Для material запрос LOOKUP получает
`unitOfMeasurement`; для product — `nomenclature,nomenclature.unitOfMeasurement`.
Итоговый граф выбранного значения сохраняется; глобальный граф остальных выборов сужается.

Отдельный эталон: `src/test/resources/forms/form-read-dependencies-snapshot.txt`.
Он фиксирует lookup-пути элементов, пути открытия default/material/product,
новые глобальные планы LOOKUP и эффективный `readOnly` обеих форм корректировки.
П.2 сохраняет колонки, размеры, подписи, порядок, поиск, поля карточек и остальные планы.

## Проверки

Все 241 целевых проверок успешны: приложение — 177, platform-vaadin — 48, platform-core — 16. Полный прогон, frontend и полные C/D harness не запускались.

Прикладной набор: FormSettingsSnapshotTest, FormReadDependenciesIT, FetchPlanLookupHydrationIT,
FetchPlanRegistryTest, FetchPlanReadBoundaryIT, FetchPlanScenarioFactsIT, ItemFormConstructionTest,
ItemFormRenderParityTest, PrdSpecSectionCompositionTest, PrdSpecRowCancelAcceptanceTest,
PrdSpecHiddenSectionSkipsValidationAndSaveTest, PrdSpecMtrVariantTest, WorkshopItemFormTest,
UserItemFormAccessTest, NomAttributeValueItemFormTest, ItemFormWorkspaceOpenGuardTest,
FormRouteOpenOutcomeTest, TableSectionFactoryStartupValidationTest, ApplicationFormFetchBoundaryTest,
JavaEntityReaderParityTest, FormDeadApiTest, FormAssemblyPurityTest.

Платформа форм: FormReadDependenciesTest, FieldLookupDependenciesTest,
EntityFieldSelectionFilterTest, ItemFormDescriptionTest, ItemFormLayoutNodesTest,
TableSectionColumnsTest, CopyRowsForTest. Backend: CanonicalDetailDependenciesTest,
FormColumnDeclarationsTest, FetchPlanColumnsTest.

Оба изменённых модуля установлены в локальный Maven-кэш с целевыми тестами.
Контроль SHA-256 подтвердил, что backend jar после итоговой установки совпадает с jar,
на котором проходили прикладные проверки. Fingerprints обновлены только для владельцев AF5.6:

| Модуль | Файлов | SHA-256 |
|---|---:|---|
| platform-core | 191 | 7806f1d843a1bcdb40a2237f959592bb64399dc70b9aa880510f4595c631c21d |
| platform-vaadin | 260 | d67668aa2cd66d288f7647a8d09ef9e8fa5470d45ff913e4ad299b704deb30ee |

diagnose-source-fingerprint подтверждает v2/ordinal. Строгий bootstrap -ValidateOnly
по-прежнему останавливается на прежнем drift platform-report-vaadin: 33→34 файла,
66e51a92…→1aa43950…. Проверка с -AllowSourceDrift проходит с этим единственным
предупреждением; этот модуль не изменялся. Проверка whitespace выполнена с учётом
CRLF исходных файлов; пробел после двоеточия у пустого значения в П.2 сохраняет
существующий формат эталона.

Визуальная приёмка К4 этим шагом не объявляется: составы экранов сохранены,
проверены построенные компоненты, биндинги и данные; отдельный браузерный прогон не проводился.

Новые интеграционные проверки используют реальные формы и canonical readers:

- выбор материала через автокомплит сохраняет отбор; обработчик подставляет единицу
  после `EntityManager.clear()`, без дополнительных запросов;
- оба диалога возвращают связи, пригодные для чтения после отсоединения объектов;
- вложенный DETAIL-вывод и форматтер читают значение вне persistence-контекста без SQL;
- ROW-зависимости сохраняются при виде с одной колонкой, сбросе вида и копировании строк;
- обе формы корректировки сохраняют `readOnly` после `setReadOnly(false)`;
- зависимости доходят до preflight до открытия Workspace/route-вкладки;
- описания проверяются без создания редакторов, пути для нового открытия не кешируют
  состояние предыдущего пользователя/открытия.

При подготовке проверок тестовые данные переведены на штатные repository/aggregate
границы; проверки авторизации production-кода не ослаблялись. Заглушки фабрик секций
получили реестр форм. Также обновлён устаревший тест provenance для AttributeValue и
SklNomOpa: уже существующий ET8 выводит возможности из `InternedContract` (DERIVED),
а тест ещё ожидал удалённые capability-бины (REGISTRATION). Production-политика не менялась.

Первый архитектурный прогон пересёкся с установкой Maven-артефакта и получил ошибку
чтения jar (EOF/ZLIB). Итоговый прогон выполнен после завершения установок и прошёл успешно.

## Остаток AF5.6 / пакет 2

ReceivingDocument → автоформа; поведение PrdSpec и PrdSpecMtr вместе с AF5.8;
типизированный `readOnlySections`; удаление неиспользуемого contextual-вида;
поведение корректировки в `behavior()` без `loading`, собственный редактор через
`setEditor`, очистка через `onChange`; полный набор проверок поведения и визуальная
приёмка К4. AF5.7 остаётся в пакете 3.


## Коммиты

| Область | Коммит |
|---|---|
| ET8 test maintenance | 344abdbc |
| Platform dependencies | 53af9f61 |
| Correction readOnly | 5ae5c086 |
| PrdSpecMtr lookup dependencies | 50183b6a |

Прикладные проверки и снимки оформлены отдельным завершающим коммитом. Прежние
незакоммиченные работы AF4.1–AF4.3 и другие изменения не включались.
