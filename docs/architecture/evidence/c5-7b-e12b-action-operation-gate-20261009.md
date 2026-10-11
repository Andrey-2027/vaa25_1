# C5.7b / E1.2b: право на операцию в UI — один backend-ответ — 2026-10-09

- План: [C5.4–C5.10](../c5-operation-permissions-plan.md), пакет C5.7 («Применение: чтение и UI»),
  пункт «E1.2b: `ActionPermission`/`ActionContextProvider` получают общий backend-ответ;
  объявленное действие не исполняется программно без требуемого права».
- Решения: [ADR-0017](../decisions/ADR-0017-c5-single-operation-authority.md) §3 (право на операцию —
  строка гранта, а не следствие построчного `'*'`), §10–§11 (безопасный текст причины),
  [ADR-0019](../decisions/ADR-0019-owner-named-row-operation.md) (именованные операции владельцев
  спрашивают тот же сервис).
- Предшественники: [c5-7-read-ui-tile-gate-20261009.md](c5-7-read-ui-tile-gate-20261009.md) —
  назвал общий backend операций для слоя действий остатком пакета;
  [c5-6-write-enforcement-20261006.md](c5-6-write-enforcement-20261006.md) — серверная граница.
- Метод: порт вопроса + композиция в провайдере входов + проводка адаптера авто-конфигурацией;
  прогоны модуля и прикладных заборов, намеренные правки реестров поверхности.

## 1. Что было не так

Слой действий решал **операцию** типа по построчному ответу. `ActionContextProvider` собирал входы
из capability типа и `RlsUiGate`, а ключ операций `ENTITY:<Имя>` (`CREATE`/`UPDATE`/`DELETE` —
глобальное право, ADR-0017 §3) в UI не спрашивался вовсе. Отсюда два расхождения, которые нельзя
закрыть «ещё одной проверкой в кнопке»:

- строка, разрешённая построчно, показывала **доступную** кнопку, хотя права на операцию у субъекта
  нет, — сервер отказывал, а UI обещал успех;
- отказ, который RLS не выражает (грант на операцию типа не является измерением строки), в UI был
  неотличим от «прав нет на строку»: причина до пользователя не доходила.

До этого среза запрет можно было получить только «серой кнопкой» или серверным исключением; одно
решение о записи жило в двух двигателях.

## 2. Что сделано

| Место | Было | Стало |
| --- | --- | --- |
| [`OperationPermissionSource`](../../../platform-vaadin/src/main/java/org/ipro/form/action/OperationPermissionSource.java) | — | Новый INTERNAL-порт: вопрос «разрешена ли операция типа» и безопасный текст отказа. Источник не назван типом C5, поэтому провайдер входов проверяем без хранилища грантов |
| [`ActionContextProvider`](../../../platform-vaadin/src/main/java/org/ipro/form/action/ActionContextProvider.java) | capability + `RlsUiGate` | + обязательный третий коллаборатор; композиция **по И** ровно в одном месте: операция — C5, строка — RLS. Отказ C5 показывается раньше построчного; тип, не поддерживающий операцию, C5 **не спрашивает** (причина остаётся `TYPE_NOT_SUPPORTED`) |
| [`FormAutoConfiguration`](../../../platform-vaadin/src/main/java/org/ipro/form/config/FormAutoConfiguration.java) | — | `operationPermissionSource` — адаптер поверх `C5PermissionDecisionService` (тот же сервис, что у серверной границы) и `RlsCurrentUser#requireAuthenticatedUsername`: аноним и неявный `system` отказываются формулировкой C5 (`UNAUTHENTICATED`) и гранты о них не спрашиваются; read-намерение отвергается по имени, а не решается «правом на операцию» |
| [`platform-vaadin-surface.txt`](../../../src/test/resources/platform-vaadin-surface.txt) | 137 типов | 138: `OperationPermissionSource` — INTERNAL (как сам провайдер) + запись test-usage на приёмочный срез |
| [`platform-package-matrix.txt`](../../../src/test/resources/platform-package-matrix.txt) | — | reviewed-ребро `org.ipro.form.config -> org.ipro.rls.c5`: адаптер регистрирует авто-конфигурация, направление — вниз (UI → модель прав) |
| [`d1-surface-measurements.md`](../d1-surface-measurements.md) | `platform-artifact-files=500` | 501 — намеренный замер, а не побочный эффект |

Второй формулы прав не появилось: `ListForm`, `FormCoordinator`, `ItemFormWrapperView` и
`ItemFormAccessBinder` уже ходили через провайдер (E1.3–E1.5), поэтому кнопка, карточка и момент
клика читают один и тот же составной ответ. Исполнение объявленного действия пересчитывает решение
в момент клика (`ListForm#executeDeclaredAction`), то есть недоступное действие не запускается
программно — это и требовал пункт плана.

## 3. Проверки

