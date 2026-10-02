# F-REST-READ-2: каталог ресурсов и проверка деклараций — подробный план

- Статус: каталог, startup wiring и пилоты приложения реализованы; модульный gate и API baseline проходят. Три замечания mapping исправлены и покрыты regression tests; общий surface/bootstrap gate остаётся открытым. Evidence и остаток приведены в §10.
- Дата: 2026-10-02; уточнено после проверки замечаний к плану
- Родительский план: [`rest-read-api-plan.md`](rest-read-api-plan.md)
- Предыдущий срез: [`rest-read-api-stage1-builder-plan.md`](rest-read-api-stage1-builder-plan.md)
- Результат: immutable каталог явных деклараций, проверенных по текущему JPA metamodel,
  экспозиции и read capabilities; требования загрузки и будущей авторизации подготовлены

## 1. Назначение и результат

Builder первого этапа проверяет структуру объявления. Второй этап устанавливает,
что `PrdSpec`, `journal.id` и `nomenclature.code` действительно существуют в текущем
persistence unit, соответствуют внешним типам и допустимому профилю публикации.
Ошибки прикладной декларации обнаруживаются при старте приложения.

Каталог собирается только из `RestResourceDefinition<?>`, объявленных приложением
как Spring beans. Присутствие сущности в metamodel или `EntityDescriptorCatalog`
не создаёт REST-ресурс. Для второго потребителя того же контракта дополнительная
регистрация ресурса не требуется; два объявления одной пары `(resource, major)`
являются ошибкой, даже если их содержимое совпадает.

Выход каталога содержит разрешённые метаданными пути, ограничения профиля, раздельные
требования загрузки `LIST`/`DETAIL` и требования к будущей проверке доступа.
Проверка metamodel не выдаёт субъекту права и не означает готовности HTTP-публикации.

## 2. Входные условия и границы

### Перед реализацией

1. Перепроверить приёмку первого этапа после исправлений ревью: signature baseline
   `platform-rest.api`, реестр `platform-rest-surface.txt`, роль типов в общем реестре
   приложения и проверку разрешённого Maven-графа. Наличие правок в дереве должно
   сопровождаться результатами тестов; прежние отказы не считаются закрытыми автоматически.
2. Проверить транзитивное отсутствие `platform-rest` в существующих starters. Проверки
   прямого текста POM для этого недостаточно.
3. Убрать `typeNom -> nomenclature.typeNom` из рабочего bean спецификаций; сохранить
   его в отдельном syntax-fixture builder. Прямой фильтр `Nomenclature.typeNom` остаётся.
4. Зафиксировать результат модульного `verify` и общих surface guards. Расхождение
   fingerprint соседнего FilterGrid из ревью первого этапа проверить повторно и,
   если оно сохраняется, записать как внешнюю проблему общей проверки bootstrap.

Подготовка этого плана допустима до закрытия перечисленных замечаний. Приёмка второго
этапа требует подтверждённого первого gate. Готовность C5 не требуется для проверки
метаданных; C5 и gate этапа C остаются обязательными перед открытием маршрутов.

### Входит во второй этап

- Сбор явных bean-деклараций, уникальность `(resource, major)`, immutable индексы.
- Проверка типа в текущем persistence unit и `EntityDescriptorCatalog`.
- Проверка `STANDARD_ROOT` и наличия `FetchScenario.LIST` и `DETAIL`.
- Разрешение полей, фильтров, сортировки и `id` по JPA metamodel.
- Проверка внешнего типа, формата, nullability и ограничений пути.
- Раздельные фиксированные требования загрузки из maximum-полей `list/detail`.
- Учёт всех путей, для которых далее понадобятся attribute/reference permissions.
- Регистрация каталога модулем, диагностика старта и проверка границ зависимостей.

### Следующие этапы

- C5: гранты субъектов, entity/attribute/API permissions и правило для ссылочной цели.
- Узкий canonical read SPI, SQL-компиляция фильтров, фактический fetch-граф и cost gate.
- HTTP-аутентификация, маршруты, DTO-проекция, Problem Details и OpenAPI.
- Запись, синхронизация, cursor pagination, owned-секции и вычисляемые поля.

Второй этап не выполняет чтение бизнес-данных. Инициализация persistence unit может
выполнять служебные действия ORM; каталог после создания metamodel не вызывает
repository, `EntityManager.find`, JPQL или native SQL и не обращается к таблицам данных.

## 3. Место реализации и зависимости

Рекомендуется продолжить существующий optional артефакт `platform-rest`, разделив
пакеты декларации, каталога и регистрации:

