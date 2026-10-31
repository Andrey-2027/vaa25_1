-- DB1 UDR: инвентарь перед удалением физических таблиц движка (read-only).
--
-- Запуск на каждой БД (psql):
--   psql "$PGURL" -X -A -F '|' -P footer=off -v ON_ERROR_STOP=1 \
--        -f scripts/db1-udr-inventory.sql
--
-- Скрипт ничего не меняет: читает information_schema/pg_catalog и count(*) по пяти
-- таблицам. Пустой результат по таблицам означает, что в этой БД удалять нечего.
-- Вывод сохраняется рядом с решением (дата, БД, исполнитель); удаление выполняет
-- scripts/db1-udr-removal.sql только после проверенного бэкапа.

\echo '== UDR tables: rows and size =='
select t.table_name,
       (xpath('/row/c/text()',
              query_to_xml(format('select count(*) as c from public.%I', t.table_name),
                           false, true, '')))[1]::text::bigint as rows,
       pg_size_pretty(pg_total_relation_size(format('public.%I', t.table_name))) as total_size
  from information_schema.tables t
 where t.table_schema = 'public'
   and t.table_name in ('report_template','report_band','report_field','report_param','report_order')
 order by array_position(array['report_template','report_band','report_field','report_param','report_order'],
                         t.table_name);

\echo '== UDR columns (schema inventory) =='
select table_name,
       string_agg(column_name || ' ' || data_type
                  || case when is_nullable = 'NO' then ' not null' else '' end,
                  ', ' order by ordinal_position) as columns
  from information_schema.columns
 where table_schema = 'public'
   and table_name in ('report_template','report_band','report_field','report_param','report_order')
 group by table_name
 order by table_name;

\echo '== UDR keys, FKs and identity =='
select tc.table_name,
       tc.constraint_type,
       tc.constraint_name,
       coalesce(kcu.column_name, '-') as column_name,
       coalesce(ccu.table_name, '-') as references_table,
       coalesce(rc.delete_rule, '-') as on_delete
  from information_schema.table_constraints tc
  left join information_schema.key_column_usage kcu
         on kcu.constraint_name = tc.constraint_name and kcu.constraint_schema = tc.constraint_schema
  left join information_schema.constraint_column_usage ccu
         on ccu.constraint_name = tc.constraint_name and ccu.constraint_schema = tc.constraint_schema
  left join information_schema.referential_constraints rc
         on rc.constraint_name = tc.constraint_name and rc.constraint_schema = tc.constraint_schema
 where tc.table_schema = 'public'
   and tc.table_name in ('report_template','report_band','report_field','report_param','report_order')
   and tc.constraint_type in ('PRIMARY KEY','UNIQUE','FOREIGN KEY')
 order by tc.table_name, tc.constraint_type, tc.constraint_name;

select table_name, column_name, is_identity, identity_generation
  from information_schema.columns
 where table_schema = 'public'
   and table_name in ('report_template','report_band','report_field','report_param','report_order')
   and column_name = 'id'
 order by table_name;

\echo '== UDR indexes =='
select tablename, indexname
  from pg_indexes
 where schemaname = 'public'
   and tablename in ('report_template','report_band','report_field','report_param','report_order')
 order by tablename, indexname;

\echo '== UDR rows detail (template headers) =='
-- \gexec исполняет сгенерированный запрос только при наличии таблицы.
select case when to_regclass('public.report_template') is null
            then 'select ''report_template table is absent'''
            else 'select id, name, state, coalesce(target_entity_class, ''-'') as target_entity_class, '
                 || 'coalesce(created_by, ''-'') as created_by, created_at from report_template order by id'
       end
\gexec

\echo '== UDR-related grants (must be reviewed, not guessed) =='
-- Внутренний запрос — строка: psql не планирует его, пока access_grant не нужен,
-- поэтому инвентарь работает и в БД без таблицы грантов.
select case
         when to_regclass('public.access_grant') is null then 'access_grant table is absent'
         else (xpath('/row/c/text()', query_to_xml(
                'select count(*) as c from access_grant where dimension ilike ''%udr%'' or dimension ilike ''%reportstudio%'' or dimension ilike ''%report_template%''',
                false, true, '')))[1]::text
       end as udr_grants;
