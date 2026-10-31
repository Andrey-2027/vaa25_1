package org.ipro.vaadin.explorer;

import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.JpaFieldFacts;
import org.ipro.metadata.StructuralAttribute;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.metadata.facet.FacetKey;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.ResolvedValue;

import java.lang.reflect.Field;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.ipro.vaadin.explorer.EntitySummary.*;

/** Internal projection of resolved metadata and existing summary facts; no registries or caches. */
final class EntityStructureFactsAssembler {
    record FieldInput(Class<?> type, List<FieldMetadataInfo> fields,
                      List<StructuralAttribute> structuralAttributes, List<FieldRow> labels,
                      List<FieldRow> form, List<FieldRow> grid, List<ColumnRow> list,
                      List<ColumnRow> selection, List<LookupRow> lookups) { }

    record SectionInput(TableSectionMetadataInfo metadata, ResolvedValue label, FieldInput fields) { }

    StructureFacts assemble(Class<?> rootType, FieldInput root, List<SectionInput> sections) {
        return assemble(rootType, root, sections, null);
    }

    StructureFacts assemble(Class<?> rootType, FieldInput root, List<SectionInput> sections,
            org.ipro.metadata.EntityMetadataInfo metadata) {
        List<OwnedSectionRow> rows = new ArrayList<>(sections.size());
        for (SectionInput input : sections) {
            TableSectionMetadataInfo section = input.metadata();
            OwnedSectionIdentity identity = new OwnedSectionIdentity(rootType,
                section.getParentFieldName(), section.getRowClass());
            String symbol = section.getRowClass().getName();
            String lineNumber = section.getLineNumberFieldName();
            rows.add(new OwnedSectionRow(identity,
                FacetKey.of(FacetKind.TABLE_SECTION, rootType, identity.ownerField() + "#" + identity.rowType().getName()),
                input.label(), integer(section.getOrder(), FactOrigin.DERIVED, symbol),
                integer(section.getMinRows(), FactOrigin.DERIVED, symbol),
                lineNumber == null ? null : ResolvedValue.fact(lineNumber, FactOrigin.DERIVED,
                    symbol(section.getLineNumberField())),
                ResolvedValue.fact(section.getPersistenceMode().name(), FactOrigin.DERIVED, symbol),
                ResolvedValue.fact(section.getRlsPolicy().name(), FactOrigin.DERIVED, symbol),
                fields(rootType, identity, input.fields())));
        }
        return new StructureFacts(true, fields(rootType, null, root), rows, presentation(rootType, metadata),
            org.ipro.form.CatalogOwnerForms.ownerOf(rootType));
    }

    private PresentationFacts presentation(Class<?> type, org.ipro.metadata.EntityMetadataInfo metadata) {
        var raw = type.getAnnotation(org.ipro.metadata.annotation.EntityMetadata.class);
        String method = "";
        if (org.ipro.metadata.HasDisplayName.class.isAssignableFrom(type)) {
            try { method = type.getMethod("getDisplayName").getDeclaringClass().getName() + "#getDisplayName"; }
            catch (NoSuchMethodException ignored) { /* No confirmed method. */ }
        }
        org.ipro.fetch.instance.InstanceName declaration = null;
        Class<?> owner = type;
        for (; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
            declaration = owner.getDeclaredAnnotation(org.ipro.fetch.instance.InstanceName.class);
            if (declaration != null) break;
        }
        String strategy = declaration != null ? "@InstanceName: "
            + (declaration.value().length == 0 ? "состав из displaySortFields (явно подключён @InstanceName)"
                : String.join(", ", declaration.value())) + "; разделитель «" + declaration.separator() + "»"
            : !method.isBlank() ? "Java-метод getDisplayName()" : "Платформенное представление Type#id";
        return new PresentationFacts(metadata != null ? metadata.getIcon() : raw == null ? "FILE" : raw.icon(),
            metadata != null ? metadata.getDisplaySortFields()
                : raw == null ? List.of() : List.of(raw.displaySortFields()), strategy, method,
            declaration != null ? owner.getName() + " · @InstanceName" : method);
    }

