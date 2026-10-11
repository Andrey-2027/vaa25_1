package org.ip.views.register;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.crud.BaseEntity;
import org.ipro.form.FormBindingRegistry;
import org.ipro.form.FormMessages;
import org.ipro.form.registry.FormContext;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.ColumnPath;
import org.ipro.register.RegisterDescriptor;
import org.ipro.register.RegisterKeyChanges;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;

/** Edits only the key in a separate draft. The selected row never becomes a write buffer. */
final class RegisterKeyDialog extends Dialog {
    RegisterKeyDialog(RegisterDescriptor register, BaseEntity row, FormContext context,
                      RegisterKeyChanges changes, Runnable saved) {
        setHeaderTitle("Изменить ключ записи");
        setWidth("620px");
        FormBindingRegistry bindings = new FormBindingRegistry();
        var metadata = context.metadataResolver().resolve(register.entityType());
        VerticalLayout fields = new VerticalLayout();
        fields.setPadding(false);
        for (var dimension : register.dimensions())
            fields.add(context.fieldFactory().createField(metadata.getFieldByName(dimension.name()), bindings));
        register.period().ifPresent(period -> fields.add(
            context.fieldFactory().createField(metadata.getFieldByName(period.name()), bindings)));
        bindings.readAllFromEntity(row);
        add(fields);
        Button save = new Button("Сохранить", event -> {
            if (!bindings.isValid()) {
                showMessage("Заполните измерения и период. Проверьте введённые значения.");
                return;
            }
            try {
                BaseEntity draft = (BaseEntity) register.entityType().getDeclaredConstructor().newInstance();
                bindings.applyAllToEntity(draft);
                var values = new LinkedHashMap<String, Object>();
                for (var dimension : register.dimensions()) {
                    Object value = ColumnPath.resolve(register.entityType(), dimension.name()).getValue(draft);
                    values.put(dimension.name(), value instanceof IdentifiableEntity<?> reference ? reference.getId() : value);
                }
                LocalDateTime period = register.period().map(field -> (LocalDateTime)
                    ColumnPath.resolve(register.entityType(), field.name()).getValue(draft)).orElse(null);
                change(changes, register.entityType(), row, values, period);
                close();
                saved.run();
            } catch (RuntimeException failure) {
                if (!FormMessages.showAccessDenied(failure, "Не удалось изменить ключ записи", context.metadataResolver()))
                    showMessage(failure.getMessage() == null ? "Не удалось изменить ключ записи. Обновите список." : failure.getMessage());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Не удалось создать черновик ключа регистра", failure);
            }
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        getFooter().add(new Button("Отмена", event -> close()), save);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void change(RegisterKeyChanges service, Class<?> type, BaseEntity row,
                               java.util.Map<String, Object> dimensions, LocalDateTime period) {
        service.change((Class) type, row.getId(), row.getVersion(), dimensions, period);
    }
    static void showMessage(String message) {
        FormMessages.showError(message);
    }
}
