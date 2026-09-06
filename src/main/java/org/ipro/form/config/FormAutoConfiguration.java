package org.ipro.form.config;

import org.ipro.crud.EntityCopyService;
import org.ipro.form.FieldFactory;
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
import org.springframework.context.annotation.Import;

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
}
