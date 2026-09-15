# D1. Карта зависимостей и публичной поверхности платформы

**Этап:** D1 (roadmap §7). **Статус:** выполнен на checkout `main`, зафиксирован в baseline `3793165` / тег `c4.8-d1-baseline` (2026-09-15).
**Место:** D — этап физической платформизации; D1 — карта и правила, D2 — первый extraction slice.

D1 не рефакторит платформу и ничего не переносит. Его единственный продукт — карта,
на которую можно опираться при физическом разрезе: **кто чем владеет, что уже
пересекло границу, какие направления разрешены и какие строковые связи ещё держат
платформу за приложение.** Два из четырёх пунктов D1 сделаны исполняемыми (тестами),
а не абзацами: `PlatformDependencyDirectionTest`, `PlatformStringDependencyTest`
и существующий `PlatformArchitectureTest`.

Итог этапа: **граница не стала чище, но стала измеримой** — и на этом замере
обнаружены три факта, которых не было ни в roadmap, ни в ADR-0007 (разделы 3 и 7).

---

## 1. Что измерено и как воспроизвести

| Что | Число |
|---|---|
| Прикладной код (`org.ip`, src/main) | 161 файл |
| Платформенный код в репозитории (`org.ipro`, src/main) | 469 файлов |
| Платформенные артефакты **вне** репозитория (5 штук) | 69 типов |
| Типы платформы, которые называет приложение | **194** |
| Из них — аннотации (`*.annotation.*`) | 11 |
| Из них — SPI-контракты, реализуемые приложением | 18 |
| Из них — уже лежат во внешних артефактах | 8 |
| Строковые связи платформы на `org.ip` | 13 файлов (реестр ниже) |

Воспроизведение (из корня репозитория):

```bash
# типы платформы, которые называет приложение
grep -rhoE '^import org\.ipro\.[A-Za-z0-9_.]+;' src/main/java/org/ip --include=*.java \
  | sed 's/import //;s/;//' | sort -u

# строковые связи платформы на прикладной пакет
grep -rn ':org\.ip' src/main/java/org/ipro --include=*.java | grep -v 'org\.ipro'
```

---

## 2. Главная находка: root-пакет `org.ipro` имеет трёх владельцев

`org.ipro` — не «платформа» и не один артефакт. Под ним сегодня живут три разные
сущности:

| Владелец | Что | Как задаётся |
|---|---|---|
| Репозиторий (this checkout) | 18 пакетов, 469 файлов | `src/main/java/org/ipro` |
| Внешние артефакты `org.ipro:filtergrid-*` | `filtergrid.jpa`, `filtergrid.grouping`, `filtergrid.inmemory`, `filtergrid.projection` | pom, `filtergrid.version` |
| Внешний артефакт `org.ipro.crudui:crudui-core` | `org.ipro.crud` (12 типов) + Vaadin-база CRUD | pom, `crudui.version` |

Все версии — `1.0-SNAPSHOT`. **Платформизация уже частично произошла**, просто раньше
roadmap её не учитывал.

### 2.1. Split package `org.ipro.crud` (находка, блокирующая разрез по пакетам)

Пакет `org.ipro.crud` **разделён между артефактом `crudui-core` и репозиторием**:

```
из crudui-core: AbstractCrudView, AbstractEntityForm, CrudService, CrudToolBar,
                EditMode, FilterLayout, FormBuilder, IdentifiableEntity, ItemTable
в репозитории:  BaseEntity, BaseService, EntityCopyService, GenericOwnedSectionService,
                InternedEntity, LookupService, MetadataDrivenAggregateSaveService, …
```

Совпадений по именам классов нет (конфликта классов нет), но:

* приложение называет **8 типов, которые уже лежат во внешних артефактах**:
  `org.ipro.crud.IdentifiableEntity` (причём и импортирует, и **реализует**),
  `AbstractCrudView`, `AbstractEntityForm`, `CrudService`, `EditMode`, `FormBuilder`,
  `org.ipro.filtergrid.jpa.JpaFilterGrid`, `org.ipro.filtergrid.inmemory.InMemoryFilterGrid`;
* `IdentifiableEntity` — это контракт/SPI, уже вынесенный, а `BaseEntity`/`BaseService`
  — ещё нет. То есть **граница контракта разрезает пакет, а не совпадает с ним.**

