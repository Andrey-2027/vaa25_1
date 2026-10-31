# U1: evidence вывода UReport (U1.3–U1.4)

- Дата: 2026-10-18.
- Ветка: `main`, HEAD `9b3463d`; дерево содержит незакоммиченные изменения
  параллельного среза (platform-vaadin/explorer и UniDR), они названы отдельно.
- План и инварианты: [`ureport-removal-plan.md`](../ureport-removal-plan.md).
- Решения владельца 2026-10-18: D2 (дизайнер не нужен), D5 (удалить все шаблоны),
  D9 (старый `/ureport/*` не обслуживается и не перенаправляется) —
  [`ADR-0027`](../decisions/ADR-0027-report-composition-without-ureport.md).

## 1. Supply chain (U1.3)

```text
mvn -o -q clean test-compile            EXIT=0
mvn -o -q dependency:tree               297 строк
grep -ci ureport  /tmp/u1-tree.txt      0
grep -i  itext    /tmp/u1-tree.txt      0
```

- `pom.xml` больше не содержит `com.bstek.ureport`, `ureport.version`,
  `ureport3-console` и связанных исключений (`antlr`, `itext`); iText 5 не пришёл
  из другого источника.
- `scripts/local-dependencies.json` не содержит записи `ureport3`; bootstrap-скрипт
  собирает граф без форка. `../UReportFork2` не изменялся.
- `WorkspaceManifestTest` (4 теста), `BootstrapScriptContractTest` (3) и
  `PlatformStarterCompositionTest` (5) зелёные на момент U1.3; последующая правка
  platform-vaadin другим срезом сдвинула fingerprint и снова окрасила
  `WorkspaceManifestTest` — это не U1 (см. §4).

## 2. Реестры и документы (U1.4)

- `platform-public-surface.txt`: роли UReport-типов сняты вместе с runtime (U1.2).
  В U1.4 реестр дополнен типами, которые приложение уже называло на HEAD:
  37 ролей API, 1 SPI (`ReportCatalogContributor`), 2 legacy-internal
  (`RestReadOperation`, `RestResourceCatalog` в `PermissionCatalogService`).
  `EntityRef`, `QueryField`, `ReportDataset`, `ReportRow` переведены из
  legacy-internal в API: это носители данных отчётного датасета, и потребитель
  (UniDR) обязан их знать. `EntityDescriptorCatalog` остался legacy-internal —
  роль MODULE_API в `platform-core` не позволяет объявить его API приложения.
- Итоговые замеры (сгенерированы `PlatformPublicSurfaceTest`):

```text
app-source-files=271
platform-tree-files=144
platform-artifact-files=582
named-platform-types=308
api-types=217
spi-types=33
legacy-internal-types=52
legacy-internal-usage-links=180
```

- Рост `legacy-internal-usage-links` 137 → 180 — это записанный, а не разрешённый
  долг: замороженные списки приведены к фактическому употреблению среза UniDR
  (`ReportTemplate`, `ReportParam`, `ReportContext`, `ReportQueryGuard` и др.).
  Забор по-прежнему запрещает дальнейший рост, а снятие — шаг отчётного шва.
- `report-form-seam.txt` больше не описывает UReport-ветку и держит тот же шов
  Report → Form (6 тестов `ReportFormSeamTest` зелёные).
- `d-r-report-addon-inventory.md`, `README.md`, `current-baseline.md`, roadmap и
  сам план описывают состояние после U1.3; исторические документы
  (`UnionReport1.md`, `docs/plans/*.log`) намеренно не переписаны.

## 3. Адресные гейты (зелёные)

```text
BootstrapScriptContractTest                 3/0/0
PlatformStarterCompositionTest              5/0/0
ReportFormSeamTest                          6/0/0
PlatformSubsystemClosureTest                2/0/0
PlatformRlsModuleTest                       5/0/0
PlatformRegisterModuleTest                  5/0/0
PlatformRegisterVaadinModuleTest            7/0/0
PlatformPackageDependencyMatrixTest         3/0/0
PlatformAutoConfigurationRegistryTest       3/0/0
ReportCatalogAssemblyGateTest               2/0/0
ReportCatalogServiceTest                    6/0/0
ReportCatalogItemFactoryTest                3/0/0
ReportEngineCapabilitiesTest                6/0/0
ReportCatalogViewTest                      20/0/0
ReportPrintActionTest                       4/0/0
```

