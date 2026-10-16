-- Explicit development schema installation. PostgreSQL 15+ NULLS NOT DISTINCT protects global rows.
BEGIN;
CREATE UNIQUE INDEX IF NOT EXISTS ux_access_grant_subject_address
ON access_grant (subject_type, subject_key, dimension, dimension_value_id) NULLS NOT DISTINCT;
-- Verify PostgreSQL NULL uniqueness on an isolated temporary fixture, not live subjects.
CREATE TEMP TABLE p4_unique_probe(subject_type text, subject_key text, dimension text, value_id bigint,
    UNIQUE NULLS NOT DISTINCT(subject_type, subject_key, dimension, value_id)) ON COMMIT DROP;
INSERT INTO p4_unique_probe VALUES ('ROLE','probe','ENTITY:probe',NULL);
DO $$ BEGIN
    BEGIN
        INSERT INTO p4_unique_probe VALUES ('ROLE','probe','ENTITY:probe',NULL);
        RAISE EXCEPTION 'NULL logical-address duplicate was accepted';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;
END $$;
COMMIT;
