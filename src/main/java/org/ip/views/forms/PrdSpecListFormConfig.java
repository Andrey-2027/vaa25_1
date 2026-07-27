package org.ip.views.forms;

import org.ip.form.builder.FormBuilder;
import org.ip.form.builder.ListFormCustomization;
import org.ip.form.builder.ListFormVariants;
import org.ip.model.PrdSpec;
import org.springframework.stereotype.Component;

/**
 * Кастомизация формы списка спецификаций.
 * Default-вариант открывает кастомный View с фильтром по журналу.
 */
@Component
public class PrdSpecListFormConfig implements ListFormCustomization {

    @Override
    public Class<?> entityClass() {
        return PrdSpec.class;
    }

    @Override
    public void configure(ListFormVariants variants) {
        // Default-вариант — кастомный View с ComboBox для выбора журнала
        variants.addDefault(
            FormBuilder.listForm(PrdSpec.class)
                .customView(PrdSpecByJournalView.class)
        );
    }
}
