# G1 extension: зафиксированные контракты E0

Дата: 2026-10-29. Нормативный baseline E0 для
[плана продолжения](../plans/entity-generation-g1-extension-plan.md).
API inherited/dispatch/manifest опубликованы в core; однофайловый C/D v2 принят в E1,
N-file C2/D2 foundation реализован в E4.C0/D0. Owner/JSON v2 реализованы и проверены
в E3; actual A→C→D для комплекта секций ожидается после E4.A/C.
E0 не закрывает E1–E5 или migration gate M.

Актуализация E1.A/B (2026-10-29): document draft/validator/JSON и scalar Java
подключены, в P8 есть выбор вида нового черновика и document preview/download.
В E0 документ был HANDOFF_ONLY; теперь он DRAFT_READY.

Актуализация E1.C/D (2026-10-29): C выдаёт C2 с manifest/dependencies/suites,
D2 применяет `document-java-v1` в отдельный `.g1-apply-v2` ledger. Actual
EmptyDocument и ScalarDocument прошли C2 и D2 APPLIED/NOOP; evidence:
[e1-cd](evidence/entity-generation-g1-extension-20261029/e1-cd/README.md).
Owner, ссылки, секции, actual N-file integration после E4.A/C, browser и migration gate
остаются последующими пакетами.

Приёмка E1 завершена 2026-10-29: [независимый итог, 156 tests, C/D regression и applied runtime](evidence/entity-generation-g1-extension-20261029/e1-acceptance/README.md).

Актуализация E2 (2026-10-29): текущий dispatcher использует `catalog-java-v2` и
`document-java-v2`, F2/M2/C2/D2 для scalar/reference roots. Фиксированный
`CatalogJavaGenerator` сохраняет legacy v1/F1/C1/D1. Цель ссылки задаётся проверенным
`ReferenceTargetDescriptor`: добавлен `sourceTypeName` — canonical Java name из
runtime adapter, без reflection в core. Старый восьмиаргументный constructor и
десериализация прежнего context используют `typeId.replace('$', '.')` как default.
Это поле участвует в freshness и F2; сериализация F1 остаётся прежней. Генератор
использует полное source name, LAZY ManyToOne и явный JoinColumn без cascade.
C2 требует `ReferenceScenarioTest` для планов со ссылками; D2 выводит обязательную
suite из source plan и заново хэширует полный набор current dependency locations.
Результаты E2: [core/P8, C2/D2, runtime/components](evidence/entity-generation-g1-extension-20261029/e2/README.md).

Актуализация E3.A (2026-10-29): OwnerDraft, checked owner и отдельный projection
binding реализованы; codec поддерживает отдельный envelope v2. Текущие generators
— catalog-java-v3/document-java-v3; owner только у standard-catalog-v2@2. В F2
добавлены raw owner, resolved owner/binding и portable owner facts; F1 не менялся.
Импорт/экспорт v1 и старый шестипараметрический constructor EntityDraft сохранены.
Это выполнение E3.A; owner UI/E3.B и actual C/D/runtime приёмка E3.C остаются открыты.
[Evidence E3.A](evidence/entity-generation-g1-extension-20261029/e3-a/README.md).

Актуализация E3.B (2026-10-30): P8 редактирует отдельный OwnerDraft с явным
переходом catalog-v2 в envelope v2 при включении owner. Legacy recipe не меняется;
у документа недопустимый импортированный owner можно удалить. Стандартный ItemForm
блокирует owner при persisted id и при opening owner-context; form-wide readOnly
не снимает эту блокировку. Fixed list/selection filters сохраняют owner; заполненный
opening owner передаётся в initialValues создания. ItemForm ограничивает
CatalogOwnerContext временем save-handler, с восстановлением thread context.
Explorer переносит resolved CatalogOwnerDescriptor отдельно от section parent;
отсутствие capability и недоступность facts показываются раздельно.
[Evidence E3.B](evidence/entity-generation-g1-extension-20261029/e3-b/README.md).
Actual generated owner C/D/runtime — отдельная приёмка E3.C; browser — E5.B.

Приёмка E3.C завершена 2026-10-30: OwnedCatalog и document envelope v1/v2 получены
через JSON/validator/builder и прошли C2, D2 APPLIED/NOOP и runtime из applied projects.
Owner case требует `com.example.generated.harness.OwnerScenarioTest`; D выводит
требование из `plan.source().owner()`, а не из заявления C summary. Проверены
owner-filter A/B в LIST/LOOKUP, required-null/missing target, immutable A→B,
opening context mismatch, delete/reference checks и отсутствие grants inheritance.
Обычная ссылка owner без capability меняется/очищается. Document v2 с owner=null
проходит; non-null owner остаётся invalid JSON draft без checked/plan. C summaries
document v1/v2 нельзя переставить даже при одинаковых Java bytes. [Evidence E3.C](evidence/entity-generation-g1-extension-20261029/e3-c/README.md).
Gate E3 закрыт в scope R/A/B/C; E4, E5/browser и M остаются открытыми.

Актуализация E4.C0/D0 (2026-10-30): C2 принимает полный N-entry source set и
публикует `sources`/`sourceSetSha256`; D2 проверяет весь manifest/C/dependency set,
делает preflight всех targets, публикует entries последовательно, откатывает только
собственные подтверждённые файлы и требует `MultiFileScenarioTest` для N>1. Evidence:
[C0](evidence/entity-generation-g1-extension-20261029/e4-c0/README.md),
[D0](evidence/entity-generation-g1-extension-20261029/e4-d0/README.md).
Actual N-file A→C→D, SQL/runtime и browser остаются после E4.A/C.

