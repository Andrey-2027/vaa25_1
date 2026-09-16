package org.ipro;

import org.ipro.settings.SettingsRegistry;
import org.ipro.settings.SettingsService;
import org.ipro.settings.setting.Setting;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2 в†’ D3: РІС‚РѕСЂРѕР№ РјРѕРґСѓР»СЊ-РїРѕРґСЃРёСЃС‚РµРјР° РїРѕСЃР»Рµ РЅСѓРјРµСЂР°С†РёРё.
 *
 * <p>РќСѓРјРµСЂР°С†РёСЏ РґРѕРєР°Р·Р°Р»Р°, С‡С‚Рѕ РїРѕРґСЃРёСЃС‚РµРјР° СЃ СЃСѓС‰РЅРѕСЃС‚СЏРјРё, СЂРµРїРѕР·РёС‚РѕСЂРёСЏРјРё, Р±РёРЅР°РјРё Рё reflection
 * РјРѕР¶РµС‚ Р¶РёС‚СЊ РІРЅРµ РґРµСЂРµРІР°. РљРѕРЅСЃС‚Р°РЅС‚С‹ РЅСѓР¶РЅС‹ РЅРµ РєР°Рє РµС‰С‘ РѕРґРЅРѕ РґРѕРєР°Р·Р°С‚РµР»СЊСЃС‚РІРѕ С‚РѕРіРѕ Р¶Рµ, Р° РєР°Рє
 * РїСЂРѕРІРµСЂРєР° РІРѕСЃРїСЂРѕРёР·РІРѕРґРёРјРѕСЃС‚Рё РїСЂРёС‘РјР° РЅР° РїРѕРґСЃРёСЃС‚РµРјРµ РґСЂСѓРіРѕРіРѕ РїСЂРѕС„РёР»СЏ: Р·РґРµСЃСЊ РµСЃС‚СЊ РєР°С‚Р°Р»РѕРі,
 * РєРѕС‚РѕСЂС‹Р№ СЃР°Рј СЃРєР°РЅРёСЂСѓРµС‚ РїР°РєРµС‚ РїСЂРёР»РѕР¶РµРЅРёСЏ, РєР°С‚Р°Р»РѕРі Р·РЅР°С‡РµРЅРёР№ РїРѕ СѓРјРѕР»С‡Р°РЅРёСЋ Рё РѕР±СЂР°С‚РЅР°СЏ СЃСЃС‹Р»РєР° РІ
 * РёРЅРґРµРєСЃ РјРµС‚Р°РґР°РЅРЅС‹С… ({@code settings в†’ metadata}).</p>
 *
 * <p>Р—Р°РјС‹РєР°РЅРёРµ РєРѕРЅСЃС‚Р°РЅС‚ РЅР° РґРµСЂРµРІРѕ РґРѕ СЃСЂРµР·Р° Р±С‹Р»Рѕ СЂРѕРІРЅРѕ РґРІР° С‚РёРїР° вЂ” С‚Рµ СЃР°РјС‹Рµ СЃРєР°РЅРµСЂ Рё РёРЅРґРµРєСЃ,
 * С‡С‚Рѕ СѓРµС…Р°Р»Рё РІ {@code platform-metadata}; РїРѕСЌС‚РѕРјСѓ СЃСЂРµР· РѕР±РЅСѓР»РёР» РµРіРѕ Рё СЃРЅСЏР» Р·Р°РїРёСЃСЊ Рѕ РїРѕРґСЃРёСЃС‚РµРјРµ
 * СЃ СЂРµРµСЃС‚СЂР° {@code PlatformSubsystemClosureTest}.</p>
 */
class PlatformSettingsModuleTest {

    private static final Path MODULE = Path.of("platform-settings");

    private static final String IMPORTS_RESOURCE =
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** Reviewed-Р±СЋРґР¶РµС‚ СЃСЂРµР·Р°: РїРѕРґСЃРёСЃС‚РµРјР° С†РµР»РёРєРѕРј. */
    private static final Set<String> REVIEWED_TYPES = Set.of(
        "org.ipro.settings.SettingValue",
        "org.ipro.settings.SettingValueRepository",
        "org.ipro.settings.SettingsRegistry",
        "org.ipro.settings.SettingsReverseReferenceSource",
        "org.ipro.settings.SettingsService",
        "org.ipro.settings.config.SettingsAutoConfiguration",
        "org.ipro.settings.setting.Setting",
        "org.ipro.settings.setting.SettingsGroup");

    private static final Set<String> REVIEWED_DEPENDENCIES = Set.of(
        "platform-contracts",
        "platform-metadata",
        "platform-persistence",
        "jakarta.persistence-api",
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

        assertThat(actual).isEqualTo(new TreeSet<>(REVIEWED_TYPES));
    }

