# AF4.5 — перенос прикладных колонок (07.11.2026)

К3 закрыт владельцем 07.11.2026. Полный прогон запрещён владельцем и не выполняется.

## Согласованные решения

- Составы 5 listColumns и 15 selectColumns сохраняются в статических конфигурациях форм. Явные составы остаются EXPLICIT, автоматические не замораживаются.
- AccountingObjectCorrection, AccountingObjectInstallation и ReceivingDocument: прежний выбор наследовал массив списка с формальным режимом AUTO. Владелец явно согласовал сохранение колонок через @SelectionColumns и переход выбора в EXPLICIT. Порядок и состав не меняются.
- Списки и выбор используют политику размеров AF3.4. Владелец согласовал удаление старых неиспользуемых width/flexGrow; повторное включение этих значений в новых декларациях изменило бы экран. Табличные части сохраняют действующие размеры через TableSectionCustomization.columns(), состав AUTO.

## Актуальная инвентаризация до переноса

69 объявлений свойств: 51 width и 18 flexGrow (это не 69 ширин). Базы AccountingObjectDocument и AccountingObjectItem включены. Шесть строк секций: AccountingObjectCorrectionItem, AccountingObjectInstallationItem, NomAttributeValue, PrdSpecMtr, PrdSpecOper, ReceivingDocumentItem. Унаследованная настройка nomenclature базы AccountingObjectItem перенесена в обе конкретные строки.

| Поле | width | flexGrow | Действие |
|---|---|---|---|
| AccountingObjectCorrectionItem.accountingType | 180px | — | перенесено в columns() табличной части |
| AccountingObjectCorrectionItem.currentAccountingType | 180px | — | перенесено в columns() табличной части |
| AccountingObjectDocument.number | 150px | — | удалено: не использовалось списком/выбором |
| AccountingObjectDocument.date | 190px | — | удалено: не использовалось списком/выбором |
| AccountingObjectDocument.workshop | — | 1 | удалено: не использовалось списком/выбором |
| AccountingObjectInstallationItem.accountingType | 180px | — | перенесено в columns() табличной части |
| AccountingObjectItem.nomenclature | — | 1 | перенесено в columns() табличной части |
| AccountingType.code | 140px | — | удалено: не использовалось списком/выбором |
| AccountingType.name | — | 1 | удалено: не использовалось списком/выбором |
| AttributeType.code | 150px | — | удалено: не использовалось списком/выбором |
| AttributeType.name | — | 1 | удалено: не использовалось списком/выбором |
| AttributeType.valueType | 180px | — | удалено: не использовалось списком/выбором |
| AttributeType.targetDictionary | 220px | — | удалено: не использовалось списком/выбором |
| AttributeType.active | 100px | — | удалено: не использовалось списком/выбором |
| AttributeValue.attrType | 200px | — | удалено: не использовалось списком/выбором |
| AttributeValue.code | 200px | — | удалено: не использовалось списком/выбором |
| AttributeValue.name | — | 1 | удалено: не использовалось списком/выбором |
| Branch.code | 150px | — | удалено: не использовалось списком/выбором |
| Branch.name | — | 1 | удалено: не использовалось списком/выбором |
| GridFormView.formKey | 220px | — | удалено: не использовалось списком/выбором |
| GridFormView.name | — | 1 | удалено: не использовалось списком/выбором |
| GridFormView.shared | 110px | — | удалено: не использовалось списком/выбором |
| GroupNom.code | 150px | — | удалено: не использовалось списком/выбором |
| GroupNom.name | — | 1 | удалено: не использовалось списком/выбором |
| Journal.code | 150px | — | удалено: не использовалось списком/выбором |
| Journal.name | — | 1 | удалено: не использовалось списком/выбором |
| NomAttributeValue.attrType | 200px | — | перенесено в columns() табличной части |
| NomAttributeValue.attrValue | — | 1 | перенесено в columns() табличной части |
| Nomenclature.code | 150px | — | удалено: не использовалось списком/выбором |
| Nomenclature.name | — | 1 | удалено: не использовалось списком/выбором |
| Nomenclature.unitOfMeasurement | 200px | — | удалено: не использовалось списком/выбором |
| Nomenclature.groupNom | 200px | — | удалено: не использовалось списком/выбором |
| Nomenclature.typeNom | 150px | — | удалено: не использовалось списком/выбором |
| NomenclaturePrice.nomenclature | 300px | — | удалено: не использовалось списком/выбором |
| NomenclaturePrice.price | 160px | — | удалено: не использовалось списком/выбором |
| NomSklAttribute.nomenclature | 250px | — | удалено: не использовалось списком/выбором |
| NomSklAttribute.attrType | 250px | — | удалено: не использовалось списком/выбором |
| NomSklAttribute.required | 120px | — | удалено: не использовалось списком/выбором |
| Oper.code | 150px | — | удалено: не использовалось списком/выбором |
| Oper.name | — | 1 | удалено: не использовалось списком/выбором |
| PrdSpec.journal | 200px | — | удалено: не использовалось списком/выбором |
| PrdSpec.nomenclature | 250px | — | удалено: не использовалось списком/выбором |
| PrdSpec.codeSpec | 200px | — | удалено: не использовалось списком/выбором |
| PrdSpec.draft | 200px | — | удалено: не использовалось списком/выбором |
| PrdSpecMtr.typeMtr | 100px | — | перенесено в columns() табличной части |
| PrdSpecMtr.prdSpecMtr | 250px | — | перенесено в columns() табличной части |
| PrdSpecMtr.nomenclature | 250px | — | перенесено в columns() табличной части |
| PrdSpecMtr.unit | 150px | — | перенесено в columns() табличной части |
| PrdSpecMtr.qt | 150px | — | перенесено в columns() табличной части |
| PrdSpecOper.oper | 250px | — | перенесено в columns() табличной части |
| PrdSpecOper.ceh | 200px | — | перенесено в columns() табличной части |
| PrdSpecOper.route | 200px | — | перенесено в columns() табличной части |
| PrdSpecOper.order | 100px | — | перенесено в columns() табличной части |
| ReceivingDocument.journal | 180px | — | удалено: не использовалось списком/выбором |
| ReceivingDocument.number | 150px | — | удалено: не использовалось списком/выбором |
| ReceivingDocument.date | 150px | — | удалено: не использовалось списком/выбором |
| ReceivingDocument.receivingWorkshop | — | 1 | удалено: не использовалось списком/выбором |
| ReceivingDocument.transferringWorkshop | — | 1 | удалено: не использовалось списком/выбором |
| ReceivingDocumentItem.nomenclature | — | 1 | перенесено в columns() табличной части |
| ReceivingDocumentItem.quantity | 140px | — | перенесено в columns() табличной части |
| Role.name | — | 1 | удалено: не использовалось списком/выбором |
| UnitOfMeasurement.shortCode | 100px | — | удалено: не использовалось списком/выбором |
| UnitOfMeasurement.name | — | 1 | удалено: не использовалось списком/выбором |
| UnitOfMeasurement.code | 100px | — | удалено: не использовалось списком/выбором |
| User.username | 200px | — | удалено: не использовалось списком/выбором |
| User.enabled | 100px | — | удалено: не использовалось списком/выбором |
| Workshop.code | 150px | — | удалено: не использовалось списком/выбором |
| Workshop.name | — | 1 | удалено: не использовалось списком/выбором |
| Workshop.branch | 180px | — | удалено: не использовалось списком/выбором |