| Область | Назначение и граница |
| --- | --- |
| `org.ipro.rest.api` | Существующий прикладной builder и immutable definition. Java API остаётся независимым от Spring/JPA; его сигнатуры не меняются для подключения каталога. |
| `org.ipro.rest.catalog` | Внутренние каталог, разрешение путей, проверка профиля и диагностика. Может использовать JPA metamodel и APP_API/MODULE_API ядра. |
| `org.ipro.rest.config` | Принадлежащая REST-модулю auto-configuration и проверка полноты startup wiring. |

Предлагаемые имена `RestResourceCatalog`, `RestResourceKey`, `ResolvedRestResource`
и `ResolvedRestPath` уточняются на API-review. Это внутренние исполнители модуля,
а не новый прикладной SPI. Если для Spring нужен технически `public` класс, его
семантическая роль всё равно фиксируется как `INTERNAL`. Прикладной код продолжает
объявлять только `RestResourceDefinition<?>`.

Доступность исходных типов подтверждена по текущим реестрам:

| Типы | Артефакт | Подтверждённая роль |
| --- | --- | --- |
| `EntityDescriptorCatalog` | `platform-core` | `MODULE_API` |
| `EntityDescriptor`, `EntityCapabilities`, `ManagedEntityCatalog` | `platform-core` | `APP_API` |
| `EntityExposure`, `FetchScenario` | `platform-contracts` | `API` в общем реестре приложения |

Источники — [`platform-core-surface.txt`](../../src/test/resources/platform-core-surface.txt)
и [`platform-public-surface.txt`](../../src/test/resources/platform-public-surface.txt).
Для перечисленных зависимостей повышение роли не требуется. Дополнительные типы,
если они понадобятся при реализации, проверяются отдельно; технический `public`
не заменяет разрешённую семантическую роль.

Для runtime-каталога разрешается зависимость `platform-rest -> platform-core`,
`platform-spring-boot-autoconfigure`, Spring Boot auto-configuration, JPA API и
Hibernate runtime mapping API. Для типизированного порядка после
`DataAccessAutoConfiguration` предлагается зависимость на
`platform-spring-boot-autoconfigure`; соответствующие прямые POM-рёбра отражаются
в `scripts/local-dependencies.json`. Обратной зависимости core/backend configuration
на REST нет. Vaadin и Spring Web не входят в compile-граф самого `platform-rest`;
транзитивный `spring-web` от `platform-telemetry` исключён на REST-рёбрах, а
приложение, использующее этот web-адаптер, объявляет собственную web-зависимость.

### Решение API-review и источника mapping (2026-10-02)

Внутренний query-контракт зафиксирован без изменения `org.ipro.rest.api`:

- `RestResourceCatalog.find(String, int)` возвращает `Optional<ResolvedRestResource>`;
  `resources()` перечисляет immutable записи в порядке `(resource, major)`.
- `ResolvedRestResource.projection(RestReadOperation)` возвращает maximum/default и
  разрешённые поля по alias; `fetchRequirements(operation)` даёт фиксированный
  дедуплицированный список association paths из maximum-полей.
- `accessRequirements(operation)` возвращает `RestResourceAccessRequirements` с ключом,
  entity type, LIST/DETAIL и ordered списком alias/use/path требований для output,
  filter и sort. Это только требования метаданных, без permission ids, грантов и allow.

Стандартный JPA metamodel не подтверждает effective local-FK mapping после XML override,
join table и alternate referenced column. Первый профиль поэтому целенаправленно
поддерживает Hibernate 7 mapping model: `SessionFactoryImplementor`, `EntityPersister`,
`ToOneAttributeMapping` и `ForeignKeyDescriptor`. Последний ToOne type находится в
Hibernate implementation package; это осознанная версионная граница, закреплённая
проверками на настоящем Hibernate metamodel. Если provider не Hibernate, каталог
останавливает старт с `UNSUPPORTED_PERSISTENCE_PROVIDER` и не делает annotation-only
fallback.

Для `association.id` одновременно проверяются `MANY_TO_ONE`, ссылка на primary id,
отсутствие join table, один non-formula key/target JDBC value и key table, совпадающая
с root table. Nullable/basic mapping берётся из runtime Hibernate selectable metadata;
аннотации Java не являются fallback-источником. На compile-графе REST исключён
транзитивный `spring-web`, принесённый `platform-telemetry` через `platform-core`.

Это намеренное развитие состава артефакта: правило первого этапа «весь module compile
classpath состоит только из JDK» заменяется проверкой разрешённых runtime-зависимостей.
Отдельная проверка сохраняет JDK-границу пакета `rest.api`. Реестры ролей должны отличать
новые внутренние классы от существующего прикладного API; простого списка всех public
классов без ролей недостаточно. Новый артефакт только ради каталога пока не требуется.

## 4. Регистрация и поведение при старте

