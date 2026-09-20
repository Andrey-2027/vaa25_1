package org.ipro.form.builder;

import org.ipro.form.registry.FormRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты собственной декларации ряда диалога (расхождение со списком — opt-in).
 */
class SelectionContextFiltersOverrideTest {

    @Test
    void registrarRegistersSelectionFilters() {
        FormRegistry registry = new FormRegistry();
        SelectionFormCustomization customization = new SelectionFormCustomization() {
            @Override
            public Class<?> entityClass() {
                return String.class;
            }

            @Override
            public void configure(SelectionFormVariants variants) {
            }

            @Override
            public List<ContextFilterField> contextFilters() {
                return List.of(ContextFilterField.auto("code", "Код"));
            }
        };
        SelectionFormCustomizationRegistrar registrar =
            new SelectionFormCustomizationRegistrar(registry, List.of(customization));

        registrar.afterPropertiesSet();

        assertThat(registry.getSelectionContextFilters(String.class))
            .extracting(ContextFilterField::path)
            .containsExactly("code");
        // Общий ряд списка не тронут
        assertThat(registry.getContextFilters(String.class)).isEmpty();
    }

    @Test
    void emptySelectionDeclarationLeavesNoOverride() {
        FormRegistry registry = new FormRegistry();
        SelectionFormCustomization customization = new SelectionFormCustomization() {
            @Override
            public Class<?> entityClass() {
                return String.class;
            }

            @Override
            public void configure(SelectionFormVariants variants) {
            }
        };
        SelectionFormCustomizationRegistrar registrar =
            new SelectionFormCustomizationRegistrar(registry, List.of(customization));

        registrar.afterPropertiesSet();

        assertThat(registry.getSelectionContextFilters(String.class)).isEmpty();
    }

