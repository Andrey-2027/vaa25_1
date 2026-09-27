package org.ip.views.forms;

import org.ipro.form.action.ActionDefinition;
import org.ipro.form.action.ActionHandler;
import org.ipro.form.action.ActionId;
import org.ipro.form.action.ActionInvocation;
import org.ipro.form.action.ActionRequirement;
import org.ipro.form.action.ActionSurface;
import org.ip.model.PrdSpec;
import org.springframework.stereotype.Component;

/**
 * «Только материалы» — предметное действие строки списка Спецификаций (E1.6a): образец
 * переиспользуемого row action на новом контракте.
 *
 * <p>Что изменилось по сравнению с легаси-командой. Доступность больше не предикат по форме:
 * «нужна выбранная строка» выражено требованием ({@link ActionRequirement#selectionOnly()}), и его
 * проверяет та же политика, что считает CRUD — поэтому кнопка не может оказаться включённой по
 * одному правилу, а действие — исполненным по другому. Ключ варианта (в каком варианте списка
 * действие показывать) — тоже данные: {@link ActionDefinition#variant()}. Предметный вариант
 * {@code materials-only} остался в карточке: это параметр открытия, а не применимость действия.</p>
 *
 * <p>Исполнение получает снимок списка ({@link ActionInvocation}), а не форму и не
 * {@code ApplicationContext}: строка, параметры открытия, значения контекстных фильтров и узкий
 * {@link org.ipro.form.coordinator.FormNavigator}. Строка, исчезнувшая между решением и кликом,
 * — не исключение, а штатный случай: действие в этом случае не выполняется.</p>
 */
@Component
public class PrdSpecMaterialsOnlyAction implements ActionHandler {

    /** Действие, а не подпись: подпись можно менять, идентичность — нет. */
    public static final ActionId ID = ActionId.of("prdspec.materials-only");

    /** Вариант карточки спецификации: только материалы. */
    public static final String MATERIALS_ONLY_VARIANT = "materials-only";

    @Override
    public ActionDefinition definition() {
        return ActionDefinition.forEntity(ID, ActionSurface.LIST_TOOLBAR, PrdSpec.class,
            "Только материалы", "LIST", 100, ActionRequirement.selectionOnly());
    }

    @Override
    public void execute(ActionInvocation invocation) {
        PrdSpec selected = invocation.selectionAs(PrdSpec.class).orElse(null);
        if (selected == null || selected.getId() == null || invocation.navigator() == null) {
            return;
        }
        invocation.navigator().openItemForm(PrdSpec.class, MATERIALS_ONLY_VARIANT,
            selected.getId(),
            saved -> {
                if (invocation.canRefresh()) {
                    invocation.refresh().run();
                }
            },
            null);
    }
}
