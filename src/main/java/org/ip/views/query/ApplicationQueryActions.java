package org.ip.views.query;

import jakarta.persistence.EntityManagerFactory;
import org.ipro.crud.ValidationException;
import org.ipro.data.EntityDataAccess;
import org.ipro.form.action.ActionDecision;
import org.ipro.form.action.ActionResolvers;
import org.ipro.form.action.CrudAction;
import org.ipro.form.action.NavigationCommandAvailability;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.query.list.QueryListDefinition.Command;
import org.ipro.query.list.QueryListRegistry;
import org.ipro.query.ui.grid.QueryReferenceOpener;
import org.ipro.query.ui.listform.QueryListCommands;
import org.ipro.query.ui.listform.QueryListCommands.Decision;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
class ApplicationQueryActions implements QueryReferenceOpener {
    private static final String UNAVAILABLE = "Запись недоступна. Обновите список и попробуйте снова.";
    private static final Decision ALLOWED = new Decision(true, null);

    private final EntityManagerFactory entities;
    private final EntityDataAccess data;
    private final ActionResolvers actions;
    private final ObjectProvider<FormNavigator> forms;
    private final NavigationCommandAvailability availability;
    private final MetadataResolver metadata;
    private final QueryListRegistry registry;
    private final QueryWorkbenchAccess access;

    ApplicationQueryActions(EntityManagerFactory entities, EntityDataAccess data, ActionResolvers actions,
                            ObjectProvider<FormNavigator> forms, NavigationCommandAvailability availability,
                            MetadataResolver metadata, QueryListRegistry registry, QueryWorkbenchAccess access) {
        this.entities = entities;
        this.data = data;
        this.actions = actions;
        this.forms = forms;
        this.availability = availability;
        this.metadata = metadata;
        this.registry = registry;
        this.access = access;
    }

    QueryListCommands forList(QueryListRegistry.Entry entry) {
        var object = entry.definition().commandObject();
        var type = object == null ? null : entities.getMetamodel().getEntities().stream()
                .map(entity -> entity.getJavaType())
                .filter(candidate -> candidate.getName().equals(object.entityClass()))
                .findFirst().map(ApplicationQueryActions::formType).orElse(null);
        return new QueryListCommands() {
            @Override
            public Decision decide(Command command, Long objectId) {
                return apply(command, objectId, null);
            }

            @Override
            public Decision execute(Command command, Long objectId, Runnable refresh) {
                if (!access.allows() || registry.findOpenable(entry.key())
                        .filter(current -> current.launchKey().equals(entry.launchKey())).isEmpty()) {
                    return new Decision(true, "Список изменился или стал недоступен. Откройте его снова.");
                }
                return apply(command, objectId, refresh);
            }

            private Decision apply(Command command, Long objectId, Runnable refresh) {
                if (!entry.definition().commands().contains(command)) {
                    return new Decision(false, "Команда недоступна для этого списка.");
                }
                if (type == null) return new Decision(true, "У списка не указана основная сущность.");
                if (command != Command.CREATE && object.column() == null) {
                    return new Decision(true, "У списка не указана колонка объекта.");
                }
                return checkAndRun(type, command, objectId, refresh);
            }
        };
    }

    private Decision checkAndRun(Class<IdentifiableEntity<Long>> type, Command command, Long id, Runnable refresh) {
        try {
            var resolver = actions.list(type, null);
            var action = CrudAction.valueOf(command.name());
            boolean create = command == Command.CREATE;
            var row = create || id == null ? null : data.detail(type, id).orElse(null);
            if (!create && id != null && row == null) return new Decision(true, UNAVAILABLE);
            var decision = resolver.decide(action, row, !create || !availability.hasRequiredContext(type, null));
            if (!decision.actionable()) return refusal(decision);
            if (!create && row == null) return new Decision(true, "Выберите запись в списке.");
            if (refresh == null) return ALLOWED;
            switch (command) {
                case CREATE, EDIT -> forms.getObject().openItemForm(type, null, create ? null : id,
                        saved -> refresh.run(), null);
                case OPEN -> forms.getObject().openReadOnlyItemForm(type, id);
                case DELETE -> {
                    data.delete(type, id);
                    refresh.run();
                }
            }
            return ALLOWED;
        } catch (AccessDeniedException denied) {
            return new Decision(true, UNAVAILABLE);
        } catch (DataIntegrityViolationException conflict) {
            return new Decision(true, "Не удалось удалить запись. Возможно, она используется в других данных.");
        } catch (ValidationException invalid) {
            String reason = invalid.getMessage();
            return new Decision(true, reason == null || reason.isBlank() ? "Не удалось выполнить команду." : reason);
        }
    }

    @Override
    public String open(Class<?> entityType, long id) {
        var type = formType(entityType);
        if (type == null || !access.allows()) return UNAVAILABLE;
        try {
            var row = data.detail(type, id).orElse(null);
            if (row == null) return UNAVAILABLE;
            var resolver = actions.list(type, null);
            var edit = resolver.decide(CrudAction.EDIT, row, true);
            if (edit.actionable()) {
                forms.getObject().openItemForm(type, null, id, null, null);
                return null;
            }
            var open = resolver.decide(CrudAction.OPEN, row, true);
            if (open.actionable()) {
                forms.getObject().openReadOnlyItemForm(type, id);
                return null;
            }
            return refusal(edit.visible() ? edit : open).blockReason();
        } catch (AccessDeniedException denied) {
            return UNAVAILABLE;
        }
    }

    @Override
    public String typeName(Class<?> type) {
        return metadata.find(type).map(EntityMetadataInfo::getItemFormTitle).orElse("запись");
    }

    private static Decision refusal(ActionDecision decision) {
        String reason = decision.message();
        return new Decision(decision.visible(), reason == null || reason.isBlank() ? "Команда недоступна." : reason);
    }

    @SuppressWarnings("unchecked")
    private static Class<IdentifiableEntity<Long>> formType(Class<?> type) {
        return IdentifiableEntity.class.isAssignableFrom(type) ? (Class<IdentifiableEntity<Long>>) type : null;
    }
}
