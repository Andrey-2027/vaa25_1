# Р5: SliceSpec и генератор CTE

> Уточнение 2026-10-05: текущая реализация использует LocalDateTime и календарную периодичность.
> Временной контракт и переход схемы: [период и периодичность](information-register-periodicity.md).
> Ниже сохранено описание соответствующего этапа до этого уточнения.

- Дата: 2026-10-04.
- Статус: Р5 закрыт; следующий этап — Р6, приёмка пользовательского отчёта и измерения.
- Основание: закрытые [Р2–Р3](information-register-r2-r3-acceptance.md) и [Р4](information-register-r4-placement.md).

## Контракт

`org.ipro.register.SliceSpec` — immutable read intent, независимый от reportstudio и UI:
managed Java-тип регистра, `LATEST`, обязательная `LocalDate asOf`, условия измерений и
отдельные условия после среза. Условия в каждой фазе соединяются AND.
Вход — имя объявленного поля, закрытый enum оператора и типизированное immutable scalar-значение.
HQL/SQL, dotted paths, `Class.forName`, entity instances и mutable значения не принимаются.
Ссылочное измерение задаётся persistent id целевой сущности.

`SliceSpecValidator` проверяет соответствие descriptor/intent, фазы и типы.
Измерения нельзя подставлять в отбор после среза; период и ресурсы нельзя использовать
в отборе измерений. Тип значения совпадает с Java-типом поля (primitive сравнивается с wrapper);
автоматического числового сужения или преобразования строки в id нет.
EQ/NE доступны для поддерживаемых scalar-типов; GT/GE/LT/LE — для чисел и LocalDate.
Ссылочные id поддерживают только EQ/NE. NULL, OR, IN, BETWEEN, LIKE и runtime parameter refs
в первом intent не представлены. Пустые строки и строки с compiler-маркерами `:` / `@subquery:`
явно отклоняются. Обычный текст, включая кавычки и фрагменты SQL, остаётся bind-значением.

Scalar-типы: String, Long, Integer, Short, Double/Float (конечные числа), BigDecimal,
Boolean, LocalDate, UUID и enum. Коллекции, embedded и ресурсы-ссылки отклоняются до генерации.
Составные id и другие неподдерживаемые mapping не угадываются.

## Подготовка и генерация

`RegisterSliceSource.resolve` сверяет managed registry, реальный JPA metamodel и каталог
разрешённых report-полей. Snapshot содержит descriptor, scalar-типы, имена целевых сущностей
и проверенные id; объектов JPA и каталога в snapshot нет. Подготовка отделена от генерации.
Каталог использует действующий read gate; актуальные C5/RLS и допуск проекции проверяются
обычным report pipeline при выполнении. Snapshot не выдаёт прав и не заменяет authorizer.

`RegisterSliceCteGenerator.generate(source, spec, names)` — чистая функция без БД,
создающая две обычные CTE:

1. Максимум периода при `period <= asOf`, с отбором измерений и GROUP BY полного ключа.
2. Соединение по всем измерениям и максимуму периода; отбор ресурсов/периода применяется здесь.

Для каждого ссылочного измерения независимый INNER entity JOIN по фактическому id включён
в обе фазы. Это сохраняет видимость цели, принятую в Р3. Две ссылки на один целевой тип
получают разные alias/ON. Технический id строки не участвует в выборе победителя;
равные периоды исключает бизнес-уникальность регистра.

Результат `Fragment` содержит immutable CTE, схему scalar-колонок, map «поле → колонка»,
имя latest-источника и следующий namespace context. `asPackage()` строит простой consumer
всех колонок. `appendTo(package)` добавляет CTE после существующих этапов, сохраняя main.
Consumer соединяется с latest как с виртуальной таблицей; GROUP BY/MAX и внутренний
составной ON писать не нужно.

`NameContext.from(package)` учитывает чужие имена; генератор выделяет детерминированный
namespace без коллизий, включая сравнение без учёта регистра. Managed entity names резервируются.
Следующий срез получает `fragment.nextContext()`. Поздняя коллизия CTE и затенение managed
entity отклоняются при append без частичного изменения исходного пакета.

Bindings создаёт существующий общий `ProjectionFilterCompiler.ParameterContext` при единственной
компиляции всего итогового пакета. `fragment.compile(catalog)` возвращает обычный
`ReportQueryAssembler` с реальными typed bindings и колонками. Отдельно компилировать фрагменты
и склеивать HQL/bindings нельзя: счётчик параметров относится ко всему пакету.
Пользовательский named-параметр `visualFilter_N` отклоняется: это namespace compiler.
Два среза на разных датах получают разные bindings.

```java
var source = RegisterSliceSource.resolve(type, registers, emf.getMetamodel(), catalog);
var spec = SliceSpec.latest(type, asOf);
var fragment = generator.generate(source, spec, RegisterSliceCteGenerator.NameContext.from(existingPackage));
// Consumer SELECT/JOIN использует fragment.latestName() и fragment.columnNames().
var complete = fragment.appendTo(consumerPackage);
var compiled = VisualQueryCompiler.compile(complete, catalog);
// Выполнение — существующий защищённый assembly/guard/executor.
```