P8-адаптер части E4.B реализован 2026-10-30: reviewed `GenerationPlan` показывается
по файлам, один файл скачивается как `.java`, N>1 — как ZIP с корневым
`generation-manifest.json`; freshness/access проверяются на весь план, JSON checkpoint
не меняется. [Evidence E4.B](evidence/entity-generation-g1-extension-20261029/e4-b/README.md).
Section-формы и actual A→C→D остаются открытыми.

Уточнено 2026-10-29 по замечаниям к плану: версии по срезам, owner projection,
dependency freshness, перенос проекта, статусы D, ZIP и диагностики определены ниже.
Это дополнение спецификации будущих E1–E4; ранее опубликованные результаты 85 tests
подтверждают исходный E0 API baseline, а не реализацию этих уточнений.

## E0.1 — точные рецепты

| Recipe | Base | Inherited/reserved subject names | Default list / selection | Numbering |
| --- | --- | --- | --- | --- |
| standard-catalog-v1@1 | StandardCatalogEntity | code, name | code, name | code: CATALOG_CODE |
| standard-catalog-v2@2 | StandardCatalogEntity | code, name | code, name | code: CATALOG_CODE |
| standard-document-v1@1 | StandardDocumentEntity | number, date | number, date | number: DOCUMENT_NUMBER, dateField=date |

`RecipeCatalog.inheritedFields(RecipeRef)`, `baseType(RecipeRef)` и
`defaultPublications(RecipeRef)` принимают только эти точные refs. Старый
`inheritedFields()` сохраняет code/name. System names из `systemFieldNames()`
резервируются дополнительно у обоих видов. Catalog STRING lengths 50/200,
document number STRING(50), date DATE без scalar length. Все четыре поля
serverRequired / UI REQUIRED, порядки 10/20, пользователь их не переопределяет.
Представление документа: номер + « от » + дата, fallback только номер/дата/пустая строка
по StandardDocumentEntity. Нет implicit UNIQUE или новой numbering policy.
Проверка базы подтвердила `@NotNull date`; отсутствие даты — отрицательный сценарий.

## E0.2 — вход генерации

```java
new EntityJavaGenerator().generate(CheckedStructure checked,
    EntityDraft currentDraft, ProjectDesignContext currentContext); // GenerationResult
```

Сохраняются immutable result/diagnostics, addressable targets и freshness.
В baseline E0 catalog-v2 делегируется существующему `CatalogJavaGenerator`, включая прежние bytes,
planId и диагностики. Catalog-v1 и HANDOFF_ONLY отклоняются UNSUPPORTED_RECIPE.
Document подключён в этом единственном dispatcher в E1.A как document-java-v1.
Старый CatalogJavaGenerator остаётся совместимым публичным входом только catalog-v2.
P8 `DraftEditorSession` переведён на dispatcher в E1.B; D `GenerationApplyAdapter`
перейдёт в E1.D;
UI/D не выводят base type из kind и не формируют собственный Java.
Начиная с E2 dispatcher выбирает текущий extension generator по таблице E0.3.1.
Legacy `catalog-java-v1` сохраняет отдельный фиксированный вход и контрольный rebuild
для MatrixLegacy; D выбирает поддержанный профиль по recipe/generatorVersion, а не
перегенерирует старый план текущей версией dispatcher. Выбор неподдержанного профиля
отклоняется до CREATE/NOOP; чужая версия не переключается на текущую автоматически.

## E0.3 — DTO, JSON, runtime facts и версии

| Область | Сохранённый baseline | Следующее изменение |
| --- | --- | --- |
| Legacy recipe | standard-catalog-v1@1 и create() | Без переосмысления |
| Catalog generator | catalog-java-v1 | Изменение output/scope требует catalog-java-v2 |
| Document generator | Отсутствует | document-java-v1 в E1 |
| Plan fingerprint | plan-fingerprint-v1 для старого catalog | plan-fingerprint-v2 для extension; не менять v1 |
| Draft envelope | schemaVersion=1, строгий JSON codec/schema | Document без новых structural fields допускает v1 в E1 |
| Owner envelope | Не поддержан | schemaVersion=2 в E3, отдельная schema |
| Manifest / C summary / D state | Существующие v1 adapters | Независимые v2 ниже, без auto-upgrade старого ledger |

В E1.A реализован document-java-v1 с plan-fingerprint-v2, envelope по-прежнему v1.
Для текущего scalar scope fingerprint v2 сериализует полный draft/context и ordered
file operation/path/SHA-256/UTF-8 byte count. Нормализованные scalar решения отражены
в generated content hash; runtime/target facts входят через полный context.
Legacy v1 сериализация и catalog-java-v1 не изменены. В E3.A добавлены owner DTO и
соответствующая ветка F2 по E0.3.3; старые E1/E2 результаты остаются историческими.

### E0.3.1 — матрица версий по срезам

