# Рабочее место прав: приёмка П0, 13.10.2026

Статус: П0 выполнен. UI и модель разрешений в приложении на этом этапе не заменялись.

Локальные документы: [основной план](C:/JavaProject/TestVaadin25/GitVaa25/docs/plans/permissions-administration-workspace-plan.md), [спецификация П0](C:/JavaProject/TestVaadin25/GitVaa25/docs/plans/permissions-administration-p0-specification.md). Эти планы сохраняются локально по правилам проекта; `.gitignore` не менялся.

## 1. Границы и воспроизведение

Собраны 434 application Java source files из зафиксированного source snapshot в отдельный run-каталог под `outputs/permissions-p0`. Основной `target` приложения и соседние проекты не изменялись этим прогоном. Classpath разрешён offline из явно заданного `C:/Users/strim/.m2/repository`; зависимости скопированы в workspace, их SHA256 записаны в manifest.

Инструменты: JDK 21, Maven IntelliJ 3.9.9, фактически разрешённые application dependencies; JUnit 6.0.3. Недоступный default Maven repository в этом окружении обойдён явным `maven.repo.local`, без изменения глобальной конфигурации.

Команды из корня проекта:

```powershell
./scripts/run-permissions-p0.ps1
./scripts/run-permissions-p0-audit.ps1
```

Первый скрипт компилирует приложение и проверочный пример в изолированные каталоги и запускает Spring-контекст с `jdbc:h2:mem:permissions_p0`. PostgreSQL URL, credentials, dialect и schema mode явно перекрыты тестом; demo и initial issuance выключены. REST read/web включены, как в main-конфигурации. Контекст работает с mock servlet environment; HTTP-сервер приложения не запускается.

Второй скрипт читает только локальную PostgreSQL, указанную в `config/application-local.properties`. Пароль используется только в окружении дочернего процесса и восстанавливается после вызова. На уровне соединения установлен `default_transaction_read_only=on`; SQL использует `REPEATABLE READ READ ONLY`, timeout и завершается `ROLLBACK`. Скрипт не содержит изменения схемы, удаления или выдачи грантов.

## 2. Исполняемый пример: 6/6

Тест: [PermissionsWorkspaceP0IT.java](C:/JavaProject/TestVaadin25/GitVaa25/src/test/java/org/ip/security/PermissionsWorkspaceP0IT.java). Standalone launcher хранится в `scripts/permissions-p0`; дополнительная launcher-зависимость не добавлялась в application POM.

| Проверка | Результат |
| --- | --- |
| Runtime catalog/registry snapshot, capability AttributeValue CREATE-only и SklNomOpa read-only, четыре REST READ | Пройдена. |
| Реальные стандартные User/Role ListForm в layout; независимый selection при совпадающих числовых id | Пройдена. |
| JpaFilterGrid: встроенный text-filter + additional specification, реальные H2 fetch/count, страница с offset | Пройдена. |
| Компонентная Checkbox + «Р»: изменение внешнего draft, отсутствие переключения от «Р» и записи AccessGrant | Пройдена. |
| Две назначенные роли: обе дают READ, только оператор даёт CREATE/UPDATE | Пройдена. |
| Общие источники JOURNAL/BRANCH и применимость к PrdSpec/ReceivingDocument/Workshop | Пройдена. |

Итог последнего прогона: 6 tests started, 6 successful, 0 failed, 0 skipped. [Полный summary](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-p0/test-summary.txt). Прямое создание protected Branch в первоначальной фикстуре было отклонено write-границей; окончательный пример создаёт Branch через canonical owner с явными тестовыми правами. Enforcement не отключался ради прохождения этой проверки.

Компиляция текущих application sources успешна; 19 предупреждений существующих deprecated/unchecked API не являются результатом новой UI-реализации. Проверка не заменяет полный application regression suite, браузерную приёмку и нагрузочный прогон большого справочника.

