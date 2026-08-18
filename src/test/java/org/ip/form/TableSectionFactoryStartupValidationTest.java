package org.ip.form;

import org.ip.form.builtin.ItemTable;
import org.ip.form.registry.FormRegistry;
import org.ip.form.registry.FormResolver;
import org.ipro.metadata.MetadataResolver;
import org.ip.model.PrdSpecMtr;
import org.ipro.crud.IdentifiableEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Startup validation (PR-1.4): если {@link TableSectionCustomization} объявляет варианты формы
 * строки ({@code declaredRowVariants()}), каждый из них обязан быть зарегистрирован как
 * ITEM-вариант — иначе старт падает, а не молчит до первого открытия формы строки.
 */
class TableSectionFactoryStartupValidationTest {

    private final FormRegistry registry = new FormRegistry();

    private final TableSectionCustomization<PrdSpecMtr> customization =
        new TableSectionCustomization<>() {
            @Override
            public Class<PrdSpecMtr> rowClass() {
                return PrdSpecMtr.class;
            }

            @Override
            public void configure(ItemTable<PrdSpecMtr, ?> table) {
            }

            @Override
            public List<String> declaredRowVariants() {
                return List.of("material", "product");
            }
        };

    private TableSectionFactory factoryWith(List<TableSectionCustomization<?>> customizations) {
        FormResolver resolver = mock(FormResolver.class);
        doReturn(registry).when(resolver).getFormRegistry();
        @SuppressWarnings("unchecked")
        ObjectProvider<FormResolver> provider = mock(ObjectProvider.class);
        doReturn(resolver).when(provider).getObject();

        return new TableSectionFactory(mock(MetadataResolver.class), mock(FieldFactory.class),
            mock(ApplicationContext.class), provider, customizations);
    }

    @Test
    void allDeclaredVariantsRegisteredPasses() {
        registry.registerItemForm(PrdSpecMtr.class, "material", ctx -> null);
        registry.registerItemForm(PrdSpecMtr.class, "product", ctx -> null);

        assertThatCode(() -> factoryWith(List.of(customization)).run(mock(ApplicationArguments.class)))
            .doesNotThrowAnyException();
    }

    @Test
    void undeclaredInRegistryVariantFailsStartup() {
        registry.registerItemForm(PrdSpecMtr.class, "material", ctx -> null);

        assertThatThrownBy(() -> factoryWith(List.of(customization)).run(mock(ApplicationArguments.class)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("'product'")
            .hasMessageContaining(PrdSpecMtr.class.getName());
    }

    @Test
    void customizationWithoutDeclaredVariantsIsIgnored() {
        TableSectionCustomization<IdentifiableEntity> silent = new TableSectionCustomization<>() {
            @Override
            public Class<IdentifiableEntity> rowClass() {
                return IdentifiableEntity.class;
            }

            @Override
            public void configure(ItemTable<IdentifiableEntity, ?> table) {
            }
        };

        assertThatCode(() -> factoryWith(List.of(silent)).run(mock(ApplicationArguments.class)))
            .doesNotThrowAnyException();
    }
}
