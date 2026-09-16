package org.ip.telemetry.vaadin;

import org.ipro.telemetry.api.EventSink;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Vaadin-адаптер телеметрии в дереве приложения.
 *
 * <p>D2 → D3 (срез platform-telemetry): {@code TelemetryVaadinInitListener} и
 * {@code TelemetryErrorHandler} тянут {@code com.vaadin}, поэтому в модуль
 * platform-telemetry (без зависимости на UI) они не входят — как и
 * {@code RlsUiGate}, который специально не зависит от Vaadin. Бин регистрирует
 * приложение, а не модуль: пакет {@code org.ip} попадает в component scan,
 * отдельная запись в imports-файле не нужна. Условие включения зеркалит
 * {@code TelemetryAutoConfiguration} ({@code ipro.telemetry.enabled}).</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "ipro.telemetry", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class TelemetryVaadinConfiguration {

    @Bean
    public TelemetryVaadinInitListener telemetryVaadinInitListener(EventSink eventSink) {
        return new TelemetryVaadinInitListener(eventSink);
    }
}
