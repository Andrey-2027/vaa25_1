# Сценарий «Матрица»

Пользовательский сценарий от 2026-10-29: стандартный справочник без табчастей и ссылок.
Класс `com.example.generated.Matrix`, таблица `matrix`; `code/name` наследуются.

| Подпись | Java-поле | Тип | Ограничение |
| --- | --- | --- | --- |
| ID внешней системы | idSys | Long | nullable |
| Обозначение АгК | draftAgk | String | nullable, max 150 |
| Активен | active | Boolean | nullable |

Обязательность собственных полей пользователь не задавал: все необязательные.
Все поля включены в форму, список и выбор. Без подсистемы, grants не генерируются.
Тип `Long` проверяется значениями больше Integer.MAX_VALUE и Long.MAX_VALUE.

## Воспроизведение

```powershell
./scripts/run-matrix-generation-scenario.ps1
```

Java 21, offline Maven и установленные platform/design JAR требуются как для существующих G1 gates.
Запуск создаёт новый `outputs/entity-generation-g1/matrix-20261029/run-<timestamp>/`;
существующие рабочие проекты и БД не затрагивает. Filesystem должен разрешать hard links.
`-SkipP8` допускается только после успешного P8-прогона с готовыми input и test XML;
основной воспроизводимый запуск выполняет P8 заново.

1. `EntityDraftMatrixScenarioTest` импортирует draft в P8 editor, выполняет preview/download и
   сохраняет реальные source/plan/receipt плюс текущие owner draft/context в `input/`.
2. Штатный C launcher проверяет этот source по независимому `ScenarioSpec`: Java compile,
   Spring startup, metadata, canonical save/read/update, constraints/access, platform JAR provenance.
3. `MatrixApply` заново читает текущие owner inputs, валидирует и вызывает публичный builder.
   Сравнивает с reviewed receipt и bytes P8 download; trusted GenerationPlan из JSON не восстанавливает.
   D получает настоящий объект, current draft/context и настоящий matching C summary.
4. D создаёт source в тестовом проекте, повтор даёт NOOP. Сборка выполняется именно из этого проекта.
5. Дополнительный `MatrixRuntimeTest` меняет все три собственных поля, имя и повторно открывает запись;
   проверяет nullable/false и строки 150/151. Отдельная копия проекта используется для CONFLICT_MODIFIED;
   пользовательская правка там сохраняется, основной тестовый проект остаётся неизменённым.

`current-context.json` — зафиксированные owner facts из изолированного P8-теста. Это тестовый проект
без конфликтующих классов/таблиц и с явно заданными runtime prerequisites; он не подменяет
захват актуального каталога рабочего приложения. Исходники генератора A, harness C и adapter D
сценарий не изменяет.

Прогон P8 выполняется через Vaadin component tests с resource registry; native browser не запускается.
Runtime — отдельный backend consumer и H2 create-drop. Он проверяет работу записей через canonical API,
но не является миграцией рабочей БД, применением в основном приложении или приёмкой UI-форм.
