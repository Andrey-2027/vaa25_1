package org.ipro;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2 (persistence slice): РјРѕРґСѓР»СЊ СЃ persistence-С‚РёРїР°РјРё РѕС‚РІРµС‡Р°РµС‚ РЅР° СЂРёСЃРє В§3.5 РєР°СЂС‚С‹ D1.
 *
 * <p>Р РёСЃРє Р±С‹Р»: {@code @EntityScan} Рё {@code @EnableJpaRepositories} Р·Р°РґР°СЋС‚СЃСЏ
 * С†РµРЅС‚СЂР°Р»РёР·РѕРІР°РЅРЅРѕ, РїРѕСЌС‚РѕРјСѓ РІС‹РЅРµСЃРµРЅРЅС‹Р№ С‚РёРї РјРѕР¶РµС‚ РѕСЃС‚Р°С‚СЊСЃСЏ Р±РµР· СЂРµРіРёСЃС‚СЂР°С†РёРё. РћС‚РІРµС‚ СЃСЂРµР·Р° вЂ”
 * РјРѕРґСѓР»СЊ РѕР±СЉСЏРІР»СЏРµС‚ СЃРІРѕРё РїР°РєРµС‚С‹ СЃР°Рј, Р° РїСЂРёР»РѕР¶РµРЅРёРµ Рѕ РЅРёС… Р±РѕР»СЊС€Рµ РЅРµ Р·РЅР°РµС‚. РўРµСЃС‚ РґРµСЂР¶РёС‚ С‚СЂРё
 * СЃРІРѕР№СЃС‚РІР°:</p>
 * <ol>
 * <li>reviewed СЃРѕСЃС‚Р°РІ Рё Р·Р°РІРёСЃРёРјРѕСЃС‚Рё РјРѕРґСѓР»СЏ;</li>
 * <li>РјРѕРґСѓР»СЊ СЃР°Рј РЅРµСЃС‘С‚ РѕР±Рµ РґРµРєР»Р°СЂР°С†РёРё, Р° РїСЂРёР»РѕР¶РµРЅРёРµ Рё РїР»Р°С‚С„РѕСЂРјРµРЅРЅС‹Р№ С…Р°Р± вЂ” СѓР¶Рµ РЅРµС‚;</li>
 * <li>С‚РёРїС‹ СѓРµС…Р°Р»Рё РёР· РґРµСЂРµРІР° РїСЂРёР»РѕР¶РµРЅРёСЏ: РєРѕРїРёСЏ РІ РґРµСЂРµРІРµ РѕР·РЅР°С‡Р°Р»Р° Р±С‹, С‡С‚Рѕ РіСЂР°РЅРёС†Р°"
 *     РґРµРєРѕСЂР°С‚РёРІРЅР°СЏ.</li>
 * </ol>
 *
 * <p>Р”РёРЅР°РјРёС‡РµСЃРєР°СЏ С‡Р°СЃС‚СЊ вЂ” РІ {@code PersistenceRegistrationIT} (СЂРµРїРѕР·РёС‚РѕСЂРёР№ СЃСѓС‰РµСЃС‚РІСѓРµС‚ РєР°Рє Р±РёРЅ,
 * entity РІ persistence unit, РЅРё РѕРґРЅР° С‡СѓР¶Р°СЏ РґРµРєР»Р°СЂР°С†РёСЏ РЅРµ РїРµСЂРµРєСЂС‹С‚Р°) Рё РІ
 * {@link PersistenceTypeRegistrationTest} (СЂРµРµСЃС‚СЂ РїРѕРєСЂС‹С‚РёСЏ С‚РёРїРѕРІ).</p>
 */
class PlatformPersistenceModuleTest {

    private static final Path MODULE = Path.of("platform-persistence");

    private static final String IMPORTS_RESOURCE =
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private static final Set<String> REVIEWED_TYPES = Set.of(
        "org.ipro.crud.BaseEntity",
        "org.ipro.jr.JrxmlTemplateRepository",
        "org.ipro.jr.dom.JrxmlTemplate",
        "org.ipro.persistence.config.PersistenceAutoConfiguration");

    private static final Set<String> REVIEWED_DEPENDENCIES = Set.of(
        "crudui-core",
        "jakarta.persistence-api",
        "jakarta.validation-api",
        "spring-data-jpa",
        "spring-boot-autoconfigure",
        "spring-boot-persistence",
        "hibernate-core");

    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern ANNOTATION = Pattern.compile(
        "^\\s*@(EntityScan|EnableJpaRepositories)\\b", Pattern.MULTILINE);

    @Test
    void moduleCarriesExactlyTheReviewedPersistenceTypes() {
        Set<String> actual = new TreeSet<>();
        for (Path source : javaSources(MODULE.resolve("src"))) {
            actual.add(packageOf(source) + "." + source.getFileName().toString().replace(".java", ""));
        }

        assertThat(actual)
            .as("persistence-РєР°РїСЃСѓР»Р° вЂ” reviewed: СЃСЋРґР° РїРѕРїР°РґР°СЋС‚ С‚РѕР»СЊРєРѕ С‚РёРїС‹, РєРѕС‚РѕСЂС‹Рµ РЅСѓР¶РЅС‹"
                + " Р°СЂС‚РµС„Р°РєС‚Сѓ, С‡С‚РѕР±С‹ Р±С‹С‚СЊ СЃР°РјРѕРґРѕСЃС‚Р°С‚РѕС‡РЅС‹Рј (РІРєР»СЋС‡Р°СЏ Р±Р°Р·Сѓ СЃСѓС‰РЅРѕСЃС‚РµР№), РЅРѕ РЅРµ"
                + " СЂРµР°Р»РёР·Р°С†РёРё РїР»Р°С‚С„РѕСЂРјС‹")
            .isEqualTo(new TreeSet<>(REVIEWED_TYPES));
    }

