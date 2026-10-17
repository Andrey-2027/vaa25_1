# S5(а), поставка: `platform-register` в замыкании `platform-backend-starter`

Дата: 2026-10-16. Область: поставка [ADR-0024 §2.2](../decisions/ADR-0024-register-module-and-operation-titles.md)
(движок регистра приходит backend-потребителю через стартер) и гейт состава стартера. Реализация
имён операций (S5(а) по существу) сюда не входит — здесь только поставка.

## Что изменено

| Файл | Изменение |
| --- | --- |
| `platform-backend-starter/pom.xml` | Прямое ребро `org.ipro:platform-register`; в описании и комментарии — причина (ADR-0024 §2.2) и измеренная цена. Имена `platform-rest`/`platform-register-vaadin` в тексте pom не называются: гейты состава читают pom как текст и не отличают упоминание от ребра |
| `platform-backend-starter/src/test/java/org/ipro/BackendStarterResolvedDependenciesTest.java` | Проверка разрешённого runtime-замыкания усилена (регистр есть, UI/HTTP нет) и получила **компиляционное** ребро: типы движка названы в исходнике теста, плюс проверка источника класса (`getProtectionDomain`) |
| `src/test/java/org/ipro/PlatformStarterCompositionTest.java` | Состав backend-стартера — `platform-spring-boot-autoconfigure` + `platform-register`; `platform-rest`, UI-модуль, Vaadin, отчётные координаты остаются вне |
| `platform-vaadin-starter/pom.xml` | Repair: комментарий называл REST-артефакт по имени, и гейт REST-модуля (текстовый) видел в прозе объявленное ребро. Формулировка переписана — гейт не ослаблен |
| `scripts/local-dependencies.json` | `dependsOn` backend-стартера, три перезаписанных fingerprint (см. ниже) |

## Измерения

| # | Что измерено | Значение |
| --- | --- | --- |
| M1 | разрешённое runtime-замыкание backend-стартера **до** ребра | 60 артефактов, `platform-register` отсутствует |
| M2 | то же **после** ребра | **61** артефакт, `platform-register` есть, `platform-register-vaadin` нет, ни одного артефакта со строкой `vaadin` нет, `platform-rest`/`platform-rest-web` нет |
| M3 | цена включения | ровно +1 артефакт — то же, что предсказал замер ADR-0024 (все транзитивные ветки модуля уже были в замыкании) |
| M4 | гейт стартера | 2/2 (`BackendStarterResolvedDependenciesTest`) |
| M5 | состав стартера (приложение) | 4/5; 1 краснота чужая и названа (см. «Ограничения») |

Команды: `mvn -o -f platform-backend-starter/pom.xml generate-test-resources` (M1–M3),
`mvn -o -f platform-backend-starter/pom.xml test` (M4),
`mvn -o -Dtest=PlatformStarterCompositionTest,WorkspaceManifestTest test` (M5).

## Негативный прогон

Проверка «гейт меряет ребро» состоялась **со второй попытки**, и первая — тоже результат:

1. **Негодная попытка (записана в протоколе как негодная).** Ребро убрано, `mvn test` без `clean`:
   `BUILD SUCCESS`, 2/2. Причина видна в выводе: `Nothing to compile - all classes are up to date` —
   Maven не перекомпилировал тест, и сборка проверяла старые классы, то есть ребро не измерялось
   вовсе. Это тот же класс ошибки, что и в S5(в): зелёный прогон, который не мерил предмет.
2. **Решающая попытка.** Ребро удалено физически из pom, сборка с `clean`:
   `BUILD FAILURE`, `package org.ipro.register does not exist` (2 ошибки импорта и 2 `cannot find
   symbol`), `NEGATIVE_RUN_V2_EXIT=1`. После восстановления — `BUILD SUCCESS`, 2/2, sha256 pom
   совпал с измеренным до эксперимента, дерево снова 61 артефакт с регистром.

Протокол: [register-s5a-negative-run-20261016.log](../../plans/register-s5a-negative-run-20261016.log),
сборки стартера и гейтов — [register-s5a-backend-starter-20261016.log](../../plans/register-s5a-backend-starter-20261016.log)
и [register-s5a-app-gates-20261016.log](../../plans/register-s5a-app-gates-20261016.log).

## Манифест: что перезаписано, а что названо

Перезаписаны три значения — все три следствие изменений этого захода или незакрытого долга S5(в):

| Проект | Было → стало | Почему это долг этого захода |
| --- | --- | --- |
| `platform-register` | 50 → **69** файлов, `7f38e904…` → `7add599c…` | Хвост S5(в): 17 тестов движка и две фикстуры (19 файлов) переехали в модуль, а запись манифеста за ними не поехала — 50 + 19 = 69 сходится | 
| `platform-backend-starter` | `cceb9af2…` → `29bb3640…` (2 файла) | Ребро и гейт этого захода |
| `platform-vaadin-starter` | `85eee40c…` → `087f3f3a…` (2 файла) | Repair комментария в том же файле |

**Не перезаписаны и названы чужими** (содержимое каталогов совпадает с тем, что уже несёт HEAD —
`git diff HEAD` по ним пуст, то есть дрейф старше этой правки):

- `platform-rls`: записано 61 файл, фактически 62 (`1d2b137d…` → `424c9003…`); самые свежие файлы
  каталога — C5-классы (`C5PermissionDecisionService`, `RlsStatementGuard`, `RlsFilterActivator`)
  с временем 2026-10-14 22:35, то есть это работа соседней темы, а не регистра.
- `platform-register-vaadin`: 14 файлов, содержимое изменилось (`9c5c121e…` → `4d0c31b7…`); самые
  свежие — `register-vaadin-surface.txt` (2026-10-15 18:21) и `RegisterVaadinExtensionPointTest`
  (2026-10-15 18:11).

Перезаписать их значило бы одобрить чужие правки, не будучи их автором; гейт `WorkspaceManifestTest`
называет оба дрейфа, и владелец правки обязан записать их сам.

## Ограничения и названная краснота

- **`WorkspaceManifestTest` не зелёный целиком**: 3/4, остаются два названных выше дрейфа. До этого
  захода тот же тест был красным и на `platform-register`, и на `platform-backend-starter`.
- **`PlatformStarterCompositionTest` не зелёный целиком**: 4/5; краснота
  `applicationUsesStartersButKeepsReportAndAppOnlyFilterGridDependencies` вызвана прямой
  зависимостью приложения на `org.ipro:platform-navigation` — она объявлена в `pom.xml` HEAD
  (строка 48) и к регистру отношения не имеет; сама проверка состава backend-стартера
  (`backendStarterCarriesTheBackendPlatformAndTheRegisterEngineWithoutUiOrRest`) проходит.
- **`platform-rls.api` и `PlatformPublicSurfaceTest`** в этом заходе не запускались.
- Полный `mvn verify` и random-order прогон (§6.5) не выполнялись; кейс внешнего потребителя
  (§6.3, `examples/platform-consumer` с объявленным регистром) не написан — негативный прогон здесь
  доказывает ребро на самом артефакте, а не на внешнем потребителе.
- Реализация имён операций (S5(а) по существу, `displayName` в декларации) не начата: поставка
  решена, механизм подписей — следующий шаг.