    private List<StructuralFieldRow> fields(Class<?> rootType, OwnedSectionIdentity section, FieldInput input) {
        Map<String, FieldRow> labels = index(input.labels(), FieldRow::name);
        Map<String, LookupRow> lookups = index(input.lookups(), LookupRow::fieldName);
        Map<String, FieldMetadataInfo> publishedFields = index(input.fields(), FieldMetadataInfo::getName);
        Map<String, StructuralAttribute> structuralFields = index(
            input.structuralAttributes(), StructuralAttribute::name);
        Map<String, List<FieldApplication>> applications = applications(input);

        LinkedHashMap<String, StructuralFieldRow> rows = new LinkedHashMap<>();
        for (StructuralAttribute attribute : structuralFields.values()) {
            FieldMetadataInfo published = publishedFields.get(attribute.name());
            rows.put(attribute.name(), published != null
                ? publishedField(rootType, section, input, published, attribute, labels, lookups, applications)
                : structuralField(rootType, section, input, attribute, labels, applications));
        }
        for (FieldMetadataInfo field : publishedFields.values()) {
            rows.computeIfAbsent(field.getName(), ignored ->
                publishedField(rootType, section, input, field, structuralFields.get(field.getName()),
                    labels, lookups, applications));
        }
        return rows.values().stream()
            .sorted(Comparator.comparingInt((StructuralFieldRow row) -> row.order().value())
                .thenComparing(row -> row.identity().fieldName()))
            .toList();
    }

    private StructuralFieldRow publishedField(
            Class<?> rootType, OwnedSectionIdentity section, FieldInput input,
            FieldMetadataInfo field, StructuralAttribute structural,
            Map<String, FieldRow> labels, Map<String, LookupRow> lookups,
            Map<String, List<FieldApplication>> applications) {
        Field declaration = field.getField();
        String symbol = symbol(declaration);
        ResolvedValue label = labels.containsKey(field.getName())
            ? labels.get(field.getName()).value()
            : ResolvedValue.fact(field.getLabel(), FactOrigin.DERIVED, symbol);
        return new StructuralFieldRow(new FieldIdentity(rootType, section, field.getName()),
            FacetKey.of(FacetKind.FIELD_STRUCTURE, input.type(), field.getName()),
            declaration.getDeclaringClass(), field.getJavaType(),
            new TypeFact(field.getResolvedType(), field.getTypeOrigin(),
                field.getTypeOrigin() == FactOrigin.JPA_MAPPING ? symbol(field.getPersistentMember()) : symbol),
            label, integer(field.getOrder(), FactOrigin.DERIVED, symbol),
            flag(field.isHidden(), FactOrigin.DERIVED, symbol),
            flag(field.isReadOnly(), FactOrigin.DERIVED, symbol),
            flag(field.isRequired(), field.getRequiredOrigin(),
                JpaFieldFacts.requiredSymbol(declaration, field.getPersistentMember(), field.getRequiredOrigin())),
            flag(field.isServerRequired(), field.getServerRequiredOrigin(),
                JpaFieldFacts.requiredSymbol(declaration, field.getPersistentMember(), field.getServerRequiredOrigin())),
            sizes(declaration, field.getPersistentMember()), storage(field.getPersistentMember()),
            applications.getOrDefault(field.getName(), List.of()),
            lookups.get(field.getName()), true,
            structural == null ? null : structural.persistentKind(),
            structural == null ? null : structural.accessMode(),
            structural == null ? null : structural.technicalPurpose());
    }

    private StructuralFieldRow structuralField(
            Class<?> rootType, OwnedSectionIdentity section, FieldInput input,
            StructuralAttribute attribute, Map<String, FieldRow> labels,
            Map<String, List<FieldApplication>> applications) {
        Field declaration = findField(input.type(), attribute.name());
        AnnotatedElement persistentMember = attribute.persistentMember() != null
            ? attribute.persistentMember() : declaration;
        String symbol = persistentMember != null
            ? symbol(persistentMember)
            : attribute.declaringType().getName() + "#" + attribute.name();
        ResolvedValue label = labels.containsKey(attribute.name())
            ? labels.get(attribute.name()).value()
            : ResolvedValue.fact(attribute.name(), FactOrigin.PLATFORM_DEFAULT, "");
        return new StructuralFieldRow(new FieldIdentity(rootType, section, attribute.name()),
            FacetKey.of(FacetKind.FIELD_STRUCTURE, input.type(), attribute.name()),
            attribute.declaringType(), attribute.javaType(),
            new TypeFact(attribute.platformType(), attribute.platformTypeOrigin(), symbol),
            label, integer(999, FactOrigin.PLATFORM_DEFAULT, ""),
            flag(false, FactOrigin.PLATFORM_DEFAULT, ""),
            flag(false, FactOrigin.PLATFORM_DEFAULT, ""),
            flag(attribute.required(), attribute.requiredOrigin(),
                JpaFieldFacts.requiredSymbol(declaration, persistentMember, attribute.requiredOrigin())),
            flag(attribute.required(), attribute.requiredOrigin(),
                JpaFieldFacts.requiredSymbol(declaration, persistentMember, attribute.requiredOrigin())),
            sizes(declaration, persistentMember), storage(persistentMember),
            applications.getOrDefault(attribute.name(), List.of()), null, false,
            attribute.persistentKind(), attribute.accessMode(), attribute.technicalPurpose());
    }

