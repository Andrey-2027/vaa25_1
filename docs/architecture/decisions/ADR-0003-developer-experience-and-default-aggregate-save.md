# ADR-0003: AppDev-first и default aggregate save

- Статус: принято
- Дата: 2026-09-11
- Область: platform defaults, application API, aggregate save, roadmap gates

## Контекст

GitVaa должен быть не только технически строгой платформой, но и сокращать объём
механической работы прикладного программиста. Переходная реализация атомарного
сохранения доказала контракт на `ReceivingDocument` и `PrdSpec` через отдельные
commands, results, use cases и form adapters. Если сделать такую обвязку обязательной
для каждого обычного документа, платформа переложит собственную транзакционную и UI
механику в application layer.

Одновременно простой generic path `save header -> commitTableSections` не подходит:
он допускает частичное сохранение и не выражает различие `ABSENT`, `ATTACHED + empty`
и `ATTACHED + rows` одной транзакционной операцией.

## Постулат

Короткое имя принципа — **AppDev-first**.

Архитектурное изменение не должно перекладывать платформенную сложность на
прикладного программиста. Новый обязательный прикладной класс или фрагмент
конфигурации допустим только когда он выражает предметную либо security-семантику,
а не повторяет стандартную механику платформы.

Постулат не ослабляет correctness, транзакционность или security. Напротив, их
безопасное применение по умолчанию является обязанностью платформы.

## Решение

1. Стандартная entity без табличных секций сохраняется generic entity path.
2. Стандартный агрегат с metadata-declared owned mutable sections сохраняется одним
   metadata-driven platform service в одной транзакции.
3. Платформа сама выполняет harvest attached sections, structural/authoritative
   validation, header/section persistence, lifecycle events, сбор результата,
   применение persisted state к форме и `commitSnapshot`.
4. Vaadin `ItemForm` остаётся за UI-границей. Транзакционный service получает
   platform request/descriptors, не UI-компонент.
5. Persistence выполняется через platform services/policies, а не прямой repository
   access, чтобы сохранить RLS, validation, numbering и event contracts.
6. `ItemFormSaveHandler` registry является SPI для custom overrides. Отдельный handler
   не требуется для стандартного документа.
7. Два custom handler, поддерживающие один класс, являются configuration error;
   порядок Spring beans не определяет скрытый приоритет.
8. Неподдерживаемые режимы секций не получают неявный `replaceAll` fallback. Для
   `write-through`, `immutable`, `create-once` и сложного workflow требуется явный
   metadata contract либо typed custom use case.
9. Внутренние erased descriptors допустимы как implementation detail платформы, но
   `Map<Class<?>, List<?>>` не становится предметным API приложения.
10. Каждый roadmap stage и PR проходит DX-review: оцениваются новые обязательные
    классы, конфигурация и ручные действия типового прикладного сценария.

В B3.4 этот контракт материализован в `MetadataDrivenAggregateSaveService` и
`MetadataDrivenItemFormSaveAdapter`: platform request содержит только фактически
attached sections, а root/sections/events проходят одну transaction boundary. В
B3.5–B3.10 `ItemFormSaveHandlerRegistry` подключён как optional override SPI; typed
handlers контрольных пилотов выведены из Spring registry, и оба пилота используют
default engine. В B3.11 переходные adapters/use cases и пустая section wiring удалены
после behavioural parity; custom handlers и предметные listeners сохранены.

## Последствия

Положительные:

- новый стандартный документ не требует save-specific handler/adapter/command/result/use-case;
- атомарность, section presence, RLS и lifecycle применяются единообразно;
- custom code содержит предметные отличия, а не инфраструктурный boilerplate;
- registry сохраняет расширяемость без domain type switch в dispatcher;
- reference application и tests могут измеримо подтверждать минимальный setup.

Ограничения:

- внутренний platform save coordinator сложнее прежнего dispatcher и требует сильных
  integration/architecture tests;
- legacy typed adapters контрольных пилотов удалены после behavioural parity;
- режим секции нельзя выводить из UI-признака `readOnly`;
- при неподдерживаемой семантике платформа обязана fail-fast, даже если это требует
  явного custom extension.

## Рассмотренные альтернативы

### Обязательный handler и adapter на каждый документ

Отклонено как platform default: решение сохраняет типобезопасность локально, но
увеличивает прикладной boilerplate и делает стандартную возможность ручной.

### Оставить generic two-phase save

Отклонено: сохранение шапки и строк не имеет гарантированной общей транзакции и
может оставить частично сохранённый агрегат.

### Сразу построить универсальный business use case для всех агрегатов

Отклонено: платформа обобщает только доказанную механику owned/replace-all sections.
Предметные workflow, проведение, движения и интеграционные сценарии остаются typed.

## Связь с другими решениями

ADR уточняет объём этапа B из ADR-0001 и опирается на lifecycle contract ADR-0002.
Семантические entity archetypes, стандартные базовые классы и независимый контракт
owned sections определены в ADR-0004.
Полный living audit нарушений и поэтапный remediation plan ведётся в
`docs/architecture/appdev-first-audit.md`.
