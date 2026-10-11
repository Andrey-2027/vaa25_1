# Г5: production CTE и canonical slice полного порядка победителя

Дата: 2026-10-05. Статус: функциональная приёмка Г5 закрыта на Hibernate/H2.

## Цель

Проверить, можно ли существующим `VisualQueryPackage` и `VisualQueryCompiler` выбрать
реальную строку по полному порядку подчинённого периодического регистра:

```text
max (period, recording_order, line_no)
```

при `period <= asOf`, не полагаясь на оконные функции и не соединяя документ-регистратор.

## Реализованный запрос

Исполняемый тест `RecorderWinnerCteSpikeTest` строит три последовательных сгруппированных CTE:

1. `periodMax`: `MAX(period)` по измерениям после включительного фильтра `period <= :asOf`.
2. `orderMax`: среди строк на найденном периоде — `MAX(recording_order)` по тем же измерениям.
3. `lineMax`: среди строк с найденными периодом и порядком — `MAX(line_no)`.
4. Итоговый запрос соединяет исходную сущность со всеми тремя результатами и возвращает
   поля одной строки, в том числе identity регистратора и ресурс.

Каждый последующий максимум вычисляется только среди кандидатов, прошедших предыдущие
компоненты порядка. Поэтому максимум отдельного столбца не может быть взят с другой строки.

## Проверенные случаи

- Более старый период с большим `recording_order` проигрывает более новому периоду.
- При равном периоде выигрывает больший `recording_order`.
- Строки одной замены с одинаковым порядком разрешаются большим `line_no`.
- Два типа регистратора с одинаковым числовым id остаются различимыми; в результат попадает
  тип, соответствующий строке-победителю.
- Строка позже `asOf` исключается, а другая группа измерений получает собственного победителя.
- Запрос возвращает ровно одну фактическую строку на каждую группу.

## Результат и границы

Спайк `RecorderWinnerCteSpikeTest` подтвердил алгоритм, а `RegisterSliceCteGenerator` теперь
строит эту же последовательность для подчинённого периодического регистра. Победитель берётся
из реальной исходной строки, а не из независимо вычисленных максимумов полей. Generator читает
порядок из `RegisterDescriptor.winnerOrderFields()` и пока принимает ровно
`period → recordingOrder → lineNumber`.

Production test использует настоящий JPA `@Embedded RecorderRef`, `recorder.typeCode/id`,
`recordingOrder` и `lineNumber`. Source resolver сверяет embedded mapping и разрешённые поля
регистра. JPQL выбирает скалярные поля регистратора и не содержит сущностей документов или JOIN
к ним. Строки в H2 fixture загружаются native insert только для подготовки чтения; production
writer и его guard не менялись. Эти технические поля доступны как проекция под read gate регистра;
generic filter resolver не предлагает их как обычные WHERE-поля. Вместо этого `SliceSpec` задаёт
явное `ALL_RECORDERS` либо `SELECTED_RECORDERS` с точными парами `(typeCode, id)`. Выбранные
пары сохраняются в typed metadata каждой CTE visual package v2 и применяются к исходным строкам
до вычисления period/order/line максимумов и при соединении итогового победителя. Компилятор
проверяет наличие технических полей и разрешённость каждого type code по декларации регистра;
поддельная selection с незарегистрированным типом отклоняется. Редактор пакета сохраняет metadata
при повторном открытии и сборке CTE-этапов.
Точечный `getLast()` и CTE вернули одну и ту же строку на fixture с одинаковым числовым id
у двух типов документа, равным периодом, двумя строками одной записи и более старой строкой
с большим `recordingOrder`.

Дополнительно исполнены срезы только по каждому из двух типов с одинаковым recorder id: тип A
возвращает собственный ресурс, несмотря на больший `recordingOrder` типа B, а тип B выбирает
свою строку по `recordingOrder` и `lineNumber`. Та же selection переживает JSON round-trip.
Формат package v1 без selection продолжает читаться; v1 не принимает новую selection.

## C5/RLS подчинённого среза

`RegisterSliceReportSecurityTest.recordedSliceKeepsGroupVisibilityAndWinnerIndependentOfRecorderDocumentC5`
запускает production CTE через JSON visual package, `ReportExecutionService`, C5 guard и реальный
Hibernate filter. В fixture два зарегистрированных типа документа используют один numeric id и
одинаковый период; больший `recording_order` выбирает документ B. У пользователя есть права C5 на
сущность и все нужные поля регистра, кроме embedded `recorder`; запрос сначала отказывает на
отсутствующем ATTR-read именно регистра. После выдачи этого права на поле регистра запрос проходит.
У пользователя нет C5 прав на оба типа документов-регистраторов. Результат содержит одну разрешённую
группу `itemId=1`, победителя `(r3-recorder-b, 501)` и resource `200`; группа `itemId=2` не видна.
Сгенерированный JPQL не упоминает document entities, а `item_id in (...)` присутствует на каждой из
четырёх ссылок к таблице регистра в CTE фазах и итоговом чтении.

