# C5.5: определения операций и единое решение C5 — 2026-10-06

- План: [C5.4–C5.10](../c5-operation-permissions-plan.md), пакет C5.5.
- Решение: [ADR-0017](../decisions/ADR-0017-c5-single-operation-authority.md) §2, §3, §5, §8, §10.
- Метод: чтение исходников, реализация в `platform-rls` и
  `platform-spring-boot-autoconfigure`, прогоны owner-тестов и архитектурных гейтов.

## 1. Входы

| Параметр | Значение |
| --- | --- |
| Ветка | `main` |
| HEAD | `d11c67424497f232d50181fb3626d90e87760123`, 2026-10-05 00:24:27 +03:00 |
| Рабочее дерево | параллельные незакоммиченные изменения платформы, приложения и документации (регистры, REST, readable-ids seam); они не трогались |
| JDK | OpenJDK/Axiom 21.0.11 LTS |
| Maven | IntelliJ bundled; в PATH отсутствует |
| Артефакты | `platform-build/pom.xml install -DskipTests` выполнен, потому что дерево содержит `ItemTable.refreshRows()`, которого нет в ранее установленном `platform-vaadin-1.0-SNAPSHOT.jar` — без обновления артефактов приложение не компилируется. Это состояние дерева, а не следствие C5.5 |

## 2. Что введено

| Тип | Роль |
| --- | --- |
| [C5Operation](../../../platform-rls/src/main/java/org/ipro/rls/c5/C5Operation.java) | один словарь операций: `READ`, `CREATE`, `UPDATE`, `DELETE`, `EXECUTE` |
| [C5DecisionReason](../../../platform-rls/src/main/java/org/ipro/rls/c5/C5DecisionReason.java) | стабильные безопасные причины: `PERMITTED`, `UNAUTHENTICATED`, `ENTITY_/ATTRIBUTE_/ACTION_PERMISSION_DENIED`; конфигурационные ошибки — не причина |
| [C5PermissionDefinition](../../../platform-rls/src/main/java/org/ipro/rls/c5/C5PermissionDefinition.java) | определение «ключ + допустимые операции» с фабриками `entityRead`/`entityOperations`/`action` и запретом смешивать `READ`/`EXECUTE` с другими операциями |
| [C5PermissionDecision](../../../platform-rls/src/main/java/org/ipro/rls/c5/C5PermissionDecision.java) | один результат: allow/deny, причина, ключ, операция, источники (`прямой`, `роль: <name>`) и безопасное сообщение; deny без источников |
| [C5OperationPermissionRegistry](../../../platform-rls/src/main/java/org/ipro/rls/c5/C5OperationPermissionRegistry.java) | явные определения: идемпотентная регистрация, конфликт ключа — отказ, неизвестный ключ/неподдержанная операция — `C5PermissionConfigurationException` |
| [C5PermissionDecisionService](../../../platform-rls/src/main/java/org/ipro/rls/c5/C5PermissionDecisionService.java) | единое решение `decide`/`require` для операций сущности и именованных операций; read делегируется в существующий `C5ReadDecisionService` |

Правила хранения (ADR-0017 §3) реализованы как заявлено: берётся **только** точный ключ и
глобальная строка (`dimensionValueId == null`); `'*'` и value-scoped строки операцию не
авторизуют; `canUpdate` даёт `CREATE`/`UPDATE`/`EXECUTE`, `canDelete` даёт `DELETE`,
`canRead` операцией не распоряжается; `false` не является explicit deny и не отменяет
allow другой назначенной роли. Отсутствующий субъект — `UNAUTHENTICATED`, ошибка
хранилища распространяется и не превращается в allow или пустой успех.

Написание ключей разведено так же, как в ADR: read остаётся `ENTITY:<FQN>`, определение
операции использует существующий ключ сущностного гейта `ENTITY:<SimpleName>` —
[`C5PermissionKeys.entityOperations`](../../../platform-rls/src/main/java/org/ipro/rls/c5/C5PermissionKeys.java)
и `C5PermissionDefinition` — единственные поддерживаемые способы его получить.

Регистрация из capabilities:
[C5EntityOperationPermissionRegistrar](../../../platform-spring-boot-autoconfigure/src/main/java/org/ipro/data/config/C5EntityOperationPermissionRegistrar.java)
выводит `CREATE`/`UPDATE`/`DELETE` из `EntityDescriptor.capabilities().writes()`, получает
от типа только те операции, которые он объявил, и регистрирует ключ как динамическое
измерение `CHECK_ONLY` — тем же механизмом, каким read-регистратор уже делает read-ключи
доступными администратору грантов. Тип без write-capability не получает определения.
Регистрация никому не выдаёт доступ.

