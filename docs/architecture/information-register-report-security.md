# Р3: приёмка допуска и видимости среза в report pipeline

Уточнение 2026-10-07: [ADR-0018](decisions/ADR-0018-no-field-permissions.md) отменяет
отдельные права на поля. Описанные ниже результаты приёмки относятся к прежней
модели; требования ATTR из неё больше не действуют. Текущая модель — ENTITY и RLS.

- Дата подготовки: 2026-10-03; приёмка: 2026-10-04.
- Статус: Р3 закрыт в ограниченном scalar-контракте; 15 security-сценариев прошли.
- Контракт и ограничения: [ADR-0014](decisions/ADR-0014-report-read-authorization.md).
- Команды, SHA-256 и результаты: [протокол закрытия](information-register-r2-r3-acceptance.md).

## Изменения

| Часть | Результат в исходниках |
| --- | --- |
| ReportReadAuthorizer | Общий evaluator C5, RLS admission всех SQM-типов, ограниченная scalar-проекция |
| ReportQueryGuard | В production wiring вызывает authorizer |
| ReportQueryExecutor | Повторный допуск итогового HQL перед SQL, затем реальный RLS activator |
| ReportQueryAssemblyService | Передаёт entityClass из ReportParam в допуск, загрузка класса без инициализации |
| ReportStudioAutoConfiguration | Обязательные provider/subject и полные защищённые конструкторы |
| ReportReadPolicy / RegisterReportReadPolicy | Предметная проверка постоянства видимости версий, без org.ipro → org.ip |
| Surface / matrix / D1 | Новый SPI и два направления зависимости на org.ipro.rls.c5 |

Production owner-код этого report-патча не менялся. Актуальный RLS owner прошёл 23 теста
и установлен локально после обнаружения отставшего JAR; обновлён только его fingerprint.
Счётчики D1, surface, API baseline, package matrix и starter composition проверены.
Перенос аннотации и общий descriptor выполнены следующим этапом
[Р4](information-register-r4-placement.md). Сценарии и ограничения Р3 сохраняются.

## Прошедшие сценарии

RegisterSliceReportSecurityTest создаёт отдельный Hibernate/H2 persistence unit с реальным
AccessGrantRepository (JpaRepositoryFactory), evaluator, AccessService, registry, cache и activator.
Транзакция открывается на том же EntityManager, которым читает executor.
Приёмочные чтения не открывают privileged bypass. SQL собирается StatementInspector.
Данные фикстур создаются напрямую до чтения, чтение проходит run → assembly → guard → executor.

1. Сущность регистра находится только внутри CTE; нет её ENTITY grant — отказ до SQL данных.
2. ENTITY grant есть, ATTR grant на price отсутствует — отказ до SQL данных.
3. C5 разрешён, но нет RLS grant — диагностический отказ, а не пустой результат.
4. Разрешена одна из двух групп измерений. Результат содержит последнюю цену только этой группы;
   проверяется реальный SQL с фильтром в обеих фазах CTE и count parity.
5. Та же сессия и cache последовательно читают под двумя пользователями и снова первым:
   фильтры другого субъекта не должны сохраняться.
6. RLS по id строки скрывает новую версию 90, оставляя старую 150. Policy отклоняется
   до SQL; цена 150 не должна выдаваться как последняя.
7. После подготовки HQL меняется subject: executor заново проверяет C5. `system` отклоняется.
8. Отсутствующий C5 provider вызывает ошибку конфигурации конструктора.
9. Проверяется C5 для неявного чтения номенклатуры по ссылке и entity-параметра.
10. Entity-valued результат отклоняется до внедрения безопасной attribute lineage.
11. Отзыв RLS grant инвалидирует cache/filter activation в той же сессии.
12. Узкий unit-сценарий policy отклоняет вложенное mutable-свойство ссылки, ресурс, период,
    технический id и custom rule. Остальные сценарии используют настоящий registry.

13. RLS действует только на цель измерения, регистр не имеет row filter: скрытая цель
    исключает группу, а видимая без истории сохраняется в LEFT JOIN с NULL-ценой.
14. Ограниченный FILTERABLE-доступ через FK/association JOIN требует независимого INNER
    entity join по фактическому id. LEFT/OR/постороннее ON не обходят эту проверку.
15. Настоящие Hibernate query options отключают query cache и обходят entity cache.

Реальный SQL и результат подтвердили фильтры обеих фаз и count parity. На H2 Hibernate
раскрывает CTE как derived tables. Обычный association JOIN цели не обеспечил её фильтр:
спайк использует независимый INNER entity join по id. Authorizer проверяет этот shape по SQM;
при полном RLS-доступе дополнительный join не требуется. Внешний cache provider не тестировался.

## Последствия для текущих отчётов

По решению пользователя 2026-10-07 ([ADR-0018](decisions/ADR-0018-no-field-permissions.md))
контракт требует ENTITY-read участвующих типов и RLS. Отдельные ATTR-grants не требуются;
запрет типа только из-за наличия embedded/element-collection удалён. Entity-valued
результаты по-прежнему отклоняются; scalar-проекция сохраняется.
Существующие шаблоны и тесты реального report wiring должны быть проверены на совместимость.
Наличие старого конструктора в структурном тесте не подтверждает C5 enforcement.

Р2 проверяет выразимость через structural guard; Р3 — полный путь с C5 и RLS.
Оба этапа прошли приёмку. HTTP preview получил положительный тест с точными правами
и отрицательный с preview/RLS без C5. Существующий Jasper export test переведён на полный
защищённый pipeline; PDF/XLSX/DOCX/CSV прошли. Короткие конструкторы deprecated,
ArchUnit запрещает их production-вызов в репозитории. Инвентаризация шаблонов конкретной
production-БД перед выпуском обязательна; автоматические разрешающие миграции не создавались.

## Запуск

С настроенными JDK/Maven и актуальными локальными зависимостями:

```powershell
mvn -o -B -ntp '-Dtest=NomenclaturePriceCteSpikeTest,TwoDimensionRegisterCteSpikeTest,RegisterSliceReportSecurityTest,ReportStudioAutoConfigurationSmokeTest' test
```

Итоговый расширенный набор — 322 теста без отказов, включая report query/run/preview,
public surface, package matrix, D1 и остальные Platform gates. Полная команда — в протоколе.
PostgreSQL SQL/EXPLAIN — отдельная проверка; legacy test adapters не являются защищённым API.
