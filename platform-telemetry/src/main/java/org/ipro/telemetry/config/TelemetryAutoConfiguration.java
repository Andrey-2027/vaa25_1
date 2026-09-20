package org.ipro.telemetry.config;

import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.api.Telemetry;
import org.ipro.telemetry.api.TraceService;
import org.ipro.telemetry.api.UserContext;
import org.ipro.telemetry.core.AppLifecycleLogger;
import org.ipro.telemetry.core.AsyncEventSink;
import org.ipro.telemetry.core.CompositeOperationHandler;
import org.ipro.telemetry.core.ExecutionTimeAspect;
import org.ipro.telemetry.core.FieldAuditBridge;
import org.ipro.telemetry.core.FieldAuditOperationHandler;
import org.ipro.telemetry.core.FieldAuditQueryService;
import org.ipro.telemetry.core.JournalQueryService;
import org.ipro.telemetry.core.JournalSearchService;
import org.ipro.telemetry.core.NoopEventSink;
import org.ipro.telemetry.core.OperationCompletionHandler;
import org.ipro.telemetry.core.OperationContext;
import org.ipro.telemetry.core.PerfCounterStore;
import org.ipro.telemetry.core.RetentionPurgeJob;
import org.ipro.telemetry.core.SecurityEventLogger;
import org.ipro.telemetry.core.SlowOperationHandler;
import org.ipro.telemetry.core.SqlTimingBridge;
import org.ipro.telemetry.core.TelemetryBridge;
import org.ipro.telemetry.core.TelemetryGuard;
import org.ipro.telemetry.core.TelemetryService;
import org.ipro.telemetry.core.TraceDumpHandler;
import org.ipro.telemetry.core.TraceRequestFilter;
import org.ipro.telemetry.core.TraceServiceImpl;
import org.ipro.telemetry.core.WindowReporter;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

