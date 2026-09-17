# platform-telemetry — подсистема наблюдения

**D2 → D3.** Артефакт `org.ipro:platform-telemetry:1.0-SNAPSHOT` несёт подсистему
наблюдения целиком (66 типов): API (`EventSink`/`Telemetry`/`TraceService`/`UserContext`,
нейтральные швы `SqlStatementAudit`/`DeclaredNameSource`), core (операции, field audit,
журнал, security-события, SQL-инспекция), сущности журнала, репозиторий и свою
авто-конфигурацию с `@EntityScan`/`@EnableJpaRepositories`.

## Почему выносится третьей

Порядок задан замером замыкания (`PlatformSubsystemClosureTest`): после выворота цикла
с RLS (шаг 8а — швы `SqlStatementAudit`, `DeclaredNameSource`, перенос `RlsBypassAudit`
на сторону RLS) замыкание телеметрии стало пустым. Направление выбрано по слою, а не по
цене: наблюдение обязано работать без принуждения.

## Что в модуле, чего нет

Vaadin-адаптеры (`TelemetryVaadinInitListener`, `TelemetryErrorHandler`) в модуль не
входят — они живут в дереве приложения (`org.ip.telemetry.vaadin`), модуль остаётся без
зависимости на UI. `AppLifecycleLogger` — в модуле: Vaadin-версию он читает рефлексией
с fallback, импорта на Vaadin нет.

## Регистрация

Сущности и репозитории объявляет модуль (`@EntityScan("org.ipro.telemetry.model")` и
`@EnableJpaRepositories("org.ipro.telemetry.repository")` в `TelemetryAutoConfiguration`),
авто-конфигурация — своим imports-файлом. Приложение и платформенный хаб
`RlsAutoConfiguration` эти пакеты больше не перечисляют. Hibernate-хуки
(`SqlStatementInspector`, `SqlStatementListener`, `FieldAuditIntegratorProvider`)
подключаются свойствами `spring.jpa.properties.hibernate.*` (имена классов сохранены,
перенос их не тронул).
