# Карта каналов доступа к данным

- Этап: `C1 — инвентаризация каналов`
- Состояние: исходная карта текущего checkout
- Дата: 2026-09-11
- Следующий шаг: `C2 — fail-closed RLS`

Этот документ отвечает на три вопроса для любого пути к данным:

1. кто владеет решением о доступе;
2. где оно принудительно применяется до выполнения запроса или изменения;
3. допустим ли привилегированный обход и как он ограничен.

`RlsStatementGuard` в колонке «текущее состояние» означает только обнаружение.
Он не является enforcement-механизмом и не делает канал безопасным.

## Защищённая модель на момент C1

| Entity | Измерения | Вид |
|---|---|---|
| `Journal` | `JOURNAL` | FILTERABLE |
| `Branch` | `BRANCH` | FILTERABLE |
| `Workshop` | `BRANCH` | FILTERABLE |
| `PrdSpec` | `JOURNAL` | FILTERABLE |
| `ReceivingDocument` | `JOURNAL`, `BRANCH`, `ENTITY:ReceivingDocument` | FILTERABLE + CHECK_ONLY |

Строки `PrdSpecMtr`, `PrdSpecOper` и `ReceivingDocumentItem` не объявляют
собственную RLS policy. Сейчас их доступ неявно наследуется от aggregate root. До
формализации этого правила канал секций считается частично закрытым.

## Обозначения

- **Покрыт** — текущий путь явно вызывает существующую проверку до доступа к данным.
- **Частично** — защита есть, но зависит от вызывающего пути, Hibernate session,
  неформализованного наследования или отдельной UI-проверки.
- **Разрыв** — защищённые данные могут попасть в запрос без обязательного enforcement.
- **Зарезервирован** — канала ещё нет; до появления должен быть выбран безопасный
  контракт, а не разрешён прямой доступ по умолчанию.

## Security matrix

