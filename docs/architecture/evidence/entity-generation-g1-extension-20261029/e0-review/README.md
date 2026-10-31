# Сверка E0 после уточнения контрактов

Дата: 2026-10-29. Проверены обновлённые план G1 и нормативные контракты E0
(E0.3.1–4, E0.4) относительно реализованных E0 и E1.A/B.

## Результат

Переопределять inherited/base/publication API, сигнатуру generate, GenerationManifest
или его golden hash не требуется. E1 profiles совпадают с новой матрицей:
catalog-java-v1/F1 и document-java-v1/F2, envelope v1; legacy вход сохранён.

Уточнения owner DTO/portable facts и owner binding относятся к E3; scope/profile bumps
по ссылкам/owner/sections — к E2–E4; ZIP — к E4.B. Их не следует добавлять в E0
как действующие возможности. D whitelist, C2/D2 для одного документа, current JAR
locations/freshness, relocation и failure statuses обязательны уже для E1.C/D.
Они не могут откладываться до multi-file gate E4. Старые C1/D1 остаются legacy-only.

Обнаружено одно расхождение уже реализованного E1: UNSUPPORTED_CONSTRUCT для документа
не содержал parameter `construct` из E0.3.4. В StandardRootJavaGenerator добавлены
`construct=REFERENCE` и `construct=SECTION` для extension fingerprint profile.
Legacy catalog получает ровно прежние parameter maps, что проверяет новый test.
Коды/Targets остаются прежними; UI routing не менялся. Generated Java, scope и plan
fingerprint не меняются, поэтому bump generatorVersion не требуется.

## Проверка

Offline reactor core + JSON: **93 tests, 0 failures/errors/skips**:
83 core (включая новый test document/legacy diagnostic parameters), 10 JSON.
Результаты сохранены в [Surefire XML](surefire/) и [build log](tests.log).
Команда Maven: `-o -f outputs/entity-generation-g1-extension/e0-review/20261029/pom.xml test`;
reactor содержит core/json modules, без install в общий Maven cache.

Дополнительно выполнены 33 source contract checks:
`scripts/test-entity-design-contract.ps1 -SkipBuild`.
Core fingerprint обновлён: 51 files,
`18b67c89f56f24a45da0e14dd4d25208e0dd3756d3a92705c672f646e26965cd`.
JSON source fingerprint не менялся. UI suites повторно не запускались:
изменены только generator diagnostic parameters, их точность проверена в core.
Прежние evidence 85/155 tests сохранены как исторические результаты своих runs.
