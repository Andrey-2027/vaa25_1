package org.ipro.form;

import com.vaadin.flow.component.icon.VaadinIcon;
import org.ipro.form.action.ActionId;
import org.ipro.form.builder.ListCommandLayout;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Командная панель диалоговой формы и карточки элемента: {@link CommandBar} с раскладкой «все
 * команды на панели», порядком по номеру (затем по регистрации), видом «только иконка» по запросу
 * и автоскрытием, пока нет ни одной видимой команды.
 *
 * <p>Служебный класс платформы: приложение работает с {@link FormCommand}, а не с панелью.</p>
 */
public final class CommandPanel {

    /** Порядок команд, объявленных потребителем: после встроенных команд карточки. */
    public static final int CUSTOM_ORDER_BASE = 100;

    private final CommandBar bar = new CommandBar();
    private final Set<ActionId> iconOnlyIds = new LinkedHashSet<>();
    private int counter;

    public CommandPanel() {
        bar.setVisible(false);
        bar.setLayout(ListCommandLayout.builder().allOnPanel().build());
        bar.setHideWhenNoVisibleCommands(true);
    }

    public CommandBar bar() {
        return bar;
    }

    /** Обычная команда панели; порядок — по регистрации среди команд потребителя. */
    public FormCommand add(ActionId id, String title, VaadinIcon icon, Runnable handler) {
        return wrap(id, bar.register(id, title, icon, handler), CUSTOM_ORDER_BASE + counter++);
    }

    /** Встроенная команда с явным порядком (меньше — левее). */
    public FormCommand add(ActionId id, String title, VaadinIcon icon, Runnable handler, int order) {
        return wrap(id, bar.register(id, title, icon, handler), order);
    }

    /** Акцентная команда (primary) с явным порядком. */
    public FormCommand addPrimary(ActionId id, String title, VaadinIcon icon, Runnable handler, int order) {
        return wrap(id, bar.registerPrimary(id, title, icon, handler), order);
    }

    /** Разрушительная команда (красная). */
    public FormCommand addDanger(ActionId id, String title, VaadinIcon icon, Runnable handler) {
        return wrap(id, bar.registerDanger(id, title, icon, handler), CUSTOM_ORDER_BASE + counter++);
    }

    /** Команда только для «Ещё»; кнопки на панели у неё нет. */
    public FormCommand addMenu(ActionId id, String title, Runnable handler) {
        return wrap(id, bar.addMenuCommand(id, title, handler), CUSTOM_ORDER_BASE + counter++);
    }

    /** Команда в подменю «Сервис» внутри «Ещё». */
    public FormCommand addService(ActionId id, String title, Runnable handler) {
        return wrap(id, bar.addMenuCommand(id, title, handler, CommandBar.Group.SERVICE),
            CUSTOM_ORDER_BASE + counter++);
    }

    private FormCommand wrap(ActionId id, CommandBar.Command registered, int order) {
        registered.setDefinitionOrder(order);
        return new FormCommand(registered, id, iconOnlyIds, this::rebuildLayout);
    }

    private void rebuildLayout() {
        ListCommandLayout.Builder builder = ListCommandLayout.builder().allOnPanel();
        for (ActionId id : iconOnlyIds) {
            builder.command(id).iconOnly();
        }
        bar.setLayout(builder.build());
    }
}
