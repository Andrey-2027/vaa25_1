-- Этап 10: журнал изменений полей сущностей (field-level audit).
-- Справочный DDL для ручного развёртывания (в dev таблица создаётся
-- ddl-auto=update из JPA-модели EntityChangeLogEntity).

CREATE TABLE IF NOT EXISTS entity_change_log (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    changed_at    TIMESTAMP NOT NULL,
    change_type   VARCHAR(10) NOT NULL,   -- INSERT | UPDATE | DELETE
    entity        VARCHAR(200) NOT NULL,
    entity_id     VARCHAR(100) NOT NULL,
    user_id       VARCHAR(100),
    trace_id      VARCHAR(40),            -- связь с operation_log
    field_count   INT,                    -- число изменённых полей (для грида без разбора JSON)
    payload       JSONB NOT NULL          -- [{"field":"price","old":"100","new":"150"}, ...]
);

CREATE INDEX IF NOT EXISTS ix_ecl_entity ON entity_change_log (entity, entity_id, changed_at DESC);
CREATE INDEX IF NOT EXISTS ix_ecl_user   ON entity_change_log (user_id, changed_at DESC);
