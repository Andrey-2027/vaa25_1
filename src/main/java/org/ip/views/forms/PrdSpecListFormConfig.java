package org.ip.views.forms;

import org.ipro.form.builder.ListFormCustomization;
import org.ipro.form.builder.ListFormVariants;
import org.ip.model.PrdSpec;
import org.springframework.stereotype.Component;


/**
 * Конфигурация формы списка спецификаций.
 *
 * <p>Обязательный журнал объявлен в filterPanel; подпись и редактор выводятся
 * из метаданных. Пустой журнал блокирует создание, сохраняя просмотр всех записей.</p>
 */
@Component
public class PrdSpecListFormConfig implements ListFormCustomization {

    @Override
    public Class<?> entityClass() {
        return PrdSpec.class;
    }

    @Override
    public void configure(ListFormVariants variants) {
        variants.filterPanel(variants.filter("journal").setRequired(true));
        // default-вариант — generic; «выбор журнала» идёт через панель контекст-фильтров.
        variants.addView("contextual", ctx -> new PrdSpecByJournalView(
            ctx.formNavigator(),
            ctx.entityLookup(),
            ctx));
    }

}