### 3.1. Повторная проверка в финальном дереве (2026-10-18, после сверки реестров)

Полный `test-compile` в этот момент блокирован чужим незавершённым тестом
(`EntitySummaryAssemblerTest` ссылается на `EntitySummary.structure()`, которого
ещё нет). Поэтому затронутые гейт-классы скомпилированы напрямую в
`target/test-classes` и запущены через `surefire:test` — те же тестовые классы и
ресурсы, без lifecycle-шага `test-compile`:

```text
PlatformPublicSurfaceTest           10/0/0
PlatformCoreSurfaceTest               6/0/0
PlatformVaadinSurfaceTest             6/0/0
BootstrapScriptContractTest           3/0/0
PlatformStarterCompositionTest        5/0/0
ReportFormSeamTest                    6/0/0
PlatformSubsystemClosureTest          2/0/0
PlatformRlsModuleTest                 5/0/0
PlatformRegisterModuleTest            5/0/0
PlatformRegisterVaadinModuleTest      7/0/0
PlatformPackageDependencyMatrixTest   3/0/0
PlatformAutoConfigurationRegistryTest 3/0/0
report catalog/print (6 классов)     41/0/0
```

То есть реестры поверхностей и замеры в документе совпадают с кодом, включая
U1-замеры `582` и `180`. Остаётся красным только `WorkspaceManifestTest`:
fingerprint `platform-core` ожидает 153 файла, фактически 154 — соседний срез
добавил production-тип в `platform-core` уже после прогона. Обновлять манифест
под чужую незавершённую правку — отдельное осознанное действие её владельца.

## 4. Полный прогон и чужая краснота

```text
mvn -o clean verify -Dmaven.test.failure.ignore=true
Tests run: 2335, Failures: 39, Errors: 15, Skipped: 0
```

`-Dmaven.test.failure.ignore=true` использован только чтобы получить полный список
падений за один прогон; `BUILD SUCCESS` в этом режиме **не означает** зелёный
verify. 24 набора красные, и ни один из них не относится к U1: UReport-классов в
дереве нет, каталог/печать/JR/UniDR-гейты зелёные (§3).

Причины красного, зафиксированные по отчётам:

1. **Параллельный срез platform-vaadin/explorer** (незакоммиченные правки в момент
   прогона). Добавлен `EntityStructureFactsAssembler` и расширены
   `EntitySummary`/`ExplorerSnapshot`:
   - `PlatformVaadinModuleTest` — 138 → 139 production-типов;
   - `PlatformVaadinSurfaceTest` — та же дельта в реестре ролей;
   - `WorkspaceManifestTest` — fingerprint `platform-core` и `platform-vaadin`;
   - `PlatformApiBaselineTest` — новый класс не найден на classpath (baseline
     проверяется по установленным артефактам, `mvn install` не выполнялся);
   - `EntitySummaryAccessPilotTest`, `EntitySummaryAssemblerTest`,
     `NavigationAdminWiringTest`.
2. **Незакрытый долг C5/RLS** (существовал до U1): `ApplicationBoundaryTest`
   (37 reach-through `jakarta.persistence.EntityManager` в `org.ip.application.*`),
   `RlsAutoConfigurationSmokeTest`, `RlsDeniedEventTest` (ожидается
   `RlsAccessDeniedException`, приходит `C5AccessDeniedException`),
   `RlsIntegrationTest`, `RlsReadGateTest`, `RlsUiGateTest`,
   `RlsServiceWriteBoundaryIT`, `RlsDimensionFactsIT`, `C5GrantAdminParityIT`,
   `NamedOperationGrantAdminIT`, `OperationEffectiveRightsIT`,
   `RestReadRoleMatrixIT`, `UngrantedDefinitionsReportTest`,
   `UserAdministrationSelfElevationIT`, `DeepLinkTwoUserAccessIT`.
