# F-REST-READ-3: контрольное ревью перед HTTP-этапом

- Дата: 2026-10-05, Europe/Moscow.
- Актуальный итог 2026-10-06: З5/Gate 3A/3B закрыты полным стабильным bootstrap ([evidence 4.7](rest-read-api-stage4-47-evidence-20261006.md)). Ниже сохранены выводы контрольного прогона 2026-10-05.
- Проверенный Git HEAD: `d11c674`; рабочая копия содержит изменения другого потока.
- План: [завершение этапа 3](rest-read-api-stage3-completion-plan.md).
- История приёмки: [completion evidence, §9–§12](rest-read-api-stage3-completion-evidence.md).
- Следующий этап: [HTTP-адаптер](rest-read-api-stage4-http-plan.md).

## 1. Вывод

Серверное чтение и адресная приёмка З0–З4 подтверждены. В контрольном наборе
2026-10-05 — 40/40, без failures/errors/skipped. Проверены текущие пилоты чтения,
реальные роли/отзыв, grant admin и API/surface. Новых функциональных отказов
в этом наборе нет; повторный полный bootstrap в этом ревью не выполнялся.

**Замечание к закрытию этапа:** З5 остаётся открытым по действующим критериям.
Последний полный bootstrap в `target/rest-stage3-z5/bootstrap-full.log` завершился
2026-10-05 в 00:11:51 по Москве с BUILD FAILURE: 1757 tests, 20 failures,
22 errors. Evidence §12 и checklist прямо требуют зелёного application clean
verify. Адресный успех не заменяет этот gate. Прежний документ датирует весь
пакет 2026-10-04; фактическая финальная фаза завершилась уже 2026-10-05.

Исправления C5 callers/test fixtures, описанные в §12, и новый полный прогон нужны
для формального закрытия. Планирование, wire-контракт и изолированная разработка
этапа 4 допустимы сейчас; сквозная приёмка и публикация сохраняют прежние gates.

## 2. Повторная проверка

| Набор | Tests / failures / errors / skipped |
| --- | --- |
| `RestReadPipelinePilotIT` | 12 / 0 / 0 / 0 |
| `RestReadRoleMatrixIT` | 8 / 0 / 0 / 0 |
| `C5GrantAdminParityIT` | 7 / 0 / 0 / 0 |
| `PlatformApiBaselineTest` | 3 / 0 / 0 / 0 |
| `PlatformPublicSurfaceTest` | 10 / 0 / 0 / 0 |
| Всего | **40 / 0 / 0 / 0** |

Maven завершён в 14:35:06 по Москве, BUILD SUCCESS. Использован JDK 21 (Axiom),
Maven из IntelliJ IDEA CE 2025.2.3, offline и `.local-maven-repository`.
Surefire classpath содержит owner JAR `platform-rls`, `platform-core`,
`platform-spring-boot-autoconfigure`, `platform-rest` из этого repository.
Owner production sources в данном ревью не менялись и не пересобирались.

```powershell
$restMaven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd'
$env:JAVA_HOME = 'C:\Program Files\Axiom\AxiomJDK-21'
$restRepo = [IO.Path]::GetFullPath('.local-maven-repository')
& $restMaven --batch-mode --no-transfer-progress --offline "-Dmaven.repo.local=$restRepo" '-Dtest=RestReadPipelinePilotIT,RestReadRoleMatrixIT,C5GrantAdminParityIT,PlatformApiBaselineTest,PlatformPublicSurfaceTest' test
```

Локальный лог — `target/rest-stage4-review/review-stage3.log`; root clean его
удалит. Числа и команда сохранены здесь; это evidence адресного прогона,
а не подтверждение сборки всего изменяющегося checkout.

| Проверенный source | SHA-256 |
| --- | --- |
| `RestReadPipelinePilotIT.java` | `4d7e8134117254ef1de1ab51a1d5e6b6c271cc299b08210a60c62a5452a565eb` |
| `RestReadRoleMatrixIT.java` | `17f3040b76c6296c7fd3550726be1b18ce1a0e41b19ca166a04cf8d3653b2d5f` |
| `C5GrantAdminParityIT.java` | `b31c49c74c4f1e2c12fd259df3f9abc5b0bb8faaec961bfd22df956a842fe770` |
| `RestReadService.java` | `c4f47b8421f9525f3ebd5ae44235a67641100da585819b79873e2cb0e0dd100e` |
| `C5ReadDecisionService.java` | `96c1c1128cb186f158b5958b8497656ce42ce1e4505163ff42f77eed6afb85da` |

## 3. Обязательные условия интеграции этапа 4

1. `RlsReadableIdsCache` создаётся через `@SessionScope` в `RlsAutoConfiguration`
   и используется `RlsFilterActivator`. Настройка STATELESS в Spring Security
   относится к SecurityContext/request cache и сама не отменяет session scope
   прикладного бина. Для REST нужен кеш, ограниченный запросом, и проверка
   отсутствия создания HttpSession/JSESSIONID. UI session cache сохраняется.
   Это условие нового HTTP-потребителя, а не отказ нынешних З3/З4 пилотов.
2. `RestReadService` принимает типизированный id, filters и int paging после
   авторизации. Обычный MVC binding способен отвергнуть строку раньше API/entity
   gate. В этапе 4 нужно явно сохранить порядок: authentication → resource →
   API/entity → parsing/validation → attribute/reference → canonical read.
3. `platform-rest` сейчас не содержит MVC и защищён composition tests от HTTP
   зависимостей. Выделение optional `platform-rest-web` требует узкого MODULE_API
   для чтения и schema facts; прежние INTERNAL types нельзя объявить разрешённым
   межмодульным API без review и baseline. Builder APP_API сохраняется.
4. Есть отдельная `@Order(1)` цепочка `/api/report-jpql/**` и Vaadin fallback.
   Новая цепочка должна иметь собственный matcher и проверяемый приоритет.
5. З4 измерял внутренние вызовы с session cache. У stateless HTTP добавятся
   authentication SQL и повторное RLS resolution на каждом запросе. Его бюджет
   измеряется отдельно, без переноса 16/12 как полного HTTP-бюджета.

Эти работы включены в [план этапа 4](rest-read-api-stage4-http-plan.md).
