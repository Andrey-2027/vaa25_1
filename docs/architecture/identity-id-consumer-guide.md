# IdentifiableEntity<ID>: руководство потребителя

Статус: IG1–IG8 завершены 2026-10-09. Нейтральный identity-контракт параметризован;
текущий persistence/CRUD-контур сохраняет Long. Это руководство относится к проверенному
локальному комплекту IG7, а не к обещанию поддержки произвольного ключа во всей платформе.
[План и результаты этапов](identifiable-entity-generic-plan.md),
[протокол общей приёмки](../../outputs/ig7/IG7-report.md).

## Подключение и владение

Единственный владелец `org.ipro.identity.IdentifiableEntity` — Java-only артефакт
`org.ipro:platform-identity-api:1.0-SNAPSHOT`, собираемый реактором sibling-проекта `crudui`.
В нём нет Spring, JPA или Vaadin. Для Java-модели достаточно зависимости:

```xml
<dependency>
    <groupId>org.ipro</groupId>
    <artifactId>platform-identity-api</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

Платформенный потребитель выбирает нужные модули/стартеры и согласованные версии через
`org.ipro:platform-bom`. BOM задаёт координаты, но одинаковое имя SNAPSHOT не доказывает,
что локальные JAR собраны из одного состояния исходников. До публикации неизменяемого
комплекта действует [workspace build contract](build/local-dependency-workspace.md):
manifest, строгие source fingerprints, проверенные owner JAR и общий application verify.
Готовая поставка включает нужные JAR, POM/parent POM и транзитивные зависимости.

Не следует копировать интерфейс в исходники приложения или добавлять второй интерфейс
со старой сигнатурой. Текущий FQN сохраняется; исторический
`org.ipro.crud.IdentifiableEntity` не возвращается. Контракт в исходниках:
[IdentifiableEntity.java](../../../crudui/platform-identity-api/src/main/java/org/ipro/identity/IdentifiableEntity.java).

## Объявления типов

Для прямой реализации существующей Long-модели:

```java
// LongEntity.java
import org.ipro.identity.IdentifiableEntity;

public final class LongEntity implements IdentifiableEntity<Long> {
    private Long id;

    @Override
    public Long getId() { return id; }

    @Override
    public void setId(Long id) { this.id = id; }
}
```

Это Java-модель для примера, без JPA mapping. Production-сущность, уже наследующая
`org.ipro.crud.BaseEntity`, наследует `IdentifiableEntity<Long>` автоматически:
повторный implements и дополнительный параметр ID у BaseEntity не нужны. Класс BaseEntity
остаётся непараметризованным; Long id, генерация IDENTITY, audit и version сохраняются.
Новая сущность имеет null id; при создании копии для новой записи прежний id не переносится.

Выбор формы зависит от операций потребителя:

| Операция | Декларация |
|---|---|
| Прямая реализация текущей сущности | `implements IdentifiableEntity<Long>` |
| Текущий CRUD, aggregate save, owned root/row, форма | `T extends IdentifiableEntity<Long>` |
| Нейтральный lifecycle/event-контракт | `T extends IdentifiableEntity<?>` |
| Чтение неизвестной identity / проверка null | `IdentifiableEntity<?>` |
| Runtime-проверка интерфейса | `instanceof IdentifiableEntity<?>` |
| Class token интерфейса | `IdentifiableEntity.class` |

Безопасное чтение неизвестной identity и чтение с известным Long-контрактом:

```java
// IdentityUsage.java
import org.ipro.identity.IdentifiableEntity;

public final class IdentityUsage {
    public static boolean isPersisted(IdentifiableEntity<?> entity) {
        return entity.getId() != null;
    }

    public static <T extends IdentifiableEntity<Long>> Long longId(T entity) {
        return entity.getId();
    }

    public static <ID> ID identityOf(IdentifiableEntity<ID> entity) {
        return entity.getId();
    }
}
```

Из wildcard getId возвращается Object. Для передачи в Long API нужен известный
Long-bound либо проверка фактического значения `instanceof Long`. Два независимых
wildcard не доказывают одинаковый тип ключа. `instanceof IdentifiableEntity<Long>`
запрещён стиранием Java. Raw casts, `Number.longValue()`, преобразование через строку и
широкий SuppressWarnings не являются способом расширить поддерживаемый тип ключа.

Lifecycle сохраняет один параметр T: `EntityLifecycle<MyEntity>`,
`EntitySaveContext<MyEntity>` и остальные контексты не требуют нового параметра ID.
Нейтральные контракты/dispatch допускают собственный тип ключа; возможность выполнить
callback сама по себе не даёт такой сущности доступ к текущему Long persistence/CRUD.

## Граница CRUD и исторический ID

`CrudService<T extends IdentifiableEntity<Long>>` сохраняет `delete(Long)` и
`findById(Long)`. `BaseService<T extends IdentifiableEntity<Long>, ID>` сохраняет
исторический независимый параметр ID; он не связан с identity T и не означает, что
`BaseService<..., String>` поддержан платформой. Для действующего контура выбирается Long:

```java
// ServiceUsage.java
import java.util.Optional;
import org.ipro.crud.BaseService;
import org.ipro.crud.CrudService;
import org.ipro.identity.IdentifiableEntity;

