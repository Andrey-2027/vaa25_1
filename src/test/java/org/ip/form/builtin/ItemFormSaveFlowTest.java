package org.ip.form.builtin;

import com.vaadin.flow.component.textfield.TextField;
import org.ip.application.form.FormSaveHandler;
import org.ip.application.form.FormSaveResult;
import org.ip.form.FieldFactory;
import org.ip.form.FormBinding;
import org.ip.form.FormBindingRegistry;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.crud.BaseEntity;
import org.ipro.telemetry.api.OperationScope;
import org.ipro.telemetry.api.Telemetry;
import org.ipro.telemetry.core.TelemetryBridge;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Сохранение через {@link ItemForm#save()} (спецификация «Часть C.1/C.2»):
 * валидация до обработчика, Failure без исключений наружу, честный doSave(),
 * telemetry-owner (ui:save-intent вместо бизнес-события save:<entity>).
 */
class ItemFormSaveFlowTest {

    @Test
    void saveWithoutHandlerReturnsFailureWithMessage() {
        ItemForm<TestDocument> form = formWithMetadata();

        FormSaveResult<TestDocument> result = form.save();

        assertThat(result.success()).isFalse();
        assertThat(((FormSaveResult.Failure<TestDocument>) result).messages()).isNotEmpty();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void saveSkipsHandlerWhenValidationFails() throws Exception {
        ItemForm<TestDocument> form = formWithMetadata();
        FormSaveHandler handler = mock(FormSaveHandler.class);
        form.setSaveHandler(handler);
        TextField nameField = new TextField();
        registry(form).add(new FormBinding(
            fieldMetadata("name", true),
            nameField,
            e -> "",
            (e, v) -> {
            },
            nameField::getValue,
            v -> nameField.setValue((String) v),
            v -> v == null || v.toString().isEmpty(),
            nameField::setReadOnly));

        FormSaveResult<TestDocument> result = form.save();

        assertThat(result.success()).isFalse();
        assertThat(((FormSaveResult.Failure<TestDocument>) result).messages())
            .anyMatch(m -> m.contains("name"));
        verify(handler, never()).save(form);
    }

    @Test
    void saveReturnsHandlerSuccessVerbatim() {
        ItemForm<TestDocument> form = formWithMetadata();
        TestDocument saved = new TestDocument();
        FormSaveHandler<TestDocument> handler = mock(FormSaveHandler.class);
        when(handler.save(form)).thenReturn(new FormSaveResult.Success<>(saved));
        form.setSaveHandler(handler);

        FormSaveResult<TestDocument> result = form.save();

        assertThat(result.success()).isTrue();
        assertThat(((FormSaveResult.Success<TestDocument>) result).saved()).isSameAs(saved);
    }

    @Test
    void saveWrapsHandlerExceptionInFailure() {
        ItemForm<TestDocument> form = formWithMetadata();
        FormSaveHandler<TestDocument> handler = mock(FormSaveHandler.class);
        IllegalStateException boom = new IllegalStateException("boom");
        when(handler.save(form)).thenThrow(boom);
        form.setSaveHandler(handler);

        FormSaveResult<TestDocument> result = form.save();

        assertThat(result.success()).isFalse();
        assertThat(((FormSaveResult.Failure<TestDocument>) result).messages())
            .anyMatch(m -> m.contains("boom"));
        assertThat(((FormSaveResult.Failure<TestDocument>) result).cause()).isSameAs(boom);
    }

    @Test
    void doSaveWithHandlerReturnsHandlerResultAndEmitsIntentScopeOnly() {
        Telemetry telemetry = mock(Telemetry.class);
        when(telemetry.beginOperation("ui:save-intent:TestDocument"))
            .thenReturn(OperationScope.noop());
        TelemetryBridge.set(telemetry);
        try {
            ItemForm<TestDocument> form = formWithMetadata();
            FormSaveHandler<TestDocument> handler = mock(FormSaveHandler.class);
            when(handler.save(form)).thenReturn(new FormSaveResult.Success<>(new TestDocument()));
            form.setSaveHandler(handler);

            assertThat(form.doSave()).isTrue();

            verify(telemetry).beginOperation("ui:save-intent:TestDocument");
            verify(telemetry, never()).beginOperation("save:TestDocument");
            verify(handler).save(form);
        } finally {
            TelemetryBridge.set(null);
        }
    }

    @Test
    void doSaveFallsBackToLegacyOnSaveForRowDialogs() {
        ItemForm<TestDocument> form = formWithMetadata();
        AtomicBoolean ran = new AtomicBoolean();
        form.setOnSave(() -> ran.set(true));

        assertThat(form.doSave()).isTrue();
        assertThat(ran).isTrue();
    }

    private static ItemForm<TestDocument> formWithMetadata() {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(TestDocument.class).when(metadata).getEntityClass();
        when(metadata.getFormFields()).thenReturn(List.of());
        return new ItemForm<>(metadata, mock(FieldFactory.class));
    }

    private static FormBindingRegistry registry(ItemForm<TestDocument> form) throws Exception {
        Field field = ItemForm.class.getDeclaredField("registry");
        field.setAccessible(true);
        return (FormBindingRegistry) field.get(form);
    }

    private static FieldMetadataInfo fieldMetadata(String name, boolean required) {
        FieldMetadataInfo meta = mock(FieldMetadataInfo.class);
        when(meta.getName()).thenReturn(name);
        when(meta.getLabel()).thenReturn(name);
        when(meta.isRequired()).thenReturn(required);
        return meta;
    }

    public static class TestDocument extends BaseEntity {
    }
}
