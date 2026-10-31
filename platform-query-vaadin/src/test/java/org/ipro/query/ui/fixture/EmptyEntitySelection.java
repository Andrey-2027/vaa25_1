package org.ipro.query.ui.fixture;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.HasDisplayName;
import org.ipro.query.ui.selection.EntitySelection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * M1.3: тестовый порт выбора для модульных тестов. Раньше редактор поднимался в
 * тестах приложения и брал {@code org.ipro.reportstudio.query.adapter.EmptyEntitySelection};
 * теперь артефакт проверяет себя сам, а прикладной адаптер остаётся у потребителя.
 * Пустые колонки означают «поиск без подсказок» — само поле остаётся рабочим.
 */
public final class EmptyEntitySelection implements EntitySelection {

    @Override
    public List<ColumnPath> columns(Class<?> entityClass) {
        return List.of();
    }

    @Override
    public Field field(String label, Class<?> entityClass) {
        TextField text = new TextField(label);
        return new Field() {
            private HasDisplayName value;
            private final List<Consumer<HasDisplayName>> listeners = new ArrayList<>();

            @Override
            public Component component() {
                return text;
            }

            @Override
            public HasDisplayName value() {
                return value;
            }

            @Override
            public void setValue(HasDisplayName next) {
                value = next;
                text.setValue(next == null ? "" : String.valueOf(next));
                listeners.forEach(listener -> listener.accept(next));
            }

            @Override
            public void addValueChangeListener(Consumer<HasDisplayName> listener) {
                listeners.add(listener);
            }
        };
    }
}
