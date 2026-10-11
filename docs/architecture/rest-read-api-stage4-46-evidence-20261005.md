# F-REST-READ-4.6: настоящие HTTP/H2-пилоты

- Дата: 2026-10-05.
- Git HEAD: `d11c67424497f232d50181fb3626d90e87760123`, dirty checkout.
- Статус: реализация и функциональные пилоты 4.6 завершены: owner 109/109,
  HTTP/compatibility 50/50. Общие З5/4.7/C5/C gates открыты.
- Границы: [план этапа 4](rest-read-api-stage4-http-plan.md),
  [инструкция клиента](rest-read-api-client.md).

## 1. Контур проверки

Четыре application IT: `RestReadHttpPilotIT`, `RestReadHttpSecurityIT`,
`RestReadHttpSessionIsolationIT`, `RestReadHttpContractIT`; 17 tests.
Общий `RestReadHttpTestSupport` запускает настоящий Application, embedded Tomcat
на случайном порту и JDK HttpClient через localhost socket. Каждый авторизованный
REST-запрос содержит Basic username/password, проверяемые production provider,
UserDetailsService, BCrypt и H2 User/Role storage. C5 evaluator, role resolver,
AccessService, canonical EntityReadAccess, Hibernate и JSON codec настоящие.

Из production beans заменён только demo `DataInitializer`. Codec spy всегда
вызывает реальный serializer и проверяет отсутствие transaction/SQL во время
записи JSON. Тестовый aspect наблюдает загруженные JPA graphs внутри read
transaction; не подменяет EntityReadAccess. RLS bypass запрещён assertions.

Fixture setup применяет `RlsTestFixture` с настоящими временными wildcard grants.
Для repository reads на JUnit-потоке привязывается отдельный Mock servlet request
с обычным UI session cache, очищаемый после test. На потоках реального сервера
этого request нет: там production REST request resolver. Fixture SQL не входит
в измерения. Имена пользователей/фильтров уникальны, H2 база принадлежит контексту.

Два test-only компонента дополняют fixture: опубликованный ресурс с page default=1,
max=2 для проверки индивидуального лимита и фильтр создания/чтения настоящей
HttpSession для session-cookie сценария. Они не меняют production v1 declarations
и не присутствуют в обычном приложении.

Vaadin route scan ограничен настоящим LoginView через `@EnableVaadin` в test
configuration. Иначе unrelated UI test subclasses с унаследованными `@Route`
мешают запуску Tomcat. Production security chains сохранены; UI проверка покрывает
redirect на login для UReport, не полный browser rendering/frontend bundle.

Toolchain: Axiom JDK 21.0.11, IntelliJ Maven 3.9.9, offline local repository.
Runtime: Boot 4.0.6, Spring Security 7.0.5, Hibernate 7.2.12.Final, H2 2.4.240,
Tomcat 11.0.21, Vaadin Flow 25.1.7. OSIV и Hibernate L2 cache выключены.

## 2. Измеренные SQL и закреплённые пределы

W=200, у каждого ресурса 201 видимая строка. LIST maximum projection с fixture
filter измеряется при size=1/50/200; последняя разрешённая полная страница —
page=3,size=50. Total остаётся 201, totalPages=5; page=4,size=50 отклоняется до
business SQL. Дополнительно проверены size=0, page=1/199, границы и overflow.

Счётчик привязан к серверному request/thread через тестовый correlation header.
Категории определены по SQL: app_user/user_role/role до authentication — auth,
после — role resolution; access_grant с `dimension_value_id is null` — C5,
остальные access_grant reads — RLS; prd_spec/nomenclature — content/count.
OTHER должен оставаться нулём. Значения ниже включают весь request, без прогрева
между HTTP-запросами и без fixture SQL.

| Максимальная операция | Direct: auth/C5/roles/RLS/content/count | Через две роли: измерено | Предел через две роли |
| --- | --- | --- | --- |
| Specifications LIST + journalId | 2/17/3/2/1/1 = 26 | 2/34/20/4/1/1 = 62 | 2/51/20/4/1/1 = 79 |
| Specifications DETAIL | 2/17/3/2/1/0 = 25 | 2/34/20/4/1/0 = 61 | 2/51/20/4/1/0 = 78 |
| Nomenclature LIST + typeNom | 2/12/3/2/1/1 = 21 | 2/24/15/4/1/1 = 47 | 2/36/15/4/1/1 = 59 |
| Nomenclature DETAIL | 2/9/3/2/1/0 = 17 | 2/18/12/4/1/0 = 37 | 2/27/12/4/1/0 = 46 |