| ID | Канал и точки входа | Владелец решения | Текущее применение | Privileged bypass | Статус и решение C2 |
|---|---|---|---|---|---|
| `DAC-01` | Standard CRUD read: `AbstractBaseService.findById/findAll/page/spec/sum` | Platform data boundary | `RlsReadGate` для CHECK_ONLY и `RlsFilterActivator` для FILTERABLE | Не нужен | **Покрыт текущим API, но не fail-closed.** Перенести в обязательный policy interceptor/facade, не полагаться на дисциплину метода |
| `DAC-02` | Standard create/update/delete и aggregate root save | Platform data boundary + entity policy | Централизованные write/delete checks через `RlsDimensionValue`; aggregate coordinator сохраняет root через `BaseService` | Только типизированная system operation | **Частично.** Read и write policy дублируются разными контрактами; свести к одному descriptor |
| `DAC-03` | Предметные `search(...)` и custom repository queries | Application service; platform обязана оградить исполнение | `JournalService`, `BranchService`, `WorkshopService`, `PrdSpecService`, `ReceivingDocumentService` имеют прямые repository paths; часть веток проходит через `findAll`, часть — нет | Не допускается | **Разрыв, P0.** Любой protected query должен получить mandatory predicate автоматически; убрать зависимость от ранее активированного OSIV session |
| `DAC-04` | Lookup/selection: `LookupService.search/findAll/findById` | Platform lookup boundary | Явные `RlsReadGate` + `RlsFilterActivator` | Не нужен | **Покрыт текущим API.** Закрыть или удалить публичный `LookupService.getRepository(...)`, позволяющий вернуть raw repository |
| `DAC-05` | Обычные list/item/selection forms | Server-side data boundary; UI только отображает решение | Стандартные формы делегируют `BaseService`/`LookupService`; `RlsUiGate` управляет UX | Не допускается из UI | **Частично.** Добавить архитектурный запрет repository/`EntityManager` в UI; UI gate не считать security boundary |
| `DAC-06` | Owned table sections: `GenericOwnedSectionService`, aggregate save/delete | Aggregate policy owner | Read включает Hibernate filters; write выполняется напрямую через `EntityManager`, опираясь на проверенный root | Только системная операция над всем aggregate | **Частично.** Явно определить `INHERIT_ROOT` policy и запретить standalone row access без проверки root |
| `DAC-07` | Global search: `GlobalSearchService` + `JpaGlobalSearchProvider` | Platform search boundary | До provider включаются filters, для source выполняется CHECK_ONLY gate | Не нужен | **Покрыт текущим pipeline.** Закрепить channel tests и fail-closed регистрацию provider для protected entity |
| `DAC-08` | Projection/grid/grouping/custom UI query | Platform query boundary | Универсального enforcement нет; demo views `GroupingPanelView` и `NomenclatureLazyGroupsView` используют `EntityManager` напрямую | Не допускается | **Разрыв архитектуры.** Текущая `Nomenclature` не protected, но такой путь станет обходом при добавлении policy; перевести на secured projection API |
| `DAC-09` | Report JPQL/HQL: Report Studio, JRXML, UReport через общий runner | Reports data boundary | `ReportQueryGuard` анализирует roots/joins/subqueries и права; `ReportQueryExecutor` включает filters | Отдельный system-report contract, по умолчанию запрещён | **Покрыт текущим pipeline, не универсально fail-closed.** Запретить альтернативные executors и проверить сложные запросы channel tests |
| `DAC-10` | Entity-параметры отчёта | Reports parameter boundary | `EntityParamRefresher` выполняет read gate, включает filters и перечитывает через JPQL | Не нужен | **Покрыт.** Сохранить отдельную проверку от подмены detached entity/id |
| `DAC-11` | Асинхронный запуск отчёта | Reports orchestration | Диалоги создают raw `Thread` и вручную переносят `SecurityContext`/request context | Не допускается неявный `system` | **Частично, P0.** Ввести managed executor с обязательным immutable security snapshot и очисткой всех ThreadLocal state |
| `DAC-12` | Export и run-cache отчёта | Reports artifact boundary | Export работает с готовым `JasperPrint`; cache key включает user, template/version, params и context | System export только отдельным use case | **Частично.** `cached(key)` доверяет opaque key; добавить авторизацию владельца артефакта и тест cross-user access |
| `DAC-13` | Прямой repository/`EntityManager` из application service | Platform boundary вокруг persistence | Enforcement зависит от ручного вызова activator; `ValidatedJpaCrudService` RLS не применяет | Только зарегистрированный typed bypass | **Разрыв как общий контракт.** Запретить protected entity вне secured facade/interceptor; существующие unprotected paths инвентаризировать при добавлении policy |
| `DAC-14` | Проверка ссылочной целостности перед delete | `ReferenceCheckService` | Намеренно вызывает `withRlsDisabled`, чтобы скрытая ссылка тоже блокировала удаление; наружу возвращает только факт/описание blockers | Разрешён только этому use case | **Допустимый обход, но не типизирован и не аудируется.** Ввести actor/reason/scope и audit event |
| `DAC-15` | RLS administration: grants, users/roles, dimension values | Security administration boundary | `AccessGrantAdminService` обращается к repositories; dimension sources читают все значения через `withRlsDisabled`; `AdminView` проверяет ADMIN в UI | Разрешён security-admin use case | **Частично, P0.** Перенести ADMIN/resource permission на service boundary; типизировать и аудитировать обход |
| `DAC-16` | Auth/RLS bootstrap: users, roles, grants | Security kernel | Прямые repositories нужны для вычисления субъекта и grants, иначе возникает рекурсия policy | Внутренний kernel scope | **Особый канал.** Явно исключить только security metadata classes и запретить возврат business entities |
| `DAC-17` | Scheduled/background jobs | Job-specific service boundary | `RetentionPurgeJob` работает JDBC только с telemetry tables; общего security context contract нет | Только зарегистрированный system job с reason/scope | **Частично.** До первого business-data job ввести deny-by-default job context; не использовать молчаливый anonymous=`system` |
| `DAC-18` | Native SQL/JDBC и operational telemetry | Владельцы подсистем telemetry/diagnostics | Hibernate filters неприменимы; текущие JDBC paths относятся к operation log, field audit и settings диагностики | Startup/self-test/retention scopes | **Вне entity RLS, но требует resource permissions C5.** Любой JDBC к business tables должен быть отдельно зарегистрирован или запрещён |
| `DAC-19` | Startup/bootstrap/schema compatibility | Infrastructure owner | `DataInitializer`, schema/self-tests используют repositories/JDBC/отдельный `EntityManager` без user context | Startup-only scope | **Частично.** Отделить dev/demo initializer от production и типизировать startup authority |
| `DAC-20` | REST JPQL preview: `POST /api/report-jpql/preview` | Integration/report boundary | HTTP Basic endpoint сначала проверяет CHECK_ONLY `REPORTS:JPQL_PREVIEW`, затем вызывает общий `JpqlRunService` (guard + RLS + limits) | Named service account с минимальным report scope; silent system bypass запрещён | **Покрыт текущим pipeline.** Сохранить endpoint test и не разрешать альтернативное выполнение JPQL |
| `DAC-21` | Будущие import, messaging, external integrations и новые REST adapters | Будущий integration boundary | Общего контракта пока нет | Только named service account с минимальным scope | **Зарезервирован.** Новый adapter не допускается к repository/EM; он обязан войти через secured application API |

