# Архитектурная документация GitVaa

Этот каталог содержит версионируемые источники архитектурных решений. Локальные заметки и экспериментальные планы из `docs/plans/` не являются нормативными и остаются вне Git.

## Источники истины

| Документ | Назначение |
|---|---|
| [`../../JMIX_GitVaa_Roadmap_v2.md`](../../JMIX_GitVaa_Roadmap_v2.md) | Текущие приоритеты, этапы и Definition of Done |
| [`appdev-first-audit.md`](appdev-first-audit.md) | Living audit нарушений AppDev-first, artifact budgets и карта их устранения по этапам |
| [`c3-fetchplan-instance-name-plan.md`](c3-fetchplan-instance-name-plan.md) | Детальный план пилота C3: FetchPlan, InstanceName, миграция ручного fetch-кода и приёмочные gates |
| [`c4-data-access-facade-plan.md`](c4-data-access-facade-plan.md) | Детальный план C4: type capabilities, canonical data path, standard CRUD/search defaults, effective metadata, миграция и hardening |
| [`c4-inventory.md`](c4-inventory.md) | C4.0: проверяемая таксономия 37 persistence types, классификация service/repository/base слоя, consumers, baseline и пилоты |
| [`d-stage-fast-close-report-return-plan.md`](d-stage-fast-close-report-return-plan.md) | Ускоренное закрытие D-core с временно открытым D3.7 и обязательным возвратом к report add-on |
| [`d-r-report-addon-inventory.md`](d-r-report-addon-inventory.md) | Статический инвентарь исходников, регистраций, ресурсов и внешних зависимостей для D-R; фиксирует app-owned границу JR persistence |
| [`f-integration-erp-plan.md`](f-integration-erp-plan.md) | Принятый порядок будущих работ E1/F: capability-aware UI, внешние read models, владельцы, неизменяемые композиции, бизнес-факты и ledger |
| [`security-channel-matrix.md`](security-channel-matrix.md) | C1: карта каналов доступа, владельцев enforcement и допустимых privileged bypass |
| [`decisions/ADR-0001-platform-roadmap-stages.md`](decisions/ADR-0001-platform-roadmap-stages.md) | Принятое разделение Engineering Baseline, RLS enforcement и физической модульности |
| [`decisions/ADR-0002-lifecycle-event-semantics.md`](decisions/ADR-0002-lifecycle-event-semantics.md) | Семантика Saving/Saved/Changed/Deleting/Deleted и граница транзакции |
| [`decisions/ADR-0003-developer-experience-and-default-aggregate-save.md`](decisions/ADR-0003-developer-experience-and-default-aggregate-save.md) | Постулат AppDev-first, metadata-driven default save и custom override policy |
| [`decisions/ADR-0004-semantic-entity-archetypes.md`](decisions/ADR-0004-semantic-entity-archetypes.md) | Семантические типы сущностей, стандартные базовые классы, numbering policies и независимые owned sections |
| [`decisions/ADR-0005-entity-lifecycle-and-application-behavior.md`](decisions/ADR-0005-entity-lifecycle-and-application-behavior.md) | Единая точка `EntityLifecycle<T>` для прикладных callbacks, границы operations/queries/UI и feature-package convention |
| [`decisions/ADR-0006-fetchplan-and-instance-name.md`](decisions/ADR-0006-fetchplan-and-instance-name.md) | Сценарные FetchPlan и единый InstanceName: границы API/SPI/internal, `ManagedEntityCatalog` как единственный источник сущностей |
| [`decisions/ADR-0007-canonical-data-access-path.md`](decisions/ADR-0007-canonical-data-access-path.md) | C4: canonical `EntityDataAccess`, таксономия экспозиции типов, судьба generic CRUD-баз, search semantics, telemetry policy и compatibility milestones |
| [`decisions/ADR-0008-interned-entities.md`](decisions/ADR-0008-interned-entities.md) | Интернированные сущности: натуральный ключ как capability, поведенческий маркер `InternedEntity`, один механизм `NaturalKeyCreateSupport` и граница с обычным справочником |
| [`decisions/ADR-0009-deep-links.md`](decisions/ADR-0009-deep-links.md) | E2: адрес формы как контракт — host и синхронизация истории, грамматика `/records`/`/lists`, вывод alias и baseline, linkability, матрица результата открытия и границы API |
| [`decisions/ADR-0010-explorer-type-address.md`](decisions/ADR-0010-explorer-type-address.md) | E3.0: адрес типа как published-ключ, отдельная ветка host'а, гейт роли с неразличимым отказом, резерв первых сегментов и безадресность типа без ключа |
| [`e2-deep-link-host-spike.md`](e2-deep-link-host-spike.md) | Измеренный результат E2.0: route templates на текущей оболочке, поведение Back/Forward при `pushState`, сохранение `Workspace` и границы проверенного |
| [`actions-guide.md`](actions-guide.md) | Руководство прикладного разработчика по декларативным действиям форм (E1): два примера, требования, suppression, проверки и антипримеры |
| [`deep-links-guide.md`](deep-links-guide.md) | Руководство прикладного разработчика по глубоким ссылкам (E2): когда адрес появляется сам, как объявить ключ и прежние адреса, как получить ссылку программно и что из этого хрупко |
| [`status/current-baseline.md`](status/current-baseline.md) | Проверяемое состояние сборки и этапов |
| [`status/wip-inventory.md`](status/wip-inventory.md) | Состав незавершённого save/events среза |
| [`build/local-dependency-workspace.md`](build/local-dependency-workspace.md) | Воспроизводимая сборка соседних fork/SNAPSHOT-проектов |
| [`build/local-configuration.md`](build/local-configuration.md) | Простая локальная конфигурация подключений вне Git и JAR |
| [`discussions/README.md`](discussions/README.md) | Граница между обсуждениями и принятыми решениями |

## Правила

1. Roadmap отвечает на вопрос «что и в каком порядке делаем».
2. ADR отвечает на вопрос «какое решение принято и почему».
3. Status содержит только проверяемые факты текущего checkout.
4. Discussion не является разрешением на изменение кода и не переопределяет ADR/roadmap.
5. Закрытые вопросы удаляются из roadmap; история решения остаётся в ADR или Git.
6. Архитектурный этап считается завершённым только после выполнения его Definition of Done.
7. Изменение стандартного платформенного сценария проходит AppDev-first gate:
   инфраструктурная сложность не переносится в обязательный прикладной boilerplate.

## Проверка baseline

Проверка только приложения при уже установленных локальных зависимостях:

```text
mvn verify
```

Полная локальная проверка sibling-исходников и приложения через Maven из IntelliJ IDEA:

```powershell
.\scripts\bootstrap-local-dependencies.ps1
```

Maven Wrapper отложен по решению владельца. Следующий инфраструктурный gate — CI на чистом checkout с versioned/released внутренними зависимостями.
