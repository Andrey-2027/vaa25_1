package org.ip.navigation;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The application builds its menu through the navigation catalog only. The module stays optional for
 * the platform, but the application cannot run without it: a disabled flag is a clear startup error,
 * not a {@code NoSuchBeanDefinitionException} from a consumer.
 */
@Configuration(proxyBeanMethods = false)
public class ApplicationNavigationRequirement {
    public static final String PROPERTY = "platform.navigation.enabled";
    static final String MESSAGE = "Навигация обязательна для приложения: уберите " + PROPERTY
        + "=false (меню строится только через платформенный каталог навигации).";

    @Bean
    static NavigationRequiredCheck navigationRequiredCheck() {
        return new NavigationRequiredCheck();
    }

    static final class NavigationRequiredCheck implements BeanFactoryPostProcessor, EnvironmentAware {
        private Environment environment;

        @Override
        public void setEnvironment(Environment environment) { this.environment = environment; }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            if (!environment.getProperty(PROPERTY, Boolean.class, false)) {
                throw new IllegalStateException(MESSAGE);
            }
        }
    }
}
