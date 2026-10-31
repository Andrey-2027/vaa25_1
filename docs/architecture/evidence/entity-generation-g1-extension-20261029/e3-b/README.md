# E3.B — P8 и стандартные формы подчинённого справочника

Дата: 2026-10-30. E3.B выполнен компонентно. Полная actual owner C2/D2/runtime
приёмка E3.C и браузерная матрица E5.B остаются открытыми; gate E3 целиком не закрыт.

## Реализация

P8 получил отдельную панель «Владелец справочника»: включение capability,
подтверждённый existing CATALOG target, Java field name, AUTO/EXPLICIT SQL column и
label. Включение owner у standard-catalog-v2 явно переводит draft в schemaVersion=2,
сохраняя UUID и subject fields; удаление owner не понижает envelope автоматически.
У legacy catalog-v1 настройка не предлагается, рецепт не переосмысляется и JSON
остаётся экспортируемым. Импортированный invalid document owner виден и может быть
явно удалён. Недоступный target сохраняется с причиной; owner.* diagnostics идут
к отдельным контролам без искусственного fieldId.

Owner edits инвалидируют checked/preview и участвуют в dirty/revision. Контролы
сохраняются при переходах в subject inspector. Late events от удалённого owner
panel/reset не меняют новый owner; доступ проверяется при каждом событии. JSON и
Java preview используют E3.A codec/builder, UI не строит association самостоятельно.

В стандартном ItemForm владелец выбирается в create-mode, блокируется при загрузке
persisted entity и после applyPersistedEntity. Structural FormBinding lock переживает
form-wide readOnly toggles и исключает запись подменённого значения из компонента.
Остальные поля подчиняются обычному access/readOnly режиму формы. Обычная ссылка
без @CatalogOwner не становится immutable по имени owner/parent.

Opening owner-context задаётся существующим механизмом fixed filters:
`contextFilters = {counterparty: existingOwner}` для списка, либо filters того же
вида для стандартного selection assembler. Требуется объект объявленного target с
Long ID; unsaved/wrong-type values отклоняются. Fixed owner не удаляется очисткой
интерактивных фильтров и показывается отдельной подписью «Владелец: …».
ListForm.getRequiredContextValues передаёт opening owner в initialValues карточки,
даже если отдельная required context panel не настроена. В карточке он подставлен
до чтения bindings, не делает форму dirty и заблокирован с самого начала.

ItemForm открывает серверный CatalogOwnerContext только на время save-handler.
Смена/очистка opening owner и подстановка сущности другого владельца отклоняются;
контекст закрывается и после Failure. Это не выдаёт grants и не заменяет guards
E3.R. Все запросы остаются в стандартных BaseService/canonical read/write путях.

SelectionFormAssembler теперь сохраняет fixed filters в самом SelectionForm:
перестроение интерактивной панели не теряет исходный owner. Смена fixed filters
обновляет owner caption, очистка снимает старую specification, смена фильтра
сбрасывает прежнюю selection. Варианты форм продолжают использовать общий assembler.

Explorer StructureFacts переносит resolved CatalogOwnerDescriptor отдельно от
owned sections. Страница «Родитель / Владельцы» показывает property → target,
обязательность/immutability, отсутствие наследования прав и источник @CatalogOwner.
Недоступные facts не трактуются как отсутствие владельца. Новый CatalogOwnerForms
— INTERNAL bridge к существующему runtime resolver, а не новая owner policy;
registry surface и module composition gate обновлены до 140 production types.

## Проверки

Итоговый run: `outputs/entity-generation-g1-extension/e1-ab/e3b-owner-ui-r9/`.

| Проверка | Результат |
| --- | --- |
| Core/JSON/runtime bridge/platform forms/P8/Explorer | 38 suites, 254 tests; errors/failures/skips=0 |
| Owner P8 | 6 tests, входят в общий run |
| Owner ItemForm | 6 tests, входят в общий run |
| Owner list/selection | 2 tests, входят в общий run; проверена actual grid specification |
| Owner Explorer projection | 1 test; panel дополнительно проверен вместе с 14 Explorer tests |
| Module composition | 8 tests, входят в общий run |
| Server CatalogOwnerWriteIT | 13 tests, отдельный H2 run; errors/failures/skips=0 |
| Server CatalogOwnerAggregateTest | 3 tests, отдельный run; errors/failures/skips=0 |
| Source contract checks | 33/33, отдельно |

Регрессия проверяет create/persisted/post-save locks, readOnly toggles, preset clean
state, защиту от программной подмены UI value, scoped save context, ограничения
initialValues, ordinary references без capability, потерю facts, legacy JSON,
отзыв P8-доступа и адресацию owner.*. Повторные diagnostic runs не суммируются.
Серверные tests повторяют trusted persisted-state и aggregate guards E3.R; это не
actual generated owner consumer E3.C. Браузер не открывался.

Maven работал offline, current platform-vaadin подключён в reactor вместе с
core/JSON/runtime/app. Shared SNAPSHOT cache не переустанавливался. Во время
повторных прогонов sandbox блокировал javac-чтение отдельных dependency JAR;
итоговый run выполнен с доступом к существующему cache, без изменения зависимостей.
Current source fingerprint platform-vaadin обновлён в local-dependencies.json.

## Evidence и воспроизведение

- [Результаты](test-results.json), [reactor log](tests.log), [XML](reports/).
- [Серверные результаты](server-test-results.json), [log](server-guards.log), [XML](server-reports/).
- [Source contract checks](contract-results.json), [fingerprints](source-fingerprints.json).

```powershell
pwsh -NoProfile -File scripts/test-entity-generation-g1-document.ps1 -RunId <new-run> -IncludeOwnerForms -LocalRepository C:/Users/strim/.m2/repository
```

Для серверного повтора: offline Maven по platform-core/pom.xml с
`-Dtest=CatalogOwnerWriteIT,CatalogOwnerAggregateTest`; в данном окружении Mockito
запускался через `-DargLine=-javaagent:<repository>/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar`.
Launcher сохраняет историческое имя document/E1, но с IncludeOwnerForms проверяет
текущий E3.B scope. До runtime-запуска основного приложения platform-vaadin должен
подключаться из текущего reactor либо штатного reviewed bootstrap build.