Auto-configuration регистрируется своим `AutoConfiguration.imports` внутри
`platform-rest`. Приложение подключает артефакт и объявляет ресурсы, без ручного
`@Import` каталога. Порядок после backend/JPA-конфигурации задаётся явно и проверяется
контекстными тестами; алфавитный порядок имён не используется как гарантия.

| Контекст | Ожидаемое поведение |
| --- | --- |
| Нет `platform-rest` | Нет каталога и REST-регистраций. |
| Модуль есть, деклараций нет | Пустой каталог; наличие JPA-бинов не требуется, entity scan не публикует ресурсы. |
| Есть декларации и необходимые backend-бины и метаданные | Все декларации проверяются, затем публикуется один immutable каталог. |
| Есть декларации, отсутствует `EntityManagerFactory` или `EntityDescriptorCatalog` | Названная ошибка старта; backoff не скрывает неработающее объявление. |
| Не определён единственный persistence unit для каталога | Ошибка старта; первый профиль не выбирает случайный factory и не объединяет metamodel разных unit. |
| Одна декларация неверна | Старт не завершается успешно; каталог с остальными ресурсами не публикуется частично. |

Матрица описывает REST-регистрацию в partial/full контекстах. Полный старт приложения
также проверяет wiring остальных backend-компонентов; эти ошибки не скрываются
пустым REST-каталогом.

Первый профиль работает с одним persistence unit и согласованным набором каталогов
платформы. Поддержка нескольких unit с выбором источника на ресурс — отдельное
расширение. При наличии деклараций несколько самостоятельных `EntityManagerFactory`
beans дают `AMBIGUOUS_PERSISTENCE_UNIT`, в том числе при наличии `@Primary`.
Прикладной primary не является выбором persistence unit для REST. Bean aliases
одного factory не считаются самостоятельными factories. Эта проверка не требуется
для пустого каталога, который не использует backend.

При наличии REST-деклараций catalog дополнительно требует точного равенства entity-набора
metamodel выбранного factory и `EntityDescriptorCatalog`. Это намеренный инвариант общей
границы backend: стандартная конфигурация строит descriptor для каждого entity из того же
`ManagedEntityCatalog`, а REST использует эти descriptors для проверки экспозиции и
capabilities. Новый JPA entity при штатном wiring классифицируется автоматически. Если
descriptor-набор собран для другого или частичного persistence unit, старт завершается
`INCONSISTENT_BACKEND_METADATA` с перечнем недостающих и посторонних типов; REST не
собирает собственную частичную копию каталога. Поддержка slice-каталогов потребует отдельного
явного выбора набора entity.

Каталог не заменяется приложением для ослабления проверки, отсутствует
флаг «пропускать неверные ресурсы». Смена деклараций требует перезапуска; динамический
rebuild и административное редактирование схемы в этот срез не входят.

Декларации сортируются по `(resource, major)`; порядок полей внутри каждой сохраняется.
Диагностика содержит bean-источник, ключ, major, элемент, исходный путь и код причины.
Коллизия называет оба bean-источника. Независимые ошибки можно собрать за один проход;
после ошибки пути зависимые проверки этого пути пропускаются, чтобы не порождать
вторичные NPE или ложные сообщения. Итоговый порядок ошибок детерминирован.

## 5. Правила проверки JPA-путей

Источник истины — metamodel текущего `EntityManagerFactory`. Каталог использует
платформенный `ManagedEntityCatalog`/`EntityDescriptorCatalog` для границы управляемых
типов и экспозиции, без нового classpath scan.

Разрешение учитывает фактический JPA access и наследуемые persistent attributes.
Свойство Java, помеченное `@Transient`, не становится допустимым только потому,
что существует поле или getter. `ColumnPath` и UI `FieldType` не заменяют проверку
persistence mapping и не задают внешнюю REST-схему.

### 5.1. Тип ресурса и идентификатор

- Тип есть в текущем metamodel именно как entity; descriptor найден, управляем JPA,
  имеет `STANDARD_ROOT`, позволяет `LIST` и `DETAIL`.
- `OWNED_ROW`, `INTERNAL_STORE`, `UNCLASSIFIED` и типы только с custom/owner-путём
  автономно не публикуются этим каталогом.
- Обязательное публичное `id` указывает прямо на реальный одиночный root id attribute;
  допускается другое внутреннее имя id и наследование из mapped superclass.
- Для первого профиля предлагаются простые `Long`, `Integer`, `String` id с
  соответствующими внешними типами. Composite id, `@EmbeddedId`, `@IdClass` и UUID id
  отклоняются до отдельного решения о внешнем кодировании/разборе.
- `id` объявлен `NOT_NULL` и включён в maximum/default обеих операций. Тип из
  metamodel сверяется с декларацией, универсальное предположение «любой id — Long»
  отсутствует. Проверка C5 attribute read для обязательного id выполняется позже.

