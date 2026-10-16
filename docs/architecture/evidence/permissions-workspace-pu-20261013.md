# П-U: единый ENTITY-контракт и новая выдача после очистки разработки

Дата: 13.10.2026. Основание — явное решение владельца объединить внутренние read/operation определения и удалить старые права нерабочей базы без переноса. Этот файл описывает П-U, а не готовность всего нового UI.

## Изменение

| Участок | Реализованное поведение |
| --- | --- |
| [C5PermissionKeys](C:/JavaProject/TestVaadin25/GitVaa25/platform-rls/src/main/java/org/ipro/rls/c5/C5PermissionKeys.java:16) | Один `ENTITY:<FQN>` для чтения и записи; короткий ключ не является алиасом. |
| [Entity registrar](C:/JavaProject/TestVaadin25/GitVaa25/platform-spring-boot-autoconfigure/src/main/java/org/ipro/data/config/C5EntityOperationPermissionRegistrar.java:90) | Один полный набор canonical read/write capabilities; отдельный read registrar удалён. |
| [Read projection](C:/JavaProject/TestVaadin25/GitVaa25/platform-rls/src/main/java/org/ipro/rls/c5/C5ReadPermissionRegistry.java:25) | READ-представление общего реестра, без второго независимого ENTITY-определения. Production read decision проверяет регистрацию до запроса грантов. |
| [Bindings](C:/JavaProject/TestVaadin25/GitVaa25/src/main/java/org/ip/application/access/PermissionBinding.java:11) и [patch flags](C:/JavaProject/TestVaadin25/GitVaa25/src/main/java/org/ip/application/access/PermissionPatch.java:14) | C5/RLS authority; один physical address может менять несколько независимых флагов. Dirty patch сохраняет остальные флаги. Полный save owner — П4. |
| [Administrative save](C:/JavaProject/TestVaadin25/GitVaa25/src/main/java/org/ip/service/AccessGrantAdminService.java:345) | Одна ENTITY-строка хранит read/update/delete; неподдержанные capability и value-scoped C5 input отвергаются. Effective view показывает все флаги, отчёт не дублирует read/write определения. |
| [Recorder authorization](C:/JavaProject/TestVaadin25/GitVaa25/src/main/java/org/ipro/register/RlsRecorderSetWriteAuthorizer.java:27) | Перед recorder insert/delete проверяет единый C5-контракт, затем RLS-политики и фиксирует canonical authorization для flush. |
| [Document preflight](C:/JavaProject/TestVaadin25/GitVaa25/src/main/java/org/ip/application/register/AccountingObjectSaveService.java:49) | Явно проверяет C5-права документа и регистра до записи заголовка; удалена зависимость от старого CHECK_ONLY-гейта регистра. |

Удалены четыре старых CHECK_ONLY ENTITY-объявления и их value-checks из моделей. JOURNAL/BRANCH накладной остаются общими ограничениями. Для AccountingObjectInstallation, AccountingObjectCorrection и NomenclatureAccountingObject сохранена финальная write boundary через явное включение canonical governance; разрешение операции не становится разрешением произвольного dirty-checking.

Первичная выдача, demo seeding и пилотная фикстура обновлены: общая ENTITY-строка, без второго короткого ключа, без случайного READ на ACTION и без перетирания соседних флагов при дополнении гранта. Временная подпись существующего admin selector — «права на объект». Полное удаление старого редактора остаётся П10, после подключения нового UI.

## Проверки

| Проверка | Результат |
| --- | --- |
| `scripts/run-permissions-pu.ps1 -Regression` | 136/136, без пропусков. Компиляция исходников platform-rls, autoconfigure, platform-register и всего приложения в отдельный output, затем C5/catalogue/DTO/admin/issuance/recorder/pilot проверки. |
| `platform-rls`: offline Maven `install` | 77/77; JPA exact/global matching, C5 решения, READ projection, роли, wildcard и flush boundary. Проверенный SNAPSHOT установлен в локальный Maven repository. |
| `platform-spring-boot-autoconfigure`: offline Maven `clean install` | 38/38; registrar, read-only governance и проверка состава модуля после удаления read registrar. SNAPSHOT установлен. |
| `platform-register`: offline Maven `install` | 3/3; после параллельного среза переноса чтения в модуль установлен его текущий SNAPSHOT, без изменения этого review-реестра в П-U. |
| Приложение: Maven `resources:resources compiler:compile` | BUILD SUCCESS; backend и ресурсы собраны с установленными SNAPSHOT. Frontend сборка не запускалась. |
| PostgreSQL reset | COMMIT: DELETE 29, INSERT 4; целостность сохранённых таблиц подтверждена. |

