package org.ip.form.coordinator;

import org.ip.form.builtin.ItemForm;
import org.ipro.crud.IdentifiableEntity;
import org.ipro.rls.RlsUiGate;
import org.ipro.rls.RlsUiGate.AccessDecision;
import org.springframework.stereotype.Component;

/**
 * Применение RLS-прав к формам элемента (Фаза 4 RLS-плана) — оба пути открытия
 * (диалог через FormCoordinator.openItemFormAsDialog и вкладка через
 * ItemFormWrapperView.init) пользуются одним и тем же binder'ом:
 *
 * <ul>
 * <li>создание (id == null): {@link #blockReasonIfCannotCreate(Class)} — если
 *     canCreate(entityClass) запрещён, форму НЕ открываем вовсе, показываем причину
 *     («Нет прав на создание (измерение X)»);</li>
 * <li>существующая запись: {@link #applyReadOnlyIfCannotUpdate(ItemForm)} — если
 *     canUpdate(entity) запрещён, форма переводится в режим только просмотра
 *     (поля + табличные части read-only, «Сохранить» скрыта) и вверху показывается
 *     бейдж {code "Только просмотр: ...причина..."}.</li>
 * </ul>
 *
 * Серверный write-guard (AbstractBaseService.checkRlsWrite) остаётся последней
 * линией и НЕ ослабляется: UI-блокировка — только удобство (параллель с 1С), не
 * защита.
 */
@Component
public class ItemFormAccessBinder {

    private final RlsUiGate rlsUiGate;

    public ItemFormAccessBinder(RlsUiGate rlsUiGate) {
        this.rlsUiGate = rlsUiGate;
    }

    /**
     * @return null — создание разрешено; иначе причина запрета (для showError).
     */
    public String blockReasonIfCannotCreate(Class<?> entityClass) {
        AccessDecision decision = rlsUiGate.canCreate(entityClass);
        return decision.allowed() ? null : decision.reason();
    }

    /**
     * Переводит форму в режим только просмотра, если у текущего пользователя нет
     * права на изменение загруженной записи (новая/незагруженная — не трогает).
     */
    public <T extends IdentifiableEntity> void applyReadOnlyIfCannotUpdate(ItemForm<T> form) {
        T entity = form.getEntity();
        if (entity == null || entity.getId() == null) {
            return;
        }
        AccessDecision decision = rlsUiGate.canUpdate(entity);
        if (decision.allowed()) {
            return;
        }
        form.setReadOnly(true);
        form.setRlsReadOnlyNotice("Только просмотр: " + decision.reason());
    }
}