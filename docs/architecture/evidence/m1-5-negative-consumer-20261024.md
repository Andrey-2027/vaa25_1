# M1 шаг 5: независимые потребители query-артефактов (2026-10-24)

- Дата: 2026-10-24.
- Основание: [M1 плана](../../plans/query-report-separation-plan.md), шаг 5 —
  «собрать независимого потребителя backend без Vaadin/Jasper и UI-потребителя без
  report engines; удаление нужного artifact действительно ломает целевой сценарий».
- Срез: два отдельных Maven-проекта и скрипт, проверяющий обе стороны — успешную
  сборку и отказ при удалении артефакта. Приложение в этих сборках не участвует.

## 1. Что добавлено

```text
platform-build/query-consumer/backend/   platform-query + platform-report-core, без Vaadin/Jasper
platform-build/query-consumer/ui/        platform-query-vaadin + Vaadin, без Jasper/DynamicReports/reportui
scripts/check-query-consumers.ps1        установка артефактов, сборка, classpath, негативные прогоны
```

Потребители — отдельные POM'ы, а не тесты приложения: старый JAR или классы в
`target` приложения не могут удовлетворить их сборку. Зависимость от query-артефакта
лежит в профиле, активном по умолчанию (`with-query` / `with-query-ui`), поэтому
`-P!with-query` / `-P!with-query-ui` отключает её.

## 2. Прогон

```text
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-query-consumers.ps1
```

Результат (JDK 21, локальный `.m2`):

| Проверка | Результат |
|---|---|
| `platform-query` install | OK |
| `platform-query-vaadin` install | OK |
| backend consumer `compile` | OK |
| backend classpath | нет `jasperreports`, `dynamicreports`, `reportui`, `platform-jr`, `platform-report*`, `vaadin` |
| backend без `platform-query` | **exit 1** — компиляция ломается |
| UI consumer `compile` | OK |
| UI classpath | нет `jasperreports`, `dynamicreports`, `reportui`, `platform-jr`, `platform-report*` |
| UI без `platform-query-vaadin` | **exit 1** — компиляция ломается |

Повторный прогон с `-SkipInstall` после изменений M2.1 дал тот же результат:
оба положительных прогона зелёные, оба негативных — exit 1.

## 3. Границы

- Потребители не запускают приложение и не поднимают Spring-контекст: шаг
  доказывает зависимость сборки, а не runtime-поведение.
- `platform-report-core` остаётся допустимой зависимостью backend-потребителя:
  это нейтральный датасет, а не отчётный движок.
- Проверка classpath — по именам артефактов, а не по `jdeps`: этого достаточно,
  чтобы поймать появление Vaadin/Jasper/reportui транзитивно.

**M1 шаг 5 закрыт.** Остаток M1 — UI-мост UniDR, который умирает вместе с M2.
