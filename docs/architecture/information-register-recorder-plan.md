# Регистр сведений: план добавления регистратора

Создан: 2026-10-06. Актуализирован: **2026-10-28**, по [сверке кода и evidence перед G1](information-register-pre-g1-audit.md).
Контракты Г0–Г5, все четыре модели и runtime/UI-часть Г6 реализованы; старые отметки об отсутствии startup-тестов и подключённого пилота сняты. Г7 принят в объёме итерации 1; весь Г8 и эксплуатационный scope Г7 не закрыты. Остатки: REG-A1/A2/A3 (текущая сквозная приёмка и внешний consumer), REG-U1 (поздняя UX-проба), REG-O1 (эксплуатация). Register facts/draft/генерация — новые REG-D1/D2, а не повторная разработка движка.
Решение пользователя о победителе записано в
[ADR-0015](decisions/ADR-0015-information-register-recorder.md); реализация его order
allocator относится к Г3.

Основание: [ADR-0013](decisions/ADR-0013-information-register-v1.md),
[canonical data path](decisions/ADR-0007-canonical-data-access-path.md),
[lifecycle](decisions/ADR-0005-entity-lifecycle-and-application-behavior.md),
[периодичность](information-register-periodicity.md) и
[общий план регистра](information-register-development-plan.md).

Предметный сценарий выбран 2026-10-06: периодический регистр «Объект учёта
номенклатуры», документы установки и корректировки, справочник «Тип учёта».
[Пилот, итерация 1](information-register-accounting-object-pilot.md), с контрольным самостоятельным регистром цен реализован и положительно оценён пользователем 2026-10-07. Это закрывает выбранный scope пилота, но не позднюю UX-пробу Р8 и не весь выпускной/эксплуатационный gate Г6–Г8.

## 1. Цель и принятые границы

Один регистр может получать записи от нескольких типов документов. Каждая запись
принадлежит одному конкретному регистратору. Платформа управляет набором записей
документа атомарно, а историю и срез регистра читает независимо от доступа к документу.

Опорные требования из обсуждения:

1. Ссылка на регистратор — стабильный код типа документа и его существующий `Long id`.
   Код объявляется один раз для типа; набор разрешённых типов задаётся у регистра.
2. Права на данные регистра самостоятельны. Видимость документа влияет на подпись
   и открытие регистратора; строки регистра и результат среза от этого не исчезают.
3. У подчинённого регистра период не входит в обязательный уникальный ключ строки.
   Предлагаемый ключ набора — `(recorder_type, recorder_id, line_no)`.
4. Период — `LocalDateTime`, режимы `EXACT/DAY/MONTH/YEAR` сохраняются.
   Точность физической колонки проверяется для выбранной СУБД.
5. Есть четыре сочетания периодичности и подчинения; достаточно двух независимых
   capability-интерфейсов и базовых классов для удобства объявления полей.
6. Обычный CRUD подчинённых строк закрыт на сервере. Запись выполняется командой
   замены набора конкретного регистратора с полной проверкой прав и данных.
7. Проведение, `DRAFT/POSTED`, неизменяемые движения и регистр накопления остаются
   отдельным треком [F-ERP](f-integration-erp-plan.md). Регистратор сведений может
   работать до появления posting capability.

Текущие самостоятельные `NomenclaturePrice` сохраняют ключ
`(nomenclature_id, period_date)` и режим DAY. Их автоматическое превращение
в подчинённый регистр в этот план не входит. Параметризация `IdentifiableEntity`
и полноценная UUID-поддержка также имеют [отдельный план](identifiable-entity-generic-plan.md).

## 2. Что меняется относительно текущей реализации

| Точка | Текущее поведение | Требуемое расширение |
| --- | --- | --- |
| `RegisterMetadata` / validator | key = dimensions + period | Режим подчинения; отдельные ключ строки, группа среза и порядок выбора |
| `RegisterDescriptor` / registry | Один список `keyColumns`; managed-типы из общего каталога | Recorder mapping, разрешённые типы, row key, slice order; тот же каталог сущностей |
| `RegisterReader.getLast` | DESC period, одна строка; равных периодов в группе нет | Общий определённый порядок для равных периодов |
| `SliceSpec` / CTE | MAX(period), JOIN по dimensions + period | Ровно один победитель при разрешённых совпадениях периода |
| Canonical read/write | Самостоятельный тип с обычной записью | Самостоятельное защищённое чтение; запись только через recorder-set command |
| Delete документа | Owned sections и reference check | Координируемая очистка подчинённых регистров в той же транзакции |
| UI / отчёт | Обычные поля и ссылки конкретного JPA-типа | Полиморфная подпись/навигация без влияния на выбор и count |

В существующей PostgreSQL-базе переход периода на TIMESTAMP уже
[выполнен и проверен](evidence/register-period-timestamp-postgresql-20261005.md).
Это самостоятельная миграция; она не создаёт поля регистратора.

## 3. Модель записи и ключи

### 3.1. Четыре варианта

