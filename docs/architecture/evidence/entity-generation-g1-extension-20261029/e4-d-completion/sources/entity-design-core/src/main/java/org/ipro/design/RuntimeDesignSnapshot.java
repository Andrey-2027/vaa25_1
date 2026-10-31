package org.ipro.design;

import java.util.*;

/** Portable checked runtime facts, never a draft, Class, entity instance or generation approval. */
public record RuntimeDesignSnapshot(String snapshotId, boolean complete, Map<String, TypeFacts> types,
                                    Map<String, Prerequisite> prerequisites, List<Diagnostic> diagnostics) {
    public RuntimeDesignSnapshot(String snapshotId, boolean complete, Map<String,TypeFacts> types, Map<String,Prerequisite> prerequisites) {
        this(snapshotId,complete,types,prerequisites,List.of());
    }
    public record Diagnostic(String severity, String code, String typeId, String propertyId, String source, String message) {
        public Diagnostic { Objects.requireNonNull(severity); Objects.requireNonNull(code); Objects.requireNonNull(typeId);
            Objects.requireNonNull(propertyId); Objects.requireNonNull(source); Objects.requireNonNull(message); }
    }
    public enum Status { AVAILABLE, UNAVAILABLE, UNKNOWN }
    public record Prerequisite(Status status, String evidence) {
        public Prerequisite { Objects.requireNonNull(status); Objects.requireNonNull(evidence); }
    }
    public record Attribute(String propertyId, String name, String declaringTypeId, String javaType,
                            String technicalPurpose, boolean required, String requiredOrigin,
                            boolean form, boolean list, boolean selection, boolean table, Numbering numbering) {
        public Attribute {
            Objects.requireNonNull(propertyId); Objects.requireNonNull(name); Objects.requireNonNull(declaringTypeId);
            Objects.requireNonNull(javaType); Objects.requireNonNull(technicalPurpose); Objects.requireNonNull(requiredOrigin);
        }
    }
    public record Numbering(String role, List<String> scope, boolean allowManual, String period,
                            String prefix, String pattern, String dateField) {
        public Numbering { Objects.requireNonNull(role); scope=List.copyOf(scope); Objects.requireNonNull(period);
            Objects.requireNonNull(prefix); Objects.requireNonNull(pattern); Objects.requireNonNull(dateField); }
    }
    public record OwnedSection(String sectionId, String rowTypeId, String parentProperty,
                               String persistence, String rlsPolicy) {
        public OwnedSection { Objects.requireNonNull(sectionId); Objects.requireNonNull(rowTypeId);
            Objects.requireNonNull(parentProperty); Objects.requireNonNull(persistence); Objects.requireNonNull(rlsPolicy); }
    }
    public record NaturalKey(String constraint, List<String> properties, List<String> nullProperties, String normalization) {
        public NaturalKey { Objects.requireNonNull(constraint); properties=List.copyOf(properties);
            nullProperties=List.copyOf(nullProperties); Objects.requireNonNull(normalization); }
    }
    /** Portable resolved owner capability; never a platform DTO or a draft declaration. */
    public record CatalogOwnerFacts(String property, String targetTypeId) {
        public CatalogOwnerFacts { Objects.requireNonNull(property); Objects.requireNonNull(targetTypeId);
            if (property.isBlank() || targetTypeId.isBlank()) throw new IllegalArgumentException("Invalid catalog owner facts"); }
    }
    public record TypeFacts(String typeId, String kind, String kindOrigin, String kindSource, String exposure,
                            String capabilitiesOrigin, String capabilitiesSource, String identityOrigin, String identitySource,
                            Set<String> reads, Set<String> writes, boolean interned, boolean recorderOwned,
                            String sectionOwnerTypeId, List<String> listColumns, List<String> selectColumns,
                            List<Attribute> attributes, List<OwnedSection> sections, List<NaturalKey> naturalKeys,
                            String creationChannel, List<String> specialOperations, CatalogOwnerFacts owner) {
        /** Совместимый конструктор без owner: обычный справочник остаётся owner=null. */
        public TypeFacts(String typeId, String kind, String kindOrigin, String kindSource, String exposure,
                         String capabilitiesOrigin, String capabilitiesSource, String identityOrigin, String identitySource,
                         Set<String> reads, Set<String> writes, boolean interned, boolean recorderOwned,
                         String sectionOwnerTypeId, List<String> listColumns, List<String> selectColumns,
                         List<Attribute> attributes, List<OwnedSection> sections, List<NaturalKey> naturalKeys,
                         String creationChannel, List<String> specialOperations) {
            this(typeId, kind, kindOrigin, kindSource, exposure, capabilitiesOrigin, capabilitiesSource,
                identityOrigin, identitySource, reads, writes, interned, recorderOwned, sectionOwnerTypeId,
                listColumns, selectColumns, attributes, sections, naturalKeys, creationChannel,
                specialOperations, null);
        }
        public TypeFacts {
            Objects.requireNonNull(typeId); Objects.requireNonNull(kind); Objects.requireNonNull(kindOrigin);
            Objects.requireNonNull(kindSource); Objects.requireNonNull(exposure); Objects.requireNonNull(creationChannel);
            Objects.requireNonNull(capabilitiesOrigin); Objects.requireNonNull(capabilitiesSource);
            Objects.requireNonNull(identityOrigin); Objects.requireNonNull(identitySource);
            Objects.requireNonNull(sectionOwnerTypeId); listColumns=List.copyOf(listColumns); selectColumns=List.copyOf(selectColumns);
            reads=Collections.unmodifiableSet(new TreeSet<>(reads)); writes=Collections.unmodifiableSet(new TreeSet<>(writes));
            attributes=List.copyOf(attributes); sections=List.copyOf(sections); naturalKeys=List.copyOf(naturalKeys);
            specialOperations=List.copyOf(specialOperations);
            Set<String> ids=new HashSet<>();
            for (var attribute : attributes) {
                if (!attribute.propertyId().equals(typeId+"#"+attribute.name()) || !ids.add(attribute.propertyId()))
                    throw new IllegalArgumentException("Unstable/duplicate property id: "+attribute.propertyId());
            }
        }
    }
    public RuntimeDesignSnapshot {
        Objects.requireNonNull(snapshotId);
        types=Collections.unmodifiableMap(new TreeMap<>(types));
        prerequisites=Collections.unmodifiableMap(new TreeMap<>(prerequisites));
        diagnostics=List.copyOf(diagnostics);
        types.forEach((key,value) -> { if (!key.equals(value.typeId())) throw new IllegalArgumentException("Runtime type id mismatch"); });
        if (snapshotId.isBlank()) throw new IllegalArgumentException("Snapshot id required");
    }
    public static RuntimeDesignSnapshot unknown() { return new RuntimeDesignSnapshot("runtime:unknown",false,Map.of(),Map.of()); }
    public Prerequisite prerequisite(String id) {
        return prerequisites.getOrDefault(id,new Prerequisite(Status.UNKNOWN,"Runtime host has not supplied this fact"));
    }
}
