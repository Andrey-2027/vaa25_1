# ADR-0021: отдельная команда изменения ключа записи регистра

Дата: 2026-10-09. Статус: принято для второго backend пакета Р8.

## Что было и почему требуется изменение

`PeriodicRegisterEntryBase` и `PeriodicRecordedRegisterEntryBase` объявляют период
с `updatable=false`; измерения обоих пилотов тоже были исключены из UPDATE.
`RegisterMetadataValidator.checkKeyField` запрещал любое writable поле ключа.
`NomenclaturePriceLifecycle.beforeUpdate` отклонял изменение ключа в canonical
UPDATE; `RecordedRegisterEntryWriteGuard` закрывал все автономные записи
подчинённого регистра. Это сохраняло прежнюю неизменяемость, но не позволяло
исправить измерения / период с сохранением ID.

Замена набора через `RecorderSetWriter` удаляет старые строки и создаёт новые,
а процедура `RegisterRecords` из ADR-0020 имеет полномочия только внутри onSave
документа. Они не подходят для согласованного ручного исправления без изменения
документа. Общий `executeNamed(..., Consumer)` принимает произвольную мутацию;
сам по себе он не ограничивает команду ключевыми полями и не координируется
с заменой подчинённого набора.

## Рассмотренные варианты

1. Remove/persist с новым ID: нарушает сохранение идентичности строки.
2. Пересохранение регистратора: изменяет документ и может переформировать весь набор.
3. Сделать ключ writable и оставить только lifecycle-проверку: managed dirty flush
   обходит canonical lifecycle и получает неконтролируемое изменение ключа.
4. Отдельная команда, явная capability и проверка Hibernate snapshots: обеспечивает
   сохранение строки и ограничения обычных путей. Выбран этот вариант.

## Контракт

`RegisterKeyChanges.change(type, id, expectedVersion, dimensions, period)` принимает
полный набор измерений. Для to-one ссылки передаётся Long ID; тип берётся из
RegisterDescriptor, объект загружается canonical detail с ENTITY/RLS. По ID тип
не угадывается. Basic значения проверяются по объявленному Java-типу. Пустое
измерение / отсутствующий период периодического регистра — отказ.

Пример прикладного вызова после назначения прав:

```java
keyChanges.change(NomenclatureAccountingObject.class, rowId, rowVersion,
    Map.of("workshop", workshopId, "nomenclature", nomenclatureId), period);
```

Команда использует REQUIRED TransactionTemplate. Вложенный отказ делает общую
транзакцию rollback-only даже при перехвате исключения вызывающим кодом.
Исходная строка читается заново через EntityDataAccess.detail, обновляется под
PESSIMISTIC_WRITE и проверяется после refresh. Ожидаемая версия сравнивается
с сохранённой версией. При изменении увеличивается версия строки, ID сохраняется.
Ресурсы / прочие реквизиты, recorder / lineNumber / recordingOrder не изменяются.

Период нормализуется по периодичности до сравнения. Полностью совпавший ключ
не вызывает UPDATE, callbacks, аудит и продвижение версии. Доступность ссылок
и права проверяются также при таком вызове.

У независимого регистра остаётся UNIQUE по измерениям и периоду. SQLState 23505
даёт понятный отказ; строки не объединяются и не заменяются. У подчинённого
сохраняется UNIQUE recorder + lineNumber; совпадение измерений / периода само
по себе допустимо. Нового бизнес-индекса и скрытого upsert нет.

## Полномочия и граница flush

- `keyChangeAllowed` default-off; включён только у NomenclaturePrice и
  NomenclatureAccountingObject. Тип вне RegisterRegistry не приобретает capability.
- Для каждого opt-in типа зарегистрирован ACTION с EXECUTE:
  `REGISTER:<FQN сущности>:CHANGE_KEY`. Регистрация не выдаёт гранты.
- Требуются именованное действие, ENTITY READ, существующая ENTITY UPDATE
  и RLS изменения старого / нового состояния. Наличие ACTION либо RLS wildcard
  не заменяет отдельное C5 UPDATE. Прав на документ не требуется. ATTR нет.
- `RegisterKeyChangePermissions.canChangeKey(type, row)` использует те же C5
  решения и проверку текущих RLS write values для будущей кнопки. Новый ключ
  полностью проверяется командой; UI не является authority.
- Русские названия действий объявлены в RegisterKeyPermissionTitles через
  существующий NamedOperationOwner и видны в администрировании прав.
- У opt-in пилотов writable только измерения и переопределённый период.
  Базовые mapping по умолчанию и технические поля подчинённого остаются immutable.
  Validator учитывает AttributeOverride базовых полей; installer дополнительно
  сверяет фактическую property updateability Hibernate с capability.
- `RegisterKeyChangeScope` нельзя открыть из приложения. Публична только проверка
  текущего разрешения для lifecycle; выдача scope package-private, constructor private.
