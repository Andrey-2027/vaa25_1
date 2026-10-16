# П2 — evidence эффективных прав и происхождения

Дата: 14.10.2026. Статус: выполнен.

## Дополнение: запрет изменения/удаления без чтения

По явному решению владельца после первого завершения П2 отменено описанное ниже
permissive bootstrap-поведение. AccessService требует RLS READ того же существующего id
и соответствующий write-флаг. Bootstrap только UPDATE-создание без id, не DELETE.
C5 decision/enforcement и batch contributions требуют effective ENTITY READ для
UPDATE/DELETE. Read и write могут поступать от разных назначенных источников.
Флаги хранения независимы, CREATE и ACTION EXECUTE не требуют READ.

Повторный `scripts/run-permissions-p2.ps1`: **91/91**, 103.440 s JUnit.
Повторный Maven test platform-rls после обновления JPA-контрактов: **78/78**,
BUILD SUCCESS. Проверки flush boundary включены в этот модульный прогон.
Дополнительные сценарии: write без read закрыт; read другого id недостаточен;
read отдельной назначенной роли разрешает write; отзыв read закрывает UPDATE/DELETE;
создание без read сохраняется. Исторические результаты ниже относятся к первому прогону.
Из-за параллельных Java-процессов прогон выполнен с Xmx256m, SerialGC и ограничением
code cache; данные DBVaa25 не изменялись.

## Изменения

- `src/main/java/org/ip/application/access/PermissionStateService.java`: ADMIN-only immutable snapshot матрицы и переданной страницы ограничения, новое значение без id, актуальная адресация субъектов и назначенных ролей.
- `PermissionCellState.grantingRoles(...)`: полный уникальный набор наследуемых ролей для одной галки, без разбора подписей.
- `platform-rls/.../RlsGrantMatching.java`: общие правила RLS provenance/enforcement, без нормализации при чтении.
- C5 decision/read services: пакетный contributions API у владельцев определения и capability; exact/global и флаг остаются обязательными.
- `AccessGrantRepository`: пакетный subject/type/key/value/global запрос и grouped exact dimension counts.
- `UserRepository.findPermissionSubject`: субъект с назначенными ролями одним fetch join.
- runner П2 и совместимость существующего runner с native stderr warnings Windows PowerShell 5.1.

## Выполненные команды

1. `scripts/run-permissions-p2.ps1` — **89 tests successful, 0 failed**, 64.509 s собственно JUnit-прогона. Компилируются текущие application и platform-rls/autoconfigure/register sources, копии dependency jars и manifest фиксируются в `outputs/permissions-p2/`.
2. Maven offline `-f platform-rls/pom.xml -Dmaven.repo.local=C:\Users\strim\.m2\repository test`, JAVA_HOME AxiomJDK-21 — **77 tests, 0 failures/errors/skipped**, BUILD SUCCESS.

Основной результат: `outputs/permissions-p2/test-summary.txt`; лог: `outputs/permissions-p2/test-run.log`. Все записи тестовых фикстур — изолированная H2 с rollback, локальная DBVaa25 не изменялась.

## Проверенные сценарии

- Две назначенные роли дают READ, одна UPDATE, пользователь DELETE: у каждого флага свой точный набор. Сравнение с реальным `decideForSubject` для READ/CREATE/UPDATE/DELETE.
- Дубликаты физических read-грантов присутствуют в provenance, но роль в «Р» показывается один раз.
- Wildcard и value-scoped ENTITY-строки не выдаются как C5-источники; capability/common registry проходят существующий П-U gate.
- RLS-флаг другого значения не попадает в источники текущего. Прямое и ролевые права складываются; wildcard масштаб сохраняется.
- Показанное READ сравнивается с `getReadableIds`, UPDATE/DELETE — с AccessService для нескольких существующих id. Вопрос о новом значении сравнивается отдельно.
- Собственный all-values источник эффективен, но не изображается как снимаемый exact-row direct grant.
- Роль рассматривается как прямой субъект без вложенных ролей. Пользователь без ролей не наследует грант одноимённой роли или чужой роли.
- Bootstrap объясняется как SYSTEM_RULE; false-only грант неназначенной роли прекращает bootstrap. Подтверждено расхождение смысла чтения и записи в действующей политике, а не изменение политики.
- Не-ADMIN получает отказ на всех трёх публичных операциях; unknown scope, отрицательный id и отсутствующий субъект отвергаются.

## SQL / N+1

Hibernate statistics включаются только на время бюджетного сценария после flush/clear. Вызовы fetch данных выполняются через реальный Spring/JPA сервис:

| Набор | SQL |
| --- | --- |
| 1 значение, 2 роли | 3 |
| 100 значений / 300 ячеек, 2 роли | 3 |
| 100 значений, 26 ролей | 3 |
| Матрица всех рабочих объектов | 3 |

Строка лога: `P2 SQL budget: page1=3, page100=3, matrix=3`. Утверждение о 26 ролях также проверено assertion равенства query count. Нет запроса на отдельную ячейку или роль.

## Границы результата

Это серверная приёмка П2. UI, draft overlay, сохранение, fetch/count предметных значений и выбранных строк относятся к следующим этапам. Планы SQL/нагрузка PostgreSQL и UI-приёмка не выполнялись в П2.

Действующая bootstrap-политика разрешает запись существующего значения FILTERABLE без exact грантов, но не чтение. П2 явно объясняет это, не вводя скрытого нового allow/deny. Контракт: `docs/plans/permissions-administration-p2-contracts.md`.