    @Test
    void moduleDeclaresOnlyPersistenceApis() {
        String pom = read(MODULE.resolve("pom.xml"));
        Set<String> declared = new TreeSet<>();
        Matcher matcher = ARTIFACT_ID.matcher(pom);
        while (matcher.find()) {
            String artifactId = matcher.group(1);
            if (!artifactId.equals("platform-persistence")
                    && !artifactId.equals("spring-boot-starter-parent")) {
                declared.add(artifactId);
            }
        }

        assertThat(declared).isEqualTo(new TreeSet<>(REVIEWED_DEPENDENCIES));
        assertThat(pom).doesNotContain("<groupId>org.ip</groupId>");
        assertThat(pom).doesNotContain("<artifactId>Vaa25_1</artifactId>");
    }

    @Test
    void moduleDeclaresItsOwnScanPackagesAndTheApplicationDoesNot() {
        String autoConfiguration = read(MODULE
            .resolve("src/main/java/org/ipro/persistence/config/PersistenceAutoConfiguration.java"));
        assertThat(declaredAnnotations(autoConfiguration))
            .as("РјРѕРґСѓР»СЊ РѕР±СЏР·Р°РЅ РѕР±СЉСЏРІР»СЏС‚СЊ Рё persistence unit, Рё Spring Data: РёРЅР°С‡Рµ РµРіРѕ С‚РёРїС‹"
                + " РѕСЃС‚Р°СЋС‚СЃСЏ РІ Р°СЂС‚РµС„Р°РєС‚Рµ Р±РµР· СЂРµРіРёСЃС‚СЂР°С†РёРё")
            .containsExactlyInAnyOrder("EntityScan", "EnableJpaRepositories");
        assertThat(autoConfiguration).contains("\"org.ipro.jr.dom\"");
        assertThat(autoConfiguration).contains("\"org.ipro.jr\"");

        // РџСЂРёР»РѕР¶РµРЅРёРµ СЃРѕС…СЂР°РЅСЏРµС‚ СЃРІРѕРё С‚СЂРё СЂРµРіРёСЃС‚СЂР°С†РёРё: @EntityScan РґР»СЏ РїСЂРёРєР»Р°РґРЅС‹С… СЃСѓС‰РЅРѕСЃС‚РµР№
        // Рё @EnableJpaRepositories РґР»СЏ СЃРІРѕРёС… СЂРµРїРѕР·РёС‚РѕСЂРёРµРІ. РЈРµР·Р¶Р°РµС‚ С‚РѕР»СЊРєРѕ С‡СѓР¶РѕРµ.
        assertThat(declaredAnnotations(read(Path.of("src/main/java/org/ip/Application.java"))))
            .containsExactlyInAnyOrder("EntityScan", "EnableJpaRepositories");
        assertThat(read(Path.of("src/main/java/org/ip/Application.java")))
            .doesNotContain("org.ipro.jr.dom");

        assertThat(read(Path.of("platform-rls/src/main/java/org/ipro/rls/config/RlsAutoConfiguration.java")))
            .as("модуль RLS не владеет чужими пакетами: свои регистрирует RlsPersistenceAutoConfiguration")
            .doesNotContain("\"org.ipro.jr\"");

        assertThat(lines(MODULE.resolve("src/main/resources").resolve(IMPORTS_RESOURCE)))
            .containsExactly("org.ipro.persistence.config.PersistenceAutoConfiguration");
    }

    @Test
    void persistenceTypesLeftTheApplicationTree() {
        assertThat(Path.of("src/main/java/org/ipro/jr/dom/JrxmlTemplate.java")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/jr/JrxmlTemplateRepository.java")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/crud/BaseEntity.java"))
            .as("Р±Р°Р·Р° СЃСѓС‰РЅРѕСЃС‚РµР№ РІС…РѕРґРёС‚ РІ persistence-РєР°РїСЃСѓР»Сѓ: Р±РµР· РЅРµС‘ entity РЅРµ РєРѕРјРїРёР»РёСЂСѓРµС‚СЃСЏ"
                + " РІРЅРµ РґРµСЂРµРІР° РїСЂРёР»РѕР¶РµРЅРёСЏ")
            .doesNotExist();
    }

    private static List<String> declaredAnnotations(String text) {
        Matcher matcher = ANNOTATION.matcher(text);
        List<String> annotations = new java.util.ArrayList<>();
        while (matcher.find()) {
            annotations.add(matcher.group(1));
        }
        return annotations;
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

    private static String packageOf(Path source) {
        Matcher matcher = PACKAGE.matcher(read(source));
        if (!matcher.find()) {
            throw new IllegalStateException("РЅРµС‚ package: " + source);
        }
        return matcher.group(1);
    }

    private static List<Path> javaSources(Path root) {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
