# Поддержанные runtime-профили сущностей — ET0–ET8

Дата: 2026-10-28. Основной контракт подтверждён [ET7 evidence](../plans/entity-types-appdev-first-et7-evidence.md),
полное interning-дополнение — [ET8 evidence](../plans/entity-types-appdev-first-et8-evidence.md).
Это runtime-профили; исполнимые новые рецепты генерации проверяются отдельно в ET9.

| Профиль | Прикладной intent и публикация | Запись и ограничения |
| --- | --- | --- |
| Standard catalog | @Entity + StandardCatalogEntity, EntityMetadata необязательна; code/name объявлены базой | Платформенные defaults, numbering role CATALOG_CODE; новые поля публикуются локально |
| Standard document | @Entity + StandardDocumentEntity; number/date объявлены базой | 0/1/2 owned-секции сохраняются агрегатом, absent/empty различаются; DOCUMENT_NUMBER относится только к подключённой нумерации |
| Custom catalog/document/plain | BaseEntity + EntityMetadata, собственные имена, temporal types, search/display/numbering | Существующий canonical path; ручной внешний номер не требует Numbered/NumberingRole |
| Owned row | Проверенная section declaration либо явная структурная ownership policy | ROW/aggregate boundary; generic standalone writes отсутствуют; OWNED_ROW не semantic kind |
| Internal store | Managed JPA без прикладного intent либо явная exposure policy | Нет автоматической публикации или canonical handle; непубликуемая ManyToOne на storage допустима |
| Information register | INFORMATION_REGISTER только из проверенного RegisterDescriptor backend-модуля | Independent/recorded различаются recorderOwned, не разными kind; операции принадлежат register engine |
| Interned (текущий маркер) | Отдельный identity-факт при CATALOG или PLAIN; предметный ключ остаётся в модели/сервисе | Explicit capabilities: generic UPDATE/DELETE запрещены; AttributeValue сохраняет CREATE и named rename, SklNomOpa — owner-managed findOrCreate без generic CREATE |
| Interned (полный ET8) | InternedEntity + одна InternedContract: properties/UNIQUE/NULL/нормализация/канал создания | Без capability-бинов: generic UPDATE/DELETE и по умолчанию CREATE запрещены; genericCreate=true явно сохраняет canonical CREATE. Предметные named операции и C5/RLS остаются отдельными |

## Общий gate и адресные отказы

MetadataConsistencyValidator объединяет базовые факты с MetadataDiagnosticSource,
применяет прежнюю MetadataAllowance-политику ровно один раз и передаёт общий immutable
список в MetadataConsistencyStartupCheck/Explorer. Межконтрактные отношения вычисляет
EntityContractConsistencyValidator в data-слое: metadata не зависит от data/fetch.
Все структурные register/section/numbering проверки остаются у существующих владельцев.

| Код | Severity / target | Причина |
| --- | --- | --- |
| CLASSIFICATION_SOURCE_MISSING | ERROR / entity | Нет проверенного источника для module candidacy или declared INFORMATION_REGISTER |
| CLASSIFICATION_SOURCE_FAILED | ERROR / entity | Ошибка вкладчика; исходная cause сохранена |
| CLASSIFICATION_SOURCE_DUPLICATE | ERROR / entity | Несколько вкладов; порядок Spring beans не выбирает победителя |
| ENTITY_CLASSIFICATION_CONFLICT | ERROR / entity | Базовый kind противоречит проверенному модульному kind |
| INTERNED_GENERIC_WRITES | ERROR / entity | Интернированный тип допускает generic UPDATE/DELETE |
| INTERNED_CONTRACT_INVALID | Startup error / entity и property в сообщении | Нет точного UNIQUE, неверное persistent property/getter, nullable key без альтернативы или пересечение ветвей |
| INTERNED_KEY_MISMATCH | Attempt error / entity и structural identity | Finder/creator вернул другую идентичность; попытка откатывается |
| NUMBERING_ROLE_KIND_CONFLICT | ERROR / numbered property | Роль подключённой нумерации несовместима с итоговым kind |
| REFERENCE_TARGET_NOT_METADATA | ERROR / published reference property | У цели нет применимых metadata; существующий код сохранён |
| LOOKUP_TARGET_NOT_ACCESSIBLE | ERROR / published reference property | Цель опубликованного lookup не STANDARD_ROOT либо не имеет LOOKUP capability |

Ссылка считается опубликованной для этой проверки, когда она участвует в форме или
гриде. Поле без UI-декларации и полностью скрытое поле не создают lookup-сценарий.
Публикации REST задаются отдельно; JPA-структура и FieldMetadata не добавляют REST-полей.

Новые ERROR-коды не входят в allow-list. MetadataAllowance по-прежнему разрешает только
UI_REQUIRED_SERVER_OPTIONAL; kind/security/identity ошибки не превращаются в legacy warnings.
Прямые compatibility constructors metadata-проверок работают без upper-layer sources;
полное backend wiring автоматически подключает cross-contract source после final catalog.

## Подтверждённые границы

Workshop, AccountingType и UnitOfMeasurement остаются custom: [сравнение инвариантов](../../examples/entity-types-consumer/README.md).
ET7 не меняет схему/данные, grants, C5/RLS, generic writes policy или named операции.
Полный ET8-профиль описан в [уточнении ADR-0008](decisions/ADR-0008-interned-entities.md#уточнение-et8--2026-10-28).
Его ограничения mapping и NULL-семантики явные; аннотация не доказывает применённую схему.
Приёмка отдельно проверяет локальную внешнюю схему read-only и гонки/rollback в PostgreSQL.
Накопление, новые исполнимые рецепты и генерация этим документом не объявляются готовыми.

ET9 передаёт эти runtime-факты в существующий design-core без зависимости ядра от
платформы/UI. Catalog-v2, capture compatibility, представимость FieldApplications и
HANDOFF_ONLY blockers описаны в [передаче G1](entity-design-g1-handoff.md).
Генерация всех рецептов по-прежнему выключена.
