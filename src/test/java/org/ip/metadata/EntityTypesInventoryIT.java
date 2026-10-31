package org.ip.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ipro.crud.InternedEntity;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposureOverride;
import org.ipro.metadata.*;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.query.QueryBuilderMetadataCatalog;
import org.ipro.register.RegisterRegistry;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.catalog.RestReadOperation;
import org.ipro.rls.RlsReadGate;
import org.ipro.rls.RlsCurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Opt-in measurement of existing runtime facts; never a registration source. */
@EnabledIfSystemProperty(named = "entity.inventory.output", matches = ".+")
@SpringBootTest(classes = Application.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:entity-types-inventory;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class EntityTypesInventoryIT {
    @MockitoBean DataInitializer demo;
    @Autowired EntityManagerFactory emf;
    @Autowired ManagedEntityCatalog managed;
    @Autowired MetadataResolver resolver;
    @Autowired EntityDescriptorCatalog descriptors;
    @Autowired SectionMetadataRegistry sections;
    @Autowired RegisterRegistry registers;
    @Autowired org.ipro.vaadin.explorer.EntitySummaryAssembler explorer;
    @Autowired ManagedEntityTypes managedTypes;
    @Autowired MetadataConsistencyStartupCheck consistency;
    @Autowired RestResourceCatalog rest;
    @Autowired(required = false) List<EntityExposureOverride> exposureOverrides = List.of();

    @Test
    void captureReadOnlyRuntimeFacts() throws Exception {
        var types = managed.managedEntityClasses().stream().sorted(Comparator.comparing(Class::getName)).toList();
        assertThat(types).containsExactlyInAnyOrderElementsOf(emf.getMetamodel().getEntities().stream()
            .map(e -> e.getJavaType()).toList());
        // Measure the publication ceiling with a test-only allow gate; no data query executes.
        var gate = mock(RlsReadGate.class);
        var user = mock(RlsCurrentUser.class);
        when(user.requireAuthenticatedUsername()).thenReturn("inventory-measurement");
        when(gate.canRead(any(), anyString())).thenReturn(true);
        var queryRoots = new QueryBuilderMetadataCatalog(managedTypes, resolver, gate, user, descriptors).roots();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Class<?> type : types) {
            Map<String, Object> row = new LinkedHashMap<>();
            rows.add(row);
            var descriptor = descriptors.descriptorOf(type);
            row.put("type", type.getName());
            row.put("classification", descriptors.classificationOf(type));
            row.put("codeSource", type.getProtectionDomain().getCodeSource().getLocation().toString());
            row.put("jpaName", emf.getMetamodel().entity(type).getName());
            row.put("standardAncestry", StandardCatalogEntity.class.isAssignableFrom(type) ? "CATALOG"
                : StandardDocumentEntity.class.isAssignableFrom(type) ? "DOCUMENT" : "NONE");
            row.put("interned", InternedEntity.class.isAssignableFrom(type));
            row.put("rawAnnotation", type.isAnnotationPresent(EntityMetadata.class));
            row.put("declaredAnnotation", type.getDeclaredAnnotation(EntityMetadata.class) != null);
            row.put("exposure", descriptor.exposure().name());
            row.put("exposureOrigin", descriptor.exposureOrigin().name());
            row.put("exposureSymbol", descriptor.exposureSymbol());
            row.put("exposureReason", descriptor.reason());
            row.put("reads", descriptor.capabilities().readScenarios().stream().map(Enum::name).sorted().toList());
            row.put("writes", descriptor.capabilities().writes().stream().map(Enum::name).sorted().toList());
            row.put("capabilitiesOrigin", descriptor.capabilitiesOrigin().name());
            row.put("capabilitiesSymbol", descriptor.capabilitiesSymbol());
            row.put("capabilitiesReason", descriptor.capabilities().reason());
            row.put("persistentAttributes", emf.getMetamodel().entity(type).getAttributes().stream()
                .sorted(Comparator.comparing(a -> a.getName()))
                .map(a -> Map.of("name", a.getName(), "javaType", a.getJavaType().getTypeName(),
                    "kind", a.getPersistentAttributeType().name(),
                    "declaringType", a.getDeclaringType().getJavaType().getName())).toList());
            try {
                var metadata = resolver.resolve(type);
                row.put("resolution", "RESOLVED");
                row.put("localKind", metadata.getEntityKind().name());
                row.put("kindOrigin", metadata.getEntityKindOrigin().name());
                row.put("kindSymbol", metadata.getEntityKindSymbol());
                row.put("subsystem", metadata.getSubsystem().getName());
                row.put("form", metadata.getFormFields().stream().map(FieldMetadataInfo::getName).toList());
                row.put("list", metadata.getListColumnPaths().stream().map(ColumnPath::getKey).toList());
                row.put("selection", metadata.getSelectColumnPaths().stream().map(ColumnPath::getKey).toList());
            } catch (IllegalArgumentException error) {
                row.put("resolution", MetadataResolver.isApplicable(type) ? "INVALID" : "ABSENT");
                row.put("resolutionError", error.getClass().getName() + ": " + error.getMessage());
            }
            row.put("sections", sections.forOwner(type).stream().map(s -> Map.of(
                "row", s.getRowClass().getName(), "key", s.getKey(), "parentField", s.getParentFieldName())).toList());
            row.put("sectionOwner", sections.findByRow(type).map(s -> s.getOwnerClass().getName()).orElse(""));
            row.put("ownershipOverrides", exposureOverrides.stream().filter(o -> o.type().equals(type))
                .map(o -> Map.of("owner", o.owner().getName(), "exposure", o.exposure().name(), "reason", o.reason())).toList());
            sections.findByRow(type).ifPresent(s -> {
                row.put("rowForm", s.getFormFields().stream().map(FieldMetadataInfo::getName).toList());
                row.put("rowGrid", s.getGridFields().stream().map(FieldMetadataInfo::getName).toList());
            });
            row.put("validatedRegister", registers.find(type).isPresent());
            row.put("query", queryRoots.stream().filter(q -> q.javaType().equals(type)).map(q -> Map.of(
                "category", q.category(), "fields", q.fields().stream().map(f -> f.name()).toList(),
                "associations", q.associations().stream().map(a -> a.name()).toList())).toList());
            row.put("rest", rest.resources().stream().filter(r -> r.definition().resourceType().equals(type))
                .map(r -> Map.of("key", r.key().toString(), "fields", r.fields().keySet().stream().sorted().toList(),
                    "list", r.projection(RestReadOperation.LIST), "detail", r.projection(RestReadOperation.DETAIL),
                    "filters", r.filters().keySet().stream().sorted().toList(),
                    "sorts", r.sorts().keySet().stream().sorted().toList())).toList());
            row.put("diagnostics", consistency.diagnostics().stream().filter(d -> d.entity().equals(type.getName())).toList());
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("scope", "Application test context; isolated H2; no demo seeding; query metadata ceiling with test-only allow gate (not user authorization)");
        snapshot.put("types", rows);
        snapshot.put("diagnostics", consistency.diagnostics());
        Map<String, String> runtimeSources = new TreeMap<>();
        for (Class<?> source : List.of(ManagedEntityCatalog.class, MetadataResolver.class,
                EntityDescriptorCatalog.class, SectionMetadataRegistry.class, RegisterRegistry.class,
                QueryBuilderMetadataCatalog.class, RestResourceCatalog.class)) {
            runtimeSources.put(source.getName(), source.getProtectionDomain().getCodeSource().getLocation().toString());
        }
        snapshot.put("runtimeSources", runtimeSources);
        Path output = Path.of(System.getProperty("entity.inventory.output"));
        assertThat(Files.isDirectory(output.toAbsolutePath().getParent())).isTrue();
        new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), snapshot);
        StringBuilder report = new StringBuilder("# ET0.2 — измеренный инвентарь managed-типов\n\n");
        report.append("Сгенерировано `EntityTypesInventoryIT`; источник — PU `default` приложения на изолированной H2.\n\n");
        for (String key : List.of("exposure", "resolution", "localKind", "standardAncestry")) {
            Map<String, Long> counts = new TreeMap<>();
            rows.forEach(r -> counts.merge(String.valueOf(r.getOrDefault(key, "N/A")), 1L, Long::sum));
            report.append("- ").append(key).append(": ").append(counts).append("\n");
        }
        report.append("- managed: ").append(rows.size()).append("\n");
        report.append("- query roots (publication ceiling): ").append(queryRoots.size()).append("\n");
        report.append("- REST resources: ").append(rest.resources().size()).append("\n");
        report.append("- validated registers: ").append(registers.descriptors().size()).append("\n");
        Map<String, Long> diagnosticCounts = new TreeMap<>();
        consistency.diagnostics().forEach(d -> diagnosticCounts.merge(d.severity() + "/" + d.code(), 1L, Long::sum));
        report.append("- diagnostics: ").append(diagnosticCounts).append("\n\n");
        report.append("`—` означает отсутствие root-метаданных; kind строки не выводится из owner. Публикации и полные FQN — в JSON.\n\n");
        report.append("| Type | Local kind / origin | Exposure | Writes | Interned | Section owner |\n");
        report.append("| --- | --- | --- | --- | --- | --- |\n");
        for (var row : rows) {
            report.append("| ").append(row.get("type")).append(" | ")
                .append(row.getOrDefault("localKind", "—")).append(" / ").append(row.getOrDefault("kindOrigin", "—"))
                .append(" | ").append(row.get("exposure")).append(" | ").append(row.get("writes"))
                .append(" | ").append(row.get("interned")).append(" | ").append(row.get("sectionOwner")).append(" |\n");
        }
        report.append("\n## Runtime sources\n\n");
        runtimeSources.forEach((key, value) -> report.append("- `").append(key).append("`: `").append(value).append("`\n"));
        Files.writeString(output.resolveSibling(output.getFileName().toString().replace(".json", ".md")), report);
        assertThat(rows).hasSize(emf.getMetamodel().getEntities().size());
        System.out.println("ENTITY_INVENTORY types=" + rows.size() + " output=" + output.toAbsolutePath());
    }

    @Test
    void et6ConsumersAgreeOnValidatedRegisterAndSeparateIdentityFacts() {
        var gate = mock(RlsReadGate.class);
        var user = mock(RlsCurrentUser.class);
        when(user.requireAuthenticatedUsername()).thenReturn("et6-metadata-only");
        when(gate.canRead(any(), anyString())).thenReturn(true);
        var query = new QueryBuilderMetadataCatalog(managedTypes, resolver, gate, user, descriptors);
        var snapshot = explorer.explorerSnapshot();
        for (var register : registers.descriptors()) {
            var fact = descriptors.classificationOf(register.entityType());
            assertThat(fact.kind()).isEqualTo(org.ipro.metadata.annotation.EntityKind.INFORMATION_REGISTER);
            assertThat(fact.recorderOwned()).isEqualTo(register.recorded());
            assertThat(fact.origin()).isEqualTo(FactOrigin.DERIVED);
            assertThat(fact.symbol()).isEqualTo(register.entityType().getName());
            assertThat(query.roots()).filteredOn(root -> root.javaType() == register.entityType())
                .singleElement().satisfies(root -> assertThat(root.category()).isEqualTo("Регистр"));
            assertThat(snapshot.entries()).filteredOn(entry -> entry.type() == register.entityType())
                .singleElement().satisfies(entry -> assertThat(entry.kind()).isEqualTo(fact.kind()));
            assertThat(explorer.summarize(register.entityType()).overview())
                .filteredOn(row -> row.key().kind() == org.ipro.metadata.facet.FacetKind.ENTITY_KIND
                    && row.caption().equals("Тип сущности"))
                .singleElement().satisfies(row -> assertThat(row.value().value()).isEqualTo(fact.kind().name()));
        }
        assertThat(registers.descriptors()).hasSize(2);
        var attribute = descriptors.classificationOf(org.ip.model.AttributeValue.class);
        var operation = descriptors.classificationOf(org.ip.model.SklNomOpa.class);
        assertThat(attribute.kind()).isEqualTo(org.ipro.metadata.annotation.EntityKind.CATALOG);
        assertThat(operation.kind()).isEqualTo(org.ipro.metadata.annotation.EntityKind.PLAIN);
        assertThat(attribute.interned()).isTrue();
        assertThat(operation.interned()).isTrue();
    }

    @Test
    void et7IdentityPolicyPreservesCreateOnlyAndOwnerManagedDifferences() {
        assertThat(descriptors.descriptorOf(org.ip.model.AttributeValue.class).capabilities().writes())
            .containsExactly(org.ipro.data.DataOperation.CREATE);
        assertThat(descriptors.descriptorOf(org.ip.model.SklNomOpa.class).capabilities().writes()).isEmpty();
        assertThat(consistency.diagnostics()).noneMatch(d -> d.severity() == MetadataDiagnostic.Severity.ERROR);
        // Both contracts were checked at startup, without changing named-operation semantics.
        assertThat(descriptors.classificationOf(org.ip.model.AttributeValue.class).interned()).isTrue();
        assertThat(descriptors.classificationOf(org.ip.model.SklNomOpa.class).interned()).isTrue();
    }

    @Test
    void runtimeResolverReceivesJpaMetamodelAndKeepsChannelsSeparate() {
        var metadata = resolver.resolve(org.ip.model.SklNomOpa.class);

        assertThat(metadata.getStructuralAttributes())
            .anyMatch(attribute -> attribute.name().equals("displayName") && !attribute.hasUiDeclaration());
        assertThat(metadata.getStructuralAttributes())
            .anyMatch(attribute -> attribute.name().equals("nomenclature")
                && attribute.persistentKind() == StructuralAttribute.PersistentKind.ASSOCIATION_TO_ONE);
        assertThat(metadata.getFormFields()).noneMatch(field -> field.getName().equals("displayName"));
        assertThat(metadata.getSelectColumnPaths()).extracting(ColumnPath::getKey).contains("displayName");
    }
}
