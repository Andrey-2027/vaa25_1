# S5(а) по существу: имена операций регистра — из декларации, подписи собирает платформа

Дата: 2026-10-16. Область: [ADR-0024 §2.4](../decisions/ADR-0024-register-module-and-operation-titles.md)
(`displayName` в декларации, подписи операций собирает платформа, в приложении один адаптер вместо
четырёх бинов) плюс следствия по гейтам поверхности. Поставка `platform-register` в
`platform-backend-starter` — отдельный заход того же дня
([evidence](register-s5a-backend-starter-20261016.md)).

## Что изменено

| Файл | Изменение |
| --- | --- |
| `platform-register/.../RegisterMetadata.java` | Атрибут `displayName()` (пустая строка — «не объявлено»), с объяснением, почему имя требуется только регистру с именованными операциями |
| `platform-register/.../RegisterMetadataValidator.java` | Новый код отказа `OPERATION_DISPLAY_NAME_MISSING`: `keyChangeAllowed`/`keyWriteAllowed` при пустом имени — ошибка декларации, а не пустая подпись в экране |
| `platform-register/.../RegisterDescriptor.java` | `displayName()` — имя объявленного регистра (читается из аннотации, как и оба флага opt-in) |
| `platform-register/.../RegisterNamedOperations.java` | **Новый тип**: определения именованных операций вместе с подписями как данные. Форма одна на всех: «Изменить ключ записи: …», «Записать по ключу: …», «Удалить по ключу: …» |
| `platform-register/.../config/RegisterKeyWriteConfiguration.java` | Бин `RegisterNamedOperations` в том же срезе S2, что и сами операции |
| `src/main/java/org/ip/application/catalog/NamedOperationSource.java` | **Новый прикладной порт**: источник, объявляющий несколько названий, и статический `merge(owners, sources)`, которым пользуются оба потребителя каталога |
| `src/main/java/org/ip/application/register/RegisterOperationTitles.java` | **Один адаптер** вместо четырёх бинов: переводит платформенный список в `NamedOperationOwner`. Ни одной строки текста |
| `src/main/java/org/ip/application/register/RegisterKeyPermissionTitles.java` | Удалён (`git rm`): четыре бина подписей на два регистра |
| `src/main/java/org/ip/application/access/PermissionCatalogService.java`, `src/main/java/org/ip/views/admin/AdminView.java` | Потребители видят источники наравне с одиночными владельцами (слияние в одном месте — `NamedOperationSource.merge`) |
| `src/main/java/org/ip/model/NomenclaturePrice.java`, `NomenclatureAccountingObject.java` | `displayName` — «Цены номенклатуры» и «Объекты учёта номенклатуры» |
| `platform-register/.../META-INF/register-surface.txt`, `RegisterSurfaceRoleTest.java` | Reviewed-реестр ролей: 46 → **47** типов, APP_API 20 → 21, решение по новому типу записано срезом |
| `src/test/resources/platform-api-baseline/platform-register.api` | Surface-фиксация: атрибут аннотации, метод дескриптора, новый тип и его вложенная запись (15 добавленных строк, ни одного удаления) |
| `src/test/resources/platform-public-surface.txt` | Reviewed-реестр поверхности **приложения**: −2 API (`RegisterKeyChangePermissions`, `RegisterKeyWritePermissions` — приложение их больше не называет ни одним синтаксисом ссылки), +1 API (`RegisterNamedOperations` — единственный тип регистра, который приложение называет). Решение записано комментарием рядом со строками |
| `docs/architecture/d1-surface-measurements.md` | Перегенерирован тем же гейтом (`-Dplatform.measurements.write=true`): `platform-artifact-files` 578 → **579** (+`RegisterNamedOperations`), `api-types` 178 → **177** (−2 таблицы, +1 носитель), `named-platform-types` **312** без изменений, `app-source-files` 271 → **273** |
| `scripts/local-dependencies.json` | Fingerprint `platform-register` 69 → **71** файл и `state` с решением S5(а) |

