package org.ipro.form.builder;

import org.ipro.form.registry.FormRegistry;
import org.springframework.beans.factory.InitializingBean;

import java.util.List;

/**
 * Находит все бины {@link ItemFormCustomization} и регистрирует их варианты в
 * {@link FormRegistry}. Один общий класс на весь проект — конкретные классы-конфиги (по одному
 * на сущность, см. {@code org.ip.views.forms}) ничего не знают про {@code FormRegistry} или
 * жизненный цикл Spring-бинов, только описывают FormFactory через {@link ItemFormVariants}.
 */
/**
 * Регистрация бина — в {@code FormAutoConfiguration}, без условий: регистратор агрегирует вклады
 * приложения в реестр форм (D3.5.5).
 */
public class ItemFormCustomizationRegistrar implements InitializingBean, org.springframework.beans.factory.SmartInitializingSingleton {

    private final FormRegistry formRegistry;
    private final List<ItemFormCustomization> customizations;
    private List<Class<?>> annotatedForms = List.of();
    private org.ipro.metadata.MetadataResolver metadataResolver;
    private org.springframework.beans.factory.config.AutowireCapableBeanFactory beanFactory;

    public void setAnnotatedForms(List<Class<?>> types, org.ipro.metadata.MetadataResolver metadata,
            org.springframework.beans.factory.config.AutowireCapableBeanFactory factory) {
        annotatedForms = List.copyOf(types); metadataResolver = metadata; beanFactory = factory;
    }

    public ItemFormCustomizationRegistrar(FormRegistry formRegistry,
                                          List<ItemFormCustomization> customizations) {
        this.formRegistry = formRegistry;
        this.customizations = customizations;
    }

    @Override
    public void afterPropertiesSet() {
        for (ItemFormCustomization customization : customizations) {
            Class<?> entityClass = customization.entityClass();
            String source = customization.getClass().getName();
            ItemFormVariants variants = new ItemFormVariants();
            customization.configure(variants);

            variants.getFactories().forEach((variant, factory) ->
                formRegistry.registerItemForm(entityClass, variant, factory, source, variants.declaredClass(variant)));

            variants.getCustomizers().forEach((variant, customizerList) ->
                customizerList.forEach(customizer ->
                    formRegistry.addItemCustomizer(entityClass, variant, customizer)));
        }
        for (Class<?> type : annotatedForms) registerDescription(type);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerDescription(Class<?> type) {
        FormFor declaration = type.getAnnotation(FormFor.class);
        if (!org.ipro.form.builtin.ItemForm.class.isAssignableFrom(type) || java.lang.reflect.Modifier.isAbstract(type.getModifiers()))
            throw new IllegalArgumentException("@FormFor requires a concrete ItemForm: " + type.getName());
        Class<?> generic = org.springframework.core.ResolvableType.forClass(type)
            .as(org.ipro.form.builtin.ItemForm.class).getGeneric(0).resolve();
        if (generic != declaration.value()) throw new IllegalArgumentException("@FormFor generic/entity mismatch: " + type.getName());
        int depth = 0;
        for (Class<?> parent = type; parent != org.ipro.form.builtin.ItemForm.class; parent = parent.getSuperclass()) {
            if (++depth > 2) throw new IllegalArgumentException("Form inheritance exceeds two levels: " + type.getName());
            var inherited = parent.getDeclaredAnnotation(FormFor.class);
            if (inherited != null && inherited.value() != declaration.value())
                throw new IllegalArgumentException("Variant changes its entity: " + type.getName());
        }
        String variant = declaration.variant().isBlank() ? null : declaration.variant();
        formRegistry.registerItemForm(declaration.value(), variant,
            context -> (org.ipro.form.builtin.ItemForm<?>) beanFactory.createBean(type), type.getName(), (Class) type);
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (Class<?> type : annotatedForms) {
            try {
                var declaration = type.getAnnotation(FormFor.class);
                var form = (org.ipro.form.builtin.ItemForm<?>) beanFactory.createBean(type);
                var definition = form.describeLayout(descriptionMetadata(metadataResolver, declaration.value()));
                validateNodes(definition.nodes(), declaration.value());
                form.validateDescriptionBehavior(definition);
                formRegistry.registerItemReadPaths(declaration.value(),
                    declaration.variant().isBlank() ? null : declaration.variant(),
                    () -> {
                        var opening = (org.ipro.form.builtin.ItemForm<?>) beanFactory.createBean(type);
                        var layout = opening.describeLayout(descriptionMetadata(metadataResolver, declaration.value()));
                        return org.ipro.form.builder.layout.FormReadDependencies.readPaths(declaration.value(), layout);
                    });
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("Form " + type.getName() + ": " + failure.getMessage(), failure);
            }
        }
    }

    public static org.ipro.metadata.EntityMetadataInfo descriptionMetadata(org.ipro.metadata.MetadataResolver resolver, Class<?> type) {
        if (org.ipro.metadata.MetadataResolver.isApplicable(type)) return resolver.resolve(type);
        var row = resolver.resolveRowMetadata(type);
        return new org.ipro.metadata.EntityMetadataInfo(type, null, org.ipro.metadata.annotation.EntityKind.PLAIN,
            resolver.resolveAllAnnotatedFields(type), row.getFormFields(), row.getGridFields(), row.getColumnPaths(), row.getColumnPaths());
    }

    private void validateNodes(List<org.ipro.form.builder.layout.LayoutNode> nodes, Class<?> entity) {
        for (var node : nodes) {
            switch (node) {
                case org.ipro.form.builder.layout.ElementNode element -> {
                    if (element.selectionVariant() != null && element.content() instanceof org.ipro.form.builder.layout.FieldNode field) {
                        var info = descriptionMetadata(metadataResolver, entity).getFieldByName(field.fieldName());
                        if (SelectionColumnResolver.resolve(metadataResolver, formRegistry, info.getLookupEntity(), element.selectionVariant()) == null)
                            throw new IllegalArgumentException("Unknown selection variant '" + element.selectionVariant() + "' for " + field.fieldName());
                    }
                    validateNodes(List.of(element.content()), entity);
                }
                case org.ipro.form.builder.layout.SectionNode section -> {
                    if (metadataResolver.resolveTableSections(entity).stream().noneMatch(value -> value.getRowClass() == section.rowClass()))
                        throw new IllegalArgumentException("Unknown section " + section.rowClass().getName());
                }
                case org.ipro.form.builder.layout.PanelNode panel -> validateNodes(panel.children(), entity);
                case org.ipro.form.builder.layout.TabSheetNode tabs -> tabs.tabs().forEach(tab -> validateNodes(tab.children(), entity));
                default -> { }
            }
        }
    }
}