## Узкие исправления compiler

Композиция с чужим CTE показала, что compiler записывал проекцию реального корня в
`virtualEntities` под именем сущности. Следующий этап терял исходные поля и ассоциации.
Теперь virtual metadata публикуется только под объявленным именем CTE в `compile(package)`.
Отдельный `VisualQueryPackageCompilerTest` проверяет повторение реального источника.

Независимые JOIN одного entity с разными alias/ON разрешены. Уникальность alias и запрет
дублирования association path сохраняются. Отдельный compiler JOIN-тест и исполняемый
регистр с двумя ссылками подтверждают поведение.

`VisualQueryFilterResolver` согласован с прежним SELECT/ON-контрактом технического Long id:
на реальных разрешённых alias id доступен для identity-отбора, на virtual CTE id не создаётся.
Из-за этого существующего контракта Р5 поддерживает ссылки только на одиночный BASIC
Long id с именем `id`, проверенный в metamodel. Иные id требуют расширения каталога/compiler;
молчаливого сведения к Long нет. Compiler и security SPI не зависят от SliceSpec.

## Границы

`getLast` сохраняет descriptor, полный ключ, включительную дату и canonical `EntityDataAccess`.
Паритет generator/point/oracle проверяется на production mapping цен и тех же историях.
Общий SliceSpec пригоден для отдельного Criteria executor; зависимости register → reportstudio нет.
Генератор зарегистрирован бином reportstudio; модели и `EntityKind` не меняются.

Сохраняется раскрытый visual package с фиксированной датой. Versioned сохранение intent,
runtime date binding через report resolver, полноценный пользовательский отчёт и PostgreSQL
EXPLAIN/стоимость остаются Р6 и последующими gates. Р5 не закрывает всю v1.1.

## Приёмка

Проверены 360 различных сценариев: широкий набор завершился с одним отказом
`PlatformCoreSurfaceTest`, остальные 359 сценариев прошли. После точечного исправления
test-usage повторены все 6 тестов этого gate: 0 failures/errors/skips, BUILD SUCCESS.
Полный набор повторно не запускался. [Сводка обоих запусков](evidence/register-r5-tests-20261004.md).

Исполняемые проверки включают независимый oracle для двух измерений и нескольких ресурсов,
паритет с production point reader, включительную дату, отсутствие истории и ресурс 0,
отбор 150 → 90, два среза с чужим CTE, LEFT JOIN, codec round-trip, typed bindings,
две ссылки на один entity, C5/RLS и отказ до SQL. Регрессии compiler, автоконфигурация,
package matrix, API/SPI surface и subsystem closure также проверены.

Для компиляции текущего дерева потребовался отсутствовавший import `RestDetailResult`
в параллельно изменённом `RestReadPipelinePilotIT`. Единственный отказ широкого набора
относился к его новой fixture: создание `GroupNom` через canonical write с нормализацией
версии. В `platform-core-surface.txt` добавлен только этот test-usage `CanonicalWriteExecutor`;
роль MODULE_API и production API не расширены.

Package matrix и публичные роли остались прежними. Измерение D1 platform-tree: 144 типа;
reportstudio reach-back: 6 нейтральных типов регистра вместо 4 после Р4. Это по-прежнему
явный долг выделения backend owner/port, а не разрешение зависимости на приложение.
Owner-модули, API signatures и dependency fingerprints в Р5 не менялись.

Проверялось текущее рабочее дерево, включая параллельные изменения, при HEAD
`70b036c9f8ca1facb8dd55876c0a6406f0a4fc29`.
[Source inventory](evidence/register-r5-sources-20261004.sha256): 814 файлов
(`pom.xml`, `src/**`, `platform-rls/pom.xml`, `platform-rls/src/**`), SHA-256 manifest:
`35c29bde543c48029679652922841594be55cf1fc29fb2be42dc02d363925a0c`.
Инвентарь фиксирует состояние проверки, не означает приёмку всех параллельных задач.

Среда: Maven 3.9.9, AxiomJDK 21.0.11, Boot 4.0.6, Hibernate 7.2.12.Final, H2 2.4.240.
Исходный POM сохранён; изолированный POM, selector и логи находятся в
`.local-maven-repository/register-r5/`, вывод сборки — в его `target/`.
SHA-256 локального `platform-rls-1.0-SNAPSHOT.jar`:
`80ef8186801439b85a50b6cb2e53dbbbe7d3a01855299be8ae1ceffdf48d60b0`.
Starter composition читает основной `target/application-runtime-dependency-tree.json`;
его содержимое совпадает с изолированным runtime tree, SHA-256 обоих:
`fd82a2c5fc67e73990b732dbd442adab16998ce971e13a19672fbab60ad591a5`.
PostgreSQL-производительность и runtime date resolver этим запуском не подтверждены.