### 5.2. Допустимые формы путей первого профиля

| Использование | Разрешённая форма | Отказ |
| --- | --- | --- |
| Поле ответа | Root BASIC scalar либо один переход через to-one к BASIC scalar/id | Коллекция на любом сегменте, объект/entity в конце, transient, неизвестный сегмент. Более глубокая цепочка и embedded path отложены. |
| `EQUALS` фильтр | Root BASIC scalar; либо `association.id` по owning many-to-one с простым локальным FK | To-one к произвольному полю, inverse-связь, join table, составной FK и неподтверждённая форма mapping. |
| Сортировка | Объявленное root scalar поле, доступное в maximum `list`, либо root id | Переход через ссылку, коллекция или поле только из detail. |

`journal.id` относится к кандидату на локальный FK-фильтр; `nomenclature.typeNom`
не допускается как фильтр. Для ссылочного фильтра первого профиля проверяются:

- вид атрибута `MANY_TO_ONE`, одиночная связь и реальный простой id целевого типа;
- локальный одиночный FK в owning mapping, ссылающийся на primary id целевого типа,
  без join table и составных join columns; ссылка на иной unique attribute не
  классифицируется как локальный FK-фильтр по id;
- persistent member из `Attribute.getJavaMember()`: при property access это getter,
  при field access — поле; поиск только по Java-полям не заменяет effective mapping;
- источник подтверждения effective FK mapping, согласованный на API-review.
  Java-аннотации не должны молча перекрывать XML mapping или association overrides.
  Неподтверждённая форма mapping получает `UNSUPPORTED_FILTER_PATH`.

