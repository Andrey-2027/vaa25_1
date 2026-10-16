# П4 — evidence

Дата: 14.10.2026.

- scripts/run-permissions-p4.ps1: **127 successful, 0 failed**, 76.408 s JUnit в финальном прогоне.
  Компилируются текущие application/platform sources; manifest и reports в outputs/permissions-p4.
  Число включает inherited P0/P2 сценарии, это не 127 новых тестов.
- Два потока / независимые Spring transactions создают absent-row patch с одинаковым baseline:
  **one saved / one conflict**, физическая строка ровно одна. H2, bounded wait 30s.
- Сохранение одного dirty flag не теряет соседние; stale absent snapshot отвергается.
- Черновики сохраняются при конфликте, разные субъекты независимы; revert удаляет dirty запись;
  RLS normalization применяется в draft.
- Реальный REQUIRES_NEW rollback после save/flush оставляет базу без гранта; ошибочный второй
  адрес пакета не сохраняет первый.
- Canonical Role update переносит грант на новый ключ; старый SubjectRef получает Conflict;
  после снятия fixture assignment canonical delete удаляет собственные гранты роли.
- ADMIN method security: не-ADMIN не читает baseline и не сохраняет; отсутствующее значение
  RLS и нерабочий ключ отвергаются.
- scripts/run-permissions-p4-schema.ps1: локальный PostgreSQL CREATE UNIQUE INDEX
  NULLS NOT DISTINCT успешно committed; повторная установка идемпотентна. Временная таблица
  доказывает unique_violation повторного global NULL адреса. Предметные строки не менялись.
- Committed event AFTER_COMMIT в успешном concurrent сценарии; payload только actor/target/time/diffs.

Решение concurrency: subject database FOR UPDATE + physical @Version snapshots + PostgreSQL
NULL-aware unique index. Общая блокировка подключена compatibility admin methods и lifecycle.
Счётчик AccessGrantVersion не используется как версия данных.

Ограничение evidence: Spring/JPA concurrency выполнена H2, не PostgreSQL; на PostgreSQL
проверены DDL/NULL semantics. Расширенная PostgreSQL/rollback/audit и User lifecycle matrix
остаются интеграционной приёмкой П9. Новый UI и удаление старого редактора — П5/П10.
