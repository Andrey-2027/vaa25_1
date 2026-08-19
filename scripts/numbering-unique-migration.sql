-- Этап: нумерация (docs/numbering-settings-plan.md), пилот ReceivingDocument.
-- Снятие глобального UNIQUE с receiving_document.number: номера теперь выдаются
-- per-журнал (ключ счётчика JOURNAL), ограничение (journal_id, number) добавляет
-- ddl-auto=update сам (аннотация @Table(uniqueConstraints=...)).
-- Применять вручную ПОСЛЕ развёртывания версии с новым мэппингом,
-- до него — не нужно (обновлённый мэппинг всё равно собирается поверх).

-- POSTGRES-ONLY (PL/pgSQL, pg_constraint): одноразовый эксплуатационный скрипт для
-- конкретной инсталляции. Для MSSQL/иной СУБД написать аналог отдельно — не переедет.

-- ^ имя автосгенерированного Hibernate-ограничения непостоянно (UK_<случайный суффикс>),
-- ищем по факту: единственная unique-констрейнта на столбце number.
DO $$
DECLARE
    constrained text;
BEGIN
    SELECT c.conname INTO constrained
    FROM pg_constraint c
    WHERE c.conrelid = 'receiving_document'::regclass
      AND c.contype = 'u'
      AND c.conkey = ARRAY(
          SELECT a.attnum FROM pg_attribute a
          WHERE a.attrelid = 'receiving_document'::regclass AND a.attname = 'number');

    IF constrained IS NOT NULL THEN
        EXECUTE format('ALTER TABLE receiving_document DROP CONSTRAINT %I', constrained);
        RAISE NOTICE 'Dropped unique constraint % on receiving_document(number)', constrained;
    ELSE
        RAISE NOTICE 'No single-column unique constraint on receiving_document(number) found';
    END IF;
END $$;