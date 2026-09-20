package org.ipro.form.registry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;

/**
 * Конфигурация реестра форм.
 *
 * Создаёт Spring-бин FormRegistry, который используется для регистрации
 * кастомных вариантов форм.
 *
 * Для регистрации собственных форм создайте свой @Configuration класс
 * и добавьте регистрации через @Bean метод, принимающий FormRegistry:
 *
 * <pre>
 * {@code @Configuration}
 * public class CustomFormsConfiguration {
 *     {@code @Bean}
 *     public void registerCustomForms(FormRegistry registry) {
 *         registry.registerListForm(
 *             Nomenclature.class,
 *             "archived",
 *             context -> new ArchivedNomenclatureListForm(context)
 *         );
 *     }
 * }
 * </pre>
 *
 * <p><b>D3.5.5: почему этот класс остался {@code @Import}, а не стал {@code @Bean}.</b>
 * {@link #onApplicationEvent(org.springframework.context.event.ContextRefreshedEvent)} замораживает
 * реестр, вызывая {@link #formRegistry()} <i>напрямую</i>. Такое самообращение работает только
 * потому, что класс — {@code @Configuration}: вызов перехватывает CGLIB-прокси и возвращает
 * контейнерный синглтон. Зарегистрировав этот объект обычным {@code @Bean}, получишь вызов метода у
 * одноразового экземпляра: заморозится мусорный объект, а реестр контейнера продолжит принимать
 * регистрации после старта — без единого падения. Это единственная причина, по которой класс не
 * переведён на общую схему шага, и она закреплена тестом {@code FormAutoConfigurationTest}.</p>
 */
@Configuration
public class FormRegistryConfiguration implements ApplicationListener<ContextRefreshedEvent> {

    /**
     * Тестовый (и только тестовый) выход из заморозки: {@code DialogWorkspaceResolutionIT}
     * намеренно регистрирует вариант после старта, чтобы сравнить Dialog- и Workspace-ветку.
     * В приложении свойства нет — значит реестр после старта закрыт.
     */
    private final boolean allowRuntimeRegistration;

    public FormRegistryConfiguration(
            @Value("${ipro.form.registry.allow-runtime-registration:false}")
            boolean allowRuntimeRegistration) {
        this.allowRuntimeRegistration = allowRuntimeRegistration;
    }

    @Bean
    public FormRegistry formRegistry() {
        return new FormRegistry();
    }

    /**
     * D3.5.3: после старта контекста реестр форм закрыт для регистраций.
     *
     * <p>Почему событие, а не {@code SmartInitializingSingleton}: последний выполняется в момент
     * инициализации синглтонов, а регистраторы ({@code ListFormCustomizationRegistrar} и др.)
     * сами обязаны успеть зарегистрировать варианты. {@link ContextRefreshedEvent} приходит
     * после того, как все синглтоны проинициализированы, — то есть тогда, когда композиция форм
     * действительно собрана.</p>
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (!allowRuntimeRegistration) {
            formRegistry().freeze();
        }
    }
}