| Режим | Capability | Предлагаемая база | Обязательная уникальность |
| --- | --- | --- | --- |
| Самостоятельный непериодический | Без temporal/recorder capability | `BaseEntity` | dimensions |
| Самостоятельный периодический | `PeriodicRegisterEntry` | `PeriodicRegisterEntryBase` | dimensions + period |
| Подчинённый непериодический | `RecordedRegisterEntry` | `RecordedRegisterEntryBase` | recorder_type + recorder_id + line_no |
| Подчинённый периодический | Оба интерфейса | `PeriodicRecordedRegisterEntryBase` | recorder_type + recorder_id + line_no |

Интерфейсы выражают возможность чтения периода или ссылки/номера строки.
Базы `@MappedSuperclass` объявляют общие поля; технический `id`, `version` и audit
поступают из существующего `BaseEntity`. Периодическая подчинённая база наследует
подчинённую базу и добавляет period; два интерфейса совмещаются без множественного
наследования классов. Каждый конкретный регистр имеет свою таблицу.

Метаданные остаются источником семантики. Startup проверяет согласованность
capability, аннотации и JPA mapping. Поддержка собственного mapped superclass
возможна при том же контракте; прикладному разработчику не нужны копии инфраструктуры.
При переносе существующего периода в базу сохраняется имя колонки через явный mapping.

Текущее состояние Г2: добавлены `recorderTypes` в декларацию, раздельные row key / dimension
group / winner order в descriptor, проверка разрешённых managed DOCUMENT типов и
`RecordedRegisterReader` для canonical HISTORY/DETAIL через `EntityDataAccess`.
JPA guard отклоняет generic persist/update/remove без package-scoped recorder-set scope.
`NomenclaturePrice` использует периодическую mapped base с прежней колонкой `period_date`.
Существующие `getLast` и report CTE явно отказывают подчинённым регистрам до реализации
общего winner order. Persistence-проверка подтвердила `@Embedded RecorderRef`, listener guard
и все четыре модели; проверки writer/rollback добавлены в Г3. JPQL bulk DML/native SQL не
запускают entity callbacks и остаются вне generic-write контракта; их может применять только
закрытый lifecycle participant с точным recorder predicate.

### 3.2. `RecorderRef` и каталог типов

- `RecorderRef` — JPA embeddable value object с `typeCode` и `Long id`, value equality,
  проверенной Hibernate/JPA формой создания и отсутствием частично заполненной ссылки.
- В каждой таблице подчинённого регистра: `recorder_type`, `recorder_id`, `line_no`,
  все `NOT NULL`; `line_no > 0`; UNIQUE по этой тройке. Recorder и номер строки
  неизменяемы для существующей строки.
- Предлагаемая декларация документа: `@RecorderType("receiving-document")`.
  Код стабилен при переименовании Java-класса, сущности и маршрута формы. Длина,
  грамматика, регистр символов и сравнение кодов фиксируются до первого DDL.
- `RecorderTypeRegistry` строит immutable snapshot из `ManagedEntityCatalog` и
  resolved metadata. Регистратор должен иметь effective `EntityKind.DOCUMENT`,
  Long identity и поддерживаемую version/lock capability. Наследование от
  `StandardDocumentEntity` обязательным условием не является.
- Startup отклоняет пустые/повторные коды, неизвестные или неподдерживаемые типы,
  неподчинённый регистр с recorder mapping и противоречивые declarations.
- Регистр объявляет разрешённые классы документов; descriptor переводит их
  в стабильные коды через единый каталог. Новое поле на каждый тип документа не нужно.
- Java FQCN, имя таблицы и form route alias не сохраняются как идентичность документа.
  Изменение опубликованного кода требует контролируемой миграции сохранённых ссылок.

Обычного FK на разные таблицы документов у такой пары нет. Ответственность за
существование документа, удаление и гонки принадлежит общему протоколу записи.
Общая таблица идентичностей остаётся альтернативой отдельного ADR; она могла бы
иметь собственный ключ без смены генераторов ID документов, но добавляет свой lifecycle.

### 3.3. Группа и порядок среза

Recorder identity, line identity, dimensions и temporal order — отдельные понятия.
Регистратор автоматически не добавляется в измерения. Иначе срез вернёт последнюю
строку каждого документа вместо последнего значения для бизнес-группы.

Для непериодического подчинённого регистра несколько документов могут иметь
одинаковые измерения. `getLast(asOf)` ему недоступен; историю/набор документа читают
явными операциями. Единственное текущее значение или агрегация требуют отдельного контракта.

Для периодического подчинённого регистра допускаются одинаковые dimensions + period.
Более высокая точность времени этого не исключает: DAY и округление TIMESTAMP тоже
создают совпадения. Принятое пользователем правило: при равном периоде побеждает
последний по порядку набор регистратора. Для однозначной реализации в
[ADR-0015](decisions/ADR-0015-information-register-recorder.md) предлагается:
`(period, recording_order, line_no)`. `recording_order` сохраняется с записью;
технический id документа и wall-clock время при этом не задают победителя.
Транзакционный allocator должен доказать сериализованный commit order на Г3.

## 4. Права, экспозиция и транзакция

### 4.1. Раздельные границы доступа

