# Р11: реализация записи по ключу в canonical pipeline (set / delete + пилот цен)

Дата: 2026-10-13. Статус: реализация закрыта и проверена — H2-приёмка, регрессия регистров и
пилота, PostgreSQL-прогон 24 / 24. Контракт — [ADR-0023](../decisions/ADR-0023-register-key-write.md),
спайк — [протокол 2026-10-12](register-r11-key-write-spike-20261012.md); этим пакетом закрыта та
часть, которую спайк намеренно не проверял: права C5/RLS, canonical-фиксация записи, row lifecycle,
durable-аудит и перевод пилота цен.

## Что именно сделано

| Место | Что появилось |
| --- | --- |
| `RegisterKeyWrites` | Команда `set`/`delete` по полному ключу: собственные транзакции на попытку, вердикты вместо исключений, классификация по SQLState, bounded retry, durable-аудит `set-register-key:<Тип>` / `delete-register-key:<Тип>` |
| `RegisterKeyWritePermissions` | Именованные операции `REGISTER:<FQN>:SET_KEY` и `REGISTER:<FQN>:DELETE_KEY`: определения, требования, отдельно — то же решение для интерфейса (`canWriteKey`) |
| `RegisterRowState` | Доступ к полям строки по объявленному имени и независимый исходный снимок для `beforeUpdate` |
| `RegisterMetadata` / `RegisterDescriptor` / `RegisterMetadataValidator` | Декларация `keyWriteAllowed` (default `false`), её проверка на дескрипторе и запрет для подчинённого регистра |
| `RegisterAutoConfiguration` | Бины прав и команды; команда не прячет чужую транзакцию и не вводит `REQUIRES_NEW` |
| Пилот цен | `NomenclaturePrice` объявляет `keyWriteAllowed`; `NomenclaturePriceService` получил `setPrice` / `changePrice` / `removePrice`; `RegisterKeyPermissionTitles` называет две новые операции для назначения прав |

Ключ этим контрактом не переносится: другой ключ — другая строка, перенос остаётся командой
[ADR-0021](../decisions/ADR-0021-register-key-change-command.md). Обычная запись самостоятельного
регистра (форма, REST) сохраняется как canonical CRUD: контракт не отменяет прежние каналы, а
добавляет именованную операцию с определённым ответом на гонку. Подчинённый регистр командой не
затрагивается — `keyWriteAllowed` у него отвергается и валидатором, и командой.

## Порядок, который соблюдает команда

`permissions.requireSet/requireDelete` (C5: ACTION плюс ENTITY READ и CREATE/UPDATE для set,
DELETE для delete) → RLS-значения строки → row lifecycle (`beforeSave`/`beforeUpdate`/`onSave`,
`beforeDelete`) → Bean Validation → фиксация canonical write → durable-аудит → публикация событий
(committed-события доставляются только после commit). Тот же порядок, что у остальных canonical
write-путей; новых таблиц и миграций нет — ключ уже физический UNIQUE.

## Проверено

| Проверка | Результат |
| --- | --- |
| `RegisterKeyWritesTest` (H2, реальная схема и Criteria-чтение ключа) | 12 / 12: upsert создаёт строку, повтор ресурсов — `UNCHANGED` без версии, callbacks и аудита, проверяемая версия не создаёт строку и не перезаписывает чужое решение, `delete` требует версии, отказ C5 до записи, отказ декларации до прав, классификация по SQLState |
| `RegisterKeyChangesTest` | 9 / 9 — прежняя команда смены ключа не сломана |
| `NomenclaturePricePersistenceTest` (Spring-контекст, реальная схема пилота) | 24 / 24, из них 4 новых: upsert цены, проверяемая версия и конфликт, удаление с версией и повтором, отдельное право на операцию (generic-запись её не выдаёт) |
| Регрессия регистров (валидатор, дескриптор, periodicity, reader, фильтры, slice spec, lifecycle) | 69 / 69 |
| Регрессия пилота и чтения (AccountingObjectPilotIT, R9 batch, формы списков, PilotRegisterListToolbarIT, PilotFixtureWriteBoundaryIT, PilotSecurityQueryMeasurementIT, InformationRegisterRecorderPersistenceTest, NomenclaturePriceFormTest, NomenclaturePriceRegisterOracleTest) | 80 / 80 |
| Граница приложение→платформа (`PlatformPublicSurfaceTest`) | Новые публичные типы Р11 объявлены в reviewed-реестре ролями API; остальные падения класса — из параллельной работы UniDr/reportstudio, к Р11 не относятся |
| `RegisterKeyWritesPostgresIT` + спайк на изолированной базе | 24 / 24 (20 спайк + 4 реализация), failures 0, errors 0, skipped 0, BUILD SUCCESS — [протокол](../../plans/register-r11-postgresql-implementation-20261013.log) |

