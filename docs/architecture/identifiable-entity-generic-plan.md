# План: `IdentifiableEntity<ID>` с сохранением `Long`

- Дата: 2026-10-05.
- Статус: IG0 и baseline приняты; IG1–IG8 выполнены 2026-10-09. Generic identity с сохранением Long завершена; общий regression gate IG6 закрыт. Поддержка другого физического ID и полная типизация CRUD по ID остаются самостоятельными работами.
- Результаты IG1: [протокол owner-проверок](../../outputs/ig1/IG1-report.md). Проверенные API/core JAR установлены в отдельный `outputs/ig1/m2`; общий комплект приложения ещё не пересобран.
- Результаты IG2: [протокол persistence-проверок](../../outputs/ig2/IG2-report.md). BaseEntity получил Long-контракт; 11 owner-тестов и проверки наследования/схемы прошли. Проверенный persistence JAR установлен в тот же repository миграции.
- Результаты IG3: [протокол lifecycle/events](../../outputs/ig3/IG3-report.md). Нейтральные wildcard-контракты и проверенный afterCommit dispatch; 45 owner-тестов прошли. Проверенные contracts/events JAR установлены в repository миграции.
- Результаты IG4: [протокол canonical CRUD/aggregate](../../outputs/ig4/IG4-report.md). Long-bound core, proxy-aware service lookup, проверенная граница rollback identity; 176 owner-тестов прошли. Проверенный core JAR установлен в repository миграции. Общая строгая manifest validation по-прежнему останавливается на отдельном drift platform-rls/platform-vaadin; API baseline и общая сборка остаются IG7.
- Результаты IG5: [протокол форм/действий/навигации](../../outputs/ig5/IG5-report.md). Long-bound формы, проверенные callback/row Class bridges и Long deep-link boundary; 498 owner-тестов прошли. Проверенный Vaadin JAR установлен в repository миграции; актуальный fingerprint Vaadin подтверждён. Общая строгая manifest validation остаётся FAIL только по прежнему drift platform-rls. Приложение/смежные потребители — IG6, API baseline и общая приёмка — IG7.
- Результаты IG6: [исходный протокол приложения/фикстур/смежных потребителей](../../outputs/ig6/IG6-report.md). Адаптированы 18 Java-файлов; 45 тестов изменённых классов и 220 REST owner-тестов прошли, Long-контракты entities/repositories проверены. Исходный общий прогон выявил 30 failures/errors; их причины исправлены при закрытии gate в IG7. Все 27 пропущенных PostgreSQL-проверок затем выполнены отдельно.
- Результаты IG7: [протокол закрытия regression gate и согласованной поставки](../../outputs/ig7/IG7-report.md). API baseline учитывает generic bounds; reviewed ресурсы обновлены, synthetic/bridge filter сохранён. Platform-rls прошёл 77 owner-тестов, fingerprint актуализирован; strict manifest validation PASS. Финальный clean verify: 2009 тестов, 321 suites, 0 failures/errors, 27 PostgreSQL skipped; отдельный PostgreSQL-прогон — 55/55 PASS. Собран app JAR, hashes 22 вложенных owner JAR совпали с проверенными зависимостями, identity-api единственная. Frontend bundle и браузерная приёмка не выполнялись.
- Результаты IG8: [руководство внешнего потребителя](identity-id-consumer-guide.md), [протокол документации](../../outputs/ig8/IG8-report.md). Обновлены README identity/crud API, contracts, persistence и core; описаны Long/wildcard, binary incompatibility, ограничения runtime и владельцы bridges. Все три Java-примера скомпилированы против проверенных JAR; strict manifest validation PASS. Source/POM/manifest и JAR IG7 сохранены, hashes не переписывались; schema/data migration и новая ручная регистрация сущностей не вводились.
- Основание: требование сделать контракт идентичности дженериком, продолжая использовать Long у всех существующих сущностей.
- Область: нейтральный identity API, его потребители в sibling-проекте crudui, платформенные модули и приложение.
- Связанные документы: [сборка локальных зависимостей](build/local-dependency-workspace.md), [развитие регистра](information-register-development-plan.md), [baseline API](../../src/test/java/org/ipro/PlatformApiBaselineTest.java).

