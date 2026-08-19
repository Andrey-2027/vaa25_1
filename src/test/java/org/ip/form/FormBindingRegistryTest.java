package org.ip.form;

import com.vaadin.flow.component.textfield.TextField;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.numbering.annotation.Numbered;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Тесты {@link FormBindingRegistry} (PR-0.1 + PR-1.1): read/write/dirty/readOnly/required,
 * unique-key guard в {@code add()}, external-биндинги (PR-1.1): валидация/dirty/read-only
 * работают по BindingDescriptor без FieldMetadataInfo.
 */
class FormBindingRegistryTest {

    @Test
    void registryStoresAndFindsBindings() {
        FormBindingRegistry registry = new FormBindingRegistry();
        registry.add(binding("code", "Код", false, new TextField()));
        registry.add(binding("name", "Наименование", false, new TextField()));

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.getBinding("code")).isPresent();
        assertThat(registry.getBinding("missing")).isEmpty();
        assertThat(registry.getAll()).hasSize(2);
        assertThatThrownBy(() -> registry.getAll().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void applyAllToEntityWritesComponentValuesIntoEntity() {
        FormBindingRegistry registry = new FormBindingRegistry();
        TextField code = new TextField();
        registry.add(binding("code", "Код", false, code));
        code.setValue("A-1");

        Map<String, Object> entity = new HashMap<>();
        registry.applyAllToEntity(entity);

        assertThat((String) entity.get("code")).isEqualTo("A-1");
    }

    @Test
    void readAllFromEntityPopulatesComponentsAndResetsDirty() {
        FormBindingRegistry registry = new FormBindingRegistry();
        TextField code = new TextField();
        registry.add(binding("code", "Код", false, code));

        Map<String, Object> entity = new HashMap<>();
        entity.put("code", "A-1");
        registry.readAllFromEntity(entity);

        assertThat(code.getValue()).isEqualTo("A-1");
        assertThat(registry.isDirty()).isFalse();
    }

    @Test
    void dirtyTracksComponentChangeSinceLastReadAndMarkClean() {
        FormBindingRegistry registry = new FormBindingRegistry();
        TextField code = new TextField();
        registry.add(binding("code", "Код", false, code));

        Map<String, Object> entity = new HashMap<>();
        entity.put("code", "A-1");
        registry.readAllFromEntity(entity);
        assertThat(registry.isDirty()).isFalse();

        code.setValue("B-2");
        assertThat(registry.isDirty()).isTrue();

        registry.markClean();
        assertThat(registry.isDirty()).isFalse();
    }

    @Test
    void requiredFieldValidationReportedByLabel() {
        FormBindingRegistry registry = new FormBindingRegistry();
        TextField code = new TextField();
        registry.add(binding("code", "Наименование", true, code));

        assertThat(registry.isValid()).isFalse();
        assertThat(registry.validate())
            .containsExactly("Наименование: обязательно для заполнения");

        code.setValue("X");
        assertThat(registry.isValid()).isTrue();
        assertThat(registry.validate()).isEmpty();
    }

    @Test
    void nonRequiredEmptyFieldIsValid() {
        FormBindingRegistry registry = new FormBindingRegistry();
        registry.add(binding("code", "Код", false, new TextField()));

        assertThat(registry.isValid()).isTrue();
    }

    /**
     * Авто-нумеруемое поле (@Numbered): форма не должна блокировать сохранение пустого
     * значения — код присвоит хук нумерации в сервисе (Indexed, в UI поле видно с
     * placeholder'ом, required не выставляется).
     */
    @Test
    void requiredNumberedFieldIsValidEvenWhenEmpty() throws Exception {
        Field field = NumberedEntity.class.getDeclaredField("code");
        FieldMetadataInfo info = new FieldMetadataInfo(field,
            field.getAnnotation(FieldMetadata.class));
        TextField code = new TextField();
        FormBindingRegistry registry = new FormBindingRegistry();
        registry.add(FormBinding.forMetadata(info, code,
            entity -> ((NumberedEntity) entity).code,
            (entity, value) -> ((NumberedEntity) entity).code = (String) value,
            code::getValue,
            value -> {
                if (value == null) {
                    code.clear();
                } else {
                    code.setValue((String) value);
                }
            },
            value -> value == null || ((String) value).isBlank(),
            readOnly -> {
            }
        ));

        assertThat(registry.isValid()).isTrue();
        assertThat(registry.validate()).isEmpty();

        code.setValue("A-1");
        assertThat(registry.isValid()).isTrue();
    }

    private static class NumberedEntity {

        @Numbered
        @FieldMetadata(label = "Код", required = true)
        private String code;
    }

    @Test
    void setReadOnlyAppliesToAllBindingsAndEmptyRegistryReportsFalse() {
        FormBindingRegistry empty = new FormBindingRegistry();
        assertThat(empty.isReadOnly()).isFalse();

        FormBindingRegistry registry = new FormBindingRegistry();
        registry.add(binding("code", "Код", false, new TextField()));
        registry.add(binding("name", "Наименование", false, new TextField()));

        registry.setReadOnly(true);
        assertThat(registry.isReadOnly()).isTrue();

        registry.setReadOnly(false);
        assertThat(registry.isReadOnly()).isFalse();
    }

    @Test
    void duplicateFieldNamesAreRejected() {
        FormBindingRegistry registry = new FormBindingRegistry();
        FormBinding first = binding("code", "Код", false, new TextField());
        FormBinding second = binding("code", "Код 2", false, new TextField());

        registry.add(first);

        assertThatThrownBy(() -> registry.add(second))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("code");
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.getBinding("code")).containsSame(first);
    }

