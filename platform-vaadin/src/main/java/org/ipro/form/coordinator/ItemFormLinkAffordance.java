package org.ipro.form.coordinator;

import com.vaadin.flow.component.notification.Notification;
import org.ipro.form.action.ActionDecision;
import org.ipro.form.action.ActionResolver;
import org.ipro.form.action.CopyLinkButton;
import org.ipro.form.action.CrudAction;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.link.FormLinkService;
import org.ipro.identity.IdentifiableEntity;

/**
 * Подключение ссылки на запись в подвал карточки (E2.1, ADR-0009 §5): один код для вкладки
 * Workspace и для диалога.
 *
 * <p>Карточка собирается в двух местах ({@code ItemFormWrapperView} и
 * {@code FormCoordinator#openItemFormAsDialog}), и «скопировать ссылку» обязано вести себя
 * одинаково в обоих: разойтись здесь означало бы, что адрес открытой записи зависит от того,
 * каким путём её открыли. Поэтому встраивание вынесено в одну функцию, а не в две похожие
 * строки.</p>
 *
 * <p><b>Что именно общее.</b> Ленивая привязка к снимку записи: id спрашивается в момент клика
 * ({@code peekEntity()}, без создания нового объекта), потому что у только что созданной записи
 * адрес появляется лишь после сохранения. До сборки записи кнопки нет вовсе — карточка без
 * entity не «неадресуема по типу», у неё просто нет адресата, и решение об этом не выносится:
 * {@code ActionResolver} трактует {@code row == null} как контекст <i>списка</i>, а спрашивать
 * про список у подвала карточки нельзя.</p>
 *
 * <p><b>Почему отказ показывается здесь.</b> Кнопка и так неактивна, когда решение отрицательно;
 * уведомление нужно для окна между перерисовкой и кликом (запись удалили, права изменились) —
 * молчаливый клик по видимой кнопке выглядел бы как «ссылка скопирована».</p>
 */
public final class ItemFormLinkAffordance {

    private ItemFormLinkAffordance() {
    }

    /**
     * Добавить в подвал карточки кнопку «Скопировать ссылку» и вернуть её.
     *
     * <p>Возвращённую кнопку обязан перерисовать host после успешного сохранения
     * ({@code refresh()}): у новой записи адрес появляется вместе с id, а без перерисовки кнопка
     * осталась бы скрытой до переоткрытия карточки.</p>
     *
     * @param form        карточка, в подвал которой встраивается affordance
     * @param itemResolver решатель поверхности {@code ITEM_FOOTER} для типа и варианта карточки
     * @return кнопка — её состояние host пересчитывает после сохранения
     */
    public static CopyLinkButton attach(ItemForm<?> form, FormLinkService formLinkService,
                                        ActionResolver itemResolver, Class<?> entityType,
                                        String variant) {
        CopyLinkButton button = CopyLinkButton.forRecord("Скопировать ссылку", formLinkService,
            entityType, variant,
            () -> idOf(form.peekEntity()),
            () -> {
                Object entity = form.peekEntity();
                if (entity == null) {
                    return ActionDecision.hidden(ActionDecision.Reason.NOT_APPLICABLE,
                        "Запись карточки ещё не собрана: адресовать нечего");
                }
                return itemResolver.decide(CrudAction.COPY_LINK, entity, true);
            },
            decision -> Notification.show(decision.message(), 4000, Notification.Position.MIDDLE));
        // Слева от «Отмена»/«Сохранить»: действие не завершает карточку, поэтому не встаёт
        // на место основных кнопок подвала.
        form.getFooter().addComponentAsFirst(button);
        return button;
    }

    /** Id записи либо {@code null}, если он ещё не присвоен (или тип не объявляет идентичность). */
    private static Long idOf(Object entity) {
        return entity instanceof IdentifiableEntity identifiable ? identifiable.getId() : null;
    }
}
