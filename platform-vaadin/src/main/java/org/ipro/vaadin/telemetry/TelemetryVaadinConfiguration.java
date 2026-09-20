package org.ipro.vaadin.telemetry;

import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.config.TelemetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import com.vaadin.flow.server.VaadinService;

/**
 * Vaadin-адаптер телеметрии — часть UI-слоя платформы.
 *
 * <p>D2 → D3 (срез platform-telemetry): {@code TelemetryVaadinInitListener} и
 * {@code TelemetryErrorHandler} тянут {@code com.vaadin}, поэтому в модуль
 * platform-telemetry (без зависимости на UI) они не входят — как и
 * {@code RlsUiGate}, который специально не зависит от Vaadin.</p>
 *
 * <p>D3.5.4 (физический перенос): адаптер уехал из дерева приложения
 * ({@code org.ip.telemetry.vaadin}) в {@code platform-vaadin} — платформенный модуль
 * не может жить в пакете приложения. Вместе с переездом сменился способ регистрации:
 * раньше класс находился в component scan'е пакета {@code org.ip}, теперь его
 * подключает imports-файл модуля. Это не деталь: без записи в imports-файле адаптер
 * исчез бы молча — конфигурация есть, бина нет, ошибки UI перестают попадать в
 * журнал. Условие включения зеркалит {@code TelemetryAutoConfiguration}
 * ({@code ipro.telemetry.enabled}); полный lifecycle (владение sink'ом,
 * {@code @ConditionalOnClass(VaadinService)}, дедупликация listener) — D3.5.6.</p>
 *
 * <p>D3.5.6 (lifecycle и применимость): адаптер переведён в {@code @AutoConfiguration} и получил
 * три условия, каждое из которых закрывает наблюдаемый отказ.</p>
 * <ul>
 *   <li>{@code @ConditionalOnClass(VaadinService)} — Vaadin объявлен в модуле {@code provided}.
 *       Конфигурация без этого условия доходила до создания бина с Vaadin-типом и падала там, где
 *       UI вообще нет: необязательный адаптер выглядел как обязательная зависимость.</li>
 *   <li>{@code @ConditionalOnBean(EventSink)} — без приёмника обработке ошибок UI некуда писать,
 *       а прежний безусловный {@code @Bean} давал вместо отступления
 *       {@code UnsatisfiedDependency}: выключенная телеметрия выглядела как поломка платформы.</li>
 *   <li>{@code @AutoConfigureAfter(TelemetryAutoConfiguration)} — условие на {@code EventSink}
 *       проверяется в момент применения конфигурации, а не по факту создания бинов, поэтому
 *       порядок здесь несущий. До шага он держался на алфавите FQN ({@code telemetry.config} <
 *       {@code vaadin.telemetry}) — случайность, а не контракт.</li>
 * </ul>
 *
 * <p>{@code @Bean} больше не принимает sink: слушатель им не пользуется (владение мостом —
 * {@code TelemetryAutoConfiguration.SinkConfiguration}), а параметр заставлял UI-бин быть
 * условием работоспособности подсистемы наблюдения. Условие на {@code EventSink} при этом
 * остаётся: непустой приёмник — по-прежнему признак, что телеметрия собрана.</p>
 */
@AutoConfiguration
@ConditionalOnClass(VaadinService.class)
@ConditionalOnBean(EventSink.class)
@AutoConfigureAfter(TelemetryAutoConfiguration.class)
@ConditionalOnProperty(prefix = "ipro.telemetry", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class TelemetryVaadinConfiguration {

    @Bean
    public TelemetryVaadinInitListener telemetryVaadinInitListener() {
        return new TelemetryVaadinInitListener();
    }
}
