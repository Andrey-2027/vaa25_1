# ADR-0005: единая прикладная точка lifecycle-поведения сущности

- Статус: принято; core B4 реализован в текущем scope
- Дата: 2026-09-11
- Область: application API, entity lifecycle, aggregate rules, developer experience

## Контекст

Платформа уже предоставляет metadata-driven формы, нумерацию, стандартный
`BaseService`, атомарное сохранение агрегатов и lifecycle events. Предметные проверки
сейчас могут быть реализованы через `EntitySavingEvent`, `AggregateSavingEvent`,
переопределение service hook либо custom save handler.

Эти механизмы достаточны как внутренняя инфраструктура, но не образуют очевидного
прикладного API. Разработчику нового справочника или агрегата непонятно, где находится
аналог обработчика `ПередЗаписью`. При использовании Spring events он должен знать
технические типы событий, учитывать стирание generic-параметров и вручную фильтровать
события через `instanceof` или `context.aggregateType()`.

Свободное размещение нескольких listeners также затрудняет обзор поведения сущности:
правила могут оказаться в service, form handler, listener и UI-классе. Простая замена
этого набора одним универсальным `ObjectModule` или `ManagerModule` проблему не решает:
такой класс быстро смешает lifecycle, persistence, запросы, предметные workflow и UI.

Требуется единая, предсказуемая точка входа для authoritative lifecycle-правил без
переноса всей предметной функциональности в Hibernate entity или god-class.

## Решение

### 1. Публичным прикладным API становится `EntityLifecycle<T>`

Платформа вводит типизированный optional SPI:

```java
public interface EntityLifecycle<T extends IdentifiableEntity> {

    Class<T> entityType();

    default void beforeSave(EntitySaveContext<T> context) {
    }

    default void beforeUpdate(EntityUpdateContext<T> context) {
    }

    default void beforeAggregateSave(AggregateSaveContext<T> context) {
    }

    default void beforeDelete(EntityDeleteContext<T> context) {
    }

    default void onSave(EntitySaveContext<T> context) {
    }

    default void afterCommit(EntityChangedContext<T> context) {
    }
}
```

Контракт и порядок фаз являются частью public API:

- `beforeSave` вызывается для самой entity на любом стандартном create/update/save path;
- `beforeUpdate` вызывается для существующей записи, когда platform может передать
  исходное и новое состояние; это каноническое место для правил вроде
  `ПриИзмененииКода`. Для уже managed instance callback также вызывается, но
  надёжный diff доступен только при наличии отдельного исходного snapshot;
- `beforeAggregateSave` получает root и только фактически подключённые owned sections;
- `beforeDelete` является синхронным veto до удаления;
- `onSave`, если предоставляется SPI, вызывается после `repository.save`, но внутри
  активной транзакции;
- `afterCommit` вызывается только после успешного commit и не может отменить операцию.

Для обычного entity save pipeline это означает следующую последовательность:

```text
beforeSave -> beforeUpdate (только существующая запись) -> repository.save
  -> onSave (та же транзакция) -> commit -> afterCommit
```

`beforeAggregateSave` выполняется до persistence root и получает снимок состава
подключённых sections; затем root проходит обычный entity pipeline, а `onSave` агрегата
вызывается coordinator'ом после сохранения root и sections. Если `beforeSave`,
`beforeUpdate` или `onSave` выбрасывает исключение, текущая транзакция откатывается;
`afterCommit` уже ничего отменить не может.

Один класс может содержать правила шапки и правила агрегата, поэтому стандартное место
для поведения объекта находится по имени `<Entity>Lifecycle`.

### 2. Для одной сущности допускается не более одного прикладного lifecycle handler

Кардинальность контракта:

```text
Entity -> 0..1 EntityLifecycle
```

Lifecycle-класс не требуется, если у сущности нет специальных предметных правил.
Registry автоматически собирает Spring beans `EntityLifecycle<?>`, индексирует их по
`entityType()` и при старте отклоняет дубли. Порядок Spring beans и `@Order` не должны
создавать скрытый приоритет нескольких authoritative handlers одной сущности.

Если правил много, lifecycle handler остаётся единой точкой входа и явно делегирует
узким предметным rule/policy-классам. Таким образом обзор порядка сохраняется, а сам
handler не обязан содержать реализацию всех алгоритмов.

Пример:

```java
@Component
public final class ReceivingDocumentLifecycle
        implements EntityLifecycle<ReceivingDocument> {

    private final WorkshopTransferRule workshopTransferRule;
    private final UniqueDocumentItemsRule uniqueItemsRule;

    @Override
    public Class<ReceivingDocument> entityType() {
        return ReceivingDocument.class;
    }

    @Override
    public void beforeSave(EntitySaveContext<ReceivingDocument> context) {
        workshopTransferRule.validate(context.entity());
    }

    @Override
    public void beforeAggregateSave(
            AggregateSaveContext<ReceivingDocument> context) {
        uniqueItemsRule.validate(
            context.entity(),
            context.section(ReceivingDocumentItem.class));
    }
}
```

