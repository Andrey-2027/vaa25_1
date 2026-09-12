package org.ipro.reportstudio.param;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.ipro.crud.BaseEntity;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsContext;

/**
 * Построитель безопасного контекста запуска из экранов форм и списков сущностей.
 *
 * <p>Фабрика не читает поля сущностей и не обходит RLS. Она передаёт только
 * класс, идентификатор текущей сущности, выбранные идентификаторы и viewId;
 * {@link ReportParamResolver} повторно загружает ENTITY/ENTITY_LIST через
 * защищённые сервисы в момент запуска.</p>
 *
 * <p>Spring-bean с инъекцией {@link RlsCurrentUser} (тот же SPI, что у
 * ReportParamResolver/EntityParamRefresher) и {@link Clock} — детерминируемые
 * тесты; регистрируется в ReportStudioAutoConfiguration (план
 * reportstudio-reverse-deps, 2.2 — вместо статического CurrentUser.username()).</p>
 */
public class ReportContextFactory {

    private final RlsCurrentUser currentUser;
    private final Clock clock;

    public ReportContextFactory(RlsCurrentUser currentUser, Clock clock) {
        this.currentUser = currentUser;
        this.clock = clock;
    }

    public ReportContext empty(String viewId) {
        return context(null, null, List.of(), viewId);
    }

    public ReportContext forEntity(BaseEntity entity, String viewId) {
        if (entity == null) {
            return empty(viewId);
        }
        Object entityId = entity.getId();
        return context(entity.getClass(), entityId, entityId == null ? List.of() : List.of(entityId), viewId);
    }

    public ReportContext forSelection(
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

    public ReportContext forEntities(Collection<? extends BaseEntity> entities, String viewId) {
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

    private ReportContext context(Class<?> entityClass, Object entityId, List<?> selectedIds, String viewId) {
        return ReportContext.of(entityClass, entityId, selectedIds, viewId,
            RlsContext.isBypassed()
                ? currentUser.username()
                : currentUser.requireAuthenticatedUsername(),
            clock.instant());
    }
}
