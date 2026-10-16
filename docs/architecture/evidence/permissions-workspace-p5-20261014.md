# П5 — оболочка рабочего места

Дата: 14.10.2026. Компонентный срез.

PermissionWorkspace — prototype Spring component, SplitLayout с изменяемой шириной,
стандартные ListForm User/Role через FormCoordinator, SINGLE selection, compact FilterGrid.
Стандартные toolbar и double-click handlers не переопределяются. Закладки сохраняют
компоненты списков и отдельные выделения/фильтры. Refresh/filter сбрасывает выделение
только затронутого списка; draft сохраняется. Загрузка субъекта всегда по stable identity.

Заголовок показывает имя, назначенные роли, число изменений активного и число других
субъектов с draft. Save пишет patch П4, очищает draft только при успешном возврате;
Cancel относится к активному субъекту. Ошибка остаётся в панели и не удаляет draft.
Dirtyable/Savable поддерживают Workspace close contract. BeforeLeave диалог предлагает
сохранение текущего, возврат и явный отказ от всех; save не разрешает выход при других draft.

PermissionWorkspaceUpdates рассылает committed User/Role/grant изменения подключённым
UI через ui.access; подписка снимается на detach. Header/state перезагружаются, draft
baseline не заменяется. Обновление внешней карточки использует существующий canonical event.

Проверка scripts/run-permissions-p5.ps1: 142/142. Runner использует Stage P4 с additional
P5 class, reports в outputs/permissions-p4/test-run-p5.log и test-summary.txt.
Это component/JPA проверка без браузера: реальный ListForm/InMemoryFilterGrid и сервисы
П2/П4; FormCoordinator mock возвращает стандартные формы, поскольку Vaadin UI scope
в Spring test без VaadinSession не активен. Production constructor использует настоящий
coordinator. Проверены selection, deselection, draft switching, cancel, save и соседние gates.

Ручная browser/keyboard/CRUD/close приёмка в полном Workspace ещё не проведена — П9.
Новый компонент не подключён как основной AdminView: это П10 после матрицы П6 и scope П7.
Для П6/П7 доступны content slot, active snapshot, subject listeners и change API.
