-- Рандомное заполнение полей group_nom_id и type_nom в nomenclature
-- Требует предварительно заполненной таблицы group_nom (fill-group-nom.sql)
-- Идемпотентен: заполняет только строки где оба поля NULL
--
-- ВАЖНО: подзапрос "(SELECT ... ORDER BY random() LIMIT 1)" в UPDATE вычисляется
-- один раз (initplan) и всем строкам достаётся одно значение. Поэтому используем
-- массив с volatile-индексом random() — значение выбирается на каждую строку.
UPDATE nomenclature n SET
  group_nom_id = (ARRAY(SELECT id FROM group_nom ORDER BY id))[floor(random() * (SELECT count(*) FROM group_nom))::int + 1],
  type_nom     = (ARRAY['Узел','ДСЕ','Нормали','Материал','ПКИ','Прочее'])[floor(random() * 6)::int + 1]
WHERE n.group_nom_id IS NULL AND n.type_nom IS NULL;

-- Для полной перерандомизации всех строк (админ): тот же UPDATE без WHERE.

-- Проверка распределения
-- SELECT g.code, g.name, count(*) FROM nomenclature n JOIN group_nom g ON g.id=n.group_nom_id GROUP BY g.code, g.name ORDER BY g.code;
-- SELECT type_nom, count(*) FROM nomenclature GROUP BY type_nom ORDER BY type_nom;
