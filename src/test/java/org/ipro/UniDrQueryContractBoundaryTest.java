package org.ipro;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1 acceptance gate: UniDR как потребитель query-контракта больше не импортирует
 * {@code org.ipro.reportstudio.*}.
 *
 * <p>Домен {@code org.ip.unidr} обязан быть чистым, и UI {@code org.ipro.unidr.ui}
 * больше не держит мост к UDR-редактору: R3.2 перевёл параметры и рендер на
 * {@code org.ipro.query}, поэтому список исключений пуст. Любой новый импорт
 * {@code reportstudio} падает сборкой.</p>
 */
class UniDrQueryContractBoundaryTest {

    private static final String REPORTSTUDIO = "org.ipro.reportstudio.";

    /** R3.2: мост снят полностью; список служит местом для явного обсуждения, а не разрешением. */
    private static final Map<String, Set<String>> UI_BRIDGE = Map.of();

    @Test
    void unidrDomainHasNoReportstudioImports() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/org/ip/unidr"))) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .forEach(path -> read(path).lines()
                            .filter(line -> line.startsWith("import " + REPORTSTUDIO))
                            .forEach(line -> offenders.add(
                                    path.toString().replace('\\', '/') + " -> " + line.strip())));
        }
        assertThat(offenders)
                .as("домен UniDR — потребитель query-контракта: reportstudio остался только "
                        + "в мосте к UDR-редактору (org.ipro.unidr.ui)")
                .isEmpty();
    }

    @Test
    void unidrUiBridgeIsTheReviewedShrinkOnlyList() throws IOException {
        Path root = Path.of("platform-report-vaadin/src/main/java/org/ipro/unidr/ui");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        Set<String> actual = new TreeSet<>();
                        read(path).lines()
                                .filter(line -> line.startsWith("import " + REPORTSTUDIO))
                                .forEach(line -> actual.add(line
                                        .replace("import ", "")
                                        .replace(";", "")
                                        .strip()));
                        Set<String> allowed = UI_BRIDGE.getOrDefault(path.getFileName().toString(), Set.of());
                        if (!actual.equals(allowed)) {
                            offenders.add(path.toString().replace('\\', '/') + " -> " + actual);
                        }
                    });
        }
        assertThat(offenders)
                .as("UniDR UI не импортирует reportstudio: рендер и параметры идут "
                        + "через нейтральный query-контракт")
                .isEmpty();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError("не удалось прочитать " + path, error);
        }
    }
}
