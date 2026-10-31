# Передача типов сущностей в G1 — контракт ET9

Дата: 2026-10-28. ET9 завершает план типов сущностей передачей контрактов, не реализацией
движка генерации. Источник матрицы — `RecipeCatalog.recipes()` в существующем
entity-design-core; gate публикует outputs/et9/recipe-matrix.json.

## Версии и совместимость

- `standard-catalog-v1@1` остаётся capture P8: baseApiRoleAtCapture=INTERNAL, прежние
  defaults, DTO, UUID, порядок, семантическая проверка и JSON. Ресурс, схема и три
  fixture не переписаны. Старый DraftOperations.create() создаёт этот рецепт.
- `standard-catalog-v2@2` выбирается явно: create(RecipeCatalog.STANDARD_CATALOG_V2)
  или импорт точной пары id/version. Capture базы — APP_API; текущий prerequisite
  проверяется по runtime snapshot отдельно от capture. V1 автоматически не обновляется.
- schemaVersion остаётся 1: формат и смысл полей envelope не изменены. Codec явно
  регистрирует новую пару через отдельное представление того же envelope; оригинальный
  schema resource сохранён. V1@2, v2@1 и неизвестные версии отклоняются. Runtime snapshot
  не входит в экспорт EntityDraft.
- Старый девятиаргументный constructor ProjectDesignContext сохранён. Старый бинарный
  клиент связывается с новым JAR без перекомпиляции. Отсутствующие runtime-факты — UNKNOWN.

## Матрица рецептов

CAPTURED — совместимый старый контракт. DRAFT_READY — новая версия с текущими prerequisites.
HANDOFF_ONLY — описанная граница будущего рецепта: его DTO/operation contract ещё не
реализован; такие пары отвергаются DraftValidator и JSON importer. Это не поддержка
создания и не исполняемые рецепты. **У всех generationEnabled=false.**

| Рецепт | Состояние и база | Capabilities / решения | Файлы, схема и контрольная fixture |
| --- | --- | --- | --- |
| standard-catalog-v1@1 | CAPTURED; StandardCatalogEntity, capture INTERNAL | Прежний catalog intent, LIST/DETAIL/LOOKUP и CREATE/UPDATE/DELETE; отдельные права | Entity + row на секцию; code 50/name 200 NOT NULL, без UNIQUE(code); EntityTypesAcceptanceTest |
| standard-catalog-v2@2 | DRAFT_READY; StandardCatalogEntity, capture APP_API; STANDARD_CATALOG_APP_API и ET0_ET7_RUNTIME | Те же defaults; предметные поля, публикации и C5/RLS | Entity + rows + acceptance; CATALOG_CODE; parent NOT NULL, MUTABLE_REPLACE_ALL/INHERIT_ROOT; FieldApplicationRuntimeParityTest, EntityDesignRuntimeProjectionIT, внешний consumer |
| standard-document-v1@1 | DRAFT_READY; StandardDocumentEntity; STANDARD_DOCUMENT_APP_API и ET0_ET7_RUNTIME | E1.A/B: document draft/JSON/P8 и scalar root Java; number/date, DOCUMENT_NUMBER. Секции и C/D приёмка ещё открыты | document-java-v1, plan-fingerprint-v2; Order/TwoSectionDocument/CustomDocument — runtime baseline, не приёмка генератора |
| custom-plain-v1@1 | HANDOFF_ONLY; BaseEntity, APP_SPI | Нужен plain draft contract: свои имена/presentation/roles; без навязанных code/name | Entity + rows по выбранному ownership + fixture; явные columns/constraints; PlainProbe в FieldApplicationRuntimeParityTest |
| interned-catalog-v1@1 | HANDOFF_ONLY; BaseEntity + InternedContract; обязателен ET8 | Нужен identity/operations draft: ветви ключа, NULL, нормализация, owner-managed или подтверждённый CREATE exception, named операции | Entity + domain findOrCreate/normalization + fixture; точный UNIQUE/NOT NULL, без generic UPDATE/DELETE; InternedPostgresIT, оба сервиса |
| independent-register-v1@1 | HANDOFF_ONLY; BaseEntity; ET6–ET7 и проверенные операции | Нужны dimensions/resources/periodicity, key/write semantics и draft contract | Register entity + operation adapter + fixture; descriptor/key constraints; RegisterStartupValidatorTest / RegisterWriteConcurrencyPostgresIT |
| recorded-register-v1@1 | HANDOFF_ONLY; BaseEntity implements RecordedRegisterEntry | Дополнительно recorder codes, managed DOCUMENT и owner boundary; новый operation profile не подтверждён | Те же файлы + recorder mapping; recorder columns/allowed types; register owner suites |

Reads/writes у HANDOFF_ONLY описывают консервативный будущий профиль, не действующие
capabilities и не executable defaults. Два текущих INFORMATION_REGISTER сохраняют в
snapshot CREATE/UPDATE/DELETE; матрица не делает их read-only. REGISTER_ENGINE_OPERATIONS
остаётся UNAVAILABLE для нового рецепта до оформления его operation contract.
Другой профиль записи — предметное решение, не вывод из kind.

