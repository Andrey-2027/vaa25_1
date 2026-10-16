# Архитектурная документация GitVaa

Этот каталог содержит версионируемые источники архитектурных решений. Локальные заметки и экспериментальные планы из `docs/plans/` не являются нормативными и остаются вне Git.

## Источники истины

| Документ | Назначение |
|---|---|
| [`../../JMIX_GitVaa_Roadmap_v2.md`](../../JMIX_GitVaa_Roadmap_v2.md) | Текущие приоритеты, этапы и Definition of Done |
| [`development-delivery-policy.md`](development-delivery-policy.md) | Выбор пилота или завершённого механизма, итерации пилота, границы объёма, критерии пробы и шаблон карточки решения |
| [`command-interface-pilot-plan.md`](command-interface-pilot-plan.md) | План пилота командного интерфейса: устойчивые ID, видимость, профили и назначения, единое дерево меню и плиток |
| [`evidence/command-interface-ki1-20261010.md`](evidence/command-interface-ki1-20261010.md) | КИ-1: каталог навигации, декларации приложения, resolver видимости и результаты проверок |
| [`evidence/command-interface-ki2-20261010.md`](evidence/command-interface-ki2-20261010.md) | КИ-2: защищённое хранение профилей, версии, назначения, адаптеры пользователей, PostgreSQL DDL и результаты проверок |
| [`evidence/command-interface-ki3-20261010.md`](evidence/command-interface-ki3-20261010.md) | КИ-3: единый UI-снимок меню и плиток, права чтения, изоляция UI, отказ загрузки и переключение флага |
| [`evidence/command-interface-ki4-20261010.md`](evidence/command-interface-ki4-20261010.md) | КИ-4: административный редактор профилей, предпросмотр, черновик, конфликты и назначения |
| [`command-interface-user-trial.md`](command-interface-user-trial.md) | КИ-5: запуск отдельного PostgreSQL-пилота, доступ и сценарий пользовательской пробы |
| [`decisions/command-interface-pilot-boundary.md`](decisions/command-interface-pilot-boundary.md) | Границы первого пилота: видимость, права, стабильная идентичность и UI-снимок |
| [`evidence/command-interface-ki5-20261010.md`](evidence/command-interface-ki5-20261010.md) | КИ-5: браузерная приёмка PostgreSQL, перезапуск, режимы и скриншоты |
| [`evidence/unidr-u2-20261010.md`](evidence/unidr-u2-20261010.md) | UniDR U2: источник позиций накладных, параметры/отборы, canonical lookup, защищённый запуск и результаты проверок |
| [`evidence/unidr-u3-20261010.md`](evidence/unidr-u3-20261010.md) | UniDR U3: сборщик DynamicReports, границы/чередование строк, PDF preview и PDF/XLSX через экспорт UDR |
| [`evidence/unidr-u4-20261010.md`](evidence/unidr-u4-20261010.md) | UniDR U4: редактор, настройка параметров, жизненный цикл preview/download, проверки и локальный UI-стенд; визуальная приёмка открыта |
| [`evidence/unidr-query-source-20261010.md`](evidence/unidr-query-source-20261010.md) | UniDR: редактор и конструктор источника UDR, параметры, динамические поля и защищённое выполнение |
| [`appdev-first-audit.md`](appdev-first-audit.md) | Living audit нарушений AppDev-first, artifact budgets и карта их устранения по этапам |
| [`c3-fetchplan-instance-name-plan.md`](c3-fetchplan-instance-name-plan.md) | Детальный план пилота C3: FetchPlan, InstanceName, миграция ручного fetch-кода и приёмочные gates |
| [`c4-data-access-facade-plan.md`](c4-data-access-facade-plan.md) | Детальный план C4: type capabilities, canonical data path, standard CRUD/search defaults, effective metadata, миграция и hardening |
| [`c4-inventory.md`](c4-inventory.md) | C4.0: проверяемая таксономия 37 persistence types, классификация service/repository/base слоя, consumers, baseline и пилоты |
| [`c5-operation-permissions-plan.md`](c5-operation-permissions-plan.md) | C5.4–C5.10: единое решение C5 о правах на операции, RLS за строки, wildcard только построчно; C5.6b — финальная граница записи. **C5.4–C5.9 выполнены 2026-10-10** (матрица П01–П14 на H2 — 246/0/0), C5.10 — учёт закрытия. Открыты attribute-часть, query paths, PostgreSQL-шаг П09 и ручная проба Gate C5.8 — в §7 плана с условиями входа |
| [`d-stage-fast-close-report-return-plan.md`](d-stage-fast-close-report-return-plan.md) | Ускоренное закрытие D-core с временно открытым D3.7 и обязательным возвратом к report add-on |
| [`d-r-report-addon-inventory.md`](d-r-report-addon-inventory.md) | Статический инвентарь исходников, регистраций, ресурсов и внешних зависимостей для D-R; фиксирует app-owned границу JR persistence |
| [`f-integration-erp-plan.md`](f-integration-erp-plan.md) | Принятый порядок будущих работ E1/F: capability-aware UI, внешние read models, владельцы, неизменяемые композиции, бизнес-факты и ledger |
| [`information-register-development-plan.md`](information-register-development-plan.md) | Подробный план развития регистра сведений: завершение v1.0, срезы в отчётах и LIST, безопасная запись по ключу, предметные пилоты и условный трек ledger |
| [`information-register-accounting-object-pilot.md`](information-register-accounting-object-pilot.md) | Пилот 1: объекты учёта номенклатуры по цехам, документы установки/корректировки, справочник типов учёта и контрольный сценарий самостоятельных цен |
| [`accounting-object-pilot-user-guide.md`](accounting-object-pilot-user-guide.md) | Подготовка, запуск и шаги пользовательской пробы первой итерации |
| [`accounting-object-pilot-iteration1-evidence.md`](accounting-object-pilot-iteration1-evidence.md) | Результаты проверок H2 / PostgreSQL / браузера и границы готовности первой итерации |
| [`information-register-cte-spike.md`](information-register-cte-spike.md) | Р2: принятый CTE-спайк на ценах и двухмерном регистре, оракул, типы и ограничения |
| [`information-register-report-security.md`](information-register-report-security.md) | Р3: принятые C5/RLS, видимость версий и цели измерения, ограничения проекции |
| [`information-register-r4-placement.md`](information-register-r4-placement.md) | Р4: общий пакет регистра, immutable descriptor/managed registry, автоконфигурация и границы report-адаптера |
| [`information-register-r5-generator.md`](information-register-r5-generator.md) | Р5: immutable SliceSpec, типы и фазы отбора, генератор CTE, namespace/bindings, composition и ограничения |
| [`information-register-r6-report.md`](information-register-r6-report.md) | Р6: сохраняемый отчёт цен, runtime дата, форма, фильтры, права и приёмка |
| [`information-register-periodicity.md`](information-register-periodicity.md) | Период LocalDateTime, точное время и календарные режимы, запись, чтение и переход схемы |
| [`information-register-recorder-plan.md`](information-register-recorder-plan.md) | План регистратора сведений: полиморфная ссылка, четыре режима, независимые права, замена набора, конкуренция, срезы и миграции |
| [`information-register-extraction-slice.md`](information-register-extraction-slice.md) | Замер замыкания движка `org.ipro.register` на приложение: полные/транзитивные/однопачечные ссылки, исходники артефактов и предлагаемый срез контрактов S0–S3 |
| [`information-register-extraction-plan.md`](information-register-extraction-plan.md) | План выноса механизма регистра в `platform-register`/`platform-register-vaadin`: срезы S0–S5, решения ADR-0024/ADR-0025, изменения гейтов, приёмка и риски. **S0–S4 закрыты (2026-10-13 … 2026-10-15)**, решение ADR-0025 принято и реализовано 2026-10-15 |
| [`evidence/register-s0-extraction-20261013.md`](evidence/register-s0-extraction-20261013.md) | D-R S0: перенос 17 типов в `platform-register` без бинов, реестр ролей и его негативные прогоны, API-baseline, запись в манифест и явно названные чужие красные гейты |
| [`evidence/register-s1-extraction-20261013.md`](evidence/register-s1-extraction-20261013.md) | D-R S1: реестр, чтение и фабрика читателя в `platform-register`, удаление прикладной конфигурации читателя, разделение монолитной конфигурации по срезам, 25 reviewed-ролей и названные чужие красные гейты |
| [`evidence/register-s2-extraction-20261013.md`](evidence/register-s2-extraction-20261013.md) | D-R S2: запись по ключу и смена ключа в `platform-register`, часы порядка с их сущностью и `@EntityScan`, правило ключа на стороне платформы, 35 reviewed-ролей, PostgreSQL-гонки 24/24 и чужие красные гейты |
| [`evidence/register-s3-extraction-20261014.md`](evidence/register-s3-extraction-20261014.md) | D-R S3: протокол набора владельца в `platform-register`, исчезновение прикладной регистрации регистра, 46 reviewed-ролей и понижение MODULE_API, PostgreSQL-гейт набора 27/27, найденный и починенный разрыв `RegisterBatchReadIT` и названное открытое решение ADR-0025 |
| [`evidence/register-s4-extraction-20261015.md`](evidence/register-s4-extraction-20261015.md) | D-R S4: стандартный интерфейс регистра в `platform-register-vaadin` — смена пакета, `provided`-Vaadin, пустой прикладной каталог интерфейса, точка расширения как проверяемый механизм, 6 reviewed-ролей, заборы артефакта и потребителя и названные чужие красные гейты |
| [`evidence/register-adr0025-row-lifecycle-20261015.md`](evidence/register-adr0025-row-lifecycle-20261015.md) | ADR-0025: замер, выбравший ветку (12 прикладных handler'ов, из них одно правило строки — на пути по ключу), счётчик хуков по строкам (H2 28/28 в классе и 64/64 в области, PostgreSQL 48/48 на одноразовой базе), откат порядка записи вместе с вето, отсутствие конструктора без реестра и находка приёмки — устаревшие `target/classes` приложения, затенявшие уехавший артефакт |
| [`evidence/platform-write-paths-gate-20261015.md`](evidence/platform-write-paths-gate-20261015.md) | Гейт перечня путей записи сущностей платформы: предмет (пять контуров записи плюс исполнители правил), четыре вердикта с проверкой в байткоде, 19 объявленных субъектов, негативный прогон (убрана строка — гейт назвал путь и его сигнал) и названные границы |
| [`identifiable-entity-generic-plan.md`](identifiable-entity-generic-plan.md) | План параметризации IdentifiableEntity по ID с сохранением Long: потребители, JVM-совместимость, этапы, сборка и приёмка |
| [`identity-id-consumer-guide.md`](identity-id-consumer-guide.md) | Завершённый generic identity: миграция внешнего потребителя, Long/wildcard примеры, binary compatibility, границы поддержки ID и владельцы runtime bridges |
| [`rest-read-api-plan.md`](rest-read-api-plan.md) | План входящего REST API чтения: явная публикация list/detail, поля, фильтры, права и граница canonical read path |
| [`rest-read-api-stage1-builder-plan.md`](rest-read-api-stage1-builder-plan.md) | Подробный первый срез F-REST-READ: builder декларации ресурса, immutable результат, локальные проверки и пилоты `PrdSpec`/`Nomenclature` |
| [`rest-read-api-stage2-catalog-plan.md`](rest-read-api-stage2-catalog-plan.md) | Подробный второй срез F-REST-READ: каталог явных деклараций, проверки JPA/экспозиции/capabilities, требования загрузки и доступа, регистрация и приёмка |
| [`rest-read-api-stage3-security-read-plan.md`](rest-read-api-stage3-security-read-plan.md) | Подробный третий срез F-REST-READ: общий C5 read-контур, canonical read contract, независимый REST fetch profile по ADR-0007 §4.1, защита ссылок, транзакционная проекция и graph/SQL приёмка |
| [`rest-read-api-stage3-completion-plan.md`](rest-read-api-stage3-completion-plan.md) | План завершения этапа 3: конечное окно страниц, C5/admin parity, реальные роли и UI/service grant policy, численные SQL/graph budgets, strict bootstrap и окончательная приёмка |
| [`rest-read-api-stage3-review-20261005.md`](rest-read-api-stage3-review-20261005.md) | Контрольное ревью перед HTTP: REST/C5/API/surface 40/40, открытый полный build gate З5 и условия stateless интеграции |
| [`rest-read-api-stage4-http-plan.md`](rest-read-api-stage4-http-plan.md) | Подробный завершающий функциональный этап read-среза: optional web artifact, gateway/parsing, Basic/TLS, request RLS, JSON/Problem Details/OpenAPI, реальные HTTP пилоты и итоговая приёмка |
| [`rest-read-api-stage4-fixes-40-43-20261005.md`](rest-read-api-stage4-fixes-40-43-20261005.md) | Исправления ревью 4.0–4.3: transaction proxy, изоляция filters/advice, MODULE_API, context path/Accept; identity/RLS integration и остаточные общие gates |
| [`rest-read-api-stage4-44-evidence-20261005.md`](rest-read-api-stage4-44-evidence-20261005.md) | 4.4: единая матрица HTTP-ошибок, authentication/C5/framework faults, безопасная сериализация Problem, trace/log и актуальные gates |
| [`rest-read-api-stage4-45-evidence-20261005.md`](rest-read-api-stage4-45-evidence-20261005.md) | 4.5: deterministic OpenAPI из gateway facts, reviewed wire/Problem snapshots, v1 drift gate и version/grant isolation |
| [rest-read-api-stage4-46-evidence-20261005.md](rest-read-api-stage4-46-evidence-20261005.md) | 4.6: реальные HTTP/H2 pilots, cost/graph/window и session isolation |
| [rest-read-api-stage4-47-evidence-20261006.md](rest-read-api-stage4-47-evidence-20261006.md) | 4.7: закрытые owner/app/starter/strict/bootstrap gates, стабильные source/JAR receipts и внешний rollout checklist |
| [`rest-read-api-stage3-completion-evidence.md`](rest-read-api-stage3-completion-evidence.md) | Фактические результаты пакетов завершения: inputs/JAR parity, SQL trace, UI LIST/REST baseline, окно страниц, C5/admin parity, реальные роли и UI/service grant policy, открытые build gates; З0–З4 выполнены |
| [`security-channel-matrix.md`](security-channel-matrix.md) | C1: карта каналов доступа, владельцев enforcement и допустимых privileged bypass |
| [`decisions/ADR-0016-rest-resource-field-access.md`](decisions/ADR-0016-rest-resource-field-access.md) | REST: API + entity read для опубликованных полей без отдельных attribute grants; ссылки и RLS сохраняются |
| [`decisions/ADR-0017-c5-single-operation-authority.md`](decisions/ADR-0017-c5-single-operation-authority.md) | Принято: C5 — единый хозяин решения по операциям, RLS остаётся за строки, `'*'` сохраняется как семантика RLS |
| [`decisions/ADR-0019-owner-named-row-operation.md`](decisions/ADR-0019-owner-named-row-operation.md) | Принято: именованная операция владельца на строке (`ACTION:ATTRIBUTE_VALUE:RENAME`) и перевод создания/переименования значения атрибута на canonical path |
| [`decisions/ADR-0001-platform-roadmap-stages.md`](decisions/ADR-0001-platform-roadmap-stages.md) | Принятое разделение Engineering Baseline, RLS enforcement и физической модульности |
| [`decisions/ADR-0002-lifecycle-event-semantics.md`](decisions/ADR-0002-lifecycle-event-semantics.md) | Семантика Saving/Saved/Changed/Deleting/Deleted и граница транзакции |
| [`decisions/ADR-0003-developer-experience-and-default-aggregate-save.md`](decisions/ADR-0003-developer-experience-and-default-aggregate-save.md) | Постулат AppDev-first, metadata-driven default save и custom override policy |
| [`decisions/ADR-0004-semantic-entity-archetypes.md`](decisions/ADR-0004-semantic-entity-archetypes.md) | Семантические типы сущностей, стандартные базовые классы, numbering policies и независимые owned sections |
| [`decisions/ADR-0005-entity-lifecycle-and-application-behavior.md`](decisions/ADR-0005-entity-lifecycle-and-application-behavior.md) | Единая точка `EntityLifecycle<T>` для прикладных callbacks, границы operations/queries/UI и feature-package convention |
| [`decisions/ADR-0006-fetchplan-and-instance-name.md`](decisions/ADR-0006-fetchplan-and-instance-name.md) | Сценарные FetchPlan и единый InstanceName: границы API/SPI/internal, `ManagedEntityCatalog` как единственный источник сущностей |
| [`decisions/ADR-0007-canonical-data-access-path.md`](decisions/ADR-0007-canonical-data-access-path.md) | C4: canonical `EntityDataAccess`, таксономия экспозиции типов, судьба generic CRUD-баз, search semantics, telemetry policy и compatibility milestones |
| [`decisions/ADR-0008-interned-entities.md`](decisions/ADR-0008-interned-entities.md) | Интернированные сущности: натуральный ключ как capability, поведенческий маркер `InternedEntity`, один механизм `NaturalKeyCreateSupport` и граница с обычным справочником |
| [`decisions/ADR-0009-deep-links.md`](decisions/ADR-0009-deep-links.md) | E2: адрес формы как контракт — host и синхронизация истории, грамматика `/records`/`/lists`, вывод alias и baseline, linkability, матрица результата открытия и границы API |
| [`decisions/ADR-0010-explorer-type-address.md`](decisions/ADR-0010-explorer-type-address.md) | E3.0: адрес типа как published-ключ, отдельная ветка host'а, гейт роли с неразличимым отказом, резерв первых сегментов и безадресность типа без ключа |
| [`decisions/ADR-0011-fact-origin-and-symbol.md`](decisions/ADR-0011-fact-origin-and-symbol.md) | E3.1: две оси источника факта (слой и происхождение) и Java-символ как часть факта, пять состояний lifecycle без делегатов, владелец причины адресуемости и что показывает карточка Explorer |
| [`decisions/ADR-0012-aspect-facts.md`](decisions/ADR-0012-aspect-facts.md) | E3.2.0: владелец факта как единственный источник аспекта, два вида факта в аспекте и семь секций карточки, запрет значений грантов и решений по записи, порядок строк как работа сборщика и границы вкладок/якорей |
| [`decisions/ADR-0013-information-register-v1.md`](decisions/ADR-0013-information-register-v1.md) | Информационный регистр v1 (пилот `ЦеныНоменклатуры`): декларация, стартовая валидация, запись и уточнение Р4 о размещении в `org.ipro.register` |
| [`decisions/ADR-0014-report-read-authorization.md`](decisions/ADR-0014-report-read-authorization.md) | Принятый C5-допуск report read, ограниченная проекция и постоянная видимость версий регистра |
| [`decisions/ADR-0015-information-register-recorder.md`](decisions/ADR-0015-information-register-recorder.md) | Полиморфный регистратор информации, независимые права и правило победителя по порядку записи при равном периоде |
| [`decisions/ADR-0021-register-key-change-command.md`](decisions/ADR-0021-register-key-change-command.md) | Отдельная opt-in смена ключа строки, C5/ENTITY/RLS, Hibernate snapshot guard, неизменность регистратора и аудит |
| [`decisions/ADR-0023-register-key-write.md`](decisions/ADR-0023-register-key-write.md) | Запись регистра по ключу (Р11): `set`/upsert/`delete`, физический UNIQUE как авторитет гонки отсутствующего ключа, классификация конфликтов по SQLState и bounded retry на границе транзакции |
| [`decisions/ADR-0025-row-lifecycle-on-recorder-set.md`](decisions/ADR-0025-row-lifecycle-on-recorder-set.md) | Принято: lifecycle строки регистра на пути набора владельца — `beforeSave`/`onSave`/`beforeDelete` вызываются ровно один раз на затронутую строку, после присвоения порядка записи и до Bean Validation; вето откатывает операцию владельца целиком; `beforeUpdate` и `beforeAggregateSave` на этом пути не вызываются; право записи владельца хуку строки недоступно |
| [`e2-deep-link-host-spike.md`](e2-deep-link-host-spike.md) | Измеренный результат E2.0: route templates на текущей оболочке, поведение Back/Forward при `pushState`, сохранение `Workspace` и границы проверенного |
| [`actions-guide.md`](actions-guide.md) | Руководство прикладного разработчика по декларативным действиям форм (E1): два примера, требования, suppression, проверки и антипримеры |
| [`deep-links-guide.md`](deep-links-guide.md) | Руководство прикладного разработчика по глубоким ссылкам (E2): когда адрес появляется сам, как объявить ключ и прежние адреса, как получить ссылку программно и что из этого хрупко |
| [`status/current-baseline.md`](status/current-baseline.md) | Проверяемое состояние сборки и этапов |
| [`status/wip-inventory.md`](status/wip-inventory.md) | Состав незавершённого save/events среза |
| [`build/local-dependency-workspace.md`](build/local-dependency-workspace.md) | Воспроизводимая сборка соседних fork/SNAPSHOT-проектов |
| [`../../platform-build/README.md`](../../platform-build/README.md) | Maven-агрегатор платформы: общий install в стандартный .m2 и подключение в IDEA |
| [`build/local-configuration.md`](build/local-configuration.md) | Простая локальная конфигурация подключений вне Git и JAR |
| [`discussions/README.md`](discussions/README.md) | Граница между обсуждениями и принятыми решениями |

## Правила

1. Roadmap отвечает на вопрос «что и в каком порядке делаем».
   Режим и объём существенной задачи фиксируются по
   [политике разработки](development-delivery-policy.md); пилот имеет собственный
   критерий готовности к пробе, завершение механизма — согласованный DoD.
2. ADR отвечает на вопрос «какое решение принято и почему».
3. Status содержит только проверяемые факты текущего checkout.
4. Discussion не является разрешением на изменение кода и не переопределяет ADR/roadmap.
5. Закрытые вопросы удаляются из roadmap; история решения остаётся в ADR или Git.
6. Архитектурный этап считается завершённым только после выполнения его Definition of Done.
7. Изменение стандартного платформенного сценария проходит AppDev-first gate:
   инфраструктурная сложность не переносится в обязательный прикладной boilerplate.

## Проверка baseline

Проверка только приложения при уже установленных локальных зависимостях:

```text
mvn verify
```

Полная локальная проверка sibling-исходников и приложения через Maven из IntelliJ IDEA:

```powershell
.\scripts\bootstrap-local-dependencies.ps1
```

Maven Wrapper отложен по решению владельца. Следующий инфраструктурный gate — CI на чистом checkout с versioned/released внутренними зависимостями.
