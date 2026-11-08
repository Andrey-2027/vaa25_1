# AF2: представление отдельно от колонок — 05.11.2026

Реализованы AF2.1–AF2.5. Полный прогон не выполнялся по указанию владельца.
Независимая декларация пары RLS добавлена по отдельному решению владельца 05.11;
соответствующее исключение внесено в Р4 и AF2.2 обоих планов. О3 об объединении остальных
аннотаций не решалось этим пакетом.

## Реализация

- `PresentationResolver` в `platform-core` разделяет имя, поиск, сортировку и подписи RLS.
  Имя сохраняет `InstanceNameResolver`/legacy `getDisplayName`; сортировка читает канонический
  `getDisplaySortFields`, включая умолчания стандартных базовых сущностей. Поиск:
  `@SearchFields` → строковые части `@InstanceName` → существующие строковые `code,name`
  для известного метаданным типа. Правила явных полей вызывающего (strict/tolerant) сохранены.
- `@RlsLabels(code, name)` в `platform-contracts`: самостоятельные пути, наследуемые от базы.
  Пустой code означает отсутствие кода, пустой name — отображаемое имя. Без декларации
  используются существующие поля `code`/`name`. Ошибка пути даёт отказ при старте с классом,
  ролью и путём; это проверено через настоящую автоконфигурацию.
- Нестандартные пары объявлены на 12 классах моделей, в том числе на общей базе двух
  документов `AccountingObjectDocument`. Пара `number,date` объявлена и на платформенной
  `StandardDocumentEntity`: захваченным рецептам не требуется менять сгенерированный текст.
- `NomenclatureAccountingObject` и `User` получили явные `@SearchFields`, сохраняющие прежний
  серверный поиск после удаления зависимости от явных и автоматически выведенных колонок.
  Колонки моделей, имя, сортировка и существующие `@Lookup.fetch` не переносились и не менялись.
- `FieldFactory`, `ContextFilterPanel` и `LookupComboHelper` используют общий вход представления.
  Вариант колонок выбора не подменяет поля поиска. Диалог по-прежнему разрешает и валидирует
  свой вариант и передаёт пути активных колонок в чтение дополнительно.
- LOOKUP собирается из потребностей представления, поиска, RLS и прежних `@Lookup.fetch`.
  `FetchGraphs` получает пути имени через существующий нейтральный `InstanceNameSource`:
  зависимости `metadata → data/fetch` не добавлены. Для legacy `getDisplayName` зависимости
  не объявлены, поэтому fallback консервативно включает опубликованные ссылки и известные
  пути `displaySortFields`. Для `@InstanceName` используются объявленные пути имени.
- `PresentationBoundaryTest` запрещает серверным потребителям `getSelectColumnPaths` в core,
  autoconfigure, RLS и REST. Исключён только сам getter-хранилище `EntityMetadataInfo`.

## Сверка К2

Исходник: `outputs/verification/af2-20261105/form-settings-before-af2.txt`, 23 сущности,
485 строк, MD5 `b377a6f81b1ec363001f83f085d73628`. Новый снимок —
`form-settings-after-af2.txt`, MD5 `4c089da0fea2aecf2eb30c96f2b28dff`.
Ключи строк совпадают; из 42 отличающихся значений 27 — поля автокомплита, 15 — fetch-пути.
Никаких других отличий нет. `snapshot-changes.json` содержит каждую строку до/после,
`snapshot.diff` — полный diff. Снимок серверного поиска `search-fields-after.txt` не менялся.

Для каждой сущности проверены четыре роли (именно значения, а не происхождение декларации):

| Сущность | Имя | Серверный поиск | Сортировка | Подписи RLS |
| --- | --- | --- | --- | --- |
| AccountingObjectCorrection | совпадает | совпадает | совпадает | совпадает |
| AccountingObjectInstallation | совпадает | совпадает | совпадает | совпадает |
| AccountingType | совпадает | совпадает | совпадает | совпадает |
| AttributeType | совпадает | совпадает | совпадает | совпадает |
| AttributeValue | совпадает | совпадает | совпадает | совпадает |
| Branch | совпадает | совпадает | совпадает | совпадает |
| GridFormView | совпадает | совпадает | совпадает | совпадает |
| GroupNom | совпадает | совпадает | совпадает | совпадает |
| Journal | совпадает | совпадает | совпадает | совпадает |
| NomAttributeValue | совпадает | совпадает | совпадает | совпадает |
| NomSklAttribute | совпадает | совпадает | совпадает | совпадает |
| Nomenclature | совпадает | совпадает | совпадает | совпадает |
| NomenclatureAccountingObject | совпадает | совпадает | совпадает | совпадает |
| NomenclaturePrice | совпадает | совпадает | совпадает | совпадает |
| Oper | совпадает | совпадает | совпадает | совпадает |
| PrdSpec | совпадает | совпадает | совпадает | совпадает |
| PrdSpecMtr | совпадает | совпадает | совпадает | совпадает |
| ReceivingDocument | совпадает | совпадает | совпадает | совпадает |
| Role | совпадает | совпадает | совпадает | совпадает |
| SklNomOpa | совпадает | совпадает | совпадает | совпадает |
| UnitOfMeasurement | совпадает | совпадает | совпадает | совпадает |
| User | совпадает | совпадает | совпадает | совпадает |
| Workshop | совпадает | совпадает | совпадает | совпадает |

Колонки списка/выбора, ширины, поля карточек, свойства полей и состав табличных частей
также совпадают. Переопределение RLS отделено от сортировки: `NomSklAttribute` сохраняет
`nomenclature,attrType`, хотя сортируется по `attrType,nomenclature`.

