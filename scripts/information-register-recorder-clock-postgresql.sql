-- Г3: transactional sequence per recorder-owned register table.
-- Apply through the project's schema migration process. This file was not run against an
-- application database as part of the G3 test run.
CREATE TABLE IF NOT EXISTS ipro_register_recording_clock (
    register_key VARCHAR(255) NOT NULL,
    last_order BIGINT NOT NULL DEFAULT 0 CHECK (last_order >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_ipro_register_recording_clock PRIMARY KEY (register_key)
);