- `RegisterKeyWriteGuardInstaller` устанавливает обязательный PRE_UPDATE listener
  отдельно от телеметрии. Old/new snapshots сравниваются по значениям / ID.
  Разрешение привязано к экземпляру, ID, Session, исходной версии и точному
  старому / новому ключу. Оно не разрешает менять ресурсы или другие строки.
  Recorder / lineNumber / recordingOrder проверяются независимо от writer scope.
- RecordedRegisterEntryWriteGuard принимает этот scope только в PreUpdate.
  Create/delete продолжают требовать процедуру владельца. Обычный price lifecycle
  сохраняет отказ для generic UPDATE. RLS bypass не выключает проверку ключа.

Серверные JPA writes через canonical / обычные формы / REST / импорт и managed
dirty checking закрыты этим механизмом. Произвольный native SQL / bulk DML
не является поддерживаемым API записи регистра; Hibernate entity events для
таких запросов не работают. Редактор отчётных запросов остаётся read-only.

## Координация и lifecycle

У подчинённого регистра команда берёт тот же RecorderOrderClock mutex, что
clear / add / delete / replacement / удаление документа. Общий helper —
RegisterWriteMutex; lock не продвигает recordingOrder. Документ не загружается
и не блокируется. Смена ключа и обработка набора сериализованы в одном регистре.
Независимый регистр блокирует выбранную строку; разные строки с одним новым
ключом координирует физический UNIQUE.

Вызываются existing before-save event, beforeSave / beforeUpdate и onSave
**строки**; отдельный original snapshot существует. Документный lifecycle
не вызывается и owner write authority не выдаётся. Flush scope проверяет
целостность строки также после onSave. Changed публикуется только после commit.
Прикладной обработчик может отказать в переносе; изменение ресурсов / ключа
сверх запрошенного откатывается. При следующем сохранении документа приложение
по-прежнему самостоятельно решает, переформировать ли набор (ADR-0020).

## Аудит и зависимости

После принятого UPDATE команда явно пишет FieldChangeRecord через existing
EventSink.acceptFieldChangeDurable. Запись присоединяется к бизнес-транзакции:
userId, entity/ID, traceId и old/new ключевых полей. Payload дополнен operation
`change-register-key:<entityName>`; прежняя форма JSON списка изменений сохранена.
Новый store / таблица не вводятся. При отключённом DB-журнале действует штатный
noop EventSink; долговременного аудита в такой конфигурации нет.
При выключенной телеметрии EventSink отсутствует и передаётся как Optional.empty;
команда и обязательный guard продолжают работать. Это штатная отключаемость
телеметрии, а не выдача дополнительных прав или отключение защиты записи.

Общий register слой зависит от telemetry API, data, lifecycle и RLS, не от
Vaadin, отчётов или классов приложения. Package edge внесён явно. Прикладному
lifecycle доступны только readonly scope и permission identity; public-surface
реестр обновлён для этих потребителей. Публичный command API будет отражён в
реестре потребления приложения при подключении ListForm.

## Последствия / оставшаяся работа

Структура таблиц регистров не меняется, миграция ключей и существующих строк
не требуется. Новые ACTION назначаются явно; прежние пользователи автоматически
не приобретают возможность смены ключа. Неизменяемые сущности вне регистров
сохраняют свои ограничения. Исключение дополняет ADR-0013 и ADR-0015 только для opt-in.

Backend команда готовится к подключению общей ListForm; диалог и кнопка входят
в следующий пакет вместе с История / Срез последних и переносом pilot generation
в onSave. PostgreSQL гонки нового контура и пользовательская проба относятся
к завершению всей итерации Р8, а не к этому backend пакету.

## Проверяемые точки реализации / приёмка

- [Команда и pipeline](../../../src/main/java/org/ipro/register/RegisterKeyChanges.java#L66).
- [Регистрация и решение прав](../../../src/main/java/org/ipro/register/RegisterKeyChangePermissions.java#L36).
- [Установка guard и фактический mapping](../../../src/main/java/org/ipro/register/RegisterKeyWriteGuardInstaller.java#L23).
- [Snapshot authorization](../../../src/main/java/org/ipro/register/RegisterKeyChangeScope.java#L47).
- [Общий mutex](../../../src/main/java/org/ipro/register/RegisterWriteMutex.java#L14).
- [Wiring](../../../src/main/java/org/ipro/register/config/RegisterAutoConfiguration.java#L44).

Финальный H2 / architecture прогон: 139 / 139, BUILD SUCCESS, 2026-10-09 00:22:35.
Реальные обе модели, сохранение ID / ресурсов / technical fields, отсутствие
прав на документ, no-op, конфликт независимого ключа, допустимые coincident группы
подчинённого, stale version, direct flush, callbacks, реальный durable audit и его
rollback. Проверены regression writer procedures, декларации и package directions.
[Протокол](../../plans/r8-key-tests-final-20261009.log).

Полный общий public-surface gate имеет 5 прежних несогласованных checks, описанных
в §9 [плана](../../plans/information-register-r8-listform-key-commands.md).
Четыре structural public-surface проверки прошли в финальном прогоне.
UI и PostgreSQL race приёмка этого решения пока не выполнены.
