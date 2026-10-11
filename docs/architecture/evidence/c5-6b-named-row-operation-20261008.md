# C5.6b: именованная операция владельца на canonical path — 2026-10-08

- План: [C5.4–C5.10](../c5-operation-permissions-plan.md), пакет C5.6b (пункт про `AttributeValueService`).
- Решение: [ADR-0019](../decisions/ADR-0019-owner-named-row-operation.md) §1–§5;
  носитель права — [ADR-0017](../decisions/ADR-0017-c5-single-operation-authority.md) §3;
  граница записи — ADR-0017 §9.
- Метод: рабочий срез на существующем шве `EntityDataAccess`; целевые прогоны затронутых
  классов, а не полный прогон.

## 1. Что сделано

**Шов именованной операции.** [`EntityDataAccess.executeNamed(Class, String, Object, Consumer)`](../../../platform-core/src/main/java/org/ipro/data/EntityDataAccess.java#L80)
— единственный вход именованной операции; реализация — в
[`CanonicalEntityDataAccess`](../../../platform-core/src/main/java/org/ipro/data/CanonicalEntityDataAccess.java)
поверх [`CanonicalWriteExecutor`](../../../platform-core/src/main/java/org/ipro/data/CanonicalWriteExecutor.java).
Порядок внутри:

```text
capability типа (fail-closed)
-> определение операции существует и объявляет EXECUTE (иначе ошибка конфигурации)
-> решение C5 ACTION:<permissionId> EXECUTE   <-- до загрузки строки и до SQL
-> canonical read исходного состояния (RLS + C5 read)
-> мутация на загруженной строке -> persistence -> события
```

Ключ берётся из определения владельца, а не из подписи метода или имени bean'а; generic
`UPDATE`/`DELETE` этим путём не публикуются — тип остаётся CREATE-only (`@RlsDimension(kind =
CHECK_ONLY)` на типе с canonical CRUD по-прежнему отказ при старте).

**Регистрация определения владельцем.** Определение `ACTION:ATTRIBUTE_VALUE:RENAME` объявляет
[`AttributeValuePermissions`](../../../src/main/java/org/ip/application/catalog/AttributeValuePermissions.java)
(`InitializingBean`): регистрирует ключ как динамическое измерение `CHECK_ONLY` — иначе грант
нельзя было бы назначить, — и вносит определение в реестр операций. Регистрация никого не пускает:
носителем права остаётся строка `access_grant`; незарегистрированный ключ — ошибка конфигурации, а
не allow.

**Прикладной путь.** [`AttributeValueService`](../../../src/main/java/org/ip/service/AttributeValueService.java)
переведён на canonical create (четыре пути создания) и на именованное переименование
(`renameValue` → `executeNamed`). Прямых `repository.save` по этому типу в production-коде не
осталось; repository используется только на чтение.

**Гейт кнопок из того же решения.** Форма
[`AttributeTypeForm`](../../../src/main/java/org/ip/views/forms/AttributeTypeForm.java) спрашивает
`AttributeValuePermissions.canCreateValue()` / `canRenameValue()` — то же решение C5, что и
серверный путь, а не собственное условие формы. Кнопка, открытая без права, была бы обещанием,
которое сервер не исполняет; переименование дополнительно требует чтения строки, поэтому
`canRenameValue` спрашивает и операцию, и read-ключ.

**Вооружение типа.** `AttributeValue` назван в `rls.write-boundary.enforced-types` (main-properties):
прямых записей этого типа в production-коде не осталось, поэтому граница для него обязательна.
Вооружение ничего не меняет для легальных путей — они и так проходят тот же preflight, — но
закрывает обход: прямой `EntityManager` отклоняется на flush.

## 2. Проверки

| Проверка | Результат |
| --- | --- |
| [`AttributeValueRenameOperationIT`](../../../src/test/java/org/ip/application/catalog/AttributeValueRenameOperationIT.java) — право, порядок, generic-запреты, canonical create | 6/6 |
| [`AttributeValueWriteBoundaryIT`](../../../src/test/java/org/ip/application/catalog/AttributeValueWriteBoundaryIT.java) — вооружённый тип, отказ прямого flush, пары «кнопка ↔ сервер» | 5/5 |
| [`AttributeValueServiceTest`](../../../src/test/java/org/ip/service/AttributeValueServiceTest.java), `AttributeValueRefRlsIT`, `RlsDimensionFactsIT` — затронутые фикстуры и слайс-заглушка | 24 + 2 + 11, 0 отказов |
| [`PilotSecurityQueryMeasurementIT`](../../../src/test/java/org/ip/config/PilotSecurityQueryMeasurementIT.java) — замер, [evidence](c5-6b-security-query-measurement-20261008.md) | 1/1 |

Порядок «решение до SQL» подтверждён телеметрией отказа, а не только текстом ошибки: в отчёте
затронутого прогона отказ переименования идёт строкой
`op=AttributeValueService.renameValue ... duration=13,21 ms sql=0 error=C5AccessDeniedException:
Нет прав на выполнение операции: EXECUTE ACTION:ATTRIBUTE_VALUE:RENAME` — то есть ни одной
строки SQL до решения.

## 3. Что осталось

- **Первичная выдача гранта** `ACTION:ATTRIBUTE_VALUE:RENAME` (C5.8): пока строки нет,
  переименование закрыто и для держателя прежнего `ENTITY:AttributeType`. Это названное
  состояние, а не тихий обход, но без выдачи операция недоступна человеку.
- **Замена набора регистратора** — свой пункт C5.6b: отдельное именованное определение владельца,
  generic CRUD строк регистра не открывается.
- Полные прогоны и PostgreSQL-проверка остаются за C5.9: здесь сняты только целевые прогоны.
