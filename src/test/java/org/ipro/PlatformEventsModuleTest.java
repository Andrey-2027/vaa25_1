package org.ipro;

import org.ipro.events.EntityEventPublisher;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2 (runtime slice), прикладная сторона: модуль представился контейнеру сам.
 *
 * <p><b>Что здесь осталось.</b> Состав runtime-среза и его compile-зависимости переехали к
 * владельцу — {@code platform-events/src/test} (состав, зависимости, поведение publisher'а,
 * поведение реестра и провод автоконфигурации). Прикладной тест держит то, что проверяется
 * только с обеих сторон сразу:</p>
 * <ol>
 * <li>авто-конфигурация модуля зарегистрирована ровно один раз и <b>не</b> в приложении —
 *     требование roadmap «extraction не увеличивает число обязательных registrations»;</li>
 * <li>классы контура приходят из артефакта, а не из {@code target/classes} приложения.</li>
 * </ol>
 */
class PlatformEventsModuleTest {

    private static final Path MODULE = Path.of("platform-events");

    private static final String IMPORTS_RESOURCE =
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** Авто-конфигурации, которые модуль обязан регистрировать сам. */
    private static final Set<String> MODULE_AUTO_CONFIGURATIONS =
        Set.of("org.ipro.events.config.EventsAutoConfiguration");

    @Test
    void moduleRegistersItsOwnAutoConfigurationAndTheApplicationDoesNot() {
        Path moduleImports = MODULE.resolve("src/main/resources").resolve(IMPORTS_RESOURCE);
        assertThat(moduleImports)
            .as("без собственного imports-файла модуль не представится контейнеру, и потеря"
                + " контура будет тихой")
            .exists();
        assertThat(lines(moduleImports)).containsExactlyInAnyOrderElementsOf(MODULE_AUTO_CONFIGURATIONS);

        List<String> appImports = lines(Path.of("src/main/resources").resolve(IMPORTS_RESOURCE));
        assertThat(appImports)
            .as("требование roadmap: extraction не увеличивает число обязательных registrations"
                + " в приложении — авто-конфигурация модуля не должна быть перечислена в"
                + " приложении, иначе приложение снова знает о внутренностях модуля")
            .doesNotContainAnyElementsOf(MODULE_AUTO_CONFIGURATIONS);
    }

    @Test
    void runtimeClassesComeFromTheArtifactAndNotFromTheApplicationTree() {
        assertThat(Path.of("src/main/java/org/ipro/events/EntityEventPublisher.java"))
            .as("класс контура обязан исчезнуть из дерева приложения")
            .doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/lifecycle/EntityLifecycleRegistry.java"))
            .as("реестр — часть контура: он тоже выехал в модуль, вместе со своим проводом")
            .doesNotExist();

        URL location = EntityEventPublisher.class.getProtectionDomain()
            .getCodeSource().getLocation();
        assertThat(location.toString())
            .as("владение классами проверяется там, где оно важно — в загруженном артефакте:"
                + " класс контура должен приходить из platform-events, а не из target/classes"
                + " приложения")
            .contains(MODULE.getFileName().toString());
    }

    private static List<String> lines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
