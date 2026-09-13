# C3: FetchPlan + InstanceName pilot — план работ

Статус: `COMPLETED` (C3.0–C3.7, 2026-09-13)
Родительский этап: [`JMIX_GitVaa_Roadmap_v2.md`, C3](../../JMIX_GitVaa_Roadmap_v2.md#c3-fetchplan--instancename-pilot)
Связанные нарушения: `ADX-07`, `ADX-09`; подготовка к закрытию `ADX-08` в C3–C4.

## 1. Цель

C3 должен доказать на ограниченном наборе сущностей, что платформа может:

- загружать сценарно необходимый граф данных без знания Hibernate session в UI;
- обнаруживать ошибочные пути загрузки при старте;
- давать единое отображаемое имя entity для lookup, search и audit;
- не смешивать FetchPlan, RLS и persistence в одну новую универсальную абстракцию;
- заменить существующий ручной fetch-код, а не создать параллельный путь рядом с ним.

Пилот охватывает:

- `ReceivingDocument`;
- `PrdSpec`;
- `PrdSpecMtr`;
- `PrdSpecOper`;
- lookup-сущности, необходимые для их instance name и отображения.

## 2. Решение о границах этапа

Полный backlog упрощения платформы не является шлюзом перед C3. До основного пилота
выполняются только изменения, которые непосредственно предотвращают рост сложности в
новом коде C3.

В критический путь C3 входят:

1. устранение доказанного повторного RLS write/delete enforcement в
   `AbstractBaseService`;
2. отдельная autoconfiguration для FetchPlan и InstanceName;
3. использование существующего `ManagedEntityCatalog` вместо нового classpath scan;
4. общий контракт зависимостей instance name и FetchPlan;
5. миграция ручного fetch-кода пилотных форм;
6. поведенческие тесты и измерение запросов.

Не блокируют C3 и остаются отдельным backlog:

- консолидация трёх UI-вариантов Report Studio;
- удаление `crudui-core` и legacy Workshop;
- завершение миграции старого/нового form-save API;
- отвязка `ExecutionTimeAspect` от `org.ip.service`;
- полная ревизия public surface платформы;
- общее сведение архитектурной документации.

## 3. Архитектурные ограничения

### 3.1. Одна read orchestration boundary, разные обязанности

FetchPlan и RLS подключаются к существующему read pipeline, но остаются разными
компонентами:

- `RlsPolicyEnforcer` определяет и применяет ограничения доступа;
- FetchPlan resolver определяет граф загрузки;
- repository/query adapter выполняет запрос;
- InstanceName resolver формирует отображаемое имя уже загруженной entity.

FetchPlan не становится частью RLS policy, а RLS не вычисляет fetch paths. В C3 не
создаётся третья параллельная точка входа в persistence и не строится фасад C4 раньше
времени.

### 3.2. Использование общего каталога сущностей

Все компоненты C3 получают набор управляемых сущностей из `ManagedEntityCatalog`.
Собственный classpath scanner для FetchPlan или InstanceName запрещён.

### 3.3. API, SPI и internal разделяются сразу

Публичными остаются только контракты, необходимые прикладному коду для декларации или
явного расширения поведения. Registry implementation, кэши, сборка defaults и
startup-validation являются internal implementation.

Новый тип не становится `public` только ради удобства wiring или тестирования.

### 3.4. Metadata не дублируется

FetchPlan использует уже существующую per-entity metadata как исходные факты.
Отдельная декларация добавляется только для сценарного override, который нельзя
однозначно вывести из Java type, JPA, Bean Validation и существующей UI metadata.

Не создаётся четвёртое независимое семейство описаний сущности, не связанное с
существующими RLS-, section- и field-descriptors.

### 3.5. Каждый новый механизм заменяет старый код

C3 не считается завершённым, пока пилотные формы продолжают использовать ручные
`EntityGraph` hints, fetch-depth constants, reload detached entity или обработку
`LazyInitializationException` как штатный путь.

## 4. Последовательность работ

### C3.0. Расчистка периметра

#### C3.0.1. Устранить повторный RLS write/delete enforcement

Перед изменением добавить или уточнить characterization tests для записи:

- через application service;
- напрямую через Spring Data repository;
- напрямую через `EntityManager`;
- для insert, update и delete;
- для разрешённого и запрещённого действия;
- для owned section через aggregate boundary.

После этого удалить явные `checkRlsWrite`/`checkRlsDelete` из того сервисного пути, где
тот же `repository.save/delete` уже перехватывается repository aspect.

Сохраняются:

- repository enforcement aspect;
- Hibernate flush-time listener;
- `RlsPolicyEnforcer` как общий policy owner;
- существующий read enforcement.

Критерий завершения: сервисный путь не выполняет одну policy-проверку дважды, а прямые
repository- и `EntityManager`-каналы остаются защищёнными.

#### C3.0.2. Создать отдельную autoconfiguration

Новые бины C3 не добавляются в `MetadataAutoConfiguration`. Создаётся отдельная
конфигурационная граница FetchPlan/InstanceName, даже если в первом срезе она содержит
только несколько bean definitions.

Проверки контекста должны доказать:

- metadata core поднимается без C3-компонентов;
- C3-компоненты подключаются при наличии необходимых контрактов;
- отсутствие optional UI/application beans не ломает startup;
- конфигурация не импортирует и не сканирует `org.ip`.

#### C3.0.3. Подключить `ManagedEntityCatalog`

FetchPlan registry и InstanceName registry/resolver используют единый каталог
управляемых сущностей. Для одного набора сущностей не выполняются независимые
classpath scans.

### C3.1. Общий контракт зависимостей

До независимой реализации двух подсистем определяется небольшой общий контракт:

- instance name может объявить attribute paths, необходимые для вычисления имени;
- FetchPlan resolver включает эти зависимости в соответствующие `LOOKUP` и `DETAIL`
  планы;
- зависимости валидируются при старте;
- вычисление instance name после закрытия persistence context не инициирует lazy load.

Конкретные имена Java-типов выбираются при реализации. Контракт не должен превращаться
в общий data-access facade.

### C3.2. Реализовать InstanceName resolver

Для пилотных сущностей вводится один источник отображаемого имени.

Требования:

- lookup, global search и audit используют одинаковое представление;
- явное описание имеет понятный приоритет над metadata-derived default;
- формат не зависит от `toString()`;
- resolver не выполняет скрытых repository-запросов;
- необходимые association paths объявлены через контракт C3.1;
- непилотные сущности сохраняют текущее поведение до отдельной миграции.

На первом этапе допустим compatibility adapter для `HasDisplayName`, но он не является
новым рекомендуемым API и не применяется к уже мигрированным пилотным сущностям.

### C3.3. Реализовать FetchPlan registry

Ключ плана:

```text
(entityClass, scenario)
```

Минимальный набор сценариев:

- `LIST`;
- `DETAIL`;
- `LOOKUP`;
- `ROW`.

Правила разрешения:

1. стандартный план выводится из эффективной metadata;
2. зависимости instance name добавляются для нужного сценария;
3. явный plan используется только как override для отличающегося сценария;
4. invalid path является startup error, а не runtime fallback;
5. планы разных сценариев и сущностей кэшируются и не смешиваются.

Вывод плана не должен рекурсивно загружать весь `@Lookup`-граф. Необходимы:

- ограничение глубины;
- защита от циклов;
- сценарные правила включения association;
- детерминированный порядок путей;
- диагностируемая причина включения каждого пути;
- измерение размера полученного графа.

### C3.4. Подключить к существующей read-границе

Рекомендуемый pipeline:

1. вызывающий код передаёт entity type, сценарий и параметры загрузки;
2. RLS boundary подготавливает обязательные read restrictions;
3. FetchPlan resolver определяет граф;
4. существующий repository/query adapter выполняет запрос с RLS и планом;
5. UI получает подготовленную entity и не управляет Hibernate session.

Интеграция выполняется в существующих service/lookup adapters. FetchPlan logic не
встраивается внутрь `RlsPolicyEnforcer`, а `AbstractBaseService` не превращается в
новый универсальный data-access object.

### C3.5. Выполнить пилотную миграцию

#### `ReceivingDocument`

Проверить и подключить:

- `LIST`, `DETAIL` и `LOOKUP` планы;
- instance name документа и используемых lookup-сущностей;
- read restrictions при каждом сценарии;
- отображение после закрытия persistence context.

#### `PrdSpec`, `PrdSpecMtr`, `PrdSpecOper`

Проверить и подключить:

- root detail plan;
- `ROW` plans для owned sections;
- зависимости instance name;
- отсутствие row query для скрытой секции;
- отсутствие автономного persistence path для section row.

#### `PrdSpecMtrFormCustomization`

Это обязательный приёмочный сценарий `ADX-07`. Из customization удаляются:

- `LOOKUP_FETCH_DEPTH`;
- ручной расчёт association paths;
- ручной reload выбранных lookup entities;
- знание о Hibernate session;
- обработка lazy proxy как штатный механизм загрузки.

Пока этот класс использует старый fetch-путь, C3 не закрыт.

### C3.6. Приёмочные тесты и измерения

Обязательные проверки:

- invalid path обнаруживается при регистрации/startup;
- detached render не вызывает lazy loading;
- lookup/value-change не требует ручного reload;
- скрытая секция не вызывает row query;
- планы `LIST`, `DETAIL`, `LOOKUP` и `ROW` не смешиваются;
- RLS применяется при любом плане;
- instance name одинаков для lookup, search и audit;
- instance name не вызывает скрытый запрос;
- query count и объём загруженного графа измерены до и после миграции;
- сценарный default не загружает association, не нужные этому сценарию.

Измерения фиксируются в тестах или в воспроизводимом benchmark/diagnostic сценарии, а
не только в текстовом отчёте.

### Опциональная последующая работа: lifecycle diff spike

Разрешён time-boxed spike продолжительностью не более двух рабочих дней: проверить,
можно ли использовать Hibernate event state, аналогично
`RlsWriteEnforcementListener`/`FieldAuditListener`, для надёжного old/new diff в
`beforeUpdate`.

Эта работа находится вне DoD C3 и не блокирует закрытие этапа. Spike:

- не меняет production lifecycle contract;
- не приводит к частичной реализации;
- завершается тестовым прототипом и зафиксированным выводом.

Если требуемый момент veto несовместим с flush-time event, работа выносится в отдельный
этап.

### Документация (сквозная работа)

Документация обновляется в том же изменении, что и поведение:

- roadmap отражает фактически выполненные срезы;
- `current-baseline.md` содержит только проверяемое состояние текущего checkout;
- `appdev-first-audit.md` обновляет статус `ADX-07`/`ADX-09` только после миграции
  production-кода и прохождения приёмки;
- новые архитектурные решения, требующие объяснения альтернатив, оформляются ADR.

## 5. Рекомендуемые вертикальные срезы

1. RLS characterization tests и удаление повторной сервисной проверки.
2. Отдельная C3 autoconfiguration и wiring через `ManagedEntityCatalog`.
3. Контракт instance-name dependencies и его startup validation.
4. InstanceName pilot на одной lookup-сущности и `ReceivingDocument`.
5. FetchPlan `LOOKUP`/`DETAIL` pilot для `ReceivingDocument`.
6. `PrdSpec` и `ROW` plans для owned sections.
7. Удаление ручного fetch-кода из `PrdSpecMtrFormCustomization`.
8. Полная приёмка, измерения и синхронизация документации.

Каждый срез должен оставлять сборку рабочей и удалять заменённый пилотный код в том же
изменении. Не допускается сначала распространить новый API на все сущности, а затем
пытаться доказать его работоспособность на пилоте.

## 6. Definition of Done C3

C3 завершён, когда одновременно выполнены все условия:

- пилотные сущности используют сценарные планы из одного registry;
- invalid paths завершают startup с понятной диагностикой;
- UI пилотных форм не содержит session/fetch knowledge;
- `PrdSpecMtrFormCustomization` больше не содержит ручного fetch/reload механизма;
- hidden section не вызывает row query;
- detached render не вызывает lazy loading;
- RLS сохраняется для всех пилотных read-сценариев;
- query count и размер графа измерены и не имеют необъяснимой регрессии;
- lookup, search и audit используют единый instance name пилотных сущностей;
- InstanceName resolver не инициирует скрытые запросы;
- C3-компоненты не выполняют новый classpath scan;
- C3-компоненты не добавлены в `MetadataAutoConfiguration`;
- публичные SPI отделены от internal registry/cache implementation;
- старый пилотный код удалён, а не оставлен вторым поддерживаемым путём;
- целевые, архитектурные и согласованные regression gates зелёные;
- roadmap, baseline и audit отражают фактическое состояние.

## 7. Риски и способы контроля

| Риск | Контроль |
|---|---|
| FetchPlan превращается в eager-load всего графа | Сценарные правила, depth/cycle guards, query/graph measurements |
| Instance name обращается к lazy association | Явные dependency paths и detached-render tests |
| RLS и FetchPlan смешиваются в одном сервисе | Разные collaborators в одном read pipeline |
| Появляется новый универсальный registry framework | Только два специализированных компонента поверх `ManagedEntityCatalog` |
| Новый API остаётся рядом со старым | Миграция `PrdSpecMtrFormCustomization` и удаление старого кода входят в DoD |
| C3 незаметно превращается в C4 | В C3 нет generic CRUD и нового обязательного data-access facade |
| Public surface растёт автоматически | Явная классификация API/SPI/internal в каждом срезе |
| Опциональный spike захватывает этап | Жёсткий time-box, отсутствие production-изменений и неблокирующий статус |

## 8. Следующий этап

После успешного пилота C4 может использовать проверенные FetchPlan и InstanceName как
отдельные policies внутри узкого data-access facade. C4 должен заменять пустые
application repository/service по одной вертикали за раз, а не добавлять третий CRUD
путь поверх существующих.
