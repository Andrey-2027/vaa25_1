package org.ipro.form.coordinator;

import org.ipro.form.action.ActionDecision;
import org.ipro.form.action.ActionResolver;
import org.ipro.form.action.CrudAction;
import org.ipro.form.action.ReadOnlyReason;
import org.ipro.form.builtin.ItemForm;
import org.ipro.identity.IdentifiableEntity;

/**
 * Применение решения по действию к форме элемента (E1.5) — оба пути открытия (диалог через
 * {@code FormCoordinator.openItemFormAsDialog} и вкладка через {@code ItemFormWrapperView.init})
 * пользуются одним и тем же binder'ом:
 *
 * <ul>
 * <li>создание (id == null): {@link #blockReasonIfCannotCreate(ActionResolver)} — если решение по
 *     {@code crud.create} не разрешает операцию, форма НЕ открывается, а пользователь видит
 *     названную причину. Раньше здесь проверялся только RLS ({@code canCreate}), поэтому тип без
 *     generic {@code CREATE} всё равно открывал карточку создания;</li>
 * <li>существующая запись: {@link #applyReadOnlyIfCannotSave(ItemForm, ActionResolver)} — если
 *     решение по {@code crud.save} не разрешает сохранение, форма переводится в режим просмотра с
 *     <b>типизированной</b> причиной (поля и табличные части read-only, «Сохранить» скрыта, а бейдж
 *     «Только просмотр: …» показывается только для отказа в правах).</li>
 * </ul>
 *
 * <p><b>Почему решение о режиме — это решение о сохранении.</b> Карточка правится ровно тогда,
 * когда её можно сохранить; отдельного «права на редактирование» в модели нет. Поэтому режим
 * определяется тем же решением, которым потом руководствуется кнопка «Сохранить». Это закрывает
 * дефект, найденный в E1.0: у типа с {@code DETAIL} без {@code UPDATE} (и без RLS) карточка
 * открывалась редактируемой, а сохранение отклонял серверный canonical write.</p>
 *
 * <p>Входы решения (capability типа, права пользователя) собирает {@code ActionContextProvider},
 * поэтому у binder'а нет собственных коллабораторов и собственной формулы прав: он переводит
 * готовое решение в форму и в причину режима. Серверный write-guard (общая RLS-граница:
 * repository-aspect + Hibernate flush-listener) остаётся последней линией и НЕ ослабляется:
 * UI-блокировка — только удобство (параллель с 1С), не защита.</p>
 *
 * <p>Регистрация бина — в {@code FormAutoConfiguration}, без условий: binder выражает контракт wiring
 * доступа, и его отсутствие обязано ронять старт с названной причиной (D3.5.5).</p>
 */
public class ItemFormAccessBinder {

    /**
     * @return null — создание разрешено; иначе причина запрета (для showError).
     *
     * <p>Ожидаемый контекст — заполненным: правило обязательного контекста при прямом открытии
     * ({@code openItemForm(..., id=null)}) решается отдельно (§4.1.5 плана) и в E1.5 не меняется.
     * Здесь проверяется то, что можно проверить без списка: capability {@code CREATE} типа и права
     * пользователя.</p>
     */
    public String blockReasonIfCannotCreate(ActionResolver listResolver) {
        ActionDecision decision = listResolver.decide(CrudAction.CREATE, null, true);
        return decision.actionable() ? null : decision.message();
    }

    /**
     * Решение о режиме просмотра по состоянию объекта. Состояние определяется самим объектом
     * (запись без {@code id} — ещё не сохранённая), а не тем, кто его передал: см.
     * {@code ActionContextProvider.rowContext}.
     *
     * @param itemResolver решатель поверхности {@code ITEM_FOOTER} для типа и варианта карточки
     * @param row          запись карточки; {@code null} — карточка создаёт новую запись
     * @return null — карточку можно править; иначе причина режима просмотра
     */
    public ReadOnlyReason readOnlyReason(ActionResolver itemResolver, Object row) {
        return readOnlyReasonOf(itemResolver.decide(CrudAction.SAVE, row, true));
    }

    /**
     * Переводит форму в режим просмотра, если запись сохранить нельзя. Новую (ещё не сохранённую)
     * карточку не трогает: её сохранение разрешает решение о создании, и режим просмотра здесь
     * означал бы «создавать нельзя» — отказ, а не просмотр.
     *
     * @return применённая причина либо {@code null}, если форма осталась редактируемой
     */
    public ReadOnlyReason applyReadOnlyIfCannotSave(ItemForm<?> form, ActionResolver itemResolver) {
        ReadOnlyReason reason = readOnlyReason(itemResolver, form.getEntity());
        if (reason != null) {
            form.setReadOnly(reason);
        }
        return reason;
    }

    /**
     * Отображение решения в причину режима просмотра — единственное место этого отображения.
     *
     * <p>Отказ в правах ({@code ACCESS_DENIED}) сохраняет текст решения: пользователю нужно знать,
     * почему запись недоступна на изменение. Все остальные причины (тип не поддерживает операцию,
     * действие подавлено, нет выделения, не собран контекст, у формы нет адреса) дают
     * <b>нейтральный</b> режим просмотра: права в этих случаях никто не отказывал, и сообщение о
     * правах было бы ложью. Для карточки {@code NO_SELECTION} и {@code CONTEXT_INCOMPLETE}
     * практически недостижимы — выделение обеспечено самой карточкой, а контекст сохранения
     * проверяет форма, — но отображение тотально, поэтому новый вид причины не сможет молча
     * превратиться в «нет прав».</p>
     *
     * <p>{@code NOT_LINKABLE} здесь — ветвь для тотальности: сохранение адреса не требует, и
     * требование адреса у него было бы ошибкой композиции. Нейтральный режим просмотра — всё равно
     * правильный ответ: прав никто не отказывал, а выдавать эту причину за отказ в правах значило
     * бы объяснять заблокированную карточку тем, чего не происходило.</p>
     *
     * @return null — запись можно править
     */
    public static ReadOnlyReason readOnlyReasonOf(ActionDecision decision) {
        return switch (decision.reason()) {
            case NONE -> null;
            case ACCESS_DENIED -> ReadOnlyReason.accessDenied(decision.message());
            case TYPE_NOT_SUPPORTED, NOT_APPLICABLE, NO_SELECTION, CONTEXT_INCOMPLETE, NOT_LINKABLE ->
                ReadOnlyReason.typeReadOnly(decision.message());
        };
    }
}
