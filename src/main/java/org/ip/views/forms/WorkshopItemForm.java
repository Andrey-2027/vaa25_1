package org.ip.views.forms;

import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextField;
import org.ip.form.FieldFactory;
import org.ip.form.builtin.ItemForm;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.model.Workshop;

import java.util.List;

/**
 * Полностью написанная вручную Форма Элемента для {@link Workshop} — пример прямого кода
 * (composition, не наследование от общего DSL) для случая, когда декларативных полей/панелей
 * недостаточно и нужен произвольный Java-код внутри формы.
 *
 * Два примера "мимо" стандартного механизма:
 *   - {@code hint} — просто текст, узла для этого в дереве нет вообще.
 *   - {@code idField} — поле {@code BaseEntity.id}: у него нет {@code @FieldMetadata} (оно
 *     унаследовано от BaseEntity, а {@code MetadataResolver} сканирует только
 *     {@code getDeclaredFields()} самого {@code Workshop}, без родителей), поэтому через
 *     addField()/дерево его не получить в принципе — только вручную.
 *
 *     Оно read-only, поэтому обошлись без полноценного {@link org.ip.form.FormBinding}:
 *     переопределили {@link #setEntity} и прочитали значение напрямую. Если бы поле было
 *     редактируемым, пришлось бы либо самим зарегистрировать {@code FormBinding} через
 *     {@code getBindingRegistry().add(...)}, либо дополнительно переопределить
 *     {@link #getEntity()} и записать значение в сущность там.
 */
public class WorkshopItemForm extends ItemForm<Workshop> {

    // Без своего label в конструкторе — подпись даёт FormLayout.addFormItem(...) ниже, так же,
    // как и для code/name (иначе получится "заголовок сверху", а не в одну строку с полем).
    private final TextField idField = new TextField();
    private final TextField nameField = new TextField();

    // true, только если пользователь реально ввёл что-то в nameField (isFromClient()) —
    // отличает ручной ввод от программного setValue(...) внутри setEntity() ниже. Нужно для
    // isDirty(): registry (и, соответственно, ItemForm.isDirty()) про nameField не знает.
    private boolean nameFieldDirty = false;

    public WorkshopItemForm(EntityMetadataInfo metadata, FieldFactory fieldFactory) {
        super(metadata, fieldFactory, List.of("code"));

        idField.setReadOnly(true);
        // Добавляем в тот же FormLayout, что и code/name (а не в саму ItemForm через
        // addComponentAsFirst) — иначе поле не получит "заголовок слева, поле справа".
        FormLayout.FormItem idItem = getFormLayout().addFormItem(idField, "ID");
        FormLayout.FormItem nameItem = getFormLayout().addFormItem(nameField, "Наименование1");
        getFormLayout().addComponentAsFirst(idItem);

        nameField.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                nameFieldDirty = true;
            }
        });

        Span hint = new Span(
            "Цех — справочник для полей \"Цех приёмщик\"/\"Цех сдатчик\" в приёмно-сдаточных накладных.");
        hint.getStyle()
            .set("font-size", "var(--lumo-font-size-s)")
            .set("color", "var(--lumo-secondary-text-color)");

        addComponentAsFirst(hint);
    }

    @Override
    public void setEntity(Workshop entity) {
        super.setEntity(entity);
        idField.setValue(entity != null && entity.getId() != null ? entity.getId().toString() : "");
        nameField.setValue(entity != null ? entity.getName() : "");
        nameFieldDirty = false; // открытие/переоткрытие формы — не пользовательский ввод
    }

    /**
     * Обратное направление для nameField: registry (FormBindingRegistry) о нём не знает, поэтому
     * super.getEntity() его не тронет — записываем значение сами, поверх того, что уже сделал
     * super.getEntity() (валидный, не-null entity — созданный через entityFactory/рефлексию,
     * если форма ещё не привязана к существующей записи).
     */
    @Override
    public Workshop getEntity() {
        Workshop entity = super.getEntity();
        entity.setName(nameField.getValue());
        return entity;
    }

    /**
     * super.isDirty() (через registry/snapshot) ничего не знает про nameField — добавляем
     * реальный ввод пользователя отдельно, вместо сравнения со snapshot (к нему нет доступа
     * из подкласса, а ValueChangeListener с isFromClient() даёт тот же результат проще).
     */
    @Override
    public boolean isDirty() {
        return super.isDirty() || nameFieldDirty;
    }
}
