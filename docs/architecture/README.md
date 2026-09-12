# Архитектурная документация GitVaa

Этот каталог содержит версионируемые источники архитектурных решений. Локальные заметки и экспериментальные планы из `docs/plans/` не являются нормативными и остаются вне Git.

## Источники истины

| Документ | Назначение |
|---|---|
| [`../../JMIX_GitVaa_Roadmap_v2.md`](../../JMIX_GitVaa_Roadmap_v2.md) | Текущие приоритеты, этапы и Definition of Done |
| [`appdev-first-audit.md`](appdev-first-audit.md) | Living audit нарушений AppDev-first, artifact budgets и карта их устранения по этапам |
| [`security-channel-matrix.md`](security-channel-matrix.md) | C1: карта каналов доступа, владельцев enforcement и допустимых privileged bypass |
| [`decisions/ADR-0001-platform-roadmap-stages.md`](decisions/ADR-0001-platform-roadmap-stages.md) | Принятое разделение Engineering Baseline, RLS enforcement и физической модульности |
| [`decisions/ADR-0002-lifecycle-event-semantics.md`](decisions/ADR-0002-lifecycle-event-semantics.md) | Семантика Saving/Saved/Changed/Deleting/Deleted и граница транзакции |
| [`decisions/ADR-0003-developer-experience-and-default-aggregate-save.md`](decisions/ADR-0003-developer-experience-and-default-aggregate-save.md) | Постулат AppDev-first, metadata-driven default save и custom override policy |
| [`decisions/ADR-0004-semantic-entity-archetypes.md`](decisions/ADR-0004-semantic-entity-archetypes.md) | Семантические типы сущностей, стандартные базовые классы, numbering policies и независимые owned sections |
| [`decisions/ADR-0005-entity-lifecycle-and-application-behavior.md`](decisions/ADR-0005-entity-lifecycle-and-application-behavior.md) | Единая точка `EntityLifecycle<T>` для прикладных callbacks, границы operations/queries/UI и feature-package convention |
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