В таблице: F1/F2 = `plan-fingerprint-v1/v2`; M2 = `g1-generation-manifest-v2`;
C1 = legacy subset `g1-c-summary-v1`, C2 = `g1-c-summary-v2`;
D1/D2 = receipt/journal `g1-apply-*-v1/v2`. Номер envelope независим от этих версий.
Столбец Draft envelope обозначает поддержанный вход генерации; структурный import
семантически ошибочного draft и его адресные diagnostics не включают новый scope генератора.

| Срез / recipe | generatorVersion | Fingerprint | Manifest | C summary | D state | Draft envelope |
| --- | --- | --- | --- | --- | --- | --- |
| E0–E5 legacy standard-catalog-v2@2 | catalog-java-v1 | F1 | Legacy single-source transport, без M2 | C1 | D1 | 1 |
| E1 standard-document-v1@1, scalar | document-java-v1 | F2 | M2, 1 entry | C2 | D2 | 1 |
| E2 standard-catalog-v2@2, scalar/reference | catalog-java-v2 | F2 | M2, 1 entry | C2 | D2 | 1 |
| E2 standard-document-v1@1, scalar/reference | document-java-v2 | F2 | M2, 1 entry | C2 | D2 | 1 |
| E3 standard-catalog-v2@2, optional owner capability | catalog-java-v3 | F2 | M2, 1 entry | C2 | D2 | 1 без owner; 2 с owner |
| E3 standard-document-v1@1, owner запрещён; envelope v2 с owner=null | document-java-v3 | F2 | M2, 1 entry | C2 | D2 | 1 или 2 с owner=null |
| E4–E5 standard-catalog-v2@2, 0/1/2+ sections | catalog-java-v4 | F2 | M2, 1+ entries | C2 | D2 | 1 без owner; 2 с owner |
| E4–E5 standard-document-v1@1, 0/1/2+ sections | document-java-v4 | F2 | M2, 1+ entries | C2 | D2 | 1 или 2 с owner=null |

`standard-catalog-v1@1` остаётся capture/import recipe без генерации и не получает
ни одного extension-профиля. Каждый extension generator поддерживает весь scope
своей строки, включая простой scalar root; нельзя выбирать более старый профиль
по отсутствию reference/owner/section. Аргументы публичного generate не меняются:
текущий профиль recipe фиксируется сборкой согласно достигнутому срезу.

Legacy вход `CatalogJavaGenerator` остаётся `catalog-java-v1` и выдаёт прежние bytes,
F1/planId/diagnostics. Extension builders используют отдельные versioned правила;
общий refactoring не должен менять legacy output. D поддерживает legacy handler и
текущие extension-профили своей сборки. Предыдущий extension generator, не входящий
в этот явный whitelist, получает `REJECTED/UNSUPPORTED_GENERATOR_PROFILE` даже при
совпадении bytes. NOOP проверяется только в пределах того же поддержанного профиля.

Bump версии означает новый planId. Для приёмки следующего среза берётся чистый
project/output root; старый receipt не даёт MODIFY или ownership нового плана.
E3 добавляет документу допустимый envelope v2 с owner=null, поэтому меняет его
generator на v3 без введения document owner; sections в E4 меняют его на v4.
После фиксации профиля исправление правил, меняющее
output, поддержанный scope или fingerprint, требует нового явного bump этой таблицы.

**Условие E1:** C2 и D2 должны работать для manifest из одного документа уже в E1.C/D.
E4.C0/D0 готовят тот же v2 формат и многофайловый механизм параллельно; E1 ждёт только
однофайловый v2 путь, E4 — приёмку полного N-file поведения. Расширять C1/D1 на документ
или вводить временный третий формат не требуется. M2 не означает, что N-file apply уже принят.

### E0.3.2 — owner: типы и границы модулей

Child — **справочник договоров**, owner — **справочник контрагентов**: оба CATALOG.
Документ не получает owner capability в этом этапе. API зарезервирован однозначно:

```java
// platform-contracts, org.ipro.metadata.annotation; TYPE, RUNTIME, без @Inherited
@CatalogOwner(property = "counterparty")
// entity-design-core, необработанный ввод сохраняется, допускает незавершённость
record OwnerDraft(String targetTypeId, String fieldName, DraftText columnName, String label) {}
// platform-core, org.ipro.metadata; resolved runtime descriptor
record CatalogOwnerDescriptor(String property, String targetTypeId) {}
// entity-design-core, вложенный portable record RuntimeDesignSnapshot
record CatalogOwnerFacts(String property, String targetTypeId) {}
```

Capability относится к catalog root, один target CATALOG с подтверждённым Long ID,
managed root, representation и selection. Тип цели берётся из association property.
Обычный reference с именем owner/parent не получает capability. Section parent не owner.
Owner обязательный, immutable после persist; cascade roots и перенос запрещены.
Сервер сравнивает owner с persisted state до побочных действий; context owner должен
совпадать с полем. Проверка удаления остаётся reference-check/FK. RLS/grants не наследуются.

В E3 `EntityDraft` добавляет nullable `OwnerDraft owner` с прежним шестипараметрическим
конструктором, делегирующим owner=null. withEntity/withFields/withSections сохраняют owner.
CheckedStructure добавляет resolved owner по E0.3.3, без дублирования в subject fields.
Envelope v2 содержит обязательный `owner` (null или объект с четырьмя
полями выше); v1 не принимает owner даже null. Raw text, UUID и DraftText mode/value
сохраняются без нормализации; export owner через v1 запрещён с явной ошибкой.
Импорт v1, старые constructors и fixtures остаются работающими.
В E3 decoder v2 может сохранять документный draft с ошибочно заданным owner как
семантически невалидный ввод: validator выдаёт OWNER_UNSUPPORTED_RECIPE, генерации нет.
Document envelope v2 с owner=null поддерживается с document-java-v3 в E3; v1 остаётся
поддержанным, автоматическая конверсия старого draft и смена его recipe не требуются.

