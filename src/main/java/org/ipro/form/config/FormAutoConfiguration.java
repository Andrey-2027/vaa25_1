package org.ipro.form.config;

import org.ipro.crud.EntityCopyService;
import org.ipro.form.FieldFactory;
import org.ipro.form.ItemFormSaveHandler;
import org.ipro.form.ItemFormSaveHandlerRegistry;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.TableSectionFactory;
import org.ipro.form.builder.ItemFormCustomizationRegistrar;
import org.ipro.form.builder.ListFormCustomizationRegistrar;
import org.ipro.form.builder.SelectionFormCustomizationRegistrar;
import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.form.coordinator.ItemFormAccessBinder;
import org.ipro.form.coordinator.ItemFormWrapperView;
import org.ipro.form.registry.FormRegistryConfiguration;
import org.ipro.form.registry.FormResolver;
import org.ipro.form.registry.ListCommandRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Бины формообразующего слоя платформы (org.ipro.form).
 *
 * <p>Регистрация через {@code @Import}: платформа исключена из component-scan
 * приложения, при этом сохраняется полная аннотационная обработка класса
 * (включая {@code @Scope("prototype")} у {@code ItemFormWrapperView}).</p>
 */
@AutoConfiguration
@Import({
    SelectionFormAssembler.class,
    EntityCopyService.class,
    FormRegistryConfiguration.class,
    ListCommandRegistry.class,
    FieldFactory.class,
    FormResolver.class,
    FormCoordinator.class,
    ItemFormAccessBinder.class,
    ItemFormWrapperView.class,
    TableSectionFactory.class,
    ListFormCustomizationRegistrar.class,
    ItemFormCustomizationRegistrar.class,
    SelectionFormCustomizationRegistrar.class
})
public class FormAutoConfiguration {

    /**
     * Collect application handlers as an optional override SPI. The list may be
     * empty: standard entities are intentionally handled by the metadata-driven
     * platform path.
     */
    @Bean
    @ConditionalOnMissingBean
    public ItemFormSaveHandlerRegistry itemFormSaveHandlerRegistry(
            ObjectProvider<ItemFormSaveHandler<?>> handlers) {
        return new ItemFormSaveHandlerRegistry(handlers.orderedStream().toList());
    }
}
