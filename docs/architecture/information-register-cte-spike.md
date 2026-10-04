# Р2: спайк среза последних через существующий CTE-компилятор

- Дата: 2026-10-03.
- Статус: исполняемые сценарии подготовлены; компиляция и тесты не запускались по просьбе пользователя.
- Основание: Р2 в [плане развития](information-register-development-plan.md#6-р2--спайк-среза-на-существующем-компиляторе).
- Gate Р2 остаётся открытым до успешного выполнения. Наличие тестового кода не доказывает выразимость на Hibernate.

## Состав

В `src/test/java/org/ip/application/register/` добавлены:

| Файл | Назначение |
| --- | --- |
| `RegisterSliceCteSpike.java` | Ручная сборка обычного VisualQueryPackage и выполнение после настоящего SQM/guard |
| `NomenclaturePriceCteSpikeTest.java` | Четыре сценария с production mapping NomenclaturePrice в контексте приложения |
| `TwoDimensionRegisterCteSpikeTest.java` | Три сценария с отдельным Hibernate/H2 persistence unit и тестовым регистром |

Production-код, AST, semantic kind, размещение общих типов и compiler не менялись.
Тестовый helper не является production generator и не вводит SliceSpec.
У тестовых сущностей `SpikeItem` и `SpikePrice` есть реальные `@Entity`, `@Id`, metadata полей
и физический составной unique. Они зарегистрированы явно только в отдельном persistence unit.
Прикладной `@EntityScan("org.ip.model")` их не включает.

## Пакет

```text
mx:
  SELECT nomenclature.id AS nomenclatureId [, channel], MAX(period) AS lastPeriod
  FROM register p
  WHERE period <= :dateBinding AND dimensionConditions
  GROUP BY nomenclature.id [, channel]

latest:
  SELECT nomenclature.id AS nomenclatureId [, channel], period, price
  FROM register p JOIN mx m
    ON p.nomenclature.id = m.nomenclatureId
       [AND p.channel = m.channel]
       AND p.period = m.lastPeriod
  WHERE resourceConditions

main (срез):
  SELECT nomenclatureId [, channel], period, price FROM latest

main (отчёт):
  SELECT n.id, latest.period, latest.price
  FROM Nomenclature n LEFT JOIN latest ON n.id = latest.nomenclatureId
  WHERE fixtureScope [AND consumerConditions]
```

Дата передаётся helper-у один раз на пакет. В существующей модели WHERE она хранится
как каноническая ISO-строка и декодируется через типизированный metadata catalog в LocalDate.
Компилятор присваивает binding имя `visualFilter_*`; тест проверяет ровно одно значение LocalDate
в bindings и его равенство переданной дате. Это параметризованный запрос, дата не вставляется в HQL.
Сохранённый пакет в этом спайке содержит выбранную дату; механизм подстановки именованного
runtime `asOf` в сохранённый шаблон здесь не вводится и остаётся задачей Р5–Р6.

Одна дата действует на всю выборку. Корреляция «дата каждого документа» не реализуется.

## Подготовленные проверки

1. Одномерный срез: четыре номенклатуры, одна без истории, воспроизводимая случайная история
   трёх остальных. Для 24 дат сравниваются период и цена с `TreeMap.floorEntry`, а точечный
   `priceOn` — с тем же независимым оракулом. Проверяются даты до истории, точное совпадение,
   промежутки и дата после последней записи. LEFT JOIN сохраняет все четыре номенклатуры.
2. История 150 → 90 с `price > 100` даёт пустой срез для этой номенклатуры.
   Отбор применяется после MAX и не возвращает старую цену 150. Второй товар с последней
   ценой 120 остаётся. Внутренний отбор среза сохраняет пустые строки основного LEFT JOIN;
   внешний WHERE по цене их исключает.
3. Codec round-trip: сохранение/чтение пакета, повторная компиляция, равенство HQL,
   bindings и schema, повторное выполнение; цена 0 остаётся нулём.
4. Настоящий SQM/guard видит сущность регистра, используемую внутри CTE, и отклоняет
   запрос с необъявленным параметром даты.
5. Двухмерный регистр: ссылочное измерение nomenclature и строковое channel.
   Для трёх товаров и двух каналов генерируется история с фиксированным seed.
   На 18 дат проверяются все ключи, независимый максимум периода, цена, отсутствие
   лишних строк и дублей. Соединение включает оба измерения и период.
6. Отбор channel применяется до MAX; отбор ресурса — после MAX. История одного канала
   не подменяется историей другого.
7. Codec сохраняет составной ON и тип даты двухмерного пакета; после восстановления
   должны вернуться оба канала.

В каждом исполняемом сценарии используются настоящий QueryBuilderMetadataCatalog,
SqmQuerySemanticAnalyzer и ReportQueryGuard. Guard не замокан. Проверяются типы колонок
period (LocalDate) и price (BigDecimal); HQL выполняется через EntityManager только после guard.
Fixtures создаются напрямую для изоляции данных, это не изменение прикладного write path.

## Границы проверки доступа

Основной тест использует реальные прикладные AccessService/RlsReadGate и гранты тестового
пользователя на участвующие типы. Изолированный регистр не имеет RLS-измерений;
его AccessService не обращается к хранилищу грантов. Исполнение через EntityManager предназначено
для проверки выразимости, типов и результата HQL, не является новым production read API.

Спайк не подтверждает C5-политику report path, применение FILTERABLE RLS к обеим фазам CTE
или согласованную видимость версий. Эти отрицательные сценарии и исполнение через полный
report pipeline относятся к Р3. PostgreSQL, EXPLAIN и нагрузочные измерения не выполнялись.

## Отложенный запуск и решение

Из корня проекта, с настроенными JDK/Maven и актуальными локальными зависимостями:

```powershell
mvn -o -B -ntp '-Dtest=NomenclaturePriceCteSpikeTest,TwoDimensionRegisterCteSpikeTest' test
```

Команда приведена для будущего запуска и не выполнялась при подготовке Р2.
Не выполнялись также test-compile и общий регрессионный прогон.
В этой итерации выявленных выполнением дефектов compiler нет; исправления compiler не внесены.
После запуска необходимо зафиксировать фактические результаты и, при необходимости,
минимальную исправляющую регрессию. Только затем можно закрыть gate выразимости Р2.
