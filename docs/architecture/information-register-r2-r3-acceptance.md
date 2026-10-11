# Р2–Р3: протокол закрытия

- Дата: 2026-10-04.
- Статус: Р2 и Р3 закрыты в заявленном ограниченном контракте на Hibernate/H2.
- План: [information-register-development-plan.md](information-register-development-plan.md).
- База рабочего дерева итогового прогона: `70b036c`, с проверяемым diff этого закрытия.

## Результат

Итоговый прогон: **322 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**.
В него вошли семь CTE-сценариев Р2, 15 security-сценариев Р3, архитектурный запрет
коротких production-конструкторов, startup wiring, регрессии регистра, compiler/codec,
обычные report query/run/HTTP preview и все `Platform*Test`.
Отдельно прошли 23 owner-теста `platform-rls` с установкой проверенного JAR и один
owner-тест независимого runtime-замыкания `platform-vaadin-starter`.
PDF/XLSX/DOCX/CSV существующего отчёта проверены через полный защищённый pipeline.

[Сводка по классам](evidence/register-r2-r3-tests-20261004.md) хранится в дереве проекта.
Полные локальные логи — `.local-maven-repository/register-r2-r3/`:
`final.log`, `rls-owner.log`, `starter-owner.log`; промежуточные неуспешные прогоны сохранены.

## Исправления по фактическим отказам

- Codec WHERE корректно читает FilterNode/FilterGroup; package codec сохраняет expression/HAVING codec.
- ON допускает проверенный глубокий путь ссылочного id; неизвестный alias отвергается и для `id`.
- Тестовые RLS-сущности вынесены за прикладной scan `org.ip`, старые generated `.class` удалены.
- SQL-проверка учитывает фактическое раскрытие CTE Hibernate в derived tables на H2.
- HTTP-пользователь разрешённого preview получает точные ENTITY/ATTR grants; добавлен отказ
  при наличии preview/RLS без C5. Автоматическая выдача production-грантов не добавлена.
- Видимость цели измерения проверяется отдельным регистром без собственного row filter.
  Association JOIN оказался недостаточным: используется независимый INNER JOIN целевого типа
  по его id в обеих фазах. Скрытая цель убирает группу, видимая без истории сохраняется в LEFT JOIN.
- При ограниченном FILTERABLE-доступе authorizer отклоняет FK/association JOIN без подтверждённого
  INNER entity join по id. Сравнение проверяется по SQM и JPA id, в том же источнике, а не по alias
  другого CTE. LEFT/OR/постороннее ON не считаются доказательством. Полный RLS-доступ не требует
  искусственного дополнительного JOIN.
- Короткие конструкторы deprecated; ArchUnit запрещает их вызов production-кодом репозитория.
- Report query явно отключает result query cache и обходит entity cache; проверены реальные
  Hibernate query options (`cacheable=false`, `CacheMode.IGNORE`). Тестов внешнего cache provider нет.

## Архитектура и воспроизводимость

Public surface, API baseline, D1, package matrix, core/RLS/Vaadin surface и starter composition зелёные.
Существующие недостающие роли пяти REST core-типов сверены с owner composition и отражены
как MODULE_API. Узкие test-usage записи объясняют form/REST приёмку; матрица дополнена группами
проверенных направлений C5, typed routes, REST и Explorer с причинами, без полной перегенерации.
Starter-проверка учитывает Maven nearest-winner: общие зависимости могут находиться под прямым
REST в application tree; отсутствие REST проверено также независимым owner tree starter.

JDK: AxiomJDK 21.0.11; Maven: IntelliJ 2025.2.3 bundled Maven 3.9.9;
Boot 4.0.6, Hibernate 7.2.12.Final, H2 2.4.240. Прогон offline, локальный repository —
`C:\Users\strim\.m2\repository`.

Исходники зафиксированы [инвентарём SHA-256](evidence/register-r2-r3-sources-20261004.sha256):
803 файла (`pom.xml`, `src`, `platform-rls/pom.xml`, `platform-rls/src`);
SHA-256 самого инвентаря: `7a1e1e48107bebf28ac324c988265f5b8ef1186000f460db4294d9ed8af80042`.
Проверенный source fingerprint v2 RLS: 49 файлов,
`5ca95045ee21369623bd1e7596c65c5caa3beb4b71385280a0522f91f8d9f67c`;
SHA-256 установленного RLS JAR: `80ef8186801439b85a50b6cb2e53dbbbe7d3a01855299be8ae1ceffdf48d60b0`.
Обновлена только соответствующая запись проверенного owner в `scripts/local-dependencies.json`.

## Команды

С настроенными JAVA_HOME/PATH, из корня:

```powershell
mvn -o -B -ntp '-Dmaven.repo.local=C:\Users\strim\.m2\repository' -f platform-rls/pom.xml install
mvn -o -B -ntp '-Dmaven.repo.local=C:\Users\strim\.m2\repository' -f platform-vaadin-starter/pom.xml test
mvn -o -B -ntp '-Dmaven.repo.local=C:\Users\strim\.m2\repository' '-Dtest=NomenclaturePrice*Test,NomenclaturePrice*IT,RegisterMetadataValidatorTest,RegisterStartupValidatorTest,TwoDimensionRegisterCteSpikeTest,RegisterSliceReportSecurityTest,ReportAuthorizationWiringTest,ReportStudioAutoConfigurationSmokeTest,VisualQuery*Test,VisualQuery*IT,Cte*IT,ReportQuery*Test,ReportQuery*IT,ReportExecution*Test,ReportExecutionIT,ReportJpqlPreviewControllerIT,JpqlRunServiceTest,Platform*Test' test
```

## Ограничения и следующий этап

Строгий report contract принят для scalar-проекций и полностью разрешённых persistent-атрибутов
участвующих типов. Entity-valued/embedded/element-collection и неоднозначные association paths
отклоняются; legacy constructors остаются test adapters, не защищённым внешним API.
Policy постоянства версий пока применяется ко всем report-чтениям регистра: сужение требует
явного slice intent, не эвристики по слову CTE. Инвентаризация и настройка прав сохранённых
шаблонов конкретной production-БД относятся к её выпуску; такая БД здесь не изменялась.

Р4 можно начать после этой приёмки. Named runtime asOf, versioned SliceSpec, два среза в одном
пакете, LIST/batch parity остаются Р5–Р9. PostgreSQL/EXPLAIN, внешние cache providers,
нагрузка и конкурентная запись этой приёмкой не подтверждены.
