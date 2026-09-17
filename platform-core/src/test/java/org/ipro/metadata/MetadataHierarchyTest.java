package org.ipro.metadata;

import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты иерархического скана полей (Этап 5.1): аннотированные поля предков
 * подхватываются, при перекрытии имён побеждает подкласс, технический ключ
 * без аннотации (BaseEntity.id) не собирается.
 */
class MetadataHierarchyTest {

    private final MetadataResolver resolver = new MetadataResolver();

    @Test
    void inheritedAnnotatedFieldsIncluded() {
        EntityMetadataInfo meta = resolver.resolve(Child.class);

        assertThat(meta.getFormFields()).extracting(FieldMetadataInfo::getName)
            .contains("parentCode", "ownCode");
    }

    @Test
    void subclassFieldHidesParentField() {
        EntityMetadataInfo meta = resolver.resolve(HidingChild.class);

        assertThat(meta.getFormFields()).filteredOn(f -> f.getName().equals("code"))
            .hasSize(1)
            .first()
            .extracting(FieldMetadataInfo::getLabel)
            .isEqualTo("Код наследника");
    }

    @Test
    void technicalIdWithoutAnnotationNotCollected() {
        EntityMetadataInfo meta = resolver.resolve(Plain.class);

        assertThat(meta.getFormFields()).extracting(FieldMetadataInfo::getName)
            .containsExactly("name");
        assertThat(meta.getFieldByName("id")).isNull();
    }

    @Test
    void hiddenFieldIsAvailableOnlyThroughCompleteMetadataProjection() {
        EntityMetadataInfo meta = resolver.resolve(WithHiddenField.class);

        assertThat(meta.getFormFields()).extracting(FieldMetadataInfo::getName)
            .containsExactly("visible");
        assertThat(meta.getAllAnnotatedFields()).extracting(FieldMetadataInfo::getName)
            .containsExactly("hidden", "visible");
        assertThat(meta.getFieldByName("hidden")).isNotNull();
        assertThat(meta.getAllAnnotatedFields()).isUnmodifiable();
    }

    static abstract class Ancestor {
        @FieldMetadata(label = "Код предка", grid = @GridColumn(order = 1))
        String parentCode;
    }

    @EntityMetadata(listFormTitle = "Наследник")
    static class Child extends Ancestor {
        @FieldMetadata(label = "Свой код", grid = @GridColumn(order = 2))
        String ownCode;
    }

    static abstract class HidingAncestor {
        @FieldMetadata(label = "Код предка")
        String code;
    }

    @EntityMetadata(listFormTitle = "Перекрытие")
    static class HidingChild extends HidingAncestor {
        @FieldMetadata(label = "Код наследника")
        String code;
    }

    @EntityMetadata(listFormTitle = "Обычная")
    static class Plain extends BaseEntity {
        @FieldMetadata(label = "Имя", grid = @GridColumn(order = 1))
        String name;
    }

    @EntityMetadata(listFormTitle = "Со скрытым полем")
    static class WithHiddenField extends BaseEntity {
        @FieldMetadata(label = "Скрытое", hidden = true, order = 1)
        String hidden;

        @FieldMetadata(label = "Видимое", order = 2)
        String visible;
    }
}
