package org.ipro;

import org.ipro.telemetry.config.TelemetryAutoConfiguration;
import org.ipro.telemetry.core.JournalQueryService;
import org.ipro.telemetry.core.TelemetryService;
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
 * D2 в†’ D3: С‚СЂРµС‚РёР№ РјРѕРґСѓР»СЊ-РїРѕРґСЃРёСЃС‚РµРјР° вЂ” РЅР°Р±Р»СЋРґРµРЅРёРµ С†РµР»РёРєРѕРј, Р° РЅРµ СЃСЂРµР· РєРѕРЅС‚СЂР°РєС‚РѕРІ.
 *
 * <p>Р§С‚Рѕ Р·РґРµСЃСЊ РїСЂРѕРІРµСЂСЏРµС‚СЃСЏ Рё РїРѕС‡РµРјСѓ РёРјРµРЅРЅРѕ СЌС‚Рѕ:</p>
 * <ol>
 * <li>СЃРѕСЃС‚Р°РІ СЃСЂРµР·Р° вЂ” reviewed-Р±СЋРґР¶РµС‚: РїРѕРґСЃРёСЃС‚РµРјР° РІС‹РµС…Р°Р»Р° С†РµР»РёРєРѕРј (66 С‚РёРїРѕРІ: API, core,
 *     СЃСѓС‰РЅРѕСЃС‚Рё Р¶СѓСЂРЅР°Р»Р°, СЂРµРїРѕР·РёС‚РѕСЂРёР№, Р°РІС‚Рѕ-РєРѕРЅС„РёРіСѓСЂР°С†РёСЏ), Р° Vaadin-Р°РґР°РїС‚РµСЂС‹
 *     ({@code TelemetryVaadinInitListener}, {@code TelemetryErrorHandler}) СЃРѕР·РЅР°С‚РµР»СЊРЅРѕ
 *     РѕСЃС‚Р°Р»РёСЃСЊ РІ РґРµСЂРµРІРµ РїСЂРёР»РѕР¶РµРЅРёСЏ ({@code org.ip.telemetry.vaadin}) вЂ” РјРѕРґСѓР»СЊ РЅРµ Р·Р°РІРёСЃРёС‚
 *     РЅР° UI;</li>
 * <li>РѕР±СЉСЏРІР»РµРЅРЅС‹Рµ Р·Р°РІРёСЃРёРјРѕСЃС‚Рё вЂ” reviewed: JPA/Hibernate, Spring Data/JDBC/TX/Web/Security,
 *     Jackson, Logback, РЅРѕ РЅРµ РІРІРµСЂС… Рє {@code data}/{@code form}/{@code rls}/{@code fetch}/
 *     {@code reportstudio} Рё РЅРµ Vaadin;</li>
 * <li>СЂРµРіРёСЃС‚СЂР°С†РёСЏ: СЃСѓС‰РЅРѕСЃС‚Рё Рё СЂРµРїРѕР·РёС‚РѕСЂРёРё РѕР±СЉСЏРІР»СЏРµС‚ РјРѕРґСѓР»СЊ ({@code @EntityScan}/
 *     {@code @EnableJpaRepositories} РІ {@code TelemetryAutoConfiguration}), РїСЂРёР»РѕР¶РµРЅРёРµ
 *     Рё РїР»Р°С‚С„РѕСЂРјРµРЅРЅС‹Р№ С…Р°Р± РёС… Р±РѕР»СЊС€Рµ РЅРµ РїРµСЂРµС‡РёСЃР»СЏСЋС‚;</li>
 * <li>Р°РІС‚Рѕ-РєРѕРЅС„РёРіСѓСЂР°С†РёСЏ Р·Р°СЂРµРіРёСЃС‚СЂРёСЂРѕРІР°РЅР° <b>СЃРІРѕРёРј</b> imports-С„Р°Р№Р»РѕРј, Р° РЅРµ С„Р°Р№Р»РѕРј
 *     РїСЂРёР»РѕР¶РµРЅРёСЏ: С‚СЂРµР±РѕРІР°РЅРёРµ roadmap В«extraction РЅРµ СѓРІРµР»РёС‡РёРІР°РµС‚ С‡РёСЃР»Рѕ РѕР±СЏР·Р°С‚РµР»СЊРЅС‹С…
 *     registrations РІ applicationВ».</li>
 * </ol>
 */
