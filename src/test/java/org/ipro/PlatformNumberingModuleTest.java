package org.ipro;

import org.ipro.numbering.NumberingRuleService;
import org.ipro.numbering.NumberingService;
import org.ipro.numbering.annotation.Numbered;
import org.junit.jupiter.api.Test;

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
 * D2 в†’ D3: РїРµСЂРІС‹Р№ РЅР°СЃС‚РѕСЏС‰РёР№ РјРѕРґСѓР»СЊ-РїРѕРґСЃРёСЃС‚РµРјР°, Р° РЅРµ СЃСЂРµР· РєРѕРЅС‚СЂР°РєС‚РѕРІ Рё РЅРµ РєР°РїСЃСѓР»Р° РёР· РѕРґРЅРѕРіРѕ
 * С‚РёРїР°.
 *
 * <p>РџСЂРµРґС‹РґСѓС‰РёРµ СЃСЂРµР·С‹ РїСЂРѕРІРµСЂСЏР»Рё РїРѕ РѕРґРЅРѕРјСѓ СЃРІРѕР№СЃС‚РІСѓ РєР°Р¶РґС‹Р№: РєРѕРЅС‚СЂР°РєС‚С‹ вЂ” С‡С‚Рѕ РёС… С‡РёС‚Р°РµС‚
 * РєРѕРјРїРёР»СЏС‚РѕСЂ; events вЂ” С‡С‚Рѕ РјРѕРґСѓР»СЊ СЂРµРіРёСЃС‚СЂРёСЂСѓРµС‚ СЃРІРѕРё Р±РёРЅС‹ СЃР°Рј; persistence вЂ” С‡С‚Рѕ РјРѕРґСѓР»СЊ СЃР°Рј
 * РѕР±СЉСЏРІР»СЏРµС‚ СЃРІРѕРё РїР°РєРµС‚С‹ РІ persistence unit. РќСѓРјРµСЂР°С†РёСЏ РЅРµСЃС‘С‚ РІСЃРµ СЌС‚Рё СЃРІРѕР№СЃС‚РІР° СЃСЂР°Р·Сѓ, Рё
 * РёРјРµРЅРЅРѕ РїРѕСЌС‚РѕРјСѓ РѕРЅР° РІС‹Р±СЂР°РЅР° РїРµСЂРІРѕР№ РїРѕ Р·Р°РјРµСЂСѓ: РїРѕСЃР»Рµ РІС‹РЅРѕСЃР° РїРѕР·РІРѕРЅРѕС‡РЅРёРєР° РјРµС‚Р°РґР°РЅРЅС‹С…
 * ({@code platform-metadata}) Сѓ РЅРµС‘ РЅРµ РѕСЃС‚Р°Р»РѕСЃСЊ РЅРё РѕРґРЅРѕР№ СЃСЃС‹Р»РєРё РЅР° РґРµСЂРµРІРѕ РїСЂРёР»РѕР¶РµРЅРёСЏ.</p>
 *
 * <p>Р§С‚Рѕ Р·РґРµСЃСЊ РїСЂРѕРІРµСЂСЏРµС‚СЃСЏ Рё РїРѕС‡РµРјСѓ РёРјРµРЅРЅРѕ СЌС‚Рѕ:</p>
 * <ol>
 * <li>СЃРѕСЃС‚Р°РІ СЃСЂРµР·Р° вЂ” reviewed-Р±СЋРґР¶РµС‚: РїРѕРґСЃРёСЃС‚РµРјР° РІС‹РµС…Р°Р»Р° С†РµР»РёРєРѕРј (17 С‚РёРїРѕРІ), Р° РЅРµ РїРѕР»РѕРІРёРЅРѕР№,
 *     РёРЅР°С‡Рµ В«РјРѕРґСѓР»СЊВ» РѕСЃС‚Р°Р»СЃСЏ Р±С‹ РїР°РїРєРѕР№ РІ РґРµСЂРµРІРµ;</li>
 * <li>РѕР±СЉСЏРІР»РµРЅРЅС‹Рµ Р·Р°РІРёСЃРёРјРѕСЃС‚Рё вЂ” reviewed: РІРЅРёР· (РєРѕРЅС‚СЂР°РєС‚С‹, РјРµС‚Р°РґР°РЅРЅС‹Рµ, persistence) Рё
 *     API-Р°СЂС‚РµС„Р°РєС‚С‹, РЅРѕ РЅРµ РІРІРµСЂС… Рє {@code data}/{@code form}/{@code rls}/{@code telemetry};</li>
 * <li>СЂРµРіРёСЃС‚СЂР°С†РёСЏ: СЃСѓС‰РЅРѕСЃС‚Рё Рё СЂРµРїРѕР·РёС‚РѕСЂРёРё РѕР±СЉСЏРІР»СЏРµС‚ РјРѕРґСѓР»СЊ, РїСЂРёР»РѕР¶РµРЅРёРµ Рё РїР»Р°С‚С„РѕСЂРјРµРЅРЅС‹Р№ С…Р°Р±
 *     РёС… Р±РѕР»СЊС€Рµ РЅРµ РїРµСЂРµС‡РёСЃР»СЏСЋС‚ вЂ” РёРЅР°С‡Рµ РїРѕСЏРІРёР»РёСЃСЊ Р±С‹ РґРІРµ РґРµРєР»Р°СЂР°С†РёРё С‚Р°Рј, РіРґРµ СЂР°РЅСЊС€Рµ Р±С‹Р»Р°
 *     РѕРґРЅР°, Рё В«РєС‚Рѕ РІР»Р°РґРµР»РµС†В» РїРµСЂРµСЃС‚Р°Р»Рѕ Р±С‹ Р±С‹С‚СЊ РїСЂРѕРІРµСЂСЏРµРјС‹Рј;</li>
 * <li>reflection-СЂРµРіРёСЃС‚СЂР°С†РёСЏ СЂР°Р±РѕС‚Р°РµС‚ С‡РµСЂРµР· Р°СЂС‚РµС„Р°РєС‚: {@code @Numbered} СЃС‚РѕРёС‚ РЅР° СЃСѓС‰РЅРѕСЃС‚СЏС…
 *     РїСЂРёР»РѕР¶РµРЅРёСЏ, Р° СЃРєР°РЅРёСЂСѓРµС‚ РёС… РїР»Р°С‚С„РѕСЂРјРµРЅРЅС‹Р№ СЃРєР°РЅРµСЂ вЂ” С‚Рѕ РµСЃС‚СЊ Р°РЅРЅРѕС‚Р°С†РёСЏ РѕР±СЏР·Р°РЅР° РіСЂСѓР·РёС‚СЊСЃСЏ
 *     РёР· РјРѕРґСѓР»СЏ, Р° РЅРµ РёР· РґРµСЂРµРІР°;</li>
 * <li>Р°РІС‚Рѕ-РєРѕРЅС„РёРіСѓСЂР°С†РёСЏ Р·Р°СЂРµРіРёСЃС‚СЂРёСЂРѕРІР°РЅР° <b>СЃРІРѕРёРј</b> imports-С„Р°Р№Р»РѕРј, Р° РЅРµ С„Р°Р№Р»РѕРј
 *     РїСЂРёР»РѕР¶РµРЅРёСЏ: С‚СЂРµР±РѕРІР°РЅРёРµ roadmap В«extraction РЅРµ СѓРІРµР»РёС‡РёРІР°РµС‚ С‡РёСЃР»Рѕ РѕР±СЏР·Р°С‚РµР»СЊРЅС‹С…
 *     registrations РІ applicationВ».</li>
 * </ol>
 */
