-- Supplemental read-only check of the restored pilot fixture, not a replacement for browser/API tests.
BEGIN TRANSACTION READ ONLY;
DO $$
DECLARE a_roles bigint[]; b_roles bigint[];
BEGIN
    IF current_database() !~ '^navigation_pilot_[a-f0-9]{32}$' THEN
        RAISE EXCEPTION 'Expected separate navigation pilot database';
    END IF;
    IF (SELECT count(*) FROM app_user WHERE username IN ('ki-admin','ki-a','ki-b')) <> 3 THEN
        RAISE EXCEPTION 'Incomplete pilot accounts';
    END IF;
    SELECT array_agg(ur.role_id ORDER BY ur.role_id) INTO a_roles
        FROM user_role ur JOIN app_user u ON u.id=ur.user_id WHERE u.username='ki-a';
    SELECT array_agg(ur.role_id ORDER BY ur.role_id) INTO b_roles
        FROM user_role ur JOIN app_user u ON u.id=ur.user_id WHERE u.username='ki-b';
    IF a_roles IS NULL OR a_roles IS DISTINCT FROM b_roles OR cardinality(a_roles) <> 1 THEN
        RAISE EXCEPTION 'Readers must have the same single role';
    END IF;
    IF EXISTS (SELECT 1 FROM access_grant WHERE subject_type='USER' AND subject_key IN ('ki-a','ki-b')) THEN
        RAISE EXCEPTION 'Unexpected individual grants for readers';
    END IF;
    IF (SELECT count(*) FROM access_grant WHERE subject_type='ROLE' AND subject_key='KI_READER'
        AND can_read AND NOT can_update AND NOT can_delete
        AND dimension IN ('ENTITY:org.ip.model.UnitOfMeasurement','ENTITY:org.ip.model.PrdSpec','JOURNAL')) <> 3 THEN
        RAISE EXCEPTION 'Expected explicit read-only grants';
    END IF;
    IF EXISTS (SELECT 1 FROM access_grant WHERE subject_type='ROLE' AND subject_key='KI_READER'
        AND (dimension='ENTITY:org.ip.model.ReceivingDocument' OR can_update OR can_delete)) THEN
        RAISE EXCEPTION 'Unexpected document read or write rights';
    END IF;
    IF (SELECT count(*) FROM navigation_assignment a JOIN app_user u ON a.user_key=u.id::text
        JOIN navigation_profile p ON p.id=a.profile_id
        WHERE (u.username='ki-a' AND p.name='Пилот А — справочники')
           OR (u.username='ki-b' AND p.name='Пилот Б — производство')) <> 2 THEN
        RAISE EXCEPTION 'Expected restored A/B assignments';
    END IF;
END $$;
SELECT u.username, r.name AS role FROM app_user u
    JOIN user_role ur ON ur.user_id=u.id JOIN role r ON r.id=ur.role_id ORDER BY u.username;
SELECT p.name, p.version, count(o.id) AS overrides FROM navigation_profile p
    LEFT JOIN navigation_override o ON o.profile_id=p.id GROUP BY p.id ORDER BY p.name;
SELECT u.username, p.name AS profile FROM navigation_assignment a
    JOIN app_user u ON a.user_key=u.id::text JOIN navigation_profile p ON p.id=a.profile_id ORDER BY u.username;
COMMIT;
