# REST resource field access — проверка ADR-0016, 2026-10-06

## Изменение

По согласованному [ADR-0016](../decisions/ADR-0016-rest-resource-field-access.md)
REST требует права операции и чтения сущности для всех опубликованных полей.
RestReadService не проверяет attribute grants для вывода, фильтров и сортировок.
RestReferenceAuthorizationValidator сохраняет target entity read/RLS без attribute
grants. C5RestOperationRegistrar больше не регистрирует ATTR keys.
Проверка допустимости effective sort path в каталоге сохранена отдельно.

API/entity revocation, LIST/DETAIL isolation, capability gates, authentication,
row/class RLS и fixed reference profile сохраняются. Поля вне опубликованного
контракта не становятся доступны. Старые записи attribute grants автоматически
не удалялись. Общие C5 attribute API не менялись.

## Результаты

- Полный owner `platform-rest test/install`: **111 tests**, failures/errors/skipped 0.
- Итоговый application прогон без write/drift флагов: **57 tests**, failures/errors/skipped 0.
  - HTTP contract/pilot/security/session isolation: 18 тестов на реальном HTTP/H2.
  - Canonical pipeline и role matrix: 21 тест.
  - Каталог/декларации: 3 теста.
  - API baseline, starter composition, manifest и architecture: 15 тестов.
- HTTP фикстуры выдают только API/entity и необходимые row grants, без ATTR.
  Проверены default/maximum LIST и DETAIL, scalar/reference aliases, фильтры,
  default/явная сортировка и обязательный id. Неизвестные поля/сортировки дают 400.
- API/root/target entity denial и отзыв/снятие роли продолжают отказывать.
  Проверки утечки сессии, graph и отсутствия SQL при сериализации прошли.
- Reviewed OpenAPI/wire snapshots не изменились; drift gate прошёл.
- Точный service SQL budget запроса PrdSpec с reference/filter снизился с 20 до 11
  при первом чтении и с 16 до 7 при повторном; убраны девять attribute-grant lookups.
  Количество запросов не растёт между страницами с 1 и 50 строками.
- Bootstrap `-ValidateOnly`: exit 0 без разрешения source/toolchain drift.
- `git diff --check`: без ошибок.

Fingerprint platform-rest (60 files):
`51624956d9f7e6f378c58b0fb5e07bc2b1b9b79f4a8ffdb141ec36489e03657a`.
Manifest обновлён на проверенный owner; финальный gate выполнен без write-флага.

Проверенный JAR установлен в private verification repository и стандартный
`C:\Users\strim\.m2\repository`; SHA-256 совпадает:
`6c1e906541c460a5bcbd0c039ece5c43414f831d419143e0bd5a5688b16ceb47`.

Локальные журналы в игнорируемой `.local-maven-repository`:
`rest-entity-permission-owner.log`, `rest-entity-permission-app-final.log`,
`rest-entity-permission-validate.log`, `rest-entity-permission-default-install.log`.
Первый application прогон имел один отказ из-за старого точного SQL budget;
после его согласования весь повторный прогон зелёный.

Историческая приёмка 4.7 не переписывалась. Полный production/bootstrap прогон
в этом изменении не повторялся; общий C5/C и внешний deployment gate остаются
отдельными условиями. Права и данные рабочего экземпляра не изменялись.
