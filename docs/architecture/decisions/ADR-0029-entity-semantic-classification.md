# ADR-0029: единый semantic kind и проверенный вклад регистра

Статус: принято для ET6, 2026-10-26.

Решение D8 пересматривает ADR-0013 §6 и F-ERP-3 только в части запрета
действующего INFORMATION_REGISTER. Для runtime-классификации достаточно
валидированного RegisterDescriptor из PU-scoped RegisterRegistry; два production
примера больше не являются условием включения enum-значения. Пилоты не объявляются
production-примерами. Обобщение бизнес-операций F-ERP-3 этим решением не завершено.

EntityDescriptorCatalog в core владеет immutable EntityClassification. Существующий
record EntityDescriptor и его constructors не меняются. Отдельный accessor хранит
kind, origin/symbol, interning и recorder ownership. Exposure и capabilities остаются
прежними контрактами; распознавание InternedEntity не изменяет generic writes.

EntityKindCandidate — meta-annotation модульной декларации, только candidacy hint.
Отсутствующий validated source, ошибочный источник, дубли и противоречивые kind
завершают построение каталога ошибкой. Порядок Spring beans не выбирает победителя.
Raw metadata kind сохраняется как базовое объявление, не подменяет effective kind.

Порядок: общий JPA snapshot → базовые metadata/sections → RecorderTypeRegistry
(проверяет DOCUMENT только по базовым metadata) → RegisterRegistry → register
contributor → итоговый core catalog → Explorer/query/presentation. Обратных
зависимостей core → register/query/Vaadin нет. Вкладчик поставляется backend-модулем.

Уточнение E3: EntitySummaryAssembler остаётся единственным сборщиком UI-карточки,
но потребляет core-факты. Генератор не получает зависимости на Vaadin.
Legacy constructors сохранены; standalone consumers без вкладчика отвергают
регистровую candidacy вместо marker-based fallback. Для них предоставляется
core standalone adapter с теми же правилами, без статического кеша и новых scans.


## ET7: межконтрактная согласованность (2026-10-27)

После final catalog data-слой вычисляет EntityContractConsistencyValidator и публикует
его результаты через нейтральный MetadataDiagnosticSource. MetadataConsistencyValidator
объединяет их с базовыми диагностическими фактами и прежними allowances; startup и
Explorer используют один список. Metadata не получает зависимость на data/fetch.

Проверяются interning против generic UPDATE/DELETE, применимость NumberingRole к
подключённой нумерации и kind, доступность опубликованного lookup target. Результаты
вкладчиков классификации имеют стабильные ERROR-коды, entity/property target и source;
исходная cause ошибки источника сохраняется. Новых legacy exemptions нет.
[Поддержанные профили и коды](../entity-types-runtime-profiles.md), [gate evidence](../../plans/entity-types-appdev-first-et7-evidence.md).
