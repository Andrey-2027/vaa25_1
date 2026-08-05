-- ============================================================
-- Проверка телеметрии (Веха 2): простой чек-лист.
-- Запуск:
--   psql -h localhost -U postgres -d DBVaa25 -f scripts/verify-telemetry.sql
-- (пароль через PGPASSWORD=1 или будет запрошен)
-- ============================================================

\echo '=== 1. Таблицы телеметрии (должны быть все три) ==='
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN ('operation_log', 'perf_stats', 'trace_settings')
ORDER BY table_name;

\echo ''
\echo '=== 2. L0-агрегаты (perf_stats) — топ по суммарному времени ==='
SELECT stat_key, count,
       round(total_ms::numeric, 1) AS total_ms,
       round(p95_ms::numeric, 1)   AS p95_ms,
       window_start
FROM perf_stats
ORDER BY total_ms DESC
LIMIT 10;

\echo ''
\echo '=== 3. Журнал L1 (operation_log) — последние 10 записей ==='
SELECT id, level, event_type, operation, duration_ms, sql_count, n1,
       trace_id, user_id
FROM operation_log
ORDER BY id DESC
LIMIT 10;

\echo ''
\echo '=== 4. N+1-аномалии в журнале ==='
SELECT id, operation, duration_ms, sql_count, trace_id
FROM operation_log
WHERE n1
ORDER BY id DESC
LIMIT 10;

\echo ''
\echo '=== 5. Дерево фреймов (payload) последней аномалии ==='
SELECT operation, duration_ms, payload
FROM operation_log
ORDER BY id DESC
LIMIT 1;

\echo ''
\echo '=== 6. Счётчики строк в журналах ==='
SELECT 'operation_log' AS tbl, count(*) AS rows_count FROM operation_log
UNION ALL
SELECT 'perf_stats', count(*) FROM perf_stats;