`OneToOne.id` не входит в первый профиль фильтров, даже если конкретная связь owning.
Стандартный [JPA Attribute API](https://jakarta.ee/specifications/persistence/3.2/apidocs/jakarta.persistence/jakarta/persistence/metamodel/attribute)
даёт вид атрибута и Java member; наличие `association.id` само по себе не доказывает
SQL без join. Фактическая SQL-форма проверяется на этапе компиляции/исполнения read
запроса. Отдельно подтверждается соблюдение прав на ссылочную цель: отсутствие join
не является доказательством авторизации.

Более широкая грамматика `RestPropertyPath` первого этапа сохраняется: builder может
описать путь, который текущий каталог отвергнет как неподдержанный профиль. Каталог
не удаляет такой фильтр молча и не переносит отказ на первый HTTP-запрос.

### 5.3. Внешние типы, форматы и nullability

Начальная таблица соответствия для API-review:

| Persistent Java type | `RestFieldType` | Формат первого профиля |
| --- | --- | --- |
| `String` | `STRING` | `NONE` |
| `boolean` / `Boolean` | `BOOLEAN` | `NONE` |
| `int` / `Integer` | `INTEGER` | `NONE` |
| `long` / `Long` | `LONG` | `NONE` |
| `BigDecimal` | `DECIMAL` | `NONE` |

Примитив и wrapper нормализуются для проверки типа. Не выполняются автоматические
сужения `Long -> INTEGER`, преобразования неизвестного объекта в строку или вывод
формата из UI metadata. Enum, пользовательские converted/JSON-типы, даты и UUID
нуждаются в явном решении о представлении; до этого получают отказ каталога.
`RestFieldFormat.UUID/DATE/DATE_TIME` остаются допустимыми структурными декларациями
builder, но не обещают готовый serializer. Поддержка этих форматов добавляется после
согласования codec, включая формат фильтра; для двух исходных пилотов она не нужна.

Для `NOT_NULL` проверяется вся цепочка: optional to-one либо nullable terminal
не позволяет обещать обязательное значение. `NULLABLE` допускается как более слабое
обещание, даже если mapping обязательный. Неопределённая nullability не превращается
в `NOT_NULL` по предположению. Проверка опирается на mapping/metamodel и не доказывает
состояние уже записанных данных или соответствие схемы БД mapping.

Таблица, правила определения nullability и распознавания custom mapping должны
получить тесты на реальном JPA metamodel до стабилизации реализации; mocks являются
только дополнительными тестами отдельных отказов.

## 6. Неизменяемый результат и требования загрузки

Для каждой пары `(resource, major)` каталог хранит исходное immutable definition
и внутренний разрешённый результат:

| Часть | Содержание |
| --- | --- |
| Идентичность | Ключ, major, entity type, bean-источник |
| Root | Descriptor и подтверждённые read capabilities |
| Поля | Alias, persistent path, конечный Java type, внешний type/format/nullability |
| Операции | Раздельные maximum/default списки и фиксированные требования загрузки |
| Фильтры/сортировка | Разрешённые пути и их классификация, без готового `Specification` |
| Доступ | Полный перечень root/attribute/reference требований для будущего C5 wiring |

Индексы по `(resource, major)` и перечисление всех ресурсов immutable. Поиск неизвестного
ключа возвращает отсутствие записи, без fallback по Java-имени. Один тип может быть
явно объявлен под несколькими ресурсами/major; индекс только по `Class<?>` не становится
основной идентичностью. Не нужен отдельный controller или DTO на ресурс.

### Контракт чтения каталога

На API-review зафиксирован внутренний query API, по которому следующие компоненты
получат эти сведения. Сигнатуры остаются внутренней поверхностью REST-модуля.

| Операция внутреннего контракта | Результат |
| --- | --- |
| `catalog.find(resource, major)` | `Optional<ResolvedRestResource>`; неизвестная пара не создаёт fallback. |
| `catalog.resources()` | Immutable перечисление в стабильном порядке `(resource, major)`. |
| `resource.projection(operation)` | `RestResourceProjection`: maximum/default поля и `ResolvedRestField` по alias для `LIST` либо `DETAIL`. |
| `resource.fetchRequirements(operation)` | Фиксированные REST association paths этой операции. |
| `resource.accessRequirements(operation)` | `RestResourceAccessRequirements`: root/API-операция и ordered attribute/reference требования по alias/use: вывод, фильтр, сортировка. |

Требования доступа сохраняют persistent путь и целевой тип, а не готовые permission
ids, гранты или решения C5. Будущий обработчик выбирает требования для фактически
запрошенных полей, активных фильтров и сортировки, включая обязательный `id`.
Объединение всех maximum-полей не подменяет эту выборку. Фиксированный fetch-набор
остаётся отдельным требованием загрузки; семантика серверного применения attribute
permissions согласуется с C5 на следующем этапе.

Возвращаемые списки и карты immutable. Query API не исполняет SQL, не возвращает
`EntityManager`/`EntityGraph` и не выдаёт `allow` по результату metadata-проверки.
Контекстные тесты проверяют раздельность операций, использование alias и сохранение
всех attribute/reference требований. Эти методы остаются внутренним контрактом
REST-модуля и не расширяют прикладной builder API.

Пути загрузки выводятся из maximum-полей операции. Например, `nomenclatureCode`
требует ассоциацию `nomenclature`; `journalId` в maximum detail требует `journal`.
Если несколько aliases используют одну ассоциацию, она включается один раз.
Фильтр сам по себе не добавляет association в граф вывода. Неиспользуемые объявленные
поля проверяются, но не расширяют fetch-набор операции. Клиентский выбор `fields`
в будущем меняет только JSON, а не этот фиксированный набор.

Эти наборы — требования REST, а не окончательный runtime `EntityGraph`.
`fetchRequirements()` остаётся association-only summary; полные source paths,
включая root scalars и terminal attributes/id, доступны через `projection(operation)`
и `ResolvedRestField.source()`. На третьем этапе из них выводится самостоятельный
fixed profile с explicit subgraphs и обязательным root id, а не UI scenario plan
с extras. Пустой association summary скалярного ресурса не означает отсутствие графа.

Согласно [ADR-0007 §4.1](decisions/ADR-0007-canonical-data-access-path.md#rest-fixed-fetch-profile)
и [контракту третьего этапа, §4.2.1–§4.2.3](rest-read-api-stage3-security-read-plan.md),
REST не добавляет metadata/InstanceName/display dependencies. Построение остаётся
в единственной canonical graph boundary; UI readers сохраняют union/deepen semantics.
Третий этап проверяет **итоговый** graph tree, actual loaded state, стоимость и
отсутствие незаявленных association/collection loads. Второй этап не строит граф,
не использует отдельный `EntityManager` и не объявляет проверку N+1 выполненной.

## 7. Граница с C5 и защищёнными ссылками

Каталог фиксирует, какие persistent attributes используются для вывода, фильтрации
и сортировки. Alias не заменяет persistent attribute при проверке прав. Для to-one
пути сохраняются также целевой тип, атрибут связи и terminal attribute/id;
нельзя проверять только финальный `code` или только публичное имя поля.

`nomenclature.code` может пройти метаданные и войти в каталог как требование чтения
ссылки. Это не означает, что пользователь вправе читать связанную номенклатуру.
Для `journal.id` также сохраняется требование проверки ссылки и семантики FK-фильтра.
Отсутствие RLS у целевого класса не заменяет C5 entity/attribute permissions.

У результата второго этапа нет признака «безопасно выполнять» или универсального
`allow` по отсутствию security collaborator. Он не предоставляет метод исполнения.
Будущий адаптер допускается к выполнению только после внедрения C5, правила для
ссылочной цели и принятого read SPI. Требования не теряются при переводе декларации
в разрешённый результат. Невалидная metadata отклоняется сейчас; ещё не реализованное
security-решение сохраняется как обязательное условие следующего этапа.

## 8. Диагностика и проверки

Предлагается отдельный внутренний `RestResourceCatalogException` с кодами каталога.
Коды builder не расширяются только ради внутренней JPA-механики. Конкретные названия
ниже — проект словаря диагностики старта; они не становятся HTTP Problem Details.

| Сценарий | Ожидаемый результат |
| --- | --- |
| Дублируется `(resource, major)` | `DUPLICATE_RESOURCE`, оба bean-источника |
| Тот же ресурс в v1 и v2 | Две независимые записи, без взаимного изменения |
| Есть декларации, backend wiring отсутствует/неоднозначен | `BACKEND_CONTEXT_REQUIRED` / `AMBIGUOUS_PERSISTENCE_UNIT` |
| Два самостоятельных factories, включая вариант с `@Primary` | `AMBIGUOUS_PERSISTENCE_UNIT`; primary не скрывает второй unit |
| Тип не entity текущего unit | `UNMANAGED_RESOURCE_TYPE` |
| Неподходящая экспозиция или нет LIST/DETAIL capability | `UNSUPPORTED_EXPOSURE` / `READ_CAPABILITY_REQUIRED` |
| Неизвестный, transient или неверно продолженный путь | `INVALID_PERSISTENT_PATH`, конкретный сегмент |
| Коллекция на любом сегменте либо entity/object terminal | `COLLECTION_PATH_NOT_SUPPORTED` / `NON_SCALAR_TERMINAL` |
| Внешний type/format не соответствует mapping | `SCALAR_MAPPING_MISMATCH` / `UNSUPPORTED_SCALAR_MAPPING` |
| NOT_NULL против nullable цепочки | `NULLABILITY_MISMATCH` |
| id не root id, неподдержанный/составной id | `INVALID_ID_MAPPING` / `UNSUPPORTED_ID_MAPPING` |
| Реальная fixture с `@EmbeddedId` | `UNSUPPORTED_ID_MAPPING` до публикации каталога |
| Реальная fixture с `@IdClass` | `UNSUPPORTED_ID_MAPPING` до публикации каталога |
| Простой String/Integer id с правильной декларацией | Успешная проверка; отсутствие предположения «любой id — Long» |
| Фильтр/сортировка за границей первого профиля | `UNSUPPORTED_FILTER_PATH` / `UNSUPPORTED_SORT_PATH` |
| `OneToOne.id`, many-to-one через join table, ссылка на не-id attribute или неподтверждённый FK mapping | `UNSUPPORTED_FILTER_PATH` |
| Неопубликованная JPA-сущность существует в unit | В каталоге отсутствует |

Обязательны тесты с реальным metamodel для field access и property access, inherited
id, nullable to-one, owned row, transient property и коллекции на промежуточном
сегменте. `@EmbeddedId` и `@IdClass` проверяются отдельными entity fixtures;
тип ошибки подтверждается на настоящем metamodel. Два factory проверяются с
декларацией ресурса в обоих вариантах: без primary и с primary. Проверить field/property
access для FK-фильтра и отказ при неподдержанном effective mapping.
Проверить, что смена порядка bean-регистрации не меняет каталог и диагностику.
С одним неверным ресурсом контекст не публикует частично успешный каталог.

## 9. Порядок работ

1. **Закрыть входные замечания первого этапа.** Перепроверить baseline, роли,
   разрешённый compile-граф и starter closure. Сузить рабочую декларацию спецификаций;
   сохранить экспериментальный фильтр в syntax-fixture. Записать фактические результаты.
2. **Согласовать внутреннюю модель и зависимости.** Зафиксировать packages, роли
   новых типов, query API из §6, ключ каталога, первый mapping-профиль, источник
   проверки effective FK mapping и поведение startup wiring. Исходные роли ядра
   подтверждены в §3; дополнительные зависимости проверяются по D3.9. Если поверхность
   ядра действительно меняется, отдельно объяснить изменение, не расширять её автоматически.
3. **Реализовать разрешение persistent paths.** По текущему metamodel определить
   сегменты, association kinds, terminal type, id и nullability. Проверить реальные
   JPA fixtures до использования resolver в сборке каталога.
4. **Реализовать содержательную проверку декларации.** Проверить root descriptor,
   поля, id, filters/sort и профиль. Подготовить immutable требования загрузки и доступа.
5. **Собрать каталог атомарно.** Индексы, коллизии, детерминированные ошибки,
   отсутствие автоматической публикации и раздельные v1/v2. У каталога нет метода чтения.
6. **Подключить auto-configuration владельца.** Зарегистрировать imports-файл,
   задать явный порядок и проверить матрицу partial/full contexts из §4. Без деклараций
   модуль остаётся пустым; при наличии деклараций не теряет обязательную проверку тихо.
7. **Проверить реальные пилоты приложения.** `PrdSpec` и `Nomenclature` из
   `RestResourceDefinitionsConfiguration` проходят каталог; to-one фильтр остаётся
   отдельным отрицательным fixture. Приложение не копирует JPA/Spring wiring платформы.
8. **Выполнить сборочные и surface gates.** Обновить POM/manifest/fingerprint,
   согласовать внутренние роли и baseline, прогнать модуль и целевые проверки приложения.
   Записать границу проверенного результата и внешние отказы общей сборки.

## 10. Gate завершения F-REST-READ-2

- Первый этап подтверждён после исправлений; прикладные сигнатуры builder сохранены.
- Каталог состоит ровно из явных деклараций. Коллизии запрещены, v1/v2 сосуществуют,
  неуправляемые/owned/internal типы и неподдержанные пути отклоняются при старте.
- Все поля/filter/sort/id проверены по metamodel и capabilities. Положительные тесты
  охватывают оба прикладных типа; отрицательные — матрицу §8 и реальные JPA mapping.
- Отдельные fixtures подтверждают отказ для `@EmbeddedId` и `@IdClass`, поддерживаемый
  простой id другого типа, а также неоднозначность двух factories даже с primary.
- Fixed fetch requirements раздельны для list/detail и производятся из maximum.
  Все attribute/reference требования сохранены; готовность C5 не предполагается.
- Внутренний query API из §6 согласован и проверен; потребитель получает требования
  по операции и назначению пути, без предположений о структуре `ResolvedRestResource`.
- Auto-configuration принадлежит REST-модулю. Нет деклараций — пустой каталог;
  есть декларации без backend — ошибка старта. Частичный успешный каталог невозможен.
- Каталог после построения immutable; регистрация не выполняет запросов бизнес-данных
  и не создаёт HTTP routes. Нет repository/`EntityManager` обхода canonical read path.
- Разрешённый Maven-граф проверен по фактическому dependency tree. `rest.api` сохраняет
  независимость от Spring/JPA; модуль не содержит Web/Vaadin-зависимостей и обратных рёбер
  из core. Starter closure не подтягивает optional `platform-rest` транзитивно.
- Роли новых технически public типов определены; существующий APP_API baseline не
  изменён без причины. Проверки `PlatformApiBaselineTest`/`PlatformPublicSurfaceTest`
  и owner-side guards модуля проходят либо имеют конкретно отделённый внешний отказ.
- POM-рёбра, bootstrap manifest и fingerprints согласованы; модульный `verify` пройден
  на JDK 21. Стандартный bootstrap `-ValidateOnly` выполнен; bypass source drift не
  считается успешным стандартным gate, внешняя причина записывается отдельно.

Минимальный набор команд после реализации: модульный
`mvn -f platform-rest/pom.xml verify`, целевые тесты каталога/регистрации в приложении,
`PlatformApiBaselineTest`, `PlatformPublicSurfaceTest` и
`scripts/bootstrap-local-dependencies.ps1 -ValidateOnly`. Установка локального JAR
и BOM перед тестами приложения проводится принятым bootstrap; root `mvn verify`
не заменяет сборку нового состояния platform-модулей.

В итоговой записи указать JDK, команды, число тестов и отказы. Проект плана и список
критериев сами по себе не являются подтверждением их выполнения. Второй этап не
закрывает C5, gate C, read SPI, SQL/N+1-приёмку или публикацию REST.

### Фактическая приёмка реализации (2026-10-02)

- JDK: AxiomJDK 21.0.11; фактический mapping API проверен на Hibernate ORM 7.2.12.Final.
  `mvn -f platform-rest/pom.xml clean verify` — BUILD SUCCESS, 33 теста, 0 отказов.
  Catalog fixtures проверяют nullable/formula primitive, `AttributeConverter`, JSON JDBC,
  разрешённые `Long`/`Integer`/`String` ID и отказ для `BigDecimal` ID корня и FK-фильтра,
  а также owner-side composition guards.
- Приложение: `mvn -Dtest=RestResourceCatalogPilotIT test` — BUILD SUCCESS,
  2 теста, 0 отказов. Полный Spring Boot контекст разрешил обе декларации приложения
  против persistence unit; у `PrdSpec` проверены `journal.id` и раздельный fetch-набор.
- `mvn -Dtest=PlatformApiBaselineTest test` — BUILD SUCCESS, 3 теста, 0 отказов.
- `mvn -Dtest=PlatformPublicSurfaceTest test` — 8 из 10 тестов прошли; два отказа не
  относятся к REST: устарел замороженный бюджет ссылок на `org.ipro.reportstudio.data.EntityRef`
  (`src/main/java/org/ip/views/admin/EntityExplorerView.java`) и расходятся измерения
  (`platform-artifact-files`: ожидалось 423, найдено 415). Проверка общего реестра ролей
  типов приложения прошла, включая REST-роли.
- `scripts/bootstrap-local-dependencies.ps1 -ValidateOnly -AllowSourceDrift -Offline` —
  BUILD SUCCESS: 44 POM-рёбра и fingerprints внутренних REST/starter модулей подтверждены.
  Стандартный `-ValidateOnly -Offline` остаётся красным по FilterGrid:
  ожидалось 160 файлов (`bfa65774…`), обнаружено 177 (`48e03e4e…`). Это изменение
  соседнего исходного дерева, не включённое в REST manifest.
- Полный локальный bootstrap был остановлен на сборке соседнего `crudui`: Maven не смог
  удалить занятый `C:\JavaProject\TestVaadin25\crudui\platform-identity-api\target\platform-identity-api-1.0-SNAPSHOT.jar`.
  В ответ внутренние изменённые модули установлены отдельно в workspace `.local-maven-repository`;
  исходники соседнего checkout не менялись; заблокированный JAR не был удалён или заменён.
- Для запуска root пилота устранены два ранее блокирующих compile-расхождения в общей
  рабочей копии: конфликт имени локальной переменной `section` в `ExplorerTreeModel` и
  устаревшее число аргументов fixture-конструктора `EntitySummary` в
  `EntityExplorerViewAddressTest`.

### Дополнение по повторному ревью (2026-10-02)

На JDK 21 / Hibernate 7.2.12 повторно прошли 30 модульных тестов, два app-пилота,
API baseline и owner-side проверки обоих starters. Общие surface/bootstrap отказы
воспроизведены. Дополнительные real-mapping примеры первоначально обнаружили три пробела:

1. Primitive BASIC обходит проверку effective JDBC selectable: nullable column
   принимается как `NOT_NULL`, а примитивный `@Formula` не отклоняется.
2. `String` с пользовательским `AttributeConverter` и `String` с JSON mapping
   принимаются без отдельной проверки запрещённых converted/custom/JSON mappings.
3. `validateId` использует общую scalar-таблицу и принимает `BigDecimal id`,
   выходящий за согласованные `Long`/`Integer`/`String` первого профиля.

Затем эти пробелы были исправлены и покрыты fixtures в `RestResourceCatalogTest`:
`validatesPrimitiveNullabilityAndFormulaFromEffectiveMapping`,
`rejectsConvertedAndJsonFieldsUsingEffectiveJdbcMapping` и
`restrictsRootAndAssociationIdsToTheReviewedSimpleIdTypes`. Идентификаторы проверяются
через `getIdentifierMapping()`; составные id остаются вне профиля. Последующая чистая
проверка `mvn -f platform-rest/pom.xml clean verify` прошла: 33 теста, 0 отказов.
`RestResourceCatalogPilotIT` прошёл 2/2, `PlatformApiBaselineTest` — 3/3. Полный журнал
команд и версий находится выше в §10.

Mapping-подгейт закрыт. Первый профиль id принимает wrapper-типы `Long`, `Integer`,
`String`; primitive id намеренно не включён. Primitive scalar-поля при этом проходят
проверку effective JDBC mapping и nullability. Согласованное правило stage3 должно
сохранять это различие.

F-REST-READ-2 в целом формально остаётся открытым до зелёного общего surface gate и
стандартной проверки bootstrap после согласования изменений FilterGrid и соседнего UI.

## 11. Передача следующему этапу

Подробный план следующего среза —
[`rest-read-api-stage3-security-read-plan.md`](rest-read-api-stage3-security-read-plan.md).

Следующий этап получает проверенные схемы двух ресурсов, внутренние lookup/перечисление,
fixed fetch requirements и карту требований доступа. Его первые решения — C5 wiring
и правило защиты ссылок, узкий canonical read SPI и проверка итогового графа/SQL.
После этих решений выполняется HTTP-адаптер по родительскому плану. Контракт первого
потребителя сверяется перед публикацией; существование каталога URL не открывает.

Правила major и сосуществования версий уже определены в
[`rest-read-api-plan.md`](rest-read-api-plan.md), §4. Каталог реализует идентичность
и независимость v1/v2 по этим правилам. Перед HTTP-публикацией владелец прикладного
контракта согласует с потребителями срок поддержки и снятие прежней версии;
автоматический sunset из каталога не выводится. Новый major получает отдельный
API-грант согласно родительскому плану.
