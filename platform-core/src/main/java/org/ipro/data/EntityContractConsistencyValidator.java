package org.ipro.data;

import org.ipro.metadata.*;
import org.ipro.numbering.NumberingMetadataRegistry;
import java.util.*;

/** Upper-layer relationship checks over ready descriptors; structural validation stays with each owner. */
public final class EntityContractConsistencyValidator {
    private EntityContractConsistencyValidator() { }

    public static List<MetadataDiagnostic> validate(MetadataResolver resolver, ManagedEntityTypes types,
            EntityDescriptorCatalog descriptors, NumberingMetadataRegistry numbering) {
        Objects.requireNonNull(descriptors, "descriptors");
        List<MetadataDiagnostic> diagnostics = new ArrayList<>();
        for (Class<?> type : types.all()) {
            var descriptor = descriptors.descriptorOf(type);
            var classification = descriptors.classificationOf(type);
            if (classification.interned() && (descriptor.capabilities().allows(org.ipro.data.DataOperation.UPDATE)
                    || descriptor.capabilities().allows(org.ipro.data.DataOperation.DELETE))) {
                diagnostics.add(error(MetadataDiagnosticCodes.INTERNED_GENERIC_WRITES, type, "",
                    "EntityDescriptorCatalog.capabilities / InternedEntity",
                    "interned type permits generic UPDATE/DELETE: " + descriptor.capabilities().writes()
                        + "; use explicit named operations; CREATE policy remains independent"));
            }
            if (numbering != null) for (var field : numbering.forEntity(type)) {
                var role = field.definition().role();
                var requiredKind = switch (role) {
                    case CATALOG_CODE -> org.ipro.metadata.annotation.EntityKind.CATALOG;
                    case DOCUMENT_NUMBER -> org.ipro.metadata.annotation.EntityKind.DOCUMENT;
                    case CUSTOM -> null;
                };
                if (requiredKind != null && classification.kind() != requiredKind)
                    diagnostics.add(error(MetadataDiagnosticCodes.NUMBERING_ROLE_KIND_CONFLICT,
                        type, field.fieldName(), "NumberingMetadataRegistry / EntityDescriptorCatalog.classification",
                        role + " requires " + requiredKind + ", effective kind is " + classification.kind()));
            }
            if (descriptor.exposure() != org.ipro.data.EntityExposure.STANDARD_ROOT
                    && descriptor.exposure() != org.ipro.data.EntityExposure.OWNED_ROW) continue;
            for (var field : resolver.resolveAllAnnotatedFields(type)) {
                if (!publishedLookup(field) || !MetadataResolver.isApplicable(field.getLookupEntity())) continue;
                var target = descriptors.descriptorOf(field.getLookupEntity());
                if (target.exposure() != org.ipro.data.EntityExposure.STANDARD_ROOT
                        || !target.capabilities().allows(org.ipro.fetch.plan.FetchScenario.LOOKUP))
                    diagnostics.add(error(MetadataDiagnosticCodes.LOOKUP_TARGET_NOT_ACCESSIBLE,
                        type, field.getName(), "EntityDescriptorCatalog.exposure/capabilities / @Lookup",
                        "published lookup target " + field.getLookupEntity().getName()
                            + " has exposure " + target.exposure() + " and no standalone lookup contract"));
            }
        }
        return List.copyOf(diagnostics);
    }

    private static MetadataDiagnostic error(String code, Class<?> type, String field, String source, String message) {
        return new MetadataDiagnostic(MetadataDiagnostic.Severity.ERROR, code, type.getName(), field, source, message);
    }

    private static boolean publishedLookup(FieldMetadataInfo field) {
        return field.hasLookup() && (!field.isHidden() || field.isGridVisible());
    }

}
