package org.ipro.design.runtime;

import org.ipro.crud.InterningContracts;
import org.ipro.design.RuntimeDesignSnapshot;
import org.ipro.design.RuntimeDesignSnapshot.Attribute;
import org.ipro.design.RuntimeDesignSnapshot.Numbering;
import org.ipro.design.RuntimeDesignSnapshot.OwnedSection;
import org.ipro.design.RuntimeDesignSnapshot.NaturalKey;
import org.ipro.design.RuntimeDesignSnapshot.CatalogOwnerFacts;
import org.ipro.design.RuntimeDesignSnapshot.TypeFacts;
import org.ipro.design.RuntimeDesignSnapshot.Prerequisite;
import org.ipro.design.RuntimeDesignSnapshot.Status;
import org.ipro.design.RuntimeDesignSnapshot.Diagnostic;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.metadata.MetadataConsistencyStartupCheck;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.numbering.NumberingMetadataRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Backend-only projection of validated runtime owners. Never resolves or executes a draft type name. */

public final class EntityDesignRuntimeProjection {
    private final EntityDescriptorCatalog descriptors;
    private final MetadataResolver metadata;
    private final SectionMetadataRegistry sections;
    private final NumberingMetadataRegistry numbering;
    private final MetadataConsistencyStartupCheck startup;
    EntityDesignRuntimeProjection(EntityDescriptorCatalog descriptors, MetadataResolver metadata,
            SectionMetadataRegistry sections, NumberingMetadataRegistry numbering, MetadataConsistencyStartupCheck startup) {
        this.descriptors=descriptors; this.metadata=metadata; this.sections=sections; this.numbering=numbering; this.startup=startup;
    }
    /** Portable bridge: platform descriptor → design-core facts; no DTO crosses the module edge. */
    static CatalogOwnerFacts ownerFacts(org.ipro.data.EntityDescriptor descriptor) {
        return descriptor.owner() == null ? null
            : new CatalogOwnerFacts(descriptor.owner().property(), descriptor.owner().targetTypeId());
    }

    public RuntimeDesignSnapshot snapshot() {
        // Construction of this owner has already rejected invalid final metadata/classification.
        if (startup.diagnostics().stream().anyMatch(d -> d.severity().name().equals("ERROR")))
            throw new IllegalStateException("Cannot project invalid runtime metadata");
        Map<String,TypeFacts> types=new TreeMap<>();
        for (Class<?> type : descriptors.managedTypes()) {
            var descriptor=descriptors.descriptorOf(type); var classification=descriptors.classificationOf(type);
            var info=metadata.find(type);
            var list=info.map(m -> m.getListColumnPaths().stream().map(p -> p.getKey()).toList()).orElse(List.of());
            var selection=info.map(m -> m.getSelectColumnPaths().stream().map(p -> p.getKey()).toList()).orElse(List.of());
            var rowSection=sections.findByRow(type);
            var form=rowSection.map(s -> s.getFormFields().stream().map(f -> f.getName()).collect(java.util.stream.Collectors.toSet()))
                .orElseGet(() -> info.map(m -> m.getFormFields().stream().map(f -> f.getName()).collect(java.util.stream.Collectors.toSet())).orElse(Set.of()));
            var table=rowSection.map(s -> s.getGridFields().stream().map(f -> f.getName()).collect(java.util.stream.Collectors.toSet())).orElse(Set.of());
            var attributes=metadata.resolveStructuralAttributes(type).stream().sorted(Comparator.comparing(a -> a.name())).map(a -> {
                var numbered=numbering.find(type,a.name()).map(n -> {
                    var definition=n.definition();
                    return new Numbering(definition.role().name(),definition.scope(),definition.allowManual(),definition.period().name(),
                        definition.prefix(),definition.pattern(),definition.dateField());
                }).orElse(null);
                return new Attribute(type.getName()+"#"+a.name(),a.name(),a.declaringType().getName(),a.javaType().getName(),
                    a.technicalPurpose().name(),a.required(),a.requiredOrigin().name(),form.contains(a.name()),list.contains(a.name()),selection.contains(a.name()),table.contains(a.name()),numbered);
            }).toList();
            var owned=sections.forOwner(type).stream().map(s -> new OwnedSection(type.getName()+"#section:"+s.getKey(),
                s.getRowClass().getName(),s.getParentFieldName(),s.getPersistenceMode().name(),s.getRlsPolicy().name())).toList();
            var ownerFacts = ownerFacts(descriptor);
            var contract=InterningContracts.find(type);
            var keys=contract.map(c -> Arrays.stream(c.keys()).map(k -> new NaturalKey(k.constraint(),List.of(k.properties()),
                List.of(k.nullProperties()),k.normalization())).toList()).orElse(List.of());
            types.put(type.getName(),new TypeFacts(type.getName(),classification.kind().name(),classification.origin().name(),classification.symbol(),
                descriptor.exposure().name(),descriptor.capabilitiesOrigin().name(),descriptor.capabilitiesSymbol(),
                classification.identityOrigin().name(),classification.identitySymbol(),
                descriptor.capabilities().readScenarios().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),
                descriptor.capabilities().writes().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),classification.interned(),classification.recorderOwned(),
                sections.findByRow(type).map(s -> s.getOwnerClass().getName()).orElse(""),list,selection,attributes,owned,keys,
                contract.map(c -> c.creation()).orElse(""),contract.map(c -> List.of(c.specialOperations())).orElse(List.of()),
                ownerFacts));
        }
        // Explicit supported integration contract, checked against the reviewed API surface by ET9 gates.
        // Public Java visibility alone is not interpreted as an APP_API role.
        Map<String,Prerequisite> prerequisites=new TreeMap<>();
        prerequisites.put("STANDARD_CATALOG_APP_API",new Prerequisite(Status.AVAILABLE,"ET4 published StandardCatalogEntity APP_API; ET9 surface gate"));
        prerequisites.put("STANDARD_DOCUMENT_APP_API",new Prerequisite(Status.AVAILABLE,"ET4 published StandardDocumentEntity APP_API; ET9 surface gate"));
        prerequisites.put("ET0_ET7_RUNTIME",new Prerequisite(Status.AVAILABLE,"MetadataConsistencyStartupCheck and final EntityDescriptorCatalog"));
        prerequisites.put("ET8_INTERNING",new Prerequisite(Status.AVAILABLE,"Published InternedContract/NaturalKeyCreateSupport ET8 API; applied schema and generation gates remain separate"));
        prerequisites.put("ET6_ET7_REGISTER_CLASSIFICATION",new Prerequisite(Status.AVAILABLE,"Final classification requires validated register contributions"));
        prerequisites.put("REGISTER_ENGINE_OPERATIONS",new Prerequisite(Status.UNAVAILABLE,"New register recipe operations/draft contract not implemented; current runtime capabilities are preserved per type"));
        var diagnostics=startup.diagnostics().stream().map(d -> new Diagnostic(d.severity().name(),d.code(),d.entity(),
            d.field().isEmpty() ? d.entity() : d.entity()+"#"+d.field(),d.source(),d.message())).toList();
        String content=types.toString()+"|"+prerequisites+"|"+diagnostics;
        try {
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
            return new RuntimeDesignSnapshot("runtime-v1:"+hash,true,types,prerequisites,diagnostics);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

