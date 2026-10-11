# Проверка UI пользователей и отказов C5 — 2026-10-06

## Причина и границы исправления

В журнале рабочего приложения `UserService.findAll`, субъект `admin`, получил
`C5AccessDeniedException: Entity C5 read denied for org.ip.model.User`.
Роль ADMIN не заменяет явное разрешение C5 entity read.
Форма создания пользователя также читает справочник Role через EntityLookup.

В ListForm отказ при построении фильтров больше не трактуется как восстанавливаемая
ошибка конфигурации. При отложенном чтении Grid показывается сообщение о недостаточных
правах, затем исходный отказ передаётся дальше. FormCoordinator сообщает об отказе
при открытии карточки, включая чтение связанных справочников.
Права и данные рабочего приложения автоматически не изменялись.

## Проверки

- Полный `platform-vaadin` test/install: 476 тестов, failures/errors/skipped = 0.
- Прикладной прогон: 23 теста, failures/errors/skipped = 0:
  FormCoordinatorUiIsolationTest, UserAdministrationFlowIT, UserServiceTest,
  PlatformVaadinSurfaceTest, WorkspaceManifestTest.
- Отдельные gates PlatformApiBaselineTest и PlatformVaadinModuleTest:
  10 тестов, failures/errors/skipped = 0.
- UserAdministrationFlowIT на H2 проверяет отказ User без grant, отказ Role
  при открытии формы с одним User grant, создание через реальную UserItemForm
  с обоими grants, хэширование пароля и сохранение назначенной роли.
- Проверка уведомления координатора проверяет создание сообщения и вызов open;
  живой браузер этой проверкой не покрывается.
- Bootstrap `-ValidateOnly` завершился успешно без разрешения drift.
- `git diff --check` без ошибок.

Обновлён только fingerprint затронутого owner в текущем manifest:
`platform-vaadin`, 213 source files,
`e4f5c30614a67b6a4b144b1d18b342bbb73e63fbf29afb3126464593541f2f33`.
Добавлено явное test-usage разрешение FormResolver для нового прикладного IT;
публичный API платформы не расширялся.

Проверенный JAR переустановлен также в стандартный репозиторий
`C:\Users\strim\.m2\repository`. SHA-256 JAR в обоих репозиториях совпадает:
`7f12ba5a10cd8d79a7cac7a6a93188ff55dc97dc2b3f184aab433c321a15bcce`.

Локальные журналы находятся в игнорируемой `.local-maven-repository`:
`user-ui-vaadin-install.log`, `user-administration-flow-final.log`,
`user-ui-api-gates.log`, `user-ui-validate.log`, `user-ui-default-install.log`.
Эта проверка дополняет историческую приёмку REST 4.7, не заменяя её результаты.

## Действия в рабочем приложении

В «Администрирование → Доступ (RLS)» для USER / admin выдать чтение
`ENTITY:org.ip.model.User` и `ENTITY:org.ip.model.Role`.
Обновить `/lists/user`. Для использования исправленных сообщений перезагрузить
Maven-проекты в IDEA и перезапустить приложение.
Пошаговые условия создания REST-потребителя описаны в
[инструкции клиента](../rest-read-api-client.md#создание-учётки-для-локальной-проверки).
