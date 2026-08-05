package org.ipro.telemetry.config;

import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.api.Telemetry;
import org.ipro.telemetry.api.TraceService;
import org.ipro.telemetry.api.UserContext;
import org.ipro.telemetry.core.AppLifecycleLogger;
import org.ipro.telemetry.core.AsyncEventSink;
import org.ipro.telemetry.core.CompositeOperationHandler;
import org.ipro.telemetry.core.ExecutionTimeAspect;
import org.ipro.telemetry.core.NoopEventSink;
import org.ipro.telemetry.core.OperationCompletionHandler;
import org.ipro.telemetry.core.OperationContext;
import org.ipro.telemetry.core.PerfCounterStore;
import org.ipro.telemetry.core.SecurityEventLogger;
import org.ipro.telemetry.core.SlowOperationHandler;
import org.ipro.telemetry.core.SqlTimingBridge;
import org.ipro.telemetry.core.TelemetryBridge;
import org.ipro.telemetry.core.TelemetryGuard;
import org.ipro.telemetry.core.TelemetryService;
import org.ipro.telemetry.core.TelemetryVaadinInitListener;
import org.ipro.telemetry.core.TraceDumpHandler;
import org.ipro.telemetry.core.TraceRequestFilter;
import org.ipro.telemetry.core.TraceServiceImpl;
import org.ipro.telemetry.core.WindowReporter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

/**
 * Auto-Configuration подсистемы телеметрии. Пакеты org.ipro.telemetry.*
 * не попадают в component-scan приложения (базовый пакет org.ip), поэтому
 * все бины регистрируются здесь — это же гарантирует выключение
 * подсистемы целиком при ipro.telemetry.enabled=false.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "ipro.telemetry", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TelemetryProperties.class)
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

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "ipro.telemetry", name = "db-journal",
            havingValue = "true", matchIfMissing = true)
    public EventSink telemetryEventSink(JdbcTemplate jdbcTemplate,
                                        PlatformTransactionManager transactionManager) {
        AsyncEventSink asyncEventSink = new AsyncEventSink(jdbcTemplate, transactionManager,
                properties.getQueueSize());
        TelemetryBridge.setSink(asyncEventSink);
        return asyncEventSink;
    }

    @Bean
    @ConditionalOnMissingBean(EventSink.class)
    public EventSink noopTelemetryEventSink() {
        TelemetryBridge.setSink(NoopEventSink.INSTANCE);
        return NoopEventSink.INSTANCE;
    }

    @Bean
    public OperationCompletionHandler operationCompletionHandler(EventSink eventSink) {
        SlowOperationHandler slow = new SlowOperationHandler(properties.getMethodThresholdMs(),
                properties.getN1Threshold(), eventSink);
        TraceDumpHandler dump = new TraceDumpHandler(eventSink, properties.getTraceDir(),
                properties.getN1Threshold());
        return new CompositeOperationHandler(List.of(slow, dump));
    }

    @Bean
    public OperationContext operationContext(PerfCounterStore perfCounterStore,
                                             UserContext userContext,
                                             OperationCompletionHandler completionHandler) {
        TelemetryGuard.setEnabled(properties.isEnabled());
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
    public TelemetryVaadinInitListener telemetryVaadinInitListener(EventSink eventSink) {
        return new TelemetryVaadinInitListener(eventSink);
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
}
