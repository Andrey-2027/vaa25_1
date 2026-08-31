package org.ip.views.forms;

import org.ip.form.builder.ContextFilterField;
import org.ip.form.builder.ListFormCustomization;
import org.ip.form.builder.ListFormVariants;
import org.ip.model.Journal;
import org.ip.model.PrdSpec;
import org.ip.form.registry.FormContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Конфигурация формы списка спецификаций.
 *
 * <p>Раньше default-вариант открывал рукописный {@code PrdSpecByJournalView}: ComboBox «Журнал»
 * + {@code ListForm.setContextFilter}. Теперь тот же «выбор журнала» выражается декларативно через
 * панель контекст-фильтров {@code ListForm} ({@link #contextFilters()}): Журнал — сущность,
 * поэтому она выбирается формой выбора ({@code ContextFilterField.select} → SelectionForm),
 * панель рендерится механизмом, а не Java-классом под каждую сущность. default-вариант остаётся generic.</p>
 */
@Component
public class PrdSpecListFormConfig implements ListFormCustomization {

    @Override
    public Class<?> entityClass() {
        return PrdSpec.class;
    }

    @Override
    public void configure(ListFormVariants variants) {
        // default-вариант — generic; «выбор журнала» идёт через панель контекст-фильтров.
        variants.addView("contextual", ctx -> new PrdSpecByJournalView(
            (org.ip.form.coordinator.FormCoordinator) ctx.getParameter("coordinator"),
            ctx.lookupService(),
            ctx));
    }

    @Override
    public List<ContextFilterField> contextFilters() {
        return List.of(ContextFilterField.select("journal", "Журнал", Journal.class));
    }
}