package org.ipro.form;

import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.ipro.form.builtin.ItemForm;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemFormSaveHandlerRegistryTest {

    @Test
    void resolvesDeclaredHandlerByExactEntityClass() {
        ItemFormSaveHandler<ReceivingDocument> handler = handler(ReceivingDocument.class);
        ItemFormSaveHandlerRegistry registry = new ItemFormSaveHandlerRegistry(List.of(handler));

        assertThat(registry.find(ReceivingDocument.class)).containsSame(handler);
        assertThat(registry.find(PrdSpec.class)).isEmpty();
        assertThat(registry.declaredFor(ReceivingDocument.class)).containsSame(handler);
    }

    @Test
    void rejectsDuplicateDeclaredHandlersDuringConstruction() {
        ItemFormSaveHandler<ReceivingDocument> first = handler(ReceivingDocument.class);
        ItemFormSaveHandler<ReceivingDocument> second = handler(ReceivingDocument.class);

        assertThatThrownBy(() -> new ItemFormSaveHandlerRegistry(List.of(first, second)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(ReceivingDocument.class.getName())
            .hasMessageContaining(first.getClass().getName())
            .hasMessageContaining(second.getClass().getName());
    }

    @Test
    void rejectsDynamicAmbiguityBeforePersistence() {
        ItemFormSaveHandler<ReceivingDocument> first = new DynamicHandler();
        ItemFormSaveHandler<ReceivingDocument> second = new DynamicHandler();
        ItemFormSaveHandlerRegistry registry = new ItemFormSaveHandlerRegistry(List.of(first, second));

        assertThatThrownBy(() -> registry.find(ReceivingDocument.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Неоднозначный")
            .hasMessageContaining(ReceivingDocument.class.getName());
    }

    private static <T extends org.ipro.identity.IdentifiableEntity> ItemFormSaveHandler<T> handler(
            Class<T> entityClass) {
        return new ItemFormSaveHandler<>() {
            @Override
            public Class<T> supportedEntityClass() {
                return entityClass;
            }

            @Override
            public FormSaveResult<T> save(ItemForm<T> form) {
                return new FormSaveResult.Success<>(form.getEntity());
            }
        };
    }

    private static final class DynamicHandler implements ItemFormSaveHandler<ReceivingDocument> {
        @Override
        public boolean supports(Class<?> entityClass) {
            return ReceivingDocument.class.equals(entityClass);
        }

        @Override
        public FormSaveResult<ReceivingDocument> save(ItemForm<ReceivingDocument> form) {
            return new FormSaveResult.Success<>(form.getEntity());
        }
    }
}
