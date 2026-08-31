package org.ip.views.forms;

import org.ip.form.coordinator.FormCoordinator;
import org.ip.form.registry.ListCommand;
import org.ip.form.registry.ListCommandContext;
import org.ip.model.PrdSpec;
import org.springframework.stereotype.Component;

/**
 * Пилот механизма команд списка ({@link ListCommand}): row-команда «Только материалы»
 * в реестре Спецификаций. Открывает выбранную спецификацию в варианте {@code materials-only}
 * через {@link FormCoordinator} и получает контекст исходного списка через
 * {@link ListCommandContext}.
 *
 * <p>Если команда понадобится только одному составному View, её предпочтительнее собрать
 * локальным {@code createCommand()} этого View, а не регистрировать глобально.</p>
 */
@Component
public class PrdSpecMaterialsOnlyCommand implements ListCommand<PrdSpec> {

    @Override
    public Class<PrdSpec> entityClass() {
        return PrdSpec.class;
    }

    @Override
    public String title() {
        return "Только материалы";
    }

    @Override
    public String iconName() {
        return "LIST";
    }

    @Override
    public boolean requiresSelection() {
        return true;
    }

    @Override
    public void execute(ListCommandContext<PrdSpec> context) {
        PrdSpec selected = context.selectedItem();
        if (selected == null) {
            return;
        }
        context.coordinator().openItemForm(PrdSpec.class, "materials-only", selected.getId(),
            saved -> context.listForm().refresh(), null);
    }
}
