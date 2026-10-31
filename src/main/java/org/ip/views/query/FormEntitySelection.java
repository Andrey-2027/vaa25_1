package org.ip.views.query;

import com.vaadin.flow.component.Component;
import org.ipro.crud.EntityLookup;
import org.ipro.form.EntityField;
import org.ipro.form.SelectionForm;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.query.ui.selection.EntitySelection;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * M1.2: единственное место, где query UI встречается с формой платформы.
 *
 * <p>Редактор запроса живёт в {@code platform-query-vaadin} и знает только порт
 * {@link EntitySelection}. Этот адаптер — прикладная сторона шва: он собирает поле
 * из {@link EntityField} и {@link SelectionFormAssembler} ровно так, как это делают
 * формы приложения. Поэтому выбор ссылки в запросе выглядит и ведёт себя как везде,
 * а артефакт не тянет {@code org.ipro.form} и Spring-граф формы.</p>
 *
 * <p>R3.2: адаптер не знает UDR DOM и живёт в прикладном query-UI, поэтому
 * удаление {@code reportstudio} не задевает ни рабочий стол запросов, ни UniDR.</p>
 */
public final class FormEntitySelection implements EntitySelection {

    private final SelectionFormAssembler assembler;
    private final EntityLookup lookup;

    public FormEntitySelection(SelectionFormAssembler assembler, EntityLookup lookup) {
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    @Override
    public List<ColumnPath> columns(Class<?> entityClass) {
        return assembler.resolveColumns(entityClass).columns();
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Field field(String label, Class<?> entityClass) {
        List<String> searchFields = columns(entityClass).stream()
                .filter(path -> path.getResolvedType() == FieldType.TEXT)
                .map(ColumnPath::getKey)
                .toList();
        EntityField<HasDisplayName> field = new EntityField<>(label,
                term -> (List<HasDisplayName>) (List) lookup.search(entityClass, searchFields, term, 20));
        field.setSelectionFormFactory(onSelect ->
                (SelectionForm<HasDisplayName>) (SelectionForm) assembler.assemble((Class) entityClass, (Consumer) onSelect));
        return new Field() {
            @Override
            public Component component() {
                return field;
            }

            @Override
            public HasDisplayName value() {
                return field.getValue();
            }

            @Override
            public void setValue(HasDisplayName value) {
                field.setValue(value);
            }

            @Override
            public void addValueChangeListener(Consumer<HasDisplayName> listener) {
                field.addValueChangeListener(listener);
            }
        };
    }
}