| Операция | Проверка |
| --- | --- |
| История / getLast / slice / count | C5 и собственные RLS регистра; полевых прав нет (ADR-0018) |
| Подпись и открытие документа | Права конкретного типа и строки документа |
| Замена набора | Явный допуск команды, recorder scope и проверка старых/новых строк регистра |
| Удаление документа с очисткой | Допуск удаления документа; закрытая lifecycle-очистка только его строк |

Из права изменить документ не выводится произвольное право записи в регистр.
Для производных записей возможна узкая серверная authority конкретной бизнес-команды:
она выдаётся явно, ограничена одним recorder scope и перечнем регистров, проверяется
и наблюдается в audit. Передаваемый клиентом флаг privileged эту границу не открывает.

При замене проверяется весь прежний и новый набор, включая удаляемые строки.
RLS-отфильтрованный SELECT не доказывает, что прочитан полный набор.
Операция при недостаточном допуске отказывается целиком либо выполняется через
заранее разрешённую authority команды. Частичная замена невидимых строк запрещена.
Успешный document delete в той же транзакции очищает принадлежащие ему строки через
закрытую lifecycle-операцию по `RecorderRef`. Она не показывает содержимое строк и
не выдаёт права читать/изменять регистр. Другие операции записи всё равно требуют
явной authority.

Групповая инвариантность RLS для среза сохраняется. Наследование видимости от
регистратора могло бы скрыть одну версию группы и открыть другую; такая схема
не включается молча. Caption enrichment выполняется после защищённого выбора строк.

### 4.2. Экспозиция подчинённой строки

Подчинённый регистр должен иметь самостоятельные read capabilities и серверный
запрет generic CREATE/UPDATE/DELETE. Декларация этого режима выводится из register
descriptor в общий capability catalog; обязательный ручной bean на каждый регистр
не добавляется. Конкретная классификация экспозиции фиксируется в ADR.

`TableSectionMetadata` напрямую не подходит: фиксирует одного parent-типа,
`@ManyToOne`, RLS `INHERIT_ROOT` и owned-экспозицию. `EntityDataAccessResolver`
не предоставляет owned row автономный canonical handle. Поэтому register set
получает собственный контракт, а из aggregate machinery переиспользуются проверенные
границы транзакции, validation, lifecycle, events и rollback.

### 4.3. Замена набора и конкурентность

Предлагаемый вход: `replaceForRecorder(registerType, recorder, expectedRecorderVersion, rows)`.
Параметры точной публичной сигнатуры определяются после спайка. Команда:

1. Разрешает типы через managed registries, проверяет разрешённый recorder и authority.
2. В общей транзакции блокирует строку документа, проверяет существование и ожидаемую
   версию. Блокировка берётся до чтения/замены набора. Новый документ сначала сохраняется
   в этой же транзакции и получает Long id.
3. Проверяет полный старый/новый набор, Bean Validation, resource rules, нормализацию
   периода, порядок среза и принадлежность каждой строки этому recorder scope.
4. Назначает `line_no` по объявленной политике (предложение — 1..N), затем атомарно
   заменяет только этот набор. Пустой явно переданный набор означает очистку;
   отсутствующий вход при document save означает отсутствие изменения регистра.
5. Продвигает version документа даже при изменении только набора. В одном document
   command продвижение согласуется с существующей aggregate version boundary.
6. Делает flush в управляемой границе, возвращает результат и version, публикует
   согласованные lifecycle/events; внешние эффекты выполняются после успешного commit.

`PESSIMISTIC_WRITE` на существующем документе — стартовый кандидат переносимого
протокола. Замена пустого набора блокируется тем же способом: строка регистра
для блокировки не требуется. Expected version защищает от замены свежего набора
устаревшим payload; одна блокировка обеспечила бы только последовательность записей.

Delete документа обязан использовать ту же блокировку и version policy. Проверка
exists без общего протокола допускает гонку «проверили документ → документ удалён →
вставили строки». Все поддерживаемые каналы удаления включаются до выпуска write API.
Для нескольких документов/регистров задаётся устойчивый порядок захвата блокировок.
Ошибки unique, optimistic conflict, deadlock и lock timeout различаются. Retry принадлежит
внешней transaction boundary; неудачная PostgreSQL-транзакция не продолжается.

Прямой SQL/bulk DML внешнего администрирования находится вне этого протокола.
Регламент эксплуатации запрещает удаление документов таким путём без согласованной
очистки; privileged диагностика выявляет orphan refs и неизвестные коды типов.

Замена набора сведений может удалять прежние строки этого документа. Это модель
актуального набора, а не неизменяемый журнал всех исправлений. Требование хранить
каждую коррекцию повлечёт отдельную version/batch модель.

## 5. Последовательность реализации

Этапы Г0–Г8 — отдельные небольшие патчи с проверкой своих owner-модулей и приложения.
Закрытие означает выполненные критерии, а не только наличие кода.

