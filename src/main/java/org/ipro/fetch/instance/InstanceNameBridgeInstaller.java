package org.ipro.fetch.instance;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Objects;

/**
 * Привязывает статический {@link InstanceNameBridge} к жизненному циклу Spring-контекста
 * (C3.7).
 *
 * <p>До этого установка выполнялась внутри @Bean-метода резолвера, из-за чего
 * регистрация никогда не снималась, второй контекст перезаписывал первый, а при
 * пользовательском провайдере (когда бин платформы отступал по
 * {@code @ConditionalOnMissingBean}) bridge не устанавливался вообще.</p>
 *
 * <p>Разделение ролей: рендеринг отдаётся пользовательскому {@link InstanceNameProvider},
 * если он объявлен (пользовательская настройка важнее платформенной), а анализ состава
 * имени всегда остаётся у {@link InstanceNameResolver} — он выводится из metadata и
 * используется fetch-планами, поэтому не должен зависеть от того, кто рендерит.</p>
 */
public final class InstanceNameBridgeInstaller implements InitializingBean, DisposableBean {

    private final InstanceNameProvider provider;
    private final InstanceNameResolver analysis;

    public InstanceNameBridgeInstaller(InstanceNameResolver analysis,
                                       ObjectProvider<InstanceNameProvider> providers) {
        this.analysis = Objects.requireNonNull(analysis, "analysis must not be null");
        this.provider = providers.stream()
            .filter(candidate -> !(candidate instanceof InstanceNameResolver))
            .findFirst()
            .orElse(analysis);
    }

    /** Провайдер, который будет установлен — для диагностики и тестов. */
    public InstanceNameProvider provider() {
        return provider;
    }

    @Override
    public void afterPropertiesSet() {
        InstanceNameBridge.install(provider, analysis);
    }

    @Override
    public void destroy() {
        InstanceNameBridge.uninstall(provider, analysis);
    }
}