    private static List<SizeConstraint> sizes(Field backingField, AnnotatedElement member) {
        List<SizeConstraint> facts = new ArrayList<>(sizes(member));
        if (backingField != null && !backingField.equals(member)) {
            facts.addAll(sizes(backingField));
        }
        return List.copyOf(facts);
    }

    private static List<SizeConstraint> sizes(AnnotatedElement declaration) {
        if (declaration == null) {
            return List.of();
        }
        String symbol = symbol(declaration);
        return JpaFieldFacts.sizeFacts(declaration).stream()
            .map(size -> new SizeConstraint(size.min(), size.max(), size.groups(),
                ResolvedValue.fact("@Size", FactOrigin.BEAN_VALIDATION, symbol)))
            .toList();
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // continue with superclass
            }
        }
        return null;
    }

    private static Map<String, List<FieldApplication>> applications(FieldInput input) {
        Map<String, FieldMetadataInfo> fields = index(input.fields(), FieldMetadataInfo::getName);
        Map<String, List<FieldApplication>> result = new LinkedHashMap<>();
        for (FieldRow row : input.form()) {
            FieldMetadataInfo field = fields.get(row.name());
            if (field != null) {
                add(result, row.name(), new FieldApplication(FieldApplicationKind.FORM, row.key(), row.name(),
                    row.value(), field.getOrder()));
            }
        }
        for (FieldRow row : input.grid()) {
            FieldMetadataInfo field = fields.get(row.name());
            if (field != null) {
                add(result, row.name(), new FieldApplication(FieldApplicationKind.GRID, row.key(), row.name(),
                    row.value(), field.getGridOrder()));
            }
        }
        columns(result, input.list(), FieldApplicationKind.LIST);
        columns(result, input.selection(), FieldApplicationKind.SELECTION);
        return result;
    }

    private static void columns(Map<String, List<FieldApplication>> result, List<ColumnRow> columns,
                                FieldApplicationKind kind) {
        for (int position = 0; position < columns.size(); position++) {
            ColumnRow column = columns.get(position);
            String rootField = column.path().split("\\.", 2)[0];
            add(result, rootField, new FieldApplication(kind, column.key(), column.path(),
                column.value(), position + 1));
        }
    }

    private static void add(Map<String, List<FieldApplication>> result, String field, FieldApplication application) {
        result.computeIfAbsent(field, ignored -> new ArrayList<>()).add(application);
    }

    private static StorageFacts storage(AnnotatedElement field) {
        if (field == null) {
            return new StorageFacts(List.of(), null);
        }
        JpaFieldFacts.StorageFact facts = JpaFieldFacts.storageFacts(field);
        String symbol = symbol(field);
        List<NullabilityFact> nullability = facts.nullability().stream()
            .map(fact -> new NullabilityFact(NullabilityKind.valueOf(fact.kind().name()),
                flag(fact.allowsNull(), FactOrigin.JPA_MAPPING, symbol)))
            .toList();
        JpaFieldFacts.ColumnFact column = facts.column();
        return new StorageFacts(nullability, column == null ? null : new ColumnStorage(
            integer(column.length(), FactOrigin.JPA_MAPPING, symbol),
            integer(column.precision(), FactOrigin.JPA_MAPPING, symbol),
            integer(column.scale(), FactOrigin.JPA_MAPPING, symbol)));
    }

    private static String symbol(AnnotatedElement element) {
        Member member = (Member) element;
        return member.getDeclaringClass().getName() + "#" + member.getName();
    }

    private static String source(FactOrigin origin, String symbol) {
        return origin == FactOrigin.PLATFORM_DEFAULT ? "" : symbol;
    }

    private static FlagFact flag(boolean value, FactOrigin origin, String symbol) {
        return new FlagFact(value, origin, symbol);
    }

    private static IntFact integer(int value, FactOrigin origin, String symbol) {
        return new IntFact(value, origin, symbol);
    }

    private static <T> Map<String, T> index(List<T> rows, Function<T, String> key) {
        return rows.stream().collect(Collectors.toMap(key, Function.identity()));
    }
}