    @Test
    void moduleDeclaresOnlyReviewedDependencies() {
        Set<String> declared = new TreeSet<>();
        Matcher matcher = ARTIFACT_ID.matcher(read(MODULE.resolve("pom.xml")));
        while (matcher.find()) {
            String artifactId = matcher.group(1);
            if (!artifactId.equals("platform-settings")
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
        String autoConfiguration = read(
            MODULE.resolve("src/main/java/org/ipro/settings/config/SettingsAutoConfiguration.java"));
        assertThat(declaredAnnotations(autoConfiguration))
            .as("РјРѕРґСѓР»СЊ РѕР±СЏР·Р°РЅ РѕР±СЉСЏРІР»СЏС‚СЊ Рё persistence unit, Рё Spring Data")
            .containsExactlyInAnyOrder("EntityScan", "EnableJpaRepositories");
        assertThat(autoConfiguration).contains("\"org.ipro.settings\"");

        assertThat(read(Path.of("src/main/java/org/ip/Application.java")))
            .as("приложение больше не перечисляет пакеты подсистемы")
            .doesNotContain("org.ipro.settings");

        assertThat(read(Path.of("platform-rls/src/main/java/org/ipro/rls/config/RlsAutoConfiguration.java")))
            .as("РїР»Р°С‚С„РѕСЂРјРµРЅРЅС‹Р№ С…Р°Р± СЂРµРїРѕР·РёС‚РѕСЂРёРµРІ РїРµСЂРµС‡РёСЃР»СЏРµС‚ С‚РѕР»СЊРєРѕ С‚Рѕ, С‡С‚Рѕ РµС‰С‘ Р¶РёРІС‘С‚ РІ РґРµСЂРµРІРµ")
            .doesNotContain("\"org.ipro.settings\"");

        assertThat(lines(MODULE.resolve("src/main/resources").resolve(IMPORTS_RESOURCE)))
            .containsExactly("org.ipro.settings.config.SettingsAutoConfiguration");
        assertThat(lines(Path.of("src/main/resources").resolve(IMPORTS_RESOURCE)))
            .doesNotContain("org.ipro.settings.config.SettingsAutoConfiguration");
    }

    @Test
    void subsystemLeftTheApplicationTree() {
        assertThat(Path.of("src/main/java/org/ipro/settings")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/settings/SettingsRegistry.java")).doesNotExist();
    }

    /**
     * Р¤Р°Р№Р»РѕРІС‹Рµ РїСЂРѕРІРµСЂРєРё РіРѕРІРѕСЂСЏС‚, РіРґРµ С‚РёРїС‹ РѕР±СЉСЏРІР»РµРЅС‹; СЌС‚Р° вЂ” РѕС‚РєСѓРґР° РѕРЅРё Р±РµСЂСѓС‚СЃСЏ РІ СЂР°РЅС‚Р°Р№РјРµ.
     * Р”Р»СЏ РєРѕРЅСЃС‚Р°РЅС‚ СЌС‚Рѕ СЃСѓС‰РµСЃС‚РІРµРЅРЅРѕ: {@code @Setting}/{@code @SettingsGroup} СЃС‚РѕСЏС‚ РЅР°
     * appdev-РєР»Р°СЃСЃР°С… РїСЂРёР»РѕР¶РµРЅРёСЏ, Р° С‡РёС‚Р°РµС‚ РёС… РєР°С‚Р°Р»РѕРі РёР· Р°СЂС‚РµС„Р°РєС‚Р°.
     */
    @Test
    void subsystemClassesAreResolvedFromTheArtifactAtRuntime() {
        for (Class<?> type : List.of(SettingsService.class, SettingsRegistry.class, Setting.class)) {
            CodeSource codeSource = type.getProtectionDomain().getCodeSource();
            assertThat(codeSource).as("%s РґРѕР»Р¶РµРЅ РіСЂСѓР·РёС‚СЊСЃСЏ РёР· Р°СЂС‚РµС„Р°РєС‚Р°", type.getName()).isNotNull();
            assertThat(codeSource.getLocation().toString())
                .as("%s: РєР»Р°СЃСЃ РѕР±СЏР·Р°РЅ РїСЂРёС…РѕРґРёС‚СЊ РёР· platform-settings, Р° РЅРµ РёР· target/classes",
                    type.getName())
                .contains("platform-settings");
        }
    }

    private static List<String> declaredAnnotations(String text) {
        Matcher matcher = ANNOTATION.matcher(text);
        List<String> annotations = new ArrayList<>();
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
