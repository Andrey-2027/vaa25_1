# G1: редактор, первый шаг

Дата: 2026-10-28. Объём: G1.1.1 потока B из [плана](../../plans/entity-generation-g1-minimum-plan.md).

## Изменение

`DraftEditorSession` явно выбирает `RecipeCatalog.STANDARD_CATALOG_V2` в constructor и `newDraft()`.
Старый `DraftOperations.create()` по-прежнему создаёт v1. Импорт не меняет рецепт, UUID или raw input;
JSON checkpoint и dirty semantics сохраняются. Runtime prerequisites не подменяются: UNKNOWN блокирует
CheckedStructure даже при полном snapshot без подтверждённых prerequisites.

Положительные session/editor fixtures теперь явно содержат AVAILABLE для `STANDARD_CATALOG_APP_API`
и `ET0_ET7_RUNTIME`. Эти факты заданы только в тестах. Отдельно проверены неизвестный snapshot,
полный snapshot с неизвестными prerequisites и обновление каталога из подтверждённого состояния в UNKNOWN.
Регрессионные проверки покрывают импорт/экспорт v1 с некорректными raw constraints и стабильными ID,
изменения во время выдачи JSON, смену пользователя и независимость редакторов.

## Воспроизведение

`scripts/test-entity-generation-g1-editor.ps1` запускает восемь целевых suite с установленными
локальными зависимостями, без install и clean. Выход сборки изолирован в
`outputs/entity-generation-g1/g1-1/build-ui`; root target и общий Maven cache не очищаются.
Root POM и dependency fingerprints не изменяются.

В ограниченном Windows окружении self-attach Mockito задержал первый запуск с ошибкой доступа к pipe.
Он был прерван; лог сохранён как `ui-tests-interrupted-mockito-attach.log`. Для повторного запуска
агент подключён при старте JVM:

```powershell
./scripts/test-entity-generation-g1-editor.ps1 -TestJvmArguments '-javaagent:C:/Users/strim/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar'
```

Окончательный прогон: **41 тест, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS.
Компиляция приложения и тестов прошла на Java 21.
Фактические результаты последнего запуска: `outputs/entity-generation-g1/g1-1/ui-test-results.json`;
лог: `ui-tests.log`; SHA-256 использованных core/json/Mockito JAR: `ui-dependencies.json`.
Числа разных запусков не суммируются.

## Остаток потока B

Обновление после получения A: G1.1.5 реализован; актуальное описание и проверки —
в [отчёте preview](entity-generation-g1-preview-20261028.md). Ниже сохранено состояние первого шага
до получения API. Лог и JSON результатов в общей output папке теперь относятся к последнему прогону;
41 тест выше — исторический результат G1.1.1.

G1.1.5–G1.1.6 ещё не реализованы: пользователь подтвердил, что API параллельного потока A
пока не определён; пакета `org.ipro.design.generation` на момент работы нет.
Подключение выполняется к настоящему builder/result/plan/diagnostics после появления контракта.
Временные генератор, план и заглушки результата не создавались.

При интеграции остаются: свежая проверка текущего draft/context, read-only Dialog, адресная навигация
generation diagnostics, выдача Java с повторной проверкой доступа/epoch/плана и без изменения
JSON checkpoint, fixture/plan/Java evidence. Install нового core и app-side проверка выполняются
последовательно после завершения A.

G1.1 в целом, G1.2, G1.3 и compile/startup/persistence сгенерированного класса не заявляются завершёнными.
