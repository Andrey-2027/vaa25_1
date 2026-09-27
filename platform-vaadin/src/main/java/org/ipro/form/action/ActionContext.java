package org.ipro.form.action;

import org.ipro.data.EntityCapabilities;

import java.util.Objects;

/**
 * Immutable снимок контекста, по которому принимается решение (E1.1).
 *
 * <p>Контекст содержит только то, что нужно <b>решению</b>: тип и вариант формы, effective
 * capabilities типа, права текущего пользователя, наличие выделения и заполненность
 * обязательного контекста. Ни {@code ListForm}, ни {@code Button}, ни {@code ApplicationContext}
 * здесь нет — предметный исполнитель и форма остаются на стороне UI (E1.3).</p>
 *
 * <p>Что появится позже: строка и её id для исполнителя (E1.3, поток строки в UI-контексте),
 * dirty-состояние карточки (E1.5, действия карточки). Добавлять их сейчас значило бы описать
 * решение для действий, которых нет.</p>
 *
 * <p><b>Адресный вход (E2.1).</b> {@code linkability == null} означает «вход не подключён», а не
 * «адреса нет»: это тот же разбор, что у {@link ActionPermission#NO_ROW_REASON} — «не оценено»
 * отличается от «отказано». Действие, объявившее требование адреса, на таком контексте падает с
 * названной причиной композиции, а не прячется: спрятать его значило бы выдать отсутствие
 * настроенного входа за отсутствие адреса у типа.</p>
 *
 * @param entityType              класс сущности списка/карточки
 * @param variant                 вариант формы ({@code null} = default)
 * @param capabilities            effective capabilities типа (из {@code EntityDescriptorCatalog})
 * @param permission              права пользователя на операции
 * @param hasSelection            выбрана ли строка
 * @param requiredContextComplete заполнен ли обязательный контекст открытия
 * @param rowState                есть ли уже объект, к которому относится решение (E1.5)
 * @param linkability             строится ли адрес того, к чему относится действие (E2.1);
 *                                {@code null} — адресный вход не подключён (см. ниже)
 */
public record ActionContext(Class<?> entityType,
                            String variant,
                            EntityCapabilities capabilities,
                            ActionPermission permission,
                            boolean hasSelection,
                            boolean requiredContextComplete,
                            RowState rowState,
                            RouteLinkability linkability) {

    /**
     * Состояние объекта, к которому относится действие (E1.5).
     *
     * <p>Нужно там, где одна запись действия обслуживает и создание, и изменение
     * ({@code crud.save}): операция, к которой предъявляется требование, выбирается этим
     * состоянием, а не ветвлением по {@code id} в политике.</p>
     */
    public enum RowState {

        /** Объекта ещё нет: карточка создаёт новую запись, строка списка не выделена. */
        NEW,

        /** Объект существует: карточка правит загруженную запись. */
        EXISTING
    }

    public ActionContext {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        Objects.requireNonNull(permission, "permission must not be null");
        Objects.requireNonNull(rowState, "rowState must not be null");
    }

    /** Контекст без выделения и без обязательного контекста — типичный старт списка. */
    public static ActionContext of(Class<?> entityType, EntityCapabilities capabilities,
                                   ActionPermission permission) {
        return new ActionContext(entityType, null, capabilities, permission, false, false,
            RowState.EXISTING, null);
    }

    public ActionContext withVariant(String variant) {
        return new ActionContext(entityType, variant, capabilities, permission,
            hasSelection, requiredContextComplete, rowState, linkability);
    }

    public ActionContext withSelection(boolean hasSelection) {
        return new ActionContext(entityType, variant, capabilities, permission,
            hasSelection, requiredContextComplete, rowState, linkability);
    }

    public ActionContext withRequiredContextComplete(boolean complete) {
        return new ActionContext(entityType, variant, capabilities, permission,
            hasSelection, complete, rowState, linkability);
    }

    public ActionContext withRowState(RowState state) {
        return new ActionContext(entityType, variant, capabilities, permission,
            hasSelection, requiredContextComplete, state, linkability);
    }

    /** Копия с адресным входом (E2.1). */
    public ActionContext withLinkability(RouteLinkability routeLinkability) {
        return new ActionContext(entityType, variant, capabilities, permission,
            hasSelection, requiredContextComplete, rowState, routeLinkability);
    }
}
