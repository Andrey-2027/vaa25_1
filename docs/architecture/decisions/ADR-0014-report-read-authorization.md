# ADR-0014: C5 и видимость регистров в report read

- Статус: принято для ограниченного scalar report-контракта; приёмка Р3 выполнена.
- Дата: 2026-10-04 (подготовка 2026-10-03).
- Основание: Р3 [плана регистров](../information-register-development-plan.md), ADR-0007 и ADR-0013.

## Проблема

ReportQueryGuard проверял семантику, параметры и RLS entity admission. ReportQueryExecutor
активировал row filters, но общий C5PermissionEvaluator в этом пути не вызывался.
Кроме того, RLS по id версии регистра мог скрыть свежую запись, оставив старую в MAX(period).
Разрешение такого результата изменило бы смысл среза последних.

## Решение

ReportReadAuthorizer использует общий C5PermissionEvaluator, AccessService, RlsCurrentUser
и Hibernate SQM. Он не копирует алгоритм вычисления грантов.
Обход BaseSemanticQueryWalker собирает типы корней, CTE, явных/неявных связей, выражений,
ON и подзапросов. Entity-параметры учитываются по SQM и объявленным типам ReportParam.

Production factory ReportStudioAutoConfiguration требует evaluator и subject provider;
отсутствующий бин — ошибка конфигурации. `system`/пустой subject не разрешает чтение.
ReportQueryGuard применяет authorizer при подготовке, executor повторяет проверку итогового HQL
непосредственно перед активацией RLS и выполнением. Проверяется также RLS admission всех
собранных SQM-типов, а не только сущностей из прежнего частичного анализа.
Добавленный visual filter, ORDER BY и смена пользователя после сборки не должны обходить допуск.

Старые конструкторы guard/executor сохранены для структурных тестов и совместимости.
Они не являются новым защищённым execution API. Production factory использует полные
конструкторы; короткие адаптеры deprecated, ArchUnit запрещает production-вызовы в репозитории.
Заменяющий executor обязан сохранять этот контракт. Это переходный долг до
выделения report add-on, а не разрешённый прикладной обход.

## Ограниченная опубликованная проекция

**Уточнение 2026-10-07:** требования ATTR-read и attribute lineage для авторизации
заменены [ADR-0018](ADR-0018-no-field-permissions.md). Текущий report gate требует
ENTITY-read участвующих типов и RLS; отдельных прав на поля нет. Текст ниже
фиксирует первоначальный контракт и не является действующим требованием ATTR.

До полноценной attribute lineage канал допускает только scalar-проекцию и требует точные
ENTITY-read и ATTR-read для **всех persistent-атрибутов каждого участвующего типа**, включая
id/version, даже если часть атрибутов не выбрана. Это намеренно более строгий контракт,
чем проверка только колонок верхнего SELECT: ресурсы могут участвовать в CTE, WHERE и MAX.

Entity-valued результат не допускается: нормализация caption могла бы прочитать дополнительные
ссылки. Типы с embedded/element-collection также отклоняются до поддержки lineage вложенных
атрибутов. Доступ по wildcard RLS не заменяет точные C5 permission keys.
Автоматическое создание разрешающих ATTR-грантов не добавлено.

Это изменение действует на общий report channel, в том числе preview/JpqlRunService.
Существующие отчёты могут потребовать новых грантов или scalar-проекции. Перед выпуском
нужна проверка consumers и согласованная настройка прав. Известные source consumers прошли
приёмку; шаблоны конкретной production-БД перед её выпуском требуют собственной инвентаризации.

При ограниченном FILTERABLE-доступе цели association JOIN/FK не гарантирует row filter.
Требуется независимый INNER entity join по фактическому id в том же источнике SQM;
LEFT/OR/постороннее ON не подтверждают видимость. Полный RLS-доступ не требует этого join.
Report query явно отключает result query cache и обходит entity cache; Hibernate query options проверены.

## Постоянная видимость версий

ReportReadPolicy — SPI дополнительных ограничений источника. С Р4 реализация
`org.ipro.reportstudio.query.register.RegisterReportReadPolicy` использует общий
`RegisterRegistry` / `RegisterDescriptor`. Она регистрируется обязательным бином reportstudio;
общий пакет регистра не импортирует reportstudio или предметные model-пакеты.

Для FILTERABLE политики разрешены стандартные valuePaths:

- неизменяемое числовое BASIC-измерение;
- одношаговая to-one ссылка-измерение плюс фактический единственный JPA id целевого типа.

Проверка использует JPA metamodel, а не строковый префикс пути. Период, ресурс, технический
id строки, произвольные вложенные mutable-пути, composite id и custom policy не поддерживаются.
Отказ содержит `REGISTER_SLICE_VISIBILITY`, тип, измерение RLS и пути.
CHECK_ONLY не фильтрует отдельные версии и проверяется как допуск типа.
Ограничение пока применяется ко всем report-запросам участвующего регистра, не только к CTE.
Р4 добавляет descriptor без расширения набора допустимых инвариантных значений.
Для расширения этого набора нужны отдельные правила и отрицательные security-тесты.

## Проверка и оставшиеся gates

Прошли 15 сценариев в RegisterSliceReportSecurityTest с настоящими грантами,
DefaultC5PermissionEvaluator, RLS activator/cache, SQM, assembly/executor и ReportExecutionService.
Renderer только записывает полученный dataset; security и persistence не замоканы.
Один отдельный unit-сценарий подменяет descriptor registry для проверки отказов policy.

Р2/Р3, архитектурные gates, существующие source report consumers и wiring прошли приёмку:
322 теста без failures/errors/skipped. Отдельно принят RLS owner и независимый starter closure.
PostgreSQL, внешние cache providers и нагрузочные измерения не выполнялись. Протокол:
[Р2–Р3 — закрытие](../information-register-r2-r3-acceptance.md).