| Этап | Содержание | Зависимость | Результат |
| --- | --- | --- | --- |
| Г0 | Общие контракты и ADR | Требования пользователя и модель приложения | Winner order, row/group keys, права и lifecycle определены; ADR-0015 |
| Г1 | RecorderRef и каталог типов | Г0 | Реализовано; duplicate code/non-DOCUMENT startup tests существуют и прошли 2026-10-28 |
| Г2 | Четыре модели, metadata/descriptor/capabilities | Г1 | Реализовано; four-model JPA/constraints/write-guard tests прошли в owner gate |
| Г3 | Set writer, права, блокировка и version | Г2 | Проверенная атомарная замена одного набора; выполнено |
| Г4 | Document save/delete coordination | Г3 | Документ и все его наборы меняются/удаляются согласованно; выполнено |
| Г5 | getLast, SliceSpec и CTE с общим порядком | Г0, Г2; для сквозных сценариев Г4 | Реализовано; текущий CTE application gate требует REG-A1 |
| Г6 | UI, подписи, ссылки и saved queries | Г4–Г5 | Runtime/UI реализованы; REG-U1 открыт, старый UDR-report gate снят потребителем |
| Г7 | Предметный пилот и схема развёртывания | Г4–Г6 | Итерация 1 принята; общий production/migration scope REG-O1 открыт |
| Г8 | Поставка, документация и итоговая приёмка | Г7 | S0–S5 завершены; внешний consumer и текущий общий gate REG-A2/A3 открыты |

### Г0. Зафиксировать семантику до изменения схемы

- Использовать принятый [ADR-0015](decisions/ADR-0015-information-register-recorder.md)
  для stable code + Long id, четырёх режимов, row/group key, winner order, независимых
  прав, write/delete authority и optimistic version + общего lock.
- Правило равных period зафиксировано в [ADR-0015](decisions/ADR-0015-information-register-recorder.md):
  последний набор записи побеждает; полный порядок содержит `recording_order` и `line_no`.
  Это порядок записи набора, не дата или номер документа.
- Production-приёмка остаётся отдельным scope Г7/REG-O1; принятый пилот и semantic kind её не подменяют. Для контрактной проверки использовать
  `ReceivingDocument` и второй managed `DOCUMENT` fixture; прикладное назначение
  регистра не выводить из того, что этот document уже существует.
- Зафиксировать lifecycle: отсутствующий set input оставляет набор как есть;
  явный empty list его очищает; разрешённое удаление документа закрыто очищает только
  его recorder sets. Изменение других полей документа само по себе set не заменяет.
- Set replacement требует register-specific write authority. При недоступном документе
  остаются доступны те же строки/срез, которые разрешают права регистра; caption/link
  скрывают поля документа. Удаление документа выполняет закрытую очистку его строк.
- Optimistic version обязательна: повтор со старой expected version отказывается и
  не получает новый `recording_order`; явный новый вызов с актуальной version считается
  новой записью и становится последним при равном периоде.
- Составить examples: одинаковый период у разных
  document types, повтор полного набора, две строки одной группы, rollback, удаление
  победившего документа и отказ открыть документ при разрешённом чтении регистра.
- Зафиксировать line numbering 1..N по порядку входного набора. Физическая замена
  строк может создавать новые технические id; эти id не участвуют в выборе победителя.
- Зафиксировать transaction entry point, rollback и внутреннюю очистку набора при
  document delete; внешний grant на прямую запись регистра не выдаётся.
- Проверить минимальный AppDev recipe и распределение API/SPI/internal; перенос в
  `platform/query` и большой ID-рефакторинг предпосылками регистратора не являются.

Приёмка Г0: ADR-0015 и permission/lifecycle matrix дают один ответ для всех examples;
повтор stale request, rollback, document delete и denial открыть document не меняют
согласованный winner/read contract. Реальный бизнес-пилот и его dimensions/resources
фиксируются перед Г7. Механизм `recording_order` и реальная конкурентность проверяются
на Г3.

### Г1. Ссылка и каталог разрешённых документов

- Добавить `RecorderRef`, `@RecorderType` и `RecorderTypeRegistry` в текущий нейтральный
  register package; открыть только прикладной surface, необходимый для объявления и
  построения ссылки. Для существующего документа объявить код `receiving-document`.
- Каталог получает классы только из `ManagedEntityCatalog`, а effective DOCUMENT — из
  `MetadataResolver`. Второго classpath scan нет.
- Проверить mapping, value equality, стабильный Long identity и effective DOCUMENT;
  весь класс не загружается по произвольному FQCN клиента. Caption resolution позднее
  загружает/авторизует документ своей самостоятельной канонической операцией.
- Предусмотреть диагностику удаления опубликованного типа из конфигурации и изменения кода.
- Backend-каталог не зависит от Vaadin, FormRouteCatalog или reportstudio.

Текущее состояние Г1: API, snapshot registry и startup validator добавлены; production-класс
`DOCUMENT` объявляет стабильный код. Проверка Г4 подтвердила JPA-сохранение
`RecorderRef` и различение двух типов документов с одинаковым Long id.
Сверка 2026-10-28: прежняя отметка об отсутствии startup-тестов устарела. `RegisterStartupValidatorTest.duplicateRecorderCodeStopsStartupBeforeAnyReaderIsCreated` и `recorderWithNonDocumentKindStopsStartup` находятся у module owner и прошли свежий прогон. Эксплуатационная проверка уже сохранённых ссылок при смене/удалении type code остаётся отдельным REG-O1; проверка деклараций её не заменяет.