Для этого `ReportReadAuthorizer` допускает ровно `RecorderRef` как embedded-атрибут сущности,
реализующей `RecordedRegisterEntry` с `@RegisterMetadata` и непустым `recorderTypes`; обычный
ATTR-read этого поля на самом регистре всё ещё обязателен.
Это не даёт доступ к документу и не меняет C5 проверки остальных embedded/collection полей.
Fixture строки подготовлены native insert, чтобы не обходить тестом production recorder-set writer.

Тем самым C5/RLS видимость группы и winner для подчинённого report CTE подтверждены на
Hibernate 7.2.12 / H2 2.4.240. Проверка выполняет visual JSON-пакет через in-memory `ReportTemplate`,
но не проверяет хранение/повторное открытие saved report или параметризованный recorder-срез.

## Canonical paged slice и независимая oracle

Добавлен `RegisterSliceReader`: он строит correlated Criteria `NOT EXISTS` по descriptor и
`SliceSpec`, затем передаёт `Specification` в `EntityDataAccess.list`. Canonical read executor
исполняет тот же winner predicate для content и count и сохраняет C5/RLS, FetchPlan, exposure и
telemetry gates. Максимум выбирается внутри dimensions, `RecorderReadIntent` сужает множество
кандидатов до максимума, а `afterSlice` применяется к победителю. Сортировка принимает только
dimensions/resources/period и дополняет порядок persistent id измерений и строки; unpaged и страницы
больше 500 отклоняются до чтения.

`RegisterSliceCteGeneratorTest` исполнен повторно: **19 tests, 0 failures/errors/skipped**. В нём
real Hibernate/H2 canonical read path сверяет content/count, CTE, point `getLast()` и независимый
Java comparator по `(period, recording_order, line_no)`. Fixtures покрывают два document types с
одинаковым numeric id, равные периоды и line tie, inclusive nanosecond boundary, future rows,
selected recorder, reference-dimension identity, все шесть scalar operators и post-filter `150 → 90`
(побеждает 90, старые 150 не возвращаются). После этого fixture recorder set заменяется на строку
с повышенным `recording_order`, и все три query path снова сверяются с oracle; атомарность реального
writer и повышение clock уже проверены тестами Г3. Для DAY/MONTH/YEAR три reader'а сверяются на
нормализованных timestamp values; фактическая запись через listener остаётся проверкой
`RegisterPeriodicityTest`.

Paging дополнительно проверяет equal-sort tie-break по dimensions/id, повтор первой страницы,
последнюю и пустую страницу, запрет unpaged/слишком большой страницы/технической сортировки,
after-slice по period и detached scalar чтение. Reference dimension сравнивается по persistent id.

Отдельный canonical test подключает `CanonicalReadExecutor` с разрешённым C5 entity-read и активирует
реальный Hibernate RLS filter по измерению: скрытая группа отсутствует и в content, и в
`totalElements`. Результат дополнен report C5/RLS сценарием выше, где отдельно проверяется ATTR-read
embedded recorder-поля и отсутствие прав на документы.

Таким образом, Г5 закрывает query semantics на H2. Saved/reopened parameterized report остаётся
приёмкой Г6. Проверка JPQL/HQL, schema, explain и стоимости на выбранном production backend остаётся
приёмкой Г7 после того, как проект зафиксирует backend-профиль; PostgreSQL здесь не объявляется
единственной целевой СУБД.

## Запуск

Ранее проверены `RegisterSliceCteGeneratorTest` (14), `RecorderWinnerCteSpikeTest` (1),
`VisualQueryPackageJsonTest` (4), `QueryConstructorDraftPackageTest` (3),
`VisualQueryPackageCompilerTest` (2), `VisualQueryFilterResolverTest` (2) и
`VisualQueryCompilerSecurityTest` (2): всего 28, 0 failures/errors/skipped.
В том числе production CTE execution, точечный reader, exact recorder selection, compiler allow-list,
версия visual package, редакторская сохранность selection и JSON round-trip. Также прошёл
`RegisterSliceReportSecurityTest` (18: 0 failures/errors/skipped), включая recorded-register
C5/RLS сценарий и прежние независимые slice/report проверки. Последний целевой прогон после
добавления canonical reader и group/count RLS теста: `RegisterSliceCteGeneratorTest` (19:
0 failures/errors/skipped).

Использовались Maven 3.9.9 и JDK 21. Для запуска в текущей рабочей копии применён временный
POM без зависимости `platform-rest-web`: установленный в локальном Maven cache BOM не содержит
её management entry. В запуске тестового класса компилятор ограничен этим файлом, поскольку другие
тесты рабочего дерева уже ссылаются на исходники ещё не включённого в root classpath модуля
`platform-rest-web`. Корневой `pom.xml` не менялся. Перед компиляцией корневого приложения локальные
SNAPSHOT `platform-core` и `platform-rls` обновлены из их текущих workspace исходников, так как
приложение использует добавленные в Г4 API этих модулей.
При forked javac в выводе есть внутренний `AccessDeniedException` при закрытии локального
`commons-codec` JAR; обновлённые class-файлы записаны, целевой класс исполнился и Maven завершил
сборку с `BUILD SUCCESS`.
