# ADR-0014: C5 и видимость регистров в report read

- Статус: проект решения; код подготовлен, компиляция и приёмка отложены по просьбе пользователя.
- Дата: 2026-10-03.
- Основание: Р3 [плана регистров](../information-register-development-plan.md), ADR-0007 и ADR-0013.

## Проблема

ReportQueryGuard проверял семантику, параметры и RLS entity admission. ReportQueryExecutor
активировал row filters, но общий C5PermissionEvaluator в этом пути не вызывался.
Кроме того, RLS по id версии регистра мог скрыть свежую запись, оставив старую в MAX(period).
Разрешение такого результата изменило бы смысл среза последних.

## Подготовленное решение

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
конструкторы; заменяющий executor обязан сохранять этот контракт. Это переходный долг до
выделения report add-on, а не разрешённый прикладной обход.

## Ограниченная опубликованная проекция

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
нужна проверка текущих consumers и согласованная настройка прав. Непроверенный код в рабочем
дереве не является подтверждением готовности общего канала к выпуску.

## Постоянная видимость версий

ReportReadPolicy — SPI дополнительных ограничений источника. Прикладной
RegisterReportReadPolicy реализует его для @RegisterMetadata, поэтому reportstudio
не импортирует прикладные register/model пакеты.

Для FILTERABLE политики разрешены стандартные valuePaths:

- неизменяемое числовое BASIC-измерение;
- одношаговая to-one ссылка-измерение плюс фактический единственный JPA id целевого типа.

Проверка использует JPA metamodel, а не строковый префикс пути. Период, ресурс, технический
id строки, произвольные вложенные mutable-пути, composite id и custom policy не поддерживаются.
Отказ содержит `REGISTER_SLICE_VISIBILITY`, тип, измерение RLS и пути.
CHECK_ONLY не фильтрует отдельные версии и проверяется как допуск типа.
Ограничение пока применяется ко всем report-запросам участвующего регистра, не только к CTE.
Расширение набора инвариантных значений требует явного контракта descriptor в Р4.

## Проверка и оставшиеся gates

Подготовлены 12 сценариев в RegisterSliceReportSecurityTest с настоящими грантами,
DefaultC5PermissionEvaluator, RLS activator/cache, SQM, assembly/executor и ReportExecutionService.
Renderer только записывает полученный dataset; security и persistence не замоканы.
Один отдельный unit-сценарий подменяет descriptor registry для проверки отказов policy.

Запуск отсутствует. Р2/Р3, архитектурные gates, existing report consumers и wiring требуют
приёмки. PostgreSQL и нагрузочные измерения не выполнялись. Протокол:
[Р3 — подготовка](../information-register-report-security.md).