| Проверка | Результат |
| --- | --- |
| [`ActionContextProviderTest`](../../../platform-vaadin/src/test/java/org/ipro/form/action/ActionContextProviderTest.java) — новые срезы: отказ операции гасит действие и **называет операцию**; построчное разрешение не заменяет право на операцию; отказ C5 назван раньше построчного; тип без операции источник не спрашивает; отсутствие любого коллаборатора падает названно | 15/15 |
| [`FormAutoConfigurationTest`](../../../platform-vaadin/src/test/java/org/ipro/form/config/FormAutoConfigurationTest.java) — адаптер отвечает решением C5; аноним отказан **без** запроса грантов; чтение операцией не является (названная ошибка композиции) | 16/16 |
| [`RlsProtectedTypeActionDecisionTest`](../../../src/test/java/org/ip/security/RlsProtectedTypeActionDecisionTest.java) — реальный носитель: настоящий `C5PermissionDecisionService` + реестр определений, настоящий `RlsUiGate`, и **тот же адаптер, что регистрирует контейнер**; отзыв права операции (`revokeOperation`) снимает действие и переводит карточку в просмотр без второго механизма | 10/10 |
| [`ListFormActionDecisionTest`](../../../platform-vaadin/src/test/java/org/ipro/form/builtin/ListFormActionDecisionTest.java), [`ListFormDeclaredActionTest`](../../../platform-vaadin/src/test/java/org/ipro/form/builtin/ListFormDeclaredActionTest.java), [`ItemFormAccessBinderDecisionTest`](../../../platform-vaadin/src/test/java/org/ipro/form/coordinator/ItemFormAccessBinderDecisionTest.java), [`ActionResolverTest`](../../../platform-vaadin/src/test/java/org/ipro/form/action/ActionResolverTest.java) — хосты читают составной ответ и не собирают его сами; объявленное действие пересчитывает решение в момент клика (`ListForm#executeDeclaredAction`, строка 720) | 14+10+11+7 |
| [`VaadinModuleCompositionTest`](../../../platform-vaadin/src/test/java/org/ipro/vaadin/module/VaadinModuleCompositionTest.java) — reviewed-состав модуля: 138 типов | 8/8 |
| `platform-vaadin`, весь модуль (`-f platform-vaadin/pom.xml test`) | **BUILD SUCCESS, отказов нет** |
| [`PlatformVaadinModuleTest`](../../../src/test/java/org/ipro/PlatformVaadinModuleTest.java) — типы модуля ушли из дерева приложения: 138 | 7/7 |
| [`PlatformVaadinSurfaceTest`](../../../src/test/java/org/ipro/PlatformVaadinSurfaceTest.java) — роли типов и заморозка ссылок тестов приложения: 138 и запись overlay | 6/6 |
| [`PlatformPackageDependencyMatrixTest`](../../../src/test/java/org/ipro/PlatformPackageDependencyMatrixTest.java) — новое ребро между пакетами платформы зарегистрировано, а не появилось молча | 3/3 |
| [`PlatformPublicSurfaceTest`](../../../src/test/java/org/ipro/PlatformPublicSurfaceTest.java) — замеры поверхности совпадают с кодом после намеренного обновления | 10/10 |
| [`ActionPolicyConfigTest`](../../../src/test/java/org/ip/config/ActionPolicyConfigTest.java) — прикладная композиция действий не задета | 7/7 |

Прогоны: `mvn -o -Dmaven.repo.local=.local-maven-repository -f platform-vaadin/pom.xml -Dvaadin.skip=true test`
(BUILD SUCCESS, 0 отказов), `mvn -o -Dvaadin.skip=true test -Dtest="ActionPolicyConfigTest,RlsProtectedTypeActionDecisionTest,PlatformVaadinSurfaceTest,PlatformVaadinModuleTest,PlatformPackageDependencyMatrixTest,PlatformPublicSurfaceTest"`
(43 теста, 0 отказов). JDK 21. Рабочее дерево общее: рядом идут незакоммиченные срезы других
авторов, поэтому счётчики прогонов и замеры поверхности читаются на момент прогона.

## 4. Остаток

- **Полный прикладной прогон красный, и это не этот срез.** `mvn -o -Dvaadin.skip=true test` дал
  **2007 тестов, 27 отказов, 7 ошибок, 27 пропусков**; ни один отказ не называет тип или сообщение
  этого среза. Отказы сгруппированы вокруг незакоммиченного дрейфа соседних работ: пилотные
  регистры (`AccountingObject*` — `ApplicationBoundaryTest`, `ReferenceTargetToStringTest`),
  адреса типов (`DeepLinkCatalogBaselineIT`, `DeepLinkRouteBaselineTest`), текст C5 read-отказа
  (`RlsUnauthenticatedAccessIT`), админ-IT операций (`NamedOperationGrantAdminIT`),
  reportstudio-CTE, `RegisterStartupValidatorTest`, `SearchFieldParitySnapshotTest`,
  `CanonicalReadBoundaryIT`, `SectionMetadataRegistryTest`. Baseline отказов **до** среза не
  снимался, поэтому это суждение по предмету отказа, а не A/B-измерение.
- **Первичная выдача операций в «обычной» базе — парный остаток C5.8, и теперь его цена видна в UI.**
  UI спрашивает C5 безусловно, а явные строки на ключ операций сегодня выдаёт только
  пилотный профиль ([`AccountingObjectPilotInitializer`](../../../src/main/java/org/ip/config/AccountingObjectPilotInitializer.java))
  и seed приложения для `Role`/`User`. Пока «обычная» база не получила выдачу на типы без
  измерений, generic-запись там будет отказана и `admin` — это предсказание плана C5.8
  («иначе запись откажет и для `admin`»), названное, а не спрятанное. Серверная граница flush
  при этом остаётся снятой (`rls.write-boundary.enforced=false`, C5.6): UI строже сервера — тот же
  факт, что назван в C5.6, и он снимается включением границы, а не ослаблением UI.
- **Манифест не перезаписывался.** [`scripts/local-dependencies.json`](../../../scripts/local-dependencies.json)
  (перезапись — `-Dplatform.manifest.write=true`) разошёлся и без этого среза: кроме одного файла
  E1.2b в его дрейфе лежит незакоммиченный срез `platform-rls`. Перезапись манифеста здесь
  закрепила бы чужое изменение как наше решение, поэтому запись оставлена владельцу дрейфа.
- **Кликами UI не проверялось:** решение проверено тестами на реальном носителе, отрисовка кнопок
  на живой странице не воспроизводилась.
- Атрибутные (полевые) права остаются вне объёма C5 — условие возврата названо в плане.
