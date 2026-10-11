# Регистр сведений: приёмка Г4 (2026-10-05)

## Проверенное поведение

- Реализация aggregate save принимает server-side contributions вместе с owned sections.
  Отсутствие contribution сохраняет набор; переданный пустой набор очищает его.
  На границе aggregate service код требует ровно одного participant для каждого contribution.
- При ошибке после persistence rollback восстанавливает версию root, owned section,
  оба типа recorder-регистра и их order clocks. Успешное сохранение заменяет оба набора,
  сохраняет section и повышает document version ровно один раз.
- Canonical delete выполняет lock/version check и refresh root, затем очищает через
  registry все регистры, разрешённые данному типу документа, перед reference check и
  удалением root. Ошибка reference check возвращает строки обоих регистров; успешное
  удаление оставляет наборы другого документа и с тем же Long id нетронутыми.
- H2 тест использует настоящий `CanonicalWriteExecutor` и `RecorderSetDeleteParticipant`:
  вместе с двумя регистрами удаляется owned section; отказ reference check откатывает
  все три очистки. Отдельная core integration проверка подтверждает, что stale root после
  lock/refresh отклоняется до вызова participant.
- PostgreSQL проверяет обе стороны гонки delete/replace, stale document edit после
  replacement, rollback при failure и reference-check отказе, bounded lock timeout/retry,
  конкурентные replacement и атомарную работу clock.
- Сгенерированный Hibernate SQL чтения полного recorder set пропускается через узкий RLS
  guard только внутри named recorder scope. Обычные entity lifecycle `persist/update/remove`
  без scope отклоняются. Внутренний `RecorderSetWriter` package-private.

## Результаты тестов

- `InformationRegisterRecorderPersistenceTest`: 11/11 после расширения сквозного сценария
  до двух регистров одновременно.
- `MetadataDrivenAggregateTelemetryTest`: 3/3; `RlsRecorderSetWriteAuthorizerTest`: 1/1;
  `RlsStatementGuardRecorderSetTest`: 3/3; `PlatformCoreSurfaceTest`: 6/6.
- `CanonicalWritePathIT`: 33/33; проверены передача managed locked root участнику удаления,
  конфликт версии при lock/refresh и общий порядок canonical write.
- `RecorderSetWriterPostgresIT`: 6/6 на PostgreSQL 17.10. Тестовая база
  `g3_register_test_codex_g4_20261005_02` создана для прогона и удалена после него.

Среда: Java 21, Hibernate ORM 7.2.12.Final, H2 2.4.240, PostgreSQL 17.10. Полный
тестовый набор приложения не запускался. Для локальной сборки использовался временный
validation POM с отдельным output directory; из него исключалась только неразрешённая в
локальном BOM зависимость `platform-rest-web`. Основной POM в ходе проверки не
перезаписывался.

## Граница проверки

Выполнен source audit путей записи: metadata root использует canonical data path;
`ValidatedJpaCrudService` запрещён для типов с `@EntityMetadata`; у подчинённых строк
нет автономного canonical write capability; прямой JPA lifecycle write блокирует listener.
REST/import и формы не получают отдельной команды записи набора — их доступные write path
упираются в эти же server-side границы. Внешний native/bulk SQL обходит callbacks и
считается запрещённым эксплуатационным обходом; автоматический interceptor для произвольного
внешнего SQL не заявляется.
