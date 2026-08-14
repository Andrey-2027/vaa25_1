package org.ipro.reportstudio.param;

import org.ip.security.CurrentUser;
import org.ipro.crud.BaseEntity;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Построитель безопасного контекста запуска из экранов форм и списков сущностей.
 *
 * <p>Фабрика не читает поля сущностей и не обходит RLS. Она передаёт только
 * класс, идентификатор текущей сущности, выбранные идентификаторы и viewId;
 * {@link ReportParamResolver} повторно загружает ENTITY/ENTITY_LIST через
 * защищённые сервисы в момент запуска.</p>
 */
public final class ReportContextFactory {

    private ReportContextFactory() {
    }

    public static ReportContext empty(String viewId) {
        return context(null, null, List.of(), viewId);
    }

    public static ReportContext forEntity(BaseEntity entity, String viewId) {
        if (entity == null) {
            return empty(viewId);
        }
        Object entityId = entity.getId();
        return context(entity.getClass(), entityId, entityId == null ? List.of() : List.of(entityId), viewId);
    }

    public static ReportContext forSelection(
            Class<?> entityClass,
            Object currentEntityId,
            Collection<?> selectedIds,
            String viewId) {
        List<Object> ids = selectedIds == null ? List.of() : selectedIds.stream()
                .filter(Objects::nonNull)
                .map(Object.class::cast)
                .toList();
        return context(entityClass, currentEntityId, ids, viewId);
    }

    public static ReportContext forEntities(Collection<? extends BaseEntity> entities, String viewId) {
        if (entities == null || entities.isEmpty()) {
            return empty(viewId);
        }
        BaseEntity first = entities.stream().filter(Objects::nonNull).findFirst().orElse(null);
        if (first == null) {
            return empty(viewId);
        }
        List<Object> ids = entities.stream()
                .filter(Objects::nonNull)
                .map(BaseEntity::getId)
                .filter(Objects::nonNull)
                .map(Object.class::cast)
                .toList();
        return context(first.getClass(), first.getId(), ids, viewId);
    }

    private static ReportContext context(Class<?> entityClass, Object entityId, List<?> selectedIds, String viewId) {
        return ReportContext.of(entityClass, entityId, selectedIds, viewId, CurrentUser.username(), Instant.now());
    }
}
