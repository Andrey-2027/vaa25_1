# Р4: общая инфраструктура регистра

- Дата: 2026-10-04.
- Статус: Р4 закрыт; 346 тестов, 0 failures/errors/skips.
- Основание: закрытые [Р2–Р3](information-register-r2-r3-acceptance.md).
- Размещение: корневой артефакт, пакет `org.ipro.register`; отдельного `platform-register` нет.

## Реализация

Общие `RegisterMetadata`, `RegisterMetadataValidator`, `RegisterReader` и startup validator
перенесены из `org.ip.register`. `NomenclaturePrice`, lifecycle и `NomenclaturePriceService`
остаются предметным кодом `org.ip`. Сущность остаётся `PLAIN`; F-ERP-3 не снимается.

`RegisterDescriptor` — неизменяемый снимок проверенной декларации: managed Java-тип,
объявленное JPA entity name, упорядоченные измерения, optional период, ресурсы и физические
имена колонок ключа. Поля описаны именем, Java-типом, optional явным именем колонки и видом
mapping (`BASIC`, `TO_ONE`, `OTHER`). Неявные ресурсные колонки не угадываются; `OTHER`
не объявляет поддержку таких отображений будущим генератором. Descriptor не содержит
`Field`, persistence context или report-типы и не подтверждает физическую схему БД.

`RegisterRegistry` получает классы из существующего `ManagedEntityCatalog`, без второго
classpath/metamodel scan. Декларации проверяются в стабильном порядке; все ошибки собираются
до публикации snapshot. Snapshot публикуется атомарно и не допускает изменения коллекций.
`require` отклоняет обычный или отсутствующий в текущем persistence unit тип. Конфигурация
`RegisterAutoConfiguration` зарегистрирована в Boot imports; startup проверяет даже регистр,
для которого никто не создаёт reader. Reader прикладного сервиса использует этот registry.

Самостоятельный двухаргументный конструктор reader сохранён: он проверяет декларацию без
проверки managed membership. `getLast` сохраняет полный набор измерений, включительную дату,
пустой результат при отсутствии истории и чтение через `EntityDataAccess` с C5/RLS.
Используется прежний paged read с отдельным count; bounded/batch API относятся к Р9.

`RegisterReportReadPolicy` перенесён рядом с reportstudio в `query.register`, получает общий
registry и зарегистрирован обязательным бином `ReportStudioAutoConfiguration`. Семантика
допуска и видимости Р3 сохраняется. Общий register-пакет не зависит от reportstudio, UI или Jasper.

## Архитектурные контракты

В точный прикладной surface добавлены API `RegisterMetadata`, `RegisterReader`, `RegisterRegistry`.
Роль `ReportReadPolicy` удалена из этого реестра: приложение больше не называет SPI, хотя сам
SPI остаётся доступен в reportstudio. Descriptor — возвращаемый контракт registry; validator и
startup обслуживают общую инфраструктуру. Нового прикладного reach-through не добавлено.
В package matrix явно разрешены зависимости register → data/catalog, его конфигурации →
register/catalog и reportstudio → register. Обратное направление запрещено ArchUnit.
Актуальные числа D1 проверяются exact-match тестом.

При будущем D-R выносе reportstudio остаётся явное замыкание на четыре нейтральных типа
корневого register-пакета: descriptor, metadata, validator и registry. До публикации report
add-on их нужно перенести в подходящий backend owner либо выделить нейтральный port;
зависимость отдельного отчётного артефакта на корневое приложение недопустима.
Замыкание записано в `PlatformSubsystemClosureTest`. Owner-модули и их публичные сигнатуры
Р4 не меняет; их baseline проверяются, fingerprints не перегенерируются.

## Приёмка

Итоговый набор включает весь согласованный набор Р2–Р3, дополнительно все `Register*Test`
и transfer-regression. Прошли 346 тестов: CRUD/форма/reader/oracle, CTE для одного и двух
измерений, 15 реальных security-сценариев, startup, descriptors, codec/assembly/run/preview,
surface/matrix/D1, API baseline, composition и архитектурные запреты.
[Сводка прогона](evidence/register-r4-tests-20261004.md).

JDK Axiom 21.0.11, Maven 3.9.9, Boot 4.0.6, Hibernate 7.2.12.Final, H2 2.4.240;
offline repository `C:\Users\strim\.m2\repository`. Полные логи находятся в игнорируемом
`.local-maven-repository/register-r4/`, итог — `final.log`.
Первый запуск остановился на параллельно добавленном REST-тесте; его ошибки типизации
были исправлены в рабочем дереве. Промежуточный общий запуск пересёкся с перекомпиляцией
`target`. Проверка перенесена в изолированный Maven directory
`.local-maven-repository/register-r4/target`; название `target` сохраняет стандартное
исключение test-классов в ArchUnit. Промежуточные неуспешные логи сохранены.

Для изоляции использовалась временная копия корневого POM с единственным изменением
`build.directory`; исходный `pom.xml` не менялся. Команда: обычный offline `mvn test`
с `-f .register-r4-pom.xml` и согласованным `-Dtest` (точный selector в локальном `selection.txt`).
Копия POM сохранена рядом с логами, временный файл в корне удалён после проверки.
Starter composition читает стандартный `target/application-runtime-dependency-tree.json`;
его SHA-256 совпал с runtime tree изолированной сборки:
`fd82a2c5fc67e73990b732dbd442adab16998ce971e13a19672fbab60ad591a5`.

Исходники зафиксированы [инвентарём](evidence/register-r4-sources-20261004.sha256):
808 файлов (`pom.xml`, `src`, `platform-rls/pom.xml`, `platform-rls/src`),
SHA-256 инвентаря `175d571da5b83561384c62ac5fe41d5cded154fce5b91d50005f84c3fbfb336d`.
Baseline HEAD `70b036c9f8ca1facb8dd55876c0a6406f0a4fc29`; изменения Р4 не закоммичены.
Установленный RLS JAR остался тем же, что при закрытии Р2–Р3:
`80ef8186801439b85a50b6cb2e53dbbbe7d3a01855299be8ae1ceffdf48d60b0`.

`SliceSpec`, production-генератор CTE и новые виды чтения остаются следующими этапами.