Тесты, добавленные/изменённые вместе с кодом: модульные
`RegisterNamedOperationsTest` (5) и негативный сценарий в `RegisterMetadataValidatorTest`;
прикладные `RegisterOperationTitlesTest` (2), случай источника в `PermissionCatalogServiceTest`,
проверка подписей на настоящем контексте в `NamedOperationGrantAdminIT`. Opt-in фикстуры дерева
(`RegisterDescriptorTest`, `RegisterMetadataValidatorMappingTest`, `RegisterKeyChangesTest`,
`RegisterKeyWritesTest`, оба PostgreSQL-IT и фикстура модуля интерфейса) получили `displayName` —
они объявляют именованные операции, а значит обязаны назваться по новому правилу.

## Измерения

| # | Что измерено | Значение |
| --- | --- | --- |
| M1 | сборка модуля целиком | 175 тестов, 0 отказов, 52 пропуска (PostgreSQL-IT вне этой машины) |
| M2 | негативный юнит-сценарий | `RegisterMetadataValidatorTest` 18/18: `optInWithoutDisplayNameIsRejected` — `containsExactly(OPERATION_DISPLAY_NAME_MISSING)`; тот же регистр с именем пуст, регистр без opt-in пуст |
| M3 | подписи как данные | `RegisterNamedOperationsTest` 5/5: три операции одного регистра с общим идентификатором и формой, один флаг — одна операция, регистр без opt-in — пусто, список неизменяем, имя читается дескриптором |
| M4 | реестр ролей и замыкание API | `RegisterSurfaceRoleTest` 3/3 при 47 типах; `PlatformRegisterModuleTest` 5/5 |
| M5 | поверхность артефакта | `PlatformApiBaselineTest -Dplatform.api.baseline.artifact=platform-register` 4/4 после намеренной перезаписи baseline |
| M6 | один адаптер в приложении | `RegisterOperationTitlesTest` 2/2; бинов подписи на регистр: **4 → 0**, прикладных строк текста: **4 → 0** |
| M7 | каталог прав видит источники | `PermissionCatalogServiceTest` 11/11, включая «один источник называет несколько операций как несколько одиночных владельцев» и отсутствие `MISSING_ACTION_TITLE` для названных |
| M8 | настоящий контекст | `NamedOperationGrantAdminIT.registerOperationTitlesComeFromTheDeclarationThroughTheSingleAdapter` — зелёный: подписи четырёх регистровых операций приходят из деклараций, `NamedOperationSource` в контексте ровно **один** |
| M9 | модуль интерфейса | `platform-register-vaadin` 24/24 — фикстура диалога назвала регистр, остальное не тронуто |
| M10 | гейт поверхности приложения | `PlatformPublicSurfaceTest` 10 тестов, **6 зелёных**: обе стороны сверки с reviewed-реестром проходят, `legacyInternalBudgetOnlyShrinks` (57 ≤ 60) проходит, замеры D1 сходятся с кодом |
| M11 | повторная проверка итогового артефакта | после вычистки переносов строк в `AdminView` (см. ниже) `mvn -o compile` — успех; `PermissionCatalogServiceTest` 11/11, `RegisterOperationTitlesTest` 2/2, `PlatformRegisterModuleTest` 5/5 (18/18) |
| M12 | `app-source-files` 271 → 273 | Мой вклад: +2 прикладных файла (`NamedOperationSource`, `RegisterOperationTitles`), −1 (`RegisterKeyPermissionTitles`), итого **+1**. Ещё **+1** — чужой дрейф: записанное 271 не сходилось с кодом уже до моего захода. Перегенерация записи не одобряет чужое изменение, а лишь приводит документ в согласие с кодом; строка названа здесь именно поэтому |

Команды: `mvn -o -f platform-register/pom.xml test`, `mvn -o -f platform-register-vaadin/pom.xml test`,
`mvn -o test -Dtest=…` в корне проекта.

## Негативный прогон: «opt-in без имени отвергнут»

Проверялось не только юнит-правило, но и то, что отказ **виден на старте приложения**, а не только
в тесте валидатора.