Итог H2 — 194 сценария без повторов между классами: 12 (новая команда) + 9 (прежняя команда смены
ключа) + 69 (регистры) + 80 (пилот и чтение) + 24 (пилот цен).

На реальном сервере проверено именно то, чего H2 подтвердить не может: проигранная гонка вставки
сходится в `UPDATED` только потому, что каждая попытка — новая транзакция (после `23505`
транзакция PostgreSQL непригодна, 25P02); проверяемая версия проигравшего приходит к строковой
блокировке и получает `VERSION_CONFLICT`, а не перезапись; откатанная попытка не оставляет ни
строки, ни записи аудита.

H2-классы и `RegisterKeyWritesPostgresIT` без изолированной базы инертны: PostgreSQL-класс
пропускается по отсутствию переменной окружения и в обычном прогоне не влияет.

## Что осталось и чем этот пакет не является

- **Наборы ключей (`setAll`).** Контракт ADR-0023 описывает запись набора ключей одной транзакцией
  с каноническим порядком захвата; реализация этого пакета — одиночный ключ. Потребителя с
  настоящим набором пока нет, поэтому батч не вводится заранее; канонический порядок в спайке
  подтверждён.
- **Идемпотентность интеграций.** Стабильный `commandId` для outbox/дедупликации вызывающей стороны
  в команде не появился.
- **Сериализационные конфликты.** `40001` и `40P01` классифицируются, но сценарий на явном уровне
  изоляции `SERIALIZABLE` не прогонялся.
- **Другие серверные СУБД.** Переносимость не заявляется без собственных измерений.
- **Интерфейс пилота.** Права на `SET_KEY` / `DELETE_KEY` назначает администратор: регистрация
  операции грантов не выдаёт. Отдельного действия «записать цену на дату» в форме пока нет:
  прикладной путь — `NomenclaturePriceService`, а карточка цены по-прежнему пишет ресурс через
  canonical CRUD, что контракт не запрещает.
- **REST-канал.** Публикация команды в REST — отдельный пакет.
- **API baseline.** Граница приложение→платформа прошла через reviewed-реестр:
  `src/test/resources/platform-public-surface.txt` получил две строки API
  (`RegisterKeyWrites`, `RegisterKeyWritePermissions`), потому что приложение их называет. Роль
  назначена осознанно; API-baseline'ы `platform-*` модулей не менялись — новых платформенных
  типов пакет регистра не добавил. Сам register-пакет (43 файла) остаётся прикладным: отдельного
  `platform-register` нет (Р4/ADR-0013), а ссылок на `org.ip` в нём нет вовсе — перенос в модуль
  возможен вместе с решением Р10/Р13, не раньше.

## Источники

- [ADR-0023: запись регистра по ключу](../decisions/ADR-0023-register-key-write.md).
- [Спайк и сценарии Р11](register-r11-key-write-spike-20261012.md).
- [PostgreSQL-прогон реализации](../../plans/register-r11-postgresql-implementation-20261013.log).
- [Скрипт изолированной базы](../../../scripts/register-r11-postgresql.ps1).
- [План информационных регистров, §15](../information-register-development-plan.md).