Встраивание factory API проверено компиляцией; runtime factory mock возвращал реальные ListForm. Реальный UI-scoped coordinator, маршрутизация, toolbar CRUD и браузерный фокус входят в П5/П9. П0 не выдаёт эту ограниченную проверку за готовый экран.

## 3. Runtime inventory

| Срез | Количество |
| --- | --- |
| JPA persistence types | 45 |
| STANDARD_ROOT | 21 |
| OWNED_ROW | 7 |
| INTERNAL_STORE | 17 |
| Entity READ definitions | 21 |
| REST READ definitions | 4 |
| Entity operation definitions | 20 |
| Named ACTION definitions | 7 |
| Все C5 definitions | 52 |
| Общие FILTERABLE | 2: JOURNAL и BRANCH |
| Legacy CHECK_ONLY вне C5 definitions | 5: четыре SETTINGS и REPORTS:JPQL_PREVIEW |
| Зарегистрированные dimensions | 59 |

Группы STANDARD_ROOT: 11 в Справочниках, 7 в Производстве, 3 без subsystem marker (User, Role, GridFormView). В дереве четыре subsystem markers, включая разные «Производство» и «Документы / Производство»; два последних documentary markers не имеют собственных сущностей, но несут права настроек.

Снимки: [objects.tsv](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-p0/objects.tsv), [definitions.tsv](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-p0/definitions.tsv), [dimensions.tsv](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-p0/dimensions.tsv), [subsystems.tsv](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-p0/subsystems.tsv).

Registry snapshot относится к текущим скомпилированным исходникам и изолированному test-контексту с REST включённым. Snapshot не получен из работающего production-процесса. Изменения другой активной задачи, присутствующие в workspace, не сбрасывались; точный source snapshot зафиксирован в manifest.

## 4. Аудит локальной PostgreSQL

База из локальной конфигурации: DBVaa25. Аудит агрегатов и exact grants на минимальные административные операции: [database-audit.txt](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-p0/database-audit.txt).

| Показатель | Результат |
| --- | --- |
| Пользователи / роли | 4 / 3 |
| Гранты | 29; все прямые USER, без ROLE-grants |
| Журналы / филиалы | 3 / 2 |
| Пользователи с несколькими ролями в этом наборе | 0; у каждого одна роль |
| Дубли одного логического гранта | 0 |
| Гранты на отсутствующего пользователя/роль | 0 / 0 |
| JOURNAL-ссылки на отсутствующее значение | 0 |
| BRANCH-ссылки на отсутствующее значение | 4: id 9, 12, 13, 16, по одному гранту |
| ATTR / value-scoped ENTITY-ACTION-REST | 0 / 0 |
| Пустые ключи / все флаги false | 0 / 0 |
| RLS write/delete без read | 0 |

Одна сохранённая учётная запись с ролью ADMIN имеет entity READ для User и Role. Она не имеет разрешающих exact/global grants для `ENTITY:User`, `ENTITY:Role`, `ACTION:USER:ASSIGN_ROLES`, `ACTION:USER:CHANGE_PASSWORD`. Показаны роли и прямые гранты вместе; wildcard не засчитывался как C5-разрешение.

Следствие: сам ADMIN-prerequisite не обеспечивает editable стандартные формы. П4/П5 должны подготовить узкий минимальный набор и пилотировать его явно. П0 не менял эту выдачу и не удалял четыре исторические BRANCH-ссылки.

## 5. Подтверждённые API и ограничения