В role fixture назначены две реальные роли: reader с разрешениями и дополнительная
без grants. Предел C5 допускает USER + обе ROLE lookup для каждого permission:
resolver не обещает порядок перебора ролей, short-circuit может найти reader первым
либо вторым. RLS роль-запрос получает набор ролей. Численные maxima закреплены
до итогового прогона в `assertMaximumBudget`; меньшая стоимость допустима.
Это бюджет данной projection/filter/role cardinality, не универсальный предел
для произвольного числа ролей или будущих ресурсов.

Стоимость неизменна между size=1/50/200. Business budget: ровно 1 content + 1 count
для полных LIST fixtures, 1 content без count для DETAIL. Security/RLS оплачиваются
отдельно. Отсутствие JOURNAL annotation у Nomenclature означает отсутствие row
predicate для этого root; общий filter activator всё ещё разрешает RLS ids,
поэтому его запросы не вычитаются из HTTP стоимости.

Graphs проверяются при всех размерах, DETAIL и direct/role:
spec LIST — nomenclature загружена, journal нет; spec DETAIL — nomenclature и
journal загружены; materials/operations не загружены. Во всех случаях nested
unit/group не загружены, также у Nomenclature root. Ненулевые связи в fixtures
предотвращают ложное подтверждение lazy graph на null. Serializer выполняется
после transaction; 0 SQL; наружу уходят только scalar aliases.

## 3. Security, transport, contract

- Anonymous known/unknown/malformed → 401 без SQL вообще; неверный пароль,
  disabled account, session-only/stale cookie → 401.
- Unknown major/resource/unpublished entity → 404. LIST/DETAIL API grants
  независимы у обоих ресурсов. Entity/id/selected/filter/sort/reference права
  проверяются до business SQL; malformed input без прав даёт 403, с правами 400.
- Реальные назначения ролей, неназначенная роль, отзыв grant/снятие роли действуют
  в следующем HTTP request. Алиса/Боб видят разные JOURNAL; скрытая и отсутствующая
  DETAIL одинаковы после удаления traceId/instance; скрытый FK filter даёт пустой
  список. Номенклатура доступна обоим субъектам при обязательных C5 grants.
- Ни один REST request не создаёт session/JSESSIONID; incoming real UI session
  не аутентифицирует REST и не меняется при Basic. SecurityContext, REST marker,
  RequestContextHolder очищены после request. 12 параллельных обращений двух
  субъектов, success/400/403 и следующий запрос не смешивают rows/cache.
- Все 12 default/id-only/maximum wire cases совпадают с reviewed v1 snapshots
  после нормализации только fixture strings/integer ids. Точные aliases/id/null,
  numeric grammar, duplicate/unknown inputs и resource-specific max проверены.
- Число `9007199254740993` записано в H2, прочитано через HTTP и разобрано клиентом
  как точный int64. Decimal отсутствует в текущих двух ресурсах; точность decimal
  покрывает owner codec/OpenAPI suite 4.5, его результат повторён в web owner run.
- Настоящие HTTP методы/Accept: 405 с Allow только GET, 406, HEAD без entity body;
  Problem status/code/trace/challenge/no-store, отсутствие HTML/redirect/internal
  details. Legacy JPQL сохраняет отдельный формат 401/403/400; UI login redirect
  сохраняется. Storage/provider 503, unexpected 500, framework/error-dispatch и
  codec fallback проверяются owner fault seams 4.4, повторёнными в этом пакете;
  H2 не выключается для fault simulation.

## 4. Найденное и исправленное

Реальный HTTP pilot обнаружил `Set-Cookie: JSESSIONID` при Basic с существующей
UI session. Наблюдение за changeSessionId показало стек:
`SessionManagementFilter → CompositeSessionAuthenticationStrategy →
ChangeSessionIdAuthenticationStrategy`. Одного STATELESS + NullSecurityContextRepository
недостаточно для запрета этих callbacks.

