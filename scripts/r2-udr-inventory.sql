-- R2: инвентарь UDR-шаблонов на окружении (read-only).
--
-- Запуск (psql, БД приложения):
--   psql "$URL" -U "$USER" -A -F '|' -P footer=off -f scripts/r2-udr-inventory.sql
--
-- Показывает по одной строке на шаблон: привязку к печати, состояние, лимиты,
-- использованные бэнды/поля/агрегаты/параметры/сортировки и число самоссылок
-- parent_id = id (наследие старой модели). Решение по каждому шаблону
-- («пересобрать / архив / не нужен») вносится владельцем в таблицу соответствия,
-- а не выводится из этого запроса.
select t.id,
       t.name,
       t.state,
       coalesce(t.target_entity_class, '-') as target_entity_class,
       coalesce(t.query_source, 'MANUAL') as query_source,
       t.max_rows,
       t.timeout_ms,
       (select count(*) from report_band b where b.template_id = t.id) as bands,
       (select count(*) from report_field f
          join report_band b on b.id = f.band_id
         where b.template_id = t.id) as fields,
       (select count(*) from report_param p where p.template_id = t.id) as params,
       (select count(*) from report_order o where o.template_id = t.id) as orders,
       (select count(*) from report_band b where b.template_id = t.id and b.parent_id = b.id) as self_parents,
       (select string_agg(distinct b.kind, ',' order by b.kind)
          from report_band b where b.template_id = t.id) as band_kinds,
       (select string_agg(distinct f.kind || ':' || f.aggregation, ',' order by f.kind || ':' || f.aggregation)
          from report_field f join report_band b on b.id = f.band_id
         where b.template_id = t.id) as field_kinds,
       (select string_agg(p.kind || '/' || p.value_source || '/' || p.computed, ',' order by p.position)
          from report_param p where p.template_id = t.id) as params_detail,
       (select string_agg(o.column_name || ' ' || o.direction, ',' order by o.position)
          from report_order o where o.template_id = t.id) as orders_detail,
       (select count(*) from report_template x where x.state = t.state) as templates_in_state
  from report_template t
 order by t.id;
