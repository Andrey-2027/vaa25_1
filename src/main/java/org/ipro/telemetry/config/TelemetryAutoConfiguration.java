package org.ipro.telemetry.config;

import org.ipro.telemetry.api.Telemetry;
import org.ipro.telemetry.api.UserContext;
import org.ipro.telemetry.core.ExecutionTimeAspect;
import org.ipro.telemetry.core.OperationCompletionHandler;
import org.ipro.telemetry.core.OperationContext;
import org.ipro.telemetry.core.PerfCounterStore;
import org.ipro.telemetry.core.SlowOperationHandler;
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

    @Bean
    public OperationCompletionHandler operationCompletionHandler() {
        return new SlowOperationHandler(properties.getMethodThresholdMs());
    }

    @Bean
    public OperationContext operationContext(PerfCounterStore perfCounterStore,
                                             UserContext userContext,
                                             OperationCompletionHandler completionHandler) {
        TelemetryGuard.setEnabled(properties.isEnabled());
        return new OperationContext(perfCounterStore, userContext, completionHandler,
                properties.getFrameLimit());
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
    public WindowReporter windowReporter(PerfCounterStore perfCounterStore) {
        return new WindowReporter(perfCounterStore, properties.getL0WindowSeconds());
    }
}