`RuntimeDesignSnapshot.TypeFacts` получает nullable portable `CatalogOwnerFacts owner`,
прежние constructors сохраняются как overload с null. Target eligibility для обычной
ссылки остаётся существующим ReferenceTargetDescriptor.supported(); для owner дополнительно
проверяется TypeFacts.kind=CATALOG. UNKNOWN, неполный snapshot или неизвестный kind
не становятся AVAILABLE. Не кодировать kind в caption/reason/exposure.
Runtime projection переносит kind, identity, managed-root, representation/selection,
owner facts и provenance; platform-core не зависит от design-core/Vaadin.
`entity-design-runtime` преобразует platform `CatalogOwnerDescriptor` в core
`CatalogOwnerFacts`. Ни одна сторона не импортирует DTO другой; runtime projection
остаётся единственным мостом. FQN runtime descriptor и portable facts различаются.

### E0.3.3 — owner в checked/projection/builder

```java
// Внутри CheckedStructure, entity-design-core; nullable owner() у корня
record Owner(String targetTypeId, String fieldName, String columnName,
             String label, int order, FieldApplications applications) {}
// Внутри FieldApplicationMapping; nullable owner() в Projection
record OwnerBinding(String property, int order, boolean emitFieldMetadata,
                    boolean hidden, boolean gridVisible,
                    String requiredMode, boolean serverRequired) {}
```

- Owner не имеет UUID и не помещается в `Projection.fields()`/UUID-keyed Binding.
  Projection получает отдельный nullable `OwnerBinding owner`; старый constructor
  без owner делегирует с null. `OwnerDraft`, `CheckedStructure.Owner` и OwnerBinding
  сопоставляются по единственной owner capability, не по искусственному fieldId.
- Checked owner хранит разрешённые target/SQL column и исходную подпись. Его
  applications фиксированы `FieldApplications.root()` = form/list/selection true,
  table false; пользовательских флагов публикации owner в этом срезе нет.
- `order=30`, serverRequired=true, requiredMode=REQUIRED, emitFieldMetadata=true,
  hidden=false, gridVisible=false. List/select добавляют owner после inherited
  `code/name`, затем subject fields. Form metadata содержит owner на позиции 30.
- При наличии owner собственные root fields получают `40 + 10*i`; без owner —
  `30 + 10*i`. Java declarations/accessors: owner, затем subject fields. Builtins
  всегда 10/20. Динамический запрет редактирования owner после persist задаёт
  runtime/form capability; статическое readOnly=true не блокирует его выбор при create.
- Builder выбирает base по recipe API; обрабатывает owner отдельно до UUID bindings
  и генерирует `@CatalogOwner`, JPA association, NotNull и field metadata. Отсутствие
  или несогласованность OwnerBinding — `PROJECTION_UNREPRESENTABLE` на `owner`.
- Owner target/name/column/label/order/applications, binding и portable owner facts
  входят в F2. Обычный reference с тем же именем не получает этот binding.

### E0.3.4 — диагностики и обязательность

`DraftValidator` остаётся semantic rule engine. Для уже существующих ошибок
используются DraftProblem.Code, а GenerationDiagnostic описывает ограничение
поддержанного generation scope/projection. Массового дублирования кодов не вводится.

| Случай | Код | DraftProblem.Target |
| --- | --- | --- |
| Owner у recipe документа | Новый DraftProblem `OWNER_UNSUPPORTED_RECIPE` | draftId, null, null, `owner` |
| Owner target пуст / недопустим / факты неизвестны | `REFERENCE_REQUIRED` / `REFERENCE_UNAVAILABLE` / `PROJECT_FACT_UNKNOWN` | draftId, null, null, `owner.targetTypeId` |
| Owner label/name/column невалидны | Существующие `VALUE_REQUIRED`, `INVALID_JAVA_IDENTIFIER`, `INVALID_STORAGE_NAME` | draftId, null, null, `owner.label` / `owner.fieldName` / `owner.columnName` |
| Owner name/column конфликтуют | `DUPLICATE_NAME`, `RESERVED_FIELD`, `DUPLICATE_STORAGE_NAME` | draftId, null, null, `owner.fieldName` / `owner.columnName` |
| Reference target невалиден | Существующие `REFERENCE_REQUIRED`, `REFERENCE_UNAVAILABLE`, `PROJECT_FACT_UNKNOWN` | draftId, sectionId или null, fieldId, `reference.typeId` |
| Section parent/lineNumber конфликтуют с subject field | Существующие `RESERVED_FIELD`, `DUPLICATE_STORAGE_NAME` | draftId, sectionId, fieldId, `name` / `columnName`; для parent — fieldId=null, `parentFieldName` / `parentColumnName` |
| Рецепт не генерируется / checked устарел | Generation `UNSUPPORTED_RECIPE` / `STALE_CHECKED_STRUCTURE` | draftId, null, null, `recipe` / `checkedStructure` |
| Reference/owner/section/envelope ещё вне профиля | Generation `UNSUPPORTED_CONSTRUCT`, parameter construct=REFERENCE/OWNER/SECTION/ENVELOPE | Reference: sectionId/fieldId и `type`; owner: null/null и `owner`; section: sectionId/null и `sections`; envelope: null/null и `schemaVersion` |
| Нельзя представить applications/owner binding | Generation `PROJECTION_UNREPRESENTABLE` | draftId, null, null, `applications` / `owner` |
| serverRequired=true + uiRequired=OPTIONAL | Generation `UNREPRESENTABLE_REQUIRED_MODE` | draftId, sectionId или null, fieldId, `uiRequired` |

