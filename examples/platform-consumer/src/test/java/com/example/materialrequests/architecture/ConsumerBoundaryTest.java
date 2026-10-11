package com.example.materialrequests.architecture;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsumerBoundaryTest {

    // Reviewed consumer usage, not an allowance for every public class in a platform JAR.
    private static final Set<String> ALLOWED_PLATFORM_TYPES = Set.of(
            "org/ipro/data/EntityDataAccess",
            "org/ipro/form/MetadataDrivenItemFormSaveAdapter",
            "org/ipro/rls/AccessGrant",
            "org/ipro/rls/RlsAccessDeniedException",
            "org/ipro/rls/RlsCurrentUser",
            "org/ipro/rls/RlsRoleResolver",
            "org/ipro/rls/AccessGrant$SubjectType",
            "org/ipro/rls/AccessGrantRepository",
            "org/ipro/rls/RlsDimension",
            "org/ipro/rls/c5/C5PermissionKeys",
            "org/ipro/crud/BaseEntity",
            "org/ipro/identity/IdentifiableEntity",
            "org/ipro/fetch/instance/InstanceName",
            "org/ipro/metadata/annotation/Subsystem",
            "org/ipro/metadata/annotation/EntityMetadata",
            "org/ipro/metadata/annotation/EntityKind",
            "org/ipro/metadata/annotation/FieldMetadata",
            "org/ipro/metadata/annotation/Lookup",
            "org/ipro/metadata/annotation/TableSections",
            "org/ipro/metadata/annotation/TableSectionMetadata",
            "org/ipro/metadata/annotation/SectionRlsPolicy",
            "org/ipro/metadata/MetadataResolver",
            "org/ipro/metadata/RowMetadataInfo",
            "org/ipro/metadata/FieldMetadataInfo",
            "org/ipro/form/FieldFactory",
            "org/ipro/form/EntityField",
            "org/ipro/numbering/annotation/Numbered",
            "org/ipro/lifecycle/EntityLifecycle",
            "org/ipro/lifecycle/AggregateSaveContext",
            "org/ipro/lifecycle/EntityChangedContext",
            "org/ipro/lifecycle/EntitySaveContext",
            "org/ipro/form/FormSaveResult$Success",
            "org/ipro/form/FormSaveResult$Failure",
            "org/ipro/form/FormSaveResult$Conflict",
            "org/ipro/form/FormSaveResult",
            "org/ipro/form/FormSaveHandler",
            "org/ipro/metadata/HasDisplayName",
            "org/ipro/form/builder/ItemFormCustomization",
            "org/ipro/form/builder/ItemFormVariants",
            "org/ipro/form/registry/FormContext",
            "org/ipro/form/registry/FormFactory",
            "org/ipro/form/coordinator/FormNavigator",
            "org/ipro/form/builtin/ItemForm",
            "org/ipro/form/builtin/ItemTable",
            "org/ipro/form/builtin/ListForm",
            "org/ipro/form/spi/FormSettingsStore",
            "org/ipro/form/spi/GridViewStore",
            "org/ipro/form/spi/GridView");
    private static final Pattern PLATFORM_TYPE = Pattern.compile("org/ipro/[A-Za-z0-9_/$]+");
    private static final Pattern ORIGINAL_APP_TYPE = Pattern.compile("org/ip/[A-Za-z0-9_/$]+");

    @Test
    void runtimeHasPlatformArtifactsAndNoOriginalApplicationOrReportStack() throws Exception {
        String tree = Files.readString(Path.of("target/consumer-runtime-dependency-tree.json"));
        assertThat(tree).contains("platform-vaadin-starter", "platform-spring-boot-autoconfigure")
                .doesNotContain("Vaa25_1", "ureport3-console", "dynamicreports-core", "reportui-core",
                        "platform-rest-web");
        ClassLoader loader = getClass().getClassLoader();
        assertThatThrownBy(() -> Class.forName("org.ip.Application", false, loader))
                .isInstanceOf(ClassNotFoundException.class);
        Class<?> platformType = Class.forName("org.ipro.rls.RlsCurrentUser", false, loader);
        assertThat(platformType.getProtectionDomain().getCodeSource().getLocation().toString())
                .endsWith(".jar");
    }

    @Test
    void compiledConsumerUsesOnlyReviewedApplicationApiAndSpi() throws IOException {
        Set<String> used = new TreeSet<>();
        for (Path directory : new Path[]{Path.of("target/classes"), Path.of("target/test-classes")}) {
            try (var files = Files.walk(directory)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                    for (String constant : utf8Constants(file)) {
                        // Dotted strings in this negative test name an absent class, not a dependency.
                        assertThat(ORIGINAL_APP_TYPE.matcher(constant).find())
                                .as("original app type in %s: %s", file, constant).isFalse();
                        var matcher = PLATFORM_TYPE.matcher(constant);
                        while (matcher.find()) {
                            used.add(matcher.group());
                        }
                    }
                }
            }
        }
        assertThat(used).isNotEmpty().isSubsetOf(ALLOWED_PLATFORM_TYPES);
    }

    private static Set<String> utf8Constants(Path file) throws IOException {
        Set<String> strings = new TreeSet<>();
        try (var input = new DataInputStream(Files.newInputStream(file))) {
            if (input.readInt() != 0xCAFEBABE) {
                throw new IOException("Not a Java class: " + file);
            }
            input.skipNBytes(4);
            int count = input.readUnsignedShort();
            for (int index = 1; index < count; index++) {
                switch (input.readUnsignedByte()) {
                    case 1 -> strings.add(input.readUTF());
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                    case 5, 6 -> { input.skipNBytes(8); index++; }
                    case 7, 8, 16, 19, 20 -> input.skipNBytes(2);
                    case 15 -> input.skipNBytes(3);
                    default -> throw new IOException("Unknown constant pool tag: " + file);
                }
            }
        }
        return strings;
    }
}
