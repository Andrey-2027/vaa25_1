# Техническая проверка Б1/AF1 — 05.11.2026

Проверяется текущее рабочее дерево с незакоммиченными Б1, AF1 и соседними работами EM6.
Полный прогон не выполнялся по прямому указанию владельца от 05.11.2026. Новые экраны
и поведение формы в этом пакете не добавлялись; визуальная приёмка Б1 остаётся открытой.

## Изменения

- `LookupSelectionFilterIT` использует уникальный термин для своего набора. Рядом создаётся
  посторонняя запись с `B1` в коде: короткий общий термин воспроизводил бы прежнее падение.
  Проверка пустых ключей теперь сравнивает точные коды, а не только количество. Длины кодов
  согласованы с ограничениями моделей: номенклатура — 20, единица измерения — 10 символов.
- `LookupTargetFactsIT` проверяет цели ссылок из JPA вместо удалённых избыточных объявлений.
  Сохраняются проверки класса цели, адреса поля и наличия строк Explorer. У `SklNomOpa`
  проверяется наличие JPA-связи без `FieldMetadata` и отсутствие строки в Explorer.
  Избыточных объявлений цели lookup в модели приложения нет.
- В `effective-metadata-origins.txt` изменено только происхождение 17 целей ссылок:
  `EXPLICIT: 17 → 0`, `JPA_MAPPING: 14 → 31`. Сами значения effective-метаданных,
  снимок настроек форм и снимок поиска в текущем пакете не перезаписывались.
- API-эталоны перезаписаны адресно для `platform-contracts,platform-core`: пять удалённых
  атрибутов AF1, пять удалённых геттеров, общий `SelectionFilters`, перегрузка поиска
  с отбором и дополнения `SearchRead`. Старый конструктор `SearchRead` сохранён в эталоне.
  Эталон `platform-report-vaadin` не менялся. После записи выполнена проверка без write-флага.
- В матрице учтено только ребро Б1 `form → data`: диалог использует общий с автокомплитом
  построитель `SelectionFilters`. Fully-qualified вызов заменён обычным импортом.
- В test-usage overlay перечислены обращения снимка П.2 к реестру планов, представлению,
  серверному поиску, секциям и колонкам выбора. При сверке также зафиксированы существующие
  обращения `TwoDimensionRegisterCteSpikeTest` к реестру секций,
  `IndependentNoneRegisterFormTest` к `FormResolver` и `UniDrQueryUiTest` к мосту представления.
  Роли production-типов не повышались. Счётчик `PlatformVaadinModuleTest` согласован с
  реестром: 139 + существующий `CatalogOwnerForms` + `FilterableSearchFunction` Б1 = 141.
- Javadoc `Lookup` больше не утверждает, что `entity = Void.class` отключает выбор:
  цель выводится из JPA/Java-типа. Пример не требует избыточного объявления цели.

## Проверки

JDK Axiom 21.0.11, Maven 3.9.9 из IntelliJ, offline. Использован изолированный Maven repository
`outputs/verification/full-20261104-233835/repository` с ранее собранными из этого checkout
зависимостями. `platform-contracts` и `platform-vaadin` установлены в этот repository.
Обычный пользовательский `.m2` не перезаписывался. Временный POM приложения создан из
корневого POM с отдельным build directory `outputs/verification/b1-af1-20261105/application/target`.

Первые попытки столкнулись с отказом Windows при dynamic attach Mockito. Для повторов явно
передан `-DargLine=-javaagent:.../org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar`.
Промежуточная ошибка длины кода единицы в усиленной фикстуре исправлена; итоговый lookup-прогон зелёный.