Проводка: [RlsAutoConfiguration](../../../platform-rls/src/main/java/org/ipro/rls/config/RlsAutoConfiguration.java)
публикует `c5OperationPermissionRegistry` и `c5PermissionDecisionService`;
[DataAccessAutoConfiguration](../../../platform-spring-boot-autoconfigure/src/main/java/org/ipro/data/config/DataAccessAutoConfiguration.java)
публикует регистратор операций (package-private метод, как у существующего read-регистратора).

## 3. Проверки

| Прогон | Команда | Результат |
| --- | --- | --- |
| platform-rls owner-набор | `mvn -f platform-build/pom.xml -pl ../platform-rls test` | 59 тестов, 0 failures, 0 errors; из них 32 новых C5.5: 9 реестра, 19 решения, 4 на настоящем JPA |
| platform-spring-boot-autoconfigure | `mvn -f platform-build/pom.xml -pl ../platform-spring-boot-autoconfigure test` | 35 тестов, 0 failures; из них 3 новых на регистрацию из capabilities |
| проводка в приложении | `mvn -o test -Dtest=RlsAutoConfigurationSmokeTest,C5GrantAdminParityIT,PlatformRlsModuleTest` | 17 + 7 + 5 = 29 тестов, 0 failures: контекст с новыми бинами поднимается, паритет админского экрана с решением сохранён |
| границы пакетов | `mvn -o test -Dtest=PlatformPackageDependencyMatrixTest` | 3 теста, 0 failures: новых рёбер нет |
| манифест рабочего пространства | `mvn -o test -Dtest=WorkspaceManifestTest` | 4 теста, 0 failures после намеренного обновления отпечатков `platform-rls` (51 → 60 файлов) и `platform-spring-boot-autoconfigure` (17 → 19) через `-Dplatform.manifest.write=true` |
| reviewed-состав модулей | `PlatformRlsModuleTest`, `AutoconfigureModuleCompositionTest` | пройдены после добавления новых типов в оба reviewed-списка |

Новые тесты проверяют: независимость `CREATE`/`UPDATE`/`DELETE` по одной строке;
недопустимость трактовки read-гранта как права на запись; отказ по `'*'` и value-scoped
строкам; роль действует только пока назначена; отзыв гранта закрывает следующее решение;
неизвестное определение и неподдержанная операция — ошибка конфигурации, а не отказ;
ошибка хранилища не становится allow; read-решение сохраняет прежнюю семантику и
источники.

## 4. Что не проверено
- **Отпечатки манифеста включают чужие файлы в этих двух проектах:** записанный набор
  `platform-rls` — это текущее дерево целиком, то есть мои 9 файлов плюс 2 файла
  параллельной работы (`RlsReadableIdsResolver`, `RlsReadableIdsSeamTest`); сам манифест был
  частично обновлён до этого пакета. Пересмотр отпечатков намеренный: без него
  `WorkspaceManifestTest` падал на двух проектах.

- **Потребителя ещё нет.** Ни один production-путь не вызывает новое решение: применение
  к записи и к UI — это C5.6 и C5.7. Поэтому «одна строка даёт одно независимое решение»
  доказано тестами owner-набора, а не поведением приложения.
- **Signature-baseline `platform-rls.api` не перегенерирован.** Перегенерация захватила бы
  и чужие незакоммиченные изменения поверхности (`RlsBypassScope`, `RlsFilterActivator`,
  `RlsReadableIdsResolver`, сигнатуры `DataAccessAutoConfiguration` и
  `MetadataAutoConfiguration`), то есть зафиксировала бы как reviewed то, что ещё не
  ревьюировано. По той же причине сейчас красны `PlatformApiBaselineTest`
  (`platform-core`, `platform-rest`, `platform-vaadin`, отсутствующий `platform-rest-web`) и
  `PlatformPublicSurfaceTest` (6 неназванных типов, wildcard-импорты и fully-qualified
  ссылки в коде регистра, устаревшие числа `d1-surface-measurements.md`) — ни один из
  перечисленных случаев не относится к файлам C5.5. Базовая линия поверхности вернётся в
  зелёное состояние вместе с закрытием параллельной работы.
- **Поверхность зафиксирована не полностью:** 6 новых public-типов записаны в reviewed-список
  `PlatformRlsModuleTest`, но в подписной baseline не попали (см. выше).
- **PostgreSQL-шаг не выполнялся:** реальные flush-callbacks и откат — C5.6/C5.9.
- **Администратор ещё не умеет выдавать операционные права:** типизированный сервис
  назначения и первичная выдача — C5.8. До неё определения зарегистрированы и видны
  администратору грантов, но allow для операций никому не выдан.
