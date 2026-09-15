package org.ipro;

import org.junit.jupiter.api.Test;

import org.ipro.metadata.AnnotationClassScanner;
import org.ipro.metadata.ReferenceIndex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2 → D3: позвоночник метаданных — отдельный артефакт, и это проверяется.
 *
 * <p>Срез выбран замером, а не удобством: {@code PlatformSubsystemClosureTest} показал, что
 * весь упор подсистем в дерево — это два типа (сканер классов по аннотации и индекс
 * обратных ссылок). Пока они лежали в дереве, ни {@code numbering}, ни {@code settings}
 * нельзя было сделать модулем: модуль не может ссылаться на дерево.</p>
 *
 * <p>Тест держит четыре свойства, которые легко потерять незаметно:</p>
 * <ol>
 * <li>состав среза — reviewed-бюджет: ровно два типа, а не «пакет целиком». Пакет
 *     {@code org.ipro.metadata} — это движок метаданных, и он выносится по мере того, как
 *     этого потребует замыкание следующей подсистемы;</li>
 * <li>объявленные зависимости помника — reviewed: контракты (аннотации) и
 *     {@code spring-context} (сканер и bean-контракт индекса);</li>
 * <li>внешние импорты — только разрешённые пакеты: реализация платформы в этом модуле
 *     означала бы, что подсистемы не смогут зависеть на него без цикла;</li>
 * <li>модуль <b>не несёт авто-конфигурации</b>: бин {@code ReferenceIndex} объявляет
 *     {@code MetadataAutoConfiguration} приложения, поэтому потеря артефакта ломает сборку,
 *     а не проявляется позже как «каталог молча пуст». Если модулю когда-нибудь
 *     понадобится своя авто-конфигурация, он обязан нести свой imports-файл — по образцу
 *     {@code platform-events} и {@code platform-persistence}.</li>
 * </ol>
 */
class PlatformMetadataModuleTest {

    private static final Path MODULE = Path.of("platform-metadata");

    private static final Set<String> REVIEWED_TYPES = Set.of(
        "org.ipro.metadata.AnnotationClassScanner",
        "org.ipro.metadata.ReferenceIndex");

    private static final Set<String> REVIEWED_DEPENDENCIES = Set.of(
        "platform-contracts",
        "spring-context");

    /** Разрешённые внешние импорты: только эти пакеты не-JDK. */
    private static final Set<String> ALLOWED_EXTERNAL_IMPORTS = Set.of(
        "org.ipro.metadata.annotation",  // аннотации метаданных из platform-contracts
        "org.springframework");         // сканер классов и bean-контракт

    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern IMPORT = Pattern.compile("^import\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern AUTO_CONFIGURATION = Pattern.compile("@AutoConfiguration\\b");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    @Test
    void moduleCarriesExactlyTheReviewedSpineTypes() {
        Set<String> actual = new TreeSet<>();
        for (Path source : javaSources()) {
            actual.add(packageOf(source) + "." + typeOf(source));
        }

        assertThat(actual)
            .as("срез позвоночника reviewed: сюда попадают только типы, на которые упирается"
                + " замыкание подсистем, а не весь пакет метаданных")
            .isEqualTo(new TreeSet<>(REVIEWED_TYPES));
    }

    @Test
    void moduleDeclaresOnlyReviewedDependencies() {
        Set<String> declared = new TreeSet<>();
        Matcher matcher = ARTIFACT_ID.matcher(read(MODULE.resolve("pom.xml")));
        while (matcher.find()) {
            String artifactId = matcher.group(1);
            if (!artifactId.equals("platform-metadata")
                    && !artifactId.equals("spring-boot-starter-parent")) {
                declared.add(artifactId);
            }
        }

        assertThat(declared).isEqualTo(new TreeSet<>(REVIEWED_DEPENDENCIES));
        String pom = read(MODULE.resolve("pom.xml"));
        assertThat(pom).doesNotContain("<groupId>org.ip</groupId>");
        assertThat(pom).doesNotContain("<artifactId>Vaa25_1</artifactId>");
    }

    @Test
    void moduleImportsOnlyContractsAndSpring() {
        Set<String> offending = new TreeSet<>();
        for (Path source : javaSources()) {
            Matcher matcher = IMPORT.matcher(withoutComments(read(source)));
            while (matcher.find()) {
                String imported = matcher.group(1);
                if (imported.startsWith("java.") || imported.startsWith("javax.")) {
                    continue;
                }
                if (imported.startsWith("org.ipro.metadata.")
                        && !imported.startsWith("org.ipro.metadata.annotation.")) {
                    // собственный пакет среза: типы этого модуля
                    continue;
                }
                boolean allowed = ALLOWED_EXTERNAL_IMPORTS.stream().anyMatch(imported::startsWith);
                if (!allowed) {
                    offending.add(imported);
                }
            }
        }

        assertThat(offending)
            .as("импорт платформенной реализации сделал бы этот модуль верхним слоем,"
                + " и подсистемы не смогли бы на него зависеть")
            .isEmpty();
    }

    @Test
    void moduleCarriesNoAutoConfigurationAndTheApplicationStillDeclaresItsBean() {
        assertThat(MODULE.resolve("src/main/resources/META-INF/spring/"
            + "org.springframework.boot.autoconfigure.AutoConfiguration.imports"))
            .as("модуль не несёт авто-конфигурации: бин индекса объявляет приложение")
            .doesNotExist();
        for (Path source : javaSources()) {
            assertThat(withoutComments(read(source)))
                .as("авто-конфигурация в этом модуле — отдельное решение: тогда модуль обязан"
                    + " зарегистрировать её в собственном imports-файле")
                .doesNotContain("@AutoConfiguration");
        }

        String applicationConfig =
            read(Path.of("src/main/java/org/ipro/metadata/config/MetadataAutoConfiguration.java"));
        assertThat(applicationConfig)
            .as("провайдер бина остался в дереве: так потеря артефакта видна компилятору")
            .contains("public ReferenceIndex referenceIndex(");
    }

    @Test
    void spineTypesLeftTheApplicationTree() {
        assertThat(Path.of("src/main/java/org/ipro/metadata/AnnotationClassScanner.java")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/metadata/ReferenceIndex.java")).doesNotExist();
    }

    /**
     * Проверка исходников говорит, где типы объявлены; эта — откуда они берутся на самом
     * деле. Разница существенна: копия в дереве оставила бы файловые проверки зелёными,
     * а подсистема-модуль по-прежнему не собиралась бы.
     */
    @Test
    void spineTypesAreResolvedFromTheArtifactAtRuntime() {
        for (Class<?> type : List.of(AnnotationClassScanner.class, ReferenceIndex.class)) {
            CodeSource codeSource = type.getProtectionDomain().getCodeSource();
            assertThat(codeSource).as("%s должен грузиться из артефакта", type.getName()).isNotNull();
            assertThat(codeSource.getLocation().toString())
                .as("%s: класс обязан приходить из platform-metadata, а не из target/classes", type.getName())
                .contains("platform-metadata");
        }
    }

    private static List<Path> javaSources() {
        Path sources = MODULE.resolve("src/main/java");
        if (!Files.isDirectory(sources)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(sources)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String typeOf(Path source) {
        String name = source.getFileName().toString();
        return name.substring(0, name.length() - ".java".length());
    }

    private static String packageOf(Path source) {
        Matcher matcher = PACKAGE.matcher(read(source));
        if (!matcher.find()) {
            throw new IllegalStateException("Нет package в " + source);
        }
        return matcher.group(1);
    }

    private static String withoutComments(String source) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll(" ")).replaceAll(" ");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