Приёмка: два типа документов с одинаковым числовым id дают разные RecorderRef;
дубликат кода и недопустимый тип останавливают старт; JPA сохраняет пару code/id;
повторный поиск кода даёт один immutable snapshot. Один код объявлен в одном месте.

### Г2. Метаданные, базы и самостоятельное чтение

- Добавить два capability-интерфейса, mapped superclasses и recorder declaration.
- Подчинённый режим включается только для `RecordedRegisterEntry` с непустым списком
  `recorderTypes`; stable code и effective DOCUMENT разрешаются через общий managed catalog.
- Расширить descriptor: отдельные row key, dimension group, recorder fields,
  разрешённые types и winner order. Существующий `keyColumns` не переопределять
  молча так, чтобы прежние consumers считали recorder key группой среза.
- Научить validator проверять embedded column mapping, inherited fields,
  `NOT NULL`, immutability, UNIQUE/line policy, периодичность и order constraints.
- Встроить read/write policy в register catalog. Подчинённые строки читаются только
  через canonical HISTORY/DETAIL с собственными C5/RLS; document access
  не меняет видимость. Generic JPA persist/merge/remove отклоняются lifecycle guard до SQL;
  разрешающий scope открывает только set command внутри register package.
- `RecordedRegisterReader` предоставляет страницу истории с опциональным dimension-фильтром
  и чтение детали по техническому id; document не загружается и не авторизуется этим API.
- До общего recorder winner executor существующие `getLast` и latest CTE должны отказывать
  на подчинённом descriptor, а не возвращать недетерминированную строку.
- Проверить все четыре режима реальными JPA-моделями. Непериодический режим
  отклоняет temporal slice; самостоятельные цены сохраняют прежний контракт.

Приёмка: справочная история читается через canonical path, C5/RLS действуют;
вызовы generic create/update/save/delete подчинённой строки отклоняются на сервере.

### Г3. Запись набора и PostgreSQL-спайк конкуренции

- Реализовать команду и общий recorder lock/version context. Подготовить test adapter
  для стандартной document version boundary; authority создаётся только серверным исполнителем.
- Validation и enforcement охватывают весь набор, а не только добавленные строки.
- Протокол replacement выбирается явно: при bulk delete/insert executor отвечает
  за lifecycle, RLS, events, persistence-context cleanup и flush ordering. Простое
  `deleteAll`/`saveAll` в repository не заменяет эту ответственность.
- Выполнить real PostgreSQL тесты: два replacement одного recorder с одинаковой
  ожидаемой version; пустой набор; раздельные recorder; timeout; rollback после
  удаления старых строк/части INSERT; отказ проверки любой строки; bounded retry.
- Проверить изменение period после нормализации, точность выбранной колонки и
  конкуренцию документов с одинаковыми dimensions/period согласно Г0.

Приёмка: получен ровно один целый набор либо определённый конфликт; документная
version продвинута; ошибка не оставляет частичной очистки/вставки и побочных эффектов.
Write API ещё не публикуется без delete coordination следующего этапа.

Фактическая проверка Г3: `RecorderSetWriter` остаётся package-private и требует уже
открытую транзакцию. Он блокирует документ, сверяет ожидаемую version, затем блокирует
per-register clock; старый полный набор удаляется и новый вставляется под точным
package-scoped `(register type, RecorderRef)` guard. Версия документа принудительно
увеличивается пессимистической блокировкой. Результаты сохранены в
[evidence Г3](evidence/register-g3-tests-20261005.md). Общая форма save/delete и права
команды закрыты следующей границей Г4.

### Г4. Координация документа и его регистров

- Ввести dedicated recorder-set contribution/adapter к общей операции документа.
  Вход отличает отсутствующий набор от явного пустого. Вычисление производных строк
  принадлежит предметной операции; persistence orchestration принадлежит платформе.
- Согласовать текущие root/owned sections, recorder sets, version, lifecycle/events
  и rollback. Не подключать полиморфный регистр как обычный `TableSectionMetadata`.
- В canonical delete добавить платформенный participant: lock/version check,
  очистка разрешённых наборов, сохранение reference check и удаление root в одной
  транзакции. Участник получает регистры из registry, без ручного списка в document service.
- Проверить обходные пути: формы, canonical APIs, typed services, REST/импорт,
  standalone set command и другие поддерживаемые входы. Неразрешённый канал отказывает.
- Выполнить PostgreSQL гонку delete vs replace в обоих порядках, конфликт document
  edit vs replace, откат при reference-check отказе и при ошибке одного из регистров.

Приёмка: удалённый документ не оставляет строк; после rollback восстановлены root,
owned sections и все recorder sets. Правка документа по устаревшей version отказывается.

Фактическая проверка Г4: `MetadataDrivenAggregateSaveService` принимает только
явно переданные серверные contributions, отдельно различает отсутствие набора и пустой
набор, разрешает ровно одного participant и выполняет его в транзакции сохранения root
и owned sections. Контекст версии не допускает двойного продвижения при одновременном
сохранении табличной части и нескольких наборов. Canonical delete блокирует и обновляет
root, запускает registry-driven очистку всех принадлежащих ему регистров до reference
check и удаления root; отказ reference check откатывает очистку. Удаление затрагивает
только `(register type, RecorderRef)` текущего документа.

