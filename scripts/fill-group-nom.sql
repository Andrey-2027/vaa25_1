-- Заполнение справочника групп номенклатуры (idempotent)
INSERT INTO group_nom(code, name) VALUES
  ('IZD', 'Группа Изделий'),
  ('MAT', 'Группа Материалов'),
  ('DSE_MAIN', 'Группа ДСЕ основная'),
  ('DSE_ADD', 'Группа ДСЕ доп'),
  ('OTHER', 'Группа Прочее')
ON CONFLICT (code) DO NOTHING;
