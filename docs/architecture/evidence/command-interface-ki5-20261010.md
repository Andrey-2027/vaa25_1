# КИ-5 — браузерная приёмка на PostgreSQL

Дата: 2026-10-10. Среда: Windows, Axiom JDK 21.0.11, Spring Boot 4.0.6,
Vaadin 25.1.6, PostgreSQL 17.10, браузер Codex. Проверялось настоящее приложение
с обычным входом, без подмены security/user/navigation backend.

План: [КИ-5](../command-interface-pilot-plan.md).
Запуск и повторение сценария: [пользовательская проба](../command-interface-user-trial.md).
Решение: [границы пилота](../decisions/command-interface-pilot-boundary.md).

## Поставка и безопасность подготовки

`scripts/run-navigation-pilot.ps1` собирает приложение и отдельные Java-классы
`scripts/navigation-pilot/java`. Они не входят в main/test jar приложения.
Создана новая локальная БД `navigation_pilot_6dd935094b1448d98f627a56dcd85d1f`.
Рабочая БД не клонировалась и не изменялась. Добавочный навигационный DDL применён
до первого старта, остальная схема подготовлена `update`; повторные старты
прошли `validate`. Сервер слушает только `127.0.0.1:8087`.

Role/User/тестовая единица создаются `EntityDataAccess` с явными правами отдельного
субъекта подготовки, включая именованные операции пароля/ролей. Enforcement
финальной границы записи включён. Профили/назначения создаются защищённым API.
Пользователи А/Б имеют одинаковую роль `KI_READER`, одинаковые read grants, без
индивидуальных grants и без права изменения. ADMIN получает явное чтение User/Role.
Накладным не выдаётся чтение, хотя в обоих профилях хранится SHOW.

Для startup без HTTP-сессии UI-пилот использует предусмотренный resolver seam
`AccessService::getReadableIds` без кэша и отключает отдельный REST web adapter.
Первый вариант с двумя resolver был отвергнут штатной fail-closed проверкой;
вариант со штатным session-only REST resolver остановил транзакцию подготовки.
Итоговый запуск имеет один источник и не использует RLS bypass.

URL БД принудительно передаётся validated command-line аргументом до Hibernate:
унаследованный `SPRING_DATASOURCE_URL` не может перенаправить DDL в другую БД.
Это проверено повторным запуском с намеренно другим URL в окружении.
Секреты — только в игнорируемых локальных config-файлах; проверен `git check-ignore`.
Start/Stop/Restart/Status/Verify работают с собственным PID/состоянием; Stop
проверяет command line процесса. Verify выполняет отдельную read-only транзакцию.

## Браузерные результаты

Использованы три независимых host cookies: администратор `127.0.0.1`, А
`localhost`, Б `localhost.`. Все адреса обслуживаются тем же loopback сервером
и одной изолированной PostgreSQL-базой.

| Сценарий | Фактический результат |
| --- | --- |
| Два исходных профиля | А видит единицы, не видит спецификации; Б видит спецификации, не видит единицы. |
| Вложенность | А видит непустого потомка и его developer-hidden плитку через SHOW. У Б HIDE потомка блокирует его SHOW плитки, включая страницу предка. |
| Права поверх SHOW | Накладные отсутствуют в рабочих плитках обоих пользователей при сохранённом SHOW. |
| Разрешённая прямая ссылка | Б открывает `/lists/unit-of-measurement` и видит «Пилот — штука», хотя плитка скрыта. Создание/изменение/удаление отключены. |
| Запрещённая прямая ссылка | `/lists/receiving-document` показывает «Доступ запрещён» с конкретным отказом чтения накладных. |
| Администрирование | У обычных пользователей нет административного пункта. Администратор создаёт третий профиль, сохраняет HIDE раздела, назначает А из поиска. |
| Изоляция и обновление | Открытый UI А сохраняет снимок до reload; после reload скрытый раздел исчезает. UI Б сохраняет своё назначение и меню. |
| Снятие/замена | Назначение А снято, reload возвращает defaults; профиль назначен повторно. Исходный профиль А восстановлен после пробы. |
| Сброс | Подтверждённый сброс убирает overrides, назначение остаётся; повторное чтение назначения подтверждает тот же профиль. |
| Переименование | Третий профиль переименован в «Браузерная приёмка — проверено» через диалог. |
| Перезапуск | Повторный вход сохраняет разные профили А/Б; PostgreSQL `validate` проходит без пересоздания схемы. |
| Выключенный режим | У Б возвращаются единицы через исходное меню, вкладка «Интерфейс» отсутствует у администратора; сохранённые профили остаются в БД. |
| Возврат включённого режима | Различие меню А/Б восстановлено; созданный и переименованный через браузер третий профиль остался в списке. Пилот оставлен включённым для пробы. |