1. `sha256` `NomenclaturePrice.java` до эксперимента:
   `fbcb83855c22266b05d9d44cc961265390261cd509cae50aa84fa83259e4d46e`.
2. `displayName = "Цены номенклатуры"` физически убран, при включённых `keyChangeAllowed` и
   `keyWriteAllowed`; прогон `NamedOperationGrantAdminIT` (поднимает контекст, то есть и
   `RegisterStartupValidator`): **7 ошибок из 7**, `NEGATIVE_EXIT=1`, причина в выводе одна и та же:
   `Некорректные декларации регистров сведений: org.ip.model.NomenclaturePrice
   [OPERATION_DISPLAY_NAME_MISSING] …`. То есть приложение не поднимается — «сырой ключ в
   админ-экране» недостижим по построению.
3. Имя возвращено; sha256 совпал с до-экспериментальным; повторный прогон вернулся к исходной
   картине (контекст поднимается, целевой тест зелёный, чужие красноты те же и не новые).

Протоколы: [положительный](../../plans/register-s5a-titles-positive-20261016.log),
[негативный с негодной попыткой и восстановлением](../../plans/register-s5a-titles-negative-20261016.log).

## Красное в `PlatformPublicSurfaceTest` (названо, не моё)

Четыре теста гейта красные, и все четыре — про ссылки файлов, которых этот заход не касался. Доказательство
принадлежности: обвиняемые файлы не изменены относительно HEAD (`git status` их не показывает),
а единственная моя правка в прикладном коде, попавшая в вывод гейта, — `AdminView`, где
`git diff -w` показывает ровно пять функциональных строк (импорт, параметр конструктора, два
комментария и строка слияния):

| Тест гейта | Что красно | Чей файл |
| --- | --- | --- |
| `legacyInternalReferencesAreFrozenPerFile` | `org/ip/unidr/query/UniDrQuerySourceService.java` начал называть `org.ipro.reportstudio.data.EntityRef` | unidr-файл, вне моего захода |
| `applicationCodeDoesNotUsePlatformWildcardImports` | `import org.ipro.lifecycle.*;` в `PermissionSubjectLifecycle`, `import org.ipro.rls.*;` в `PermissionPatchService` | P-серия «Пользователи и права» |
| `everyPlatformTypeTheApplicationNamesHasAReviewedRole` | 39 типов вне реестра (`org.ipro.rest.catalog.*`, `org.ipro.rls.*`, `org.ipro.filtergrid.*`, reportstudio, ureport); ни одного регистрового | названы HEAD-чистыми файлами (`PermissionAuditListener`, `DataInitializer`, `PermissionWorkspace`, `UniDr*` и др.) |
| `applicationCodeDoesNotUseFullyQualifiedPlatformReferences` | FQ-ссылки в 10 файлах (`org.ipro.rls.c5.C5Operation.*`, `org.ipro.form.Dirtyable`, `org.ipro.identity.IdentifiableEntity` и др.) | P-серия; в `AdminView` — те же две ссылки, что стоят в HEAD (`git show HEAD:` их содержит) |

Ни одна из четырёх краснот не зависит от S5(а): убрать их можно только правкой чужих ссылок, а это
решение владельца той темы (тип либо получает роль в реестре, либо перестаёт быть виден приложению).
Что S5(а) **обязана** была сделать и сделала — держать реестр согласованным в обе стороны со своей
правкой; это и проверяют зелёные части гейта.

Протокол: [register-s5a-surface-gate-20261016.log](../../plans/register-s5a-surface-gate-20261016.log)
— `mvn -o test -Dtest=PlatformPublicSurfaceTest`, 10 тестов, 4 отказа, 6 зелёных, тот же состав
краснот воспроизведён дважды.

## Вычистка: `AdminView` без переноса строк

Первая версия моей правки в `AdminView.java` перевела 35 строк файла из LF в CRLF (файл в дереве
смешанный: 566 CRLF + 35 LF), из-за чего `git diff` показывал 40/36 строк вместо пяти. Файл собран
заново из HEAD с применением только пяти функциональных изменений: `git diff` — **5 добавленных,
1 удалённая**, `git diff -w` — те же числа, содержимое посимвольно совпадает с проверенным ранее
(сравнение с снятой копией без учёта `\r`), сборка успешна (M11).

