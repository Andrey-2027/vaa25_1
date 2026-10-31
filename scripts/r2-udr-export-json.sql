-- R2/S8: выгрузка одного UDR-шаблона в замороженный формат ipro-report-template/3.
--
-- Запуск:
--   psql "$URL" -U "$USER" -At -v id=4 -f scripts/r2-udr-export-json.sql > template-4.json
--
-- Формат описан в docs/architecture/udr-template-json-format.md.
-- Локальные ключи бэндов — старые id в виде строк; parentKey сохраняет
-- самоссылки (parent_id = id) как есть, чтобы архив не скрывал форму данных.
with bands as (
    select t.id as template_id,
           coalesce(jsonb_agg(
               jsonb_build_object(
                   'key', b.id::text,
                   'parentKey', b.parent_id::text,
                   'kind', b.kind,
                   'position', b.position,
                   'groupField', b.group_field,
                   'startNewPage', b.start_new_page,
                   'titleWidth', b.title_width,
                   'headerLayout', b.header_layout,
                   'fields', coalesce((
                       select jsonb_agg(jsonb_build_object(
                           'kind', f.kind,
                           'queryField', f.query_field,
                           'text', f.text,
                           'caption', f.caption,
                           'width', f.width,
                           'format', f.format,
                           'border', f.border_enabled,
                           'visible', f.visible,
                           'aggregation', f.aggregation,
                           'alignment', f.alignment,
                           'position', f.position
                       ) order by f.position, f.id)
                       from report_field f where f.band_id = b.id
                   ), '[]'::jsonb)
               ) order by b.position, b.id
           ), '[]'::jsonb) as bands
      from report_template t
      left join report_band b on b.template_id = t.id
     group by t.id
), params as (
    select t.id as template_id,
           coalesce(jsonb_agg(jsonb_build_object(
               'name', p.name,
               'caption', p.caption,
               'kind', p.kind,
               'entityClass', p.entity_class,
               'valueSource', p.value_source,
               'required', p.required,
               'showOnForm', p.show_on_form,
               'defaultValue', p.default_value,
               'computed', p.computed,
               'position', p.position
           ) order by p.position, p.id) filter (where p.id is not null), '[]'::jsonb) as params
      from report_template t
      left join report_param p on p.template_id = t.id
     group by t.id
), orders as (
    select t.id as template_id,
           coalesce(jsonb_agg(jsonb_build_object(
               'columnName', o.column_name,
               'direction', o.direction,
               'position', o.position
           ) order by o.position, o.id) filter (where o.id is not null), '[]'::jsonb) as orders
      from report_template t
      left join report_order o on o.template_id = t.id
     group by t.id
)
select jsonb_pretty(jsonb_build_object(
    'format', 'ipro-report-template',
    'schemaVersion', 3,
    'template', jsonb_build_object(
        'name', t.name,
        'description', t.description,
        'targetEntityClass', t.target_entity_class,
        'jpql', t.jpql,
        'querySource', coalesce(t.query_source, 'MANUAL'),
        'maxRows', t.max_rows,
        'timeoutMs', t.timeout_ms,
        'advanced', t.advanced,
        'pageSize', t.page_size,
        'pageOrientation', t.page_orientation,
        'baseFontSize', t.base_font_size,
        'gridEnabled', t.grid_enabled,
        'stripeRows', t.stripe_rows,
        'visualFilterJson', t.visual_filter_json,
        'visualQueryJson', t.visual_query_json,
        'params', p.params,
        'bands', b.bands,
        'orders', o.orders
    )
))
from report_template t
join bands b on b.template_id = t.id
join params p on p.template_id = t.id
join orders o on o.template_id = t.id
where t.id = :id;
