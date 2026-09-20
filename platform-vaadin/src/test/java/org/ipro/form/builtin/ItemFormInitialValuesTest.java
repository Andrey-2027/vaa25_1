package org.ipro.form.builtin;

import com.vaadin.flow.component.textfield.TextField;
import org.ipro.form.FieldFactory;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.LookupService;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Тесты начальных значений новой записи (Этап 1б): предустановка до чтения в UI —
 * поле видно сразу, снимок чистый.
 */
class ItemFormInitialValuesTest {

    private ItemForm<Unit> form;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        MetadataResolver metadataResolver = new MetadataResolver();
        EntityMetadataInfo meta = metadataResolver.resolve(Unit.class);
        FieldFactory fieldFactory = new FieldFactory(
            mock(LookupService.class), mock(SelectionFormAssembler.class));
        form = new ItemForm(meta, fieldFactory);
    }

    @Test
    void initialValuesShownAndClean() {
        Unit created = form.initializeNewEntity(Map.of("name", "Гайка"));

        assertThat(created.getName()).isEqualTo("Гайка");
        assertThat(((TextField) form.getField("name")).getValue()).isEqualTo("Гайка");
        assertThat(form.isDirty()).isFalse();
    }

    @Test
    void unknownFieldFailsFast() {
        assertThatThrownBy(() -> form.initializeNewEntity(Map.of("nope", "X")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nope");
    }

    @Test
    void dottedPathFailsFast() {
        assertThatThrownBy(() -> form.initializeNewEntity(Map.of("a.b", "X")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("a.b");
    }

    @EntityMetadata(listFormTitle = "ЕИ", itemFormTitle = "ЕИ")
    static class Unit extends BaseEntity {
        @FieldMetadata(label = "Имя", grid = @GridColumn(order = 1))
        String name;

        String getName() {
            return name;
        }
    }
}