В REST chain явно задан `NullAuthenticatedSessionStrategy`. Cookie перестаёт
вращаться, incoming session id и сохранённый UI principal остаются прежними.
Изменение ограничено REST namespace. Дополнен owner regression с настоящим
MockHttpSession и app socket test с настоящей Tomcat session.
Семантика custom strategy сверена с [Spring Security SessionManagementConfigurer](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/config/annotation/web/configurers/SessionManagementConfigurer.html#sessionAuthenticationStrategy(org.springframework.security.web.authentication.session.SessionAuthenticationStrategy)).

Начальные harness failures: duplicate Vaadin test routes; отсутствие request scope
у main-thread setup чтений. Исправления описаны в §1; production read/RLS не
ослаблены. Первый полный прогон 17 tests имел 1 session failure и 2 setup errors;
промежуточный diagnostic 3 tests дал 2 passes и тот же session failure.

## 5. Команды, результаты и идентичность

```powershell
$env:JAVA_HOME = 'C:\Program Files\Axiom\AxiomJDK-21'
$restMaven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd'
$restRepo = [IO.Path]::GetFullPath('.local-maven-repository')
& $restMaven --batch-mode --no-transfer-progress --offline "-Dmaven.repo.local=$restRepo" -f platform-rest-web/pom.xml install
$restPomText = [IO.File]::ReadAllText([IO.Path]::GetFullPath('pom.xml'))
$restPomText = $restPomText.Replace('<build>', '<build><directory>${project.basedir}/target/rest-stage4-46/app-validation</directory>')
[IO.File]::WriteAllText([IO.Path]::GetFullPath('.rest46-validation-pom.xml'), $restPomText)
& $restMaven --batch-mode --no-transfer-progress --offline "-Dmaven.repo.local=$restRepo" -f .rest46-validation-pom.xml '-Dtest=RestReadHttpPilotIT,RestReadHttpSecurityIT,RestReadHttpSessionIsolationIT,RestReadHttpContractIT,RestReadPipelinePilotIT,RestReadRoleMatrixIT,C5GrantAdminParityIT,ReportJpqlPreviewControllerIT' test
Remove-Item -LiteralPath .rest46-validation-pom.xml
& ./scripts/bootstrap-local-dependencies.ps1 -LocalRepository $restRepo -ValidateOnly
& ./scripts/bootstrap-local-dependencies.ps1 -LocalRepository $restRepo -ValidateOnly -AllowSourceDrift
```

Временный POM — копия root pom.xml с единственным отличием build directory:
`target/rest-stage4-46/app-validation`. Это изолирует compiler metadata/test classes
от других работ в общем target. Sources/dependencies/test configuration штатные.
Без конкурирующих builds те же tests запускаются обычным root pom.xml.
Временный файл удаляется после проверки, production pom.xml не меняется.

| Прогон | Результат |
| --- | --- |
| platform-rest-web install | 109/109, exit 0 |
| HTTP application tests | 17/17: Pilot 3, Security 5, SessionIsolation 4, Contract 5 |
| Compatibility application tests | 33/33: Pipeline 13, RoleMatrix 8, C5GrantAdminParity 7, ReportJpqlPreviewController 5 |
| Совместный application test run | 50/50, exit 0, 4:18 min |
| strict ValidateOnly | exit 1, прежний core source drift |
| diagnostic ValidateOnly AllowSourceDrift | exit 0; graph/POM/coordinates/layout, REST fingerprints совпали; общий drift сохраняется |

Owner web fingerprint v2: `2895a05adc850c738bb4cf6c9ba2e13b1de4d0ea41fac44d526db28eaa0dd116`,
37 inputs. Обновлён только соответствующий manifest fingerprint/state.
Server REST не менялся: `5bae2f6ceb08af06fb38c85ef2060e62a926c3b48147cdf1fcf5751bce682d7f`, 60 inputs.

| Runtime JAR | SHA-256 |
| --- | --- |
| platform-rest-web | `5983389cdb15fc704fae9ed9979e0dbdc1716c71b65d009602d3d633f0bff6ab` |
| platform-rest | `6f4ff434fca1b3bf2182c133c5cde6457ab309dc8cc038e62d3457a8969cc284` |
| platform-rls | `d4ba871137b630acb864730a262b36225c52898a146da92e6e0358bf5232cd03` |
| platform-core | `99110546cba5d60732d192ba468c8575ede0f69da3713e3767f677339604aac8` |
| platform-spring-boot-autoconfigure | `a4cf9db4b6fcfa8063152b5a57a3985c82ecd3603ffbf803921acba94a33ec0d` |

Runtime coordinates/classpath фиксируются Surefire XML и Maven dependency tree
в isolated output. Логи — `target/rest-stage4-46/{first-http,http-matrix,
budgets-session,web-owner,final-http-compatibility,strict-validation,
diagnostic-validation}.log`; clean их удаляет, существенные данные перенесены сюда.

Общие gates не закрыты. Strict останавливается на core fingerprint: ожидаемые
131 inputs против фактических 136. Diagnostic также показывает прежний drift
autoconfigure, DynamicReports/UReport3. Их fingerprints не переписаны этим пакетом.
Последние structural failures отражены в [evidence 4.5](rest-read-api-stage4-45-evidence-20261005.md).
4.7 должен проверить final owner/app/starter/API/surface и полный strict bootstrap,
а внешний consumer/deployment gate согласовывается отдельно. PostgreSQL для
функциональных H2-пилотов не требуется.
