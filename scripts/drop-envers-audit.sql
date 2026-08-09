-- Этап 10: удаление таблиц аудита Hibernate Envers.
-- Решение (зафиксировано в docs/field-audit-plan.md): полная замена Envers
-- на entity_change_log; данные *_AUD нигде не использовались.
-- Применять вручную после развёртывания версии без hibernate-envers
-- (иначе ddl-auto=update может пересоздать таблицы при следующем старте).

DROP TABLE IF EXISTS grid_form_view_aud;
DROP TABLE IF EXISTS journal_aud;
DROP TABLE IF EXISTS nomenclature_aud;
DROP TABLE IF EXISTS oper_aud;
DROP TABLE IF EXISTS prd_spec_aud;
DROP TABLE IF EXISTS prd_spec_mtr_aud;
DROP TABLE IF EXISTS prd_spec_oper_aud;
DROP TABLE IF EXISTS receiving_document_aud;
DROP TABLE IF EXISTS receiving_document_item_aud;
DROP TABLE IF EXISTS unit_of_measurement_aud;
DROP TABLE IF EXISTS workshop_aud;
DROP TABLE IF EXISTS revinfo;