class PlatformNumberingModuleTest {

    private static final Path MODULE = Path.of("platform-numbering");

    private static final String IMPORTS_RESOURCE =
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** Reviewed-Р±СЋРґР¶РµС‚ СЃСЂРµР·Р°: РїРѕРґСЃРёСЃС‚РµРјР° С†РµР»РёРєРѕРј, РЅРё Р±РѕР»СЊС€Рµ РЅРё РјРµРЅСЊС€Рµ. */
    private static final Set<String> REVIEWED_TYPES = Set.of(
        "org.ipro.numbering.NumberFormatter",
        "org.ipro.numbering.NumberingCounter",
        "org.ipro.numbering.NumberingCounterRepository",
        "org.ipro.numbering.NumberingCounterService",
        "org.ipro.numbering.NumberingDefinition",
        "org.ipro.numbering.NumberingMetadataRegistry",
        "org.ipro.numbering.NumberingPeriod",
        "org.ipro.numbering.NumberingRule",
        "org.ipro.numbering.NumberingRuleRepository",
        "org.ipro.numbering.NumberingRuleService",
        "org.ipro.numbering.NumberingScopeResolver",
        "org.ipro.numbering.NumberingService",
        "org.ipro.numbering.annotation.Numbered",
        "org.ipro.numbering.annotation.NumberingPolicies",
        "org.ipro.numbering.annotation.NumberingPolicy",
        "org.ipro.numbering.annotation.NumberingRole",
        "org.ipro.numbering.config.NumberingAutoConfiguration");

