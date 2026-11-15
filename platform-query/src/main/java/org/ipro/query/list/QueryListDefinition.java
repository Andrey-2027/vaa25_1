package org.ipro.query.list;

import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.filtergrid.projection.QueryProvenance;
import org.ipro.metadata.GridViewState;
import org.ipro.query.model.QueryDefinition;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record QueryListDefinition(String key, String title, QueryDefinition query, GridViewState standardView,
                                  ProjectionExecutionMode mode, CommandObject commandObject, Set<Command> commands,
                                  QueryProvenance provenance) {
    private static final Pattern KEY = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

    public enum Command { CREATE, OPEN, EDIT, DELETE }

    public record CommandObject(String entityClass, String column) {
        public CommandObject {
            if (entityClass == null || entityClass.isBlank()) {
                throw new IllegalArgumentException("Укажите основную сущность");
            }
            column = column == null || column.isBlank() ? null : column.strip();
        }
    }

    public QueryListDefinition {
        if (key == null || key.length() > 100 || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Ключ списка содержит строчные латинские буквы, цифры, точки и дефисы");
        }
        if (title == null || title.isBlank() || title.strip().length() > 200) {
            throw new IllegalArgumentException("Название списка должно содержать от 1 до 200 символов");
        }
        title = title.strip();
        Objects.requireNonNull(query, "query");
        standardView = standardView == null ? new GridViewState(List.of(), List.of()) : standardView;
        if (standardView.fixedFilter() != null) {
            throw new IllegalArgumentException("Обязательные условия задаются в запросе и параметрах");
        }
        mode = mode == null ? ProjectionExecutionMode.LAZY : mode;
        commands = Set.copyOf(commands == null ? Set.of() : commands);
        if (provenance != QueryProvenance.REGISTERED && provenance != QueryProvenance.DECLARED) {
            throw new IllegalArgumentException("Список требует зарегистрированное или объявленное определение");
        }
    }
}