## Переносимые факты и host

entity-design-core зависит только от JDK. Codec — отдельный entity-design-json.
Опциональный **entity-design-runtime** проецирует проверенные EntityDescriptorCatalog,
MetadataResolver, SectionMetadataRegistry, NumberingMetadataRegistry и startup diagnostics.
У него нет Vaadin, org.ip-классов, business lookups, вызовов по draft FQN и файлового
генератора. Он подключён явно к host; platform-build/BOM/starters его не получают.

AutoConfiguration требует всех проверенных владельцев. Публичный snapshot() возвращает
RuntimeDesignSnapshot; constructor с владельцами package-private. Без adapter legacy
host получает UNKNOWN snapshot; prerequisites v2 не становятся выполненными.

Immutable records содержат type ID=FQN, property ID=FQN#property и section ID=owner
FQN#section:key; effective kind/origins/sources, exposure, reads/writes, interning,
recorder ownership, структурные поля, server-required/origin, form/list/selection/table,
точные list/select paths, numbering definition, sections, interning keys/каналы/исключения
и startup diagnostics с кодом/источником/целью. Class и business instances не передаются.

Section owner берётся из validated registry. Для owner-managed legacy строки вне этого
registry пустой sectionOwnerTypeId означает отсутствие подтверждённого section-факта;
владелец не угадывается по Java-полю или диагностической строке. Snapshot ID — hash
стабильной проекции. CheckedStructure сравнивает весь context/snapshot, а не один hash.
Новые факты инвалидируют проверку, не меняя draft/UUID/raw input/dirty state.

## FieldApplications → runtime

CheckedStructure.applicationProjection() возвращает данные деклараций, не исходники
и не GenerationPlan. DraftValidator остаётся единственным семантическим валидатором;
UI передаёт факты и показывает его диагностики.

| Применение | Отображение |
| --- | --- |
| root.form | FieldMetadata.hidden = !form |
| root.list | Явный EntityMetadata.listColumns в порядке полей |
| root.selection | Отдельный EntityMetadata.selectColumns, независимо от list |
| row.form | FieldMetadata.hidden = !form |
| row.table | FieldMetadata.grid.visible = table; standalone list/selection не создаются |
| serverRequired / uiRequired | Требование записи отдельно от RequiredMode; явный mode сохраняется и у скрытого поля |

Root list/selection-only при AUTO UI mode может обходиться без FieldMetadata. Явный
mode сохраняет hidden declaration; REST/query права не возникают. В первом catalog
code/name остаются inherited и в обоих projections: пустой selectColumns, который
runtime трактует как наследование list, не подменяет решение пользователя. Изменение
inherited-публикации требует другого рецепта, не дублирующего поля с тем же именем.

Root.table, row.list/selection и server-required без form получают INVALID_APPLICATION
с draft/section/field UUID и property key. Перебраны 64 сочетания scope/required/flags;
поддержанные mappings сверены с реальными MetadataResolver/SectionMetadataRegistry.
V1 сохраняет warnings; v2 добавляет runtime-код UI_REQUIRED_SERVER_OPTIONAL.
Metadata REQUIRED у нумеруемого поля не объявляется required виджета: UI numbering
owner имеет собственное поведение.

## Условия начала G1

Проверенная структура и APP_API-база не включают генерацию. Нужны реальные:

1. GenerationPlan с CREATE/MODIFY, исходными хэшами и versioned recipe reference.
2. Просматриваемый diff до применения.
3. Файловый adapter с контролем путей, согласованным применением и откатом.
4. Сохранность ручных правок и проверенная повторяемость.
5. Отдельное явное решение/план миграции БД.
6. Compile/startup/persistence/поведенческая приёмка **сгенерированного** результата каждого рецепта.

Host не обходит это, прислав AVAILABLE для всех ID: до реализации G1 catalog всегда
возвращает generationEnabled=false и G1 blockers. G2 импортирует схему в тот же draft
без угадывания kind/identity/RLS; IDE — отдельный клиент ядра.

Результаты: [ET9 evidence](../plans/entity-types-appdev-first-et9-evidence.md).

Предлагаемый следующий пакет: [актуализация P8, Документ, Регистр сведений и первые срезы G1](../plans/entity-design-p8-to-g1-plan.md). Это план продолжения; состояния рецептов и результаты ET9 выше им не изменяются.

Сверка регистровой основы 2026-10-28: [аудит Р-/Г-/S-серий](information-register-pre-g1-audit.md). Runtime всех четырёх режимов реализован; REG-A1/A2/A3 относятся к незакрытой текущей приёмке/поставке. REG-D1/D2 — новая проекция и draft/генерация. `REGISTER_ENGINE_OPERATIONS=UNAVAILABLE` обозначает готовность нового рецепта, а не отсутствие существующих runtime-операций.

По последующему решению пользователя регистровые работы отложены: ближайший пакет — [минимальный G1 справочника](../plans/entity-generation-g1-minimum-plan.md), начиная с GenerationPlan и Java preview. Это последовательная реализация G1, не отмена его итоговых compile/apply/migration gates.