public final class ServiceUsage {
    public static <T extends IdentifiableEntity<Long>> Optional<T> load(
            BaseService<T, Long> service, Long id) {
        CrudService<T> longCrud = service;
        return longCrud.findById(id);
    }
}
```

Этот же выбор действует для locator/navigation API с исторической парой `<T, ID>`.
В generic `BaseService<T, Long>` прямой вызов `findById(id)` может быть неоднозначным
для javac: доступны `CrudService.findById(Long)` и `BaseService.findById(ID)`.
Пример обращается через Long-контракт `CrudService<T>` без unchecked cast.
Это ещё одно проявление существующего долга T–ID, а не обещание его устранения в IG8.
Его нельзя механически заменить на `T extends IdentifiableEntity<ID>`: CrudService и
runtime остаются Long. Отдельная полная типизация потребовала бы согласованного решения
по CrudService, BaseService, resolver/locator, persistence, UI и представлению ключа.

## Что поддержано сейчас

| Контур | Граница поддержки |
|---|---|
| Identity API | Любой reference type ID без bounds Number/Serializable; это Java-контракт |
| Lifecycle/context/events | Не ограничивают ID на Long; EventContext.aggregateId остаётся Object |
| BaseEntity, canonical write, owned sections, aggregate rollback | Long; root и row имеют собственные id, parent-связь задаётся отдельно |
| CrudService, стандартные BaseService implementations, lookup/forms | Действующий Long-контур; исторические параметры ID не расширяют его |
| FormRoute / deep links | Long; codec принимает положительную десятичную запись без знака/ведущих нулей |
| Register report compiler / reference resources | Одиночный Long id, проверяемый RegisterSliceSource до генерации |
| RecorderRef и UI/report reference snapshots | Сохраняют действующее Long-представление ссылок |
| REST read / JSON | Существующие id/reference id остаются числовыми Long; новый wire-формат не вводится |
| C5/RLS, FetchPlan, telemetry | Сохраняют действующие сценарии; wildcard/Object внутри API не доказывает приёмку другого физического ключа |

Для String/UUID/custom ID допустима нейтральная Java-модель и соответствующие lifecycle
контракты. Поддержка другого физического ключа в JPA/CRUD и всех потребителях — отдельная
работа со своей приёмкой. Существующий внутренний JPA-only
`RecorderOrderClock.registerKey` типа String не реализует IdentifiableEntity и не служит
примером поддержки String в доменном CRUD.

## Миграция внешнего потребителя

1. Обновить согласованный комплект библиотек, включая identity-api и все изменённые
   consumers/implementors. В текущем workspace использовать manifest/bootstrap;
   выбранный repository для IG1–IG7 — `outputs/ig1/m2`. Стороннему потребителю нужен
   тот же проверенный комплект в его Maven repository.
2. Прямые реализации заменить на `IdentifiableEntity<Long>`. Нейтральное чтение и
   lifecycle bounds — на wildcard; текущие CRUD/формы/sections — на Long-bound.
   Наследников BaseEntity не параметризовать повторно.
3. Заново скомпилировать реализацию и всех Java-потребителей через clean build, включая
   расширения/плагины, которые вызывали методы интерфейса. Сохранение FQN и SNAPSHOT
   версии не обеспечивает binary compatibility.
4. Проверить dependency tree и runtime classpath: единственный identity-api из нового
   комплекта, без старой копии интерфейса в другом JAR/исходниках. Проверить generic
   API baseline и собственные CRUD/rollback/UI/wire-сценарии приложения.

До параметризации JVM descriptors интерфейса были `()Ljava/lang/Long;` и
`(Ljava/lang/Long;)V`; теперь стирание ID даёт `()Ljava/lang/Object;` и
`(Ljava/lang/Object;)V`. Старый бинарный вызов Long-метода интерфейса может завершиться
NoSuchMethodError; новая сторона со старой реализацией — AbstractMethodError.
Согласованная перекомпиляция обязательна. Компилятор создаёт Object bridge-методы у
Long-реализации; это ожидаемая часть новой поставки, не мост совместимости со старым
интерфейсом для всех старых binaries.

Миграция generic identity не требует schema/data migration, замены PK/FK, генератора
или новой ручной регистрации сущностей. Существующие entity/repository scans, metadata
и декларации lifecycle/sections остаются способом подключения приложения.

## Оставшиеся runtime bridges и владельцы

Ниже перечислены границы стирания/динамического типа на мигрированных identity-путях.
Это не реестр всех reflection/casts платформы. Технический bridge принадлежит runtime,
а приложение передаёт корректные типы через обычные API.

| Владелец и участок | Что проверяется / оставшийся долг | Подтверждение |
|---|---|---|
| persistence: BaseEntity; app: RefSnapshot | Сгенерированные javac Object bridges вокруг конкретных Long методов | BaseEntityPersistenceTest, runtime identity audit IG7, javap IG6 |
| events: EntityLifecycleRegistry.lookup / dispatchAfterCommit | Map индексируется handler.entityType; локальный lookup cast, затем Class.cast перед callback | LifecycleTransactionTest |
| core: ServiceLocator / EntityDataAccessResolver | Type-directed service index/canonical handle; локальные generic casts сохраняют исторический независимый ID | ServiceLocatorCanonicalFallbackTest; долг T–ID остаётся открытым |
| core: CanonicalWriteExecutor lifecycle dispatch и entity Class helpers | Runtime entity Class связывается с wildcard lifecycle API; локальные erasure casts | CanonicalWritePathIT |
| core: MetadataDrivenAggregateSaveService.entityType / ResolvedInput | Runtime Class root/row; rowType.cast для входных строк | MetadataDrivenAggregatePreflightTest, GenericIdentityAggregateIT |
| core: AggregateSaveRollbackState.requireLongIdentity | Hibernate proxy unwrap, declared Long generic argument даже при null id, проверка текущего id до локального cast | AggregateSaveRollbackStateTest, GenericIdentityAggregateIT |
| core: EntityPresentationService | Динамический entity Class для чтения; только фактический Long принимается как reference id | EntityPresentationServiceTest |
| vaadin: FormCoordinator / ItemFormWrapperView factory и callback boundaries | Runtime Class/variant выбирают форму; saved callback получает entity через entityClass.cast | FormResolverTest, ItemFormWrapperViewSaveTest |
| vaadin: MetadataDrivenItemFormSaveAdapter.longRowType / applySection | Проверка declared Long до Class cast; rowType.cast при применении строк | MetadataDrivenItemFormSaveAdapterTest |
| vaadin: ItemFormLinkAffordance.idOf; app: RecorderRefPresentationResolver / NomAttributeValueItemForm | Wildcard identity с проверкой Long; произвольный Number/String не преобразуется в Long; RefSnapshot хранит Long | ItemFormLinkAffordanceTest, RecorderRefPresentationResolverTest, NomAttributeValueItemFormTest |

Sources: [core](../../platform-core/src/main/java/org/ipro),
[events registry](../../platform-events/src/main/java/org/ipro/lifecycle/EntityLifecycleRegistry.java),
[vaadin](../../platform-vaadin/src/main/java/org/ipro/form),
[app recorder presentation](../../src/main/java/org/ip/views/register/RecorderRefPresentationResolver.java),
[RefSnapshot и reference guard](../../src/main/java/org/ip/views/forms/NomAttributeValueItemForm.java).

## Проверенный baseline

IG7 выполнил clean compile всех main/test sources и общий `clean verify`:
**2009 тестов, 0 failures/errors, 27 PostgreSQL skipped**. Отдельный PostgreSQL-прогон —
**55/55 PASS**, включая все 27 пропущенных. Strict manifest validation и API/architecture
gates прошли; 22 вложенных owner JAR совпали с проверенными зависимостями, identity-api
представлен одной копией. Frontend bundle и браузерная приёмка в этом прогоне не выполнялись.

API snapshots в `src/test/resources/platform-api-baseline` фиксируют generic bounds и
declarations методов/конструкторов; compiler-generated synthetic/bridge methods исключены
прежним правилом. Baseline обновляют после review diff, а проверку запускают без write-флага.
Source fingerprints из [manifest](../../scripts/local-dependencies.json) соответствуют
проверенному состоянию IG7. Изменения документации IG8 не входят в fingerprint inputs;
переписывать hashes ради даты документа не требуется.