JPA listener блокирует обычные `persist/update/remove` подчинённых строк; внутренний
`RecorderSetWriter` не публикуется как standalone API. Проверен кодовый маршрут: metadata
roots обслуживаются canonical path, для них запрещён generic internal-store service,
а подчинённая строка не имеет автономного canonical write capability. Native/bulk SQL
вне lifecycle guard остаётся запрещённым обходным путём по эксплуатационному контракту.
Проверки и границы описаны в [evidence Г4](evidence/register-g4-tests-20261005.md).

### Г5. Общая семантика getLast и среза

Спайк подтвердил последовательные максимумы `period → recording_order → line_no`; production
`RegisterSliceCteGenerator` строит этот порядок для настоящего JPA `RecorderRef`, а точечный reader,
canonical paged reader и CTE выбрали одну строку на одинаковом fixture. `SliceSpec` различает
срез по всем регистраторам и срез внутри точного набора `(typeCode, id)`; генератор применяет
набор до каждого максимума, а visual package v2 сохраняет это намерение типизированными данными
CTE. Проверены Hibernate/H2, запрет технических полей в generic filters и JSON round-trip.
Результат и конкретные тесты: [evidence Г5 CTE](evidence/register-g5-cte-spike-20261005.md).

`RegisterSliceReader` строит portable Criteria `NOT EXISTS` predicate из descriptor и `SliceSpec`,
затем вызывает `EntityDataAccess.list`. Поэтому отдельные content/count Criteria-запросы и C5/RLS,
FetchPlan, exposure и telemetry остаются ответственностью canonical read boundary. Отбор dimensions
и selected `(typeCode, id)` сужает множество кандидатов до выбора победителя; resource/period
`afterSlice` применяется после него. Поддержаны шесть scalar-операторов, reference-dimensions
сравниваются по persistent id, сортировка ограничена dimensions/resources/period и дополняется
идентификаторами группы и строки. Требуется bounded page до 500 строк.

Сквозной H2 oracle теперь сверяет point `getLast()`, canonical content/count и CTE при равных
периодах, двух document types с одинаковым numeric id, точной включительной границе, будущих
строках, полном tuple-порядке, повторной замене и post-filter `150 → 90`; отдельно пройдены
нормализованные DAY/MONTH/YEAR периоды. Canonical page/count проверены через `CanonicalReadExecutor`
с C5 gate и Hibernate RLS filter: скрытая группа отсутствует и в content, и в total. Report C5/RLS
для CTE остаётся отдельной проверкой ATTR-read регистра; права на документ-регистратор не нужны.

Функциональная приёмка Г5 закрыта на Hibernate/H2. Проверка переносимости JPQL/HQL на выбранный
эксплуатационный backend, физическая схема и explain/performance относятся к Г7 после выбора
backend-профиля; PostgreSQL не объявляется единственной целевой СУБД. Persisted/reopened report и
параметр даты относятся к Г6, а не блокируют общее slice-предикатное ядро.

- Зафиксировать winner order в нейтральном descriptor/spec; потребители выбирают
  запись одним правилом. Point reader больше не ограничивается сортировкой по period.
- Для canonical paged slice Р7 обеспечить тот же winner predicate в data и count,
  устойчивую сортировку страниц и сохранение C5/RLS group visibility. Выполнено через
  `RegisterSliceReader` и canonical `EntityDataAccess.list`; отдельный обход read boundary не создаётся.
- Для CTE сначала выполнить спайк выразимости полного порядка имеющимся visual AST
  и compiler. Возможные варианты — последовательные CTE максимумов или другой
  доказанный запрос; конкретный SQL-алгоритм выбирается по спайку и переносимости.
- После спайка перенести тот же порядок в production generator; recorder identity и line/order
  передавать через разрешённую проекцию регистра, без JOIN к документам. Выполнено на Hibernate/H2;
  report C5/RLS и canonical page/count parity также проверены. Backend-specific compatibility и
  производительность переносятся в Г7, когда приложение фиксирует набор поддерживаемых backend'ов.
- MAX каждого столбца независимо использовать нельзя: значения могут принадлежать
  разным строкам. LIMIT 1 после среза групп также не разрешает совпадения внутри групп.
- Resource filters применяются после выбора победителя. `SliceSpec.RecorderReadIntent`
  различает всех регистраторов и срез только среди выбранных ссылок; этот фильтр применяется
  до winner maxima. История документа остаётся отдельной операцией, а generic visual WHERE
  по техническим полям регистратора запрещён.
- Добавить recorder identity и line/order fields в допустимую проекцию без JOIN
  на документы. Выполнено в CTE; формат typed selection версии visual package поднят до v2.
- Независимый oracle сверяет getLast/canonical slice/CTE/count на Hibernate/H2; включает два
  document-типа, равные периоды, точную границу, будущие строки, DAY/MONTH/YEAR, повтор replacement
  и post-filter 150 → 90. Выполнено.

