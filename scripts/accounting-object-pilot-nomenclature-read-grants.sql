-- Repair existing local pilot fixtures: references read by the nomenclature card.
-- Adds missing read grants only; preserves existing grants and all business data.
BEGIN;
DO $pilot$
BEGIN
    IF current_database() !~ '^accounting_object_pilot_[a-z0-9_]+$' THEN
        RAISE EXCEPTION 'This repair requires an isolated accounting_object_pilot_* database';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM app_user WHERE username = 'accounting-pilot') THEN
        RAISE EXCEPTION 'The accounting-pilot fixture must already exist';
    END IF;
END
$pilot$;
INSERT INTO access_grant
    (version, created_at, created_by, subject_type, subject_key, dimension,
     can_read, can_update, can_delete)
SELECT 0, CURRENT_TIMESTAMP, 'pilot-nomenclature-read-repair', 'USER', 'accounting-pilot',
    requested.dimension, TRUE, FALSE, FALSE
FROM (VALUES
    ('ENTITY:org.ip.model.GroupNom'),
    ('ENTITY:org.ip.model.AttributeType'),
    ('ENTITY:org.ip.model.AttributeValue'),
    ('ENTITY:org.ip.model.NomAttributeValue'),
    ('ENTITY:org.ip.model.NomSklAttribute')
) AS requested(dimension)
WHERE NOT EXISTS (
    SELECT 1 FROM access_grant existing
    WHERE existing.subject_type = 'USER' AND existing.subject_key = 'accounting-pilot'
      AND existing.dimension = requested.dimension AND existing.dimension_value_id IS NULL
);
COMMIT;
