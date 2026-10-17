package org.ipro;

import org.ipro.register.RegisterMetadataValidator;
import org.ipro.register.RegisterRecords;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3.8 + D-R S5(a): что именно приносит backend-стартер потребителю без UI.
 *
 * <p>Проверка живёт в самом артефакте, а не в приложении, потому что она про его поставку:
 * разрешённое Maven-дерево {@code target/backend-starter-runtime-dependency-tree.json}
 * собирается сборкой этого модуля, поэтому расхождение «объявлено» и «приезжает» видно здесь,
 * а не только на сборке приложения (ADR-0024 §2.2, §5).</p>
 */
class BackendStarterResolvedDependenciesTest {

    private static final Path DEPENDENCY_TREE =
        Path.of("target/backend-starter-runtime-dependency-tree.json");
    private static final Pattern ARTIFACT_ID = Pattern.compile(
        "\"artifactId\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * ADR-0024 §2.2: движок регистра — часть backend-поставки.
     *
     * <p>Это то же ребро, которое убирает у потребителя строку сборки: объявив регистр, он
     * получает механизм. Пропущенное ребро было бы не косметической ошибкой, а тихой: без него
     * регистр исчезает не падением, а отсутствием бинов движка при старте.</p>
     */
    private static final List<Class<?>> REGISTER_ENGINE_TYPES =
        List.of(RegisterMetadataValidator.class, RegisterRecords.class);

    @Test
    void resolvedRuntimeClosureCarriesTheRegisterEngineWithoutUiOrRest() throws IOException {
        String tree = Files.readString(DEPENDENCY_TREE, StandardCharsets.UTF_8);

        assertTrue(hasArtifact(tree, "platform-backend-starter"), "root artifact missing from Maven tree");
        assertTrue(hasArtifact(tree, "platform-spring-boot-autoconfigure"),
            "backend starter's platform facade missing from resolved Maven tree");
        assertTrue(hasArtifact(tree, "platform-register"),
            "D-R S5(a), ADR-0024 §2.2: backend-поставка обязана приносить движок регистра —"
                + " иначе потребитель без UI, объявивший регистр, не получит механизм, а узнает"
                + " об этом не на сборке, а при старте контекста");
        assertFalse(hasArtifact(tree, "platform-register-vaadin"),
            "стандартный интерфейс остаётся отдельной поставкой (platform-vaadin-starter):"
                + " backend-потребитель с любым другим интерфейсом не должен тянуть Vaadin");
        assertTrue(hasNoArtifactContaining(tree, "vaadin"),
            "backend-стартер не приносит UI-стек ни напрямую, ни транзитивно");
        assertFalse(hasArtifact(tree, "platform-rest"),
            "backend starter must not pull platform-rest through any transitive dependency");
        assertFalse(hasArtifact(tree, "platform-rest-web"),
            "backend starter must not pull the optional HTTP adapter either");
    }

    /**
     * Компиляционное ребро — то, чем проверка отличается от чтения дерева.
     *
     * <p>Типы движка названы в исходнике теста, поэтому модуль без ребра не компилируется:
     * негативный прогон «убрать зависимость» даёт падение сборки, а не зелёный тест, который
     * ничего не мерил. Источник класса проверяется отдельно — иначе тест читал бы случайную
     * копию из чужого каталога (приём S4 в {@code PlatformRegisterVaadinModuleTest}).</p>
     */
    @Test
    void registerEngineTypesAreResolvedFromTheArtifactNotFromAConsumerClasspath() {
        for (Class<?> type : REGISTER_ENGINE_TYPES) {
            var codeSource = type.getProtectionDomain().getCodeSource();
            assertTrue(codeSource != null, type.getName() + " должен быть доступен backend-потребителю");
            assertTrue(codeSource.getLocation().toString().contains("platform-register"),
                type.getName() + ": класс обязан приходить из артефакта platform-register, а не из"
                    + " target/classes потребителя — иначе тест проверяет не ту поставку");
        }
    }

    private static boolean hasArtifact(String tree, String artifactId) {
        return ARTIFACT_ID.matcher(tree).results()
            .anyMatch(match -> artifactId.equals(match.group(1)));
    }

    private static boolean hasNoArtifactContaining(String tree, String fragment) {
        return ARTIFACT_ID.matcher(tree).results()
            .map(match -> match.group(1).toLowerCase(Locale.ROOT))
            .noneMatch(artifactId -> artifactId.contains(fragment));
    }
}