**Следствие для D2:** пакет (`org.ipro.crud`) перестал быть единицей владения, поэтому
резать платформу «по пакетам» нельзя — первый срез должен идти **по типу/роли**, а
целевая проверка D — по артефакту. Правило ArchUnit, привязанное к `org.ipro.crud..`,
сегодня не различает репозиторий и опубликованный артефакт.

---

## 3. Направления зависимостей

### 3.1. Внешняя граница (уже была)

`PlatformArchitectureTest`: платформа не зависит от приложения (bytecode), и
metadata-ядро не зависит от `fetch` (ADR-0006).

### 3.2. Внутренние направления — зафиксированы (новое)

`PlatformDependencyDirectionTest` — 8 правил «нижний слой не знает верхнего», зелёные
на этом checkout:

| Нижний слой | Не зависит от | Почему |
|---|---|---|
| `data` | `form`, `reportstudio`, `ureport`, `jr` | data access — фундамент C4 |
| `metadata` | `data`, `reportstudio` | metadata описывает данные, а не читает |
| `fetch` | `form`, `reportstudio` | FetchPlan — контракт загрузки |
| `rls` | `form` | политика применяется к формам, но не строится из них |
| `lifecycle` | `form` | ownership — предметная механика |
| `events` | `data` | контракты, на которые подписываются |
| `search` | `reportstudio`, `ureport` | read-механика |
| `crud` | `reportstudio`, `ureport` | отчёты — optional add-on (D3) |

### 3.3. Исправлено в D1

**`data -> form` был реальной утечкой.** Порт `GroupingValuesProviderFactory` был объявлен
в `org.ipro.form.grouping` (у потребителя), а его JPA-реализация — в
`org.ipro.data.grouping`. Из-за этого `DataAccessAutoConfiguration` (слой данных)
импортировал UI-интерфейс. Порт перенесён к реализации: `org.ipro.data.grouping`.
Направление стало `form -> data` и закреплено правилом.

**`@EnableJpaRepositories({"org.ip", ...})` в `RlsAutoConfiguration`** — платформа
объявляла состав репозиториев приложения. Теперь приложение объявляет свои репозитории
само (`@EnableJpaRepositories({"org.ip"})` в `Application`), платформа перечисляет только
платформенные пакеты.

### 3.4. Исключения (осознанные, с причиной и этапом снятия)

| Нарушение | Масштаб | Причина оставить до D2 | Снятие |
|---|---|---|---|
| `metadata -> form` | 5 файлов | `metadata.explorer` (`EntitySummary*`, `SubsystemSummaryAssembler`, `EntityExplorerAutoConfiguration`) — read-model форм; `MetadataAutoConfiguration` импортирует `MetadataDrivenItemFormSaveAdapter`. Это переносится **вместе** с form-срезом, а не до него | D2 |
| `rls -> reportstudio`, `rls -> ureport` | 1 файл | `RlsAutoConfiguration#@EnableJpaRepositories` перечисляет репозиторные пакеты **шести чужих** подсистем плюс свой (см. 3.5) | D3 |

### 3.5. Центральный хаб регистрации репозиториев (риск для D2/D3)

`@EnableJpaRepositories` платформы объявлен **ровно один раз** — в
`RlsAutoConfiguration`, и перечисляет семь пакетов: свой `org.ipro.rls` и чужие
`org.ipro.reportstudio`, `org.ipro.numbering`, `org.ipro.settings`, `org.ipro.ureport`,
`org.ipro.jr`, `org.ipro.telemetry.repository`. Пять других файлов
(`JrxmlTemplateRepository`, `NumberingAutoConfiguration`, `SettingsAutoConfiguration`,
`UreportTemplateRepository`, `UreportAutoConfiguration`, `OperationLogRepository`)
**документируют** этот факт комментарием «репозитории добавлены в
`RlsAutoConfiguration#@EnableJpaRepositories`».

Это скрытая связность: RLS-авто-конфигурация обязана знать каждый чужой подсистемный
пакет, а порядок — не случаен (`@AutoConfigureBefore(NumberingAutoConfiguration)`).
При выносе модулей (D3) каждый модуль должен объявлять свои репозитории сам; ошибка
здесь не падает на старте, а **молча теряет репозитории** — это надо закрыть
fail-fast проверкой в D2, а не комментарием.