У owner все targets имеют fieldId=null; UI использует propertyKey `owner` или
`owner.*` для перехода к отдельной owner-панели. Нового UUID и поиска такого поля
в subject fields нет. Для DTO envelope v1 с неизвестным owner сначала действует
строгий JSON refusal; semantic диагностика применяется к корректному envelope v2.

Пять существующих GenerationDiagnostic.Code достаточны; новый semantic код здесь
один — `OWNER_UNSUPPORTED_RECIPE`. В будущем новый код вводится с точным target и
проверкой UI routing в этом документе. Required-mode rejection одинаков для catalog,
document, reference и scalar fields строк. Warning `UI_REQUIRED_MISMATCH` validator
сохраняется; builder обязан отклонять указанную пару до source output, а не считать
warning разрешением на генерацию. У owner обязательность фиксирована контрактом.

Fingerprint v2 включает полный raw draft (включая owner), resolved checked facts и
полный context snapshot/target facts, recipe, generator version и все entries в порядке
root → rows draft order. Любая смена target/owner facts/context делает checked/plan stale.
Смена реализации v1 запрещена; новые поля требуют отдельной ветки сериализации v2.
Core не загружает Class, не выполняет filesystem IO и не зависит от Jackson/Vaadin.

## E0.4 — transport manifest, C и D

Единый manifest — Java record `GenerationManifest` в entity-design-core. JSON adapter
использует имена record components; recipe имеет `{id, version}`, operation строка CREATE.
Обязательные поля: kind=`g1-generation-manifest-v2`, planId, recipe, generatorVersion,
fingerprintProfile, entries, manifestSha256. Entry: operation, relativePath, sha256, bytes.
Content остаётся в GenerationPlan.files; абсолютных путей в manifest нет.
`from(plan, profile)` сверяет file digest с content; `verify(plan, profile)` проверяет
точное равенство binding и всех entries. Порядок значим. Профиль обязан передаваться
из выбранной версии генератора, а не угадываться получателем из имени файла.

Hash `g1-file-set-sha256-v2`: SHA-256 от бинарной последовательности:

1. framed UTF-8 string `g1-file-set-sha256-v2`;
2. big-endian int32 count;
3. для каждого entry: framed relativePath, framed operation, big-endian int64 bytes,
   framed lowercase hex sha256.

Framed string = big-endian int32 UTF-8 byte length + точные UTF-8 bytes (без BOM).
Entries не сортируются. Unicode content считается в UTF-8 bytes, LF не конвертируется.
Path syntax — GenerationFile; дополнительно уникальность по ASCII case-insensitive
Java paths. Realpath/ADS/device/alias guards по-прежнему обязательны в filesystem adapter.
Manifest hash связывает комплект, planId дополнительно связывает draft/context.
Хэш не является подписью и не заменяет D контрольный rebuild.

### ZIP и manifest-файл

Имя manifest внутри ZIP — `generation-manifest.json` в корне. Это JSON ровно
`GenerationManifest` по E0.4, без второго wrapper/formата; UTF-8 без BOM, LF и один
завершающий LF. JSON member order не меняет manifestSha256, который считается по
entries алгоритмом выше. Java entries лежат по relativePath относительно source root.
В архиве ровно manifest + все generated Java; supportSources, C summaries и зависимости
в ZIP не входят. Один `.java` остаётся отдельным download; его M2 передаётся C/D через
plan/export adapter, а не извлекается из содержимого Java.
P8 (E4.B delivery slice) реализует эту форму для reviewed plan; actual A→C→D не подменяет.

Порядок секций: `@TableSections` — draft order; row annotation `order=10*(i+1)`.
Каждый row имеет явный `@Table(name=checkedSection.tableName())`; техническое поле
`Integer lineNumber`, колонка `line_number`, `lineNumberField="lineNumber"`.
Subject row fields получают `10 + 10*i`, parent/lineNumber не публикуются как subject UI.
Порядок entries — root, затем rows в draft order; эти правила фиксированы для E4 profiles.

Portable golden vector и отрицательные fixtures находятся в
`entity-design-core/src/test/resources/org/ipro/design/generation/manifest-v2-vectors.json`.
Индексы invalid задают missing/extra/duplicate/reordered/empty; отдельный digest — mismatch.
Они synthetic selftest, не generated-acceptance и не evidence C.

### C summary v2: точные поля

