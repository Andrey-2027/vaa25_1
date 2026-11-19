package org.ipro.form.coordinator;

import com.vaadin.flow.component.notification.Notification;
import org.ipro.form.CommandBar;
import org.ipro.form.action.ActionDecision;
import org.ipro.form.action.ActionResolver;
import org.ipro.form.action.CopyLinkAction;
import org.ipro.form.action.CrudAction;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.link.FormLinkService;
import org.ipro.identity.IdentifiableEntity;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Подключение ссылки на запись к командной панели карточки (E2.1, ADR-0009 §5): один код для
 * вкладки Workspace и для диалога. Команда «Скопировать ссылку» живёт в «Ещё» › «Сервис» — как у
 * списка ({@code ListForm.installCopyLinkButton}): действие не завершает карточку и не должно
 * стоять рядом с «Записать и закрыть».
 *
 * <p>Карточка собирается в двух местах ({@code ItemFormWrapperView} и
 * {@code FormCoordinator#openItemFormAsDialog}), и «скопировать ссылку» обязано вести себя
 * одинаково в обоих: разойтись здесь означало бы, что адрес открытой записи зависит от того,
 * каким путём её открыли. Поэтому встраивание вынесено в одну функцию, а не в две похожие
 * строки.</p>
 *
 * <p><b>Что именно общее.</b> Ленивая привязка к снимку записи: id спрашивается в момент клика
 * ({@code peekEntity()}, без создания нового объекта), потому что у только что созданной записи
 * адрес появляется лишь после сохранения. До сборки записи команды нет вовсе — карточка без
 * entity не «неадресуема по типу», у неё просто нет адресата, и решение об этом не выносится:
 * {@code ActionResolver} трактует {@code row == null} как контекст <i>списка</i>, а спрашивать
 * про список у карточки нельзя.</p>
 *
 * <p><b>Почему отказ показывается здесь.</b> Команда и так неактивна, когда решение отрицательно;
 * уведомление нужно для окна между перерисовкой и кликом (запись удалили, права изменились) —
 * молчаливый клик по видимой команде выглядел бы как «ссылка скопирована».</p>
 */
public final class ItemFormLinkAffordance {

    private ItemFormLinkAffordance() {
    }

    /**
     * Зарегистрировать на панели карточки команду «Скопировать ссылку» и вернуть её обновление.
     *
     * <p>Возвращённое действие обязан вызвать host после успешного сохранения: у новой записи адрес
     * появляется вместе с id, а без перерисовки команда осталась бы скрытой до переоткрытия
     * карточки. До присоединения формы к UI команда скрыта; состояние появляется вместе с живой
     * формой.</p>
     *
     * @param form         карточка, на панель которой встраивается affordance
     * @param itemResolver решатель поверхности {@code ITEM_FOOTER} для типа и варианта карточки
     * @return перерисовка состояния команды (после сохранения)
     */
    public static Runnable attach(ItemForm<?> form, FormLinkService formLinkService,
                                  ActionResolver itemResolver, Class<?> entityType,
                                  String variant) {
        Runnable refresh = attach(CommandBar.forForm(form), form::peekEntity, form::isAttached,
            formLinkService, itemResolver, entityType, variant,
            decision -> Notification.show(decision.message(), 4000, Notification.Position.MIDDLE));
        form.addAttachListener(event -> refresh.run());
        return refresh;
    }

    /** То же на готовой панели: без формы и без UI, чтобы проверяться отдельно. */
    static Runnable attach(CommandBar bar, Supplier<Object> entity, BooleanSupplier live,
                           FormLinkService formLinkService, ActionResolver itemResolver,
                           Class<?> entityType, String variant,
                           Consumer<ActionDecision> onBlocked) {
        Supplier<ActionDecision> decision = () -> {
            Object current = entity.get();
            if (current == null) {
                return ActionDecision.hidden(ActionDecision.Reason.NOT_APPLICABLE,
                    "Запись карточки ещё не собрана: адресовать нечего");
            }
            return itemResolver.decide(CrudAction.COPY_LINK, current, true);
        };
        CommandBar.Command command = bar.addMenuCommand(CrudAction.COPY_LINK.id(),
            "Скопировать ссылку",
            () -> CopyLinkAction.execute(decision,
                () -> formLinkService.linkToRecord(entityType, idOf(entity.get()), variant).linkPath(),
                onBlocked),
            CommandBar.Group.SERVICE);
        // До присоединения — скрыта и недоступна: состояние появится вместе с живой формой.
        command.setVisible(false);
        command.setEnabled(false);
        return () -> {
            if (!live.getAsBoolean()) {
                return;
            }
            ActionDecision current = decision.get();
            command.setVisible(current.visible());
            command.setEnabled(current.actionable());
            command.setTooltip(current.actionable() || current.message().isEmpty()
                ? null : current.message());
        };
    }

    /** Id записи либо {@code null}, если он ещё не присвоен (или тип не объявляет идентичность). */
    private static Long idOf(Object entity) {
        return entity instanceof IdentifiableEntity<?> identifiable
            && identifiable.getId() instanceof Long id ? id : null;
    }
}