Симметрично `@EntityScan` приложения перечисляет платформенные entity-пакеты
(`org.ipro.telemetry.model`, `org.ipro.rls`, `reportstudio.dom`, `numbering`, `settings`,
`ureport.dom`, `jr.dom`). Roadmap D2 требует, чтобы extraction **не увеличивал** число
обязательных регистраций в приложении — сегодня добавление платформенного модуля
требует правки этого списка; starter D4 обязан это снять.

---

## 4. Классификация `org.ipro`: API / SPI / internal

Критерий — **роль типа в компиляции приложения**, а не пакет:

* **API** — приложение называет тип, но не наследует и не реализует его. Если он
  исчезнет, приложение не соберётся.
* **SPI** — приложение реализует/расширяет интерфейс (или абстрактный класс) платформы.
  Платформа вызывает приложение. Ломать без плана нельзя.
* **internal** — приложение не должно называть вообще; появляется в конфигурации
  через auto-configuration.

Замер по пакетам (число типов, которые называет приложение):

| Пакет | Типов названо | Роль | Решение D1 |
|---|---|---|---|
| `metadata` | 30 | **API** (11 аннотаций + value types) + SPI (`HasDisplayName`) | ядро первого среза D2 |
| `form` | 36 | SPI (`spi.*`, `builder.ItemFormCustomization`, `TableSectionCustomization`, `ListCommand`, `FormSaveHandler`, `Dirtyable`, `Savable`) + обилие reach-through в `builtin`/`coordinator` | SPI — в первый срез; `builtin`/`coordinator` — internal, приложение должно перестать их называть |
| `reportstudio` | 41 | **internal, оставшийся публичным**: `ReportExecutionService`, `ReportTemplateService`, `ReportQueryGuard`, редакторы | к снятию при D3 (optional add-on); сейчас — крупнейшая утечка |
| `crud` | 13 | **split package**: API/SPI частично уже в `crudui-core` | резать по типу, не по пакету (§2.1) |
| `rls` | 12 | **API + SPI** (`RlsDimension`, `RlsDimensionValue`, `RlsCurrentUser`, `RlsRoleResolver`) | контракты — в первый срез |
| `telemetry` | 9 | API (аннотации/интерфейсы) + internal | только контракты |
| `numbering` | 7 | **API** (`@Numbered`) | в первый срез |
| `search` | 6 | **SPI** (`GlobalSearchProvider`) + API | в первый срез |
| `settings` | 6 | API + SPI | в первый срез |
| `data` | 8 | **API** (канонический data access, C4) | в первый срез |
| `lifecycle` | 5 | **SPI** (`EntityLifecycle`) | в первый срез |
| `fetch` | 2 | API (ключ плана) | в первый срез |
| `jr` | 2 | SPI (`JpqlDatasetRunner`) | позже: отчётный add-on |
| `ureport` | 7 | internal, оставшийся публичным | к снятию при D3 |
| `security` | 1 | внутренний мост | internal |
| `events` | 0 | **internal** | — |
| `filter` | 0 | **internal** | — |

**Вывод для D2.** Публичная поверхность сегодня — **194 типа**, из которых контрактами
являются 11 аннотаций + 18 SPI + value types; остальное (порядка 165) — reach-through
в реализацию: `reportstudio.*` сервисы, `form.builtin.*`, `form.coordinator.*`,
`crud.*` Vaadin-база. Кандидаты roadmap («metadata value types/annotations»,
«event contracts», «form/action extension interfaces», «нейтральные identifiers/results»)
подтверждаются замером, но **первый срез обязан не переносить 165 типов** — иначе
публичная поверхность модуля будет скопирована как есть и разрез потеряет смысл.
Целевой бюджет среза: **аннотации + value types + SPI + canonical data access contract**,
и явное решение по каждому типу, который приложение называет зря.

---

## 5. Строковые связи платформы на приложение

`PlatformStringDependencyTest` — исполняемый реестр (§3.3 плана reportstudio-reverse-deps,
где это было только «диагностическим grep»). Реестр **shrink-only**: новый литерал
ломает сборку, снятый обязан исчезнуть из списка.

