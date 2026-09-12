# ADR-0002: семантика lifecycle-событий сохранения

- Статус: принято в текущем dev-scope
- Дата: 2026-09-11
- Область: platform events, aggregate save, persistence lifecycle

## Контекст

В проекте уже были события сохранения и удаления, но их граница с транзакцией
и ownership агрегата была неявной. Немедленная доставка `Changed`/`Deleted` вне
транзакции могла сообщить о результате, который затем откатился или вообще не
был зафиксирован.

## Решение

1. `Saving` и `Deleting` — синхронные veto-capable события до persistence.
2. `Saved` — синхронное событие внутри активной транзакции после вызова persistence.
3. `Changed` и `Deleted` — только after-commit события. При отсутствии активной
   транзакции publisher завершается ошибкой и не делает fallback-доставку.
4. Единый aggregate scope подавляет root `Saved/Changed` из generic service;
   их владельцем остаётся aggregate coordinator — metadata-driven service либо
   явный typed use case для нестандартного workflow.
5. Field audit — отдельный telemetry-контур. Он не является application event и
   не добавляет зависимость в `org.ipro.events`.
6. После commit обработчик не может отменить уже завершённую транзакцию; ошибка
   такого обработчика логируется.

## Последствия

- rollback не порождает after-commit reactions;
- вызов mutation path без транзакции обнаруживается сразу;
- порядок `Saving -> Saved -> commit -> Changed` проверяется тестами;
- внешние side effects должны подключаться к `Changed`/`Deleted` либо outbox;
- PostgreSQL-specific подтверждение остаётся отдельным pre-production gate.