## Принятое следствие

Формулировки записи по ключу стали платформенными: было «Записать цену на дату: Цены номенклатуры» /
«Удалить цену на дату: Цены номенклатуры», стало «Записать по ключу: Цены номенклатуры» /
«Удалить по ключу: Цены номенклатуры». Смена ключа сохранила прежний текст. Это ровно то следствие,
которое ADR-0024 §2.4 принял сознательно; тесты этих строк не фиксировали, но админ-экран их
показывает, и изменение видно администратору.

## Манифест: что перезаписано, а что названо

- **Перезаписано** `platform-register`: 69 → **71** файл, `7add599c…` → `8520eea3…`. Сходится с
  содержимым: +1 production-тип (`RegisterNamedOperations`) и +1 его тест.
- **Не перезаписаны и названы чужими** (проверено измерением, а не догадкой):
  - `platform-rls` — записано 61 файл, фактически 62 (дрейф из прошлого захода, не мой);
  - `platform-register-vaadin` — записано 14 файлов (`9c5c121e…`), фактически 14 (`79b3dbaf…`).
    Дрейф этого модуля **старше моего захода**: с временно снятой моей правкой фикстуры
    (`git checkout` файла теста) фактический v2 был `4d0c31b7…` — то есть не совпадал с записанным
    ещё до меня. Моя правка (`displayName` в фикстуре диалога, 3 строки) лежит внутри того же
    fingerprint, поэтому записать её отдельно нельзя: запись либо остаётся чужой, либо одобряет
    чужое изменение. Оставлено как есть и названо — тем же правилом, что и в заходе по поставке.

## Ограничения и названная краснота

- **`NamedOperationGrantAdminIT` не зелёный целиком**: 4 из 7. Причина не в регистре и не в
  подписях: `AccessGrantAdminService.saveSingleGrant` → `PermissionSubjectLock.acquire` делает
  `select … from app_user where username = :value for update` с `FlushModeType.COMMIT` и
  `Propagation.MANDATORY` и требует ровно одну строку, а фикстура этого теста создаёт **только имя**
  пользователя (`rename-grant-<uuid>`) и строки в `app_user` не заводит. `PermissionSubjectLock`
  пришёл в дерево чужим заходом (файл несёт HEAD, коммит `189760c`), и его контракт требует
  обновления этого IT владельцем той правки; я его не правил, чтобы не смешивать чужую тему со
  своей.
- **`NavigationAdminWiringTest` не зелёный целиком**: 2 из 3 — падает на числе вкладок (ожидается 4,
  фактически 5). Пятая вкладка — «Пользователи и права — новый экран», чужой заход P-серии; моя
  правка в `AdminView` добавляет только параметр конструктора и слияние названий, к составу вкладок
  отношения не имеет.
- **`PlatformApiBaselineTest` не зелёный целиком**: единственная краснота — `platform-rls`
  (`RlsHibernateWriteGuardInstaller` против `RlsGrantMatching`), чужой дрейф, названный и в заходе
  по поставке. Поверхность `platform-register` при этом сравнивается и совпадает.
- **Не запускались**: полный `mvn verify`, random-order прогон (§6.5), PostgreSQL-гейты среза
  (`register-r11-postgresql.ps1`, `register-r8-postgresql.ps1` и ветка `Concurrency`) — им нужна
  база; `platform-rls.api`. Гейт `PlatformPublicSurfaceTest` прогнан (M10) — четыре его красноты
  названы отдельным разделом выше.
- **Отрисовка подписи в экране** проверена косвенно: `AdminView` строит карту названий из того же
  слияния, что проверяет M8, но сам путь подписи (`accessDimensionLabel`) тестом не прогонялся.
- Кейс внешнего потребителя (§6.3) по-прежнему не написан, и приёмка §6 целиком не закрыта.
