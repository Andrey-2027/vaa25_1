\timing on
BEGIN;
CREATE TEMP TABLE p3_values(id bigint primary key, code text, name text) ON COMMIT DROP;
CREATE TEMP TABLE p3_grants(subject_type text, subject_key text, dimension text, value_id bigint, can_read boolean) ON COMMIT DROP;
INSERT INTO p3_values SELECT i, 'P3-' || lpad(i::text,5,'0'), 'Branch ' || i FROM generate_series(1,50000) i;
INSERT INTO p3_grants VALUES ('ROLE','reader','BRANCH',4199,true), ('USER','target','BRANCH',1,true);
DO $$
DECLARE n bigint;
BEGIN
 SELECT count(*) INTO n FROM p3_values e WHERE lower(e.code) LIKE '%04199%' AND EXISTS
 (SELECT 1 FROM p3_grants g WHERE g.dimension IN ('BRANCH','*') AND g.can_read
 AND ((g.subject_type='USER' AND g.subject_key='target') OR (g.subject_type='ROLE' AND g.subject_key='reader'))
 AND (g.value_id IS NULL OR g.value_id=e.id));
 IF n <> 1 THEN RAISE EXCEPTION 'filtered selected count mismatch: %', n; END IF;
 INSERT INTO p3_grants VALUES ('USER','target','BRANCH',NULL,true);
 SELECT count(*) INTO n FROM p3_values e WHERE EXISTS
 (SELECT 1 FROM p3_grants g WHERE g.dimension IN ('BRANCH','*') AND g.can_read
 AND ((g.subject_type='USER' AND g.subject_key='target') OR (g.subject_type='ROLE' AND g.subject_key='reader'))
 AND (g.value_id IS NULL OR g.value_id=e.id)
 AND NOT (g.subject_type='USER' AND g.subject_key='target' AND g.dimension='BRANCH' AND g.value_id IS NOT NULL AND g.value_id IN (1)));
 IF n <> 50000 THEN RAISE EXCEPTION 'global/draft NULL membership mismatch: %', n; END IF;
END $$;
EXPLAIN (ANALYZE, BUFFERS) SELECT e.id,e.code,e.name FROM p3_values e WHERE EXISTS
 (SELECT 1 FROM p3_grants g WHERE g.dimension IN ('BRANCH','*') AND g.can_read
 AND ((g.subject_type='USER' AND g.subject_key='target') OR (g.subject_type='ROLE' AND g.subject_key='reader'))
 AND (g.value_id IS NULL OR g.value_id=e.id)) ORDER BY lower(e.code),e.id LIMIT 50 OFFSET 49000;
ROLLBACK;