Результаты выше отражают завершённое состояние. Разделы ниже сохраняют исходный scope,
инвентарь и порядок реализации; описания открытых gates в результатах IG1–IG5 относятся
к моменту тех этапов и закрыты последующей приёмкой IG7.

## 1. Требуемый результат

Нейтральный контракт принимает параметр типа идентификатора:

```java
public interface IdentifiableEntity<ID> {
    ID getId();
    void setId(ID id);
}
```

Основной persistence-класс явно выбирает Long:

```java
@MappedSuperclass
public abstract class BaseEntity implements IdentifiableEntity<Long> {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Override
    public Long getId() { return id; }

    @Override
    public void setId(Long id) { this.id = id; }
}
```

Код выше показывает только части, относящиеся к идентичности. Реальный BaseEntity сохраняет version, аудит, equals/hashCode и остальные существующие методы.

После завершения:

- Все существующие production-сущности и текущие entity-фикстуры имеют Long id. Наследникам BaseEntity обычно не нужно повторять implements.
- IDENTITY, физические PK/FK, имена колонок, значения существующих ключей и версия записи сохраняются.
- Новая сущность по-прежнему имеет null id; сохранённая получает Long от действующего механизма генерации.
- Код, работающий с идентичностью, объявляет её тип явно. Raw IdentifiableEntity не служит обходом типизации.
- Контракт остаётся в org.ipro.identity и Java-only артефакте org.ipro:platform-identity-api. JPA, Spring и Vaadin туда не добавляются.
- Поставка содержит согласованно пересобранные библиотеки и приложение.

Этот результат даёт параметризованный контракт идентичности. Поддержка другого типа ключа в JPA, CRUD, формах, REST, RLS, отчётах и deep links требует собственной приёмки.

## 2. Границы текущего среза

### 2.1. Решения для контракта

1. ID не ограничивается Long, Number или Serializable. Требование Serializable допускается только там, где конкретный потребитель действительно нуждается в сериализации.
2. Методы и FQN интерфейса сохраняют названия. Дополнительный LongIdentifiableEntity и второй интерфейс с прежним контрактом не вводятся.
3. BaseEntity остаётся обычным классом с конкретным Long id. Параметризация BaseEntity и переработка иерархии документов относятся к другим задачам.
4. Runtime-данные, API-представления существующих идентификаторов и схема БД сохраняют действующий Long-контракт.

### 2.2. Правила выбора параметризации у потребителей

| Назначение кода | Целевая форма |
|---|---|
| Сущность с текущим ключом | `implements IdentifiableEntity<Long>` |
| Текущий persistence/CRUD-код, вызывающий методы с Long | `T extends IdentifiableEntity<Long>` |
| Нейтральный lifecycle-контракт, которому нужен объект сущности | `T extends IdentifiableEntity<?>` |
| Чтение неизвестной идентичности или проверка наличия id | `IdentifiableEntity<?>` |
| Runtime-проверка интерфейса | `instanceof IdentifiableEntity<?>` |
| Проверка класса/поиск бина | `IdentifiableEntity.class` |

Wildcard применяют там, где не требуется записывать конкретный ненулевой id. Для передачи getId() в Long-метод нужен известный Long-контракт. Для переноса id между объектами нужен общий доказанный тип; два независимых wildcard не доказывают его.

Запрещено устранять ошибки компиляции массовыми raw casts, преобразованием через Number.longValue(), приведением через String или добавлением suppress на класс/модуль.

### 2.3. Отдельный вопрос CrudService/BaseService

На дату исходного плана, до IG1:

```java
interface CrudService<T extends IdentifiableEntity> {
    void delete(Long id);
    Optional<T> findById(Long id);
}

interface BaseService<T extends IdentifiableEntity, ID>
        extends CrudService<T> {
    void delete(ID id);
    Optional<T> findById(ID id);
}
```

Параметр ID в BaseService уже существовал, но не был связан с контрактом идентичности T. В IG1/IG4 выполнено минимальное изменение:

```java
interface CrudService<T extends IdentifiableEntity<Long>> { /* прежние методы */ }
interface BaseService<T extends IdentifiableEntity<Long>, ID>
        extends CrudService<T> { /* прежние методы */ }
```

Все действующие параметризации BaseService и его реализаций продолжают использовать Long. Сохраняется число параметров существующих сервисных API, а новая bound у T явно описывает поддерживаемую ими идентичность.