class PlatformTelemetryModuleTest {

    private static final Path MODULE = Path.of("platform-telemetry");

    private static final String IMPORTS_RESOURCE =
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** Reviewed-Р±СЋРґР¶РµС‚ СЃСЂРµР·Р°: РїРѕРґСЃРёСЃС‚РµРјР° С†РµР»РёРєРѕРј РјРёРЅСѓСЃ Vaadin-Р°РґР°РїС‚РµСЂС‹ (РѕРЅРё РІ org.ip). */
    private static final Set<String> REVIEWED_TYPES = Set.of(
        "org.ipro.telemetry.api.AggregateStats",
        "org.ipro.telemetry.api.DeclaredNameSource",
        "org.ipro.telemetry.api.EventSink",
        "org.ipro.telemetry.api.EventType",
        "org.ipro.telemetry.api.FieldAudit",
        "org.ipro.telemetry.api.FieldChangeRecord",
        "org.ipro.telemetry.api.Measured",
        "org.ipro.telemetry.api.OperationScope",
        "org.ipro.telemetry.api.SqlStatementAudit",
        "org.ipro.telemetry.api.Telemetry",
        "org.ipro.telemetry.api.TelemetryEvent",
        "org.ipro.telemetry.api.TraceService",
        "org.ipro.telemetry.api.UserContext",
        "org.ipro.telemetry.config.FieldAuditSelfTest",
        "org.ipro.telemetry.config.TelemetryAutoConfiguration",
        "org.ipro.telemetry.config.TelemetryProperties",
        "org.ipro.telemetry.config.TraceSelfTest",
        "org.ipro.telemetry.core.AppLifecycleLogger",
        "org.ipro.telemetry.core.AsyncEventSink",
        "org.ipro.telemetry.core.CompositeOperationHandler",
        "org.ipro.telemetry.core.DeclaredNameBridge",
        "org.ipro.telemetry.core.EntitySnapshot",
        "org.ipro.telemetry.core.ExecutionTimeAspect",
        "org.ipro.telemetry.core.FieldAuditAccumulator",
        "org.ipro.telemetry.core.FieldAuditBridge",
        "org.ipro.telemetry.core.FieldAuditIntegrator",
        "org.ipro.telemetry.core.FieldAuditIntegratorProvider",
        "org.ipro.telemetry.core.FieldAuditListener",
        "org.ipro.telemetry.core.FieldAuditOperationHandler",
        "org.ipro.telemetry.core.FieldAuditQueryService",
        "org.ipro.telemetry.core.FieldChange",
        "org.ipro.telemetry.core.Frame",
        "org.ipro.telemetry.core.JournalQueryService",
        "org.ipro.telemetry.core.JournalSearchService",
        "org.ipro.telemetry.core.JsonLayout",
        "org.ipro.telemetry.core.MdcKeys",
        "org.ipro.telemetry.core.NoopEventSink",
        "org.ipro.telemetry.core.Operation",
        "org.ipro.telemetry.core.OperationCompletionHandler",
        "org.ipro.telemetry.core.OperationContext",
        "org.ipro.telemetry.core.PayloadJson",
        "org.ipro.telemetry.core.PerfCounterStore",
        "org.ipro.telemetry.core.RetentionPurgeJob",
        "org.ipro.telemetry.core.SecurityEventLogger",
        "org.ipro.telemetry.core.SlowOperationHandler",
        "org.ipro.telemetry.core.SqlNormalizer",
        "org.ipro.telemetry.core.SqlRecord",
        "org.ipro.telemetry.core.SqlStatementAuditBridge",
        "org.ipro.telemetry.core.SqlStatementInspector",
        "org.ipro.telemetry.core.SqlStatementListener",
        "org.ipro.telemetry.core.SqlTimingBridge",
        "org.ipro.telemetry.core.TelemetryBridge",
        "org.ipro.telemetry.core.TelemetryGuard",
        "org.ipro.telemetry.core.TelemetryService",
        "org.ipro.telemetry.core.TraceDumpHandler",
        "org.ipro.telemetry.core.TraceFileRenderer",
        "org.ipro.telemetry.core.TraceRequestFilter",
        "org.ipro.telemetry.core.TraceServiceImpl",
        "org.ipro.telemetry.core.TreeJsonRenderer",
        "org.ipro.telemetry.core.TreeRenderer",
        "org.ipro.telemetry.core.WindowReporter",
        "org.ipro.telemetry.model.EntityChangeLogEntity",
        "org.ipro.telemetry.model.OperationLogEntity",
        "org.ipro.telemetry.model.PerfStatsEntity",
        "org.ipro.telemetry.model.TraceSettingsEntity",
        "org.ipro.telemetry.repository.OperationLogRepository");