```text
kind: "g1-c-summary-v2"
mode: "generated-acceptance" | "selftest"
status: "success" | "failure"
manifest: полный GenerationManifest
scenario: { id, specSha256 }
sources: [{ relativePath, sha256, bytes }]  // ровно manifest.entries, тот же порядок
sourceSetSha256: manifest.manifestSha256
dependencies: [{ id, sha256 }]  // все использованные platform/target JAR
supportSources: [{ relativePath, sha256, bytes }]  // не generated entries
compile: { attempted: boolean, exitCode: integer }
runtime: { attempted: boolean, startup: "success" | "failure" | "not-attempted" }
tests: { reports, run, failures, errors, skipped,
         suites: [{ name, required, run, failures, errors, skipped }] }
```

Все поля обязательны, arrays могут быть пустыми только supportSources; dependencies
и suites непустые. Неизвестные поля допускаются для диагностических расширений;
duplicate JSON keys, неверные types, недостающие binding поля отвергаются.
Counts неотрицательные integers; suites уникальны, totals равны сумме suites.
Success требует generated-acceptance, compile attempted/exitCode=0, runtime attempted/
startup=success, reports>0, run>0 и failures/errors/skipped=0, а также run>0 и skipped=0
у каждого required suite. Для одного entry required suite — ровно
`com.example.generated.harness.GenerationScenarioTest`; для N>1 —
`com.example.generated.harness.MultiFileScenarioTest`. Имя сверяется независимо, а не
берётся из одного `required:true` внутри summary. Список suites задаёт scenario spec.
C сравнивает весь materialized source set с manifest (missing/extra/duplicates/hash/bytes);
D проверяет matching manifest/plan/profile и pin точных bytes summary через cSummarySha256.
Selftest summary никогда не разрешает production apply.

Для N>1 C принимает `SourceDirectory` + manifest и материализует **все** entries;
одиночный `SourceFile`-вход остаётся совместимым. `sources` и `sourceSetSha256`
подтверждают не только root, а весь комплект; `supportSources` не содержит ни одного
generated path. Multi-file suite проверяет metadata linkage и aggregate persistence
всех объявленных секций одним startup.

### Свежесть platform/target dependencies в C и D

`dependencies` описывает весь effective platform/target JAR classpath приёмки:
уникальные стабильные id и sha256, сортировка по id. Для Maven id —
`groupId:artifactId:type:classifier:version` (classifier может быть пустым).
Support sources задаются своим набором выше и не маскируют отсутствующий target JAR.
C хэширует effective JAR до и после build/runtime; изменение делает summary failure.

D дополнительно получает от consumer/integration adapter **current dependency locations**:
уникальные `{id, path}` к реально выбранным JAR. Paths берутся из текущей конфигурации
consumer/build, не из C summary, receipt или автоматически из недоверенного JSON.
Maven resolution выполняется до D; pure core не читает JAR и не разрешает зависимости.
Одних предоставленных caller хэшей без повторного чтения bytes недостаточно.

Под project lock D сам вычисляет hashes current JAR и требует точного равенства
набора id/hash с C. Missing/extra/duplicate/unreadable dependency —
`REJECTED/DEPENDENCIES_UNAVAILABLE` либо `REJECTED/DEPENDENCY_SET_MISMATCH`;
совпавший id с иными bytes — `REJECTED/DEPENDENCIES_CHANGED`. До этого CREATE/NOOP нет.
После публикации и перед durable receipt JAR хэшируются повторно; дрейф запускает
ограниченный rollback с итогом по таблице сбоев D. Receipt хранит verifiedDependencies
как `{id, sha256}`, без machine paths. NOOP тоже проверяет current dependencies против C.

Изменение JAR при прежних ReferenceTargetDescriptor/TypeFacts делает старый C
непригодным и требует нового C; если изменились facts — также нового checked/плана.
Новый C с тем же planId допустим при неизменных source/facts; новый receipt фиксирует
использованный summary при CREATE, а старый receipt не переписывается обычным NOOP.
NOOP требует тот же cSummarySha256 и verifiedDependencies своего receipt; после смены
dependencies возвращается REJECTED до отдельного явного повторного подтверждения,
автоматической перезаписи receipt в этом этапе нет.

Возраст файла/поле timestamp не доказывают свежесть. Проверки не обещают блокировку
сторонней замены JAR после D; integration build/runtime повторно сверяет тот же набор.
Наличие historical hash в C без current locations не разрешает extension apply.

### D state v2: точные поля

Общий binding receipt/journal:
`kind`, `stateVersion=2`, `applyId` (UUID string), `manifest` (полный объект),
`cSummarySha256`, `verifiedDependencies` (array `{id, sha256}`), `projectRoot`,
`sourceRoot`, `createdAt` (UTC ISO-8601), `createdDirs`, `entries` (в порядке manifest).
Kind = `g1-apply-receipt-v2` либо `g1-apply-journal-v2`.
Entry = `{relativePath, target, temp, expectedSha256, bytes, publishedIdentity,
publishedSha256}`; publishedIdentity = null или `{fileKey, realPath}`.
`fileKey` — opaque scheme-prefixed token: `fileKey:<platform value>` либо
`xattr:<applyId>:<sha256>`; null означает, что identity доказать нельзя.
До публикации published поля null, после — подтверждённые identity/hash; неизвестная
identity не даёт права удаления. Journal дополнительно содержит `phase` и `entryIndex`:
PREPARED → DIRECTORIES → TEMP_WRITTEN → PUBLISHED (для каждого entry) → VERIFIED → RECEIPT_WRITTEN.
entryIndex=-1 до цикла; в цикле индекс manifest entry. Обновление journal durable
перед опасным шагом; после crash любые незавершённые phase требуют RECOVERY_REQUIRED.
Receipt содержит `status=APPLIED`, все published identities/hashes и полный verified set.

