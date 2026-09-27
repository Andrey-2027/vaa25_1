# D-R: инвентарь будущего report add-on

Статический инвентарь текущего checkout на 2026-09-23. Это карта переноса, не решение о
переносе и не подтверждение сборкой; Maven и тесты при составлении не запускались.

| Область | Текущее расположение | Указание для D-R |
|---|---|---|
| Report Studio: модель, запросы, guard, render/run, сервисы и конфигурация | `src/main/java/org/ipro/reportstudio/**` | Кандидат в add-on; app-specific consumers оставить в приложении или подключить через явный seam. |
| UI каталога и редакторов | `src/main/java/org/ip/views/reportstudio/**` | Кандидат в add-on; сохранить интеграции с формами и текущие route aliases. |
| UReport glue, модель, сервисы и конфигурация | `src/main/java/org/ipro/ureport/**` | Кандидат в add-on; движок — отдельный внешний fork ниже. |
| JR execution/configuration | `src/main/java/org/ipro/jr/**` | Кандидат в add-on. `org.ip.views.reports.JrxmlRunDialog` — app UI-consumer, не переносить автоматически. |
| JR persistence | `src/main/java/org/ipro/jr/{dom/JrxmlTemplate.java,JrxmlTemplateRepository.java,config/JrPersistenceAutoConfiguration.java}` | Код и регистрация принадлежат приложению; `platform-persistence` оставляет только `BaseEntity`. Source boundary перенесена, Maven/тестовый cutover ещё не подтверждён. |
| Приложенческие потребители/точки подключения | `src/main/java/org/ip/Application.java`, `src/main/java/org/ip/config/EntityClassificationConfig.java`, `src/main/java/org/ip/views/reports/JrxmlRunDialog.java`, `src/main/java/org/ip/views/test/ReportQueryPreviewView.java` | Сверить каждый import/scan при переносе; не переносить бизнес-приложение вместе с движком. |
| Ресурсы и тема | `src/main/resources/jasperreports*.properties`, `src/main/resources/dynamicreports-defaults.xml`, `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; `.report-editor` в `src/main/frontend/themes/default/styles.css` | Разнести регистрацию add-on и report styling; не оставлять движковые defaults в app случайно. Других report-шаблонов в `src/main/resources` статический поиск не выявил. |
| Внешние зависимости/исходники | `../DynamicReport7` (`dynamicreports`, pinned upstream revision `c95f34d`); `../ReportFlowUI` (`reportui`); `../UReportFork2/ureport3` (dirty worktree, commit зафиксирован в manifest); JasperReports/JasperReports Fonts — Maven artifacts из root `pom.xml` | Источник и версии сверять через `scripts/local-dependencies.json` и root POM. Сохранить существующий license gate UReport/iText; решения о лицензировании здесь нет. |
| App-owned автоконфигурации и persistence | `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; `ReportStudioPersistenceAutoConfiguration`, `UreportPersistenceAutoConfiguration`, `JrPersistenceAutoConfiguration`, `JrAutoConfiguration` | Сохранить регистрации у legacy-приложения до появления add-on. Проверить, что общий starter их не импортирует. |
| Тесты и проверяемые швы | `src/test/java/org/ipro/reportstudio/**`, `src/test/java/org/ipro/ureport/**`, `src/test/java/org/ipro/jr/**`, `src/test/java/org/ip/views/reportstudio/**`, `src/test/java/org/ip/security/ReportStudioAutoConfigurationSmokeTest.java`, `src/test/java/org/ipro/{ReportFormSeamTest,PlatformPublicSurfaceTest}.java`; `platform-persistence/src/test/java/org/ipro/persistence/PersistenceModuleCompositionTest.java`; `src/test/resources/{report-form-seam.txt,platform-public-surface.txt}` | JR persistence slice `JrPersistenceRegistrationTest` живёт в приложении; module-composition и cross-module gates остаются у своих границ. Тесты при этом обновлении не запускались. |

## Ограничение перед D3.8

Статическая сверка POM-графа подтвердила, что исключить `platform-persistence` из backend
starter простым dependency exclusion нельзя: на него напрямую опираются `platform-core`,
`platform-numbering`, `platform-settings` и `platform-rls`; `platform-spring-boot-autoconfigure`
подтягивает core, numbering и RLS, а root-приложение также объявляет persistence напрямую.
Следовательно, обычный backend closure неизбежно включает этот артефакт.

**Узкое разделение JR persistence реализовано в исходниках, без Maven-верификации:**
`BaseEntity` и нужные ему нейтральные зависимости остаются в `platform-persistence`, а
`JrxmlTemplate`, `JrxmlTemplateRepository` и `JrPersistenceAutoConfiguration` находятся в
приложении. Удалены JR-only `PersistenceAutoConfiguration` и platform imports entry; обновлены
composition/registration gates и manifest fingerprint. Это не перенос всего report add-on:
движки, Report Studio и UReport пока остаются приложению. Пути, регистрации, POM и manifest
проверяются статически; тесты и Maven не запускались по просьбе владельца.

## План узкого разделения JR persistence

1. `JrxmlTemplate` и `JrxmlTemplateRepository` перенесены в `src/main/java/org/ipro/jr` с
   прежними FQN, JPA mapping и именами таблиц. `BaseEntity` осталась в `platform-persistence`;
   миграция данных для самого перемещения классов не требуется.
2. В приложении создана `JrPersistenceAutoConfiguration` только с `@EntityScan("org.ipro.jr.dom")`
   и `@EnableJpaRepositories("org.ipro.jr")`; она добавлена в app `AutoConfiguration.imports`.
   `JrAutoConfiguration` остаётся конфигурацией сервисов, её комментарий обновлён.
3. Из `platform-persistence` удалены JR-only `PersistenceAutoConfiguration` и imports-файл.
   Сохранены зависимости, требуемые `BaseEntity` (Spring Data JPA для auditing listener и
   Hibernate для proxy-safe equality); Boot auto-configuration/persistence compile-зависимости сняты.
4. Проверка записи JR entity/repository перенесена в `JrPersistenceRegistrationTest` приложения
   с явным импортом app-owned конфигурации. Обновлены `PersistenceModuleCompositionTest`,
   `PlatformPersistenceModuleTest`, `PersistenceTypeRegistrationTest` и
   `PersistenceRegistrationIT`; production-состав platform persistence закреплён за `BaseEntity`.
5. Обновлены `platform-persistence/README.md`, этот инвентарь и fingerprints/state
   `platform-persistence` и затронутого `platform-rls` в `scripts/local-dependencies.json`.
   Исторические результаты D2 сохранены как история. Проверено, что `platform-public-surface.txt`
   пока считает любой `org.ipro.*` платформенным типом, поэтому перенос FQN сам по себе не
   снимает JR-строку из реестра; исправление учёта владельца отнесено к D3.9, строка сохранена.
6. Выполнены быстрые статические проверки путей, регистраций, состава/POM и fingerprints.
   В согласованном финальном Maven-пакете сначала переустановить обновлённый
   `platform-persistence` (чтобы старый локальный JAR не сохранил JR-классы), затем проверить
   JR `@DataJpaTest`, `PersistenceRegistrationIT`, границы и полный набор приложения.

Критерий выхода: backend starter может транзитивно включать `platform-persistence`, а его
production JAR не содержит JR entity/repository или JR auto-configuration; приложение при
этом сохраняет прежние FQN, регистрацию и доступ к JR-шаблонам.
