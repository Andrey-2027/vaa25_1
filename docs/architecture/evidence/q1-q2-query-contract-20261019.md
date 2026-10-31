# Q1/Q2: нейтральный query-контракт и разрыв backend ↔ отчётный DOM (2026-10-19)

- Дата: 2026-10-19.
- Статус: **Q1 закрыт, Q2 закрыт в объёме backend-замыкания**; Q2-остаток
  (register slice → `ReportTemplate`, presentation hook в executor, read-only
  transaction boundary) назван в §4 и не выдаётся за сделанное.
- Основание: этапы Q1–Q3 локального плана
  `docs/plans/query-report-separation-plan.md` (вне Git, не нормативный),
  блокеры S2/S10 из [`r2-udr-scenario-inventory.md`](../r2-udr-scenario-inventory.md).

## 1. Что стало верно

Описание запроса больше не является отчётным шаблоном:

- `org.ipro.reportstudio.query.model` — `QueryDefinition`, `QueryParameter`,
  `QueryParameterKind` (`SCALAR/RANGE/ENTITY/ENTITY_LIST`), `QueryScalarType`,
  `QuerySource` (`MANUAL/VISUAL`), `QueryValueSource`, `QueryComputedValue`,
  `QuerySort`, `QueryLimits`. Диапазон владеет двумя именами binding'ов
  (`nameFrom`/`nameTo`) как правило контракта, а не движка.
- `QuerySource` решает первичность представления явно; непустой JSON больше не
  означает «визуальный запрос». Визуальный запрос без `visualQueryJson`
  отвергается конструктором.
- `ReportDataset` получил `ReferenceColumn`: идентичность ссылки (id, а для
  полиморфного регистратора — `type:id`) доступна по имени публичной колонки и
  индексу строки, но не входит в `fields()` и не участвует в `equals`.
  «Ключ не запрашивался» — ошибка, а не `null`-догадка.
- UDR и UniDR говорят с backend только через переходные адаптеры:
  `ReportTemplateQueryAdapter` (отчётный DOM → `QueryDefinition`, включая порядок
  вложенных групп и сортировки) и `UniDrQueryDefinitions` (UniDR-источник →
  `QueryDefinition`). Это единственные места, где backend ещё видит legacy-модель.
- `ReportQueryAssemblyService` и `ReportParamResolver` принимают нейтральные типы;
  `ReportQueryExecutor` больше не вызывает `ReportExecutionService` — порядок
  приходит обычными `QuerySort`.
- `UniDrQuerySourceService` собирает запрос через `UniDrQueryDefinitions.of(...)`, а
  `prepare(...)` берёт ключи из `data.referenceKey(...)`, а не из технических колонок
  результата.

## 2. Заборы

`QueryBackendBoundaryTest` — исполняемый гейт, а не комментарий:

1. backend-замыкание (`query/*`, `query/model`, `param`, `data`) не импортирует
   `org.ipro.reportstudio.dom`, `org.ip.unidr`, `org.ipro.reportstudio.run` и
   `com.vaadin`;
2. в нём нет строки `ReportExecutionService`;
3. переходные пакеты (`query/adapter`, `query/editor`, `query/constructor`,
   `query/register`, `query/list`) исключены явно: у них своя граница и свой срок
   жизни до R3.

`QueryModelContractTest`, `ReportTemplateQueryAdapterTest` и
`UniDrQueryDefinitionsTest` фиксируют семантику, которую нельзя потерять при
переезде: диапазон, типы, entity class, источник значения, computed/context,
обязательность, вложенный порядок групп и позиции сортировок.

## 3. Проверки

Прогон 2026-10-19, Maven off-line, JDK 21:

| Набор | Тестов | Результат |
|---|---|---|
| `org.ipro.reportstudio.query.**`, `param.**`, `org.ip.unidr.query.**`, `render.**`, `org.ip.application.register.**` | 620 | 0 failures / 0 errors |
| `org.ip.views.reportstudio.**`, `org.ip.views.unidr.**`, `org.ipro.report.**` | 269 | 0 failures / 0 errors |
| Итого по затронутой поверхности | **889** | **зелёные** |

Первый широкий прогон дал 494 `NoClassDefFoundError` на только что
скомпилированных классах; повторный прогон тех же наборов без изменения кода —
зелёный. Это артефакт окружения (свежие `target/classes` в forked JVM), а не
дефект среза; в отчётности он назван, чтобы цифра 620 не выглядела полученной с
первого раза.

Границы проверки: полный `mvn verify` и PostgreSQL-прогон не выполнялись;
production frontend не пересобирался. UI-поведение редактора проверено только
существующими unit/IT-наборами, браузерная приёмка не проводилась.

## 4. Остаток Q2 и вход в Q3

Q2 закрыт в объёме «backend не знает UDR/render/UniDR/Vaadin». Остаток Q2 после
закрытия register slice (2026-10-19, [evidence](q2-register-slice-20261019.md)):

1. ~~**Register slice возвращает `ReportTemplate`**~~ — закрыто: `createSliceQuery`
   отдаёт `QueryDefinition`, черновик — `SliceDraft`, шаблон собирает только
   `RegisterSliceDraftAdapter`; `query/register` не импортирует отчётный DOM.
2. **Presentation hook внутри executor**: `ReportQueryExecutor.usePresentations(...)`
   принимает `EntityPresentationService` и `RecorderTypeRegistry` из
   `org.ipro.data.presentation` / `org.ipro.register`. Для R2/M1 это прикладная
   зависимость в query-замыкании; её место — декоратор результата уровнем выше.
3. **Read-only transaction boundary и лимиты** остаются параметрами вызова
   (`maxRows`, `timeoutMs`), а не границей выполнения; `QueryLimits` пока только
   носитель политики.

Q3 (редактор/конструктор/UniDR на query API) на 2026-10-19 не начат:
`UniDrQuerySource` по-прежнему хранит `ReportParam*`/`ReportTemplate` и умеет
`template()`, `UniDrQueryUiSupport` — 1 файл с `ReportTemplate`. В `query/editor`
legacy-модель встречается в 4 файлах, в `query/constructor` — в 5 (13 файлов по
всему `query`, включая адаптер и register-фасады).
Порядок Q3 из плана: `setTemplate` → query draft/session с apply/cancel, затем
`UniDrQuerySource`/`UniDrQueryUiSupport`, затем вынос таблицы результата и
владения query CSS.

## 5. Следующий шаг

Рекомендуемый порядок:

1. ~~Закрыть Q2-остаток №1 (register slice → `QueryDefinition`)~~ — сделано
   2026-10-19, блокер S7 снят ([evidence](q2-register-slice-20261019.md));
2. закрыть Q2-остаток №2–3 (декоратор presentation, граница транзакции);
3. начать Q3 с `UniDrQuerySource`: убрать `ReportTemplate`/`ReportParam` из
   хранимой модели, оставив совместимость JSON и stable IDs.

Решение владельца по S2 принято 2026-10-19: авторский JPQL остаётся нейтральным
query-документом, в JRXML не уводится ([ADR-0028](../decisions/ADR-0028-author-query-as-document.md)).
Поэтому Q3 входит в объём R2, а Q2-остаток №1 — его ближайший технический шаг.

Обновление 2026-10-20: первый шаг Q3 выполнен — `UniDrQuerySource` больше не хранит
`ReportTemplate`, редактор и диалог запроса работают с draft/session
([evidence](q3-editor-draft-session-20261020.md)).
