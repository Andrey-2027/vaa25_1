# Проверка G1.2/G1.3 и сценарий «Матрица» — 2026-10-29

Сценарий выполнен успешно в изолированном тестовом приложении.
Основание: запрос пользователя и описание из `ПрогонСозданияСправочника.txt`.
Отчёт `G1.3_Отчет.txt` прочитан как evidence предыдущего исполнителя; его заявления сверены с кодом и повторными запусками.

## Проверка завершённых пакетов

- G1.2: повторный generated-acceptance `FutureCatalog` — **5 tests, 0 failures/errors/skips**.
  [Summary](verification/g1-2-summary.json) подтверждает compile, startup, metadata, canonical persistence/access на platform JAR.
- D/files G1.3: повторный self-test — **48 passed, 0 failed, 0 skipped**.
  [Summary](verification/g1-3-summary.json). В sandbox hard-link publication блокировалась;
  [первая попытка](verification/g1-3-sandbox-summary.json) дала 12 failed и 2 skipped.
  Повтор без этого ограничения подтвердил исходные 48 проверок, включая aliases.
- В исходном отчёте G1.3 явно оставался незавершённым matching A+C+D integration gate.
  Ниже он пройден на «Матрице» с одним planId/path/hash.
- **Полный G1.3 пока не подтверждён**: отдельный migration plan и его применение к тестовой БД
  отсутствуют в проверенных артефактах. H2 ORM create-drop не считается проверкой миграции.
  Существующие ограничения D (ACL/unsupported filesystem, межпроцессный lock) этим сценарием не устранены.

## Принятые входные решения

Класс `com.example.generated.Matrix`, таблица `matrix`, recipe `standard-catalog-v2@2`.
Код и наименование наследуются от `StandardCatalogEntity`.

| Подпись | Java-поле | Java-тип | Ограничения |
| --- | --- | --- | --- |
| ID внешней системы | idSys | Long | необязательное |
| Обозначение АгК | draftAgk | String | необязательное, max 150 |
| Активен | active | Boolean | необязательное |

Пользователь не задавал обязательность/публикации: собственные поля выбраны необязательными
и включены в форму, список и выбор. Уникальность, defaults и дополнительные права не добавлены.
Подсистемы, ссылки и табчасти отсутствуют.

## Фактический путь и результаты

| Шаг | Результат |
| --- | --- |
| P8 import → свежая проверка → preview → DownloadHandler | 1 test, success; исходник выдан из настоящего плана, повтор детерминирован |
| C на выданном Java | 5 tests, 0 failures/errors/skips |
| D в отдельный consumer project | APPLIED; project-local receipt сохранён, завершённый journal удалён |
| Повтор с тем же планом/C | NOOP |
| Build/start из проекта, заполненного D | 8 tests, 0 failures/errors/skips: 5 generic + 3 Matrix-specific |
| Правка файла в отдельной копии проекта | CONFLICT_MODIFIED; пользовательская правка сохранена |

Повторные generic tests до/после apply не суммируются как новые уникальные тесты.
Отдельные Matrix-specific проверки:

1. Создание с `idSys=4294967296`, `draftAgk=АгК-001`, `active=true`; чтение; изменение всех трёх полей
   на `Long.MAX_VALUE`, `АгК-002`, `false`, изменение наименования; повторное открытие и сохранность кода.
2. `null` в собственных полях сохраняется; `false` отличается от `null`.
3. Кириллическая строка длины 150 сохраняется, 151 отклоняется; пустая строка разрешена.

Проверены реальные platform metadata, унаследованные code/name, поля/типы/required/publications,
штатный canonical путь и запрет доступа без grants. Business repository/service для Matrix не создавались.

## Артефакты

- [Текущий draft владельца](input/current-draft.json), [context владельца](input/current-context.json),
  [полный reviewed plan](input/reviewed-plan.json), [receipt](input/plan-receipt.json).
- [Выданный Matrix.java](input/com/example/generated/Matrix.java).
- [Summary C](c/summary.json), [хэши platform JAR](c/platform-jars.sha256), [Maven C](c/maven.log).
- [APPLIED](applied.json), [NOOP](noop.json), [CONFLICT](conflict.json),
  [project-local receipt](project-state/apply/receipts/f5b46e1218c9c46ef93fa299949add0fb60210696857fb41200cd376bcd08800/g1-ff4f75e063206829db08b09c0fa6c9a049677b4ae23fe44151cc08361dcfdcb4.json).
- [Финальная сводка](scenario-summary.json), [P8 test XML](p8-test.xml),
  [тесты применённого проекта](applied-project-tests.log), XML в `runtime/`.

PlanId: `g1-ff4f75e063206829db08b09c0fa6c9a049677b4ae23fe44151cc08361dcfdcb4`.
Source SHA-256: `d65c2c9f5efea8e494863220fa6e4b5827c8ed04140921aad82c781ecf8fa476`.
Generator: `catalog-java-v1`. Хэш копии evidence отдельно сверен с receipt.

Исходники сценария: `scripts/entity-generation-g1-matrix/`,
launcher: `scripts/run-matrix-generation-scenario.ps1`.
Запуск 2026-10-29: P8 выполнен отдельно, затем launcher с `-SkipP8` на этих inputs.
В финальном подсчёте launcher возникла ошибка PowerShell Measure-Object для OrderedDictionary;
исправлен тип отчётов на PSCustomObject, финальная сводка пересчитана из успешных XML/receipt
через `Write-Summary.ps1` без повторного выполнения уже прошедших тестов.

Основной тестовый проект сохранён в
`outputs/entity-generation-g1/matrix-20261029/run-20261029-140029/project/`.
Его Matrix.java создан adapter D, соответствует проверенному хэшу; конфликтная правка находится
только в соседнем `conflict-project/`.

## Границы

P8 проверен через Vaadin component tests с resource registry, native browser не запускался.
Runtime — Spring Boot/H2 create-drop, приложение поднимается на время тестов.
Рабочая БД и основной source checkout не изменялись; Matrix не установлен в основное приложение.
`current-context.json` — явные facts из изолированного тестового владельца, не snapshot рабочего проекта.
Это завершённый автоматический сценарий генерации/применения/работы с записями;
приёмка UI-форм, миграция рабочей схемы и deployment не заявляются.
