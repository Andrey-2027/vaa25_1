# Р3: подготовка допуска и видимости среза в report pipeline

- Дата: 2026-10-03.
- Статус: код и сценарии подготовлены без компиляции и запуска тестов; gate Р3 открыт.
- Контракт и ограничения: [проект ADR-0014](decisions/ADR-0014-report-read-authorization.md).

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

Owner-модули platform-* не менялись, их source fingerprints не обновлялись.
Счётчики D1 уточнены по добавленным файлам/SPI, автоматическая проверка отложена.
Промышленный descriptor и перенос аннотации регистра остаются задачей Р4.

## Подготовленные сценарии

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

Без запуска нельзя утверждать, что Hibernate применяет фильтры к обеим фазам, SQM walker
собирает все ожидаемые типы и результат соответствует assertions. Это подготовленные
регрессии и критерии будущей приёмки, не результаты измерений.

## Последствия для текущих отчётов

Новый контракт общего канала требует точных ENTITY/ATTR grants всех persistent-атрибутов
участвующего типа. Entity-valued результаты и типы с embedded/element-collection отклоняются.
Существующие шаблоны и тесты реального report wiring должны быть проверены на совместимость.
Наличие старого конструктора в структурном тесте не подтверждает C5 enforcement.

Р2 продолжает проверять выразимость через свой structural guard; Р3 проверяет отдельный
полный путь с C5 и RLS. Оба этапа остаются непроверенными. Общий канал нельзя считать принятым
по одному чтению исходников. Автоматически открывающие права миграции не создавались.

## Отложенный запуск

С настроенными JDK/Maven и актуальными локальными зависимостями:

```powershell
mvn -o -B -ntp '-Dtest=NomenclaturePriceCteSpikeTest,TwoDimensionRegisterCteSpikeTest,RegisterSliceReportSecurityTest,ReportStudioAutoConfigurationSmokeTest' test
```

Затем — существующие report query/run/preview consumers и применимые architecture gates
(public surface, package matrix, D1). Команда не запускалась; test-compile и сборка также
не выполнялись. При ошибках требуются исправления по фактической диагностике и повторная
приёмка затронутого контракта. PostgreSQL SQL/EXPLAIN — отдельная проверка.
