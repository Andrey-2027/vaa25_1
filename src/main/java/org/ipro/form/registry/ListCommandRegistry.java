package org.ipro.form.registry;

import org.ipro.identity.IdentifiableEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Реестр команд списка: собирает все Spring-бины {@link ListCommand} и отдаёт их по сущности.
 * Область применения конкретного варианта проверяется координатором через
 * {@link ListCommand#appliesToVariant(String)}.
 *
 * <p>D3.5.3: снимок неизменяем после старта ({@code List.copyOf}), а двойная регистрация
 * одного класса команды на одну сущность — fail-fast: порядок Spring-бинов не задаёт
 * скрытый приоритет, неоднозначность обязана быть явной.</p>
 */
@Component
public class ListCommandRegistry {

    private final List<ListCommand<?>> commands;

    public ListCommandRegistry(List<ListCommand<?>> commands) {
        this.commands = List.copyOf(failOnDuplicates(commands));
    }

    private static List<ListCommand<?>> failOnDuplicates(List<ListCommand<?>> commands) {
        Map<String, String> seen = new LinkedHashMap<>();
        List<String> clashes = new ArrayList<>();
        for (ListCommand<?> command : commands) {
            if (command == null) {
                continue;
            }
            String key = command.entityClass().getName() + "#" + command.getClass().getName();
            String previous = seen.putIfAbsent(key, command.title());
            if (previous != null) {
                clashes.add(command.entityClass().getSimpleName() + " <- "
                    + command.getClass().getName() + " (заголовки: «" + previous + "» и «"
                    + command.title() + "»)");
            }
        }
        if (!clashes.isEmpty()) {
            throw new IllegalStateException("Дубликат ListCommand: один класс команды "
                + "зарегистрирован дважды на одну сущность: " + String.join("; ", clashes));
        }
        return commands;
    }

    /** Команды, зарегистрированные для указанной сущности. */
    public List<ListCommand<?>> byEntity(Class<?> entityClass) {
        return commands.stream()
            .filter(c -> c.entityClass().equals(entityClass))
            .toList();
    }
}