    @Test
    void externalBindingValidatesAndTracksDirty() {
        FormBindingRegistry registry = new FormBindingRegistry();
        TextField external = new TextField();
        registry.add(FormBinding.forExternal(
            new BindingDescriptor("sku", "Артикул", true),
            external,
            e -> ((Map<?, ?>) e).get("sku"),
            (e, v) -> ((Map) e).put("sku", v),
            external::getValue,
            v -> external.setValue((String) v),
            v -> v == null || v.toString().isEmpty(),
            external::setReadOnly));

        assertThat(registry.isValid()).isFalse();
        assertThat(registry.validate())
            .containsExactly("Артикул: обязательно для заполнения");

        Map<String, Object> entity = new HashMap<>();
        entity.put("sku", "S-1");
        registry.readAllFromEntity(entity);
        assertThat(external.getValue()).isEqualTo("S-1");
        assertThat(registry.isDirty()).isFalse();

        external.setValue("S-2");
        assertThat(registry.isDirty()).isTrue();

        registry.setReadOnly(true);
        assertThat(registry.isReadOnly()).isTrue();
        assertThat(external.isReadOnly()).isTrue();
    }

    @Test
    void externalBindingRejectsDuplicateKey() {
        FormBindingRegistry registry = new FormBindingRegistry();
        registry.add(externalBinding("sku", new TextField()));

        assertThatThrownBy(() -> registry.add(externalBinding("sku", new TextField())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sku");
    }

    @Test
    void externalBindingReportsEmptyMetadataAndDescriptor() {
        FormBinding binding = externalBinding("sku", new TextField());

        assertThat(binding.getMetadata()).isEmpty();
        assertThat(binding.getFieldName()).isEqualTo("sku");
        assertThat(binding.getDescriptor().label()).isEqualTo("sku");
    }

    @SuppressWarnings("unchecked")
    private FormBinding externalBinding(String key, TextField field) {
        return FormBinding.forExternal(
            new BindingDescriptor(key, null, false),
            field,
            e -> ((Map<?, ?>) e).get(key),
            (e, v) -> ((Map) e).put(key, v),
            field::getValue,
            v -> field.setValue((String) v),
            v -> v == null || v.toString().isEmpty(),
            b -> field.setReadOnly(b));
    }

    @SuppressWarnings("unchecked")
    private FormBinding binding(String fieldName, String label, boolean required, TextField field) {
        FieldMetadataInfo meta = mock(FieldMetadataInfo.class);
        when(meta.getName()).thenReturn(fieldName);
        when(meta.getLabel()).thenReturn(label);
        when(meta.isRequired()).thenReturn(required);
        return new FormBinding(meta, field,
            e -> ((Map<?, ?>) e).get(fieldName),
            (e, v) -> ((Map) e).put(fieldName, v),
            field::getValue,
            v -> field.setValue((String) v),
            v -> v == null || v.toString().isEmpty(),
            b -> field.setReadOnly(b));
    }
}