/**
 * Auto-Configuration подсистемы телеметрии. Пакеты org.ipro.telemetry.*
 * не попадают в component-scan приложения (базовый пакет org.ip), поэтому
 * все бины регистрируются здесь — это же гарантирует выключение
 * подсистемы целиком при ipro.telemetry.enabled=false.
 *
 * <p>D2 → D3 (пара `telemetry` + `rls`): конфигурация больше не называет ни одного типа RLS.
 * Адаптер {@code RlsBypassAudit} переехал на сторону RLS (там же живёт канарейка SQL), а
 * наблюдатели стейтментов приходят через нейтральный шов
 * {@link org.ipro.telemetry.core.SqlStatementAuditBridge}. До этого подсистема наблюдения
 * физически не поднималась в развёртывании без подсистемы принуждения.</p>
 *
 * <p>D2 → D3 (срез platform-telemetry): подсистема выехала в собственный артефакт, поэтому
 * здесь же объявляются её {@code @EntityScan} и {@code @EnableJpaRepositories} — модуль
 * владеет своими пакетами, приложение и платформенный хаб {@code RlsAutoConfiguration}
 * их больше не перечисляют. Vaadin-адаптеры ({@code TelemetryVaadinInitListener},
 * {@code TelemetryErrorHandler}) в модуль не входят — они живут в дереве приложения
 * ({@code org.ipro.vaadin.telemetry} в platform-vaadin после D3.5.4), модуль остаётся без
 * зависимости на UI.</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "ipro.telemetry", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TelemetryProperties.class)
@EnableScheduling
@EntityScan("org.ipro.telemetry.model")
@EnableJpaRepositories("org.ipro.telemetry.repository")
public class TelemetryAutoConfiguration {

    private final TelemetryProperties properties;

    public TelemetryAutoConfiguration(TelemetryProperties properties) {
        this.properties = properties;
    }

    @Bean
    @ConditionalOnMissingBean
    public UserContext telemetryUserContext() {
        return UserContext.defaultInstance();
    }

    @Bean
    public PerfCounterStore perfCounterStore() {
        return new PerfCounterStore(properties.getL0WindowSeconds());
    }

    // Sink и владелец статического моста объявлены в SinkConfiguration ниже: у них отдельный
    // предмет (lifecycle статической ячейки), и он проверяется контейнером отдельно от подсистемы.

    @Bean
    public OperationCompletionHandler operationCompletionHandler(EventSink eventSink) {
        SlowOperationHandler slow = new SlowOperationHandler(properties.getMethodThresholdMs(),
                properties.getN1Threshold(), eventSink);
        TraceDumpHandler dump = new TraceDumpHandler(eventSink, properties.getTraceDir(),
                properties.getN1Threshold());
        FieldAuditOperationHandler fieldAudit = new FieldAuditOperationHandler(eventSink);
        return new CompositeOperationHandler(List.of(slow, dump, fieldAudit));
    }

    @Bean
    public OperationContext operationContext(PerfCounterStore perfCounterStore,
                                             UserContext userContext,
                                             OperationCompletionHandler completionHandler) {
        TelemetryGuard.setEnabled(properties.isEnabled());
        FieldAuditBridge.configure(csvSet(properties.getFieldAudit().getEntities()),
                csvSet(properties.getFieldAudit().getRedactFields()));
        OperationContext operationContext = new OperationContext(perfCounterStore, userContext,
                completionHandler, properties.getFrameLimit());
        SqlTimingBridge.setOperationContext(operationContext);
        return operationContext;
    }

    @Bean
    public ExecutionTimeAspect executionTimeAspect(OperationContext operationContext) {
        return new ExecutionTimeAspect(operationContext, properties.isEntityDataEnabled());
    }

    @Bean
    public Telemetry telemetryService(OperationContext operationContext) {
        TelemetryService telemetryService = new TelemetryService(operationContext);
        TelemetryBridge.set(telemetryService);
        return telemetryService;
    }

    @Bean
    public SecurityEventLogger securityEventLogger(EventSink eventSink) {
        return new SecurityEventLogger(eventSink);
    }

    @Bean
    public AppLifecycleLogger appLifecycleLogger(EventSink eventSink) {
        return new AppLifecycleLogger(eventSink, properties.getAppName());
    }

    @Bean
    public FilterRegistrationBean<TraceRequestFilter> traceRequestFilter(TraceService traceService) {
        FilterRegistrationBean<TraceRequestFilter> registration =
                new FilterRegistrationBean<>(new TraceRequestFilter(traceService));
        registration.addUrlPatterns("/*");
        registration.setName("telemetryTraceRequestFilter");
        registration.setOrder(1);
        return registration;
    }

    @Bean
    public TraceService traceService(JdbcTemplate jdbcTemplate) {
        return new TraceServiceImpl(jdbcTemplate);
    }

    @Bean
    public JournalQueryService journalQueryService(JdbcTemplate jdbcTemplate) {
        return new JournalQueryService(jdbcTemplate);
    }

    @Bean
    public JournalSearchService journalSearchService(
            org.ipro.telemetry.repository.OperationLogRepository operationLogRepository,
            JdbcTemplate jdbcTemplate) {
        return new JournalSearchService(operationLogRepository, jdbcTemplate);
    }

    @Bean
    public FieldAuditQueryService fieldAuditQueryService(JdbcTemplate jdbcTemplate) {
        FieldAuditQueryService service = new FieldAuditQueryService(jdbcTemplate);
        FieldAuditBridge.setQueryService(service);
        return service;
    }

    @Bean
    @ConditionalOnProperty(prefix = "ipro.telemetry", name = "trace-self-test",
            havingValue = "true")
    public TraceSelfTest traceSelfTest(jakarta.persistence.EntityManagerFactory entityManagerFactory,
                                       PlatformTransactionManager transactionManager,
                                       Telemetry telemetry) {
        return new TraceSelfTest(entityManagerFactory, transactionManager, telemetry);
    }

    @Bean(destroyMethod = "close")
    public WindowReporter windowReporter(PerfCounterStore perfCounterStore,
                                         EventSink eventSink) {
        return new WindowReporter(perfCounterStore, properties.getL0WindowSeconds(), eventSink);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ipro.telemetry.retention", name = "purge-enabled",
            havingValue = "true", matchIfMissing = true)
    public RetentionPurgeJob retentionPurgeJob(JdbcTemplate jdbcTemplate) {
        TelemetryProperties.Retention retention = properties.getRetention();
        return new RetentionPurgeJob(jdbcTemplate, properties.getTraceDir(),
                retention.getEventsDays(), retention.getSecurityDays(),
                retention.getStatsDays(), retention.getTraceHours(),
                retention.getFieldAuditDays());
    }

    @Bean
    @ConditionalOnProperty(prefix = "ipro.telemetry", name = "field-audit-self-test",
            havingValue = "true")
    public FieldAuditSelfTest fieldAuditSelfTest(jakarta.persistence.EntityManagerFactory entityManagerFactory,
                                                 PlatformTransactionManager transactionManager,
                                                 Telemetry telemetry,
                                                 JdbcTemplate jdbcTemplate) {
        return new FieldAuditSelfTest(entityManagerFactory, transactionManager, telemetry, jdbcTemplate);
    }

    /**
     * Sink подсистемы и владелец статического моста.
     *
     * <p><b>Почему это отдельная конфигурация (D3.5.6).</b> Здесь единственное место, которое
     * пишет в {@link TelemetryBridge}, и единственное, которое обязано его освобождать. Пока
     * установка жила в тех же {@code @Bean}-методах, что и создание sink'а, владение было неотделимо
     * от побочного эффекта: ячейка заполнялась тем бином, который создали первым, и не очищалась
     * никогда — после закрытия контекста мост указывал на закрытый {@code AsyncEventSink}, а события
     * молча терялись. Отдельная конфигурация делает этот предмет проверяемым контейнером без
     * подъёма всей подсистемы: JPA-репозитории требуют настоящей метамодели, поэтому полный
     * контекст в тесте не поднимается.</p>
     *
     * <p><b>Member-класс, а не отдельный файл — тоже часть контракта.</b> Условие применимости
     * подсистемы ({@code ipro.telemetry.enabled}) объявлено на внешнем классе, и member-класс
     * пропускается вместе с ним. Вынесенный в отдельный файл, он поднимался бы при выключенной
     * телеметрии.</p>
     */
    @Configuration(proxyBeanMethods = false)
    static class SinkConfiguration {

        /**
         * {@code @ConditionalOnMissingBean} — потому что «default/custom» это обещание, а не
         * пожелание. Без него пользовательский sink давал два бина одного типа, и исход зависел от
         * направления инъекции: падение на неоднозначности либо (с {@code @Primary}) тихая работа
         * с приёмником, на который мост не смотрел.
         */
        @Bean(destroyMethod = "close")
        @ConditionalOnProperty(prefix = "ipro.telemetry", name = "db-journal",
                havingValue = "true", matchIfMissing = true)
        @ConditionalOnMissingBean(EventSink.class)
        public EventSink telemetryEventSink(JdbcTemplate jdbcTemplate,
                                            PlatformTransactionManager transactionManager,
                                            TelemetryProperties properties) {
            return new AsyncEventSink(jdbcTemplate, transactionManager,
                    properties.getQueueSize());
        }

        @Bean
        @ConditionalOnMissingBean(EventSink.class)
        public EventSink noopTelemetryEventSink() {
            return NoopEventSink.INSTANCE;
        }

        /**
         * Единственный владелец моста: устанавливает <b>разрешённый</b> sink (свой или
         * пользовательский) и снимает его адресно при закрытии контекста.
         */
        @Bean
        TelemetrySinkOwner telemetrySinkOwner(EventSink eventSink) {
            return new TelemetrySinkOwner(eventSink);
        }
    }

    /**
     * Владелец статической ячейки моста. Непубличный: это деталь wiring'а, а не контракт модуля
     * (публичный тип попал бы в baseline телеметрии и расширил бы известный долг D3.3).
     */
    static final class TelemetrySinkOwner implements DisposableBean {

        private final EventSink installed;

        TelemetrySinkOwner(EventSink eventSink) {
            this.installed = eventSink;
            TelemetryBridge.setSink(eventSink);
        }

        @Override
        public void destroy() {
            TelemetryBridge.clearSink(installed);
        }
    }

    private static java.util.Set<String> csvSet(String value) {
        java.util.Set<String> result = new java.util.HashSet<>();
        if (value != null) {
            for (String item : value.split(",")) {
                if (!item.isBlank()) {
                    result.add(item.trim());
                }
            }
        }
        return result;
    }
}
