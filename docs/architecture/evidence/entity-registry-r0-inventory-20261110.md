# Реестр сущностей — R0: контракт и ограниченное измерение

Дата протокола: 2026-11-10. Этап R0 [плана, редакция 2](../../plans/entity-registry-ux-plan.md).
Новая ветка Java UI ещё не реализована и не включена. R1–R7 остаются следующими этапами.

## Зафиксированный контракт

- [ADR-0010 §13](../decisions/ADR-0010-explorer-type-address.md): граница платформы/приложения,
  общий каталог и refresh, UI-session, карта живых карточек, применение явного места до
  активации, освобождение закрытой карточки, адрес и история, сохранение ввода редакторов.
- [Матрица переходов](../../plans/entity-registry-entry-matrix.md): нормативные entry id,
  источники перехода, push/replace и dirty-guard; исполнение проверяется на R3/R5.
- [Визуальная основа](../../plans/mockups/entity-registry-states.md): шесть состояний.
  Проверены SHA-256 обоих HTML и шести PNG по `entity-registry-states.json`: 8/8 совпали.
  Широкий реестр и узкий обзор просмотрены визуально. Уточнения редакции 2 (устранение
  дублей, спокойные счётчики, дополнительные опции, заголовки, доступное пояснение PLAIN)
  закреплены в ADR; новые снимки реализации обязательны на R2.

## Воспроизведение

Из корня рабочей копии в PowerShell:

```powershell
& ./scripts/measure-entity-explorer-catalog.ps1
```

Скрипт принимает `JavaHome`, `Maven`, `LocalRepository`, `Output`. Он разрешает тестовый
classpath Maven в offline-режиме, компилирует транзитивно необходимые текущие исходники
через javac sourcepath в отдельный output, затем один раз строит `ExplorerSnapshot`.
Старые application target/classes и старый standalone-сканер измерения не используются.
Логи зависимостей, компиляции и исполнения: `outputs/entity-registry-r0/`.

[JSON результата](entity-registry-r0-inventory-20261110.json) содержит время UTC, HEAD
`4b0ff3bb424b657ff8560e38112504ca9d55c004`, полный porcelain-статус dirty checkout перед
измерением, точную команду с параметрами, SHA-256 обоих измерительных исходников,
набор managed-типов, их экспозиции и присутствие в Explorer, все записи снимка,
подтверждённые секции, причины отказов и доступность диагностики.
Статус включает уже имевшиеся изменения других задач; измерение относится к этой рабочей
копии, а не к чистому HEAD. Поле `measuredAtUtc` — источник точного времени запуска.

## Область и результат

**Ограниченная fixture реальных моделей; не полный production persistence unit.**
Набор из восьми типов явно задан в `EntityRegistryInventory.java`. Все владельцы фактов
используют один `ManagedEntityTypes`: `ManagedEntityCatalog`, `SectionMetadataRegistry`,
`EntityDescriptorCatalog`, `ReferenceIndex`, нумерация, подсистемы и production-конструктор
`EntitySummaryAssembler`. Для `NomenclaturePrice` подключён настоящий валидируемый
`RegisterRegistry` / `RegisterClassificationContributor`. Сущности не сканируются.

| Множество | Число |
|---|---:|
| Managed-типы fixture | 8 |
| Записи Explorer | 6 |
| Самостоятельные roots | 5 |
| Managed owned-строки | 2 |
| Owned-записи Explorer | 1 |
| Managed служебные типы | 1 |
| Служебные записи Explorer | 0 |
| Неизвестная экспозиция записей | 0 |
| Попытки сборки сводки | 6 |
| Отказы сборки сводки | 0 |
| Общие сообщения | 1 |

Корни: `GroupNom`, `Nomenclature`, `ReceivingDocument`, `NomenclaturePrice`, `SklNomOpa`.
`NomAttributeValue` присутствует как owned-запись. `ReceivingDocumentItem` не входит в
entries, но доступен через подтверждённую секцию `ReceivingDocument`; owned-записи нельзя
считать простым вычитанием числа классов со связями из числа roots. `SettingValue` имеет
экспозицию `INTERNAL_STORE`, но не входит в entries этого сборщика. Эти результаты
не являются основанием расширять инвентарь Explorer до всех managed JPA-классов.

FormRegistry пуст, ключи маршрутов выведены платформой; прикладные регистрации форм и
alias не подключены. Стартовая диагностика, lifecycle, actions, fetch inspection и RLS
не подключены. Единственное общее сообщение — `DIAGNOSTICS_UNAVAILABLE`; у всех записей
`countsKnown=false`, значения errors/warnings в JSON — `null`. Это неизвестные счётчики,
а не подтверждённое отсутствие ошибок. Ноль отказов означает лишь успешную сборку шести
сводок в этой fixture. Подчинённые регистры и recorder metamodel здесь не проверяются.
Реальная БД, приложение и его production-контекст не запускались.

## Проверки и ограничения

- Измерительный скрипт: успешная компиляция текущего замыкания исходников и исполнение;
  JSON прочитан обратно, состав, экспозиции, секции и неизвестная диагностика сверены.
- Проверка визуального манифеста: 8/8 SHA-256 совпали.
- `scripts/test-entity-explorer-structure.ps1`: запуск выполнен, до тестов не дошёл.
  Компиляция останавливается в существующем `EntityDraftEditorView.java:942`:
  `the switch expression does not cover all possible input values` для
  `switch (diagnostic.code())`. Это вне изменений R0; код редактора не менялся.
  Прохождение общего набора тестов не заявляется.
- R0 не меняет старый UI, платформенные публичные API, CSS, навигацию и ввод редакторов.
  Следующий этап R1 выделяет общий каталог и refresh-протокол с прежним представлением;
  его целевые тесты должны пройти после устранения ошибки компиляции рабочего checkout.
