# Инвентаризация текущего save/events WIP

- Снимок: 2026-09-11
- Ветка: `main`
- Статус: B3 cleanup завершён; документ сохраняет инвентарь продолжающихся направлений этапа B
- Персональный владелец: не назначен в репозитории

Под «владельцем» ниже понимается архитектурный слой, отвечающий за контракт. Файлы и изменения рабочего дерева принадлежат пользователю; этот документ не означает их автоматического принятия или коммита.

| Срез | Архитектурный владелец | Основные файлы | Назначение | Условие принятия |
|---|---|---|---|---|
| Section presence | Platform forms | `SectionPresence`, `SectionPayload`, `ItemSectionHost`, `ItemForm` | Отличать скрытую секцию от подключённой пустой | API invariants и hidden/empty tests |
| Event contracts | Platform events | `org.ipro.events.*`, auto-configuration | Saving/deleting и after-commit lifecycle без Vaadin и field-audit coupling | B2 contract, ordering, rollback и after-commit tests |
| Entity lifecycle DX | Platform lifecycle/application boundary | `EntityLifecycle`, context contracts, `EntityLifecycleRegistry`, CRUD bases, `MetadataDrivenAggregateSaveService` | Единая typed точка `ПередЗаписью`/`ПриИзменении`/aggregate lifecycle с fail-fast `0..1` handler на entity | Registry duplicate/type tests, save/update boundary callbacks и parity tests; Explorer/scaffolder остаются |
| ReceivingDocument rules | Application document layer | `ReceivingDocumentLifecycle`, metadata-driven aggregate service | Server-side правила в едином typed lifecycle | Нет дублирования и обхода generic/service путём |
| PrdSpec aggregate | Application document layer | `MetadataDrivenAggregateSaveService`, section metadata, `PrdSpecLifecycle` | Atomic header + attached materials/operations без save-specific boilerplate | Rollback, `ABSENT`, empty section и RLS tests |
| Form save dispatch | Platform form/application boundary | `ItemFormSaveDispatcher`, `MetadataDrivenAggregateSaveService`, `ItemFormSaveHandlerRegistry` | Стандартный атомарный save без прикладной обвязки; custom handler только для исключений | Нет domain type switch, registry отклоняет ambiguity, generic two-phase fallback запрещён, стандартные пилоты используют default engine |
| CRUD event integration | Platform persistence | `AbstractBaseService`, `ValidatedJpaCrudService`, `GenericOwnedSectionService` | Подключение lifecycle к generic persistence и owned-section delete | Одинаковое поведение и отсутствие двойных events; root delete очищает owned rows |
| Regression tests | Соответствующий слой | `src/test/java/org/ip/application/document/**`, `org/ipro/events/**`, form tests | Доказательство нового контракта | Полный `mvn verify` зелёный |

## Уже закрыто в B3 cleanup

- переходные pilot adapters/commands/results/use cases;
- constructor-only application section services/repositories;
- ручной delete cascade в `ReceivingDocumentService`.

## Не входит в этот WIP

- FetchPlan/InstanceName;
- fail-closed RLS;
- resource permissions;
- Maven module extraction;
- новый ERP-документ;
- новые возможности Report Studio.

Эти направления не должны добавляться в текущий diff до стабилизации этапов A и B.