## Граница AF5.6

Инвентаризированы и сохранены до реализации отдельного контракта зависимостей:

- AccountingObjectCorrectionItem.currentAccountingType: readOnly=true; существующий собственный редактор строки остаётся.
- PrdSpecMtr.prdSpecMtr: @Lookup.fetch={nomenclature,nomenclature.unitOfMeasurement}.
- PrdSpecMtr.nomenclature: @Lookup.fetch={unitOfMeasurement}.

Их удаление относится к необходимой части AF5.6 пакета 1, а не к переносу составов AF4.5. Пакет 1 этим шагом целиком не закрывается; существующие зависимости чтения сохраняются.

## Снимок П.2

Исправлено измерение размеров: для списка/выбора записываются эффективные ColumnPath.getWidth()/getFlexGrow(), которые применяет ListForm; для секций берутся окончательные resolveColumns() фабрики и свойства с совместимым legacy fallback. Старый снимок читал сырые FieldMetadata и не отражал фактическую политику AF3.4.

Перед миграцией снят эффективный эталон: единственные отличия от прежнего ресурса — width/flexGrow. После миграции допустимы только три согласованных изменения explicit.select=false→true. Поиск, подписи, порядок, поля форм, readOnly и планы чтения сохраняются.

## Проверки

Все 256 целевых проверок успешны (повторы не суммируются):

