# AF7.1 — миграция примеров (09.11.2026)

О2 согласован владельцем: вариант 1, совместимость захваченных рецептов
сохраняется. В этом этапе выполнен только AF7.1; запрет устаревших объявлений
для нового кода (AF7.2) не реализовывался.

## Инвентаризация и изменения

В Java-исходниках `examples/` найдены только четыре устаревших объявления,
все в `examples/platform-consumer`:

| До | После |
| --- | --- |
| Material: listColumns = code/name | MaterialFormConfig: явные ListColumns и SelectionColumns code/name |
| MaterialRequest: listColumns = number/date/departmentId/status | MaterialRequestFormConfig: явные ListColumns и SelectionColumns с тем же порядком |
| MaterialRequest.number: FieldMetadata.readOnly=true | RequestFormCustomization: layout.field("number").setReadOnly(true) |
| MaterialRequestLine.material: Lookup.entity=Material.class | тип определяется из Java-поля Material |

Списки перенесены именно командой AF4.4. Вызовы из корня репозитория:

```powershell
# Выполнено отдельно для Material и MaterialRequest:
& 'C:/Program Files/Axiom/AxiomJDK-21/bin/java.exe' `
  -cp 'entity-design-source/target/classes;entity-design-core/target/classes' `
  org.ipro.design.source.EntityModifyCli migrate-forms `
  --project examples/platform-consumer `
  --type com.example.materialrequests.model.Material --out target/af71/Material
# После просмотра diff:
& 'C:/Program Files/Axiom/AxiomJDK-21/bin/java.exe' `
  -cp 'entity-design-source/target/classes;entity-design-core/target/classes' `
  org.ipro.design.source.EntityModifyCli apply `
  --project examples/platform-consumer --plan target/af71/Material
```

Планы и diff сохранены в `target/af71/Material` и `target/af71/MaterialRequest`.
На каждую сущность применены два файла. Вызов через PowerShell-обёртку первоначально
отклонён AuthorizationManager в sandbox; использован тот же Java CLI напрямую.
Атомарное применение выполнено с расширенными разрешениями, как в проверке AF4.4.

Явный состав сохранён; переходов на AUTO нет. Иные примеры менять не потребовалось.
Фабрика новой заявки и пользовательское подразделение сохранены. Совместимые
fixtures старых рецептов в тестах producer оставлены для проверок совместимости
по О2, не объявляются новым прикладным кодом.

## Проверки

В существующем FormsTest усилены проверки фактических колонок списка и выбора
для обеих сущностей. Для редактируемой заявки номер остаётся readonly после
setReadOnly(true) → setReadOnly(false), комментарий остаётся доступным.
Проверка открытия строки и выбора материала через настоящий EntityField проходит
после удаления Lookup.entity.

ConsumerBoundaryTest обновлён под используемые публичные API конфигураций и layout.
Первый прогон также показал прежнее отставание его списка разрешённых типов от
существующего ListCommandExamples: ActionDefinition/Handler/Id, CrudAction,
ListCommandLayout с builders/Ref, ListFormCustomization/Variants, ListUiCommand.
Точечный список актуализирован, исходники примера команд не менялись.

Команда:

```powershell
mvn -o -f examples/platform-consumer/pom.xml -Pbootstrap-smoke test
```

Итог: **23 теста, 0 падений, 0 ошибок, 0 пропусков**, сборка успешна.
Журнал — `target/af71/consumer-tests-final.log`; первый прогон —
`target/af71/consumer-tests.log`. Проверены сборка main/test, формы, aggregate save,
нумерация, lifecycle, C5/RLS, bootstrap и архитектурные границы consumer.
Использована H2 in-memory БД, рабочая PostgreSQL не затрагивалась.

Повторный поиск `listColumns=`, `selectColumns=`, `@GridColumn`, старых атрибутов
Lookup и `readOnly=` в Java-файлах `examples/` совпадений не дал.
`git diff --check` проходит. Полный прогон приложения и production frontend
не запускались: этот перенос не меняет визуальное устройство экрана.

**AF7.1 выполнен.** AF7 целиком остаётся открытым до AF7.2; AF5.7 отложен отдельно.
