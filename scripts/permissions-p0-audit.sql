-- P0: aggregate diagnostics only; the application database is never modified.
\set ON_ERROR_STOP on
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '15s';

SELECT current_database() AS database_name, current_timestamp AS snapshot_at,
       current_setting('transaction_read_only') AS read_only;

WITH duplicate_groups AS (
    SELECT count(*) AS copies,
           count(DISTINCT (can_read, can_update, can_delete)) AS variants
    FROM access_grant
    GROUP BY subject_type, subject_key, dimension, dimension_value_id
    HAVING count(*) > 1
)
SELECT 'users' AS metric, count(*) AS value FROM app_user
UNION ALL SELECT 'roles', count(*) FROM role
UNION ALL SELECT 'grants', count(*) FROM access_grant
UNION ALL SELECT 'journals', count(*) FROM journal
UNION ALL SELECT 'branches', count(*) FROM branch
UNION ALL SELECT 'duplicate_grant_groups', count(*) FROM duplicate_groups
UNION ALL SELECT 'duplicate_extra_rows', coalesce(sum(copies - 1), 0) FROM duplicate_groups
UNION ALL SELECT 'duplicate_groups_with_different_flags', count(*) FROM duplicate_groups WHERE variants > 1
UNION ALL SELECT 'user_grants_without_persisted_user', count(*) FROM access_grant g
    WHERE g.subject_type = 'USER' AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.username = g.subject_key)
UNION ALL SELECT 'role_grants_without_persisted_role', count(*) FROM access_grant g
    WHERE g.subject_type = 'ROLE' AND NOT EXISTS (SELECT 1 FROM role r WHERE r.name = g.subject_key)
UNION ALL SELECT 'journal_grants_without_value', count(*) FROM access_grant g
    WHERE g.dimension = 'JOURNAL' AND g.dimension_value_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM journal j WHERE j.id = g.dimension_value_id)
UNION ALL SELECT 'branch_grants_without_value', count(*) FROM access_grant g
    WHERE g.dimension = 'BRANCH' AND g.dimension_value_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM branch b WHERE b.id = g.dimension_value_id)
UNION ALL SELECT 'legacy_attribute_grants', count(*) FROM access_grant WHERE dimension LIKE 'ATTR:%'
UNION ALL SELECT 'value_scoped_entity_action_rest_grants', count(*) FROM access_grant
    WHERE dimension_value_id IS NOT NULL
      AND (dimension LIKE 'ENTITY:%' OR dimension LIKE 'ACTION:%' OR dimension LIKE 'REST:%')
UNION ALL SELECT 'blank_subject_or_dimension', count(*) FROM access_grant
    WHERE trim(subject_key) = '' OR trim(dimension) = ''
UNION ALL SELECT 'all_flags_false', count(*) FROM access_grant
    WHERE NOT can_read AND NOT can_update AND NOT can_delete
UNION ALL SELECT 'rls_write_without_read', count(*) FROM access_grant
    WHERE dimension IN ('JOURNAL', 'BRANCH', '*') AND NOT can_read AND (can_update OR can_delete)
ORDER BY metric;

SELECT dimension, subject_type, count(*) AS grant_rows,
       count(DISTINCT subject_key) AS subjects,
       count(*) FILTER (WHERE dimension_value_id IS NULL) AS global_rows,
       count(*) FILTER (WHERE dimension_value_id IS NOT NULL) AS value_rows,
       count(*) FILTER (WHERE can_read) AS read_rows,
       count(*) FILTER (WHERE can_update) AS update_rows,
       count(*) FILTER (WHERE can_delete) AS delete_rows
FROM access_grant GROUP BY dimension, subject_type ORDER BY dimension, subject_type;

SELECT role_count, count(*) AS users
FROM (SELECT u.id, count(ur.role_id) AS role_count
      FROM app_user u LEFT JOIN user_role ur ON ur.user_id = u.id GROUP BY u.id) counts
GROUP BY role_count ORDER BY role_count;

-- System owners can have deliberate non-login subjects. These are not deleted automatically.
SELECT subject_type, count(*) AS missing_subject_grants,
       count(DISTINCT subject_key) AS missing_subject_keys
FROM access_grant g
WHERE (subject_type = 'USER' AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.username = g.subject_key))
   OR (subject_type = 'ROLE' AND NOT EXISTS (SELECT 1 FROM role r WHERE r.name = g.subject_key))
GROUP BY subject_type ORDER BY subject_type;

SELECT dimension_value_id AS missing_branch_id, count(*) AS referring_grants
FROM access_grant g
WHERE dimension = 'BRANCH' AND dimension_value_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM branch b WHERE b.id = g.dimension_value_id)
GROUP BY dimension_value_id ORDER BY dimension_value_id;

-- The ADMIN prerequisite does not replace these exact C5 grants.
WITH administrators AS (
    SELECT DISTINCT u.id, u.username FROM app_user u
    JOIN user_role ur ON ur.user_id = u.id JOIN role r ON r.id = ur.role_id
    WHERE r.name = 'ADMIN'
), required(key, operation, flag) AS (
    VALUES ('ENTITY:org.ip.model.User', 'READ', 'read'),
           ('ENTITY:org.ip.model.Role', 'READ', 'read'),
           ('ENTITY:User', 'CREATE/UPDATE', 'update'),
           ('ENTITY:User', 'DELETE', 'delete'),
           ('ENTITY:Role', 'CREATE/UPDATE', 'update'),
           ('ENTITY:Role', 'DELETE', 'delete'),
           ('ACTION:USER:ASSIGN_ROLES', 'EXECUTE', 'update'),
           ('ACTION:USER:CHANGE_PASSWORD', 'EXECUTE', 'update')
)
SELECT required.key, required.operation,
       (SELECT count(*) FROM administrators) AS administrators,
       (SELECT count(*) FROM administrators a WHERE EXISTS (
           SELECT 1 FROM access_grant g
           WHERE g.dimension = required.key AND g.dimension_value_id IS NULL
             AND CASE required.flag WHEN 'read' THEN g.can_read WHEN 'update' THEN g.can_update ELSE g.can_delete END
             AND ((g.subject_type = 'USER' AND g.subject_key = a.username)
               OR (g.subject_type = 'ROLE' AND EXISTS (
                   SELECT 1 FROM user_role ur JOIN role r ON r.id = ur.role_id
                   WHERE ur.user_id = a.id AND r.name = g.subject_key)))
       )) AS administrators_with_grant
FROM required ORDER BY required.key, required.operation;

ROLLBACK;