### 3. Lifecycle registry подключается к существующим transaction boundaries

`AbstractBaseService` вызывает entity callbacks в стандартном save/delete pipeline.
`MetadataDrivenAggregateSaveService` вызывает aggregate callback внутри общей
транзакции root и attached sections. Custom aggregate use case обязан использовать тот
же lifecycle dispatcher либо явно документировать эквивалентный platform contract.

Прикладной handler не получает Vaadin-компоненты, repository конкретной формы или
неуправляемый `EntityManager`. Context передаёт только стабильные данные операции:
entity/aggregate, attached sections, источник, пользователя, идентификатор корреляции и
другие подтверждённые application-level атрибуты.

Lifecycle events могут остаться внутренним транспортом реализации. Однако
`@EventListener` не является рекомендуемой точкой для authoritative правил конкретной
сущности. Существующие прикладные veto-listeners мигрируют в `EntityLifecycle` после
появления registry с behavioural parity.

### 4. Границы ответственности фиксируются явно

| Требование | Каноническое место |
|---|---|
| Стандартное ограничение поля | Bean Validation/JPA metadata в entity |
| Чистый локальный инвариант без I/O | Метод entity или value object |
| Проверка перед записью entity | `<Entity>Lifecycle.beforeSave` |
| Реакция/проверка изменения существующей entity | `<Entity>Lifecycle.beforeUpdate` |
| Проверка root вместе с owned sections | `<Entity>Lifecycle.beforeAggregateSave` |
| Запрет удаления | `<Entity>Lifecycle.beforeDelete` |
| Реакция после успешного commit | `afterCommit` либо integration subscriber/outbox |
| Проведение, закрытие, заполнение, пересчёт | typed `<Entity>Operations`/application use case |
| Специализированная выборка | typed `<Entity>Queries` или entity-specific service |
| Кнопка, навигация и представление результата | form customization/UI command |
| Нестандартный persistence workflow | custom save handler/use case |

`EntityLifecycle` не становится persistence service, query service, UI controller или
контейнером произвольных публичных методов.

### 5. «Модуль менеджера» не вводится как обязательный класс

Стандартную manager-функциональность предоставляют platform services: CRUD, поиск,
RLS, нумерация, validation, events и metadata-driven aggregate save. Обычная сущность
не должна требовать пустого application service или manager module.

Entity-specific service создаётся только для специализированных запросов или реальной
предметной семантики. Многошаговые транзакционные операции оформляются typed application
services/use cases (`<Entity>Operations` либо более предметное имя), а не добавляются в
универсальный manager module.

### 6. Код группируется вертикально по feature/entity

Для новых прикладных объектов рекомендуются feature-пакеты вместо обязательного
разнесения по глобальным `model`, `service`, `views` и `application`:

```text
org.ip.production.receivingdocument
  ReceivingDocument.java
  ReceivingDocumentItem.java
  ReceivingDocumentLifecycle.java
  ReceivingDocumentOperations.java       # только при наличии workflow
  ReceivingDocumentQueries.java          # только при специальных выборках
  ReceivingDocumentForms.java            # только при UI-отличиях
  ReceivingDocumentRepository.java
  rules/
    WorkshopTransferRule.java
    UniqueDocumentItemsRule.java
```

Пакет является физическим обзором возможностей объекта; lifecycle-класс — единой
точкой входа в его callbacks. Существующий код переносится постепенно, без массового
package refactoring ради одного этапа.

### 7. Entity Explorer показывает resolved behavior

Entity Explorer должен показывать для каждой сущности как минимум:

- default либо custom persistence service;
- зарегистрированный `EntityLifecycle` или отсутствие custom lifecycle;
- form variants/customizations;
- UI/application actions;
- custom save handler;
- numbering и owned sections.

Это вычисляемый read-only `EntityDefinition`, собираемый из metadata и Spring registries,
а не обязательный пользовательский `EntityConfig`. Для стандартной сущности отдельный
config-класс не создаётся.

### 8. Соглашение об именовании является частью public DX

Для сущности `Nomenclature` используются предсказуемые имена:

```text
NomenclatureLifecycle   lifecycle callbacks и их порядок
NomenclatureOperations  предметные команды, если нужны
NomenclatureQueries     специальные выборки, если нужны
NomenclatureForms       варианты/кастомизация UI, если нужны
```

Документация создания сущности и будущий scaffolder должны направлять обработчик
`ПередЗаписью` именно в `<Entity>Lifecycle.beforeSave`, а правило шапки и табличных
частей — в `beforeAggregateSave`.

## Developer-experience contract

