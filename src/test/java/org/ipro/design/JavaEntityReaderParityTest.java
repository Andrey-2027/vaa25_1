package org.ipro.design;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.model.naming.Identifier;
import org.ipro.design.existing.EntityShape;
import org.ipro.design.existing.ExistingEntity;
import org.ipro.design.source.JavaEntityReader;
import org.ipro.design.source.PhysicalNames;
import org.ipro.design.source.ProjectSources;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.TableSectionMetadata;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * K1 of the entity-modification plan: what the source reader says about every entity of the
 * application model equals what the platform computes from the compiled classes. A field the reader
 * cannot read is locked with a reason, and this test checks that reason against reflection too.
 */
class JavaEntityReaderParityTest {
    private static final Path MODEL = Path.of("src", "main", "java", "org", "ip", "model");
    private final MetadataResolver resolver = new MetadataResolver();

    @Test void readerAgreesWithThePlatformOnTheWholeApplicationModel() throws Exception {
        var project = ProjectSources.scan(List.of(MODEL));
        assertThat(project.complete()).as(project.unparsable().toString()).isTrue();
        var report = new ArrayList<String>();
        int entities = 0, read = 0, locked = 0;
        for (var type : project.types()) {
            if (!type.isEntity()) continue;
            Class<?> entityClass = Class.forName(type.typeId());
            String text = Files.readString(type.root().resolve(type.relativePath()), StandardCharsets.UTF_8);
            ExistingEntity entity = JavaEntityReader.read(type.typeId(), text, project);
            entities++;
            compareClass(entityClass, entity);
            compareFields(entityClass, entity);
            read += entity.draft().fields().size();
            locked += entity.lockedFields().size();
            for (var lockedField : entity.lockedFields())
                report.add(type.simpleName() + "." + lockedField.name() + " : " + lockedField.javaType() + " : " + lockedField.reason());
        }
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target", "entity-reader-parity-report.txt"),
            (entities + " entities, " + read + " fields read, " + locked + " locked\n" + String.join("\n", report) + "\n").getBytes(StandardCharsets.UTF_8));
        assertThat(entities).isGreaterThan(25);
        assertThat(report).noneMatch(line -> line.contains("NOT_LITERAL") || line.contains("UNRESOLVED") || line.contains("TYPE_NOT_SUPPORTED"));
    }

    private void compareClass(Class<?> entityClass, ExistingEntity entity) {
        String name = entityClass.getName();
        EntityShape expected = entityClass.isAnnotationPresent(org.ipro.register.RegisterMetadata.class) ? EntityShape.REGISTER
            : entityClass.isAnnotationPresent(TableSectionMetadata.class) ? EntityShape.SECTION_ROW
            : org.ipro.crud.BaseEntity.class.isAssignableFrom(entityClass) ? EntityShape.ROOT : EntityShape.UNRESOLVED_BASE;
        assertThat(entity.shape()).as(name + " shape").isEqualTo(expected);
        Table table = entityClass.getAnnotation(Table.class);
        String expectedTable = table != null && !table.name().isEmpty() ? table.name()
            : new CamelCaseToUnderscoresNamingStrategy().toPhysicalTableName(Identifier.toIdentifier(entityClass.getSimpleName()), null).getText();
        assertThat(entity.tableName()).as(name + " table").isEqualTo(expectedTable);

        var inherited = new LinkedHashSet<String>();
        var methods = new LinkedHashSet<String>();
        for (Class<?> c = entityClass; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c != entityClass) for (var field : c.getDeclaredFields())
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) inherited.add(field.getName());
            for (var method : c.getDeclaredMethods())
                if (!method.isSynthetic() && !method.isBridge() && !Modifier.isPrivate(method.getModifiers())) methods.add(method.getName());
        }
        assertThat(entity.inheritedNames()).as(name + " inherited").isEqualTo(inherited);
        assertThat(entity.occupiedMethodNames()).as(name + " methods").containsAll(methods);
    }

    private void compareFields(Class<?> entityClass, ExistingEntity entity) {
        String name = entityClass.getName();
        var declared = new LinkedHashSet<String>();
        for (var field : entityClass.getDeclaredFields())
            if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) declared.add(field.getName());
        var accounted = new LinkedHashSet<String>();
        entity.draft().fields().forEach(f -> accounted.add(f.name()));
        entity.lockedFields().forEach(f -> accounted.add(f.name()));
        assertThat(accounted).as(name + ": every declared field is read or locked, none twice").hasSameElementsAs(declared);
        assertThat(entity.draft().fields().size() + entity.lockedFields().size()).isEqualTo(declared.size());

        var platform = resolver.resolveAllAnnotatedFields(entityClass).stream()
            .filter(info -> info.getField().getDeclaringClass() == entityClass).toList();
        var byName = platform.stream().collect(Collectors.toMap(FieldMetadataInfo::getName, info -> info));
        var metadata = MetadataResolver.isApplicable(entityClass) ? resolver.resolve(entityClass) : null;
        boolean row = entity.shape() == EntityShape.SECTION_ROW;
        var listRoots = metadata == null ? Set.<String>of() : rootNames(metadata.getListColumnPaths());
        var selectRoots = metadata == null ? Set.<String>of() : rootNames(metadata.getSelectColumnPaths());

        for (var field : entity.draft().fields()) {
            String at = name + "." + field.name();
            FieldMetadataInfo info = byName.get(field.name());
            assertThat(info).as(at + " has @FieldMetadata").isNotNull();
            assertThat(field.label()).as(at + " label").isEqualTo(info.getLabel());
            assertThat(field.serverRequired()).as(at + " server required").isEqualTo(info.isServerRequired());
            boolean uiRequired = switch (field.uiRequired()) {
                case REQUIRED -> true; case OPTIONAL -> false; case AUTO -> field.serverRequired();
            };
            assertThat(uiRequired).as(at + " ui required").isEqualTo(info.isRequired());
            assertThat(field.applications().form()).as(at + " form").isEqualTo(!info.isHidden());
            if (row) {
                assertThat(field.applications().table()).as(at + " table").isEqualTo(info.isGridVisible());
            } else if (metadata != null) {
                assertThat(field.applications().list()).as(at + " list").isEqualTo(listRoots.contains(field.name()));
                assertThat(field.applications().selection()).as(at + " selection").isEqualTo(selectRoots.contains(field.name()));
            }
            var java = info.getField();
            assertThat(field.type()).as(at + " type").isEqualTo(expectedType(java.getType()));
            if (field.type().equals("REFERENCE")) assertThat(field.reference().typeId()).as(at + " target").isEqualTo(java.getType().getName());
            else assertThat(field.reference()).isNull();
            assertConstraints(at, java, field);
            assertColumn(at, java, field);
        }
        // The relative order of the fields that were read is the platform's order among them.
        var platformOrder = platform.stream().sorted(Comparator.comparingInt(FieldMetadataInfo::getOrder)).map(FieldMetadataInfo::getName)
            .filter(n -> entity.draft().fields().stream().anyMatch(f -> f.name().equals(n))).toList();
        assertThat(entity.draft().fields().stream().map(FieldDraft::name).toList()).as(name + " order").isEqualTo(platformOrder);

        for (var locked : entity.lockedFields()) {
            String at = name + "." + locked.name();
            var java = entityClass.getDeclaredFields();
            var field = List.of(java).stream().filter(f -> f.getName().equals(locked.name())).findFirst().orElseThrow();
            switch (locked.reason()) {
                case "NO_FIELD_METADATA" -> assertThat(field.getAnnotation(FieldMetadata.class)).as(at).isNull();
                case "TRANSIENT" -> assertThat(field.isAnnotationPresent(Transient.class) || Modifier.isTransient(field.getModifiers())).as(at).isTrue();
                case "ID" -> assertThat(field.isAnnotationPresent(Id.class)).as(at).isTrue();
                case "COLLECTION" -> assertThat(Collection.class.isAssignableFrom(field.getType())).as(at).isTrue();
                case "ENUM" -> assertThat(field.getType().isEnum()).as(at).isTrue();
                default -> { /* reported, and rejected by the caller when it is a reading gap */ }
            }
        }
    }

    private static Set<String> rootNames(List<org.ipro.metadata.ColumnPath> paths) {
        return paths.stream().map(path -> path.getRootField().getName()).collect(Collectors.toSet());
    }

    private static String expectedType(Class<?> type) {
        if (type == String.class) return "STRING";
        if (type == Integer.class || type == int.class) return "INTEGER";
        if (type == Long.class || type == long.class) return "LONG";
        if (type == java.math.BigDecimal.class) return "DECIMAL";
        if (type == Boolean.class || type == boolean.class) return "BOOLEAN";
        if (type == java.time.LocalDate.class) return "DATE";
        if (type == java.time.LocalDateTime.class) return "DATETIME";
        return "REFERENCE";
    }

    private static void assertConstraints(String at, java.lang.reflect.Field java, FieldDraft field) {
        Column column = java.getAnnotation(Column.class);
        if (field.type().equals("STRING")) {
            Size size = java.getAnnotation(Size.class);
            int expected = column != null && column.length() != 255 ? column.length()
                : size != null && size.max() != Integer.MAX_VALUE ? size.max() : 255;
            assertThat(field.constraints().length()).as(at + " length").isEqualTo(Integer.toString(expected));
        } else if (field.type().equals("DECIMAL")) {
            Digits digits = java.getAnnotation(Digits.class);
            String precision = "", scale = "";
            if (column != null && column.precision() > 0) { precision = Integer.toString(column.precision()); scale = Integer.toString(column.scale()); }
            else if (digits != null) { precision = Integer.toString(digits.integer() + digits.fraction()); scale = Integer.toString(digits.fraction()); }
            else if (column != null && column.scale() > 0) scale = Integer.toString(column.scale());
            assertThat(List.of(field.constraints().precision(), field.constraints().scale())).as(at + " precision, scale").isEqualTo(List.of(precision, scale));
        } else {
            assertThat(field.constraints()).as(at + " constraints").isEqualTo(ConstraintDraft.empty());
        }
    }

    private static void assertColumn(String at, java.lang.reflect.Field java, FieldDraft field) {
        boolean reference = field.type().equals("REFERENCE");
        String declared = reference ? (java.isAnnotationPresent(JoinColumn.class) ? java.getAnnotation(JoinColumn.class).name() : "")
            : (java.isAnnotationPresent(Column.class) ? java.getAnnotation(Column.class).name() : "");
        var strategy = new CamelCaseToUnderscoresNamingStrategy();
        String implicit = strategy.toPhysicalColumnName(Identifier.toIdentifier(reference ? java.getName() + "_id" : java.getName()), null).getText();
        String expected = declared.isEmpty() ? implicit : declared;
        assertThat(DraftSuggestions.column(field)).as(at + " column").isEqualTo(expected);
        assertThat(PhysicalNames.implicit(reference ? java.getName() + "_id" : java.getName())).as(at + " implicit").isEqualTo(implicit);
    }

    @Test void implicitNamesFollowTheRealNamingStrategyOnEveryEntityAndFieldName() throws Exception {
        var strategy = new CamelCaseToUnderscoresNamingStrategy();
        var project = ProjectSources.scan(List.of(MODEL));
        int names = 0;
        for (var type : project.types()) {
            Class<?> c = Class.forName(type.typeId());
            assertThat(PhysicalNames.implicitTable(c.getSimpleName())).as(c.getSimpleName())
                .isEqualTo(strategy.toPhysicalTableName(Identifier.toIdentifier(c.getSimpleName()), null).getText());
            for (var field : c.getDeclaredFields()) {
                if (field.isSynthetic()) continue;
                assertThat(PhysicalNames.implicitColumn(field.getName())).as(field.getName())
                    .isEqualTo(strategy.toPhysicalColumnName(Identifier.toIdentifier(field.getName()), null).getText());
                assertThat(PhysicalNames.implicitJoinColumn(field.getName())).as(field.getName() + "_id")
                    .isEqualTo(strategy.toPhysicalColumnName(Identifier.toIdentifier(field.getName() + "_id"), null).getText());
                names++;
            }
        }
        assertThat(names).isGreaterThan(100);
    }
}