### Пути state v2 и перенос проекта

- State root фиксирован: `<project>/.g1-apply-v2/`. D1 остаётся в прежнем
  `<project>/.entity-generation/apply/`; импорта ledger между ними нет.
- `sourceRoot` — нормализованный relative path внутри текущего project root.
  `entries.target`, nullable `temp` и `createdDirs` — нормализованные пути относительно
  **project root**; target обязан равняться sourceRoot + manifest relativePath.
  Ни один сохранённый путь не может выходить за текущий root.
- Абсолютный `projectRoot` — provenance запуска. `publishedIdentity.realPath` —
  абсолютный provenance публикации; `fileKey` — opaque filesystem identity текущего
  apply. Они не входят в portable manifest/plan binding и не являются путями для
  удаления или переноса после relocation.
- Для completed receipt после переноса/копирования всего проекта с ledger D заново
  разрешает paths от **текущего** root, выполняет containment/alias guards и сверяет
  sourceRoot, весь manifest, C/dependencies и bytes всех targets. Тогда NOOP допустим;
  совпадение старого realPath/fileKey не требуется, receipt не переписывается.
- FileKey нужен для доказательства принадлежности при rollback текущей попытки;
  completed receipt и совпадение content hash сами по себе не разрешают DELETE.
  Перенесённый pending journal всегда RECOVERY_REQUIRED: старые absolute provenance,
  identities и createdDirs не используются для автоматической очистки.
- Изменение sourceRoot относительно проекта не считается relocation того же
  применения; неверный binding — REJECTED. Missing target с собственным receipt —
  RECOVERY_REQUIRED, modified/foreign — CONFLICT с сохранением bytes.

Сначала под одним project lock проверяются все paths, C и ownership, затем CREATE
no-replace с повторной проверкой каждого target. Receipt durable только после полного
набора. NOOP требует собственного receipt и неизменённых targets всего того же manifest.
Foreign identical не является NOOP. Late conflict/write/ledger failure запускает rollback
только доказанно собственных неизменённых entries; чужую правку сохранить и вернуть
RECOVERY_REQUIRED. Pending journal блокирует integration launcher/build/runtime.
Это не атомарная filesystem transaction. Автоматический roll-forward не входит.
v1 и v2 имеют раздельные state directories; путь v2: `<project>/.g1-apply-v2/`.
Нельзя принимать v1 receipt/summary как v2 или использовать общий ledger двух профилей.

Для N>1 D2 принимает непустой набор CREATE entries в порядке manifest: case-insensitive
уникальные paths, каждый SHA-256/UTF-8 bytes сверен, C summary обязан содержать
`sources` ровно entries и `sourceSetSha256 = manifestSha256`, required suite —
`MultiFileScenarioTest`. До первой записи проверяются **все** targets и ownership;
foreign receipt на любом target даёт CONFLICT/ORPHAN, duplicate/case-collision путей
плана — REJECTED; missing/extra materialized source отдельно отклоняет C. Публикация
идёт последовательно с journal entryIndex;
APPLIED возможен только после проверки всего набора и durable receipt, NOOP — только
если собственный receipt покрывает весь набор и все targets неизменны. Rollback удаляет
лишь доказанно собственные неизменённые entries; D1 legacy остаётся однофайловым.

### Сбои D: ожидаемые статусы

| Условие | Результат |
| --- | --- |
| Preflight foreign/modified target, публикация ещё не началась | CONFLICT, без записи generated files |
| Несовместимые версия/plan/C/dependencies/roots | REJECTED до публикации |
| Late target, write failure или drift dependencies; receipt заведомо отсутствует, весь собственный output и journal безопасно убраны | ROLLED_BACK |
| Ошибка записи receipt; проверено, что durable receipt отсутствует, все собственные файлы/temps/journal безопасно убраны | ROLLED_BACK |
| При receipt failure нельзя доказать отсутствие/состояние опубликованного receipt | RECOVERY_REQUIRED, без удаления неоднозначных данных |
| Receipt durable, но завершение/очистка journal не подтверждены | RECOVERY_REQUIRED; APPLIED не сообщать, не откатывать заведомо committed output |
| Rollback/cleanup не завершён, identity неизвестна либо чужая правка появилась после CREATE | RECOVERY_REQUIRED, изменённый файл сохранён |
| Hard crash/pending journal, включая перенос проекта | RECOVERY_REQUIRED, automatic recovery отсутствует |
| Весь комплект, hashes/dependencies и durable receipt подтверждены; journal завершён и убран | APPLIED |
| Собственный completed receipt, тот же supported profile/manifest/C/dependencies и все targets неизменны | NOOP, включая перенос completed проекта |

Receipt failure не имеет единственного безусловного статуса: test fixture обязана
задавать фазу/наблюдаемый durable outcome и проверять соответствующую строку таблицы.
Ни частичный output, ни наличие receipt при pending journal не являются успешным apply.

## E0.5 — независимые сценарии и границы файлов

Ожидания ниже заданы до generated Java и не извлекаются из его annotations.
Окончательные Java ScenarioSpec готовятся в C-пакетах из этих входов.