При визуальной приёмке исправлено сжатие TreeGrid в редакторе: все элементы
редактора получают `flex-shrink: 0`, прокручивается сам редактор. В браузере
подтверждены высоты дерева 448 px и предпросмотра 288 px; обе сетки доступны
с нормальной прокруткой. Это единственная правка production UI в КИ-5.

Скриншоты: [А — справочники](command-interface-ki5-a-directories.png),
[Б — справочники](command-interface-ki5-b-directories.png),
[А — вложенность](command-interface-ki5-a-nested.png),
[Б — вложенность](command-interface-ki5-b-nested.png),
[разрешённая прямая ссылка](command-interface-ki5-hidden-direct-link.png),
[отказ чтения](command-interface-ki5-denied-direct-link.png),
[редактор после исправления](command-interface-ki5-editor.png),
[выключенный режим](command-interface-ki5-disabled.png).

Read-only PostgreSQL-проверка подтвердила восстановленные назначения А/Б,
одинаковые роли/права и три профиля: исходные с 4/5 overrides и третий после
сброса с 0 overrides, version=3. Наполнение при рестартах не перезаписывает их.

## Автоматические проверки

- Модуль: 40 успешных тестов, PostgreSQL opt-in тест пропущен в обычном прогоне.
- Отдельный PostgreSQL-прогон: 1 успешный тест, без пропусков; временная тестовая
  БД создана и удалена самим `test-navigation-postgresql.ps1`.
- Регрессия приложения: 52 успешных проверки редактора, wiring, snapshot,
  security-адаптеров, enabled/disabled smoke, API/архитектуры и Workspace.
- После визуальной правки: компиляция прошла, 15 проверок редактора/AdminView
  повторно успешны. Java API, DDL и fingerprint `platform-navigation` не менялись.

Команды (Java 21, подготовленные локальные зависимости):

```powershell
mvn -o -f platform-navigation/pom.xml test
./scripts/test-navigation-postgresql.ps1
mvn -o '-Dvaadin.skip=true' '-Dtest=NavigationProfileEditorTest,NavigationAdminWiringTest,NavigationEditorApplicationSmokeTest,NavigationApplicationSmokeTest,NavigationDisabledApplicationSmokeTest,NavigationUiSnapshotTest,ApplicationNavigationConfigurationTest,ApplicationNavigationSecurityTest,PlatformApiBaselineTest,PlatformArchitectureTest,MainLayoutLeaveGateTest,EntityExplorerAddressWiringTest' '-Dplatform.api.baseline.artifact=platform-navigation' test
mvn -o '-Dvaadin.skip=true' '-Dtest=NavigationProfileEditorTest,NavigationAdminWiringTest' test
./scripts/run-navigation-pilot.ps1 -Action Verify
```

Локальные журналы: `outputs/navigation-ki5-{build,module,postgresql,regression,ui-fix,editor-final}.log`,
`outputs/navigation-ki5/{server,server-validated,server-disabled,database-verification}.log`,
`outputs/navigation-ki5/browser-checks.json`. В browser JSON записаны проверенные
условия без паролей; [копия в evidence](command-interface-ki5-browser-checks.json).
Широкий общий surface gate не переобъявлен зелёным:
посторонние отказы UniDR/замеров из КИ-4 остаются за пределами этого пакета.

## Практические границы

Тестовая вложенность содержит metadata-only плитки без CRUD-форм. Бизнес-проба
использует настоящие единицы/спецификации. Для текущего набора бизнес-типов
`RlsReadGate` фильтрует CHECK_ONLY права; остальные исходные плитки могут
показываться и затем отказать при C5-открытии данных — профиль не расширяет права.
Служебные/legacy-пункты не регулируются профилями. Режим dev и локальная
подготовка схемы не являются промышленной миграцией. Конкурентные записи,
rollback, stale IDs и отсутствующий security adapter покрыты автоматическими
проверками КИ-1–КИ-4, а не выдаются за отдельные браузерные сценарии этого прогона.