    private static final Set<String> REVIEWED_DEPENDENCIES = Set.of(
        "platform-contracts",
        "platform-metadata",
        "platform-persistence",
        "jakarta.persistence-api",
        "jakarta.validation-api",
        "spring-data-jpa",
        "spring-boot-autoconfigure",
        "spring-boot-persistence");

    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern ANNOTATION = Pattern.compile(
        "^\\s*@(EntityScan|EnableJpaRepositories)\\b", Pattern.MULTILINE);

    @Test
    void moduleCarriesTheWholeSubsystem() {
        Set<String> actual = new TreeSet<>();
        for (Path source : javaSources()) {
            actual.add(packageOf(source) + "." + typeOf(source));
        }

        assertThat(actual)
            .as("СЃСЂРµР· РїРѕРґСЃРёСЃС‚РµРјС‹ reviewed: РїРѕР»РѕРІРёРЅР° РїРѕРґСЃРёСЃС‚РµРјС‹ РІ РјРѕРґСѓР»Рµ, РїРѕР»РѕРІРёРЅР° РІ РґРµСЂРµРІРµ"
                + " РѕР·РЅР°С‡Р°Р»Р° Р±С‹, С‡С‚Рѕ РіСЂР°РЅРёС†Р° РїСЂРѕС…РѕРґРёС‚ РІРЅСѓС‚СЂРё РЅРµС‘")
            .isEqualTo(new TreeSet<>(REVIEWED_TYPES));
    }

