package org.ipro.form.registry;

import org.ipro.identity.IdentifiableEntity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Реестр команд списка: собирает все Spring-бины {@link ListCommand} и отдаёт их по сущности.
 * Область применения конкретного варианта проверяется координатором через
 * {@link ListCommand#appliesToVariant(String)}.
 */
@Component
public class ListCommandRegistry {

    private final List<ListCommand<?>> commands;

    public ListCommandRegistry(List<ListCommand<?>> commands) {
        this.commands = List.copyOf(commands);
    }

    /** Команды, зарегистрированные для указанной сущности. */
    public List<ListCommand<?>> byEntity(Class<?> entityClass) {
        return commands.stream()
            .filter(c -> c.entityClass().equals(entityClass))
            .toList();
    }
}