    private static final Set<String> REVIEWED_DEPENDENCIES = Set.of(
        "platform-settings",
        "jakarta.persistence-api",
        "hibernate-core",
        "spring-data-jpa",
        "spring-jdbc",
        "spring-tx",
        "spring-web",
        "spring-security-core",
        "spring-boot-autoconfigure",
        "spring-boot-persistence",
        "aspectjweaver",
        "jackson-databind",
        "logback-classic",
        "jakarta.servlet-api",
        "slf4j-api",
        "spring-boot-starter-test");

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
            if (!artifactId.equals("platform-telemetry")
                    && !artifactId.equals("spring-boot-starter-parent")) {
                declared.add(artifactId);
            }
        }

        assertThat(declared).isEqualTo(new TreeSet<>(REVIEWED_DEPENDENCIES));
        String pom = read(MODULE.resolve("pom.xml"));
        assertThat(pom).doesNotContain("<groupId>org.ip</groupId>");
        assertThat(pom).doesNotContain("<artifactId>Vaa25_1</artifactId>");
        assertThat(pom).doesNotContain("vaadin");
    }

    @Test
    void moduleOwnsItsPersistenceAndTheApplicationNoLongerDoes() {
        String autoConfiguration =
            read(MODULE.resolve("src/main/java/org/ipro/telemetry/config/TelemetryAutoConfiguration.java"));
        assertThat(declaredAnnotations(autoConfiguration))
            .as("РјРѕРґСѓР»СЊ РѕР±СЏР·Р°РЅ РѕР±СЉСЏРІР»СЏС‚СЊ Рё persistence unit, Рё Spring Data: РёРЅР°С‡Рµ РµРіРѕ"
                + " СЃСѓС‰РЅРѕСЃС‚Рё Рё СЂРµРїРѕР·РёС‚РѕСЂРёРё РѕСЃС‚Р°СЋС‚СЃСЏ РІ Р°СЂС‚РµС„Р°РєС‚Рµ Р±РµР· СЂРµРіРёСЃС‚СЂР°С†РёРё")
            .containsExactlyInAnyOrder("EntityScan", "EnableJpaRepositories");
        assertThat(autoConfiguration).contains("\"org.ipro.telemetry.model\"");
        assertThat(autoConfiguration).contains("\"org.ipro.telemetry.repository\"");
        assertThat(autoConfiguration)
            .as("Vaadin-Р°РґР°РїС‚РµСЂС‹ Р¶РёРІСѓС‚ РІ РїСЂРёР»РѕР¶РµРЅРёРё, Р° РЅРµ РІ РјРѕРґСѓР»Рµ: РЅРё РёРјРїРѕСЂС‚Р° Р°РґР°РїС‚РµСЂР°,"
                + " РЅРё Vaadin-API")
            .doesNotContain("import org.ip.telemetry.vaadin")
            .doesNotContain("import com.vaadin")
            .doesNotContain("com.vaadin");

        String application = read(Path.of("src/main/java/org/ip/Application.java"));
        assertThat(application)
            .as("РїСЂРёР»РѕР¶РµРЅРёРµ Р±РѕР»СЊС€Рµ РЅРµ РїРµСЂРµС‡РёСЃР»СЏРµС‚ РїР°РєРµС‚С‹ РїРѕРґСЃРёСЃС‚РµРјС‹")
            .doesNotContain("org.ipro.telemetry");

        String hub = read(Path.of("platform-rls/src/main/java/org/ipro/rls/config/RlsAutoConfiguration.java"));
        assertThat(hub)
            .as("модуль RLS не владеет чужими пакетами (импорты артефакта platform-telemetry легальны — это направление rls → telemetry)")
            .doesNotContain("\"org.ipro.telemetry");

        assertThat(lines(MODULE.resolve("src/main/resources").resolve(IMPORTS_RESOURCE)))
            .containsExactly("org.ipro.telemetry.config.TelemetryAutoConfiguration");
        assertThat(lines(Path.of("src/main/resources").resolve(IMPORTS_RESOURCE)))
            .as("РїСЂРёР»РѕР¶РµРЅРёРµ РЅРµ РґРѕР»Р¶РЅРѕ СЂРµРіРёСЃС‚СЂРёСЂРѕРІР°С‚СЊ С‡СѓР¶СѓСЋ Р°РІС‚Рѕ-РєРѕРЅС„РёРіСѓСЂР°С†РёСЋ: РјРѕРґСѓР»СЊ РґРµР»Р°РµС‚"
                + " СЌС‚Рѕ СЃР°Рј")
            .doesNotContain("org.ipro.telemetry.config.TelemetryAutoConfiguration");
    }

    @Test
    void subsystemLeftTheApplicationTree() {
        assertThat(Path.of("src/main/java/org/ipro/telemetry/api/Telemetry.java")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/telemetry/core/TelemetryService.java")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/telemetry/model/OperationLogEntity.java")).doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/telemetry/repository/OperationLogRepository.java"))
            .doesNotExist();
        assertThat(Path.of("src/main/java/org/ipro/telemetry/core/TelemetryVaadinInitListener.java"))
            .as("Vaadin-Р°РґР°РїС‚РµСЂ РїРµСЂРµРµС…Р°Р» РІ РґРµСЂРµРІРѕ РїСЂРёР»РѕР¶РµРЅРёСЏ")
            .doesNotExist();
        assertThat(Path.of("src/main/java/org/ip/telemetry/vaadin/TelemetryVaadinInitListener.java"))
            .exists();
    }

    /**
     * Р¤Р°Р№Р»РѕРІС‹Рµ РїСЂРѕРІРµСЂРєРё РіРѕРІРѕСЂСЏС‚, РіРґРµ С‚РёРїС‹ РѕР±СЉСЏРІР»РµРЅС‹; СЌС‚Р° вЂ” РѕС‚РєСѓРґР° РѕРЅРё Р±РµСЂСѓС‚СЃСЏ РІ СЂР°РЅС‚Р°Р№РјРµ.
     */
    @Test
    void subsystemClassesAreResolvedFromTheArtifactAtRuntime() {
        for (Class<?> type : List.of(TelemetryService.class, JournalQueryService.class,
                TelemetryAutoConfiguration.class)) {
            CodeSource codeSource = type.getProtectionDomain().getCodeSource();
            assertThat(codeSource).as("%s РґРѕР»Р¶РµРЅ РіСЂСѓР·РёС‚СЊСЃСЏ РёР· Р°СЂС‚РµС„Р°РєС‚Р°", type.getName()).isNotNull();
            assertThat(codeSource.getLocation().toString())
                .as("%s: РєР»Р°СЃСЃ РѕР±СЏР·Р°РЅ РїСЂРёС…РѕРґРёС‚СЊ РёР· platform-telemetry, Р° РЅРµ РёР· target/classes",
                    type.getName())
                .contains("platform-telemetry");
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