- Приложение — 132: снимок П.2 (5), конструкции форм, remainingFields, сохранённые виды, права старых форм, reader/platform и patched/platform parity, EntityModificationTest (17), граница чтения. На копиях реальных Branch и AccountingType новый реквизит появляется в автоформе и remainingFields без изменения конфигураций; EXPLICIT не расширяется. Повтор изолированного теста копий после выделения собственной инфраструктуры также успешен.
- platform-core — 15: FormColumnDeclarationsTest, ColumnPathFilterFieldResolverTest, FetchPlanColumnsTest.
- platform-vaadin — 27: TableSectionColumnsTest, FormResolverListColumnsTest, ItemFormLayoutNodesTest.
- entity-design-core — 29: FormConfigurationGenerationTest, ExistingEntityMarkerTest, ExistingEntityValidationTest, ModificationPlanTest; в том числе захваченные версии рецептов.
- entity-design-source — 41: PatcherGeneratorParityTest, FormColumnReaderTest, FormColumnPatcherTest.
- entity-design-json — 12: DraftJsonCodecTest.

Первый ограниченный прогон выявил ошибку области тестовой копии (копирование admin делало его доступным для изменения) и отказы применения во временных каталогах песочницы. Копия ограничена model и views/forms, тесты записи повторены вне песочницы; итоговые результаты выше успешны. Новые конфигурации участвуют в проверке reader/platform через производственный коллектор.

Сохранён autoWidth=true старых колонок секций. Для этого добавлен необязательный autoWidth в ColumnPath.Spec и fluent-свойства колонок; старый конструктор Spec и старые JSON совместимы. Явная сохранённая ширина сбрасывает унаследованный режим подбора по содержимому, reset возвращает его. Это проверено на реальном Grid и JSON round-trip; отдельная прикладная проверка строит все 6 секций и проверяет autoWidth.

В моделях org.ip отсутствуют listColumns/selectColumns и width/flexGrow. Перенесены 17 действующих объявлений свойств секций (18 применений с учётом наследования); 52 неиспользуемых объявления списков/выбора удалены по решению владельца. Составы, эффективные размеры, поиск, подписи, readOnly и все планы чтения совпали с эффективным эталоном до переноса; отличаются только три согласованных флага EXPLICIT. Браузерный прогон отдельно не выполнялся; изменение экранов не вводилось.

Модули platform-core и platform-vaadin установлены в локальный Maven-кэш; изменены только их fingerprints в рамках AF4.5. diagnose-source-fingerprint подтверждает v2/ordinal для обоих. Строгий bootstrap -ValidateOnly по-прежнему останавливается на прежнем стороннем drift platform-report-vaadin (33→34 файла, 66e51a92…→1aa43950…). Диагностический -AllowSourceDrift проходит с этим единственным предупреждением. Чужой модуль не изменялся.

Полный прогон приложения, frontend и полные C/D harness не запускались. AF4.4 остаётся в пакете 3, необходимая часть AF5.6 — следующим шагом пакета 1.

## Коммиты

Перенос оформлен 29 отдельными коммитами по конкретным моделям и двум общим базам. Базы очищены после конкретных строк. Поддержка autoWidth оформлена отдельным предварительным коммитом. Ранее незакоммиченные AF4.1–AF4.3 и посторонние изменения не включались в эти коммиты.

| Область | Коммит |
|---|---|
| platform prerequisite | dc08555c |
| AccountingObjectCorrection | dbd13d76 |
| AccountingObjectCorrectionItem | 2837e63f |
| AccountingObjectInstallation | 818a757d |
| AccountingObjectInstallationItem | 6b25da3a |
| AccountingType | 6a0b21bf |
| AttributeType | 7d84c951 |
| AttributeValue | b6e9940c |
| Branch | 90d91d44 |
| GridFormView | 7fff191a |
| GroupNom | eab5a7bc |
| Journal | 0a5d4c1b |
| NomAttributeValue | 26f566ac |
| Nomenclature | c6ac2585 |
| NomenclatureAccountingObject | e6f834e4 |
| NomenclaturePrice | 3656409a |
| NomSklAttribute | ac321da8 |
| Oper | 6376e47f |
| PrdSpec | b64e8578 |
| PrdSpecMtr | 543a4f87 |
| PrdSpecOper | 62834469 |
| ReceivingDocument | b7311414 |
| ReceivingDocumentItem | b5151940 |
| Role | d763b61e |
| SklNomOpa | 3652bea8 |
| UnitOfMeasurement | ad5d19ff |
| User | 555a4579 |
| Workshop | 35fbc887 |
| AccountingObjectDocument | 7ba12783 |
| AccountingObjectItem | be157a16 |
