# F-REST-READ-3: план завершения и окончательной приёмки

- Статус: З0–З2 выполнены; З3–З5 и полная приёмка этапа открыты.
- Дата: 2026-10-03; З2 фактически выполнен 2026-10-04.
- Основной контракт: [`rest-read-api-stage3-security-read-plan.md`](rest-read-api-stage3-security-read-plan.md).
- Родительский план: [`rest-read-api-plan.md`](rest-read-api-plan.md).
- Архитектура загрузки: [ADR-0007, §4.1](decisions/ADR-0007-canonical-data-access-path.md#rest-fixed-fetch-profile).
- Сборка: [`build/local-dependency-workspace.md`](build/local-dependency-workspace.md).
- Evidence: [`rest-read-api-stage3-completion-evidence.md`](rest-read-api-stage3-completion-evidence.md).

## 1. Цель и границы завершения

Закрыть серверный `list/detail` для `specifications:v1` (`PrdSpec`) и
`nomenclature:v1` (`Nomenclature`): права, ограниченное чтение, скалярный результат,
проверенная стоимость и воспроизводимая поставка текущих исходников.

Сохраняются принятые решения: прикладная декларация и immutable каталог;
общий C5 в `platform-rls`; чтение через canonical boundary; независимый фиксированный
REST-граф; проекция внутри транзакции. Новая архитектура чтения не требуется.

В остаток включается минимальное исправление действующего администрирования C5 read
grants: показанное разрешение должно совпадать с серверным решением, а сохранённый
грант должен иметь поддерживаемую сервером семантику. Полное администрирование C5,
write/action enforcement и UI attribute enforcement имеют отдельную приёмку.

HTTP-адаптер относится к этапу 4. Синхронизация, запись, owned-секции, генератор и
Studio не входят в эти работы. Серверная СУБД не выбирается здесь: H2 допускается для
реальных JPA/Hibernate-проверок; повторение на выбранной серверной СУБД относится
к развёртыванию. Этап не закрывает автоматически E1.2b, полный C5 или gate C.

## 2. Исходное состояние и точность evidence

В основном плане, §15, записаны результаты предыдущего прогона 2026-10-03:

| Проверка | Зафиксированный результат | Что ещё нужно |
| --- | --- | --- |
| Owner modules | `platform-rls` 12, `platform-core` 147, `platform-rest` 66, autoconfigure 32: всего 257 успешных tests | Повторить для итоговых исходников и установленных артефактов |
| Общий app canonical-read/UI/RLS набор | 65/65 | Проверить после оставшихся изменений, дополнить реальными ролевыми сценариями |
| API baseline / public surface | 13/13 | Проверить итоговую межмодульную поверхность |
| `PrdSpec` LIST, 50 совпавших строк | Первый вызов: 20 statements; повторные вызовы с size 1 и 50: по 16 | Разделить data/security SQL, объяснить разницу, измерить остальные сценарии |
| Bootstrap validation | Diagnostic validation с `-AllowSourceDrift` проходит | Strict validation и полный стандартный bootstrap остаются открытыми |

Это исторический baseline; новый прогон З0 записан в
[completion evidence](rest-read-api-stage3-completion-evidence.md): текущие owner JAR
установлены и сверены по SHA-256, app pilot прошёл 7/7, SQL классифицирован, UI LIST
и REST profiles сопоставлены. В З0 owner artifacts установлены без нового verify;
в З1 `platform-rest` install с verify прошёл 91/91, root REST/API/surface набор —
21/21. Остальные итоговые owner gates остаются в З5.
Gate 3A имеет evidence; Gate 3B и общие критерии полного завершения ещё открыты.

`DefaultC5PermissionEvaluator` сейчас обращается к хранилищу при проверке каждого
permission; кеша решений C5 в нём нет. Значения 20/16 нельзя объяснять «холодным» и
«прогретым» кешем C5. З0 установил причину: по 14 C5 и 2 data queries во всех
трёх вызовах; первые дополнительные 4 queries — RLS grants и user/role resolution,
которые затем переиспользуются через отдельный session-scoped RLS ids cache.
Fresh persistence context и выключенный Hibernate L2 cache для проверки графа —
отдельные условия, их нельзя смешивать с кешированием прав.

## 3. Пакеты работ и порядок

| ID | Работа | Владелец изменений | Условие завершения |
| --- | --- | --- | --- |
| З0 | Зафиксировать текущие inputs и контрольные измерения | Документация, тестовые fixtures, build manifest | Известно, какие исходники и JAR проверяются; SQL классифицирован |
| З1 | Ввести конечное окно страниц | `platform-rest` | Действуют численные ограничения, границы проверены до business-data SQL |
| З2 | Согласовать C5 evaluator и администрирование read grants | `platform-rls`, registrars, app grant admin | Сервер и admin одинаково понимают exact/global/read grants |
| З3 | Проверить роли и переход существующих callers | App security fixtures и документация эксплуатации | Реальные назначения/отзыв ролей работают; UI/service policy проверена |
| З4 | Завершить SQL/graph приёмку обоих ресурсов | REST/core owner tests и app JPA pilots | Записаны и соблюдаются численные бюджеты всех обязательных сценариев |
| З5 | Закрыть сборку и финальную регрессию | Manifest/bootstrap, owner modules, app | Strict gates проходят; итоговый evidence связывает код, артефакты и результаты |

Порядок: З0 → З1 → З2 → З3 → З4 → З5. Диагностику bootstrap из З5 выполнить
уже в З0, чтобы внешняя сборочная проблема не обнаружилась после всех исправлений.
Исходный SQL trace из З0 получить до оптимизации C5; окончательные бюджеты З4
проверять после З2/З3. Ранние частичные прогоны использовать повторно только если
их исходники, артефакты и зависимости не изменились.

В конце З0 записать решения по конфигурации окна, общей C5 read-границе для admin
и предварительным бюджетам. Изменение публичной сигнатуры проходит API-review и
surface gate; прикладное приложение не получает обязательную ручную сборку infrastructure.

## 4. З0 — inputs и контрольные измерения

1. Записать Git revision, состав незакоммиченных изменений в затронутых модулях,
   версии JDK/Maven/Hibernate/H2, выбранный Maven local repository и команды.
2. Проверить, что app использует JAR текущих `platform-rls`, `platform-core`,
   autoconfigure и `platform-rest`; перед app IT установить их в dependency order.
3. Снять SQL trace первого и повторного `PrdSpec` LIST на одинаковой fixture:
   content, count, grant queries, role resolution и прочие statements отдельно.
   SQL от подготовки fixture исключить из счётчика вызова.
   В pilot test переименовать misleading `coldPermissionCache` variables,
   assertion messages и комментарии о warm C5: они не описывают текущий evaluator.
4. Выполнить strict `-ValidateOnly`, записать конкретные несовпавшие fingerprints;
   исследовать причину прежнего отказа `clean` в соседнем `crudui`.
5. Сверить baseline UI LIST и независимые LIST/DETAIL REST paths. Старые UI DETAIL
   или LOOKUP измерения не подменяют требуемый UI LIST comparator.

Результат: воспроизводимый baseline, классификация 20/16 и список подтверждённых
сборочных препятствий. Наличие старого установленного SNAPSHOT не считается
подтверждением текущего исходного кода.

## 5. З1 — конечное окно страниц

### 5.1. Контракт

Реализовано платформенное окно `W = 10_000` строк по умолчанию, page index
с нуля, default size 50, maximum size 200 с сохранением меньших лимитов ресурса.
Server property — `platform.rest.read.max-window`, positive int. Enabled startup
отклоняет невалидное значение и окно меньше default page size опубликованного
ресурса; disabled read не связывает эту настройку.
Границы доказаны на H2 с 10 001 видимой строкой каждого ресурса. Число 10 000 —
рабочий default; полная приёмка стоимости относится к З4, SLA не устанавливается.
До итоговой приёмки выбрать и записать
окончательный W. Увеличение после неуспешного теста требует объяснения стоимости.

Правило: `offset = (long) page * size`, `windowEnd = offset + size`;
допустимы только `page >= 0`, корректный size и `windowEnd <= W`.
`size == 0` сохраняет текущую семантику выбора default перед проверкой;
отрицательные значения недопустимы. Окно не означает limit общего видимого total.

Один валидируемый платформенный default подключается через auto-configuration.
Если понадобится индивидуальное меньшее окно, ресурс может только сузить W;
такое расширение builder вводится лишь при реальной потребности и отдельном
API-review. Для двух нынешних пилотов достаточно общего ограничения.

З1 выполнен: owner проверки 91/91, root REST/API/surface — 21/21.
Прикладной pilot подтвердил полную последнюю разрешённую страницу, неизменный
total 10 001 и отсутствие business SQL для следующей запрещённой страницы обоих
ресурсов. Результаты и SQL trace записаны в [evidence, §8](rest-read-api-stage3-completion-evidence.md#8-з1--конечное-окно-страниц-2026-10-03).

### 5.2. Изменения и проверки

- Ввести численный лимит и fail-fast проверку его конфигурации; сохранить защиту
  арифметики и ограничение page size.
- Проверять окно до canonical business read. Авторизация может выполнить SQL
  по grants; требование «до SQL» здесь означает отсутствие business-data SQL.
- Для W=10 000 проверить: size 50/page 199 разрешён, page 200 отклонён;
  size 200/page 49 разрешён, page 50 отклонён.
- Покрыть page < 0, size < 0, default size, size > max, большие page/size,
  `windowEnd == W`, `windowEnd > W` и недопустимую конфигурацию.
- Подтвердить deterministic sort с id tie-breaker и неизменность видимого total
  при смене разрешённых страниц. Unpaged чтение не появляется.

Основные файлы: `RestReadService`, REST configuration/auto-configuration и их
owner tests; прикладной JPA pilot подтверждает край разрешённого окна.
Внутренний исход отказа — существующий `INVALID_REQUEST`; HTTP mapping относится
к этапу 4. DoD: конечный W записан, все границы доказаны тестами.

## 6. З2 — единая семантика C5 и grant admin

### 6.1. Подтверждённое расхождение

`DefaultC5PermissionEvaluator` принимает только точный permission key,
`dimensionValueId == null` и `canRead`; пользовательский и ролевые allow grants
объединяются. `AccessGrantAdminService.collectEffective` обращается к общему
`AccessService`, который учитывает RLS wildcard `*`. Поэтому admin может показывать
разрешение C5, которое реальный evaluator не предоставляет.

Дополнительно проверить `currentSingleGrant/saveSingleGrant`: выбор первого
value-scoped гранта и нормализация `read || update || delete` не должны
превращаться в глобальное C5 read-разрешение или менять чужие value grants.

### 6.2. Решение и ownership

1. Зафиксировать в C5 owner общий способ описать и распознать зарегистрированный
   C5 read permission и получить его effective decision для USER/ROLE.
   Registrars передают определения своих ключей; правила grants принадлежат
   `platform-rls`. Конкретную узкую API/SPI сигнатуру выбрать в API-review З0.
2. Server evaluator и admin используют одну реализацию exact/global/read semantics.
   App admin не копирует permission evaluation и не выводит его из префикса
   `ENTITY:`: legacy `ENTITY:ReceivingDocument` и C5 FQN key различаются.
3. Сохранить RLS wildcard semantics для обычных RLS dimensions. Для C5 не принимать
   wildcard и value-scoped rows за global grant; отсутствие подходящего allow — deny.
   False flags не вводят новую семантику explicit deny поверх ролевого allow.
4. Для C5 read в существующем admin редактировать только поддерживаемый `canRead`
   без dimension value. Недопустимые write/delete/value параметры отвергать на
   серверной границе сохранения, а в UI не предлагать их как работающие C5 права.
5. Не менять исторические value-scoped rows автоматически. Решение об удалении или
   миграции обнаруженных некорректных данных оформлять отдельно с явным перечнем.
6. Provider/storage failure сохраняет отказ/`SECURITY_UNAVAILABLE`, не allow и
   не успешную пустую выдачу. Роль администратора сама по себе не даёт C5 read.

### 6.3. Матрица приёмки

| Данные/действие | Admin C5 decision | Server C5 decision |
| --- | --- | --- |
| Только `dimension="*"` | deny | deny |
| Точный global USER read grant | allow | allow |
| Точный ROLE read grant и назначенная роль | allow для пользователя | allow |
| Точный grant только с dimension value | deny | deny |
| No grant / отзыв последнего подходящего allow | deny | deny |
| USER без allow, назначенная ROLE с allow | allow | allow |
| Роль без назначения пользователю | deny для пользователя | deny |

Отдельно проверить, что legacy RLS wildcard продолжает действовать; сохранение и
отзыв через настоящий admin service сразу отражаются в серверной проверке;
ошибка provider не показана как достоверное разрешение. Для пользовательского
effective decision учитывать назначенные роли, для решения ROLE — гранты этой роли.

Проверки распределяются между owner tests C5 и app IT admin/provider/storage.
DoD: parity matrix зелёная, read-only grant contract записан, новые сигнатуры
классифицированы, surface baseline обновлён по проверенному изменению API.

### 6.4. Записанные решения API-review и результат З2 (2026-10-04)

1. Определения C5 read keys регистрируются явно: `C5ReadPermissionRegistry`
   (owner `platform-rls`) хранит ключи, переданные registrars
   (`C5RestOperationRegistrar`, `C5EntityReadPermissionRegistrar`). Признаком C5 read
   является только регистрация; префикс `ENTITY:`/`REST:`, CHECK_ONLY и wildcard
   таковыми не считаются. Регистрация никому не выдаёт грант.
2. Effective decision владеет `C5ReadDecisionService` (owner `platform-rls`, один
   класс для сервера и admin): точный ключ, `dimensionValueId == null`, только
   `canRead`; для USER — прямые гранты плюс гранты назначенных ролей, для ROLE —
   только её гранты; отказ хранилища пробрасывается (deny/error, не allow).
   `DefaultC5PermissionEvaluator` делегирует все три проверки этому сервису;
   существующие конструкторы сохранены, bean создаётся из общего сервиса.
3. App admin: для зарегистрированных C5 keys `collectEffective` использует общее
   решение (wildcard `*`, value-scoped строки и write-флаги не показываются как
   allow; источники — «прямой»/«роль: X»); `currentSingleGrant` читает только exact
   global строку и показывает только чтение; `saveSingleGrant` редактирует только
   global строку, отвергает update/delete на серверной границе и не трогает
   value-scoped строки; `saveGrants` для C5 key отвергается; UI не предлагает
   update/delete как рабочие C5-права. Остальные CHECK_ONLY и RLS-измерения
   сохраняют прежнюю семантику.
4. Новые публичные типы классифицированы: обновлён `platform-rls.api`, в D1-реестре
   добавлены два API-типа, замеры документа обновлены до 449/228/139; API/surface
   gates пройдены. Правило v1/major и формат ключей не менялись.

Прогоны и матрица — в [evidence, §9](rest-read-api-stage3-completion-evidence.md#9-з2--c5admin-parity-2026-10-04).

## 7. З3 — реальные роли и политика существующих callers

Owner JPA tests с подменённым role resolver полезны для механизма grants, но не
доказывают связку реальных application User/Role и C5. Нужна отдельная app-проверка.

1. Создать изолированные fixtures настоящих пользователей, ролей, назначений,
   `AccessGrant` и class/row RLS. Production C5 provider и app role resolver не мокать.
2. Выдать API LIST/DETAIL, root entity и необходимые output/filter/sort/id grants
   через роль; для to-one путей включить требования сегментов и target entity,
   установленные действующим reference contract.
3. Сравнить direct и role grants: одинаковые данные и total для одинаковых
   разрешений, различная видимость двух субъектов с разными JOURNAL grants.
4. Отозвать grant, снять роль, затем повторить вызов в свежем контексте: deny
   должен появиться без перезапуска приложения. Повторить смену субъектов и
   убедиться, что решения и видимость не переносятся между ними.
5. Проверить отсутствие одного необходимого права: API, entity, выбранный
   output attribute, filter attribute, default sort/tie-breaker, reference target.
   Deny не должен обращаться к business data. Не запрошенное поле maximum-проекции
   не требует grant лишь из-за присутствия в фиксированном fetch superset.
6. Повторить anonymous/system/bypass, несовпадение identity, отказ provider,
   скрытый/отсутствующий detail и FK-фильтр на скрытую цель по основному контракту.

### Политика перехода UI/service

- Собрать фактический список затронутых canonical read callers и нужных им FQN
  entity-read keys по существующим ролям. Не выдавать доступ всем сущностям каталога.
- Существующий canonical UI/service read сейчас требует entity C5; REST дополнительно
  требует API и attribute permissions. Не объявлять UI attribute enforcement выполненным.
- Документировать явную выдачу grants администратором: ключ, роль, причина,
  проверочный caller, ожидаемый отказ до выдачи и успешное чтение после неё.
  Startup registrar регистрирует определения, но не выдаёт grants.
- Проверить минимум обычный UI list/detail, app service read и запрещённый caller
  на реальных ролевых назначениях. Сохранить class/row RLS и existing UI fetch semantics.
- Тестовые фикстуры не становятся массовым production seed или migration script.

DoD: реальная ролевая матрица проходит; переход существующих read callers описан
и проверен без скрытой массовой выдачи. Write/actions остаются отдельным C5 backlog.

## 8. З4 — количественная приёмка SQL и графов

### 8.1. Единая методика

На каждом замере фиксировать resource/operation, субъект, прямые или ролевые grants,
размер данных, фильтр, сортировку, выбранные поля, page/size, Hibernate/DB версии.
OSIV и L2 cache выключить. Загрузку графа проверять в свежем persistence context;
подготовка fixture и предыдущие UI-вызовы не должны инициализировать REST entities.

Считать отдельно: root content/detail, count, дополнительные business/reference
reads, `AccessGrant` SQL, role-resolution SQL и остальные statements. RLS predicate
в content/count не является отдельным запросом. Измерение общего числа statements
само по себе не объясняет стоимость C5 или отсутствие незаявленной загрузки.

### 8.2. Обязательная матрица

| Сценарий | `PrdSpec` | `Nomenclature` |
| --- | --- | --- |
| LIST default/max projection, size 1/50/200 | Да | Да |
| LIST без фильтра и с объявленным фильтром | `journalId`, включая невидимую цель | `typeNom` |
| LIST край разрешённого окна / следующая запрещённая страница | Да | Да |
| DETAIL default/max projection, visible/missing | Да, также RLS-hidden | Да |
| Direct grants / реальные role grants | Да | Да |
| Loaded-state maximum LIST/DETAIL | Да | Да |
| UI LIST comparator того же root | Да | Да |
| Scalar consumption после транзакции | Да | Да |

Для оконного теста нужны как минимум W+1 подходящих видимых строк: пустая глубокая
страница на fixture из 50 строк не доказывает стоимость разрешённого окна.
Для size 200 fixture также должна обеспечивать полную страницу. Данные создаются
детерминированно, SQL setup исключается из measurements; total проверяется отдельно.

### 8.3. Численные бюджеты

До финального acceptance run записать таблицу пределов для каждого сценария:

- начальная цель business SQL: LIST — не более одного content и одного count;
  DETAIL — не более одного root read. Если текущий reference contract требует
  отдельного business read, его заранее перечислить и установить отдельный предел;
  не прятать его в категорию security grants;
- Hibernate/Spring Data могут не выполнять count для неполной страницы: это
  допустимо, но total должен быть верным. Full-page fixture проверяет count отдельно;
- число business SQL при size 1/50/200 не растёт с числом возвращённых строк;
- после возврата scalar результата — 0 дополнительных SQL;
- security/role SQL имеет отдельные конкретные пределы для direct и role сценариев,
  включая число ролей и permission keys. Увеличение page size не увеличивает их;
- для роли измерить базовый случай и пользователя с несколькими назначенными
  ролями. Зафиксировать роль short-circuit и число реально проверенных keys;
- край окна проходит установленный statement budget и loaded-state checks.
  Время на H2 записывается с условиями замера; переносимого latency SLA из него нет.

Значения 20/16 — исходные наблюдения, не универсальный окончательный бюджет.
Итоговые data/security числа заполняются после SQL trace З0 и проверки роли З3,
до последнего приёмочного прогона. Без заполненной таблицы З4 не закрывается.

Если права оказались дорогостоящими, сначала устранить доказанные повторные
role-resolution/grant queries в общем C5 owner. При необходимости выбрать узкую
batch-проверку или ограниченное одним вызовом переиспользование; сохранить actual
permission requirements, отказ provider и отзыв grants между вызовами.
Межзапросный кеш решений не вводить как условие завершения этого этапа.

### 8.4. Точные бюджеты графа текущих пилотов

| Ресурс/операция | Maximum full source paths | Число paths / association prefixes / глубина в сегментах |
| --- | --- | --- |
| `PrdSpec` LIST | `id`, `codeSpec`, `draft`, `nomenclature.code` | 4 / 1 / 2 |
| `PrdSpec` DETAIL | `id`, `codeSpec`, `draft`, `comment`, `journal.id`, `nomenclature.code` | 6 / 2 / 2 |
| `Nomenclature` LIST/DETAIL | `id`, `code`, `name`, `typeNom` | 4 / 0 / 1 |

Это численные acceptance budgets нынешних деклараций, а не новый универсальный
лимит для всех будущих ресурсов. Изменение maximum declaration требует повторного
review графа и стоимости; запрос `fields` не расширяет maximum.

Проверить explicit subgraph terminals, merge prefixes, включение root id и
отсутствие UI union/InstanceName deepen. Для `PrdSpec` LIST `journal` не загружен;
DETAIL допускает `journal.id` и `nomenclature.code`, но не раскрывает их лишние
associations. У `Nomenclature` не загружены UOM/groupNom/owned sections, если они
не заявлены. Идентичность/proxy state фиксировать отдельно от инициализации entity;
случайная Hibernate identity loading не считается расширением разрешённых полей.

Изменение UI metadata не меняет REST graph. Для обоих roots записать UI LIST
paths/SQL на сопоставимой fixture и объяснить REST delta. Проверить незаявленные
EAGER associations/collections root и target через loaded state и SQL.

DoD: заполнена численная таблица, все строки матрицы проверены настоящим
JPA/Hibernate, отсутствуют N+1/collection fetch/незаявленная загрузка, scalar DTO
не содержит entities, proxies или persistent collections.

## 9. З5 — воспроизводимость и итоговые gates

### 9.1. Bootstrap препятствия

1. Для sibling `dynamicreports` и `ureport3` сравнить изменённые build inputs с
   ожидаемым fingerprint. Обновлять manifest только после объяснения и принятия
   конкретного source snapshot; не подгонять хеш под неизвестное изменение.
2. Для отказа удаления JAR в `crudui/platform-identity-api/target` установить
   причину: доступ, фактический resolved path, используемый файл/процесс или иная
   ошибка. Предположение о блокировке файла не считать установленным фактом.
3. Устранить причину штатным способом и повторить Maven clean в правильном workspace.
   Не маскировать отказ выключением clean, не удалять соседний каталог рекурсивно
   и не завершать пользовательские процессы без явной необходимости и разрешения.
4. Сверить fingerprints всех изменённых in-repo модулей, включая test inputs,
   artifact file measurements и установленный dependency order.
5. Получить strict `-ValidateOnly` без `-AllowSourceDrift`/`-AllowToolchainDrift`,
   затем полный стандартный bootstrap по действующему build contract.

Заполненный отдельный Maven repository допустим для этого прогона; offline
допустим только при наличии всех зависимостей. Проверка CI на чистом checkout
и новое требование пустого repository не добавляются к Stage 3: это отдельные
инфраструктурные gates. Diagnostic validation не заменяет strict validation,
а targeted verify не заменяет уже предусмотренный стандартный bootstrap.

Если sibling inputs или доступ к внешнему workspace не позволяют завершить
bootstrap, зафиксировать точный открытый gate. Корректность read-кода может быть
подтверждена отдельно; полный этап при открытом обязательном gate не закрывать.

### 9.2. Минимальный набор проверок

Все команды запускаются на JDK 21 против одного явно выбранного Maven local
repository. Для root IT нужны явные имена тестов: компиляция IT не доказывает запуск.

```powershell
# JAVA_HOME указывает на установленный JDK 21.
# При отсутствии mvn в PATH использовать MavenCommand из build contract.
$restRepo = [IO.Path]::GetFullPath('.local-maven-repository')
mvn --version

# Последовательно установить текущие owner artifacts; install включает verify.
mvn "-Dmaven.repo.local=$restRepo" -f platform-rls/pom.xml install
mvn "-Dmaven.repo.local=$restRepo" -f platform-core/pom.xml install
mvn "-Dmaven.repo.local=$restRepo" -f platform-spring-boot-autoconfigure/pom.xml install
mvn "-Dmaven.repo.local=$restRepo" -f platform-rest/pom.xml install

# У starters есть собственные проверки resolved runtime dependency closure.
mvn "-Dmaven.repo.local=$restRepo" -f platform-backend-starter/pom.xml verify
mvn "-Dmaven.repo.local=$restRepo" -f platform-vaadin-starter/pom.xml verify

mvn "-Dmaven.repo.local=$restRepo" '-Dtest=CanonicalReadBoundaryIT,CanonicalReadTransactionBoundaryIT,FetchPlanReadBoundaryIT,FetchPlanMeasurementIT,DetachedRenderCostIT,EntitySummaryAccessPilotTest,EntitySummaryActionPilotTest,EntitySummaryReadPlanPilotTest,RestReadPipelinePilotIT,RlsReadGateTest' test
mvn "-Dmaven.repo.local=$restRepo" '-Dtest=RlsAutoConfigurationSmokeTest,RlsDimensionFactsIT,PlatformApiBaselineTest,PlatformPublicSurfaceTest,PlatformStarterCompositionTest,BootstrapScriptContractTest' test

.\scripts\bootstrap-local-dependencies.ps1 -LocalRepository $restRepo -ValidateOnly
.\scripts\bootstrap-local-dependencies.ps1 -LocalRepository $restRepo
```

Новые app admin/role/window/budget IT из З1–З4 добавить к явному списку запуска и
записать реальные имена в evidence. В owner modules проходят новые C5/REST tests,
auto-configuration/composition tests и применимые architecture guards. Starter
owner tests — `BackendStarterResolvedDependenciesTest` и
`VaadinStarterResolvedDependenciesTest`; root composition test их не заменяет.

Полный bootstrap включает application `clean verify` с production frontend;
после него убедиться, что обязательные новые IT действительно выполнялись
либо повторить их явно на артефактах этого bootstrap. Повторять полный набор
только после исправления конкретного отказа или изменения входных исходников.

### 9.3. Итоговая запись и закрытие

Итоговый versioned evidence рядом с основным планом содержит:

- revision/source fingerprints, toolchain, artifact coordinates и local repository;
- фактические команды, test names/counts, результаты owner/app/starter/bootstrap gates;
- матрицу разрешений и отказов, admin parity, реальные роли и отзыв;
- окончательный W, business/security SQL budgets и измерения обоих ресурсов;
- maximum paths, actual loaded state, UI LIST comparator и объяснённые deltas;
- явную UI/service grant policy и список внешних gates: полный C5, E1.2b,
  gate C, HTTP publication, выбранная deployment DB и инфраструктурный CI.

После зелёных З0–З5 обновить основной Stage 3 plan и roadmap: Gate 3A/3B закрыты,
F-REST-READ-3 завершён, перечисленные внешние gates остаются в своей очереди.
Отдельно передать этапу 4 стабильные request/result/outcome contracts, права,
лимиты и примеры пилотов. Обновление документации не разрешает публикацию API.

## 10. Контрольный список завершения

- [x] З0: проверяются текущие исходники/JAR; разница SQL 20/16 объяснена trace, UI LIST baseline записан.
- [x] З1: конечное окно задано, включая численные границы и fail-fast configuration; H2 pilot проверил оба ресурса на 10 001 видимой строке.
- [x] З2: admin/server C5 parity и сохранение read grants проверены (C5GrantAdminParityIT 7/7, smoke 17/17, owner 23+32+91, surface 24/24).
- [ ] З3: реальные роли/отзыв и политика UI/service callers подтверждены.
- [ ] З4: обе сущности покрыты LIST/DETAIL, SQL/graph/window budgets заполнены и проходят.
- [ ] З5: owner/app/starter checks, strict validation и стандартный bootstrap проходят.
- [ ] Итоговый evidence опубликован в versioned документации; основной план и roadmap согласованы.

Главные риски ограничены этими пакетами: слишком дорогое глубокое чтение,
повторные обращения C5 к grants/roles, расхождение admin decision, пропущенные
гранты существующих callers и внешний build snapshot. Для каждого риска есть
проверка и условие остановки; новые возможности в этап не добавляются.