### Изменения автокомплита

| Цель | Было в каждом из трёх каналов | Стало, как на сервере | Причина |
| --- | --- | --- | --- |
| Nomenclature | code,name,typeNom | code,name | строковые части существующего InstanceName |
| PrdSpec | codeSpec,nomenclature.name | codeSpec,draft | существующая SearchFields |
| UnitOfMeasurement | code,name,shortCode | shortCode | существующая SearchFields |

Это 27 строк по существующим ссылочным полям снимка. Остальные наблюдаемые цели и каналы
совпадают с исходником. Изменения соответствуют устранению расхождений AF2.1.

### Изменения загрузки

| Где | Изменение | Обоснование |
| --- | --- | --- |
| AccountingObjectCorrection, AccountingObjectInstallation: LOOKUP | удалены workshop,workshop.branch | имя и RLS используют number/date; пути колонок выбора запрашивает диалог |
| ReceivingDocument: LOOKUP | удалены journal,receivingWorkshop,receivingWorkshop.branch,transferringWorkshop,transferringWorkshop.branch | имя использует number/date, RLS — id/number; пути колонок запрашивает диалог |
| AttributeValue: LOOKUP | добавлен attrType | консервативный fallback опубликованных ссылок legacy-имени |
| NomAttributeValue: LIST/DETAIL/LOOKUP/ROW; строка в Nomenclature | добавлен attrValue.attrType | тот же fallback при углублении имени AttributeValue |
| PrdSpec: LOOKUP | добавлен journal | консервативный fallback legacy-имени; nomenclature и Lookup.fetch сохранены |
| PrdSpecMtr: LIST/DETAIL/LOOKUP/ROW; строка в PrdSpec | добавлен prdSpecMtr.journal | углубление ссылки на PrdSpec по тому же правилу |

Всего 15 строк. Консервативный fallback местами расширяет загрузку; это осознанная цена
поддержки legacy `getDisplayName` без анализа тела Java-метода. Новая зависимость будущего
имени должна объявляться вместе с его составом. Референсные поля открытых колонок списка
и диалога продолжают передавать собственные пути. Причина явных путей InstanceName в
плане сохранена как `instance-name`, базовые потребности LOOKUP помечены `presentation:LOOKUP`.

## Проверки и поставка

JDK Axiom 21.0.11, Maven offline. Изолированный repository предыдущей проверки использован
для целевых прогонов; Mockito запускается с явным javaagent. После проверки четыре модуля
успешно установлены в обычный локальный Maven repository без запуска тестов; логи
`local-install-platform-*.log` сохранены в каталоге проверки.

| Набор | Тестов | Результат |
| --- | --- | --- |
| contracts: состав/зависимости, lifecycle identity | 6 | зелёные |
| core: PresentationResolverTest, CoreModuleCompositionTest, EffectiveFieldFactsTest | 20 | зелёные |
| autoconfigure: FetchPlanInstanceNameAutoConfigurationTest | 7 | зелёные |
| vaadin: выбор/отбор, резолвер вариантов, граница чтения, состав модуля | 31 | зелёные |
| приложение: целевые проверки AF2, метаданных, Б1 и архитектуры | 95 | зелёные |
| **Сумма различных проверок по последним отчётам** | **159** | **без failures/errors/skips** |

95 проверок приложения: снимок форм (3), снимок поиска (2), реальные AccountingType/Workshop
и независимые роли (2), SearchFieldResolverTest (8), FetchPlanRegistryTest (12),
LookupComboHelperTest (9), граница AF2.5 (1), гидратация Lookup.fetch (3), API-baseline (4),
состав contracts (4), состав core (6), поверхность core (7), поверхность vaadin (6),
FormDeadApiTest (2), effective metadata (4), Б1 (6), JavaEntityReader parity (2),
PlatformArchitectureTest (3), PlatformDependencyDirectionTest (10), замеры D1 (1).

API-эталоны обновлялись адресно для contracts/core/autoconfigure и проверены без write-флага.
Генерируемые замеры D1 обновлены тем же тестом, включая ранее накопленный дрейф счётчиков:
app 235→240, platform artifacts 793→806, named types 323→349, API 251→252. Роли чужих типов
не назначались автоматически. Состав core — 129 типов, contracts — 37; PresentationResolver
имеет MODULE_API, RlsLabels — API декларации модели. Матрица дополнена только рёбрами AF2
`fetch.plan → data` и `fetch.config → data`. Test-usage снимка сокращён после удаления
обращений к FormRegistry/SelectionColumnResolver. Существующая ссылка Listagg spike на
SectionMetadataRegistry зафиксирована отдельно, без изменения бизнес-кода теста.

Артефакты: `outputs/verification/af2-20261105/`: логи модулей, `application-initial.log`,
`application-final.log`, `application-accepted.log`, итоговые XML в `application/target/surefire-reports`,
`application-summary.json`, исходные и итоговые снимки, diff, `fingerprints.json`, bootstrap log.
Начальное падение проверки причины `instance-name` исправлено в сборщике плана, ожидание
теста сохранено. Падение overlay существующего Listagg spike устранено адресной записью.

Bootstrap ValidateOnly сохраняет прежнюю остановку на внешнем FilterGrid (177→178 файлов).
Ребро `form.builtin → data` механизма владельца этим пакетом не исправлялось. Полный прогон
приложения и полная классификация публичной поверхности не заявляются. Визуальная приёмка
Б1, К1 и открытые решения следующих этапов остаются отдельными задачами.