Критерий двусторонний и **по литералам, а не по именам файлов**: сверяется
`файл → точный набор литералов`, поэтому второй литерал в уже разрешённом файле ломает
сборку так же, как новый файл. Сканируется **код без комментариев**: упоминание
прикладного пакета в javadoc (например, в `RlsAutoConfiguration`, где теперь написано,
что репозитории приложения объявляет само приложение) — документация, а не зависимость, и в
реестр не попадает.

**Снято в D1:**

1. `@EnableJpaRepositories({"org.ip", ...})` — платформа объявляла репозитории приложения (§3.3);
2. `ReportQueryEditor` — пример-плейсхолдер `org.ip.model.DocumentStatus` заменён на нейтральный.

**Осталось (13 файлов, реестр с причинами в тесте):**

| Семейство | Файлов | Когда уходит |
|---|---|---|
| default `platform.subsystem-scan-package:org.ip` | 7 | D3 (авто-конфигурации метаданных) |
| default `rls.dimension-scan-package:org.ip` | 2 | D3 |
| default `settings.scan-package:org.ip.settings` | 3 | D3 |
| pointcut `org.ip.service..*` (`ExecutionTimeAspect`) | 1 | D3, переход на маркер/@Measured |

В этих файлах закреплено ровно **16 литералов**: сравнение идёт по ним, а не по составу
файлов.

Ни один из этих литералов не решается на D1 без правки конфигурации приложения, поэтому
они **зарегистрированы, а не «почти исправлены»**. DoD этапа D требует, чтобы
production-код платформы не содержал ссылок на прикладной пакет **включая строковые
scan/pointcut contracts** — тест делает этот критерий проверяемым и не даёт списку
разрастись.

---

## 6. Compatibility policy (D1, п.4)

На момент D1 у платформы нет версии, отличной от приложения: всё — `1.0-SNAPSHOT`,
собирается и живёт в одном репозитории, приложение подключает платформу исходниками.

Политика до первого extraction slice (D2):

1. **Пока платформа и приложение в одном артефакте — совместимость не обещается.**
   Breaking changes внутри платформы допустимы, но обязаны быть покрыты тестами
   (behavioral + architecture), а не только описаны.
2. **С момента D2** платформенные артефакты получают версию, независимую от приложения
   (`filtergrid-*`/`crudui-core` уже показывают этот механизм через `*.version` в pom).
3. **Публичная поверхность фиксируется явно.** Добавление типа в API/SPI среза —
   отдельное решение; случайное попадание реализации в публичную поверхность считается
   дефектом, а не мелочью (сегодня так попали 165 типов).
4. **Удаление API/SPI — только через ADR** с указанием замены и этапа; удаление
   internal — свободно.
5. **`org.ip` не может появляться в платформе** ни байткодом (`PlatformArchitectureTest`),
   ни строкой (`PlatformStringDependencyTest`). Это единственное жёсткое правило,
   действующее уже сейчас.
6. **Направления из §3 не могут нарушаться новым кодом** — правила исполняемые;
   исключение из §3.4 можно только сокращать.

---

## 7. Выход D1 → вход D2

Готово к использованию в D2:

* список направлений, которые нельзя нарушать (8 правил);
* shrink-only реестр строковых связок с планом снятия по этапам;
* классификация пакетов и целевой бюджет первого среза (контракты, не 165 типов);
* зафиксированный список известных исключений с этапом снятия.

Что D2 обязан учесть как риск:

* **резать по типу, а не по пакету** — `org.ipro.crud` уже split package (§2.1);
* **репозитории больше не регистрируются централизованно** — иначе модуль теряет
  репозитории молча (§3.5);
* **extraction не должен добавлять обязательных регистраций в приложении**
  (`@EntityScan`/`@EnableJpaRepositories` сегодня правятся вручную при добавлении модуля).

## 8. Что D1 сознательно не сделал

* Не переносил ничего физически — это D2.
* Не расширял `EntityDataAccess` и не удалял `BaseService` (граница C-этапа, см. C4.8).
* Не правил `metadata -> form` (5 файлов) и хаб `@EnableJpaRepositories`: обе правки
  осмысленны только вместе с соответствующим срезом (§3.4, §3.5).
* Не строил полный список «194 типа → решение по каждому»: классификация по ролям и
  бюджет среза зафиксированы, пофайловое решение — задача D2 (иначе это карта ради карты).
