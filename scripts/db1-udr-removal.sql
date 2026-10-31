-- DB1 UDR: удаление физических таблиц движка отчётов (после проверенного бэкапа).
--
-- Порядок:
--   1. scripts/db1-udr-inventory.sql  — read-only инвентарь, вывод сохранён;
--   2. scripts/db1-udr-backup.ps1     — дамп + проверка восстановления (обязательно);
--   3. этот скрипт                   — только при явном подтверждении.
--
-- Запуск (psql, одна БД за раз):
--   psql "$PGURL" -X -v ON_ERROR_STOP=1 \
--        -v confirm=DROP-UDR-TABLES -v confirm_db=DBVaa25 \
--        -f scripts/db1-udr-removal.sql
--
-- Два токена — намерение и имя БД. Без них или при несовпадении имени скрипт
-- отказывается до первого DDL: случайный запуск не на «той» базе ничего не удалит.
-- Удаляются только пять таблиц движка; access_grant не трогается (UDR-грантов
-- в коде не было — это проверяется инвентарём, а не предполагается).
-- Откат — восстановление дампа pg_dump из каталога бэкапа (см. runbook).

\if :{?confirm}
\else
  \set confirm 'MISSING'
\endif
\if :{?confirm_db}
\else
  \set confirm_db 'MISSING'
\endif

\echo '== DB1 UDR: guard =='
select set_config('db1.udr_confirm', :'confirm', false) as confirm_token;
select set_config('db1.udr_confirm_db', :'confirm_db', false) as confirm_db;

do $$
declare
  confirm_token text := current_setting('db1.udr_confirm', true);
  confirm_db    text := current_setting('db1.udr_confirm_db', true);
begin
  if confirm_token is distinct from 'DROP-UDR-TABLES' then
    raise exception 'DB1 UDR: отказ. Передайте -v confirm=DROP-UDR-TABLES';
  end if;
  if confirm_db is distinct from current_database() then
    raise exception 'DB1 UDR: отказ. confirm_db=% не совпадает с current_database()=%',
      confirm_db, current_database();
  end if;
  if to_regclass('public.report_template') is null then
    raise exception 'DB1 UDR: отказ. В БД % нет report_template — проверьте инвентарь',
      current_database();
  end if;
end $$;

\echo '== pre-drop counts (сохранить в evidence) =='
select t.table_name,
       (xpath('/row/c/text()',
              query_to_xml(format('select count(*) as c from public.%I', t.table_name),
                           false, true, '')))[1]::text::bigint as rows
  from information_schema.tables t
 where t.table_schema = 'public'
   and t.table_name in ('report_template','report_band','report_field','report_param','report_order')
 order by array_position(array['report_template','report_band','report_field','report_param','report_order'],
                         t.table_name);

\echo '== UDR-related grants (не удаляются этим скриптом) =='
select case
         when to_regclass('public.access_grant') is null then 'access_grant table is absent'
         else (xpath('/row/c/text()', query_to_xml(
                'select count(*) as c from access_grant where dimension ilike ''%udr%'' or dimension ilike ''%reportstudio%'' or dimension ilike ''%report_template%''',
                false, true, '')))[1]::text
       end as udr_grants;

begin;

-- Дочерние таблицы — до родителя: FK объявлены NO ACTION, каскад не нужен и не
-- используется, чтобы не задеть посторонние объекты.
drop table if exists public.report_field;
drop table if exists public.report_band;
drop table if exists public.report_param;
drop table if exists public.report_order;
drop table if exists public.report_template;

commit;

\echo '== post-drop check (все regclass должны быть пустыми) =='
select n as table_name, to_regclass('public.' || n) as regclass
  from unnest(array['report_template','report_band','report_field','report_param','report_order']) n
 order by array_position(array['report_template','report_band','report_field','report_param','report_order'], n);

select count(*) as leftover_udr_sequences
  from pg_class c
  join pg_namespace s on s.oid = c.relnamespace
 where s.nspname = 'public' and c.relkind = 'S' and c.relname like 'report\_%';

do $$
declare leftover text;
begin
  select string_agg(n, ', ') into leftover
    from unnest(array['report_template','report_band','report_field','report_param','report_order']) n
   where to_regclass('public.' || n) is not null;
  if leftover is not null then
    raise exception 'DB1 UDR: таблицы не удалены: %', leftover;
  end if;
  raise notice 'DB1 UDR: все пять таблиц удалены в БД % (to_regclass = null)', current_database();
end $$;