    @Test
    void registrarsRegisterVariantRows() {
        FormRegistry registry = new FormRegistry();
        ListFormCustomization listConfig = new ListFormCustomization() {
            @Override
            public Class<?> entityClass() {
                return String.class;
            }

            @Override
            public void configure(ListFormVariants variants) {
                variants.contextFilters("v", List.of(ContextFilterField.auto("code", "Код")));
            }
        };
        SelectionFormCustomization selectionConfig = new SelectionFormCustomization() {
            @Override
            public Class<?> entityClass() {
                return String.class;
            }

            @Override
            public void configure(SelectionFormVariants variants) {
                variants.contextFilters("w", List.of(ContextFilterField.auto("name", "Имя")));
            }
        };
        new ListFormCustomizationRegistrar(registry, List.of(listConfig)).afterPropertiesSet();
        new SelectionFormCustomizationRegistrar(registry, List.of(selectionConfig)).afterPropertiesSet();

        assertThat(registry.resolveListContextFilters(String.class, "v"))
            .extracting(ContextFilterField::path)
            .containsExactly("code");
        assertThat(registry.getVariantContextFilters(String.class,
            org.ipro.form.registry.FormType.SELECTION, "w"))
            .extracting(ContextFilterField::path)
            .containsExactly("name");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void selectionSpecificRowWinsOverShared() {
        FormRegistry registry = new FormRegistry();
        registry.registerContextFilters(String.class, List.of(
            ContextFilterField.auto("code", "Код"),
            ContextFilterField.auto("name", "Имя")));
        registry.registerSelectionContextFilters(String.class, List.of(
            ContextFilterField.auto("code", "Код (выбор)")));
        org.ipro.form.SelectionForm form = mockForm();
        org.ipro.metadata.MetadataResolver metadataResolver = new org.ipro.metadata.MetadataResolver();

        SelectionContextFilters.attach(form, String.class, null, java.util.Map.of(),
            registry, metadataResolver, null, mockAssembler());

        org.mockito.ArgumentCaptor<com.vaadin.flow.component.Component> header =
            org.mockito.ArgumentCaptor.forClass(com.vaadin.flow.component.Component.class);
        org.mockito.Mockito.verify(form).setHeaderRow(header.capture());
        ContextFilterPanel panel = (ContextFilterPanel) header.getValue();
        assertThat(panel.getChildren().toList()).hasSize(1);
        com.vaadin.flow.component.textfield.TextField input =
            (com.vaadin.flow.component.textfield.TextField) panel.getChildren().toList().get(0);
        assertThat(input.getLabel()).isEqualTo("Код (выбор)");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void onlyMarkedFieldsReachDialog() {
        FormRegistry registry = new FormRegistry();
        registry.registerContextFilters(String.class, List.of(
            ContextFilterField.auto("code", "Код").allListVariants(),
            ContextFilterField.auto("name", "Имя")));
        org.ipro.form.SelectionForm form = mockForm();
        org.ipro.metadata.MetadataResolver metadataResolver = new org.ipro.metadata.MetadataResolver();

        SelectionContextFilters.attach(form, String.class, null, java.util.Map.of(),
            registry, metadataResolver, null, mockAssembler());

        org.mockito.ArgumentCaptor<com.vaadin.flow.component.Component> header =
            org.mockito.ArgumentCaptor.forClass(com.vaadin.flow.component.Component.class);
        org.mockito.Mockito.verify(form).setHeaderRow(header.capture());
        ContextFilterPanel panel = (ContextFilterPanel) header.getValue();
        assertThat(panel.getChildren().toList()).hasSize(1);
        com.vaadin.flow.component.textfield.TextField input =
            (com.vaadin.flow.component.textfield.TextField) panel.getChildren().toList().get(0);
        assertThat(input.getLabel()).isEqualTo("Код");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void variantRowWinsOverMarked() {
        FormRegistry registry = new FormRegistry();
        registry.registerContextFilters(String.class, List.of(
            ContextFilterField.auto("code", "Код").allListVariants()));
        registry.registerVariantContextFilters(String.class,
            org.ipro.form.registry.FormType.SELECTION, "compact",
            List.of(ContextFilterField.auto("name", "Имя")));
        org.ipro.form.SelectionForm form = mockForm();
        org.ipro.metadata.MetadataResolver metadataResolver = new org.ipro.metadata.MetadataResolver();

        SelectionContextFilters.attach(form, String.class, "compact", java.util.Map.of(),
            registry, metadataResolver, null, mockAssembler());

        org.mockito.ArgumentCaptor<com.vaadin.flow.component.Component> header =
            org.mockito.ArgumentCaptor.forClass(com.vaadin.flow.component.Component.class);
        org.mockito.Mockito.verify(form).setHeaderRow(header.capture());
        ContextFilterPanel panel = (ContextFilterPanel) header.getValue();
        assertThat(panel.getChildren().toList()).hasSize(1);
        com.vaadin.flow.component.textfield.TextField input =
            (com.vaadin.flow.component.textfield.TextField) panel.getChildren().toList().get(0);
        assertThat(input.getLabel()).isEqualTo("Имя");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void markedUnionFromEntityAndVariantBlocks() {
        FormRegistry registry = new FormRegistry();
        registry.registerContextFilters(String.class, List.of(
            ContextFilterField.auto("code", "Код").allListVariants()));
        registry.registerVariantContextFilters(String.class,
            org.ipro.form.registry.FormType.LIST, "withExtra",
            List.of(
                ContextFilterField.auto("code", "Код-вариант").allListVariants(),
                ContextFilterField.auto("name", "Имя").allListVariants()));
        org.ipro.form.SelectionForm form = mockForm();
        org.ipro.metadata.MetadataResolver metadataResolver = new org.ipro.metadata.MetadataResolver();

        SelectionContextFilters.attach(form, String.class, null, java.util.Map.of(),
            registry, metadataResolver, null, mockAssembler());

        org.mockito.ArgumentCaptor<com.vaadin.flow.component.Component> header =
            org.mockito.ArgumentCaptor.forClass(com.vaadin.flow.component.Component.class);
        org.mockito.Mockito.verify(form).setHeaderRow(header.capture());
        ContextFilterPanel panel = (ContextFilterPanel) header.getValue();
        // code — из уровня сущности (первое вхождение побеждает), name — из блока варианта
        assertThat(panel.getChildren().toList()).hasSize(2);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static org.ipro.form.SelectionForm mockForm() {
        return org.mockito.Mockito.mock(org.ipro.form.SelectionForm.class);
    }

    private static org.ipro.form.SelectionFormAssembler mockAssembler() {
        return org.mockito.Mockito.mock(org.ipro.form.SelectionFormAssembler.class);
    }
}
