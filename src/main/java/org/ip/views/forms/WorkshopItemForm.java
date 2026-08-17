package org.ip.views.forms;

import com.vaadin.flow.component.html.Span;
import org.ip.form.FieldFactory;
import org.ip.form.builder.layout.ItemFormLayout;
import org.ip.form.builder.layout.CustomNode;
import org.ip.form.builder.layout.DisplayNode;
import org.ip.form.builder.layout.FieldNode;
import org.ip.form.builtin.ItemForm;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.model.Workshop;

import java.util.List;

/**
 * Форма Элемента для {@link Workshop} на layout-DSL (спецификация «Часть D.5», PR-1.2).
 *
 * <p>Подкласс остаётся ради бизнес-логики, но НЕ переопределяет lifecycle
 * ({@code setEntity/getEntity/isDirty}): все поля — через биндинги.</p>
 *
 * <ul>
 *   <li>{@code id} — {@link DisplayNode}: read-only вывод {@code BaseEntity.id} (в метаданных
 *       его нет — поле унаследовано), обновляется автоматически из {@code setEntity()}
 *       через displayRefreshers;</li>
 *   <li>{@code code} — обычное {@link FieldNode} из метаданных;</li>
 *   <li>{@code name} — {@link FieldNode} с {@code labelOverride} "Наименование1": подпись
 *       формы и сообщение required-валидации берутся из {@code BindingDescriptor.label};</li>
 *   <li>{@code hint} — {@link CustomNode}: независимый UI (текст-подсказка), в registry
 *       не регистрируется.</li>
 * </ul>
 */
public class WorkshopItemForm extends ItemForm<Workshop> {

    public WorkshopItemForm(EntityMetadataInfo metadata, FieldFactory fieldFactory) {
        super(metadata, fieldFactory, new ItemFormLayout(List.of(
            new DisplayNode("id", "ID",
                entity -> entity instanceof Workshop w && w.getId() != null
                    ? w.getId().toString() : ""),
            new FieldNode("code"),
            new FieldNode("name", "Наименование1"),
            new CustomNode(hint())
        )));
        initBusinessLogic();
    }

    private void initBusinessLogic() {
        getEntityField("name", String.class).addValueChangeListener(e -> {
            if (e.isFromClient() && e.getValue() != null && e.getValue().contains("Фин")) {
                setReadOnly(true);
            }
        });
    }

    private static Span hint() {
        Span hint = new Span(
            "Цех — справочник для полей \"Цех приёмщик\"/\"Цех сдатчик\" в приёмно-сдаточных накладных.");
        hint.getStyle()
            .set("font-size", "var(--lumo-font-size-s)")
            .set("color", "var(--lumo-secondary-text-color)");
        return hint;
    }
}