- [FormCoordinator:421](C:/JavaProject/TestVaadin25/GitVaa25/platform-vaadin/src/main/java/org/ipro/form/coordinator/FormCoordinator.java:421): standard embedded list factory.
- [ListForm:1637](C:/JavaProject/TestVaadin25/GitVaa25/platform-vaadin/src/main/java/org/ipro/form/builtin/ListForm.java:1637): текущая выбранная запись; native SINGLE-selection сохраняется.
- [FilterGrid:246](C:/JavaProject/TestVaadin25/FilterGrid/filtergrid-core/src/main/java/org/ipro/filtergrid/FilterGrid.java:246): компонентная ячейка.
- [JpaFilterGrid:107](C:/JavaProject/TestVaadin25/FilterGrid/filtergrid-jpa/src/main/java/org/ipro/filtergrid/jpa/JpaFilterGrid.java:107): additional specification для data/count.
- [InMemoryFilterGrid:48](C:/JavaProject/TestVaadin25/FilterGrid/filtergrid-inmemory/src/main/java/org/ipro/filtergrid/inmemory/InMemoryFilterGrid.java:48): bean cell editing, в текущем API только InMemory.
- [C5PermissionDecisionService:97](C:/JavaProject/TestVaadin25/GitVaa25/platform-rls/src/main/java/org/ipro/rls/c5/C5PermissionDecisionService.java:97): полные источники явно выбранного субъекта; [строка 217](C:/JavaProject/TestVaadin25/GitVaa25/platform-rls/src/main/java/org/ipro/rls/c5/C5PermissionDecisionService.java:217): общий canUpdate для CREATE/UPDATE/EXECUTE.
- [AccessService:156](C:/JavaProject/TestVaadin25/GitVaa25/platform-rls/src/main/java/org/ipro/rls/AccessService.java:156): существующие write-правила и bootstrap. Источники отдельных RLS-галок требуется доработать на П2.
- [SettingsAdminTab:92](C:/JavaProject/TestVaadin25/GitVaa25/src/main/java/org/ip/views/admin/SettingsAdminTab.java:92): legacy canUpdate; [ReportJpqlPreviewController:69](C:/JavaProject/TestVaadin25/GitVaa25/src/main/java/org/ip/web/ReportJpqlPreviewController.java:69): legacy hasAnyAccess, а не ACTION EXECUTE.

Разрешены `filtergrid-core/jpa/inmemory` версии `1.0-SNAPSHOT`. Полные JAR hashes отличаются от соседних target artifacts, но `.class` hashes FilterGrid, JpaFilterGrid и InMemoryFilterGrid совпали. Проверенное соответствие ограничено этими классами. [API class comparison](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-p0/api-class-comparison.json), [artifact manifest](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-p0/artifact-manifest.json), [source manifest](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-p0/source-manifest.json).

## 6. Решения и следующий этап

Уточнение расширяемости: [RlsDimensionRegistry:132](C:/JavaProject/TestVaadin25/GitVaa25/platform-rls/src/main/java/org/ipro/rls/RlsDimensionRegistry.java:132) регистрирует источник значений по `grantValues=true` только для FILTERABLE. UI-каталог должен использовать этот признак. При этом [RlsFilterActivator:152](C:/JavaProject/TestVaadin25/GitVaa25/platform-rls/src/main/java/org/ipro/rls/RlsFilterActivator.java:152) содержит явный privileged allowlist JOURNAL/BRANCH. Его обобщение через зарегистрированные административные источники и контролируемый scope включено в П3; полный путь новых измерений пока не является проверенным результатом П0.

Первый срез сохраняет variant A: «Чтение / Создание и изменение / Удаление» с точными подписью/доступностью по capability. Новая миграция флагов и пересмотр ADR-0017 для независимых CREATE/UPDATE не выполняются.

Ключевые требования закреплены в спецификации: stable SubjectRef; личные и ролевые права по OR; точная «Р»; общий JOURNAL/BRANCH; глобальные источники отдельно от view filter; drafts по субъектам; save активного субъекта одной транзакцией; атомарный lifecycle текстовых ключей и запрет небезопасного переименования до его появления.

П1 можно начинать с каталога и read-контрактов. До writable пилота обязательны: paged API, точный RLS trace, patch/concurrency, lifecycle субъектов и минимальные права администратора. Четыре исторических BRANCH-гранта остаются предметом явной диагностики/обработки, не фоновой очистки от нового UI.
