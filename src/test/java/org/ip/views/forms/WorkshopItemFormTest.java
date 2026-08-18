package org.ip.views.forms;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextField;
import org.ip.form.FieldFactory;
import org.ip.form.SelectionFormAssembler;
import org.ip.form.SelectionFormAssembler;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ip.model.Workshop;
import org.ip.service.LookupService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Приёмочный тест PR-1.2 «Workshop layout без overrides» (тестовая карта, стр. 211).
 *
 * <p>WorkshopItemForm строит layout на FieldNode/DisplayNode/CustomNode и НЕ переопределяет
 * lifecycle ({@code setEntity/getEntity/isDirty}) — всё через биндинги:</p>
 * <ul>
 *   <li>id — DisplayNode: read-only вывод BaseEntity.id (в метаданных его нет), обновляется
 *       автоматически при {@code setEntity()};</li>
 *   <li>name — FieldNode с labelOverride "Наименование1": подпись и сообщение required-валидации
 *       берутся из BindingDescriptor.label;</li>
 *   <li>hint — CustomNode: независимый UI, в registry не регистрируется.</li>
 * </ul>
 */
class WorkshopItemFormTest {

    private static FieldMetadataInfo fieldMetadata(String name) throws Exception {
        Field field = Workshop.class.getDeclaredField(name);
        return new FieldMetadataInfo(field, field.getAnnotation(FieldMetadata.class));
    }

    private static WorkshopItemForm form() throws Exception {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(Workshop.class).when(metadata).getEntityClass();
        doReturn(fieldMetadata("code")).when(metadata).getFieldByName("code");
        doReturn(fieldMetadata("name")).when(metadata).getFieldByName("name");

        FieldFactory fieldFactory =
            new FieldFactory(mock(LookupService.class), mock(SelectionFormAssembler.class));

        return new WorkshopItemForm(metadata, fieldFactory);
    }

    @Test
    void layoutWorksWithoutLifecycleOverrides() throws Exception {
        Set<String> declared = Arrays.stream(WorkshopItemForm.class.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());

        assertThat(declared).doesNotContain("setEntity", "getEntity", "isDirty", "setReadOnly");
    }

    @Test
    void idDisplayIsReadOnlyAndRefreshesOnSetEntity() throws Exception {
        WorkshopItemForm form = form();

        Workshop first = new Workshop("W-1", "Цех 1");
        first.setId(42L);
        form.setEntity(first);

        TextField idDisplay = idDisplay(form, "42");

        Workshop second = new Workshop("W-2", "Цех 2");
        second.setId(7L);
        form.setEntity(second);

        assertThat(idDisplay).isSameAs(idDisplay(form, "7"));
        assertThat(idDisplay.isReadOnly()).isTrue();
    }

    @Test
    void nameLoadsSavesAndTracksDirty() throws Exception {
        WorkshopItemForm form = form();

        Workshop workshop = new Workshop("W-1", "Цех 1");
        workshop.setId(42L);
        form.setEntity(workshop);

        TextField nameField = (TextField) form.getEntityField("name", String.class);
        assertThat(nameField.getValue()).isEqualTo("Цех 1");
        assertThat(form.isDirty()).isFalse();

        nameField.setValue("Цех 2");
        assertThat(form.isDirty()).isTrue();

        assertThat(form.getEntity().getName()).isEqualTo("Цех 2");
        assertThat(form.getEntity().getCode()).isEqualTo("W-1");
    }

    @Test
    void requiredValidationUsesOverriddenLabel() throws Exception {
        WorkshopItemForm form = form();

        form.setEntity(new Workshop("W-1", " "));

        assertThat(form.validate())
            .containsExactly("Наименование1: обязательно для заполнения");
    }

    @Test
    void hintCustomNodeIsRendered() throws Exception {
        WorkshopItemForm form = form();

        assertThat(flatten(form))
            .filteredOn(c -> c instanceof Span s
                && s.getText().startsWith("Цех — справочник для полей"))
            .hasSize(1);
    }

    // === Вспомогательное ===

    /** Read-only TextField из DisplayNode с заданным значением. */
    private static TextField idDisplay(WorkshopItemForm form, String expectedValue) {
        return flatten(form).stream()
            .filter(c -> c instanceof TextField tf)
            .map(c -> (TextField) c)
            .filter(tf -> tf.isReadOnly() && expectedValue.equals(tf.getValue()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "DisplayNode id со значением '" + expectedValue + "' не найден"));
    }

    private static List<Component> flatten(Component root) {
        List<Component> result = new ArrayList<>();
        root.getChildren().forEach(child -> {
            result.add(child);
            result.addAll(flatten(child));
        });
        return result;
    }
}
