import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.*;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.ipro.vaadin.explorer.ExplorerSnapshot;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.*;

/** R0 bounded fixture. Uses production managed-type/descriptor paths, never an entity scan. */
public final class EntityRegistryInventory {
    public static void main(String[] args) throws Exception {
        var types = new ManagedEntityTypes(List.of(
            org.ip.model.GroupNom.class, org.ip.model.Nomenclature.class,
            org.ip.model.NomAttributeValue.class, org.ip.model.ReceivingDocument.class,
            org.ip.model.ReceivingDocumentItem.class, org.ip.model.NomenclaturePrice.class,
            org.ip.model.SklNomOpa.class, org.ipro.settings.SettingValue.class));
        var metadata = new MetadataResolver();
        var sections = new SectionMetadataRegistry(types, metadata); sections.rebuild();
        var managedCatalog = new ManagedEntityCatalog(types);
        var registers = new org.ipro.register.RegisterRegistry(managedCatalog);
        var descriptors = new EntityDescriptorCatalog(managedCatalog, sections, metadata, List.of(), List.of(),
            List.of(new org.ipro.register.RegisterClassificationContributor(registers)));
        var forms = new FormRegistry();
        var routes = FormRouteCatalog.build(descriptors, forms, List.of());
        var references = new ReferenceIndex(types); references.rebuild();
        var numbering = new NumberingMetadataRegistry(types, MetadataResolver::isApplicable); numbering.rebuild();
        var subsystems = new SubsystemRegistry("org.ip.subsystem", types, metadata); subsystems.rebuild();
        var assembler = new EntitySummaryAssembler(types, metadata, forms, references, numbering,
            subsystems, FacetResolver.none(), descriptors, routes, null, sections,
            null, null, null, null, null, null);
        // One shared immutable snapshot for every count and entry below.
        ExplorerSnapshot snapshot = assembler.explorerSnapshot();
        var managed = types.all().stream().map(type -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("type", type.getName());
            row.put("exposure", descriptors.descriptorOf(type).exposure().name());
            row.put("inExplorer", snapshot.entries().stream().anyMatch(e -> e.type().equals(type)));
            return row;
        }).toList();
        var entries = snapshot.entries().stream().map(entry -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("type", entry.typeFqn()); row.put("label", entry.displayName().value());
            row.put("root", entry.rootCandidate());
            row.put("kind", entry.kind() == null ? null : entry.kind().name());
            row.put("exposure", entry.descriptor() == null ? null : entry.descriptor().exposure().name());
            row.put("subsystem", entry.subsystem().map(ExplorerSnapshot.SubsystemRef::id).orElse(null));
            row.put("publishedKey", entry.publishedKey().orElse(null));
            row.put("ownedSections", entry.ownedSections().stream().map(ExplorerSnapshot.OwnedSection::id).toList());
            row.put("state", entry.state().name()); row.put("failureReason", entry.failureReason());
            row.put("countsKnown", entry.countsKnown());
            row.put("errors", entry.countsKnown() ? entry.errorCount() : null);
            row.put("warnings", entry.countsKnown() ? entry.warningCount() : null);
            row.put("diagnostics", entry.diagnostics());
            return row;
        }).toList();
        var counts = new LinkedHashMap<String, Object>();
        counts.put("managed", managed.size()); counts.put("entries", entries.size());
        counts.put("roots", snapshot.entries().stream().filter(ExplorerSnapshot.Entry::rootCandidate).count());
        counts.put("managedOwned", managed.stream().filter(e -> "OWNED_ROW".equals(e.get("exposure"))).count());
        counts.put("explorerOwned", snapshot.entries().stream().filter(e -> !e.rootCandidate()).count());
        counts.put("managedService", managed.stream().filter(e -> service((String) e.get("exposure"))).count());
        counts.put("explorerService", entries.stream().filter(e -> service((String) e.get("exposure"))).count());
        counts.put("unknownExposure", entries.stream().filter(e -> e.get("exposure") == null).count());
        counts.put("summarizeAttempts", snapshot.stats().summarizeAttempts());
        counts.put("summarizeFailures", snapshot.stats().summarizeFailures());
        counts.put("unassignedDiagnostics", snapshot.unassignedDiagnostics().size());
        var result = new LinkedHashMap<String, Object>();
        result.put("scope", "bounded-real-model-fixture; NOT a production persistence-unit inventory");
        result.put("limitations", List.of("Explicit eight-type set; references outside the set are not managed here",
            "Empty FormRegistry; derived routes only; no application route aliases or form registrations",
            "Validated independent-register contributor; no recorded-register/recorder metamodel validation",
            "No startup diagnostics, lifecycle, action, fetch or RLS services",
            "Subsystem marker discovery in org.ip.subsystem; no entity classpath scan; no database"));
        result.put("counts", counts); result.put("managedTypes", managed); result.put("entries", entries);
        result.put("diagnosticsAvailable", snapshot.diagnosticsAvailable());
        result.put("unassignedDiagnostics", snapshot.unassignedDiagnostics());
        JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), result);
        System.out.println(counts);
    }

    private static boolean service(String exposure) {
        return EntityExposure.INTERNAL_STORE.name().equals(exposure) || EntityExposure.UNCLASSIFIED.name().equals(exposure);
    }
}