Приёмка Г5 выполнена: getLast, canonical slice, CTE, count и независимый oracle выбирают одну
и ту же строку; C5/RLS фильтруют группу одинаково для data и count. Прежние самостоятельные
регистры и формат совместимых отчётов продолжают работать; saved-report lifecycle остаётся Г6.

### Г6. Подпись регистратора, формы и отчёты

- Добавить read-only представление RecorderRef; подчинённые строки не получают
  обычных кнопок create/edit/delete. Пересчёт/замена запускаются разрешённой командой документа.
- Получать подписи пачками: группировка по type, canonical/RLS чтение ids с нужным
  FetchPlan, ограниченные IN chunks. Бюджет запросов зависит от типов/chunks страницы;
  запрос на каждую строку отсутствует. Cache учитывает security context.
- Недоступный документ показывать общей закрытой подписью, без его номера, даты
  и разрешённой ссылки. Состояния отсутствия и запрета для обычного читателя
  не раскрываются по-разному; orphan diagnostics — отдельный privileged сценарий.
- Навигация использует RecorderTypeRegistry → class → существующий FormRouteCatalog
  и механизм ссылок формы; повторного route registry или конкатенации FQCN нет.
- При изменении прав на документы сами строки/число строк/победитель среза неизменны.
  Права регистра продолжают управлять доступной проекцией recorder fields.
- Для EXACT используется принятый 2026-10-07 миллисекундный контракт: `FieldFactory` создаёт стандартный `DateTimePicker` с шагом 1 ms; `RegisterPeriodicity.EXACT` усекает до миллисекунд без округления. Старое требование nano-editor заменено этим решением, см. [периодичность](information-register-periodicity.md).
- Проверить codec/import/export saved report, форму запуска, Preview/PDF и сценарий
  документа без доступной формы. Публикация отчёта не даёт доступ к регистратору автоматически.

Приёмка: нет caption N+1 и утечек, переход доступен по правам, read/slice/count parity
сохраняется при полном отказе чтения документов. UI/server запреты совпадают.

Прогресс на 2026-10-06: добавлены read-only `RecorderRefPresentation`/`RecorderRefDisplay`
и resolver подписей, который группирует ссылки по типу, читает id ограниченными пачками через
canonical/custom read policy, маскирует отсутствие и отказ одинаково и строит ссылку только
после разрешённого чтения. Per-call разрешение не кешируется между security context. Добавлен
исполняемый сценарий сохранённого и повторно открытого параметризованного recorder-среза без
C5-доступа к документам; форма запуска и entity-форма сохраняют долю секунды. Generic binding
отклоняет invalid-компоненты даже при необязательном поле. Целевые сценарии, полный набор
`platform-vaadin` и прикладная форма проверены; детали в [evidence Г6](evidence/register-g6-presentation-20261006.md).

Актуальный результат 2026-10-28: после исторического evidence Г6 пилот и Р8 подключили подпись к реальному стандартному списку, read-only действия и saved slice state; свежие 24 tests UI-модуля зелёные. Утверждение «нет подключённого пилота» снято. Поздняя пользовательская проба Р8 остаётся REG-U1. Старый UDR-сценарий отчёта цен снят владельцем 2026-10-20, движок UDR удалён 2026-10-24; его Preview/PDF не восстанавливаются ради закрытия Г6. Текущий query-slice контракт и его регрессия учтены в REG-A1/A3.

### Г7. Предметный пилот и миграции

- Для выбранного пилота реализуются два DOCUMENT-типа: «Установка объекта учёта»
  и «Корректировка объекта учёта». Их структура и бизнес-правила записаны в
  [плане пилота](information-register-accounting-object-pilot.md).
  `ReceivingDocument` остаётся техническим примером DOCUMENT с Long id, version
  и owned sections. `PrdSpec` ради проверки регистраторов не меняется.
- Мультиполиморфизм проверить двумя DOCUMENT fixtures с совпадающими id; второй
  реальный тип документа подключается при предметной потребности. Это явно разделяется в evidence.
- Для пилота создать отдельную таблицу подчинённого регистра. Новый DDL содержит
  row UNIQUE, checks, индексы чтения recorder set и dimensions/period/order для slice.
  Индекс, начинающийся с recorder_type/id, не заменяет индекс среза по измерениям.
- Если всё же переводится заполненный самостоятельный регистр, сначала определить
  происхождение каждой старой строки. Без доказанного документа backfill не назначает
  фиктивного регистратора. Планировать copy/backfill, проверку данных, cutover и откат отдельно.
- Проверить физическую схему, фактический TIMESTAMP, данные, orphan refs и EXPLAIN
  point/slice/recorder-set запросов на выбранной СУБД. Измерения PostgreSQL относятся
  к этой реализации; другой DB backend получает свой compatibility/concurrency gate.
- `ddl-auto=update` не заменяет schema migration. Rollback приложения согласуется
  с уже созданными recorder rows и форматом сохранённых отчётов.

Приёмка: предметный пользовательский сценарий работает, миграция воспроизводима,
права регистра и документа проверены отдельно, rollback не теряет существующие данные.

