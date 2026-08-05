package org.ipro.telemetry.config;

import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.api.Telemetry;
import org.ipro.telemetry.api.UserContext;
import org.ipro.telemetry.core.AsyncEventSink;
import org.ipro.telemetry.core.ExecutionTimeAspect;
import org.ipro.telemetry.core.NoopEventSink;
import org.ipro.telemetry.core.OperationCompletionHandler;
import org.ipro.telemetry.core.OperationContext;
import org.ipro.telemetry.core.PerfCounterStore;
import org.ipro.telemetry.core.SlowOperationHandler;
import org.ipro.telemetry.core.SqlTimingBridge;
import org.ipro.telemetry.core.TelemetryGuard;
import org.ipro.telemetry.core.TelemetryService;
import org.ipro.telemetry.core.TraceRequestFilter;
import org.ipro.telemetry.core.WindowReporter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

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
        return new AsyncEventSink(jdbcTemplate, transactionManager, properties.getQueueSize());
    }

    @Bean
    @ConditionalOnMissingBean(EventSink.class)
    public EventSink noopTelemetryEventSink() {
        return NoopEventSink.INSTANCE;
    }

    @Bean
    public OperationCompletionHandler operationCompletionHandler(EventSink eventSink) {
        return new SlowOperationHandler(properties.getMethodThresholdMs(),
                properties.getN1Threshold(), eventSink);
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
        return new ExecutionTimeAspect(operationContext);
    }

    @Bean
    public Telemetry telemetryService(OperationContext operationContext) {
        return new TelemetryService(operationContext);
    }

    @Bean
    public FilterRegistrationBean<TraceRequestFilter> traceRequestFilter() {
        FilterRegistrationBean<TraceRequestFilter> registration =
                new FilterRegistrationBean<>(new TraceRequestFilter());
        registration.addUrlPatterns("/*");
        registration.setName("telemetryTraceRequestFilter");
        registration.setOrder(1);
        return registration;
    }

    @Bean(destroyMethod = "close")
    public WindowReporter windowReporter(PerfCounterStore perfCounterStore,
                                         EventSink eventSink) {
        return new WindowReporter(perfCounterStore, properties.getL0WindowSeconds(), eventSink);
    }
}