1. Для сущности без специальных правил не требуется lifecycle-класс.
2. Добавление первого правила `ПередЗаписью` требует одного класса
   `<Entity>Lifecycle`, автоматически обнаруживаемого Spring.
3. Правило `ПриИзмененииКода` и другие diff-зависимые проверки пишутся в
   `beforeUpdate`; UI-обработчик поля остаётся только для визуальной реакции.
4. Разработчик не регистрирует handler вручную в `EntityMetadata`, `EntityConfig` или
   центральном switch.
5. Duplicate lifecycle handler для одного entity type обнаруживается при старте.
6. Один и тот же handler вызывается из generic form, REST/import и typed use case,
   использующих standard platform transaction boundary.
7. Aggregate context различает `ABSENT`, `ATTACHED + empty` и `ATTACHED + rows` и не
   подменяет отсутствующую секцию пустой.
8. Entity Explorer позволяет увидеть и найти lifecycle-класс сущности.
9. Authoritative правило не размещается в Vaadin View или UI-команде.

## Последствия

Положительные:

- у прикладного разработчика появляется один очевидный аналог `ПередЗаписью`;
- поведение сущности обозримо без поиска всех Spring listeners;
- callbacks типизированы и не требуют `instanceof` из-за event type erasure;
- правила работают одинаково для UI, API, импорта и use cases;
- единая точка входа совместима с декомпозицией сложной логики на отдельные rules;
- стандартные сущности не получают обязательный boilerplate;
- lifecycle, operations, queries и UI не смешиваются в одном god-class.

Ограничения и риски:

- registry и dispatcher становятся частью критического save/delete pipeline и требуют
  unit, integration и architecture tests;
- нужно определить точную семантику ошибок post-persistence и after-commit callbacks;
- custom use cases обязаны не обходить lifecycle dispatcher;
- feature-пакеты требуют последовательной package-конвенции и не вводятся массовым
  перемещением существующего кода;
- единый handler может разрастаться, если не делегировать сложные правила узким классам.

## Рассмотренные альтернативы

### Оставить прикладным API непосредственные Spring events

Отклонено как основной DX: handlers трудно обнаружить, generic type стирается, тип
приходится проверять вручную, а несколько listeners скрывают полный набор и порядок
authoritative правил. Events остаются допустимыми для decoupled reactions и
интеграционных подписчиков.

### Помещать всю логику в Hibernate entity

Отклонено: entity подходит для чистого локального поведения, но не для зависимостей на
repository, security context, внешние сервисы и transaction orchestration.

### Один универсальный `ObjectModule` на сущность

Отклонено как нетипизированный контейнер произвольных методов. Без узкого lifecycle
контракта платформа не знает семантику вызова, а класс смешивает callbacks, команды,
запросы и инфраструктуру.

### Обязательная пара `ObjectModule` и `ManagerModule`

Отклонено: создаёт пустые классы для стандартных сущностей и дублирует platform
`BaseService`. Typed operations и queries создаются только при наличии предметной
необходимости.

### Обязательный `EntityConfig`, регистрирующий классы модулей

Отклонено как основной механизм: дублирует Spring discovery, ослабляет типобезопасность
через `Class<?>` и создаёт дополнительный файл даже для стандартного объекта. Вместо
этого registry строит resolved `EntityDefinition`; config/DSL может появиться позднее
только как optional contributor подтверждённых capabilities.

## План реализации

1. Определить context-контракты и `EntityLifecycle<T>` в platform package без Vaadin.
2. Добавить fail-fast `EntityLifecycleRegistry` и unit-тесты duplicate/type resolution.
3. Подключить entity callbacks к `AbstractBaseService`, сохранив порядок numbering,
   Bean Validation, RLS и lifecycle events.
4. Подключить aggregate callback к `MetadataDrivenAggregateSaveService` с корректной
   section presence semantics.
5. Мигрировать `ReceivingDocumentRulesListener` и
   `PrdSpecCrossValidationListener` после behavioural parity.
6. Добавить channel tests для generic form, direct service и custom use case.
7. Расширить Entity Explorer сведениями о lifecycle и custom handlers.
8. Добавить короткий app-developer guide и шаблон `<Entity>Lifecycle`.

Пункты 1–5 реализованы в текущем B4 core scope: введены context contracts,
`EntityLifecycle<T>`, fail-fast registry и callbacks в entity/aggregate save boundaries;
контрольные listeners мигрированы в typed lifecycle handlers. Пункты, относящиеся к
расширенным channel tests, Entity Explorer, scaffolder и отдельному app-developer guide,
остаются последующими шагами B4/E3.

## Связь с другими решениями

ADR опирается на transaction semantics ADR-0002, AppDev-first ADR-0003 и capability
model ADR-0004. Он уточняет, как прикладные authoritative rules подключаются к уже
существующим entity и aggregate save boundaries, не возвращая обязательные typed
save-use-case классы для стандартных объектов.
