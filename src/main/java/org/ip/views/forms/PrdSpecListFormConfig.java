package org.ip.views.forms;

import org.ip.form.builder.ListFormCustomization;
import org.ip.form.builder.ListFormVariants;
import org.ip.model.PrdSpec;
import org.springframework.stereotype.Component;

/**
 * Кастомизация формы списка спецификаций.
 * Default-вариант открывает кастомный View с фильтром по журналу (композиция —
 * PrdSpecByJournalView сам строит ListForm внутри себя через coordinator.createListForm()
 * и добавляет ComboBox для выбора журнала через ListForm.setContextFilter(), см. класс).
 */
@Component
public class PrdSpecListFormConfig implements ListFormCustomization {

    @Override
    public Class<?> entityClass() {
        return PrdSpec.class;
    }

    @Override
    public void configure(ListFormVariants variants) {
        variants.addDefaultView(PrdSpecByJournalView.class);
    }
}