| Набор | Последний результат |
| --- | --- |
| Core: `LookupReadLimitTest`, `EffectiveFieldFactsTest`, `FieldMetadataInlineFilterTest`, `CoreModuleCompositionTest` | 19, без ошибок |
| Vaadin: `EntityFieldSelectionFilterTest`, `SelectionColumnResolverTest`, `FieldFactoryLongBindingTest`, `LookupReadBoundaryTest`, `SelectionFormContextTest`, `FormResolverSelectionVariantTest`, `VaadinModuleCompositionTest` | 39, без ошибок |
| REST: `RestResourceBuilderTest` | 8, без ошибок |
| Приложение, целевые проверки Б1/AF1 — совокупность последних отчётов | 78, без ошибок |
| Отдельная диагностика `PlatformPackageDependencyMatrixTest` | 3, одно ранее известное падение вне Б1/AF1 |

78 проверок приложения: `FormSettingsSnapshotTest` (3), `LookupComboHelperTest` (9),
`LookupTargetFactsIT` (9), `NomAttributeValueItemFormTest` (12), `LookupSelectionFilterIT` (6),
`JavaEntityReaderParityTest` (2), `PatchedModelParityTest` (2), `FetchPlanLookupHydrationIT` (3),
`FormDeadApiTest` (2), `EffectiveMetadataSnapshotTest` (4), `PlatformApiBaselineTest` (4;
артефакты `platform-contracts,platform-core`), `PlatformCoreSurfaceTest` (7),
`PlatformVaadinSurfaceTest` (6), `PlatformVaadinModuleTest` (7), `SearchFieldParitySnapshotTest` (2).
Это составной результат целевых запусков, а не единый полный прогон приложения.

Логи: `outputs/verification/b1-af1-20261105/`: `core.log`, `vaadin-agent.log`, `rest.log`,
`application-agent.log`, `application-verify.log`, `lookup-final.log`, `vaadin-surface-verified.log`.
Итоговые XML приложения — в `application/target/surefire-reports`.

## Изменение формата снимка П.2 до текущего пакета

В журнале §12.2 записан исходный MD5 `934acd35d1127e5da64c0f1ca1f52154`, тогда как у
текущего эталона — `b377a6f81b1ec363001f83f085d73628`. Исходный файл найден в
`target/form-settings-current.txt` (03.11), текущий ресурс изменялся 04.11 в 19:50.
Оба сохранены в каталоге проверки как `form-settings-before-af1.txt` и `form-settings-after-af1.txt`.

Проверено точное равенство строк после единственной замены в исходном файле:
`" fieldColumns=[] fieldSearchFields=[]" → ""`. Вхождений ровно 23. Иных различий нет.
Это удалённые AF1.2 пустые атрибуты lookup; строки полей поиска, колонки, планы загрузки,
представления и подписи RLS совпадают без преобразований. Результат —
`snapshot-format-comparison.json`, `equalAfterRemovingOnlyThoseMarkers=true`.

Таким образом, требование плана о буквальном равенстве снимков имеет одно явно описанное
исключение формата AF1.2. Для AF2 исходный текущий эталон — MD5 `b377a6f81b1ec363001f83f085d73628`.

## Оставшиеся отдельные вопросы

- Матрица зависимостей падает только на `org.ipro.form.builtin → org.ipro.data`:
  существующий `ItemForm` использует `CatalogOwnerContext`. Это не изменение Б1/AF1;
  направление не было добавлено в reviewed-матрицу этим пакетом. Других новых рёбер тест не нашёл.
- `bootstrap-local-dependencies.ps1 -ValidateOnly` останавливается на прежнем дрейфе внешнего
  `filtergrid`: ожидается 177 файлов, фактически 178. Fingerprints contracts/core/vaadin проверены
  отдельно; contracts и vaadin обновлены после текущих правок. Остальные fingerprints и порядок
  зависимостей соседних работ не исправлялись.
- Визуальная приёмка Б1 владельцем и решение О6 (серверное правило типа номенклатуры) остаются
  отдельно от технической проверки. Серверная проверка типа по плану не входит в Б1.
- Планы и evidence исключаются текущим `*.md` в `.gitignore`; при будущем коммите их необходимо
  добавить явно. Коммитов в этом пакете не создавалось.
