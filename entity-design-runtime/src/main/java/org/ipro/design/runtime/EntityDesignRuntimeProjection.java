package org.ipro.design.runtime;

import org.ipro.crud.InterningContracts;
import org.ipro.design.RuntimeDesignSnapshot;
import org.ipro.design.RuntimeDesignSnapshot.Attribute;
import org.ipro.design.RuntimeDesignSnapshot.Numbering;
import org.ipro.design.RuntimeDesignSnapshot.OwnedSection;
import org.ipro.design.RuntimeDesignSnapshot.NaturalKey;
import org.ipro.design.RuntimeDesignSnapshot.CatalogOwnerFacts;
import org.ipro.design.RuntimeDesignSnapshot.RegisterFacts;
import org.ipro.design.RuntimeDesignSnapshot.TypeFacts;
import org.ipro.design.RuntimeDesignSnapshot.Prerequisite;
import org.ipro.design.RuntimeDesignSnapshot.Status;
import org.ipro.design.RuntimeDesignSnapshot.Diagnostic;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.metadata.MetadataConsistencyStartupCheck;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.register.RegisterDescriptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Backend-only projection of validated runtime owners. Never resolves or executes a draft type name. */

public final class EntityDesignRuntimeProjection {
    private static final String REGISTER_KIND = "INFORMATION_REGISTER";
    private static final String REGISTER_FACTS = "REGISTER_FACTS";
    private final EntityDescriptorCatalog descriptors;
    private final MetadataResolver metadata;
    private final SectionMetadataRegistry sections;
    private final NumberingMetadataRegistry numbering;
    private final MetadataConsistencyStartupCheck startup;
    private final RegisterFactsSource registers;
    EntityDesignRuntimeProjection(EntityDescriptorCatalog descriptors, MetadataResolver metadata,
            SectionMetadataRegistry sections, NumberingMetadataRegistry numbering, MetadataConsistencyStartupCheck startup) {
        this(descriptors,metadata,sections,numbering,startup,null);
    }
    EntityDesignRuntimeProjection(EntityDescriptorCatalog descriptors, MetadataResolver metadata,
            SectionMetadataRegistry sections, NumberingMetadataRegistry numbering, MetadataConsistencyStartupCheck startup,
            RegisterFactsSource registers) {
        this.descriptors=descriptors; this.metadata=metadata; this.sections=sections; this.numbering=numbering;
        this.startup=startup; this.registers=registers;
    }
    /** Portable bridge: platform descriptor → design-core facts; no DTO crosses the module edge. */
    static CatalogOwnerFacts ownerFacts(org.ipro.data.EntityDescriptor descriptor) {
        return descriptor.owner() == null ? null
            : new CatalogOwnerFacts(descriptor.owner().property(), descriptor.owner().targetTypeId());
    }
    /** Portable bridge: validated register declaration → design-core facts; no Class or platform DTO crosses. */
    static RegisterFacts registerFacts(RegisterDescriptor descriptor) {
        String typeId = descriptor.entityType().getName();
        return new RegisterFacts(typeId, descriptor.entityName(), descriptor.displayName(),
            descriptor.period().map(RegisterDescriptor.FieldDescriptor::name).orElse(""),
            descriptor.periodicity().name(),
            descriptor.period().map(value -> value.columnName().orElse(null)).orElse(null),
            descriptor.dimensions().stream().map(value -> registerRole(typeId, value)).toList(),
            descriptor.resources().stream().map(value -> registerRole(typeId, value)).toList(),
            descriptor.dimensionGroupColumns(), descriptor.rowKeyColumns(), descriptor.winnerOrderFields(),
            descriptor.recorderTypeCodes(), descriptor.recorded(), descriptor.genericWritesAllowed(),
            descriptor.keyChangeAllowed(), descriptor.keyWriteAllowed());
    }
    private static RegisterFacts.Role registerRole(String typeId, RegisterDescriptor.FieldDescriptor field) {
        var mapping = switch (field.mapping()) {
            case BASIC -> RegisterFacts.Mapping.BASIC;
            case TO_ONE -> RegisterFacts.Mapping.TO_ONE;
            case OTHER -> RegisterFacts.Mapping.OTHER;
        };
        return new RegisterFacts.Role(typeId+"#"+field.name(), field.name(), field.javaType().getName(),
            field.columnName().orElse(null), mapping);
    }
    /** {@code null} means the source was not supplied; an empty map means checked and no declarations. */
    static Prerequisite registerPrerequisite(Map<String,RegisterFacts> facts) {
        return facts == null
            ? new Prerequisite(Status.UNAVAILABLE,"Validated RegisterRegistry was not supplied; register facts are missing")
            : new Prerequisite(Status.AVAILABLE,"Validated RegisterRegistry: "+facts.size()+" register declaration(s)");
    }
    /**
     * A classified register must arrive with its validated facts; a missing source or a declaration
     * absent from the registry is a fact gap, never an empty ready snapshot. Non-register kinds carry none.
     */
    static RegisterFacts attachableRegisterFacts(String typeId, String kind, Map<String,RegisterFacts> facts) {
        if (!REGISTER_KIND.equals(kind)) return null;
        if (facts == null) throw new IllegalStateException("Register facts were not supplied for classified register "+typeId);
        RegisterFacts found = facts.get(typeId);
        if (found == null) throw new IllegalStateException("Classified register "+typeId+" has no validated declaration in RegisterRegistry");
        return found;
    }
    /** Content identity: register roles, key order, periodicity and write capabilities participate. */
    static String snapshotId(Map<String,TypeFacts> types, Map<String,Prerequisite> prerequisites, List<Diagnostic> diagnostics) {
        String content=types+"|"+prerequisites+"|"+diagnostics;
        try {
            return "runtime-v1:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public RuntimeDesignSnapshot snapshot() {
        // Construction of this owner has already rejected invalid final metadata/classification.
        if (startup.diagnostics().stream().anyMatch(d -> d.severity().name().equals("ERROR")))
            throw new IllegalStateException("Cannot project invalid runtime metadata");
        Map<String,RegisterFacts> registerFacts = registers == null ? null : registers.facts();
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
            var register = attachableRegisterFacts(type.getName(), classification.kind().name(), registerFacts);
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
                ownerFacts,register));
        }
        if (registerFacts != null) {
            for (String declared : registerFacts.keySet()) {
                if (!types.containsKey(declared))
                    throw new IllegalStateException("Validated register "+declared+" is absent from the managed type catalog");
            }
        }
        // Explicit supported integration contract, checked against the reviewed API surface by ET9 gates.
        // Public Java visibility alone is not interpreted as an APP_API role.
        Map<String,Prerequisite> prerequisites=new TreeMap<>();
        prerequisites.put("STANDARD_CATALOG_APP_API",new Prerequisite(Status.AVAILABLE,"ET4 published StandardCatalogEntity APP_API; ET9 surface gate"));
        prerequisites.put("STANDARD_DOCUMENT_APP_API",new Prerequisite(Status.AVAILABLE,"ET4 published StandardDocumentEntity APP_API; ET9 surface gate"));
        prerequisites.put("ET0_ET7_RUNTIME",new Prerequisite(Status.AVAILABLE,"MetadataConsistencyStartupCheck and final EntityDescriptorCatalog"));
        prerequisites.put("ET8_INTERNING",new Prerequisite(Status.AVAILABLE,"Published InternedContract/NaturalKeyCreateSupport ET8 API; applied schema and generation gates remain separate"));
        prerequisites.put("ET6_ET7_REGISTER_CLASSIFICATION",new Prerequisite(Status.AVAILABLE,"Final classification requires validated register contributions"));
        prerequisites.put(REGISTER_FACTS,registerPrerequisite(registerFacts));
        prerequisites.put("REGISTER_ENGINE_OPERATIONS",new Prerequisite(Status.UNAVAILABLE,"New register recipe operations/draft contract not implemented; current runtime capabilities are preserved per type"));
        var diagnostics=startup.diagnostics().stream().map(d -> new Diagnostic(d.severity().name(),d.code(),d.entity(),
            d.field().isEmpty() ? d.entity() : d.entity()+"#"+d.field(),d.source(),d.message())).toList();
        return new RuntimeDesignSnapshot(snapshotId(types,prerequisites,diagnostics),true,types,prerequisites,diagnostics);
    }
}