## Реестр существующих обходов

| Обход | Назначение | Допустимый результат наружу | Что отсутствует |
|---|---|---|---|
| `RlsFilterActivator.withRlsDisabled` в `ReferenceCheckService` | Увидеть все ссылки и не удалить объект из-за скрытых для пользователя строк | Только veto/список типов блокирующих ссылок | Typed reason, ограничение entity/operation scope, audit |
| `withRlsDisabled` в `JournalDimensionValueSource` и `BranchDimensionValueSource` | Построить полную матрицу грантов | Идентификатор, код и имя dimension value только администратору | Server-side resource permission, typed reason, audit |
| `RlsContext.runAsSystem/callAsSystem` | Общая техническая возможность | Сейчас production call sites не обнаружены | API слишком широк; до C2 не использовать для новых сценариев |
| Bootstrap/self-test/retention | Инициализация, проверка схемы, очистка telemetry | Только инфраструктурный результат | Явные startup/job scopes и production profile guard |

Отсутствие authentication не является разрешённым обходом. Текущий fallback
`CurrentUser -> "system"` должен быть устранён или сведен к явно созданному system
context: защищённый запрос без подтверждённого субъекта обязан завершаться отказом.

## Критические выводы C1

1. Защита сейчас **call-site based**: правильные platform services включают RLS, но
   repository или `EntityManager` остаются параллельным незащищённым входом.
2. `RlsStatementGuard` — detection-only canary. Даже strict test mode регистрирует
   нарушение, а не является production deny boundary.
3. OSIV и флаг активации на `EntityManager` делают результат зависимым от того, какой
   метод первым использовал session. Это нельзя считать security contract.
4. Raw background threads вручную переносят часть контекста и требуют замены на
   управляемый execution context.
5. UI-проверка ADMIN в `AdminView` не защищает вызываемый service. Разрешение должно
   проверяться на server-side boundary.
6. Политика секций «наследовать root» разумна для owned rows, но должна стать явной
   metadata policy, а не предположением реализации.

## Обязательный test grid для C2

Каждый защищённый тип проверяется через применимые каналы `DAC-01`–`DAC-13` и
`DAC-20`:

- нет authentication / неизвестный subject;
- нет grant;
- grant на одну строку;
- wildcard grant;
- CHECK_ONLY deny/allow;
- read/update/delete независимо;
- join, subquery, projection, count и pagination;
- worker thread и повторное использование thread/session;
- попытка cross-user доступа к report artifact;
- HTTP Basic REST preview без `REPORTS:JPQL_PREVIEW` и с разрешённым grant;
- разрешённый bypass с проверкой scope и audit record.

Новый канал считается поддержанным только после добавления строки в эту матрицу и
негативного channel test. Неизвестный канал для protected entity должен завершаться
отказом до SQL.

## Порядок реализации C2

1. Ввести единый `RlsPolicyDescriptor` и fail-closed subject resolution.
2. Поставить обязательный enforcement перед repository/query execution; guard оставить
   независимой проверкой корректности.
3. Первым вертикальным срезом закрыть `Journal` и `PrdSpec`: CRUD, lookup, custom
   search, global search и report projection.
4. Затем закрыть `Branch`/`Workshop` и многомерный `ReceivingDocument`, включая owned
   rows и CHECK_ONLY.
5. Типизировать два реальных bypass use case, добавить audit и server-side permissions.
6. Добавить архитектурный тест против repository/`EntityManager` в UI и protected
   application query вне boundary.

Сама C1 не меняет поведение runtime: она фиксирует поверхность атаки и критерии, по
которым C2 можно считать завершённым.
