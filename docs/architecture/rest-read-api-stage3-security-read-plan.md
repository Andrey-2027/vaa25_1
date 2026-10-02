# F-REST-READ-3: серверные права и canonical read contract — подробный план

- Статус: проект для согласования; реализация третьего этапа не начата
- Дата: 2026-10-02
- Архитектурное решение: независимый REST fetch profile зафиксирован в
  [ADR-0007, §4.1](decisions/ADR-0007-canonical-data-access-path.md#rest-fixed-fetch-profile);
  это решение о контракте, а не подтверждение реализации третьего этапа
- Родительский план: [`rest-read-api-plan.md`](rest-read-api-plan.md)
- Предыдущий срез: [`rest-read-api-stage2-catalog-plan.md`](rest-read-api-stage2-catalog-plan.md)
- Результат: проверяемое серверное чтение опубликованного ресурса через общий C5 и
  canonical path, с фиксированными требованиями загрузки и подготовкой скалярного результата
- Зависимости: исправления ревью второго этапа, контракт C5.0, серверный read-контур C5.1,
  API-review межмодульной read-границы

## 1. Назначение и граница результата

Каталог второго этапа подтверждает соответствие декларации persistence mapping.
Третий этап должен ответить на другой вопрос: вправе ли конкретный субъект выполнить
операцию и каким платформенным путём получить её данные без обхода RLS и C5.

Результат проверяется вызовами серверного сервиса без HTTP. Для `PrdSpec` и
`Nomenclature` используются те же прикладные декларации и каталог, которые уже есть
в приложении. Прикладной программист не пишет собственные проверки грантов,
контроллеры, Criteria-запросы и загрузчики ссылок для этих ресурсов.

Входят в этап:

- общий контракт C5 для API-операции, entity read и attribute read;
- регистрация двух API-операций на каждый `(resource, major)` без выдачи грантов;
- минимальный настоящий контур грантов и серверного применения этих read-прав;
- согласование субъекта C5 с `RlsCurrentUser`;
- узкий core read contract, передающий fixed REST fetch requirements внутрь
  существующего canonical executor;
- внутренний REST-сервис: выбор полей, проверки прав, безопасный `EQUALS`,
  ограниченный paging, чтение и скалярная проекция в одной транзакции;
- правило для to-one вывода и FK-фильтра через защищённую цель;
- измерение итогового графа, content/count SQL и поведения без OSIV;
- startup wiring, модульные границы и приёмочные пилоты.

HTTP-маршруты, security filter chain нового API, строковый разбор query/path parameters,
JSON envelope, Problem Details, OpenAPI и согласование wire-контракта с потребителем
относятся к четвёртому этапу. Синхронизация, запись и owned-секции остаются за границей.
Внутренняя скалярная проекция нужна для проверки транзакции; её Java-модель не считается
утверждённым HTTP envelope.

Этот этап реализует read-потребность REST в общем механизме C5. Полный C5 включает
также write/action enforcement, UI effective decision, администрирование и приёмку
всех каналов. Результат 3B является серверной зависимостью для возобновления E1.2b
(resource permissions), но не закрывает E1.2b: его action/UI-проверки имеют отдельную
приёмку. F-REST-READ-3 также не закрывает автоматически полный C5 или gate C. Открытие
маршрутов потребителям сохраняет все ограничения родительского плана.

## 2. Входные условия и долг второго этапа

Проектирование третьего этапа можно выполнять независимо от общих surface/bootstrap
gates. Три mapping-дефекта, найденные при ревью второго этапа, исправлены в текущем
коде и подтверждены regression fixtures: primitive BASIC проверяется по effective
JDBC mapping, converter/JSON отклоняются, а root и association id проверяются отдельным
профилем. Это закрывает именно mapping-подгейт, но не весь F-REST-READ-2.

| Замечание второго этапа | Текущее правило и evidence |
| --- | --- |
| Primitive BASIC обходил effective mapping | Проверка selectable и nullability применяется также к `int`/`long`/`boolean`; nullable column не принимается как `NOT_NULL`, primitive `@Formula` отклоняется. Fixtures находятся в `RestResourceCatalogTest`. |
| Converted/JSON mapping принимался по Java type | Effective Hibernate mapping отклоняет `AttributeConverter` и JSON JDBC mapping; обычные scalar-типы дополнительно сверяются с allow-list JDBC codes. Текущие fixtures покрывают converter и JSON. |
| Общая scalar-таблица применялась к id | Первый профиль принимает wrapper-типы `Long`, `Integer`, `String`; `BigDecimal` root id и target id FK-фильтра отклоняются. Primitive id в профиль не входит. То же решение применяется к root и target id. |
| Проверка Hibernate id полагалась на AttributeMapping | Идентификатор проверяется через `getIdentifierMapping()`; составные `@EmbeddedId`/`@IdClass` не входят в профиль. |

Текущая запись module `clean verify` и прикладных пилотов приведена в §10 плана
второго этапа. Перед использованием их как evidence следует установить текущие owner
artifacts принятым bootstrap-порядком. Общий surface gate по-прежнему имеет два отказа
(бюджет ссылок `EntityExplorerView` и измерение 423/415), стандартный bootstrap —
source drift FilterGrid 160/177. `-AllowSourceDrift` даёт только диагностический
результат. Эти общие gates остаются отдельной зависимостью приёмки и не считаются
закрытыми исправлением mapping-кода.

Приёмка третьего этапа не может опираться на неподдерживаемый mapping или зелёный
тест, который случайно использует прежний установленный JAR. Перед app-проверками
устанавливаются текущие owner artifacts принятым bootstrap-порядком.

## 3. Что уже есть в коде и чего недостаёт

| Компонент | Фактическая возможность | Работа третьего этапа |
| --- | --- | --- |
| `RestResourceCatalog` / `ResolvedRestResource` | Lookup по resource/major, maximum/default, association-only fetch requirements и полные resolved source paths | Полный REST fetch profile выводить из `projection(operation)` и resolved source paths по §4.2.1; `fetchRequirements()` не является готовым графом. Не строить второй каталог и не выводить права из metadata-проверки. |
| `EntityDataAccess.list/detail` | Canonical чтение с `Specification` и `Pageable`, без дополнительных fetch paths | Сохранить существующий прикладной контракт; добавить узкую межмодульную read-границу после API-review. |
| `BaseService` / `CanonicalEntityService` | Публичные compatibility overloads уже принимают дополнительные paths; `findAllByScenario` также принимает сценарий вызывающего кода. У этих методов есть существующие application callers. | Не использовать их как REST input contract и не удалять в этом срезе. Сохранить узкую REST read-границу без caller-selected scenario; включить текущую широкую поверхность и её callers в API-review общего canonical enforcement. |
| `PageRead` / `DetailRead` | Внутренние immutable запросы с `additionalPaths`, которые сейчас дополняют scenario plan | Не повышать их роль до REST SPI. Core bridge должен сохранить отдельную семантику полного `fixedFetchPaths`; нельзя напрямую преобразовать их в прежнюю пару `LIST/DETAIL scenario + extras`. |
| `EntityDataAccessResolver` / `EntityDataPolicy` | Выбор standard/custom пути по явному типу | Зафиксировать поддержку generic REST при custom policy; новый bridge не обходит зарегистрированное предметное чтение молча. |
| `CanonicalReadExecutor` | Capability, RLS, fetch graph, content/count, telemetry; `@Transactional(readOnly = true)` | Добавить C5 entity read enforcement в общей read-границе; сохранить RLS и существующие сценарии. |
| `ScenarioFetchGraphResolver` | Текущая UI-схема `scenario plan ∪ extras -> validate -> deepen once -> graph` | В той же canonical boundary добавить самостоятельный fixed-profile режим: полные REST paths → validate/normalize → explicit graph. UI union и display/InstanceName deepen в этом режиме не вызываются. |
| `RlsReadGate` / `RlsPolicyEnforcer` | Class/row RLS, включая CHECK_ONLY | Использовать по существующему назначению; API permission и C5 entity permission — отдельные решения. |
| `RlsCurrentUser` / `SecurityRlsUser` | Имя субъекта из Spring Security; отсутствие auth может дать legacy `system` | На входе REST-чтения обязательно требовать подтверждённый субъект; проверять согласованность identity. |
| `AccessService` / `AccessGrant` | Гранты по RLS dimensions, роли, wildcard и собственная семантика defaults | `platform-rls` — C5 owner по умолчанию; C5 review фиксирует отдельную семантику REST major, чтобы существующий RLS wildcard не разрешил его случайно. |

Источники: `platform-core/src/main/java/org/ipro/data/`,
`platform-rest/src/main/java/org/ipro/rest/catalog/`,
`platform-rls/src/main/java/org/ipro/rls/`,
`src/main/java/org/ip/security/SecurityRlsUser.java`.

## 4. API-review и владельцы до реализации

### 4.1. Решения, которые требуется записать

До coding gate фиксируются:

1. Владелец общего C5 контракта, decision service и хранилища грантов — `platform-rls`
   по умолчанию: там уже находятся `AccessService`, `AccessGrant` и RLS dimension registry.
   REST владеет регистрацией своих операций и применением требований каталога. Новый
   permission-артефакт или отдельное хранилище не вводятся без результата C5 API-review;
   если общему контракту нужен другой owner, review записывает причину и граф зависимостей.
2. Ключи API/entity/attribute permissions, субъекты, объединение пользовательских и
   ролевых грантов, defaults и приоритет отказов. Если C5 вводит explicit deny,
   его приоритет также фиксируется; семантика не выводится из RLS-флагов `canRead`.
3. Семантика attribute read при fixed fetch superset и правила защищённых ссылок из §7.
4. Сигнатура core read contract, транзакция и роли API/SPI/internal всех видимых типов.
   Семантика независимого REST fetch profile уже определена в §4.2.1–§4.2.3 и
   ADR-0007 §4.1; API-review фиксирует её Java-представление и architecture guards.
5. Активация executable read wiring и поведение при отсутствующих/неоднозначных
   collaborators. Metadata-only каталог остаётся самостоятельным результатом.
6. Методика измерений и предварительные пределы графа, offset window и SQL-бюджетов.
   Для графа фиксируется baseline UI LIST того же root и объяснённая разница путей REST;
   для SQL — одинаковый root/filter/page-size набор и отдельный count. Итоговые численные
   limits фиксируются по результатам пилотов до приёмки исполнения, с указанным baseline,
   данными, Hibernate/DB и обоснованием каждого превышения.

Для C5 нужен общий источник effective decision, пригодный для серверного вызова и
будущего UI. REST-specific map грантов, проверка строковых roles прямо в read-сервисе
и production mock, разрешающий всё, не являются реализацией этого контракта.

### 4.2. Рекомендуемая core read-граница

Предлагается отдельный `MODULE_API` контракт с рабочим именем `EntityReadAccess`:

```java
interface EntityReadAccess {
    <T> Page<T> list(Class<T> type, Specification<T> filter,
                     Pageable pageable, Collection<String> fixedFetchPaths);

    <T> Optional<T> detail(Class<T> type, Object id,
                            Collection<String> fixedFetchPaths);
}
```

Это эскиз для API-review. Контракт обслуживает только `LIST` и `DETAIL`; caller не
выбирает произвольный fetch scenario. `fixedFetchPaths` — полный фиксированный набор
полных persistent source paths REST-операции, включая root scalars и terminals ссылок,
выведенный из maximum-полей декларации. Это не association-only набор каталога и не
дополнительные пути к UI `LIST`/`DETAIL` plan. Core проверяет и нормализует этот набор,
затем строит explicit graph в единственной canonical graph boundary по правилам ниже.
UI plan union и `FetchGraphs.deepen` по display metadata/InstanceName не применяются.
`LIST`/`DETAIL` сохраняются как operation/capability intent; источник графа выбирается
доверенным bridge, а не клиентом. Новый `FetchScenario.API` этим решением не вводится;
конкретная внутренняя форма read request фиксируется в API-review.

REST вызывает интерфейс, не конструирует executor и не обращается к внутренним
request-типам. `EntityGraph`, `EntityManager`, Hibernate session, RLS predicates,
HTTP types и `RestResourceKey` не появляются в этой core-сигнатуре. Возвращаемая
entity остаётся внутри серверного pipeline и преобразуется до выхода из него.

Первый bridge обслуживает standard canonical roots. Если на тип зарегистрирована
`EntityDataPolicy`, executable profile отклоняет его до согласования typed adapter,
который сохраняет общий enforcement и поддерживает fetch requirements. В текущих
production-пилотах таких registrations не найдено. Поддержка custom read policy
не выводится только из подходящей экспозиции типа.

Текущие `EntityDataAccess` и прикладной builder не требуют расширения ради REST.
Core реализует `fixedFetchPaths` через отдельный internal read request или эквивалентный
внутренний overload executor, который сохраняет полный REST path set без UI scenario
union. До кодирования API-review фиксирует сигнатуру, owner и внутреннее представление
режима по принятому fetch-контракту; `PageRead`/`DetailRead` не повышаются в роли попутно.

#### 4.2.1. Источники фиксированного профиля

Для каждой пары resource/major и операции профиль выводится при startup из
`projection(operation)`: берутся полные `ResolvedRestField.source().source()` всех
maximum-полей, включая обязательный root id. Повторные paths удаляются в стабильном
порядке декларации. LIST и DETAIL получают самостоятельные immutable профили.

- `fetchRequirements(operation)` этапа 2 сохраняет смысл association-only summary.
  Например, оно возвращает `nomenclature`, а полный source path поля —
  `nomenclature.code`. Этот summary нельзя передать bridge как полный профиль.
- Root scalars входят в профиль вместе с reference terminals. Alias используется
  только для внешней схемы; core получает persistent path.
- Клиентские `fields`, defaults, активные filters и sort не перестраивают профиль.
  FK-фильтр сам по себе не добавляет association fetch; его авторизация обязательна.
- UI grid/form metadata, `@Lookup.fetch`, `InstanceName`, `selectColumns` и
  `displaySortFields` не являются источниками REST paths. UI baseline используется
  только для сравнения стоимости.

REST выводит описание из существующего каталога, а core валидирует и материализует
его по persistence metamodel. Дополнительный каталог и новый builder DSL не нужны.
Профиль фиксируется без business-data SQL; сам `EntityGraph` создаётся внутри core
для выполняемого чтения и соответствующего persistence context.

#### 4.2.2. Построение explicit graph

В единственной canonical graph boundary действует последовательность:

```text
maximum resolved source paths + mandatory root id
  -> validate metamodel/profile -> deduplicate and merge path prefixes
  -> explicit root attribute nodes and to-one subgraphs -> fetchgraph
```

Нормализация не выводит новых бизнес-путей. Она объединяет общие prefixes и
материализует только объявленные terminals и структурно необходимые association
узлы. Все существующие ограничения scalar/id, глубины, коллекций, owned sections и
защищённых целей сохраняются. Id terminal проверяется как identifier, а не как
обычный Hibernate `AttributeMapping`.

| Maximum source paths | Явное устройство графа |
| --- | --- |
| `id`, `codeSpec`, `draft` | Root nodes этих полей; association nodes отсутствуют. |
| `id`, `nomenclature.code`, `nomenclature.name` | Один subgraph `nomenclature` с terminals `code`, `name`; target id загружается по правилам JPA. |
| `id`, `journal.id` | Явный subgraph `journal` с terminal `id`; association node без subgraph не используется. |
| Только `id` | Явный root graph с id, передаваемый как `fetchgraph`. |

Каждая выбранная to-one association получает explicit subgraph, в том числе когда
нужен только её id. Нельзя заменять `nomenclature.code` или `journal.id` голым узлом
`nomenclature`/`journal`: он включает default fetch graph цели. Общие prefixes
объединяются в один subgraph без потери terminals и повторного display deepen.

Пустой association summary для скалярного ресурса допустим: полный профиль содержит
root scalars и обязательный id. `null`, пустой полный профиль, отсутствие root id,
неизвестный path или association без terminal — ошибка executable configuration,
а не сигнал использовать UI/default plan. Bridge не подменяет это `null`-графом.
Существующий `FetchGraphs.fromPaths` можно переиспользовать или уточнить внутри
canonical boundary только при соблюдении explicit subgraph и merge semantics.

Для REST content и detail core всегда передаёт явный
`jakarta.persistence.fetchgraph`; `loadgraph` и отсутствие hint не являются fallback.
Count не получает граф. Существующие UI/compatibility reads продолжают использовать
`scenario plan ∪ extras → validate → display deepen once → graph`.

#### 4.2.3. Граница гарантий и приёмка

Полный профиль определяет требуемое состояние для projection, но не обещает точный
список SELECT columns. Primary key/version и дополнительное scalar state могут
загружаться провайдером; это согласуется с attribute use-vs-load policy §7.1.
По [Jakarta Persistence 3.2, §3.8.1–§3.8.1.1](https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html)
провайдер вправе загружать дополнительное состояние, а to-one node без subgraph
использует default graph цели. Поэтому EntityGraph не заменяет C5/reference/RLS
проверки и не служит механизмом SQL column redaction.

Startup проверяет явные paths, subgraphs и ограничения профиля без чтения данных.
Приёмка дополнительно проверяет actual loaded associations/collections и SQL на
используемом Hibernate/DB: fixtures включают незаявленные EAGER-связи root и цели,
EAGER collection у цели, scalar-only root и reference-id-only output. Каждая проверка
использует новый persistence context и холодный cache, чтобы прежняя загрузка не скрыла эффект.
Неожиданная незаявленная загрузка association/collection не принимается как часть
REST-профиля: требуется исправить mapping/core fetch strategy и повторить проверку.
Если эффект выявлен только в SQL/loaded-state tests, gate остаётся открытым; одна
успешная startup metadata-проверка не подтверждает runtime-поведение.

Изменение UI metadata не меняет normalized REST paths и graph tree. Изменение
persistence mapping, Hibernate версии или fetch strategy требует повторной
runtime-приёмки затронутых профилей. Изменение `fields` меняет только projection;
неразрешённые значения из fixed superset не используются и не экспортируются.

### 4.3. Граф зависимостей и роли

- `platform-rest -> platform-core` сохраняется. Общий C5 decision contract используется
  core и REST из согласованного backend owner; обратного ребра core → REST нет.
- Spring Security context adapter, если выделяется в REST, использует security-core;
  HTTP/Web/Vaadin зависимости для третьего этапа не нужны.
- `org.ipro.rest.api` сохраняет JDK-границу. Orchestration, нормализованные запросы,
  projection и startup wiring остаются внутренними типами REST.
- Новый read interface и его реализации получают явные роли. Дополнение общего C5
  контракта проходит собственный surface review, включая signature closure.
- Все прямые POM-рёбра, owner artifacts, imports, BOM и fingerprints отражаются
  в manifest. Backend/Vaadin starters сохраняют отсутствие optional REST в runtime closure.

## 5. Минимальный C5 read-контур

### 5.1. Каталог требуемых прав

Для каждого ресурса регистрируются две именованные операции: LIST и DETAIL. Политика
версий уже зафиксирована родительским планом, §4: любое изменение видимой схемы, включая
добавление поля или фильтра, требует нового major и отдельного API-grant. Stage3
использует это правило и не вводит отдельную политику для изменений внутри major.
Предлагаемая форма ключа, подлежащая C5.0 review:

```text
REST:specifications:v1:LIST
REST:specifications:v1:DETAIL
REST:nomenclature:v1:LIST
REST:nomenclature:v1:DETAIL
```

Форма ключа фиксируется тестом. Ресурс, major и операция входят в идентичность;
v1-grant не даёт права на v2, LIST не подразумевает DETAIL, один REST alias не
даёт права на другой alias того же entity type. Новый major получает отдельные
гранты согласно родительской политике версий. Не вводится неявный REST wildcard,
который расширяет ранее выданные права на будущие контракты.

Для entity/attribute используются общие C5 ключи типа и persistent attribute;
REST alias не становится именем persistent permission. Ключ типа принадлежит
C5 registry и не выводится из URL формы или REST-ресурса. Для inherited attribute
фиксируется правило нормализации, одинаковое для UI и REST.

При активации read-контура startup регистрирует определения прав и проверяет коллизии.
Декларация ресурса
и присутствие permission definition никому не выдают grant.

### 5.2. Субъект и гранты

Источник субъекта — доверенный серверный authentication context. `subject`, username,
role, набор грантов и bypass-флаги не принимаются из нормализованного REST-запроса.
Пользователь, роль и именованная сервисная учётка используют общую C5 инфраструктуру;
сервисная учётка не получает системный bypass по факту своего назначения.

Проверяется один субъект для C5, RLS, content/count и проекции. Legacy `system`,
anonymous, отсутствующая auth, несовпадение identity и открытый privileged bypass
не допускаются к обычному REST-чтению. Субъект не меняется внутри транзакции;
гранты/decisions не кешируются без учёта identity и правил актуализации C5.

Минимальная интеграционная приёмка использует настоящий согласованный C5 provider
и хранилище: прямой grant, grant роли, отсутствие grant и его отзыв. Назначение
грантов выполняется серверным механизмом владельца C5; административные HTTP/UI
экраны не требуется создавать в REST-модуле. Тестовый stub допустим для проверки
одного порядка вызовов, но не заменяет эту интеграционную приёмку.

### 5.3. Точки обязательного применения

| Решение | Владелец enforcement |
| --- | --- |
| Аутентифицированный субъект и API LIST/DETAIL grant | Серверный REST read pipeline, до данных |
| Entity read C5 | Общая canonical read-граница; прямой вызов facade/executor не обходит её |
| Attribute read для output/filter/sort и reference requirements | Общий C5 decision, применённый REST pipeline по выбранным требованиям |
| Class/row RLS | Действующий canonical path, в том же persistence context |
| Защита значений связанной цели | Правило §7, применяемое до использования reference path |

При включённом C5 отсутствующий provider, неизвестное permission definition,
неоднозначный backend или отсутствие grant не означают allow. Неисправность
конфигурации имеет отдельную диагностику, не маскируется пустой страницей/NOT_FOUND.

Переход общих canonical reads на C5 требует явной политики миграции существующих
UI/service callers и их грантов. REST не добавляет API grant к обычному UI-чтению.
Миграция, seeds и defaults согласуются в C5; green REST-пилот не заменяет проверку
того, что общий enforcement сохранён при программном вызове и не сломал UI без
объяснимого решения о правах.

## 6. Серверный pipeline и исходы

### 6.1. Вход и последовательность

Внутренний запрос содержит `(resource, major)`, операцию, aliases полей,
типизированные значения объявленных фильтров, разрешённую сортировку и paging;
для detail — id поддерживаемого Java-типа. HTTP strings разбираются на следующем этапе.
Вход не содержит `Specification`, entity class, JPA path или дополнительных fetch paths.

Порядок исполнения:

1. Получить подтверждённый субъект, проверить согласованность C5/RLS context.
2. Найти опубликованную пару resource/major; неизвестная пара не получает fallback.
3. Проверить API grant и C5 entity read; capability остаётся обязательной и внутри core.
4. Валидировать операцию и нормализованный запрос по каталогу. Выбрать defaults при
   отсутствии `fields`, иначе подмножество maximum; обязательный `id` добавить всегда.
   Порядок вывода взять из декларации, а не порядка пользовательского списка.
5. Выбрать `accessRequirements(operation)` для фактически используемых output,
   активных filters и sort; проверить C5 на каждом persistent segment и target type.
   Дополняемый для детерминизма id также участвует в проверке.
6. Применить согласованное reference rule и ограничения итогового графа; получить
   полный fixed profile из resolved maximum source paths соответствующей операции,
   а не передать association-only `fetchRequirements()` как готовый граф.
7. Скомпилировать разрешённые EQUALS и sort; выполнить canonical read под RLS.
8. В той же read-транзакции получить разрешённые scalar values и создать immutable
   результат. За границу сервиса не выходят entity, proxy, lazy collection и callback.

Порядок между validation и отказами фиксируется тестами: anonymous → неизвестный
ресурс остаётся UNAUTHENTICATED; известный ресурс без API grant даёт FORBIDDEN,
даже если дополнительные параметры невалидны. Валидация использует только schema;
при отказе не выполняется запрос бизнес-данных. Чтение хранилища самих грантов
допустимо и считается отдельно от content/count запросов ресурса.

### 6.2. Типизированные исходы

| Серверный исход | Смысл | Будущая HTTP-проекция |
| --- | --- | --- |
| UNAUTHENTICATED | Нет допустимого субъекта до lookup ресурса | 401 |
| RESOURCE_NOT_FOUND | Auth есть, опубликованная пара отсутствует | 404 |
| FORBIDDEN | Нет API/entity/нужного attribute/reference grant | 403 |
| INVALID_REQUEST | Неизвестный alias, неверный тип/операция/paging/sort | 400 |
| DETAIL_NOT_FOUND | Запись отсутствует либо скрыта class/row RLS | Один 404 |
| SUCCESS | Скалярный detail либо ограниченная страница | 200 |
| SECURITY_UNAVAILABLE / READ_CONFIGURATION_ERROR | Отсутствующий provider, неподдержанное reference rule или конфигурация | Ошибка конфигурации; точное wire-представление задаёт этап 4 |

Это словарь семантических исходов, а не уже существующие Java enum или Problem Details
codes. Формальные типы и exception mapping входят в API-review. Отсутствующий и
скрытый detail имеют один внешний исход; server diagnostics не меняет это обещание.
Class RLS deny для list сохраняет пустую страницу, а не превращается в C5 FORBIDDEN.

### 6.3. Фильтры, сортировка, лимиты и проекция

- Compiler получает разрешённый путь из каталога. `EQUALS` принимает значение того
  Java-типа, который подтверждён mapping; null, coercion и неизвестный оператор
  отклоняются до SQL до отдельно согласованной поддержки.
- Root scalar и подтверждённый many-to-one id — весь первый профиль. Построенная
  `Specification` внутренна для платформы, повторно применима к content/count,
  не выполняет чтения, не добавляет fetch и не изменяет сценарий RLS.
- Sort использует только root keys каталога; `id` дополняет порядок, если его нет.
  При отсутствии явно заданного/default порядка применяется `id ASC`; добавленный id
  tie-break также использует ASC. Явный sort по id сохраняет запрошенное направление.
  Правило фиксируется API-review и одинаково в тестах и реализации.
- Нет unpaged-входа; `size` ограничен ресурсом и платформенным максимумом 200.
  Проверяются знак, переполнение `page * size + size`, выбранный offset window
  и безопасное преобразование offset в `int`, используемый нынешним canonical executor.
  Численный window фиксируется после пилотного измерения, до приёмки исполнения.
- Простой String/Integer/Long id передаётся типизированно. Полноценный HTTP id codec
  относится к следующему этапу и не выводится из `toString()` arbitrary object.
- Projection работает только с выбранными aliases и подтверждёнными scalar types.
  Optional to-one даёт null только при допускающей это схеме. Нет рекурсивной
  сериализации, автоматического InstanceName/owned-section вывода и business lambdas.

## 7. Attribute read и защищённые ссылки

### 7.1. Fixed fetch superset и выбранные права

Проверяются поля actual request, включая defaults и id, активные filters и sort.
Один запрещённый alias не удаляется молча: операция получает FORBIDDEN. Запрос
разрешённых полей не требует права на остальные maximum aliases.

Для этого среза принимается политика C5: attribute read разрешает использование
значения в ответе и запросе по нему, но не регулирует внутреннюю загрузку fixed superset.
Это не SQL column redaction и не даёт права использовать или экспортировать загруженное
значение. Запрещённый атрибут не выводится и не участвует в filter/sort. Эта семантика
вносится в C5.0 contract и проверяется сценарием, где поле присутствует в fixed fetch
superset, но субъекту запрещено его использовать.

REST fetch requirements выводятся из maximum-полей своей декларации отдельно для LIST
и DETAIL. Они не наследуют пути из UI grid/form metadata, `@Lookup.fetch` или
InstanceName автоматически. Нужный reference terminal/path включается только по явному
REST requirement и проходит те же проверки защищённой цели. Изменение UI metadata само
по себе не меняет граф, SQL стоимость или поверхность данных REST.

### 7.2. Правило первого профиля

Для `nomenclature.code` требуются C5 read на root, root reference attribute,
целевой entity type и terminal attribute. Отсутствие RLS у Nomenclature не является
разрешением C5. Отдельный REST API-grant на endpoint номенклатуры не требуется для
alias спецификации: здесь действует API-grant спецификаций и общие C5 права цели.

Для `journal.id` те же правила действуют и для output, и для активного FK-фильтра.
Local FK и Hibernate оптимизация SQL не являются доказательством авторизации цели.

Первый executable reference profile принимается двумя последовательными ступенями:

1. **3.4a — цель без row RLS:** `Nomenclature.code` допускается после C5/reference
   проверок. Это первый executable profile с protected-reference semantics.
2. **3.4b — цель с row RLS:** `PrdSpec -> Journal` допускается только при подтверждённой
   совместимости с защитой root:
   одинаковый субъект и allowed values, доказанное соответствие source FK и target id,
   все измерения/условия цели учтены, class gate не потерян. Это проверяемая metadata
   policy и тесты, а не сравнение одного имени dimension.
3. Иная защищённая цель получает отказ executable configuration до открытия read-сервиса
   для такого ресурса. Каталог остаётся metadata-каталогом; silent removal alias/filter
   и автоматический permissive fallback запрещены.

Пилот 3.4b `PrdSpec -> Journal`: root использует JOURNAL по
`journal.id`, target Journal — JOURNAL по собственному id. Дополнительно проверяются
effective policies, C5 права цели и реальный SQL с текущим Hibernate. Startup проверяет
форму policy, а каждый вызов — соответствующие class gates и единый контекст allowed values.
Изменение policy
Journal, появление дополнительного измерения или class deny обязаны нарушить прежнее
доказательство, а не оставить alias разрешённым по старому имени dimension.

Более общий reference authorization через secured EXISTS/join возможен отдельным
расширением с одинаковым predicate в content/count. В первом профиле не вводится
per-row чтение цели для проверки прав: оно даёт N+1 и может раскрывать её существование.
Fetch join/Hibernate filter сами по себе не принимаются как доказательство; отдельно
проверяются load-by-key, fetched association и сравнение FK.

## 8. Транзакция, итоговый граф и SQL-приёмка

REST read orchestration — Spring-managed read-only transaction. Core read contract
делегирует управляемому executor и присоединяется к той же транзакции. Projection
выполняется до её окончания; вызов через `new`, self-invocation и опора на OSIV
не считаются выполнением этого условия. Identity, RLS activation, content/count,
reference проверки и projection используют согласованный persistence context.

REST fixed paths берутся из maximum отдельно для LIST/DETAIL; клиент не меняет их.
Core read request сохраняет их как самостоятельный REST profile. Он не добавляет к ним
UI scenario plan и не вызывает display/InstanceName deepen. Единственная canonical
boundary выполняет path validation/normalization и строит explicit EntityGraph по
§4.2.1–§4.2.3. Ни REST, ни новый bridge не строят отдельный EntityGraph.

До data query для executable resource проверяются итоговые paths на реальном
metamodel: collection/owned-section expansion, глубина, размер и защищённые цели.
Paged LIST с collection fetch отклоняется; count не получает fetch graph.
Для данного root-detail контракта owned sections также не загружаются автоматически.
Если REST-declared fixed path приводит к неподдержанному графу, нужен named
configuration failure и явное исправление владельцем; путь не удаляется молча.
Изменение UI scenario metadata само по себе REST executable profile не меняет.

Пилотная измерительная запись должна содержать:

- association summary каталога, полные source paths, normalized graph tree и actual
  loaded associations/collections LIST/DETAIL для обоих ресурсов;
- UI LIST graph того же root как comparator и перечень обоснованных REST path deltas;
- SQL content и count с root scalar/FK filter и без него;
- результаты для двух субъектов и суммы, посчитанные только по видимым строкам;
- число business-data SQL на странице 1 и 50 записей при одинаковой проекции;
- отдельный cost грантов/ролей, одинаковый по порядку величины для этих страниц;
- стоимость detail, максимального графа и выбранного предельного offset;
- отсутствие дополнительного SQL после формирования scalar результата.

Отдельный count допустим. Число запросов для aliases/references не растёт линейно
со строками. Graph budget сравнивается с UI LIST comparator и объяснённой REST path
delta; SQL/content/count бюджеты получают отдельные численные пределы на одинаковом
root/filter/page-size наборе. Перед приёмкой записываются конкретные limits;
формулировка «не должно быть дорого» gate не закрывает. Если cost не проходит,
меняется fetch/read контракт или профиль ресурса, а пределы не повышаются без причины.

H2 нужен для быстрых fixtures. Для окончательного SQL/cost gate текущего приложения
выполняется тот же набор на PostgreSQL и используемой версии Hibernate; разрешённый
mapping и отсутствие SQL regression не выводятся только из H2. Отсутствие такой
проверки отмечается как открытый gate, а не как успешная production-приёмка.

## 9. Wiring и отказ при неполном контексте

Существующая catalog auto-configuration сохраняет своё назначение. Для executable
read предлагается отдельная REST-owned конфигурация и явная активация
`platform.rest.read.enabled=true`, по умолчанию false. Эта настройка включает
серверное чтение, не HTTP routes и не обход permissions. Имя/семантика фиксируются
на API-review; приложение не копирует platform wiring.

| Контекст | Ожидаемый результат |
| --- | --- |
| Optional REST отсутствует | Нет REST catalog/executor/permission definitions. |
| Только metadata/catalog, execution не включён | Поведение второго этапа; отсутствие C5 не превращает metadata-проверку в allow. |
| Execution включён, каталог пуст | Нет resource read handlers и регистраций API-операций; REST wiring не требует backend для пустого каталога. |
| Есть ресурсы, execution включён, отсутствует core/C5/subject/reference collaborator | Named startup failure; отсутствие read bean не маскирует объявленное намерение исполнять. |
| Есть ресурсы и полный read backend | Один защищённый executor; все executable profiles и permission definitions проверены. |
| Несколько самостоятельных C5 decision services без согласованной композиции, неоднозначная identity или backend | Named startup failure; `@Primary` не скрывает конфликт владельцев. Согласованные grant sources внутри одного C5 service допустимы. |
| Один ресурс не проходит reference/graph профиль | Нет частично успешно включённого набора ресурсов; named failure. |

Startup не читает бизнес-данные, не выдаёт grants и не включает HTTP endpoint.
Порядок после catalog/core/C5 wiring задаётся явно. Partial-context guards проверяются
отдельно от требований остальных компонентов полного приложения.

## 10. Матрица тестов

| Сценарий | Обязательное подтверждение |
| --- | --- |
| Anonymous + известный/неизвестный resource | UNAUTHENTICATED до catalog lookup и business-data SQL |
| Auth + неизвестный major/resource | RESOURCE_NOT_FOUND без entity/JPA-name fallback |
| API grant отсутствует | FORBIDDEN; core read и projection не вызываются |
| LIST grant есть, DETAIL/v2/другой alias нет | Нет переноса grant между операциями/версиями/aliases |
| API grant есть, entity C5 read нет | FORBIDDEN до business-data query, в том числе у типа без RLS |
| Direct core/facade read без entity C5 grant | Тот же общий запрет; REST gate не является единственной защитой entity |
| У опубликованного типа появилась custom EntityDataPolicy | Named executable configuration failure до поддержки соответствующего typed adapter |
| Запрошен запрещённый output/default/id | FORBIDDEN; нет silent field omission |
| Запрошены разрешённые поля, другое maximum поле запрещено | Запрос проходит; запрещённое поле может быть загружено fixed superset, но не выходит в проекцию и не используется в filter/sort |
| Запрещённый attribute используется только в filter/sort | FORBIDDEN до resource SQL, включая добавленный id tie-break |
| Alias и имя persistent attribute различаются | Решение относится к настоящему attribute, с учётом inheritance и всех сегментов |
| Нет C5 read цели/reference/terminal | Alias/FK-filter запрещён независимо от RLS root и локального FK |
| Два пользователя с разными JOURNAL grants | Различаются видимые PrdSpec и total; фильтр не расширяет множество |
| Скрытый/отсутствующий detail id | Один DETAIL_NOT_FOUND, без различимых публичных деталей |
| Class RLS deny | Пустой list и DETAIL_NOT_FOUND при наличии C5/API прав |
| Journal policy дополнена измерением/deny | Прежнее доказательство reference visibility больше не допускает execution |
| Nomenclature без RLS | API/entity/attribute grants всё равно обязательны |
| Default/direct/role/service grants и отзыв | Проверка через настоящий C5 provider и storage; изменение identity/грантов не использует чужой cache |
| Несовпадение C5/RLS identity, legacy system, bypass | Отказ до resource SQL |
| Fields/filter/sort/id тип/size/window invalid | INVALID_REQUEST до SQL; client path/Specification/extras недопустимы |
| REST LIST/DETAIL fixed requirements | Раздельные графы выведены из maximum-полей; изменение UI metadata не меняет REST paths; клиентский fields не меняет граф |
| Scalar-only root, включая maximum только из id | Полный профиль содержит root nodes/id; content/detail получают явный fetchgraph, association summary может быть пустым |
| Несколько terminals одной to-one, включая reference-id-only | Один explicit subgraph на общий prefix; terminals сохранены, default target graph не используется как fallback |
| Null/empty full profile, нет root id, голая association или неизвестный path | Named configuration failure до data query; нет UI/default plan fallback |
| UI grid/Lookup/InstanceName изменены | REST paths/tree не меняются; display deepen не вызывается; существующий UI union/deepen проходит regression |
| Незаявленная EAGER association/collection root или цели | Fresh-context/cold-cache loaded-state и SQL tests подтверждают отсутствие незаявленной загрузки; иначе runtime gate открыт |
| Fixed REST path добавляет collection/неподдержанную защищённую цель | Named executable configuration failure; неявные UI/InstanceName paths не добавляются в REST граф |
| Публичная REST/API сигнатура | Architecture rule запрещает `Page<T>`, entity, JPA/Hibernate types в `org.ipro.rest.api` и application-facing REST API; внутренний core bridge с entity допустим |
| OSIV отключён, результат читается после transaction | Нет entity/proxy/коллекций и SQL; scalar values подготовлены внутри transaction |
| Content/count с EQUALS/FK/RLS | Одинаковая семантика отбора; count без fetch |
| 1/50 строк, max graph, deep page | Зафиксированные SQL/graph/window бюджеты; нет линейного N+1 |
| Context matrix из §9 | Нет silent backoff, grants или endpoints; ошибки детерминированы |

Unit-тесты проверяют normalizer/compiler/порядок collaborators. Реальные JPA fixtures
проверяют graph, FK, nullable chains и transaction. App-пилоты используют оба
производственных ресурса; stubbed C5 integration не закрывает security gate.

## 11. Порядок работ и проверяемые результаты

| Шаг | Работа | Результат и проверка |
| --- | --- | --- |
| 3.0 | Подтвердить mapping-исправления этапа 2 по текущим artifacts и зафиксировать остаточные внешние gates | Сослаться на primitive/formula, converter/JSON, id и identifier fixtures; не дублировать уже закрытые исправления. Записать статус surface/bootstrap gates. |
| 3.1 | C5/core API-review и границы | Записаны `platform-rls` как default owner, signatures/roles, version grant, subject, attribute use-vs-load policy, Java-представление fixed profile по ADR-0007 §4.1, reference stages, wiring и стратегия миграции. |
| 3.2 | Общий минимальный C5 read-контур | Registry API definitions, реальный grant provider/storage и entity read на canonical boundary; direct/role/revoke/no-grant тесты. |
| 3.3 | Core read bridge | Fixed LIST/DETAIL с полными scalar/reference terminal paths, explicit subgraphs и обязательным fetchgraph; нет UI union/display deepen/default fallback; capability/RLS/telemetry/transaction сохранены; UI callers проходят regression. |
| 3.4a | Reference profile без row RLS | `Nomenclature.code`, C5 target/attribute checks, fixed REST graph и named отказ для неподдержанного пути. |
| 3.4b | Reference profile с row RLS | `PrdSpec -> Journal`, доказательство policy compatibility, единая identity/allowed values, class gates и реальный SQL. |
| 3.5 | Внутренний REST read pipeline | Typed request, required-field/access selection, EQUALS/sort/paging, read + scalar projection; отрицательные сценарии до SQL. |
| 3.6 | Реальные пилоты и cost | Два субъекта, list/detail, content/count parity, 1/50 строк, OSIV off, PostgreSQL; UI LIST baseline, объяснённая graph delta и численные бюджеты. |
| 3.7 | Auto-configuration и модульные guards | Матрица §9, зависимости/imports/roles/signatures/manifest, starter runtime closure и architecture rule публичной REST-поверхности. |
| 3.8 | Общая приёмка и передача | Доказательства gates, перечень остатка полного C5 и контракт передачи HTTP-этапу. |

Шаги 3.1 и подготовка fixtures можно выполнять до подтверждения 3.0. Реализация 3.3
и разработка C5 в 3.2 могут идти независимо после общего API-review. Pipeline 3A может
использовать contract-faithful test provider; 3B требует настоящий C5 provider/storage.
Reference 3.4a может приниматься отдельно до 3.4b. Полный F-REST-READ-3 требует обоих
гейтов и обоих reference stages. Полный C5 не предполагается завершённым и не
реализуется целиком в REST-модуле.

## 12. Gate завершения F-REST-READ-3

Приёмка разделена на два самостоятельных gate:

### Gate 3A — read pipeline correctness

- Стабилизированы C5/core contracts, REST-owned fixed fetch profile и request/result
  signatures; тестовый provider реализует тот же contract и проверяет порядок вызовов.
- Canonical bridge, нормализация, filter/sort/paging и scalar projection проходят
  correctness tests; API deny до business-data SQL проверяется на contract-faithful stub.
- Reference stage 3.4a и H2 pipeline fixtures проходят; UI metadata не меняет REST graph.
- Scalar-only/id-only graph, merge общих prefixes и reference-id-only subgraph
  проверены; content/detail всегда получают fetchgraph, нет UI deepen/default fallback.
- Этот gate подтверждает pipeline correctness, но не production security grants,
  C5 storage, revoke semantics или row-RLS reference proof.

### Gate 3B — C5 security acceptance

- Интеграционные проверки используют настоящий согласованный C5 provider/storage:
  direct/role grants, отсутствие grant и отзыв; C5/RLS identity едина.
- Direct canonical read без entity C5 grant запрещён; access не зависит от REST-only gate.
- Reference stage 3.4b `PrdSpec -> Journal` проходит policy proof, class/row RLS,
  two-subject visibility и PostgreSQL content/count checks.
- E1.2b получает проверенную серверную C5 read-зависимость; его собственные action/UI
  acceptance criteria остаются открыты до отдельной приёмки E1.2b.

Полный F-REST-READ-3 закрывается только после Gate 3A и Gate 3B. Следующие общие
критерии относятся к полному результату обоих gate:

- Исправления второго этапа подтверждены; неподдержанный effective mapping не
  используется для чтения. Закрыты стандартные входные surface/bootstrap gates.
- C5.0 решения записаны; минимум read enforcement проверен настоящим provider/storage.
  API grant отдельный для каждой операции/major, entity read действует и без REST.
- Attribute requirements выбираются по фактическому запросу; output/filter/sort/id
  защищены. Семантика fixed superset согласована и проверена.
- Core read signature/roles согласованы; внутренние PageRead/DetailRead не раскрыты
  попутно. Все чтения идут через управляемую canonical boundary, capability и RLS.
- Identity едина, anonymous/system/bypass не получают REST-чтения; context/кеш не
  переносят видимость между субъектами. Ошибки provider не считаются allow/пустым успехом.
- Защищённые ссылки имеют доказанное executable rule. FK/EntityGraph/наименование
  RLS dimension не используются как самостоятельное доказательство прав.
- Оба прикладных пилота проходят list/detail и отрицательные сценарии; скрытый и
  отсутствующий detail неразличимы по внешнему исходу; count считает только видимое.
- Transaction охватывает чтение и scalar projection, результат безопасен после её
  окончания и работает при выключенном OSIV. Нет entity serialization/owned expansion.
- Итоговый REST graph и SQL проверены на H2 и PostgreSQL; graph limits, SQL budgets и
  offset window имеют численные значения с UI LIST baseline и объяснёнными REST deltas.
  Fresh-context/cold-cache fixtures подтверждают actual loaded state, включая
  незаявленные EAGER dependencies; N+1, незаявленная association/collection загрузка
  и paged collection fetch отсутствуют.
- Модульные verify, новые core/C5 owner tests, app pilots и применимые общие
  signature/surface/composition/security regression gates проходят на JDK 21.
  Установлены текущие artifacts, POM-рёбра и стандартный bootstrap согласованы.
- HTTP endpoints не появились. Остаток полного C5, E1.2b, gate C и HTTP-публикации записан
  явно; третий этап не отмечает их выполненными по своим тестам.

Минимальные проверки: `mvn -f platform-rest/pom.xml verify`, verify изменённых core/C5
owner modules, owner-side проверки обоих starters, новые REST read/security app IT,
`CanonicalReadBoundaryIT`, `CanonicalReadTransactionBoundaryIT` и применимые
FetchPlan/RLS regression tests, `PlatformApiBaselineTest`, `PlatformPublicSurfaceTest`,
`scripts/bootstrap-local-dependencies.ps1 -ValidateOnly`. Имена новых tests и команды
записываются после API-review; root `test` с явным `-Dtest` либо failsafe должны
действительно запускать IT, а не только компилировать их. Смена общей canonical
security boundary дополнительно требует общего проверочного набора C5, включая UI callers.

Итоговая запись содержит JDK/Hibernate/DB версии, owner artifacts, команды, число
запущенных tests, матрицу исходов, SQL/graph измерения и открытые внешние gates.
Наличие этого плана не подтверждает выполнение перечисленных критериев.

## 13. Передача четвёртому этапу

HTTP-адаптер получает:

1. Версионированный metadata catalog и проверенный executable read profile.
2. Общие C5 definitions/decision и API-key contract, единый субъект C5/RLS.
3. Read-сервис с ограниченными typed inputs и скалярным результатом внутри transaction.
4. Семантические исходы, точные paging/graph/SQL лимиты и тесты прав/references.
5. Зафиксированные остатки полного C5 и стандартных gates.

Четвёртый этап добавляет HTTP authentication boundary, parsing/codecs, wire envelope,
Problem Details и OpenAPI. Матрица 401/403/404 проверяется уже на HTTP, включая
unknown resource; новая chain не перехватывает существующий `@Order(1)`
`/api/report-jpql/**`. Выбор authentication transport и настройка CSRF/session policy
согласуются до создания этой chain. Внешняя публикация остаётся отдельным gate:
полный C5, этап C, REST-пилот и согласование контракта с первым потребителем.
