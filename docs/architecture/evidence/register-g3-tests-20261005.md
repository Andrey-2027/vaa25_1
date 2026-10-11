# Регистр сведений — проверка Г3 (2026-10-05)

## Что проверялось

- H2 persistence: четыре register-модели, JPA mapping `RecorderRef`, generic-write guard,
  полная замена набора, version conflict, отдельные регистраторы с одинаковыми Long id,
  явное очищение пустым набором, Bean Validation, ошибка ограничения БД после части INSERT,
  rollback clock и версии, ограниченный retry.
- Проверка приложения: `NomenclaturePricePersistenceTest` после включения clock entity в
  рабочий entity scan.
- PostgreSQL 17: два конкурентных replacement с одной ожидаемой версией, победитель ровно один;
  общий clock, разные типы документов с одинаковым id, пустой набор, lock timeout с тремя
  ограниченными попытками, rollback после удаления старых строк и после отказа проверки.

## Результат

- `InformationRegisterRecorderPersistenceTest`: 6/6 прошли.
- `NomenclaturePricePersistenceTest`: 11/11 прошли.
- `RecorderSetWriterPostgresIT`: 2/2 прошли на отдельной временной базе
  `g3_register_test_*`; база удалена после запуска.
- Две конкурентные команды завершились примерно за 90 мс в повторном прогоне. Это проверка
  поведения блокировки, не нагрузочный замер и не основание выбирать PostgreSQL как единственную
  целевую СУБД.

`javac`/тесты запускались на Java 21 и Maven 3.9.6. Чтобы обойти локальную проблему доступа
`javac` к архивам в `%USERPROFILE%\.m2`, использовалась копия Maven-кэша и временный POM под
`target`; исходный `pom.xml` не менялся этой проверкой. Из временного POM исключалась только
неразрешённая в локальном BOM зависимость `platform-rest-web`; исходники и test sources
приложения компилировались.

PostgreSQL integration test проверяет persistence fixture и writer. Он не публикует API записи:
серверная авторизация команды, согласование с canonical document lifecycle и очистка при delete
относятся к Г4.
