package org.ipro.form.action;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3.2.0 шаг 1.1: что площадка сборки действий знает о <b>месте объявления</b>, до того как это
 * попало в модель фактов.
 *
 * <p>Вопрос не теоретический: `ActionDefinition` места объявления не несёт, а карточка обязана
 * показать происхождение без догадки. Здесь измеряются ровно два механизма, на которые может
 * опереться каталог происхождения, — и оба измерены на контейнере, а не на вере:</p>
 * <ul>
 *   <li>разрешается ли класс-объявление для определения-бина через метаданные определения бина
 *       (имя фабрикующего бина → его тип), причём и для {@code proxyBeanMethods = false}, и для
 *       конфигурации с проксированием по умолчанию. <b>Измерено 2026-09-28:</b> для проксируемой
 *       конфигурации сырой {@code getType(...)} отдаёт CGLIB-класс
 *       ({@code ...Config$$SpringCGLIB$$0}), поэтому место объявления разворачивается
 *       {@link ClassUtils#getUserClass}; сырое значение в символе было бы именем,
 *       которого в исходниках нет;</li>
 *   <li>разворачивает ли {@link ClassUtils#getUserClass} JDK-proxy. Если нет — исполнитель,
 *       обёрнутый JDK-proxy, обязан давать «место объявления не подтверждено», а не имя
 *       proxy-класса.</li>
 * </ul>
 *
 * <p>Третий случай — CGLIB-proxy исполнителя — здесь не воспроизводится: {@code spring-aop} не
 * является объявленной зависимостью этого модуля (в E3.1 он объявлен в `platform-events`, и там же
 * живёт разворачивание proxy). Проверка CGLIB-ветки принадлежит приложению, где {@code spring-aop}
 * есть.</p>
 */
class ActionDeclaringClassResolutionTest {

    /** Тип-заглушка: измерениям важен не он, а место объявления. */
    static class ProbeEntity {
    }

    /** Приложение объявляет подавление определением-бином: {@code ActionPolicyConfig} делает так же. */
    @Configuration(proxyBeanMethods = false)
    static class PlainConfig {

        @Bean
        ActionDefinition probeSuppression() {
            return ActionDefinition.suppress(CrudAction.CREATE, ActionSurface.LIST_TOOLBAR,
                ProbeEntity.class);
        }
    }

    /** Конфигурация с проксированием по умолчанию: класс-объявление не должен «поехать». */
    @Configuration
    static class EnhancedConfig {

        @Bean
        ActionDefinition probeEntityAction() {
            return ActionDefinition.forEntity(ActionId.of("probe.do"), ActionSurface.ITEM_MENU,
                ProbeEntity.class, "Сделать", null, 10, ActionRequirement.none());
        }
    }

    @Test
    void declaringConfigurationClassIsResolvableForDefinitionBeans() {
        try (AnnotationConfigApplicationContext context =
                 new AnnotationConfigApplicationContext(PlainConfig.class, EnhancedConfig.class)) {
            ConfigurableListableBeanFactory factory = context.getBeanFactory();

            assertThat(declaringClassOf(factory, "probeSuppression"))
                .as("класс-объявление для определения-бина в @Configuration(proxyBeanMethods = false)")
                .isEqualTo(PlainConfig.class);
            assertThat(rawDeclaringTypeOf(factory, "probeEntityAction"))
                .as("сырой тип фабрикующего бина проксируемой конфигурации — CGLIB-подкласс")
                .isNotEqualTo(EnhancedConfig.class);
            assertThat(declaringClassOf(factory, "probeEntityAction"))
                .as("класс-объявление после разворачивания CGLIB")
                .isEqualTo(EnhancedConfig.class);
        }
    }

    @Test
    void declaringClassResolutionDoesNotDependOnBeanOrder() {
        try (AnnotationConfigApplicationContext context =
                 new AnnotationConfigApplicationContext(EnhancedConfig.class, PlainConfig.class);
             AnnotationConfigApplicationContext reversed =
                 new AnnotationConfigApplicationContext(PlainConfig.class, EnhancedConfig.class)) {
            assertThat(declaringClassOf(context.getBeanFactory(), "probeSuppression"))
                .isEqualTo(declaringClassOf(reversed.getBeanFactory(), "probeSuppression"));
        }
    }

    @Test
    void jdkProxyIsNotUnwrapped() {
        ActionHandler handler = new ProbeHandler();
        ActionHandler proxy = (ActionHandler) Proxy.newProxyInstance(
            ActionHandler.class.getClassLoader(), new Class<?>[]{ActionHandler.class},
            new InvocationHandler() {
                @Override
                public Object invoke(Object self, Method method, Object[] args) {
                    throw new UnsupportedOperationException("заглушка измерения");
                }
            });

        assertThat(ClassUtils.getUserClass(handler)).isEqualTo(ProbeHandler.class);
        assertThat(Proxy.isProxyClass(ClassUtils.getUserClass(proxy)))
            .as("JDK-proxy не разворачивается: место объявления такого исполнителя неизвестно")
            .isTrue();
    }

    /**
     * Класс-объявление определения-бина так, как его можно записать в символ факта: с разворотом
     * CGLIB-подкласса конфигурации — иначе в карточку попал бы класс, которого нет в исходниках.
     */
    private static Class<?> declaringClassOf(ConfigurableListableBeanFactory factory, String beanName) {
        Class<?> declaring = rawDeclaringTypeOf(factory, beanName);
        return declaring == null ? null : ClassUtils.getUserClass(declaring);
    }

    /** Сырой тип фабрикующего бина — без разворачивания (измеряемое значение). */
    private static Class<?> rawDeclaringTypeOf(ConfigurableListableBeanFactory factory, String beanName) {
        String factoryBeanName = factory.getBeanDefinition(beanName).getFactoryBeanName();
        assertThat(factoryBeanName)
            .as("beanName=%s объявлен @Bean-методом, значит у него есть фабрикующий бин", beanName)
            .isNotNull();
        return factory.getType(factoryBeanName);
    }

    /** Минимальный исполнитель: измерению нужен только его собственный класс. */
    static class ProbeHandler implements ActionHandler {

        @Override
        public ActionDefinition definition() {
            return ActionDefinition.forEntity(ActionId.of("probe.handler"), ActionSurface.LIST_TOOLBAR,
                ProbeEntity.class, "Обработать", null, 20, ActionRequirement.none());
        }

        @Override
        public void execute(ActionInvocation invocation) {
            throw new UnsupportedOperationException("заглушка измерения");
        }
    }
}