| ID / этап | Input и независимое ожидание |
| --- | --- |
| MatrixLegacy / E1 | Нынешняя Matrix fixture без изменений, StandardCatalogEntity, code/name, catalog numbering; старые C/D guards |
| EmptyDocument / E1 | 0 fields/sections, StandardDocumentEntity, DOCUMENT, number/date, DOCUMENT_NUMBER/date; create/update/reopen, missing date rejected |
| ScalarDocument / E1 | STRING(100), INTEGER, LONG, DECIMAL(19,4), BOOLEAN, DATE, DATETIME; required и form/list/selection modes, exact values после reopen |
| ReferenceCatalog, ReferenceDocument / E2 | Existing Long-ID root target A/B; optional и required fields; A→B→null optional, required null отказ, no target mutation/cascade, no grants отказ |
| OwnedCatalog / E3 | Справочник договоров → справочник контрагентов; existing CATALOG owners A/B, обязательный counterparty; owner order=30/form/list/select, subject order=40+; filter A/B, persisted A→B отказ, удаление используемого A отказ, child delete сохраняет A |
| DocumentEnvelopeV2 / E3 | Document v1 и v2 с owner=null проходят document-java-v3/C2/D2; непустой owner в v2 остаётся invalid draft с OWNER_UNSUPPORTED_RECIPE, без генерации |
| CatalogSections0/1/2, DocumentSections0/1/2 / E4 | Root + N row sources; строка scalar/reference; metadata linkage, aggregate replace-all/delete/reopen, rollback invalid second section и optimistic conflict |
| OwnedCatalogSectionReference / E4 | counterparty owner, отдельный row parent, обычная row reference; нет смешения трёх ролей, no standalone row CRUD |
| MultiFileFailures / E4 | Whole-set NOOP/conflicts, changed/missing row, other planId/context, late file, second/third write failure, receipt failure с доказанным rollback и с неопределённым outcome, hard crash, aliases и two-process lock |
| DependencyDrift / E1–E4 | Тот же type/context/plan, изменённые bytes JAR; CREATE/NOOP отклонены до записи; изменение после публикации → ROLLED_BACK либо RECOVERY_REQUIRED по фазе |
| ProjectRelocation / E1.D, E4.D0 | Completed project + ledger перенесены/скопированы → NOOP с current path guards; pending journal после переноса → RECOVERY_REQUIRED, старые пути не используются |

Все targets/spec fixtures в C support source/JAR, вне generated manifest и business
sources основного приложения. Inputs должны сохранять UUID, draft/context, spec hash.

| Поток | Владеет файлами | Вход / selftest / передача |
| --- | --- | --- |
| Основной model/generator | RecipeCatalog, EntityDraft, DraftValidator, FieldApplicationMapping, CheckedStructure, PlanFingerprint, EntityJavaGenerator, builders, JSON codec/schema | E0 contract; core/json tests; отдаёт actual plan/content/profile |
| C | scripts/test-entity-generation-g1.ps1, scripts/entity-generation-g1/{consumer,scenarios,fixtures} | Manifest vectors + версии E0.3.1; E1.C — C2 для 1 entry, E4.C0 — N entries; отдаёт summary/log/XML/spec и dependency hashes |
| D | scripts/apply-entity-generation-g1.ps1, scripts/entity-generation-g1-apply/ | Тот же manifest/profile + C + current dependency locations; E1.D — D2 для 1 entry, E4.D0 — N entries; отдаёт receipt/journal/status/relocation/failure matrix и actual A→C→D |
| Runtime owner | platform-contracts/core/metadata owner declaration/resolver/write guards, runtime projection | Frozen owner/reference policy; metadata/canonical tests; передаёт API интегратору до draft/JSON правок |
| P8/forms | src/main/java/org/ip/views/admin/, platform-vaadin forms | Dispatcher/model API после соответствующего среза; session/component tests; browser evidence отдельно |
| Интегратор | dependency manifests/API baselines, install artifacts, итоговое evidence | Последовательная сборка согласованных artifacts; hashes, actual C/D и E5 |

Shared DTO/JSON/fingerprint меняет один основной поток. Изменение normative контракта
сначала отражается здесь и в vectors/tests, затем у C и D. Эта таблица не запускает
агентов или отдельные чаты.

Outputs: `outputs/entity-generation-g1-extension/<slice>/<run>/`; evidence:
`docs/architecture/evidence/entity-generation-g1-extension-<date>/`.
Каждый build имеет отдельные consumer/target/DB/port/output; общий SNAPSHOT cache
обновляет только интегратор. `SkipCoreBuild` допустим лишь на неизменяемом artifact,
не на параллельно меняющемся target/classes.

Команда E0: Maven `-o -f entity-design-core/pom.xml test`; regression JSON:
Maven `-o -f entity-design-json/pom.xml test` с согласованным core artifact.
C/D selftest команды следующих пакетов определяются их launcher API. Сейчас C поддерживает
`scripts/test-entity-generation-g1.ps1 -SelfTest`; legacy D selftest запускается
`scripts/apply-entity-generation-g1.ps1` без `-SelfTest`. Параметр D `-SelfTest` может
появиться в E1.D, но это не существующая команда baseline E0.
Пройденный synthetic selftest не заменяет actual builder → C → D → runtime.
