-- Run once with the application stopped, against the SM-1/SM-2 pilot schema.
-- Run on a copy of DBVaa25 first. Old navigation placements and assignments are discarded.
BEGIN;

DROP TABLE IF EXISTS navigation_override;
DROP TABLE IF EXISTS navigation_assignment;
DROP TABLE IF EXISTS navigation_entry;
DROP TABLE IF EXISTS navigation_section;
DROP TABLE IF EXISTS navigation_profile;
DROP TABLE IF EXISTS navigation_structure;

COMMIT;