    @Test
    void moduleDeclaresOnlyReviewedDependencies() {
        Set<String> declared = new TreeSet<>();
        Matcher matcher = ARTIFACT_ID.matcher(read(MODULE.resolve("pom.xml")));
        while (matcher.find()) {
            String artifactId = matcher.group(1);
            if (!artifactId.equals("platform-numbering")
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
    void moduleOwnsItsPersistenceAndTheApplicationNoLongerDoes() {
        String autoConfiguration =
            read(MODULE.resolve("src/main/java/org/ipro/numbering/config/NumberingAutoConfiguration.java"));
        assertThat(declaredAnnotations(autoConfiguration))
            .as("РјРѕРґСѓР»СЊ РѕР±СЏР·Р°РЅ РѕР±СЉСЏРІР»СЏС‚СЊ Рё persistence unit, Рё Spring Data: РёРЅР°С‡Рµ РµРіРѕ"
                + " СЃСѓС‰РЅРѕСЃС‚Рё Рё СЂРµРїРѕР·РёС‚РѕСЂРёРё РѕСЃС‚Р°СЋС‚СЃСЏ РІ Р°СЂС‚РµС„Р°РєС‚Рµ Р±РµР· СЂРµРіРёСЃС‚СЂР°С†РёРё")
            .containsExactlyInAnyOrder("EntityScan", "EnableJpaRepositories");
        assertThat(autoConfiguration).contains("\"org.ipro.numbering\"");

        String application = read(Path.of("src/main/java/org/ip/Application.java"));
        assertThat(application)
            .as("РїСЂРёР»РѕР¶РµРЅРёРµ Р±РѕР»СЊС€Рµ РЅРµ РїРµСЂРµС‡РёСЃР»СЏРµС‚ РїР°РєРµС‚С‹ РїРѕРґСЃРёСЃС‚РµРјС‹")
            .doesNotContain("org.ipro.numbering");

        String hub = read(Path.of("platform-rls/src/main/java/org/ipro/rls/config/RlsAutoConfiguration.java"));
        assertThat(hub)
            .as("модуль RLS не владеет чужими пакетами: свои регистрирует RlsPersistenceAutoConfiguration")
            .doesNotContain("\"org.ipro.numbering\"");

        assertThat(lines(MODULE.resolve("src/main/resources").resolve(IMPORTS_RESOURCE)))
            .containsExactly("org.ipro.numbering.config.NumberingAutoConfiguration");
        assertThat(lines(Path.of("src/main/resources").resolve(IMPORTS_RESOURCE)))
            .as("РїСЂРёР»РѕР¶РµРЅРёРµ РЅРµ РґРѕР»Р¶РЅРѕ СЂРµРіРёСЃС‚СЂРёСЂРѕРІР°С‚СЊ С‡СѓР¶СѓСЋ Р°РІС‚Рѕ-РєРѕРЅС„РёРіСѓСЂР°С†РёСЋ: РјРѕРґСѓР»СЊ РґРµР»Р°РµС‚"
                + " СЌС‚Рѕ СЃР°Рј")
            .doesNotContain("org.ipro.numbering.config.NumberingAutoConfiguration");
    }

    @Test
    void subsystemLeftTheApplicationTree() {
        assertThat(Path.of("src/main/java/org/ipro/numbering")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/numbering/NumberingService.java")).doesNotExist();
    }

    /**
     * Р¤Р°Р№Р»РѕРІС‹Рµ РїСЂРѕРІРµСЂРєРё РіРѕРІРѕСЂСЏС‚, РіРґРµ С‚РёРїС‹ РѕР±СЉСЏРІР»РµРЅС‹; СЌС‚Р° вЂ” РѕС‚РєСѓРґР° РѕРЅРё Р±РµСЂСѓС‚СЃСЏ РІ СЂР°РЅС‚Р°Р№РјРµ.
     * Р”Р»СЏ РЅСѓРјРµСЂР°С†РёРё СЌС‚Рѕ РЅРµ С„РѕСЂРјР°Р»СЊРЅРѕСЃС‚СЊ: {@code @Numbered} СЃС‚РѕРёС‚ РЅР° СЃСѓС‰РЅРѕСЃС‚СЏС… РїСЂРёР»РѕР¶РµРЅРёСЏ,
     * Рё СЃРєР°РЅРµСЂ РјРµС‚Р°РґР°РЅРЅС‹С… С‡РёС‚Р°РµС‚ Р°РЅРЅРѕС‚Р°С†РёСЋ СЃ РєР»Р°СЃСЃР°, РїСЂРёРµС…Р°РІС€РµРіРѕ РёР· Р°СЂС‚РµС„Р°РєС‚Р°.
     */
    @Test
    void subsystemClassesAreResolvedFromTheArtifactAtRuntime() {
        for (Class<?> type : List.of(NumberingService.class, NumberingRuleService.class, Numbered.class)) {
            CodeSource codeSource = type.getProtectionDomain().getCodeSource();
            assertThat(codeSource).as("%s РґРѕР»Р¶РµРЅ РіСЂСѓР·РёС‚СЊСЃСЏ РёР· Р°СЂС‚РµС„Р°РєС‚Р°", type.getName()).isNotNull();
            assertThat(codeSource.getLocation().toString())
                .as("%s: РєР»Р°СЃСЃ РѕР±СЏР·Р°РЅ РїСЂРёС…РѕРґРёС‚СЊ РёР· platform-numbering, Р° РЅРµ РёР· target/classes",
                    type.getName())
                .contains("platform-numbering");
        }
    }

    private static List<String> declaredAnnotations(String text) {
        Matcher matcher = ANNOTATION.matcher(text);
        java.util.List<String> annotations = new java.util.ArrayList<>();
        while (matcher.find()) {
            annotations.add(matcher.group(1));
        }
        return annotations;
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> javaSources() {
        Path sources = MODULE.resolve("src/main/java");
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
            throw new IllegalStateException("РќРµС‚ package РІ " + source);
        }
        return matcher.group(1);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
