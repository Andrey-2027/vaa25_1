-- Read-only verification of the already applied local PostgreSQL schema. No DDL or data writes.
BEGIN READ ONLY;
DO $$
DECLARE expected record; actual text[]; nullable boolean;
BEGIN
  FOR expected IN SELECT * FROM (VALUES
    ('attribute_value','uk_attr_value_type_code_up',ARRAY['attr_type_id','code_up']),
    ('attribute_value','uk_attr_value_type_ref',ARRAY['attr_type_id','ref_id']),
    ('skl_nom_opa','uk_skl_nom_opa_nom_canonical',ARRAY['canonical','nomenclature_id'])
  ) AS keys(table_name,constraint_name,columns) LOOP
    SELECT array_agg(a.attname::text ORDER BY a.attname) INTO actual
      FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid
      JOIN pg_namespace n ON n.oid=t.relnamespace JOIN pg_index i ON i.indexrelid=c.conindid
      CROSS JOIN LATERAL unnest(c.conkey) k(attnum)
      JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.attnum
      WHERE n.nspname=current_schema() AND t.relname=expected.table_name
        AND c.conname=expected.constraint_name AND c.contype='u' AND NOT c.condeferrable
        AND i.indisunique AND i.indisvalid AND i.indpred IS NULL AND i.indexprs IS NULL
        AND NOT i.indnullsnotdistinct;
    IF actual IS DISTINCT FROM expected.columns THEN
      RAISE EXCEPTION 'ET8 applied UNIQUE mismatch %.%: expected %, got %',
        expected.table_name,expected.constraint_name,expected.columns,actual;
    END IF;
  END LOOP;
  FOR expected IN SELECT * FROM (VALUES
    ('attribute_value','attr_type_id',false),('attribute_value','code_up',true),
    ('attribute_value','ref_id',true),('skl_nom_opa','nomenclature_id',false),('skl_nom_opa','canonical',false)
  ) AS columns(table_name,column_name,is_nullable) LOOP
    SELECT c.is_nullable='YES' INTO nullable FROM information_schema.columns c
      WHERE c.table_schema=current_schema() AND c.table_name=expected.table_name AND c.column_name=expected.column_name;
    IF nullable IS DISTINCT FROM expected.is_nullable THEN
      RAISE EXCEPTION 'ET8 applied NULL mismatch %.%',expected.table_name,expected.column_name;
    END IF;
  END LOOP;
END $$;
SELECT 'ET8 applied UNIQUE/NULL constraints verified',current_setting('server_version');
COMMIT;