Оставшийся отдельный ID в BaseService — зафиксированный долг существующего API: этот план не делает связь T–ID типобезопасной и не обещает поддержку BaseService с другим ключом. Исправление потребовало бы отдельного решения о согласованной цепочке `CrudService<T, ID>`, `BaseService<T, ID>`, locator и UI. Автоматически включать эту работу в данный срез не следует.

По той же причине методы с парой `<T, ID>` нельзя механически переписать на `T extends IdentifiableEntity<ID>`: сначала нужно проверить полный сервисный контракт. В текущем CRUD-пути T получает Long-bound, а исторический ID и его bridge/lookup-аудит фиксируются явно.

## 3. Фактический инвентарь на дату плана

Поиск выполнен по src/main и src/test приложения и всем проектам scripts/local-dependencies.json, с исключением target.

| Владелец | Main-файлов с упоминанием | Test-файлов с упоминанием | Основные участки |
|---|---:|---:|---|
| ../crudui | 7 | 0 | identity-api, CrudService, AbstractCrudView, AbstractEntityForm, три demo-модели |
| platform-persistence | 1 | 0 | BaseEntity |
| platform-contracts | 6 | 0 | EntityLifecycle и пять lifecycle/context-контрактов |
| platform-events | 1 | 3 | EntityLifecycleRegistry, owner-фикстуры |
| platform-core | 16 | 6 | canonical write/service, BaseService, locator, owned sections, rollback |
| platform-vaadin | 21 | 3 | формы, фабрики, coordinator, actions, explorer |
| Приложение | 3 | 9 | save dispatcher, report action, RefSnapshot, тестовые потребители |
| ../FilterGrid | 1 | 0 | Историческое упоминание в Javadoc; зависимости на interface уже нет |

В основной группе 55 main-файлов и 21 test-файл с упоминаниями. Это поисковый инвентарь, а не обещанное число изменяемых файлов: сюда входят импорты, документация и проверки путей. Косвенные потребители BaseEntity/getId могут не содержать имя интерфейса.

У platform-metadata, platform-numbering, platform-settings, platform-telemetry, platform-rls, platform-rest, autoconfigure и starter-модулей прямых упоминаний по этому поиску нет. Их транзитивные зависимости, наследники и runtime-сценарии всё равно входят в проверку сборки.

Особенно важные места:

- ../crudui/platform-identity-api/src/main/java/org/ipro/identity/IdentifiableEntity.java — единственный владелец интерфейса.
- ../crudui/platform-crud-api/src/main/java/org/ipro/crud/CrudService.java — Long CRUD-контракт.
- platform-persistence/src/main/java/org/ipro/crud/BaseEntity.java — физический тип и генерация ключа.
- platform-core/src/main/java/org/ipro/crud/AggregateSaveRollbackState.java — сохранение Long id и восстановление через setId.
- platform-core/src/main/java/org/ipro/crud/ServiceLocator.java и data/EntityDataAccessResolver.java — runtime-реестры, ResolvableType и unchecked casts.
- platform-vaadin/src/main/java/org/ipro/form/builtin/ItemTable.java — перенос идентичности при обновлении строк.
- platform-vaadin/src/main/java/org/ipro/form/coordinator/ItemFormLinkAffordance.java — получение Long id через runtime-интерфейс.
- src/main/java/org/ip/views/forms/NomAttributeValueItemForm.java — RefSnapshot непосредственно реализует interface.
- src/test/resources/platform-api-baseline/ — reviewed generic-сигнатуры артефактов.

Перед реализацией инвентарь повторяется на фактических исходниках. Соседние проекты и параллельные изменения сохраняются; результаты прошлых этапов регистра не считаются новым baseline этого рефакторинга.

## 4. Совместимость и порядок поставки

### 4.1. Схема и поведение

Изменение интерфейса само по себе не требует миграции PK/FK. Приёмка должна подтвердить Long в JPA metamodel и отсутствие вызванных этим патчем изменений схемы. Существующий ddl-auto=update не является доказательством отсутствия schema drift.

### 4.2. Исходники и JVM

У ID без bound JVM-типы методов меняются:

| Метод | До | После стирания типов |
|---|---|---|
| getId | ()Ljava/lang/Long; | ()Ljava/lang/Object; |
| setId | (Ljava/lang/Long;)V | (Ljava/lang/Object;)V |

Это вывод из правил [стирания типов, JLS 4.6](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.6) и [совместимости методов, JLS 13](https://docs.oracle.com/javase/specs/jls/se21/html/jls-13.html#jls-13.4.14).

Объявление `BaseEntity implements IdentifiableEntity<Long>` сохраняет конкретные getId()/setId(Long), а компилятор создаёт необходимые bridge-методы. Эти bridges не восстанавливают старые method descriptors на самом интерфейсе. Старый JAR, вызывающий interface-метод с Long descriptor, может получить NoSuchMethodError; старые прямые реализации интерфейса также могут оказаться несовместимыми с новым setter.

Непараметризованная старая реализация с setId(Long) не является корректной реализацией нового raw-интерфейса, setter которого стирается до Object. Поэтому прямые implementors и фикстуры нужно исправить, даже если конкретные значения у них Long.

План поставки — согласованная пересборка потребителей с новым контрактом. Для опубликованных артефактов изменение оформляется как несовместимое изменение API по действующей release-policy. Для локальных SNAPSHOT фиксируются source fingerprints, состав JAR и runtime dependency tree; одно совпадение номера SNAPSHOT совместимость не доказывает.

Этапы ниже задают порядок исходных изменений и owner-проверок. Промежуточные версии отдельных JAR не являются самостоятельными production-релизами.

## 5. Этапы реализации

### IG0. Закрепить контракт и baseline

Работы:

1. Зафиксировать выбранную форму interface, отсутствие bounds у ID, Long у BaseEntity и минимальную стратегию для CrudService/BaseService.
2. Повторить инвентарь: declarations, bounds, fields/collections, method references, instanceof, Class literals, прямые getId/setId-вызовы и reflection-поиск этих методов.
3. Для каждого участка выбрать Long, wildcard или локальный runtime bridge с проверенным основанием. Описать существующие unchecked bridges отдельно.
4. Зафиксировать текущие исходники, изменения рабочего дерева, версии Java/Maven, доступные owner-тесты и dependency tree. Получить воспроизводимый целевой baseline до изменения API.
5. Проверить текущие API baseline-файлы и релевантные reflection/serializer-потребители. Найденные исходные ошибки отнести к их владельцам и не маскировать обновлением baseline.

Выход: согласованный перечень API-изменений, карта потребителей и команд проверки. Инвентарь включает sibling crudui и его demo.

### IG1. Изменить нейтральный контракт и первый owner

Работы:

1. Изменить IdentifiableEntity на `IdentifiableEntity<ID>`, сохранив package и два имени методов.
2. Уточнить Javadoc/README/POM-описание identity-api: параметризованный контракт; текущий persistence-контур использует Long.
3. Задать CrudService bound `IdentifiableEntity<Long>`, сохранив его единственный T и Long-параметры методов.
4. Адаптировать AbstractCrudView к Long CRUD-пути. AbstractEntityForm может использовать wildcard-bound там, где не записывает id и не вызывает Long CRUD.
5. Перевести DemoProduct, DemoInvoice, DemoInvoiceItem на `IdentifiableEntity<Long>`.
6. Проверить независимую сборку нейтральных модулей, crudui-core и crudui-demo. Нейтральные compile-зависимости не расширять UI/JPA-зависимостями.

Проверки:

- Компилируемый внешний Java-потребитель принимает `IdentifiableEntity<Long>`, читает Long без cast и записывает Long/null.
- Передача String в setter этого Long-контракта отвергается компилятором.
- Reflection показывает один type parameter и generic-return/parameter ID. javap -s подтверждает Object descriptors.
- Demo и CRUD-библиотека компилируются с новым owner JAR.

Gate: контракт проверен владельцем, опубликованные локально leaf JAR соответствуют именно проверенным исходникам.

### IG2. Адаптировать persistence foundation

Работы:

1. BaseEntity реализует `IdentifiableEntity<Long>`.
2. Сохранить существующие field annotations, Long getters/setters, IDENTITY, version, аудит и equals/hashCode.
3. Проверить, что наследники StandardCatalogEntity/StandardDocumentEntity и прикладные сущности не требуют дублирования implements.
4. Проверить reflection по конкретной сущности и интерфейсу: interface-метод теперь возвращает Object на JVM, физический JPA id по-прежнему Long.

Проверки:

- Компиляция platform-persistence с новым identity-api.
- JPA metamodel действующих моделей даёт Long id.
- Создание, повторная загрузка и обновление сохраняют id и optimistic version.
- Hibernate proxy и detached-объект сохраняют прежние equals/hashCode-сценарии.
- Сравнение релевантных PK/FK и generator mapping с baseline не показывает изменений схемы.

Gate: изменение generic-interface не изменило persistence-контракт и значения ключей.

### IG3. Адаптировать нейтральные lifecycle-контракты и events

Работы:

1. `EntityLifecycle<T>` и EntitySave/Update/Delete/Changed/AggregateSaveContext получают `T extends IdentifiableEntity<?>`.
2. У контекстов сохраняются текущие параметры, поля и число generic-аргументов. ID не добавляется во все lifecycle-типы автоматически.
3. EntityLifecycleRegistry сохраняет один handler на entity type; lookup и afterCommit dispatch адаптируются к новым bounds.
4. Runtime-проверки используют wildcard, а Class literals остаются IdentifiableEntity.class.
5. Старые непосредственные implementors в owner-тестах явно реализуют `IdentifiableEntity<Long>`.
6. Сохранить EventContext.aggregateId и порядок callbacks/событий; идентификатор не преобразуется в строку.

Проверки: EntityLifecycleRegistryTest, EntityLifecycleInspectionTest, EventsAutoConfigurationTest и существующие контрактные owner-проверки.

Gate: beforeSave/beforeUpdate/beforeDelete, in-transaction callbacks, veto/rollback и afterCommit dispatch сохраняют прежнее поведение. Нейтральные модули не получают зависимость на BaseEntity.

### IG4. Адаптировать canonical CRUD и owned sections

Работы:

1. Задать Long-bound для существующих write/save/delete-потребителей: EntityDataAccess, CanonicalEntityDataAccess, CanonicalWriteExecutor, CanonicalEntityService, ValidatedJpaCrudService.
2. Аналогично описать Long-потребителей BaseService, EntityDataPolicy, EntityServiceResolver, ServiceLocator и EntityDataAccessResolver. Сохранить текущие имена, арность и назначения API.
3. Сохранить существующие Object-id read entry points. Их наличие не объявляется поддержкой произвольного типа ключа.
4. Проверить ResolvableType-выведение класса сущности через наследование, proxy и factory/default service; первый аргумент сервиса по-прежнему означает T.
5. Привести owned-section API и aggregate-save к явной параметризации. Root и row имеют собственные Long id; связь parent не выводится из generic-параметра.
6. В AggregateSaveRollbackState хранить и восстанавливать известную Long-идентичность. Проверить Hibernate proxy unwrap и восстановление состояния после отказа.
7. Универсальные helper-участки использовать с wildcard только при достаточности операций. Копирование/присваивание ненулевого id получает Long-bound либо доказанный общий capture.
8. Сохранить существующие Long-репозитории, permissions, RLS, FetchPlan, exposure checks, lifecycle и telemetry.

Проверки:

- EntityDataAccessResolverTest, ServiceLocatorCanonicalFallbackTest, CanonicalWritePathIT.
- ValidatedJpaCrudServiceGuardTest, EntityCopyServiceTest, тесты owned sections/aggregate save.
- SAVE/CREATE/UPDATE/DELETE: null/new id, существующий id, managed/detached, отказ и rollback.
- После rollback восстанавливаются исходный id, version и parent-связи; после copy новый id отсутствует.

Gate: canonical runtime сохраняет действующий Long-контракт; новые suppress/casts не скрывают mismatch типа ключа.

### IG5. Адаптировать формы, действия и навигацию

Работы:

1. Формы, coordinator, resolver, save adapters, table sections и customization API, вызывающие текущий CRUD, получают Long-bound.
2. Контекстные участки, которым достаточно читать идентичность, могут использовать `IdentifiableEntity<?>`. Это решение принимается для каждого участка с учётом вызываемого им сервиса.
3. Проверить ItemTable: обновление строки не должно присваивать Object id через unchecked cast. Поддерживаемые row-типы имеют явный Long-контракт.
4. Проверить ItemFormWrapperView callback-хранилище и Class-based factory boundaries. Существующие type erasure bridges остаются локальными и обоснованными.
5. В ItemFormLinkAffordance и аналогичных runtime-helper использовать wildcard и проверенную Long-границу. `instanceof IdentifiableEntity<Long>` недопустим в Java.
6. Проверить method references, Consumer/Function/Optional/collection-параметризации, action selection и explorer summary.
7. Сохранить действующий формат deep link, ключей навигации и команд редактирования.

Проверки: FormResolverTest и variant/customizer-проверки, ItemFormSaveFlowTest, ItemFormLifecycleTest, read-only/access binder, список/выбор/открытие/копирование, owned ItemTable и deep links.

Gate: прикладные формы не получают обязательный новый параметр ID там, где достаточно прежнего T с Long-bound; текущие Long-сценарии работают через стандартные сервисы.

### IG6. Адаптировать приложение, фикстуры и смежные потребители

Работы:

1. Обновить ItemFormSaveDispatcher, ReportPrintAction и непосредственную реализацию RefSnapshot.
2. Обновить прямые implementors и generic bounds в тестах приложения, core/events/vaadin и demo. Исторические path/assertion/Javadoc-упоминания отдельно отличить от деклараций типов.
3. Проверить все актуальные entity-классы/репозитории приложения: физические id продолжают быть Long.
4. Выполнить регрессию регистров: getLast, сохранение истории, неизменяемость бизнес-ключа, CTE/saved report и параметры даты.
5. Проверить REST read/JSON, lookup, фильтры ссылок, экземплярное имя, RLS/permissions и telemetry по действующим контрактам.
6. Сохранить проверку Long reference id в RegisterSliceSource. Общий generic-interface не снимает ограничения текущего report compiler.
7. Не создавать в FilterGrid зависимость на identity-api: найденное упоминание там описывает уже удалённую зависимость.

Gate: JSON, ссылки, отчётные параметры/пакеты и результаты выборок сохраняют существующее представление Long; regression-сценарии используют реальные owner JAR.

### IG7. Обновить API baseline и проверить согласованную поставку

Работы:

1. Проверить generic-сигнатуры изменённых публичных типов в platform-api-baseline. Особое внимание identity-api, crud-api, persistence, contracts, events, core и vaadin.
2. Сначала просмотреть ожидаемый diff API, затем обновить baseline только затронутых артефактов и снова запустить сравнение без write-режима.
3. Сохранить существующее исключение compiler-generated bridge/synthetic методов в PlatformApiBaselineTest. Не расширять исключения для сокрытия нового handwritten API.
4. Проверить ownership, public/SPI/internal роли, package matrix, module composition, dependency direction и starter closure.
5. Пересобрать необходимые owner-модули в топологическом порядке manifest. Установить только проверенные JAR и записать fingerprints проверенных исходников.
6. Проверить effective dependency tree/runtime classpath: identity-api одной согласованной версии, без старой копии интерфейса в других JAR.
7. Перекомпилировать application test sources полностью. Старый target и ранее установленный SNAPSHOT не считаются доказательством.
8. Один раз проверить общий согласованный набор после завершения всех исходных изменений.

Gate: согласованный комплект проходит архитектурные и runtime-проверки; протокол указывает source fingerprints, owner JAR, toolchain, команды и результаты.

### IG8. Зафиксировать завершение

Работы:

1. Обновить README контрактов и инструкцию для внешнего потребителя: `implements IdentifiableEntity<Long>`, нейтральные wildcard-участки, несовместимость старых binaries.
2. Обновить документацию текущего baseline и source fingerprint только по проверенному состоянию.
3. Зафиксировать, что generic identity реализована, а поддержка другого физического id и полная типизация CRUD по ID остаются самостоятельными работами.
4. Сохранить примеры корректной Long-параметризации и перечень оставшихся runtime bridges с владельцами.

Gate: внешнему потребителю достаточно обновить библиотечный комплект и декларации типов; отсутствуют schema/data migration и новая ручная регистрация сущностей.

## 6. Проверки и команды при реализации

Команды ниже — ориентиры запуска после изменения кода; при составлении этого документа они не выполнялись. Используется согласованный JDK 21 и Maven из действующего build-контракта.

### 6.1. Owner-проверки

```text
mvn -f ../crudui/pom.xml -pl platform-identity-api,platform-crud-api,crudui-core,crudui-demo -am clean verify
mvn -f platform-persistence/pom.xml clean verify
mvn -f platform-contracts/pom.xml clean verify
mvn -f platform-events/pom.xml clean verify
mvn -f platform-core/pom.xml clean verify
mvn -f platform-vaadin/pom.xml clean verify
```

Между зависимыми owner-проверками проверенные артефакты устанавливаются в выбранный local repository. Дополнительные промежуточные модули собираются по manifest: этот короткий список не заменяет его топологию.

Первый crudui-запуск включает demo, которого нет в обычном bootstrap buildSteps. Нулевое число текущих owner-тестов в identity-api/crud-api не заменяет новые Java consumer/компиляционные проверки.

### 6.2. Архитектурные gates

Обязательны PlatformApiBaselineTest, PlatformContractsModuleTest, PlatformPersistenceModuleTest, PlatformCoreModuleTest, PlatformVaadinModuleTest, PlatformPublicSurfaceTest, PlatformCoreSurfaceTest, PlatformVaadinSurfaceTest, PlatformPackageDependencyMatrixTest, PlatformDependencyDirectionTest, PlatformSubsystemClosureTest и starter composition.

Пример намеренного обновления одного reviewed baseline:

```text
mvn test -Dtest=PlatformApiBaselineTest -Dplatform.api.baseline.artifact=platform-identity-api -Dplatform.api.baseline.write=true
mvn test -Dtest=PlatformApiBaselineTest
```

Write-команда выполняется после review нового API. Она не является самостоятельным подтверждением корректности изменения.

### 6.3. Общая сборка и evidence

В завершение, после согласования fingerprints и owner-зависимостей:

```powershell
.\scripts\bootstrap-local-dependencies.ps1
```

Обычный bootstrap в нескольких buildSteps содержит -DskipTests; owner-проверки выше выполняются отдельно. Интеграционный шаг не подменяет их.

Evidence содержит:

- snapshot исходников всех затронутых owner-проектов и отдельную атрибуцию параллельных изменений;
- версии toolchain, hash проверенных JAR и дерево runtime-зависимостей;
- выбранные тесты и фактические результаты, включая исходные/промежуточные ошибки;
- JVM descriptors нового interface и bridge-методы Long-реализации;
- подтверждение Long в JPA/PK/FK, неизменности API-представления ключей и отсутствия нового schema drift;
- результаты сравнения API baseline, направления зависимостей и module/startup composition.

Для raw/unchecked использования вести baseline диагностики. Gate — отсутствие новых raw IdentifiableEntity в declarations/bounds и новых скрывающих type mismatch suppress. Простой grep не должен ошибочно запрещать imports, Javadoc и разрешённый IdentifiableEntity.class; проверка дополняется компиляцией или разбором Java syntax.

## 7. Критерии завершения

1. В единственном owner-артефакте опубликован `IdentifiableEntity<ID>` с getId()/setId(ID).
2. BaseEntity, непосредственные entity-реализации и текущие фикстуры используют Long.
3. Все активные identity-потребители параметризованы; runtime bridges локальны и проверены.
4. CRUD, lifecycle, aggregate save/rollback, copy, формы, ссылки и отчётные сценарии сохраняют поведение.
5. Изменения PK/FK, генератора, физических типов и существующих данных отсутствуют.
6. API baseline отражает только рассмотренные изменения; роли и направления зависимостей сохранены.
7. Собранный комплект не смешивает старые и новые identity binaries.
8. Owner- и интеграционные gates подтверждены воспроизводимым evidence.
9. Долг самостоятельного ID в BaseService обозначен явно; произвольный id во всём backend не объявляется поддержанным.

## 8. Связь с дальнейшим развитием

Generic identity, полиморфный регистратор и выделение platform/query — самостоятельные изменения. Для регистратора нескольких типов документов допустима действующая Long-идентичность; этот рефакторинг не является условием реализации регистратора.

Если данный срез берётся следующим, рационально закончить согласованную пересборку до исходных изменений Р7: так generic API и новая семантика LIST получают отдельные diff и проверки. Реализация CTE/Criteria-среза сама по себе не требует нового типа физического ключа.