Статус 2026-10-28: пилот итерации 1 выполнен, есть PostgreSQL/browser evidence и положительная оценка пользователя. Полный эксплуатационный scope (orphan/type-code изменения, EXPLAIN, rollback/backfill существующих данных) не объявляется принятым — REG-O1. Не переводить существующие независимые записи в recorder-owned режим без отдельного migration plan.

### Г8. Документация, поставка и закрытие

- Опубликовать AppDev recipe: document type code, declaration/base регистра,
  record-building contribution и вызов стандартной операции. Общие locks, grants,
  replacement, deletion и readers не копируются в приложение.
- Закрепить owners API/SPI/internal; обновить public surface, package matrix,
  composition и subsystem closure только по проверенным направлениям зависимостей.
- Новая инфраструктура сохраняет границу register/backend → read/write contracts;
  зависимости на reportstudio/Vaadin/Jasper в backend не появляются. При необходимости
  физического owner-модуля extraction имеет свой патч и supply-chain проверку.
- Запустить согласованный набор owner/app/security/ORM/report tests и реальные
  PostgreSQL concurrency scenarios. Сохранить source/JAR fingerprints и результаты
  отдельно от исторической приёмки Р1–Р6.
- Обновить ADR, roadmap, эксплуатационный регламент и описание ограничений.

Приёмка: нет незакрытых семантических вопросов/обходов, все заявленные сценарии
доказаны. Posting/immutable ledger не объявляются завершёнными этим этапом.

Статус 2026-10-28: поставка и module-owned API/tests завершены S0–S5. Внешний consumer REG-A2 и текущая сквозная/общая приёмка REG-A1/A3 остаются открытыми; весь Г8 не закрыт. Доказательства и точный следующий пакет — в [аудите](information-register-pre-g1-audit.md#6-действительные-остатки-и-порядок-закрытия).

## 6. Связь с Р7 и последующими работами

Г0 стоит выполнить до проектирования подчинённого slice в Р7: прежнее предположение
«dimensions + period уникальны» уже влияет на shape запроса и count. Р7 для текущих
самостоятельных цен можно продолжать независимо, сохранив возможность общего winner order.
Поддержка recorder-среза в Р7 закрывается только вместе с Г5.

Г1–Г2 реализованы независимо от UI и report add-on. Г3 и Г4 закрыты как единый выпуск
координированной записи. Г5 можно исследовать на текущих контрактах; его сквозная
приёмка использует Г4.
Г6–Г8 закрывают пользовательский сценарий и поставку.

Регистратор сведений выделяется из общего Р14 в этот самостоятельный трек.
Позднее posting/ledger переиспользует ссылку RecorderRef и каталог, но получает
собственные PostingBatch, reversal, effectiveAt/recordedAt и write policies.

## 7. Карта основных точек изменения

| Owner / исходная точка | Планируемая ответственность |
| --- | --- |
| [`org.ipro.register`](../../platform-register/src/main/java/org/ipro/register/RegisterMetadata.java) | Recorder contracts/registry, metadata, descriptor, validation и нейтральный порядок |
| [`RecorderTypeRegistry`](../../platform-register/src/main/java/org/ipro/register/RecorderTypeRegistry.java) / [`RecorderRef`](../../platform-register/src/main/java/org/ipro/register/RecorderRef.java) | Г1: stable code + Long identity, ManagedEntityCatalog membership, effective DOCUMENT |
| [`MetadataResolver`](../../platform-core/src/main/java/org/ipro/metadata/MetadataResolver.java) / ManagedEntityCatalog | Effective DOCUMENT и membership; существующий источник типов |
| [`EntityDataAccessResolver`](../../platform-core/src/main/java/org/ipro/data/EntityDataAccessResolver.java) / descriptor catalog | Самостоятельные read capabilities и запрет generic writes |
| [`CanonicalWriteExecutor`](../../platform-core/src/main/java/org/ipro/data/CanonicalWriteExecutor.java) | Document delete participant, enforcement, events/telemetry boundary |
| [`MetadataDrivenAggregateSaveService`](../../platform-core/src/main/java/org/ipro/crud/MetadataDrivenAggregateSaveService.java) | Совместимость root/sections/version и нового recorder-set adapter |
| [`RegisterReader`](../../platform-register/src/main/java/org/ipro/register/RegisterReader.java), SliceSpec и canonical slice reader | Один winner order, canonical getLast/slice/data/count |
| [`RegisterSliceCteGenerator`](../../platform-query/src/main/java/org/ipro/query/register/RegisterSliceCteGenerator.java) | Query AST/codec adapter той же семантики |
| [`FormRouteCatalog`](../../platform-vaadin/src/main/java/org/ipro/form/link/FormRouteCatalog.java) / UI adapter | Полиморфная ссылка, независимая подпись и навигация |
| [`ReceivingDocument`](../../src/main/java/org/ip/model/ReceivingDocument.java) / предметная операция | Пилот, код типа и вычисление записей |
| `scripts/`, integration tests и `docs/architecture/evidence/` | DDL, физическая проверка, concurrency и поставка |

Конкретное размещение новых классов внутри owner-модулей утверждается в Г0.
Таблица обозначает ответственность и проверенные исходные точки, а не разрешение
разместить весь код в корневом приложении.
