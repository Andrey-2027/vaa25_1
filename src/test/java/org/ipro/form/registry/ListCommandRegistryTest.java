package org.ipro.form.registry;

import org.ip.model.Nomenclature;
import org.ip.model.Workshop;
import org.ipro.identity.IdentifiableEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D3.5.3: снимок команд неизменяем, двойная регистрация одного класса команды
 * на одну сущность — fail-fast.
 */
class ListCommandRegistryTest {

    @Test
    void sameCommandClassTwiceOnOneEntityFailsFast() {
        // Один и тот же класс дважды (два бина-определения): порядок Spring-бинов
        // не должен задавать скрытый приоритет.
        assertThatThrownBy(() -> new ListCommandRegistry(List.of(
                new OpenCommand("Открыть"), new OpenCommand("Открыть копию"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Дубликат ListCommand")
            .hasMessageContaining("Nomenclature");
    }

    @Test
    void differentCommandsAndEntitiesPass() {
        ListCommandRegistry registry = new ListCommandRegistry(List.of(
            command(Nomenclature.class, "Открыть"),
            otherCommand(Nomenclature.class, "Печать"),
            command(Workshop.class, "Открыть")));

        assertThat(registry.byEntity(Nomenclature.class)).hasSize(2);
        assertThat(registry.byEntity(Workshop.class)).hasSize(1);
    }

    private static final class OpenCommand implements ListCommand<Nomenclature> {
        private final String title;

        private OpenCommand(String title) {
            this.title = title;
        }

        @Override
        public Class<Nomenclature> entityClass() {
            return Nomenclature.class;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public void execute(ListCommandContext<Nomenclature> context) {
        }
    }

    private static <T extends IdentifiableEntity> ListCommand<T> command(Class<T> entity, String title) {
        return new ListCommand<>() {
            @Override
            public Class<T> entityClass() {
                return entity;
            }

            @Override
            public String title() {
                return title;
            }

            @Override
            public void execute(ListCommandContext<T> context) {
            }
        };
    }

    private static <T extends IdentifiableEntity> ListCommand<T> otherCommand(Class<T> entity, String title) {
        return new ListCommand<>() {
            @Override
            public Class<T> entityClass() {
                return entity;
            }

            @Override
            public String title() {
                return title;
            }

            @Override
            public void execute(ListCommandContext<T> context) {
            }
        };
    }
}
