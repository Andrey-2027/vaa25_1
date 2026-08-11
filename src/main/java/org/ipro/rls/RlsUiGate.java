package org.ipro.rls;

import java.util.List;
import java.util.Map;

/**
 * Ответы "что разрешено делать с сущностью" для UI (кнопки, формы, tooltips) — без
 * зависимости от Vaadin, по образцу write-guard'а сервисов (см. AbstractBaseService.
 * checkRls), но без исключений: возвращает {@link AccessDecision} с причиной для
 * tooltip вместо throw. Серверный write-guard остаётся последней линией и не
 * ослабляется.
 *
 * Правила (зеркалят сервисы):
 * <ul>
 * <li>{@link #canCreate(Class)} — AND по ВСЕМ {@code @RlsDimension} класса:
 *     {@code accessService.canUpdate(dim, null, user)}. Для self-keyed измерения
 *     (Journal) null — "создание нового значения измерения"; для зависимого
 *     (PrdSpec наследует от Journal) — тоже null: через существующий построчный
 *     грант with update (ветка "dimensionValueId == null && grant.dimensionValueId
 *     != null" в AccessService.hasAccess) и bootstrap. Для CHECK_ONLY (например,
 *     "ENTITY:ReceivingDocument") — без гранта всегда false: bootstrap на этот род
 *     не действует (AccessService.isNewDimensionValueAllowed).</li>
 * <li>{@link #canUpdate(Object)} / {@link #canDelete(Object)} — AND-цикл по
 *     {@link RlsDimensionValue#getRlsChecks()} для каждой проверки каждого измерения
 *     (NotApplicable пропускается автоматически, как в checkRls).</li>
 * <li>Сущности без {@code @RlsDimension} / без {@code implements RlsDimensionValue} —
 *     все операции разрешены (как в сервисах).</li>
 * <li>{@link RlsContext#isBypassed()} — всё разрешено (фоновые задачи, системный
 *     контекст).</li>
 * </ul>
 */
public class RlsUiGate {

    public record AccessDecision(boolean allowed, String reason) {
        public static final AccessDecision ALLOWED = new AccessDecision(true, "");
    }

    private final AccessService accessService;
    private final RlsDimensionRegistry dimensionRegistry;
    private final RlsCurrentUser currentUser;

    public RlsUiGate(AccessService accessService, RlsDimensionRegistry dimensionRegistry,
                     RlsCurrentUser currentUser) {
        this.accessService = accessService;
        this.dimensionRegistry = dimensionRegistry;
        this.currentUser = currentUser;
    }

    /** Разрешено ли пользователю СОЗДАТЬ новый экземпляр класса — см. javadoc класса. */
    public AccessDecision canCreate(Class<?> entityClass) {
        if (RlsContext.isBypassed()) {
            return AccessDecision.ALLOWED;
        }
        String username = currentUser.username();
        for (String dimension : dimensionRegistry.dimensionsOf(entityClass)) {
            if (!accessService.canUpdate(dimension, null, username)) {
                return new AccessDecision(false,
                    "Нет прав на создание (измерение " + dimension + ")");
            }
        }
        return AccessDecision.ALLOWED;
    }

    public AccessDecision canUpdate(Object entity) {
        return canWrite(entity, PermissionCheck.CAN_UPDATE, "изменение");
    }

    public AccessDecision canDelete(Object entity) {
        return canWrite(entity, PermissionCheck.CAN_DELETE, "удаление");
    }

    private AccessDecision canWrite(Object entity, PermissionCheck permission, String actionName) {
        if (RlsContext.isBypassed() || !(entity instanceof RlsDimensionValue rdv)) {
            return AccessDecision.ALLOWED;
        }
        String username = currentUser.username();
        for (Map.Entry<String, List<RlsCheckValue>> entry : rdv.getRlsChecks().entrySet()) {
            String dimension = entry.getKey();
            for (RlsCheckValue check : entry.getValue()) {
                if (check instanceof RlsCheckValue.NotApplicable) {
                    continue; // сознательно не участвует в этом измерении — пройдено автоматически
                }
                Long id = ((RlsCheckValue.Check) check).id();
                if (!permission.test(accessService, dimension, id, username)) {
                    return new AccessDecision(false, "Нет прав на " + actionName +
                        " (измерение " + dimension +
                        (id != null ? ", id=" + id : ", создание новой записи") + ")");
                }
            }
        }
        return AccessDecision.ALLOWED;
    }

    @FunctionalInterface
    private interface PermissionCheck {
        boolean test(AccessService accessService, String dimension, Long dimensionValueId, String username);

        PermissionCheck CAN_UPDATE = AccessService::canUpdate;
        PermissionCheck CAN_DELETE = AccessService::canDelete;
    }
}