Проверены одна физическая ENTITY-строка и её стабильный id при смене флагов, независимость READ от UPDATE/DELETE, роль как источник, CREATE-only capability, отсутствие коротких ключей, отказ value-scoped/wildcard грантов, idempotent issuance, реальный canonical CRUD филиала и отказ прямой Hibernate-вставки при наличии всех прав. Дополнительные проверки документа/регистра закрыли разрыв авторизации flush после удаления CHECK_ONLY: штатная запись проходит, неподходящий субъект и произвольное изменение регистра отклоняются, rollback сохраняет агрегат.

Runtime-снимок: 32 уникальных C5-ключа, 39 registered dimensions, 36 визуальных строк и 75 колонок-флагов. Сохраняются 21 ENTITY, 7 ACTION, 4 API, 3 SETTINGS, 1 JPQL legacy owner и 2 общих scope sources. `SETTINGS:Documents` без SettingsGroup остаётся явной диагностикой. Снимки П0/П1 с 59 dimensions относятся к старому контракту и не переписаны задним числом.

Артефакты: [test summary](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-pu/test-summary.txt), [test log](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-pu/test-run-final.log), [rls module](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-pu/rls-module-tests.log), [autoconfigure module](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-pu/autoconfigure-module-tests.log), [register module](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-pu/register-module-build.log), [backend build](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-pu/application-build.log), [catalogue](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-pu/catalog-p1.tsv). Classpath/artifact/source manifests и javac arguments сохранены в той же папке. Source comments и окончания строк приведены к актуальному контракту; UTF-8 содержимое сохранено.

В рабочей папке одновременно менялся UniDR. Для компиляции устранены два локальных несоответствия типов/имён (`UniDrTableSources`, `ReportParamForm`) и добавлена управляемая BOM зависимость Jackson JSR310, которую использует новый UniDR-код. Остальные изменения этого параллельного направления не являются результатом П-U.

## Очистка локальной PostgreSQL

[SQL](C:/JavaProject/TestVaadin25/GitVaa25/scripts/permissions-pu-reset.sql) и [driver](C:/JavaProject/TestVaadin25/GitVaa25/scripts/run-permissions-pu-reset.ps1) ограничены localhost и явно ожидаемой `DBVaa25`. Без `-Apply` выполняется только read-only inspection схемы. Apply требует существующего enabled администратора с ADMIN и отсутствия входящих FK на AccessGrant. Операция атомарна, с ограничением времени/ожидания блокировок, без сброса identity и без подключения к старту.

| Новая ROLE/ADMIN строка | READ | UPDATE/EXECUTE | DELETE |
| --- | --- | --- | --- |
| ENTITY:org.ip.model.User | Да | Да | Да |
| ENTITY:org.ip.model.Role | Да | Да | Да |
| ACTION:USER:ASSIGN_ROLES | Нет | Да | Нет |
| ACTION:USER:CHANGE_PASSWORD | Нет | Да | Нет |

В одной транзакции сравнивались отпечатки полного содержимого app_user, role, user_role, journal и branch до/после очистки. Сохранены 4 пользователя, 3 роли, 4 назначения, 3 журнала и 2 филиала. Предметные таблицы не удалялись; DML очистки адресован только AccessGrant. Старые stale BRANCH, wildcard, direct и остальные гранты не переносились. [Протокол COMMIT](C:/JavaProject/TestVaadin25/GitVaa25/outputs/permissions-pu/database-reset.txt).

Остальные права назначаются заново. Широкая выдача всех entity definitions при старте не включалась. П2 должен реализовать точные типизированные источники по каждому флагу и batch matching; П3 — paging scopes, П4 — draft/save/concurrency/lifecycle, П5–П10 — новый UI и удаление старого редактора. П-U не вводит независимые CREATE/UPDATE и не заменяет RLS общей галкой объекта.