3. **Живой дрейф UniDR**: `PlatformPublicSurfaceTest.legacyInternalReferencesAreFrozenPerFile`
   и тестовые overlay-и реестров поверхностей ловят новые файлы `org.ip.unidr.*`,
   которые называют отчётное ядро. Замороженные списки приведены к факту
   (2026-10-18) с явной пометкой «долг, не повышение роли»; overlay-и
   `platform-core-surface.txt` дополнены файлами нового UniDr-теста
   (`CanonicalReadExecutor`, `ReadTelemetry`, `EntityDescriptorCatalog`), а
   `FormCoordinator` — двумя ITS экранов прав. После этого тестовое дерево
   перестало компилироваться из-за незавершённой правки `EntitySummary` в чужом
   срезе — повторный прогон поверхностных гейтов до её завершения невозможен.

### 4.1. Повторный прогон после установки platform-модулей (2026-10-18)

Причина красного `EntityExplorerPilotPanel` → `EntitySummary.structure()`: приложение
собирается против **установленных** SNAPSHOT-артефактов платформы, а правка
`EntitySummary` лежала только в дереве. После
`mvn -f platform-core/pom.xml install -DskipTests` и
`mvn -f platform-vaadin/pom.xml install -DskipTests` дерево компилируется
(`mvn -o test-compile` → 0).

`mvn -o clean verify`: **2340 тестов, 35 failures, 14 errors** в 19 наборах
(лог: `outputs/u1-verify-20261018-r2.log`). Ни один набор не относится к U1:

- долг C5/RLS (18 наборов): `ApplicationBoundaryTest`, `RlsAutoConfigurationSmokeTest`,
  `RlsDeniedEventTest`, `RlsIntegrationTest`, `RlsReadGateTest`, `RlsUiGateTest`,
  `RlsServiceWriteBoundaryIT`, `RlsProtectedTypeActionDecisionTest`,
  `RlsDimensionFactsIT`, `C5GrantAdminParityIT`, `NamedOperationGrantAdminIT`,
  `OperationEffectiveRightsIT`, `RestReadRoleMatrixIT`, `DeepLinkTwoUserAccessIT`,
  `UngrantedDefinitionsReportTest`, `UserAdministrationSelfElevationIT`;
- чужой незакоммиченный срез explorer: `PlatformVaadinModuleTest` (138 → 139 типов),
  `WorkspaceManifestTest` (fingerprint: `platform-core` 153 → 154, `platform-vaadin`
  226 → 228), `NavigationAdminWiringTest`.

Замеры [`d1-surface-measurements.md`](../d1-surface-measurements.md) перегенерированы
намеренно (`-Dplatform.measurements.write=true`): `app-source-files=273`,
`platform-tree-files=144`, `platform-artifact-files=582`, `named-platform-types=308`,
`api-types=217`, `spi-types=33`, `legacy-internal-types=52`,
`legacy-internal-usage-links=180`; гейт после этого зелёный, общий счёт упал
36 → 35 failures.

## 5. Runtime-приёмка (2026-10-18, после DB1)

Запуск `mvn -o spring-boot:run` на локальной БД, где таблицы `ureport_template` уже нет:

| Проверка | Результат |
|---|---|
| Старт приложения | `Started Application in 23.9 s`, Tomcat на 8080, ошибок в логе нет |
| `GET /ureport/index.html` | `302` → `Location: /login`; тела дизайнера нет |
| `GET /ureport/designer` | `302` → `/login` |
| `GET /report-catalog` без сессии | `302` → `/login` (каталог не отдаётся) |
| `POST /api/report-jpql/preview` без аутентификации | `401` |

Не проверено интерактивно: каталог/печать JR/UniDR/UDR и профиль `accounting-pilot` —
нужны учётные данные приложения, которых в окружении нет; запускался основной профиль
с локальным конфигом, а не `accounting-pilot`.

## 6. Что осталось за границей этого evidence

- Отдельный прогон на PostgreSQL не организовывался: IT-наборы внутри verify шли на
  тестовой конфигурации и красные по долгу C5/RLS, а не по U1.
- DB1 — отдельный протокол: [`db1-ureport-data-removal-20261018.md`](db1-ureport-data-removal-20261018.md).
- Зелёный verify: 19 красных наборов принадлежат чужому срезу explorer и долгу C5/RLS;
  они не правились, чтобы не присвоить чужую работу.
