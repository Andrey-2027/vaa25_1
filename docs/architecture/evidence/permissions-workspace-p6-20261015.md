# П6 — объектная матрица

Дата: 15.10.2026. Компонентный срез выполнен.

## Реализация

- PermissionObjectMatrix встроена production constructor PermissionWorkspace.
  Сущности и дополнительные определения берутся из PermissionCatalog, без списка классов.
- Подсистемы вложены через parentId, порядок каталога сохранён. Details сохраняют состояние
  раскрытия, поиск по названию оставляет необходимые родительские группы.
- Одна строка объекта, три позиции flags. Подпись конкретной операции из каталога находится
  у checkbox; ACTION показывает «Выполнение», settings свою операцию, отсутствующая capability
  — прочерк с объяснением. Generic CRUD owned-типы не изобретаются.
- PermissionCell показывает effective checkbox, личный источник, draft marker, «Р» с числом
  уникальных ролей и диалог точных источников; «Назначить лично» меняет только draft.
- objectsWithDraft рассчитывает личный overlay до C5/RLS решения; ролевые гранты остаются
  сохранёнными. CREATE/UPDATE колонка при отсутствии READ объясняет доступность только CREATE.
- Scope links вызывают callback общего scope id; реальные редакторы подключаются П7.
- Диагностика каталога и отдельный lazy-loaded системный отчёт ungrantedDefinitions сохранены.
  Список объектов без effective права активного субъекта подписан отдельно и не подменяет отчёт.

## Проверка

scripts/run-permissions-p6.ps1 — **157/157**, 95.006 s JUnit, 15 containers.
Reports: outputs/permissions-p4/test-summary.txt, test-run-p6.log. Runner Stage P4 + P5/P6 classes;
сумма включает inherited предыдущие сценарии, не 157 новых тестов.

Проверены отсутствие записи от draft overlay, CREATE without READ с точной подсказкой,
dirty provenance, real component matrix construction/search/reset и активный субъект,
сохранение/отмена через П4. Предыдущие catalogue/capability/concurrency gates проходят.
Исправлено сообщение успешного save в component-тесте без active UI: Notification вызывается
только в UI context, отсутствие UI не превращает уже успешный save в ложную ошибку.

## Границы

Browser layout/focus/touch приёмка — П9. Полные role navigation, дедупликация подробного
диалога и управление фокусом — П8. Scope callback пока показывает уведомление, П7 заменит
его общим редактором. Основная административная точка входа переключается в П10.
Все новые grant fixtures — H2, локальные предметные данные не изменялись.
