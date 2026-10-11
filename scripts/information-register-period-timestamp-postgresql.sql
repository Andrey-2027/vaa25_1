-- Existing daily prices keep their meaning: DATE becomes local midnight.
-- Run explicitly against the PostgreSQL application database before using the new model.
BEGIN;
ALTER TABLE nomenclature_price
    ALTER COLUMN period_date TYPE timestamp without time zone
    USING CAST(period_date AS timestamp without time zone);
COMMIT;
