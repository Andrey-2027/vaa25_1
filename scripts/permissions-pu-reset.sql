-- One-time P-U reset, explicitly authorised for the development database.
-- No old grant is migrated. Users, roles, assignments and business tables are preserved.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL statement_timeout = '15s';
SET LOCAL lock_timeout = '5s';
SELECT set_config('permissions.expected_database', :'expected_database', true);
SELECT pg_advisory_xact_lock(hashtextextended('permissions-pu-reset', 0));
LOCK TABLE access_grant IN EXCLUSIVE MODE;
LOCK TABLE app_user, role, user_role, journal, branch IN SHARE MODE;

DO $$
BEGIN
    IF current_database() <> current_setting('permissions.expected_database') THEN
        RAISE EXCEPTION 'Unexpected database';
    END IF;
    IF (SELECT count(*) FROM role WHERE name = 'ADMIN') <> 1 OR NOT EXISTS (
        SELECT 1 FROM app_user u JOIN user_role ur ON ur.user_id = u.id
        JOIN role r ON r.id = ur.role_id WHERE r.name = 'ADMIN' AND u.enabled
    ) THEN
        RAISE EXCEPTION 'An existing enabled administrator assigned ADMIN is required';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE contype = 'f' AND confrelid = 'access_grant'::regclass) THEN
        RAISE EXCEPTION 'Incoming grant references require explicit review before reset';
    END IF;
END $$;

CREATE TEMP TABLE pu_preserved_fingerprints ON COMMIT DROP AS
SELECT 'app_user' AS name, count(*) AS rows, md5(coalesce(string_agg(row_to_json(t)::text, '|' ORDER BY id), '')) AS fingerprint FROM app_user t
UNION ALL SELECT 'role', count(*), md5(coalesce(string_agg(row_to_json(t)::text, '|' ORDER BY id), '')) FROM role t
UNION ALL SELECT 'user_role', count(*), md5(coalesce(string_agg(row_to_json(t)::text, '|' ORDER BY user_id, role_id), '')) FROM user_role t
UNION ALL SELECT 'journal', count(*), md5(coalesce(string_agg(row_to_json(t)::text, '|' ORDER BY id), '')) FROM journal t
UNION ALL SELECT 'branch', count(*), md5(coalesce(string_agg(row_to_json(t)::text, '|' ORDER BY id), '')) FROM branch t;

SELECT current_database() AS database_name, count(*) AS old_grants_deleted FROM access_grant;
DELETE FROM access_grant;

-- Minimal administration: no wildcard, subsystem data, settings or other entity rights.
INSERT INTO access_grant (subject_type, subject_key, dimension, dimension_value_id,
                         can_read, can_update, can_delete, version, created_at, modified_at, created_by, modified_by)
VALUES ('ROLE', 'ADMIN', 'ENTITY:org.ip.model.User', NULL, true, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'permissions-pu-reset', 'permissions-pu-reset'),
       ('ROLE', 'ADMIN', 'ENTITY:org.ip.model.Role', NULL, true, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'permissions-pu-reset', 'permissions-pu-reset'),
       ('ROLE', 'ADMIN', 'ACTION:USER:ASSIGN_ROLES', NULL, false, true, false, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'permissions-pu-reset', 'permissions-pu-reset'),
       ('ROLE', 'ADMIN', 'ACTION:USER:CHANGE_PASSWORD', NULL, false, true, false, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'permissions-pu-reset', 'permissions-pu-reset');

DO $$
DECLARE differences bigint;
BEGIN
    SELECT count(*) INTO differences FROM (
        (SELECT * FROM pu_preserved_fingerprints EXCEPT
            (SELECT 'app_user', count(*), md5(coalesce(string_agg(row_to_json(t)::text, '|' ORDER BY id), '')) FROM app_user t
             UNION ALL SELECT 'role', count(*), md5(coalesce(string_agg(row_to_json(t)::text, '|' ORDER BY id), '')) FROM role t
             UNION ALL SELECT 'user_role', count(*), md5(coalesce(string_agg(row_to_json(t)::text, '|' ORDER BY user_id, role_id), '')) FROM user_role t
             UNION ALL SELECT 'journal', count(*), md5(coalesce(string_agg(row_to_json(t)::text, '|' ORDER BY id), '')) FROM journal t
             UNION ALL SELECT 'branch', count(*), md5(coalesce(string_agg(row_to_json(t)::text, '|' ORDER BY id), '')) FROM branch t))
    ) d;
    IF differences <> 0 THEN RAISE EXCEPTION 'Preserved data changed'; END IF;
    IF (SELECT count(*) FROM access_grant) <> 4 THEN RAISE EXCEPTION 'Unexpected new grant count'; END IF;
END $$;

SELECT name AS preserved_table, rows AS preserved_rows FROM pu_preserved_fingerprints ORDER BY name;
SELECT subject_type, subject_key, dimension, dimension_value_id, can_read, can_update, can_delete
FROM access_grant ORDER BY dimension;
COMMIT;
